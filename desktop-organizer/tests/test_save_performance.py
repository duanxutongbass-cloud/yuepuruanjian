import sqlite3

import pytest
from PySide6.QtWidgets import QApplication

from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.report_service import ReportService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def setup_data(tmp_path, count=18):
    db = Database(tmp_path / "performance.sqlite3")
    db.initialize()
    paths = []
    for index in range(count):
        path = tmp_path / f"{index % 3 + 1:02}01作品{index // 3}.pdf"
        path.write_bytes(f"%PDF fixture {index}".encode())
        paths.append(path)
    return db, paths


def count_connections(db, monkeypatch):
    calls = []
    original = db.connect
    def connect():
        calls.append(1)
        return original()
    monkeypatch.setattr(db, "connect", connect)
    return calls


def test_import_uses_one_batch_commit_and_preserves_hashes(tmp_path, monkeypatch):
    db, paths = setup_data(tmp_path, 90)
    hashes = [sha256_file(path) for path in paths]
    calls = count_connections(db, monkeypatch)
    batch = ImportService(db).import_paths(paths)
    assert len(calls) == 3
    items = db.list_import_items(batch)
    assert [item["source_sha256"] for item in items] == hashes
    assert [sha256_file(path) for path in paths] == hashes


@pytest.mark.parametrize("existing", [False, True])
def test_failed_batch_insert_rolls_back_all_new_rows(tmp_path, monkeypatch, existing):
    db, paths = setup_data(tmp_path, 3)
    service = ImportService(db)
    batch = service.import_paths(paths[:1]) if existing else None
    before = db.list_import_items(batch) if existing else []
    original = db.add_import_item
    calls = []
    def fail(*args, **kwargs):
        calls.append(1)
        if len(calls) == 2:
            raise sqlite3.OperationalError("injected insert failure")
        return original(*args, **kwargs)
    monkeypatch.setattr(db, "add_import_item", fail)
    with pytest.raises(sqlite3.OperationalError):
        service.import_paths(paths[1:], batch_id=batch)
    if existing:
        assert db.list_import_items(batch) == before
    else:
        with db.session() as connection:
            assert connection.execute("SELECT COUNT(*) FROM import_batch").fetchone()[0] == 0
    assert all(path.read_bytes().startswith(b"%PDF fixture") for path in paths)
    assert db._transaction_connection is None


def test_joined_transaction_does_not_commit_early_and_rolls_back(tmp_path):
    db, _ = setup_data(tmp_path)
    with pytest.raises(RuntimeError):
        with db.transaction() as outer:
            with db.transaction(reuse=True) as inner:
                assert inner is outer
                db.set_setting("test_join", "pending")
            with db.connect() as independent:
                assert independent.execute("SELECT value FROM settings WHERE key='test_join'").fetchone() is None
            with pytest.raises(ValueError):
                with db.transaction():
                    pass
            raise RuntimeError("abort outer")
    assert db.get_setting("test_join") is None
    assert db._transaction_connection is None


def test_hash_read_failure_does_not_leave_partial_drafts(tmp_path, monkeypatch):
    db, paths = setup_data(tmp_path, 3)
    service = ImportService(db)
    batch = service.import_paths(paths[:1])
    before = db.list_import_items(batch)
    def fail(path):
        if path == paths[2]:
            raise OSError("injected source read failure")
        return sha256_file(path)
    monkeypatch.setattr("score_organizer.services.import_service.sha256_file", fail)
    with pytest.raises(OSError):
        service.import_paths(paths[1:], batch_id=batch)
    assert db.list_import_items(batch) == before


@pytest.mark.parametrize("service_type", [GroupingService, ReportService])
def test_scan_or_report_uses_one_connection(tmp_path, monkeypatch, service_type):
    db, paths = setup_data(tmp_path, 90)
    batch = ImportService(db).import_paths(paths)
    calls = count_connections(db, monkeypatch)
    service = service_type(db)
    if service_type is GroupingService:
        service.refresh_warnings(batch)
    else:
        assert len(service.build_song_reports(batch)) == 30
    assert len(calls) == 1


def test_native_refresh_scans_and_renders_once_and_sees_new_drafts(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, paths = setup_data(tmp_path)
    batch = ImportService(db).import_paths(paths[:3])
    window = MainWindow(db)
    window.show()
    app.processEvents()
    scans, renders = [], []
    original_scan = GroupingService.refresh_warnings
    original_render = window.refresh_import_items
    def scan(self, *args, **kwargs):
        scans.append(1)
        return original_scan(self, *args, **kwargs)
    def render():
        renders.append(1)
        return original_render()
    monkeypatch.setattr(GroupingService, "refresh_warnings", scan)
    monkeypatch.setattr(window, "refresh_import_items", render)
    calls = count_connections(db, monkeypatch)
    try:
        window.refresh_draft_view()
        assert len(scans) == len(renders) == len(calls) == 1
        assert window.score_table.rowCount() == 3
        ImportService(db).import_paths(paths[3:6], batch_id=batch)
        window.refresh_draft_view()
        assert len(scans) == len(renders) == 2
        assert window.score_table.rowCount() == 6
        assert window.grab().save(str(tmp_path / "performance-native.png"))
    finally:
        window.close()


def test_default_report_revalidates_each_operation(tmp_path, monkeypatch):
    db, paths = setup_data(tmp_path, 3)
    batch = ImportService(db).import_paths(paths[:1])
    calls = []
    original = GroupingService.refresh_warnings
    def scan(self, *args, **kwargs):
        calls.append(1)
        return original(self, *args, **kwargs)
    monkeypatch.setattr(GroupingService, "refresh_warnings", scan)
    service = ReportService(db)
    assert service.build_song_reports(batch)[0].instrument_count == 1
    ImportService(db).import_paths(paths[1:], batch_id=batch)
    assert service.build_song_reports(batch)[0].instrument_count == 3
    assert len(calls) == 2
