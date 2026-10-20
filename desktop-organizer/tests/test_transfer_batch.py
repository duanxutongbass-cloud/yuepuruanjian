from contextlib import closing
import json
import hashlib
import importlib.util
from pathlib import Path
import shutil
import sqlite3
import zipfile

import pytest

from score_organizer.services.batch_library_transfer import export_library, inspect_package, restore_library
from score_organizer.services import runtime_services
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.transfer_batch import PENDING
from score_organizer.storage.database import Database
from score_organizer.storage.expanded_database import activate_candidate
from test_library_upgrade_snapshot import contents, db_state


def library(tmp_path, expanded=False):
    old = tmp_path / 'old-pc'
    old.mkdir()
    db = Database(old / 'library.sqlite3')
    db.initialize()
    output = old / 'managed'
    db.set_setting('output_root', str(output))
    paths = []
    for title in ('修女也疯狂', '正常曲目甲', '正常曲目乙'):
        for instrument in ('01', '02', '03'):
            source = old / f'{instrument}01{title}.pdf'
            source.write_bytes(('%PDF synthetic ' + title + instrument).encode())
            paths.append(source)
    batch = runtime_services.importer(db).import_paths(paths)
    archive = runtime_services.archive(db, output)
    archive.commit_plan(batch, archive.build_plan(batch))
    if expanded:
        db = activate_candidate(prepare_relocated_library(db.path, old / 'expanded'))
        output = db.path.parent / 'files'
    return db, output, old, paths


@pytest.mark.parametrize('expanded', [False, True])
def test_multiple_bad_songs_skipped_healthy_song_migrates_and_numbering_remains_reserved(tmp_path, expanded):
    db, output, old, paths = library(tmp_path, expanded)
    first = next(score for score in db.list_archived_scores() if score['display_title'] == '修女也疯狂' and score['instrument_code'] == '01')
    # Actual independent source modification, plus a bad managed copy in a
    # different song: neither can abort the third, healthy song.
    Path(first['source_path']).write_bytes(b'%PDF changed after manual correction')
    second = next(score for score in db.list_archived_scores() if score['display_title'] == '正常曲目甲')
    archive = runtime_services.archive(db, output)
    damaged = archive.score_paths(second)[0]
    damaged.write_bytes(b'%PDF wrong managed copy')
    original_state, original_files = db_state(db.path), contents(output)
    package = tmp_path / 'batch.zip'
    result = export_library(db, package)
    assert result['batch']['usableSongs'] == 1
    assert result['batch']['skippedSongs'] == 2
    assert result['batch']['usableScores'] == 3
    assert '修女也疯狂' in result['report'] and '第一小提琴' in result['report'] and '正常曲目甲' in result['report']
    assert db_state(db.path) == original_state and contents(output) == original_files
    inspected = inspect_package(package)
    assert inspected['version'] == 2 and inspected['batch'] == result['batch']
    shutil.move(old, tmp_path / 'old-pc-offline')
    restored = restore_library(package, tmp_path / 'new-pc')
    assert [row['display_title'] for row in restored.list_archived_scores()] == ['正常曲目乙'] * 3
    assert restored.allocate_next_sequence() == '004'
    with restored.session() as connection:
        assert connection.execute("SELECT count(*) FROM song WHERE status=?", (PENDING,)).fetchone()[0] == 2
        assert connection.execute('SELECT count(*) FROM score').fetchone()[0] == 9
    for row in restored.list_archived_scores():
        assert all(path.is_file() for path in runtime_services.archive(restored, restored.get_setting('output_root')).score_paths(row))
    assert (restored.path.parent / 'migration-report.txt').read_text('utf-8') == result['report']
    assert (restored.path.parent / 'source-snapshot.sqlite3').is_file()
    # No fake replacement PDF for the deferred source.
    with restored.session() as connection:
        source = connection.execute('SELECT source_path FROM score WHERE id=?', (first['id'],)).fetchone()[0]
    assert not Path(source).exists()
    # A pending identity must never be silently recreated with a different ID/number.
    fresh = []
    for code in ('01', '02', '03'):
        path = tmp_path / f'{code}01修女也疯狂.pdf'
        path.write_bytes(('%PDF replacement for future review ' + code).encode())
        fresh.append(path)
    batch = runtime_services.importer(restored).import_paths(fresh)
    assert runtime_services.archive(restored, restored.get_setting('output_root')).build_plan(batch) == []


def test_source_permission_error_is_collected_and_other_songs_continue(tmp_path, monkeypatch):
    from score_organizer.services import transfer_batch
    db, output, _, _ = library(tmp_path)
    first = next(score for score in db.list_archived_scores() if score['display_title'] == '修女也疯狂')
    unreadable = runtime_services.archive(db, output).score_paths(first)[0]
    real_read = transfer_batch._read_hash
    def read(path, *args):
        if path == unreadable:
            raise PermissionError('synthetic source permission denied')
        return real_read(path, *args)
    monkeypatch.setattr(transfer_batch, '_read_hash', read)
    result = export_library(db, tmp_path / 'permission.zip')
    assert result['batch']['usableSongs'] == 2 and result['batch']['skippedSongs'] == 1
    assert 'permission denied' in result['report']
    assert len(restore_library(tmp_path / 'permission.zip', tmp_path / 'restored').list_archived_scores()) == 6


def test_whole_bad_draft_group_is_pending_other_draft_still_archives(tmp_path):
    db, output, _, _ = library(tmp_path)
    paths = []
    for title in ('坏草稿', '好草稿'):
        for code in ('01', '02', '03'):
            path = tmp_path / f'{code}01{title}.pdf'
            path.write_bytes(('%PDF draft ' + title + code).encode())
            paths.append(path)
    batch = runtime_services.importer(db).import_paths(paths)
    paths[0].unlink()
    result = export_library(db, tmp_path / 'drafts.zip')
    assert result['batch']['skippedDraftGroups'] == 1 and len(result['batch']['pendingItems']) == 3
    restored = restore_library(tmp_path / 'drafts.zip', tmp_path / 'restored')
    archive = runtime_services.archive(restored, restored.get_setting('output_root'))
    plans = archive.build_plan(batch)
    assert len(plans) == 1 and plans[0].title == '好草稿'
    archive.commit_plan(batch, plans)
    remaining = restored.list_import_items(batch)
    assert len(remaining) == 3 and all(row['status'] == PENDING for row in remaining)


def test_all_songs_bad_produces_honest_zero_success_report_and_retains_all_records(tmp_path):
    db, output, _, _ = library(tmp_path)
    archive = runtime_services.archive(db, output)
    for title in ('修女也疯狂', '正常曲目甲', '正常曲目乙'):
        row = next(score for score in db.list_archived_scores() if score['display_title'] == title)
        archive.score_paths(row)[0].unlink()
    result = export_library(db, tmp_path / 'all-pending.zip')
    assert result['batch']['usableSongs'] == 0 and result['batch']['skippedSongs'] == 3
    restored = restore_library(tmp_path / 'all-pending.zip', tmp_path / 'new')
    assert restored.list_archived_scores() == []
    assert restored.count_scores() == 9 and restored.allocate_next_sequence() == '004'
    assert '0首 / 0份' in result['report']


def test_healthy_and_legacy_packages_still_restore_normally(tmp_path):
    from score_organizer.services.library_transfer import export_library as legacy_export
    db, _, _, _ = library(tmp_path)
    healthy = export_library(db, tmp_path / 'healthy.zip')
    assert healthy['batch']['skippedSongs'] == 0 and healthy['batch']['usableSongs'] == 3
    legacy_export(db, tmp_path / 'legacy.zip')
    restored = restore_library(tmp_path / 'legacy.zip', tmp_path / 'legacy-restored')
    assert len(restored.list_archived_scores()) == 9


def test_forged_skip_list_is_rejected_even_when_zip_itself_is_valid(tmp_path):
    db, _, _, _ = library(tmp_path)
    package = tmp_path / 'good.zip'
    export_library(db, package)
    forged = tmp_path / 'forged.zip'
    with zipfile.ZipFile(package) as source, zipfile.ZipFile(forged, 'w') as target:
        for name in source.namelist():
            data = source.read(name)
            if name == 'manifest.json':
                manifest = json.loads(data)
                manifest['batch']['pendingSongs'] = [1]
                manifest['batch']['usableSongs'] = 2
                manifest['batch']['skippedSongs'] = 1
                data = json.dumps(manifest).encode()
            target.writestr(name, data)
    with pytest.raises(ValueError, match='待处理清单'):
        restore_library(forged, tmp_path / 'rejected')
    assert not (tmp_path / 'rejected/portable-runtime.json').exists()


def test_destination_write_error_is_global_and_not_mislabeled_as_bad_song(tmp_path, monkeypatch):
    from score_organizer.services import transfer_batch
    db, _, _, _ = library(tmp_path)
    real_read = transfer_batch._read_hash
    def full_disk(path, expected, destination=None):
        if destination is not None:
            raise OSError('synthetic destination disk full')
        return real_read(path, expected)
    monkeypatch.setattr(transfer_batch, '_read_hash', full_disk)
    with pytest.raises(OSError, match='disk full'):
        export_library(db, tmp_path / 'not-success.zip')
    assert not (tmp_path / 'not-success.zip').exists()


def test_pending_state_and_number_reservations_survive_a_second_transfer(tmp_path):
    db, output, _, _ = library(tmp_path)
    row = db.list_archived_scores()[0]
    runtime_services.archive(db, output).score_paths(row)[0].unlink()
    export_library(db, tmp_path / 'first.zip')
    restored = restore_library(tmp_path / 'first.zip', tmp_path / 'restored')
    second = export_library(restored, tmp_path / 'second.zip')
    assert second['batch']['skippedSongs'] == 1 and second['batch']['usableSongs'] == 2
    again = restore_library(tmp_path / 'second.zip', tmp_path / 'again')
    assert len(again.list_archived_scores()) == 6 and again.allocate_next_sequence() == '004'


@pytest.mark.parametrize('bad_metadata', ['missing_hash', 'relative_path', 'conflicting_hash'])
def test_bad_source_metadata_is_deferred_per_song_without_discarding_rows(tmp_path, bad_metadata):
    db, _, _, _ = library(tmp_path)
    score = db.list_archived_scores()[0]
    if bad_metadata == 'conflicting_hash':
        runtime_services.importer(db).import_paths([Path(score['source_path'])])
    with db.transaction() as connection:
        if bad_metadata == 'missing_hash':
            connection.execute('UPDATE score SET source_sha256=? WHERE id=?', ('', score['id']))
        elif bad_metadata == 'relative_path':
            connection.execute('UPDATE score SET source_path=? WHERE id=?', ('unresolved-relative.pdf', score['id']))
        else:
            connection.execute('UPDATE import_item SET source_sha256=? WHERE source_path=?', ('f' * 64, score['source_path']))
    result = export_library(db, tmp_path / 'metadata.zip')
    assert result['batch']['skippedSongs'] == 1 and result['batch']['usableSongs'] == 2
    restored = restore_library(tmp_path / 'metadata.zip', tmp_path / 'new')
    assert len(restored.list_archived_scores()) == 6
    assert restored.count_scores() == 9


def test_runtime_identity_side_effect_cannot_silently_modify_original_metadata(tmp_path):
    db, _, _, _ = library(tmp_path)
    with db.transaction() as connection:
        connection.execute("CREATE TRIGGER unexpected_runtime_side_effect AFTER INSERT ON settings WHEN new.key='portable_runtime_id' BEGIN UPDATE song SET display_title='unexpected'; END")
    before = db_state(db.path)
    export_library(db, tmp_path / 'trigger.zip')
    with pytest.raises(ValueError, match='其他记录变化'):
        restore_library(tmp_path / 'trigger.zip', tmp_path / 'rejected')
    assert db_state(db.path) == before and not (tmp_path / 'rejected/portable-runtime.json').exists()


def old_reader_module(name):
    path = Path(__file__).parent / 'fixtures/compat014' / (name + '_014.py')
    spec = importlib.util.spec_from_file_location('compat014_' + name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize('expanded', [False, True])
def test_actual_014_reader_rejects_new_batch_runtime_without_modifying_database(tmp_path, expanded):
    db, _, _, _ = library(tmp_path, expanded)
    export_library(db, tmp_path / 'batch.zip')
    restored = restore_library(tmp_path / 'batch.zip', tmp_path / 'new')
    before = hashlib.sha256(restored.path.read_bytes()).hexdigest()
    old = old_reader_module('expanded_database' if expanded else 'portable_database')
    old_class = old.ExpandedDatabase if expanded else old.PortableDatabase
    with pytest.raises(ValueError):
        old_class(restored.path).initialize()
    assert hashlib.sha256(restored.path.read_bytes()).hexdigest() == before
    restored.initialize()


@pytest.mark.parametrize('expanded', [False, True])
def test_actual_014_reader_still_accepts_original_complete_v1_runtime(tmp_path, expanded):
    from score_organizer.services.library_transfer import export_library as strict_export
    db, _, _, _ = library(tmp_path, expanded)
    strict_export(db, tmp_path / 'legacy.zip')
    restored = restore_library(tmp_path / 'legacy.zip', tmp_path / 'new')
    old = old_reader_module('expanded_database' if expanded else 'portable_database')
    old_class = old.ExpandedDatabase if expanded else old.PortableDatabase
    old_class(restored.path).initialize()


def test_new_startup_pointer_reopens_batch_library_while_old_startup_refuses(tmp_path, monkeypatch):
    from score_organizer.main import remember_portable, startup_database
    db, _, _, _ = library(tmp_path)
    export_library(db, tmp_path / 'batch.zip')
    restored = restore_library(tmp_path / 'batch.zip', tmp_path / 'new')
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE', str(tmp_path / 'profile'))
    monkeypatch.setenv('SCORE_ORGANIZER_DATA_DIR', str(tmp_path / 'empty-fallback'))
    remember_portable(restored.path)
    pointer = json.loads((tmp_path / 'profile/current-library.json').read_text('utf-8'))
    assert pointer['format'] == 'score-organizer-current-portable-v2'
    reopened = startup_database()
    reopened.initialize()
    assert reopened.path == restored.path
    with pytest.raises(ValueError):
        old_reader_module('main').startup_database()
    assert not (tmp_path / 'empty-fallback').exists()


def test_partial_library_cannot_be_downgraded_to_legacy_package(tmp_path):
    from score_organizer.services.library_transfer import export_library as strict_export
    db, output, _, _ = library(tmp_path)
    row = db.list_archived_scores()[0]
    runtime_services.archive(db, output).score_paths(row)[0].unlink()
    export_library(db, tmp_path / 'batch.zip')
    restored = restore_library(tmp_path / 'batch.zip', tmp_path / 'new')
    with pytest.raises(ValueError, match='不能降为旧格式'):
        strict_export(restored, tmp_path / 'must-not-downgrade.zip')
    assert not (tmp_path / 'must-not-downgrade.zip').exists()
