import json
import shutil
import zipfile

import pytest

from score_organizer.services.program_service import ProgramService, safe_name
from score_organizer.services.import_service import sha256_file
from test_catalog_filter import catalog_fixture


def fixture(tmp_path):
    db, root, sources = catalog_fixture(tmp_path)
    service = ProgramService(db, root)
    catalog = service.catalog()
    before = {p: sha256_file(p) for p in [*sources, *root.rglob('*.pdf')]}
    records = [dict(r) for r in db.list_archived_scores()]
    return db, root, service, catalog, before, records


def test_export_order_instruments_zip_and_library_unchanged(tmp_path):
    db, root, service, catalog, before, records = fixture(tmp_path)
    plan = service.build_plan([(8, catalog[0]['id']), (2, catalog[1]['id'])])
    assert [r[0] for r in plan.rows] == [2, 8]
    output = service.export(plan, tmp_path, '音乐会')
    assert len(list(output.rglob('*.pdf'))) == 6
    for file in plan.files:
        assert sha256_file(output / file.relative) == file.digest
    assert {p: sha256_file(p) for p in before} == before
    assert [dict(r) for r in db.list_archived_scores()] == records
    manifest = json.loads((output / '节目单.json').read_text(encoding='utf-8'))
    assert [r['order'] for r in manifest['entries']] == [2, 8]
    with zipfile.ZipFile(output / '节目乐谱.zip') as bundle:
        assert bundle.testzip() is None
        assert len([n for n in bundle.namelist() if n.endswith('.pdf')]) == 6
    assert not list(tmp_path.glob('.program-*'))
    with pytest.raises(ValueError, match='已存在'):
        service.export(plan, tmp_path, '音乐会')


@pytest.mark.parametrize('entries', [[], [(0, 1)], [(10000, 1)], [(1, 1), (1, 2)], [(1, 98765)]])
def test_invalid_entries(tmp_path, entries):
    _, _, service, _, _, _ = fixture(tmp_path)
    with pytest.raises(ValueError):
        service.build_plan(entries)


@pytest.mark.parametrize('mode', ['missing', 'changed', 'db_changed', 'copy_failure', 'during_copy', 'library_destination'])
def test_failures_leave_no_output_and_no_library_edits(tmp_path, monkeypatch, mode):
    db, root, service, catalog, before, records = fixture(tmp_path)
    plan = service.build_plan([(1, catalog[0]['id'])])
    if mode == 'missing':
        plan.files[0].sources[-1].unlink()
    elif mode == 'changed':
        plan.files[0].sources[-1].write_bytes(b'%PDF changed')
    elif mode == 'db_changed':
        with db.session() as conn:
            conn.execute('UPDATE song SET display_title = ? WHERE id = ?', ('变更', catalog[0]['id']))
    elif mode == 'copy_failure':
        def fail(*_):
            raise OSError('copy failed')
        monkeypatch.setattr(shutil, 'copyfile', fail)
    elif mode == 'during_copy':
        copy = shutil.copyfile
        def change(source, target):
            result = copy(source, target)
            plan.files[0].sources[-1].write_bytes(b'%PDF changed during copy')
            return result
        monkeypatch.setattr(shutil, 'copyfile', change)
    with pytest.raises((ValueError, OSError)):
        service.export(plan, root if mode == 'library_destination' else tmp_path, '失败打包')
    assert not (tmp_path / '失败打包').exists()
    assert not list(tmp_path.glob('.program-*'))
    if mode not in ('missing', 'changed', 'db_changed', 'during_copy'):
        assert {p: sha256_file(p) for p in before} == before
        assert [dict(r) for r in db.list_archived_scores()] == records


def test_missing_parts_report_and_single_layout(tmp_path):
    db, root, service, catalog, _, _ = fixture(tmp_path)
    from score_organizer.services.archive_service import ArchiveService
    from score_organizer.services.import_service import ImportService
    source = tmp_path / '0802独立总谱.pdf'
    source.write_bytes(b'%PDF total score')
    batch = ImportService(db).import_paths([source])
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch))
    total = next(s for s in service.catalog() if s['title'] == '独立总谱')
    plan = service.build_plan([(1, catalog[0]['id']), (3, total['id'])])
    assert len(plan.files[-1].sources) == 1
    assert len(plan.missing) == 4
    output = service.export(plan, tmp_path, '混合', make_zip=False)
    assert not (output / '节目乐谱.zip').exists()
    assert len(list(output.rglob('*.pdf'))) == 4


def test_safe_names_and_existing_empty_folder_no_overwrite(tmp_path):
    _, _, service, catalog, _, _ = fixture(tmp_path)
    assert safe_name('CON') == '_CON'
    assert safe_name('a/b:*') == 'a_b__'
    plan = service.build_plan([(1, catalog[0]['id'])])
    (tmp_path / '已有').mkdir()
    with pytest.raises(ValueError, match='已存在'):
        service.export(plan, tmp_path, '已有')


def test_publish_failure_rolls_back_owned_output(tmp_path, monkeypatch):
    from pathlib import Path
    _, _, service, catalog, before, _ = fixture(tmp_path)
    plan = service.build_plan([(1, catalog[0]['id'])])
    rename = Path.rename
    count = 0
    def fail_second(path, destination):
        nonlocal count
        count += 1
        if count == 2:
            raise OSError('publish failed')
        return rename(path, destination)
    monkeypatch.setattr(Path, 'rename', fail_second)
    with pytest.raises(OSError, match='publish failed'):
        service.export(plan, tmp_path, '发布失败')
    assert not (tmp_path / '发布失败').exists()
    assert not list(tmp_path.glob('.program-*'))
    assert {p: sha256_file(p) for p in before} == before


def test_duplicate_instrument_is_not_silently_chosen(tmp_path):
    db, _, service, catalog, _, _ = fixture(tmp_path)
    with db.session() as conn:
        conn.execute('''INSERT INTO score(song_id, instrument_id, source_name, source_path,
            source_sha256, output_name, storage_layout)
            SELECT song_id, instrument_id, source_name, source_path, source_sha256, output_name, storage_layout
            FROM score WHERE song_id = ? LIMIT 1''', (catalog[0]['id'],))
    with pytest.raises(ValueError, match='同乐器多份'):
        service.build_plan([(1, catalog[0]['id'])])


@pytest.mark.parametrize('location', ['destination', 'library'])
def test_junction_paths_are_rejected(tmp_path, location):
    import subprocess
    db, root, service, catalog, before, _ = fixture(tmp_path)
    plan = service.build_plan([(1, catalog[0]['id'])])
    external = tmp_path / 'external'
    external.mkdir()
    if location == 'library':
        source = plan.files[0].sources[0]
        folder = source.parent
        folder.rename(external / 'original')
        external = external / 'original'
        link = folder
    else:
        link = tmp_path / 'destination-link'
    result = subprocess.run(['cmd', '/c', 'mklink', '/J', str(link), str(external)], capture_output=True)
    assert result.returncode == 0, result.stdout + result.stderr
    try:
        with pytest.raises(ValueError, match='链接'):
            service.export(plan, link if location == 'destination' else tmp_path, '链接打包')
        assert not (external / '链接打包').exists()
        assert not (tmp_path / '链接打包').exists()
    finally:
        link.rmdir()
