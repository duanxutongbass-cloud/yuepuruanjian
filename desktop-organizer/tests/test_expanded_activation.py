import json
from pathlib import Path

import pytest
from PySide6.QtWidgets import QApplication

from score_organizer.main import startup_database,remember_expanded
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services import runtime_services
from score_organizer.storage.database import Database,UnsupportedDatabaseVersion
from score_organizer.storage.expanded_database import activate_candidate,ExpandedDatabase,RuntimeLock,MARKER
from score_organizer.ui.main_window import MainWindow
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_library_upgrade_snapshot import make_library,contents,db_state


def active(tmp_path):
    old,root,sources=make_library(tmp_path)
    manifest=prepare_relocated_library(old.path,tmp_path/'expanded')
    database=activate_candidate(manifest)
    return old,root,sources,database


def test_activation_gui_import_archive_and_reopen_keep_originals_and_full_numbers(tmp_path,monkeypatch):
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE',str(tmp_path/'profile'))
    old,root,sources,db=active(tmp_path)
    old_state,old_files=db_state(old.path),contents(root)
    app=QApplication.instance() or QApplication([])
    window=MainWindow(db)
    try:
        assert '扩位编号' in window.windowTitle()
        paths=[]
        for code in ('01','02','03'):
            path=tmp_path/f'{code}-01-1000-Synthetic expanded.pdf'
            path.write_bytes(b'%PDF synthetic isolated activation '+code.encode());paths.append(path)
        batch=runtime_services.importer(db).import_paths(paths)
        window.current_batch_id=batch;window.refresh_import_items()
        names=[window.score_table.item(row,3).text() for row in range(window.score_table.rowCount())]
        assert all('-01-1000-' in name for name in names)
        service=runtime_services.archive(db,db.get_setting('output_root'))
        plans=service.build_plan(batch);assert plans[0].seq=='1000'
        service.commit_plan(batch,plans)
        publish=TeamPublishDialog(db)
        try:
            assert publish.service._api_prefix=='/v2'
        finally:publish.close()
        remember_expanded(db.path)
        reopened=startup_database();reopened.initialize()
        assert type(reopened) is ExpandedDatabase and reopened.path==db.path
        assert reopened.allocate_next_sequence()=='002'
        assert len([r for r in reopened.list_archived_scores() if r['seq']=='1000'])==3
        assert db_state(old.path)==old_state and contents(root)==old_files
        assert all(path.is_file() for path in [*sources,*paths])
    finally:window.close()


def test_missing_changed_or_relocated_runtime_never_falls_back_or_creates_database(tmp_path,monkeypatch):
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE',str(tmp_path/'profile'))
    old,root,sources,db=active(tmp_path)
    remember_expanded(db.path)
    marker=db.path.parent/MARKER;before=marker.read_bytes()
    marker.unlink()
    with pytest.raises(ValueError):startup_database().initialize()
    marker.write_bytes(before)
    with pytest.raises(UnsupportedDatabaseVersion):Database(db.path).connect()
    with pytest.raises(ValueError):db.set_setting('output_root',str(tmp_path/'wrong'))
    assert db.get_setting('output_root')==str(db.path.parent/'files')


def test_second_runtime_lock_is_refused_and_released(tmp_path):
    _,_,_,db=active(tmp_path)
    first=RuntimeLock(db)
    try:
        with pytest.raises(ValueError):RuntimeLock(db)
    finally:first.close()
    second=RuntimeLock(db);second.close()


def test_unactivated_or_missing_library_cannot_be_created(tmp_path):
    missing=tmp_path/'runtime/library.sqlite3'
    with pytest.raises(ValueError):ExpandedDatabase(missing).initialize()
    assert not missing.exists()
