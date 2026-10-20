import json

import pytest
from PySide6.QtWidgets import QApplication, QCheckBox, QLineEdit, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from test_force_archive import fixture
from test_menu_workflows import schedule, choose_target


def check_force_dialog(app, tmp_path, errors, cancel=None):
    dialog = app.activeModalWidget()
    assert dialog.windowTitle() == "确认归档"
    table = dialog.findChild(QTableWidget)
    assert table.rowCount() == 1
    assert "（01）" in table.item(0, 0).text()
    confirmation = dialog.findChild(QCheckBox, "force_archive_confirm")
    assert confirmation is not None and not confirmation.isChecked()
    submit = next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档")
    assert not submit.isEnabled()
    if cancel == "reject":
        dialog.reject()
    elif cancel == "unconfirmed":
        dialog.accept()
    else:
        confirmation.setChecked(True)
        assert submit.isEnabled()
        assert dialog.grab().save(str(tmp_path / "force-single-preview.png"))
        submit.click()


@pytest.mark.parametrize("unselected", [False, True])
def test_native_force_new_single_part_is_formal_and_only_selection_changes(tmp_path, monkeypatch, unselected):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path, ("01", "02", "03") if unselected else ("01",))
    before = [dict(row) for row in db.list_import_items(batch)][1:]
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    window.show()
    window.score_table.selectRow(0)
    errors, messages = [], []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    def archive():
        check_force_dialog(app, tmp_path, errors)
    def correction():
        assert app.activeModalWidget().findChild(QTableWidget, "archive_correction_changes") is not None
        archive()
    def editor():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "新建独立曲目与入库"
        dialog.findChild(QLineEdit, "correction_title").setText("一个声部也收录")
        button = dialog.findChild(QPushButton, "correction_archive")
        assert button.text() == "强制入库"
        schedule(app, correction, errors)
        button.click()
        if errors or messages:
            dialog.reject()
    schedule(app, editor, errors)
    window.menu_actions["force_new_song"].trigger()
    assert not errors and not messages
    song = db.find_archived_song("01", "一个声部也收录")
    assert song["seq"] == "001" and song["minimum_waived"]
    assert db.count_scores() == 1 and db.minimum_instruments("01") == 3
    score = db.list_archived_scores()[0]
    assert all(sha256_file(path) == hashes[sources[0]] for path in ArchiveService(db, root).score_paths(score))
    assert [dict(row) for row in db.list_import_items(batch)] == before
    if before:
        assert "已入库 1 首、1 份" in window.guidance_label.text()
        assert "未入库的分谱继续保留在草稿中" in window.guidance_label.text()
    assert {path: sha256_file(path) for path in sources} == hashes
    assert not (root / "待审查").exists()
    def catalog():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 1
        assert table.item(0, 3).text() == "1" and table.item(0, 4).text() == "1"
        assert table.item(0, 5).text() == "强制入库，声部待补齐"
        assert dialog.grab().save(str(tmp_path / "force-single-catalog.png"))
        dialog.reject()
    schedule(app, catalog, errors)
    window.show_archived_catalog()
    assert not errors
    window.close()


@pytest.mark.parametrize("cancel", ["reject", "unconfirmed"])
def test_force_final_preview_cancel_or_unconfirmed_cannot_waive_minimum(tmp_path, monkeypatch, cancel):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path)
    original = sha256_file(sources[0])
    before = dict(db.list_import_items(batch)[0])
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    def archive():
        check_force_dialog(app, tmp_path, errors, cancel)
    def editor():
        dialog = app.activeModalWidget()
        schedule(app, archive, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
        dialog.reject()
    schedule(app, editor, errors)
    window.menu_actions["force_new_song"].trigger()
    assert not errors and db.count_songs() == 0 and db.count_scores() == 0
    assert db.allocate_next_sequence() == "001" and not list(root.rglob("*.pdf"))
    assert len(db.list_import_items(batch)) == 1
    assert dict(db.list_import_items(batch)[0]) == before
    assert sha256_file(sources[0]) == original
    window.close()


def test_force_existing_target_accepts_one_ordinary_part_in_total_only_song(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, originals, batch, _ = fixture(tmp_path, ("08",))
    archive_service = ArchiveService(db, root)
    archive_service.commit_plan(batch, archive_service.build_plan(batch))
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    old = dict(db.list_archived_scores()[0])
    source = tmp_path / "0102另一个名称.pdf"
    source.write_bytes(b"%PDF better first violin")
    next_batch = ImportService(db).import_paths([source])
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def archive():
        check_force_dialog(app, tmp_path, errors)
    def correction():
        assert app.activeModalWidget().findChild(QTableWidget, "archive_correction_changes") is not None
        archive()
    schedule(app, lambda: choose_target(app, correction, errors), errors)
    window.menu_actions["force_assign_library"].trigger()
    assert not errors
    assert db.count_songs() == 1 and db.count_scores() == 2
    assert db.get_archived_song(song_id)["minimum_waived"]
    assert db.get_archived_song(song_id)["seq"] == "001"
    assert dict(next(row for row in db.list_archived_scores() if row["instrument_code"] == "08")) == old
    assert sha256_file(originals[0]) == old["source_sha256"]
    assert window.current_batch_id is None
    window.close()


def test_force_still_opens_existing_duplicate_comparison_instead_of_overwrite(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, originals, batch, ids = fixture(tmp_path)
    archive_service = ArchiveService(db, root)
    archive_service.commit_plan(batch, archive_service.build_plan(batch, ids, force=True))
    copies = {path: sha256_file(path) for path in root.rglob("*.pdf")}
    source = tmp_path / "0102独立新作品.pdf"
    source.write_bytes(originals[0].read_bytes())
    next_batch = ImportService(db).import_paths([source])
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    def conflict():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "已有入库乐谱：确认是否转入重复库"
        assert dialog.findChild(QTableWidget, "archived_duplicate_parts").rowCount() >= 2
        dialog.reject()
    def editor():
        dialog = app.activeModalWidget()
        schedule(app, conflict, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
        dialog.reject()
    schedule(app, editor, errors)
    window.menu_actions["force_new_song"].trigger()
    assert not errors and db.count_songs() == 1 and db.count_scores() == 1
    assert len(db.list_import_items(next_batch)) == 1
    assert {path: sha256_file(path) for path in copies} == copies
    assert not (root / "已确认重复").exists()
    window.close()
