import json
from pathlib import Path
import shutil
import zipfile

import pytest

from score_organizer.services.expanded_program_service import ExpandedProgramService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.program_service import ProgramService
from test_expanded_catalog_service import archived
from test_library_upgrade_snapshot import contents, db_state


@pytest.mark.parametrize('number', ['1000', '10000', '9223372036854775807'])
@pytest.mark.parametrize('make_zip', [True, False])
def test_actual_export_mixed_numbers_keeps_originals_and_user_order(tmp_path, number, make_zip):
    old, old_root, sources, db, root, archive, song = archived(tmp_path, number)
    legacy = db.find_archived_song('01', '旧曲')['id']
    before = (db_state(old.path), contents(old_root), db_state(db.path), contents(root), {p:p.read_bytes() for p in sources})
    service = ExpandedProgramService(db, root)
    assert [r['seq'] for r in service.catalog()] == ['001', number]
    plan = service.build_plan([(8, legacy), (2, song)])
    assert [(r[0],r[3]) for r in plan.rows] == [(2,number),(8,'001')]
    destination = service.export(plan, tmp_path, '扩位演出', make_zip)
    manifest = json.loads((destination/'节目单.json').read_text('utf-8'))
    assert manifest['format'] == 'score-program-v2' and manifest['namingVersion'] == 2
    assert [entry['library_seq'] for entry in manifest['entries']] == [number,'001']
    assert [entry['order'] for entry in manifest['entries']] == [2,8]
    text = (destination/'节目单.txt').read_text('utf-8')
    assert f'库编号01-{number}' in text and '库编号01001' in text
    assert len(list(destination.rglob('*.pdf'))) == 6
    for part in plan.files:
        assert sha256_file(destination/part.relative) == part.digest
    if make_zip:
        with zipfile.ZipFile(destination/'节目乐谱.zip') as bundle:
            assert bundle.testzip() is None
            for part in plan.files:
                assert bundle.read(part.relative) == part.sources[0].read_bytes()
    else:
        assert not (destination/'节目乐谱.zip').exists()
    assert (db_state(old.path), contents(old_root), db_state(db.path), contents(root), {p:p.read_bytes() for p in sources}) == before
    assert not list(tmp_path.glob('.program-*'))


def test_single_copy_total_score_has_no_extra_library_copy(tmp_path):
    _, _, sources, db, root, _, song = archived(tmp_path, codes=('08',))
    before, rows = contents(root), [dict(r) for r in db.list_archived_scores()]
    service = ExpandedProgramService(db, root)
    plan = service.build_plan([(1,song)])
    assert len(plan.files) == 1 and len(plan.files[0].sources) == 1
    destination = service.export(plan,tmp_path,'独立总谱')
    assert len(list(destination.rglob('*.pdf'))) == 1
    assert contents(root) == before and [dict(r) for r in db.list_archived_scores()] == rows
    assert all(p.is_file() for p in sources)


@pytest.mark.parametrize('codes', [('01','02','03'),('08',)])
def test_default_program_service_cannot_emit_v1_manifest_with_expanded_number(tmp_path,codes):
    _, _, _, db, root, _, song = archived(tmp_path,codes=codes)
    with pytest.raises(ValueError,match='999'):
        ProgramService(db,root).build_plan([(1,song)])
    assert not list(tmp_path.glob('.program-*'))


@pytest.mark.parametrize('location',['files','runtime','sources'])
def test_export_never_contaminates_candidate_database_or_source_directories(tmp_path,location):
    _, _, _, db, root, _, song = archived(tmp_path)
    service = ExpandedProgramService(db,root)
    plan = service.build_plan([(1,song)])
    before = contents(db.path.parent)
    parent = {'files':root,'runtime':db.path.parent,'sources':db.path.parent/'sources'}[location]
    assert parent.is_dir()
    with pytest.raises(ValueError,match='之外'):
        service.export(plan,parent,'错误位置')
    assert contents(db.path.parent) == before


@pytest.mark.parametrize('damage',['missing','changed','metadata','copy_error','during_copy','setting_during_copy','rename_error'])
def test_failure_leaves_no_export_and_can_retry_after_external_cause_removed(tmp_path,monkeypatch,damage):
    old, old_root, sources, db, root, _, song = archived(tmp_path)
    service = ExpandedProgramService(db,root)
    plan = service.build_plan([(1,song)])
    old_before = (db_state(old.path),contents(old_root),{p:p.read_bytes() for p in sources})
    files_before = {p:p.read_bytes() for file in plan.files for p in file.sources}
    source = plan.files[0].sources[-1]
    title = db.get_archived_song(song)['display_title']
    with monkeypatch.context() as patch:
        if damage == 'missing': source.unlink()
        elif damage == 'changed': source.write_bytes(b'%PDF changed')
        elif damage == 'metadata':
            with db.session() as connection:
                connection.execute('UPDATE song SET display_title=? WHERE id=?',('变更',song))
        elif damage in ('copy_error','during_copy','setting_during_copy'):
            copy = shutil.copyfile
            def change(original,destination):
                if damage == 'copy_error': raise OSError('copy failed')
                result = copy(original,destination)
                if damage == 'during_copy': source.write_bytes(b'%PDF changed during copy')
                else: db.set_setting('output_root',str(tmp_path/'wrong-root'))
                return result
            patch.setattr(shutil,'copyfile',change)
        else:
            rename = Path.rename
            calls = 0
            def fail_second(path,destination):
                nonlocal calls
                calls += 1
                if calls == 2: raise OSError('rename failed')
                return rename(path,destination)
            patch.setattr(Path,'rename',fail_second)
        with pytest.raises((ValueError,OSError)):
            service.export(plan,tmp_path,'失败可重试')
    assert not (tmp_path/'失败可重试').exists() and not list(tmp_path.glob('.program-*'))
    assert (db_state(old.path),contents(old_root),{p:p.read_bytes() for p in sources}) == old_before
    for path,data in files_before.items(): path.write_bytes(data)
    if damage == 'metadata':
        with db.session() as connection:
            connection.execute('UPDATE song SET display_title=? WHERE id=?',(title,song))
    if damage == 'setting_during_copy': db.set_setting('output_root',str(root))
    result = service.export(service.build_plan([(1,song)]),tmp_path,'失败可重试')
    assert len(list(result.rglob('*.pdf'))) == 3


@pytest.mark.parametrize('mismatch',['old_database','wrong_root','changed_setting'])
def test_all_candidate_entry_points_check_runtime_before_creating_output(tmp_path,mismatch):
    old, old_root, _, db, root, _, song = archived(tmp_path)
    good = ExpandedProgramService(db,root)
    plan = good.build_plan([(1,song)])
    if mismatch == 'old_database': service = ExpandedProgramService(old,old_root)
    elif mismatch == 'wrong_root': service = ExpandedProgramService(db,old_root)
    else:
        db.set_setting('output_root',str(old_root))
        service = good
    before = (db_state(old.path),db_state(db.path),contents(root))
    for action in [service.catalog,lambda:service.build_plan([(1,song)]),lambda:service.export(plan,tmp_path,'门禁')]:
        with pytest.raises(ValueError): action()
    assert (db_state(old.path),db_state(db.path),contents(root)) == before
    assert not (tmp_path/'门禁').exists() and not list(tmp_path.glob('.program-*'))
