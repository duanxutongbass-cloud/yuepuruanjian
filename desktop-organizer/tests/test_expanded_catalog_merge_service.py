import json
import shutil
import sqlite3

import pytest

from score_organizer.domain.score_naming import format_score_filename, format_song_folder
from score_organizer.services.expanded_catalog_merge_service import ExpandedCatalogMergeService
from score_organizer.services.expanded_import_service import ExpandedImportService
from score_organizer.services.expanded_reset_service import ExpandedResetService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.historical_manifests import verify_pending_manifests
from score_organizer.storage.database import Database
from test_catalog_merge_service import formal_hashes
from test_expanded_archive_service import prepared
from test_expanded_import_service import write
from test_library_upgrade_snapshot import contents, db_state


def fixture(tmp_path, numbers=('1000', '1001')):
    old, root, originals, db, output, archive = prepared(tmp_path)
    ids, sources = [], list(originals)
    incoming = tmp_path / 'merge-originals'
    incoming.mkdir()
    for number, title, codes in zip(numbers, ('同 曲', '同曲'), [('02', '03', '04'), ('02', '05', '06')]):
        song = db.create_song('01', number, title, status='archived')
        ids.append(song)
        folder = format_song_folder('01', number, title, 3, naming_version=2)
        for code in codes:
            name = format_score_filename(code, '01', number, title, naming_version=2)
            original = incoming / name
            original.write_bytes(f'%PDF synthetic {number} {code}'.encode())
            sources.append(original)
            for target in (output / '按乐器分类' / archive._instrument_folder(code) / name,
                           output / '按曲目分类' / folder / name):
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(original, target)
            db.add_score(song, code, name, str(original), sha256_file(original), name)
    return old, root, sources, db, output, archive, ids


def selection(db, ids, keep=0):
    return [r['id'] for song in ids for r in db.list_archived_scores(song)
            if r['instrument_code'] != '02' or r['song_id'] == ids[keep]]


@pytest.mark.parametrize('numbers', [('1000', '1001'), ('9999', '10000'), ('007', '1000')])
@pytest.mark.parametrize('retained', [0, 1])
def test_actual_merge_preserves_chosen_number_reservation_pdfs_and_audit(tmp_path, numbers, retained):
    old, root, sources, db, output, archive, ids = fixture(tmp_path, numbers)
    before_old, old_files = db_state(old.path), contents(root)
    source_hashes = {p: sha256_file(p) for p in sources}
    legacy_rows = [dict(r) for r in db.list_archived_scores() if r['song_id'] not in ids]
    service = ExpandedCatalogMergeService(db, output)
    before = contents(output)
    plan = service.build_plan(ids[retained], ids[1 - retained], selection(db, ids, 1 - retained), '同曲')
    assert contents(output) == before
    backup = service.commit_plan(plan)
    assert len(list(backup.glob('*.pdf'))) == 12
    assert (backup / '完成.txt').is_file()
    kept = db.list_archived_scores(ids[retained])
    assert len(kept) == 5 and {r['seq'] for r in kept} == {numbers[retained]}
    for row in kept:
        assert row['output_name'] == format_score_filename(row['instrument_code'], '01', numbers[retained], '同曲', naming_version=2)
        assert all(sha256_file(p) == row['source_sha256'] for p in archive.score_paths(row))
    assert db.get_archived_song(ids[1 - retained]) is None
    with db.session() as connection:
        merged = connection.execute('SELECT seq,status FROM song WHERE id=?', (ids[1 - retained],)).fetchone()
        assert tuple(merged) == (numbers[1 - retained], 'merged')
        assert not connection.execute('PRAGMA foreign_key_check').fetchall()
    assert db.allocate_next_sequence() == '002'
    duplicates = [f for f in plan.files if f.score_id not in plan.selected_score_ids]
    assert len(duplicates) == 1
    for file in duplicates:
        assert sha256_file(file.new_song) == sha256_file(file.new_instrument) == file.sha256
    record = json.loads((plan.duplicate_root / f'合并对比记录_{plan.event_id}.json').read_text('utf-8'))
    assert len(record['original_records']) == 6 and len(record['selected_score_ids']) == 5
    assert {p: sha256_file(p) for p in sources} == source_hashes
    assert db_state(old.path) == before_old and contents(root) == old_files
    assert [dict(r) for r in db.list_archived_scores() if r['song_id'] not in ids] == legacy_rows
    with pytest.raises(ValueError):
        service.commit_plan(plan)


@pytest.mark.parametrize('choice', ['all', 'none'])
def test_selection_requires_explicit_single_retained_copy_per_instrument(tmp_path, choice):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    before = contents(output)
    selected = [r['id'] for song in ids for r in db.list_archived_scores(song)] if choice == 'all' else []
    with pytest.raises(ValueError, match='只能保留'):
        ExpandedCatalogMergeService(db, output).build_plan(*ids, selected, '同曲')
    assert contents(output) == before


@pytest.mark.parametrize('change', ['category', 'version', 'title'])
def test_unrelated_or_different_version_songs_never_merge(tmp_path, change):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    with db.transaction() as connection:
        if change == 'category':
            connection.execute("UPDATE song SET category_id=(SELECT id FROM category WHERE code='02') WHERE id=?", (ids[1],))
        elif change == 'version':
            connection.execute('UPDATE song SET version_no=2 WHERE id=?', (ids[1],))
        else:
            connection.execute("UPDATE song SET display_title='另一首' WHERE id=?", (ids[1],))
    before = contents(output)
    with pytest.raises(ValueError):
        ExpandedCatalogMergeService(db, output).build_plan(*ids, selection(db, ids), '同曲')
    assert contents(output) == before


@pytest.mark.parametrize('damage', ['pdf', 'untracked', 'runtime', 'collision'])
def test_changed_preview_stops_without_deleting_either_song(tmp_path, damage):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    service = ExpandedCatalogMergeService(db, output)
    plan = service.build_plan(*ids, selection(db, ids), '同曲')
    if damage == 'pdf':
        plan.files[0].old_song.write_bytes(b'%PDF external change')
    elif damage == 'untracked':
        (plan.files[0].old_song.parent / 'untracked.pdf').write_bytes(b'%PDF untracked')
    elif damage == 'runtime':
        db.set_setting('output_root', str(root))
    else:
        target = next(f.new_instrument for f in plan.files if f.score_id not in plan.selected_score_ids)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'%PDF existing duplicate target')
    before, before_db = contents(output), db_state(db.path)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert contents(output) == before and db_state(db.path) == before_db


@pytest.mark.parametrize('failure', ['database', 'pdf'])
def test_failed_merge_restores_both_songs_all_paths_and_can_retry(tmp_path, monkeypatch, failure):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    service = ExpandedCatalogMergeService(db, output)
    plan = service.build_plan(*ids, selection(db, ids), '同曲')
    before, before_db = formal_hashes(output), db_state(db.path)
    if failure == 'database':
        update = db.update_archived_song_metadata

        def failed(*args):
            update(*args)
            raise sqlite3.OperationalError('injected merge registration failure')

        monkeypatch.setattr(db, 'update_archived_song_metadata', failed)
    else:
        copy = shutil.copyfileobj
        raised = []

        def failed(source, destination, *args):
            if not raised and '按曲目分类' in str(destination.name):
                raised.append(True)
                raise OSError('injected merge output failure')
            return copy(source, destination, *args)

        monkeypatch.setattr(shutil, 'copyfileobj', failed)
    with pytest.raises(ValueError, match='已恢复'):
        service.commit_plan(plan)
    assert formal_hashes(output) == before and db_state(db.path) == before_db
    assert not list(plan.duplicate_root.rglob('*.pdf'))
    assert not list((output / '修改备份').rglob('完成.txt'))
    monkeypatch.undo()
    service.commit_plan(service.build_plan(*ids, selection(db, ids), '同曲'))
    assert len(db.list_archived_scores(ids[0])) == 5


def test_merge_then_real_supplement_and_v2_clear_restore_keeps_reserved_number(tmp_path):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    service = ExpandedCatalogMergeService(db, output)
    service.commit_plan(service.build_plan(*ids, selection(db, ids), '同曲'))
    path = write(tmp_path, ['07-01-1000-同曲.pdf'])[0]
    batch = ExpandedImportService(db).import_paths([path])
    plans = archive.build_plan(batch)
    assert plans[0].seq == '1000' and plans[0].existing_song_id == ids[0]
    archive.commit_plan(batch, plans)
    before = contents(output)
    reset = ExpandedResetService(db)
    backup = reset.clear(reset.build_plan())
    reset.restore(backup)
    after = contents(output)
    assert all(after[name] == value for name, value in before.items())
    assert len(db.list_archived_scores(ids[0])) == 6 and db.get_archived_song(ids[1]) is None
    assert db.allocate_next_sequence() == '002'


def test_candidate_merge_reuses_strict_snapshot_policy_before_any_pdf_removal(tmp_path, monkeypatch):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    service = ExpandedCatalogMergeService(db, output)
    plan = service.build_plan(*ids, selection(db, ids), '同曲')
    before = formal_hashes(output)
    verify = service._verify_backup
    calls = []

    def reject(path):
        calls.append(path)
        verify(path)
        raise ValueError('injected backup rejection')

    monkeypatch.setattr(service, '_verify_backup', reject)
    with pytest.raises(ValueError, match='backup rejection'):
        service.commit_plan(plan)
    assert len(calls) == 1 and formal_hashes(output) == before
    assert all(db.get_archived_song(song) for song in ids)


def test_normal_product_does_not_enable_expanded_merge(tmp_path):
    db = Database(tmp_path / 'normal.sqlite3')
    db.initialize()
    output = tmp_path / 'not-created'
    with pytest.raises(ValueError, match='独立运行库'):
        ExpandedCatalogMergeService(db, output).build_plan(1, 2, [], '同曲')
    assert not output.exists()


def test_current_merge_audits_are_verified_without_skipping_duplicate_pdfs(tmp_path):
    old, root, sources, db, output, archive, ids = fixture(tmp_path)
    service = ExpandedCatalogMergeService(db, output)
    service.commit_plan(service.build_plan(*ids, selection(db, ids), '同曲'))
    before = contents(output)
    # The current historical verifier accepts a format-0 snapshot. Its metadata
    # read adapter is intentionally not bypassed with the format-1 test runtime.
    proof = verify_pending_manifests(output, old.path)
    assert proof['mergeAuditItems'] == 2 and proof['mergeAuditRecords'] == 1
    assert contents(output) == before
