import json
import shutil

import pytest

from score_organizer.domain.numbering import MAX_EXPANDED_SEQUENCE
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.expanded_archive_service import ExpandedArchiveService
from score_organizer.services.expanded_reset_service import ExpandedResetService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.expanded_import_service import ExpandedImportService
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.storage.database import Database, UnsupportedDatabaseVersion
from score_organizer.storage.writer_guard import require_format_one_guards
from test_historical_manifest_relocation import CandidateDatabase
from test_library_upgrade_snapshot import make_library, contents, db_state


class ExpandedCandidateDatabase(CandidateDatabase):
    """Test-only guarded connection; no production startup enables this class."""
    _numbering_version = 2


def prepared(tmp_path):
    old, root, originals = make_library(tmp_path)
    old.create_song('02', '999', 'Reserved number', status='merged')
    manifest = prepare_relocated_library(old.path, tmp_path / 'candidate')
    db = ExpandedCandidateDatabase(manifest.parent / 'runtime/library.sqlite3')
    output = manifest.parent / 'runtime/files'
    return old, root, originals, db, output, ExpandedArchiveService(db, output)


def batch(db, tmp_path, codes=('01', '02', '03'), title='新曲', folder='new'):
    source = tmp_path / folder
    source.mkdir()
    files = []
    for code in codes:
        path = source / f'{code}-01-1000-{title}.pdf'
        path.write_bytes(f'%PDF {folder} {code} {title}'.encode())
        files.append(path)
    return ExpandedImportService(db).import_paths(files), files


def test_actual_expanded_archive_preserves_old_numbers_and_independent_dual_pdfs(tmp_path):
    old, root, originals, db, output, service = prepared(tmp_path)
    old_state, old_files, candidate_old_files = db_state(old.path), contents(root), contents(output)
    batch_id, files = batch(db, tmp_path)
    hashes = {p: sha256_file(p) for p in [*originals, *files]}
    plans = service.build_plan(batch_id)
    assert len(plans) == 1 and plans[0].seq == '1000'
    assert plans[0].folder_name == '01-1000-新曲（03）'
    assert service.commit_plan(batch_id, plans) == 0
    rows = [r for r in db.list_archived_scores() if r['seq'] == '1000']
    assert len(rows) == 3
    for row in rows:
        paths = service.score_paths(row)
        assert len(paths) == 2 and paths[0] != paths[1]
        assert row['output_name'] == f"{row['instrument_code']}-01-1000-新曲.pdf"
        assert all(sha256_file(p) == row['source_sha256'] for p in paths)
        assert paths[0].stat().st_ino != paths[1].stat().st_ino
    assert {p: sha256_file(p) for p in hashes} == hashes
    assert db_state(old.path) == old_state and contents(root) == old_files
    current_files = contents(output)
    assert all(current_files[name] == value for name, value in candidate_old_files.items())
    with db.session() as connection:
        require_format_one_guards(connection)
        assert not connection.execute('PRAGMA foreign_key_check').fetchall()
    assert db.allocate_next_sequence() == '002'
    with pytest.raises(UnsupportedDatabaseVersion):
        Database(db.path).connect()


def test_supplement_expanded_piece_keeps_sequence_and_moves_only_song_folder(tmp_path):
    old, root, originals, db, output, service = prepared(tmp_path)
    first, files = batch(db, tmp_path)
    service.commit_plan(first, service.build_plan(first))
    old_folder = output / '按曲目分类/01-1000-新曲（03）'
    second, added = batch(db, tmp_path, ('04',), folder='supplement')
    plans = service.build_plan(second)
    assert len(plans) == 1 and plans[0].seq == '1000' and plans[0].existing_song_id
    assert service.commit_plan(second, plans) == 0
    assert not old_folder.exists()
    assert len(list((output / '按曲目分类/01-1000-新曲（04）').glob('*.pdf'))) == 4
    for row in db.list_archived_scores():
        assert all(sha256_file(p) == row['source_sha256'] for p in service.score_paths(row))
    assert db.allocate_next_sequence() == '002'


@pytest.mark.parametrize('numbers,expected', [
    (['1000'], 2), (['9999', '10000'], 2),
    (['998', '1000', '999'], 2), ([str(MAX_EXPANDED_SEQUENCE - 1)], 2), (['002', '004'], 3),
])
def test_smallest_gap_covers_all_reserved_sequences(tmp_path, numbers, expected):
    old, root, originals, db, output, service = prepared(tmp_path)
    for index, number in enumerate(numbers):
        if number != '999':
            db.create_song('02', number, f'Reserved {index}', status='merged')
    assert service.next_candidate_number({}) == expected
    assert db.allocate_next_sequence() == f'{expected:03d}'


def test_highest_reserved_number_does_not_exhaust_lower_free_numbers(tmp_path):
    old, root, originals, db, output, service = prepared(tmp_path)
    db.create_song('02', str(MAX_EXPANDED_SEQUENCE), 'Final reserved', status='merged')
    batch_id, files = batch(db, tmp_path)
    before = contents(output)
    plans = service.build_plan(batch_id)
    assert plans[0].seq == '1000'
    assert db.allocate_next_sequence() == '002'
    assert len(db.list_import_items(batch_id)) == 3 and contents(output) == before


@pytest.mark.parametrize('change', ['source', 'target', 'catalog'])
def test_changed_preview_never_overwrites_or_discards_draft(tmp_path, change):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path)
    plans = service.build_plan(batch_id)
    if change == 'source':
        files[0].write_bytes(b'%PDF changed')
    elif change == 'target':
        target = plans[0].files[0].by_instrument_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(b'%PDF already here')
    else:
        db.create_song('02', '1000', 'Concurrent reserved', status='merged')
    before = contents(output)
    with pytest.raises(ValueError):
        service.commit_plan(batch_id, plans)
    assert len(db.list_import_items(batch_id)) == 3
    assert all(not r['display_title'] == '新曲' for r in db.list_archived_scores())
    assert contents(output) == before


def test_copy_failure_rolls_back_candidate_catalog_and_all_created_pdfs(tmp_path, monkeypatch):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path)
    plans = service.build_plan(batch_id)
    before = contents(output)
    copy = shutil.copyfileobj
    calls = 0

    def interrupted(source, target, *args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 3:
            target.write(b'partial')
            raise OSError('injected copy failure')
        return copy(source, target, *args, **kwargs)

    monkeypatch.setattr(shutil, 'copyfileobj', interrupted)
    with pytest.raises(OSError, match='injected'):
        service.commit_plan(batch_id, plans)
    assert calls == 3 and len(db.list_import_items(batch_id)) == 3
    assert contents(output) == before
    assert db.allocate_next_sequence() == '002'


def test_current_runtime_rejects_expanded_component_before_import_or_output(tmp_path):
    db = Database(tmp_path / 'old.sqlite3')
    db.initialize()
    output = tmp_path / 'not-created'
    service = ExpandedArchiveService(db, output)
    with pytest.raises(ValueError, match='独立运行库'):
        service.build_plan(1)
    with pytest.raises(ValueError, match='独立运行库'):
        service.commit_plan(1, [])
    assert not output.exists() and db.count_songs() == 0
    with pytest.raises(ValueError):
        db.create_song('01', '1000', 'Still refused')


@pytest.mark.parametrize('codes', [('08',), ('04',)])
def test_expanded_single_part_retains_archive_mode_and_explicit_force(tmp_path, codes):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path, codes)
    if codes == ('04',):
        assert service.build_plan(batch_id) == []
        selected = {r['id'] for r in db.list_import_items(batch_id)}
        plans = service.build_plan(batch_id, selected, force=True)
    else:
        plans = service.build_plan(batch_id)
    assert len(plans) == 1 and plans[0].seq == '1000'
    service.commit_plan(batch_id, plans)
    row = next(r for r in db.list_archived_scores() if r['seq'] == '1000')
    paths = service.score_paths(row)
    assert len(paths) == (1 if codes == ('08',) else 2)
    assert all(sha256_file(p) == sha256_file(files[0]) for p in paths)


def test_real_expanded_archive_survives_v2_clear_and_independent_restore(tmp_path):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path)
    service.commit_plan(batch_id, service.build_plan(batch_id))
    before = contents(output)
    reset = ExpandedResetService(db)
    backup = reset.clear(reset.build_plan())
    assert db.count_scores() == 0
    reset.restore(backup)
    # Backup audit files are new; the original archived PDFs must all return.
    after = contents(output)
    assert all(after[name] == value for name, value in before.items())
    assert len([r for r in db.list_archived_scores() if r['seq'] == '1000']) == 3
    for row in db.list_archived_scores():
        assert all(sha256_file(p) == row['source_sha256'] for p in service.score_paths(row))
    assert db.allocate_next_sequence() == '002'


@pytest.mark.parametrize('change', ['different_output', 'missing_output', 'changed_setting', 'missing_guard'])
def test_candidate_path_or_protection_change_blocks_before_pdf_creation(tmp_path, change):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path)
    plans = service.build_plan(batch_id)
    if change == 'different_output':
        service = ExpandedArchiveService(db, root)
    elif change == 'missing_output':
        output.rename(output.with_name('retained-files'))
    elif change == 'changed_setting':
        db.set_setting('output_root', str(root))
    else:
        with db.transaction() as connection:
            name = connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger' AND name LIKE '_score_format1_guard_%' LIMIT 1").fetchone()[0]
            connection.execute('DROP TRIGGER "' + name + '"')
    before, old_files = contents(output.parent), contents(root)
    with pytest.raises(ValueError):
        service.commit_plan(batch_id, plans)
    assert contents(output.parent) == before and contents(root) == old_files


@pytest.mark.parametrize('requested,accepted', [('1005', True), ('999', False)])
def test_expanded_requested_number_is_preserved_or_conflict_is_explicit(tmp_path, requested, accepted):
    old, root, originals, db, output, service = prepared(tmp_path)
    batch_id, files = batch(db, tmp_path)
    # Import filename parsing and its GUI remain v1; seed explicit metadata in
    # the guarded test database to exercise the archive contract only.
    with db.transaction() as connection:
        for row in connection.execute('SELECT id,parsed_fields FROM import_item WHERE batch_id=?', (batch_id,)).fetchall():
            fields = json.loads(row['parsed_fields'])
            fields['seq'] = requested
            connection.execute('UPDATE import_item SET parsed_fields=? WHERE id=?',
                               (json.dumps(fields, ensure_ascii=False), row['id']))
    before = contents(output)
    if not accepted:
        with pytest.raises(ValueError, match='冲突'):
            service.build_plan(batch_id)
        assert contents(output) == before and len(db.list_import_items(batch_id)) == 3
    else:
        plans = service.build_plan(batch_id)
        assert len(plans) == 1 and plans[0].seq == requested
        service.commit_plan(batch_id, plans)
        assert db.allocate_next_sequence() == '002'
        assert all(f'-{requested}-' in f.output_name for f in plans[0].files)


def test_failed_supplement_restores_expanded_folder_name_and_original_parts(tmp_path, monkeypatch):
    old, root, originals, db, output, service = prepared(tmp_path)
    first, files = batch(db, tmp_path)
    service.commit_plan(first, service.build_plan(first))
    second, added = batch(db, tmp_path, ('04',), folder='supplement')
    plans = service.build_plan(second)
    before = contents(output)

    def failed_copy(source, target, *args, **kwargs):
        target.write(b'partial')
        raise OSError('supplement interrupted')

    monkeypatch.setattr(shutil, 'copyfileobj', failed_copy)
    with pytest.raises(OSError, match='supplement interrupted'):
        service.commit_plan(second, plans)
    assert contents(output) == before
    assert (output / '按曲目分类/01-1000-新曲（03）').is_dir()
    assert not (output / '按曲目分类/01-1000-新曲（04）').exists()
    assert len(db.list_import_items(second)) == 1
    assert len([r for r in db.list_archived_scores() if r['seq'] == '1000']) == 3
