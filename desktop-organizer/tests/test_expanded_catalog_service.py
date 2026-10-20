from contextlib import closing
import os
import shutil
import sqlite3

import pytest

from score_organizer.services.expanded_catalog_service import ExpandedCatalogService
from score_organizer.services.expanded_import_service import ExpandedImportService
from score_organizer.services.expanded_reset_service import ExpandedResetService
from score_organizer.services.import_service import sha256_file
from score_organizer.storage.database import Database
from score_organizer.storage.number_upgrade import _integrity
from score_organizer.storage.writer_guard import require_format_one_guards
from test_expanded_archive_service import prepared
from test_expanded_import_service import write
from test_library_upgrade_snapshot import contents, db_state


def archived(tmp_path, number='1000', codes=('01', '02', '03')):
    old, root, originals, db, output, archive = prepared(tmp_path)
    paths = write(tmp_path, [f'{code}-01-{number}-新曲.pdf' for code in codes])
    batch = ExpandedImportService(db).import_paths(paths)
    archive.commit_plan(batch, archive.build_plan(batch))
    song_id = int(db.find_archived_song('01', '新曲')['id'])
    return old, root, [*originals, *paths], db, output, archive, song_id


def archived_pdfs(output):
    return {p.relative_to(output).as_posix(): p.read_bytes()
            for folder in ('按乐器分类', '按曲目分类')
            for p in (output / folder).rglob('*.pdf')}


@pytest.mark.parametrize('number', ['1000', '10000', '9223372036854775807'])
def test_expanded_edit_preserves_sequence_and_sources_with_independently_readable_backup(tmp_path, number):
    old, root, sources, db, output, archive, song_id = archived(tmp_path, number)
    old_state, old_files = db_state(old.path), contents(root)
    source_hashes = {p: sha256_file(p) for p in sources}
    before_state = db_state(db.path)
    before_files = contents(output)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    assert contents(output) == before_files and db_state(db.path) == before_state
    backup = service.commit_plan(plan)
    song = db.get_archived_song(song_id)
    assert (song['seq'], song['category_code'], song['display_title']) == (number, '02', '重命名')
    assert len(list(backup.glob('*.pdf'))) == 6 and (backup / '完成.txt').is_file()
    for change in plan.files:
        assert change.new_instrument.name == f'{change.instrument_code}-02-{number}-重命名.pdf'
        assert change.new_song.parent.name == f'02-{number}-重命名（03）'
        assert sha256_file(change.new_song) == sha256_file(change.new_instrument) == change.sha256
        assert not change.old_instrument.exists() and not change.old_song.exists()
    restored = tmp_path / 'independent-restored.sqlite3'
    with closing(sqlite3.connect((backup / '修改前.sqlite3').as_uri() + '?mode=ro', uri=True)) as source, \
            closing(sqlite3.connect(restored)) as destination:
        source.backup(destination)
        destination.execute('BEGIN')
        require_format_one_guards(destination)
        _integrity(destination)
    assert db_state(restored) == before_state
    assert {p: sha256_file(p) for p in sources} == source_hashes
    assert db_state(old.path) == old_state and contents(root) == old_files
    for row in db.list_archived_scores():
        assert all(sha256_file(p) == row['source_sha256'] for p in archive.score_paths(row))


def test_expanded_single_copy_edit_does_not_create_song_copy(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path, codes=('08',))
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '总谱新名')
    assert len(plan.files) == 1 and plan.files[0].old_song is None and plan.files[0].new_song is None
    backup = service.commit_plan(plan)
    assert len(list(backup.glob('*.pdf'))) == 1
    row = db.list_archived_scores(song_id)[0]
    assert len(archive.score_paths(row)) == 1
    assert row['seq'] == '1000' and row['output_name'] == '08-02-1000-总谱新名.pdf'


def test_expanded_runtime_keeps_legacy_three_digit_names_when_editing_old_song(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    current_expanded = {p: p.read_bytes() for row in db.list_archived_scores(song_id) for p in archive.score_paths(row)}
    legacy_id = int(db.find_archived_song('01', '旧曲')['id'])
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(legacy_id, '02', '旧曲新名')
    service.commit_plan(plan)
    assert plan.seq == '001'
    assert {f.new_instrument.name for f in plan.files} == {f'{code}02001旧曲新名.pdf' for code in ('01', '02', '03')}
    assert all(p.read_bytes() == value for p, value in current_expanded.items())


def test_edit_then_real_filename_supplement_retains_original_expanded_sequence(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    service.commit_plan(service.build_plan(song_id, '02', '重命名'))
    added = write(tmp_path, ['04-02-1000-重命名.pdf'])
    batch = ExpandedImportService(db).import_paths(added)
    plans = archive.build_plan(batch)
    assert plans[0].seq == '1000' and plans[0].existing_song_id == song_id
    archive.commit_plan(batch, plans)
    assert len(list((output / '按曲目分类/02-1000-重命名（04）').glob('*.pdf'))) == 4
    assert db.allocate_next_sequence() == '002'


@pytest.mark.parametrize('failure', ['database', 'copy'])
def test_edit_failure_restores_both_directories_and_original_metadata(tmp_path, monkeypatch, failure):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    before_pdfs, before_db = archived_pdfs(output), db_state(db.path)
    if failure == 'database':
        update = db.update_archived_song_metadata

        def failed(*args):
            update(*args)
            raise sqlite3.OperationalError('injected metadata error')

        monkeypatch.setattr(db, 'update_archived_song_metadata', failed)
    else:
        copy = shutil.copyfileobj
        calls = []

        def failed(source, destination, *args):
            if '重命名' in str(destination.name):
                calls.append(destination.name)
                if len(calls) == 2:
                    raise OSError('injected output error')
            return copy(source, destination, *args)

        monkeypatch.setattr(shutil, 'copyfileobj', failed)
    with pytest.raises(ValueError, match='已恢复'):
        service.commit_plan(plan)
    assert archived_pdfs(output) == before_pdfs and db_state(db.path) == before_db
    assert not list((output / '修改备份').rglob('完成.txt'))


@pytest.mark.parametrize('change', ['source', 'target', 'catalog', 'output', 'guard'])
def test_changed_preview_or_runtime_refused_without_file_mutation(tmp_path, change):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    if change == 'source':
        plan.files[0].old_song.write_bytes(b'%PDF changed externally')
    elif change == 'target':
        plan.files[0].new_instrument.write_bytes(b'%PDF existing target')
    elif change == 'catalog':
        db.create_song('02', '1001', '重命名', status='archived')
    elif change == 'output':
        db.set_setting('output_root', str(root))
    else:
        with db.transaction() as connection:
            name = connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger' AND name LIKE '_score_format1_guard_%' LIMIT 1").fetchone()[0]
            connection.execute('DROP TRIGGER "' + name + '"')
    before_files, before_db = contents(output), db_state(db.path)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert contents(output) == before_files and db_state(db.path) == before_db


def test_catalog_edit_protects_pdf_used_as_original_source(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    row = db.list_archived_scores(song_id)[0]
    original = archive.score_paths(row)[0]
    batch = db.create_import_batch()
    db.add_import_item(batch, str(original), row['source_sha256'], 'recognized',
                       {'category_code': '01', 'instrument_code': '01', 'seq': '1000', 'title': '新曲'})
    before = archived_pdfs(output)
    with pytest.raises(ValueError, match='源PDF'):
        ExpandedCatalogService(db, output).build_plan(song_id, '02', '重命名')
    assert archived_pdfs(output) == before


@pytest.mark.parametrize('damage', ['database', 'pdf'])
def test_backup_change_after_copying_stops_before_removing_any_old_pdf(tmp_path, monkeypatch, damage):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    before_files, before_db = archived_pdfs(output), db_state(db.path)
    copy = shutil.copy2
    changed = []

    def tampered(source, target, *args, **kwargs):
        result = copy(source, target, *args, **kwargs)
        if not changed and (damage == 'database' or target.name == '000005.pdf'):
            changed.append(True)
            damaged = target.parent / ('修改前.sqlite3' if damage == 'database' else '000000.pdf')
            with damaged.open('ab') as stream:
                stream.write(b'tampered')
        return result

    monkeypatch.setattr(shutil, 'copy2', tampered)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert changed and archived_pdfs(output) == before_files and db_state(db.path) == before_db
    assert not list((output / '修改备份').rglob('完成.txt'))


def test_runtime_change_after_database_backup_blocks_before_removing_originals(tmp_path, monkeypatch):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    verify = service._verify_backup
    before = archived_pdfs(output)

    def changed(path):
        verify(path)
        db.set_setting('output_root', str(root))

    monkeypatch.setattr(service, '_verify_backup', changed)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert archived_pdfs(output) == before
    assert db.get_archived_song(song_id)['display_title'] == '新曲'


def test_edit_preserves_version_identity(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    with db.transaction() as connection:
        connection.execute('UPDATE song SET version_no=2 WHERE id=?', (song_id,))
    service = ExpandedCatalogService(db, output)
    service.commit_plan(service.build_plan(song_id, '02', '新标题（第二版）'))
    row = db.get_archived_song(song_id)
    assert row['seq'] == '1000' and row['version_no'] == 2


def test_normal_product_does_not_enable_expanded_catalog(tmp_path):
    db = Database(tmp_path / 'normal.sqlite3')
    db.initialize()
    output = tmp_path / 'not-created'
    with pytest.raises(ValueError, match='独立运行库'):
        ExpandedCatalogService(db, output).build_plan(1, '02', '新名')
    assert not output.exists()


@pytest.mark.parametrize('damage', ['guard', 'version', 'sidecar', 'hardlink'])
def test_invalid_database_snapshot_rejected_before_archived_pdfs_are_removed(tmp_path, monkeypatch, damage):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    plan = service.build_plan(song_id, '02', '重命名')
    before_files, before_db = archived_pdfs(output), db_state(db.path)
    verify = service._verify_backup
    reached = []

    def damaged(path):
        reached.append(path)
        if damage in ('guard', 'version'):
            with closing(sqlite3.connect(path)) as connection:
                if damage == 'guard':
                    name = connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger' AND name LIKE '_score_format1_guard_%' LIMIT 1").fetchone()[0]
                    connection.execute('DROP TRIGGER "' + name + '"')
                else:
                    connection.execute('PRAGMA user_version=0')
                connection.commit()
        elif damage == 'sidecar':
            path.with_name(path.name + '-wal').write_bytes(b'')
        else:
            os.link(path, path.with_name('shared.sqlite3'))
        verify(path)

    monkeypatch.setattr(service, '_verify_backup', damaged)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert len(reached) == 1 and archived_pdfs(output) == before_files and db_state(db.path) == before_db
    assert not list((output / '修改备份').rglob('完成.txt'))


def test_edited_expanded_catalog_survives_v2_clear_and_restore(tmp_path):
    old, root, sources, db, output, archive, song_id = archived(tmp_path)
    service = ExpandedCatalogService(db, output)
    service.commit_plan(service.build_plan(song_id, '02', '重命名'))
    before_files, before_db = archived_pdfs(output), db_state(db.path)
    reset = ExpandedResetService(db)
    backup = reset.clear(reset.build_plan())
    assert db.count_scores() == 0
    reset.restore(backup)
    assert archived_pdfs(output) == before_files
    song = db.get_archived_song(song_id)
    assert (song['seq'], song['category_code'], song['display_title']) == ('1000', '02', '重命名')
    assert db.allocate_next_sequence() == '002'
    for row in db.list_archived_scores():
        assert all(sha256_file(p) == row['source_sha256'] for p in archive.score_paths(row))
