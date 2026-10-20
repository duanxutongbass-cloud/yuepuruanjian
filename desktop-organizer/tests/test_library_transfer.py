from contextlib import closing
import json
from pathlib import Path
import shutil
import sqlite3
import zipfile

import pytest

from score_organizer.services import library_transfer as transfer
from score_organizer.services import runtime_services
from score_organizer.services.grouping_service import warning_fingerprint
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.storage.database import Database
from score_organizer.storage.expanded_database import activate_candidate
from score_organizer.main import remember_expanded, remember_portable, startup_database
from test_library_upgrade_snapshot import make_library, contents, db_state


def rows(path, sql):
    with closing(sqlite3.connect(path)) as connection:
        return connection.execute(sql).fetchall()


def fixture_library(tmp_path, expanded=False):
    old = tmp_path / '旧电脑桌面'
    old.mkdir()
    db, root, sources = make_library(old)
    if expanded:
        db = activate_candidate(prepare_relocated_library(db.path, old / '扩位母库'))
        root = db.path.parent / 'files'
        new = []
        for instrument in ('01', '02', '03'):
            path = old / f'{instrument}-01-1000-已校对曲目.pdf'
            path.write_bytes(b'%PDF expanded fixture ' + instrument.encode())
            new.append(path)
        importer = runtime_services.importer(db)
        batch = importer.import_paths(new)
        archive = runtime_services.archive(db, root)
        archive.commit_plan(batch, archive.build_plan(batch))
    source = old / '0401人工校对草稿.pdf'
    source.write_bytes(b'%PDF manual draft with durable decisions')
    batch = runtime_services.importer(db).import_paths([source])
    details = {'source_paths': [str(source)], 'description': '保留人工核对说明'}
    with db.transaction() as connection:
        connection.execute('INSERT INTO warning(batch_id,code,fingerprint,severity,state,details) VALUES(?,?,?,?,?,?)',
            (batch, 'transfer-manual', warning_fingerprint('transfer-manual', details), 'warning', '已确认', json.dumps(details)))
    db.set_setting('last_reset_backup', str(old / '历史清空备份'))
    db.set_setting('team_publisher_id', 'existing-publisher-identity')
    return db, root, old


@pytest.mark.parametrize('expanded', [False, True])
def test_real_roundtrip_without_old_computer_preserves_results_and_can_continue_archiving(tmp_path, monkeypatch, expanded):
    db, root, old = fixture_library(tmp_path, expanded)
    state, tree = db_state(db.path), contents(root)
    song_rows = rows(db.path, 'SELECT * FROM song')
    history = rows(db.path, 'SELECT * FROM operation_log')
    package = tmp_path / '整库迁移.zip'
    report = transfer.export_library(db, package)
    assert report['archivedScores'] == (6 if expanded else 3)
    assert db_state(db.path) == state and contents(root) == tree
    preview = transfer.inspect_package(package)
    assert preview['counts']['songs'] == report['songs']
    # Simulate a wholly inaccessible old PC/drive, including originals and DB.
    shutil.move(old, tmp_path / '旧电脑已离线')
    target = tmp_path / '新电脑桌面' / '迁移母库'
    target.parent.mkdir()
    restored = transfer.restore_library(package, target)
    assert rows(restored.path, 'SELECT * FROM song') == song_rows
    assert rows(restored.path, 'SELECT * FROM operation_log') == history
    assert contents(target / 'files') == tree
    assert restored.get_setting('team_publisher_id') == 'existing-publisher-identity'
    assert restored.get_setting('last_reset_backup') == ''
    assert restored._numbering_version == (2 if expanded else 1)
    paths = rows(restored.path, 'SELECT source_path FROM import_item UNION SELECT source_path FROM score')
    assert all(Path(path).is_relative_to(target / 'sources') and Path(path).is_file() for (path,) in paths)
    detail, decision = rows(restored.path, "SELECT details,state FROM warning WHERE code='transfer-manual'")[0]
    assert decision == '已确认' and json.loads(detail)['description'] == '保留人工核对说明'
    assert Path(json.loads(detail)['source_paths'][0]).is_file()
    archive = runtime_services.archive(restored, target / 'files')
    assert all(path.is_file() for score in restored.list_archived_scores() for path in archive.score_paths(score))
    batch = rows(restored.path, 'SELECT max(batch_id) FROM import_item')[0][0]
    assert runtime_services.report(restored).build_song_reports(batch)
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE', str(tmp_path / '新电脑启动记录'))
    (remember_expanded if expanded else remember_portable)(restored.path)
    reopened = startup_database()
    reopened.initialize()
    assert reopened.path == restored.path and reopened.count_scores() == report['archivedScores']
    # Explicitly archive a new independent three-part song after restoration.
    fresh = []
    for code in ('01', '02', '03'):
        path = tmp_path / f'{code}01迁移后新曲.pdf'
        path.write_bytes(b'%PDF after move ' + code.encode())
        fresh.append(path)
    batch = runtime_services.importer(reopened).import_paths(fresh)
    archive = runtime_services.archive(reopened, target / 'files')
    plans = archive.build_plan(batch)
    assert plans[0].seq == '002'
    archive.commit_plan(batch, plans)
    assert reopened.count_scores() == report['archivedScores'] + 3


def test_missing_archived_original_can_be_transferred_from_verified_managed_copy(tmp_path):
    db, root, sources = make_library(tmp_path)
    sources[0].unlink()
    package = tmp_path / 'recovered.zip'
    assert transfer.export_library(db, package)['recoveredSources'] == 1
    restored = transfer.restore_library(package, tmp_path / 'restored')
    assert restored.count_scores() == 3


@pytest.mark.parametrize('damage', ['changed_original', 'missing_managed', 'missing_draft'])
def test_incomplete_or_changed_library_never_produces_success_package(tmp_path, damage):
    db, root, old = fixture_library(tmp_path)
    if damage == 'changed_original':
        (old / '0101旧曲.pdf').write_bytes(b'changed')
    elif damage == 'missing_managed':
        next((root / '按曲目分类').rglob('*.pdf')).unlink()
    else:
        (old / '0401人工校对草稿.pdf').unlink()
    before = db_state(db.path)
    package = tmp_path / 'blocked.zip'
    with pytest.raises((ValueError, OSError)):
        transfer.export_library(db, package)
    assert not package.exists() and db_state(db.path) == before


def test_empty_library_is_valid_and_existing_package_or_target_never_overwritten(tmp_path):
    db = Database(tmp_path / 'empty.sqlite3')
    db.initialize()
    package = tmp_path / 'empty.zip'
    transfer.export_library(db, package)
    before = package.read_bytes()
    with pytest.raises(ValueError):
        transfer.export_library(db, package)
    assert package.read_bytes() == before
    target = tmp_path / 'existing'
    target.mkdir()
    with pytest.raises(ValueError):
        transfer.restore_library(package, target)
    assert list(target.iterdir()) == []
    assert transfer.restore_library(package, tmp_path / 'new').count_scores() == 0


def rewrite_package(source, target, mutate):
    with zipfile.ZipFile(source) as archive:
        parts = {name: archive.read(name) for name in archive.namelist()}
    mutate(parts)
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, value in parts.items():
            archive.writestr(name, value)


@pytest.mark.parametrize('damage', ['bytes', 'missing', 'extra', 'traversal', 'drive', 'backslash', 'case_alias', 'duplicate_json', 'future', 'wrong_count'])
def test_bad_packages_refused_without_switching_or_touching_existing_library(tmp_path, monkeypatch, damage):
    db, root, _ = make_library(tmp_path)
    package = tmp_path / 'good.zip'
    transfer.export_library(db, package)
    def mutate(parts):
        pdf = next(name for name in parts if name.endswith('.pdf'))
        if damage == 'bytes':
            parts[pdf] = b'x' * len(parts[pdf])
        elif damage == 'missing':
            del parts[pdf]
        elif damage == 'extra':
            parts['extra.pdf'] = b'unknown'
        elif damage in ('traversal', 'drive', 'backslash', 'case_alias'):
            name = {'traversal': '../escaped.pdf', 'drive': 'C:/escaped.pdf', 'backslash': 'a\\b.pdf', 'case_alias': pdf.upper()}[damage]
            parts[name] = b'bad'
        elif damage == 'duplicate_json':
            parts['manifest.json'] = b'{"version":1,"version":1}'
        else:
            manifest = json.loads(parts['manifest.json'])
            if damage == 'future':
                manifest['version'] = 2
            else:
                manifest['counts']['songs'] += 1
            parts['manifest.json'] = json.dumps(manifest).encode()
    bad = tmp_path / 'bad.zip'
    rewrite_package(package, bad, mutate)
    before, tree = db_state(db.path), contents(root)
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE', str(tmp_path / 'profile'))
    with pytest.raises((ValueError, KeyError, zipfile.BadZipFile)):
        transfer.inspect_package(bad)
    with pytest.raises((ValueError, KeyError, zipfile.BadZipFile)):
        transfer.restore_library(bad, tmp_path / 'rejected')
    assert not (tmp_path / 'profile/current-library.json').exists()
    assert not (tmp_path / 'escaped.pdf').exists()
    assert db_state(db.path) == before and contents(root) == tree
    assert not (tmp_path / 'rejected/migration-completed.json').exists()


def test_failure_or_cancel_during_restore_never_activates_partial_copy(tmp_path):
    db, _, _ = make_library(tmp_path)
    package = tmp_path / 'good.zip'
    transfer.export_library(db, package)
    def cancel(message):
        if message.startswith('正在恢复'):
            raise InterruptedError('user cancelled')
    with pytest.raises(InterruptedError):
        transfer.restore_library(package, tmp_path / 'partial', progress=cancel)
    assert (tmp_path / 'partial/migration-failed.json').exists()
    assert not (tmp_path / 'partial/portable-runtime.json').exists()


def test_concurrent_edits_abort_export_and_cannot_publish_mixed_snapshot(tmp_path):
    db, root, _ = make_library(tmp_path)
    edited = False
    def change(message):
        nonlocal edited
        if message.startswith('正在压缩') and not edited:
            edited = True
            db.set_setting('late-change', 'do not lose this')
    with pytest.raises(ValueError, match='记录已变化'):
        transfer.export_library(db, tmp_path / 'blocked.zip', progress=change)
    assert edited and not (tmp_path / 'blocked.zip').exists()


def test_lost_restored_library_never_silently_creates_empty_replacement(tmp_path, monkeypatch):
    db, _, _ = make_library(tmp_path)
    package = tmp_path / 'good.zip'
    transfer.export_library(db, package)
    restored = transfer.restore_library(package, tmp_path / 'new')
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE', str(tmp_path / 'profile'))
    remember_portable(restored.path)
    restored.path.unlink()
    with pytest.raises(ValueError, match='不会新建空库'):
        startup_database().initialize()
    assert not restored.path.exists()


def test_export_destination_inside_library_is_rejected_before_any_new_files(tmp_path):
    db, root, _ = make_library(tmp_path)
    before = contents(root)
    with pytest.raises(ValueError, match='目录以外'):
        transfer.export_library(db, root / 'nested.zip')
    assert contents(root) == before


@pytest.mark.parametrize('kind', ['review', 'legacy_duplicate', 'unused'])
def test_pending_decisions_and_restore_work_after_old_pc_disappears(tmp_path, kind):
    from test_historical_manifest_relocation import pending_library
    old = tmp_path / 'old'
    old.mkdir()
    db, root, originals, stored, service_type, archived = pending_library(old, kind)
    package = tmp_path / 'pending.zip'
    transfer.export_library(db, package)
    shutil.move(old, tmp_path / 'offline-old-pc')
    target = tmp_path / 'new'
    restored = transfer.restore_library(package, target)
    service = service_type(restored, target / 'files')
    entries = service.list_pending()
    assert len(entries) == 2
    assert all(entry['pdf_path'].is_relative_to(target / 'files') for entry in entries)
    assert all(entry['pdf_path'].is_file() for entry in entries)
    batch = service.restore_to_draft([entries[0]])
    drafts = restored.list_import_items(batch)
    assert len(drafts) == 1 and Path(drafts[0]['source_path']).is_file()
    assert len(service.list_pending()) == 1
