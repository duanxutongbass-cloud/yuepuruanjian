import json

import pytest

from score_organizer.services import import_service
from score_organizer.services.expanded_import_service import ExpandedImportService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from test_expanded_archive_service import prepared
from test_library_upgrade_snapshot import contents, db_state


def write(tmp_path, names):
    folder = tmp_path / 'incoming'
    folder.mkdir(exist_ok=True)
    paths = []
    for name in names:
        path = folder / name
        path.write_bytes(f'%PDF synthetic {name}'.encode())
        paths.append(path)
    return paths


def fields(db, batch):
    return [json.loads(row['parsed_fields']) for row in db.list_import_items(batch)]


def test_real_expanded_filename_import_archive_and_later_supplement(tmp_path):
    old, root, originals, db, output, archive = prepared(tmp_path)
    old_state, old_files = db_state(old.path), contents(root)
    paths = write(tmp_path, [f'{code}-01-1000-新曲.pdf' for code in ('01', '02', '03')])
    expected = {p: sha256_file(p) for p in [*originals, *paths]}
    service = ExpandedImportService(db)
    batch = service.import_paths(paths)
    assert all(f['seq'] == '1000' and f['title'] == '新曲' for f in fields(db, batch))
    assert db.import_batch_summary(batch)['recognized'] == 3
    plans = archive.build_plan(batch)
    assert len(plans) == 1 and plans[0].seq == '1000'
    archive.commit_plan(batch, plans)
    added = write(tmp_path, ['04-01-1000-新曲.pdf'])[0]
    second = service.import_paths([added])
    plans = archive.build_plan(second)
    assert plans[0].seq == '1000' and plans[0].existing_song_id
    archive.commit_plan(second, plans)
    rows = [r for r in db.list_archived_scores() if r['seq'] == '1000']
    assert len(rows) == 4
    for row in rows:
        assert all(sha256_file(p) == row['source_sha256'] for p in archive.score_paths(row))
    assert {p: sha256_file(p) for p in expected} == expected
    assert contents(root) == old_files and db_state(old.path) == old_state
    assert db.allocate_next_sequence() == '002'


def test_recursive_mixed_legacy_and_expanded_names_preserve_metadata(tmp_path):
    old, root, originals, db, output, archive = prepared(tmp_path)
    paths = write(tmp_path, ['0101005旧格式.pdf', '0201旧文件.pdf', '03_01_10000_2026新曲.pdf'])
    batch = ExpandedImportService(db).import_paths([paths[0].parent])
    parsed = {row['source_path']: json.loads(row['parsed_fields']) for row in db.list_import_items(batch)}
    assert parsed[str(paths[0])]['seq'] == '005'
    assert parsed[str(paths[1])]['seq'] is None
    assert parsed[str(paths[2])]['seq'] == '10000'
    assert parsed[str(paths[2])]['title'] == '2026新曲'
    assert db.import_batch_summary(batch)['recognized'] == 3


@pytest.mark.parametrize('name', [
    '01-01-100-新曲.pdf', '01-01-01000-新曲.pdf', '01-01-1000_新曲.pdf',
    '99-01-1000-新曲.pdf', '01-99-1000-新曲.pdf', '01-01-9223372036854775808-新曲.pdf',
])
def test_invalid_expanded_name_is_manual_not_truncated_or_silently_numbered(tmp_path, name):
    old, root, originals, db, output, archive = prepared(tmp_path)
    path = write(tmp_path, [name])[0]
    batch = ExpandedImportService(db).import_paths([path])
    row = db.list_import_items(batch)[0]
    parsed = json.loads(row['parsed_fields'])
    assert row['status'] == 'needs_manual_entry' and parsed['seq'] is None and parsed['reason']
    assert row['source_sha256'] == sha256_file(path)
    assert archive.build_plan(batch) == []
    assert db.allocate_next_sequence() == '002'


@pytest.mark.parametrize('number', ['1000', '10000', '9223372036854775807'])
def test_complete_expanded_number_and_digit_leading_title_preserved(tmp_path, number):
    old, root, originals, db, output, archive = prepared(tmp_path)
    path = write(tmp_path, [f'08-01-{number}-2026音乐.pdf'])[0]
    batch = ExpandedImportService(db).import_paths([path])
    parsed = fields(db, batch)[0]
    assert parsed['seq'] == number and parsed['title'] == '2026音乐'
    plans = archive.build_plan(batch)
    assert plans[0].seq == number
    archive.commit_plan(batch, plans)
    assert plans[0].files[0].output_name == f'08-01-{number}-2026音乐.pdf'
    assert sha256_file(plans[0].files[0].by_instrument_path) == sha256_file(path)


def test_normal_product_import_still_requires_compatibility_upgrade(tmp_path):
    db = Database(tmp_path / 'normal.sqlite3')
    db.initialize()
    path = write(tmp_path, ['01-01-1000-新曲.pdf'])[0]
    batch = ImportService(db).import_paths([path])
    row = db.list_import_items(batch)[0]
    assert row['status'] == 'needs_manual_entry'
    assert '尚未启用' in fields(db, batch)[0]['reason']
    with pytest.raises(ValueError, match='独立运行库'):
        ExpandedImportService(db).import_paths([path])


def test_declaring_v2_on_format_zero_does_not_enable_import(tmp_path):
    runtime = tmp_path / 'runtime'
    db = Database(runtime / 'library.sqlite3')
    db.initialize()
    output = runtime / 'files'
    output.mkdir()
    db.set_setting('output_root', str(output))
    db._numbering_version = 2
    path = write(tmp_path, ['01-01-1000-新曲.pdf'])[0]
    before = db_state(db.path)
    with pytest.raises(ValueError):
        ExpandedImportService(db).import_paths([path])
    assert db_state(db.path) == before and contents(output) == {}


def test_changed_output_after_hashing_is_checked_again_before_insert(tmp_path, monkeypatch):
    old, root, originals, db, output, archive = prepared(tmp_path)
    path = write(tmp_path, ['01-01-1000-新曲.pdf'])[0]
    before_files = contents(output)
    digest = import_service.sha256_file

    def changed(file):
        result = digest(file)
        db.set_setting('output_root', str(root))
        return result

    monkeypatch.setattr(import_service, 'sha256_file', changed)
    with pytest.raises(ValueError, match='不一致'):
        ExpandedImportService(db).import_paths([path])
    assert db.latest_import_batch_id() is None
    assert contents(output) == before_files


def test_failed_multi_file_insert_rolls_back_batch_and_all_new_records(tmp_path, monkeypatch):
    old, root, originals, db, output, archive = prepared(tmp_path)
    paths = write(tmp_path, ['01-01-1000-新曲.pdf', '02-01-1000-新曲.pdf'])
    before, old_state = db_state(db.path), db_state(old.path)
    original = db.add_import_item
    calls = 0

    def failed(**kwargs):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError('injected import failure')
        return original(**kwargs)

    monkeypatch.setattr(db, 'add_import_item', failed)
    with pytest.raises(OSError, match='injected'):
        ExpandedImportService(db).import_paths(paths)
    assert calls == 2 and db_state(db.path) == before and db_state(old.path) == old_state


def test_expanded_import_appends_to_existing_draft_without_replacing_prior_items(tmp_path):
    old, root, originals, db, output, archive = prepared(tmp_path)
    paths = write(tmp_path, ['01-01-1000-新曲.pdf', '02-01-1000-新曲.pdf'])
    service = ExpandedImportService(db)
    batch = service.import_paths([paths[0]])
    first = dict(db.list_import_items(batch)[0])
    assert service.import_paths([paths[1]], batch_id=batch) == batch
    assert dict(db.list_import_items(batch)[0]) == first
    assert len(db.list_import_items(batch)) == 2
