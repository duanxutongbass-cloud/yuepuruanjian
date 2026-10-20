import json

import pytest
from PySide6.QtCore import Qt, QTimer, QItemSelectionModel
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QCheckBox, QComboBox, QDialog, QLineEdit, QMenu, QMessageBox, QPushButton, QTableWidget

from score_organizer.storage.database import Database
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from score_organizer.ui.dictionary_dialog import DictionaryDialog
from test_correction_service import add_existing


def fixture(tmp_path):
    db = Database(tmp_path / "menu.sqlite3")
    db.initialize()
    db.set_setting("output_root", str(tmp_path / "out"))
    return db


def schedule(app, callback, errors):
    def run():
        try:
            callback()
        except Exception as exc:
            errors.append(exc)
            modal = app.activeModalWidget()
            if isinstance(modal, QDialog):
                modal.reject()
            popup = app.activePopupWidget()
            if popup:
                popup.close()
    QTimer.singleShot(80, run)


@pytest.mark.parametrize("width,height", [(1100, 720), (1600, 900), (900, 640)])
def test_main_layout_keeps_common_controls_and_hides_management(tmp_path, width, height):
    app = QApplication.instance() or QApplication([])
    db = fixture(tmp_path)
    paths = []
    for index in range(55):
        path = tmp_path / f"0101作品{index}.pdf"
        path.write_bytes(f"%PDF {index}".encode())
        paths.append(path)
    ImportService(db).import_paths(paths)
    window = MainWindow(db)
    window.resize(width, height)
    window.show()
    app.processEvents()
    assert not window.category_table.isVisible()
    assert not window.manual_title_input.isVisible()
    assert [action.text() for action in window.menuBar().actions()] == ["文件", "节目单打包", "团队发布", "分谱处理", "乐谱库", "设置", "数据管理"]
    assert window.menu_actions["team_publish"].isEnabled()
    assert window.score_table.height() > height * .45
    assert window.score_table.columnViewportPosition(5) + window.score_table.columnWidth(5) <= window.score_table.viewport().width()
    visible = [button for button in window.findChildren(QPushButton) if button.isVisible()]
    assert len(visible) <= 8
    for button in visible:
        assert button.width() >= button.fontMetrics().horizontalAdvance(button.text()) + 12
        assert window.rect().contains(button.mapTo(window, button.rect().center()))
    assert window.grab().save(str(tmp_path / f"layout-{width}.png"))
    window.score_table.horizontalHeader().resizeSection(2, 330)
    window.resize(width + 100, height)
    app.processEvents()
    assert window.score_table.columnWidth(2) == 330
    window.close()


def test_native_instrument_menu_adds_code_with_confirmation_backup(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db = fixture(tmp_path)
    window = MainWindow(db)
    window.show()
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    errors = []
    def add():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "乐器管理"
        dialog.findChild(QLineEdit, "dictionary_code").setText("10")
        dialog.findChild(QLineEdit, "dictionary_name").setText("长笛")
        dialog.findChild(QComboBox, "dictionary_archive_mode").setCurrentIndex(1)
        dialog.findChild(QPushButton, "dictionary_save").click()
        assert "10" in db.instrument_codes()
        assert dialog.grab().save(str(tmp_path / "instrument-management.png"))
        dialog.reject()
    schedule(app, add, errors)
    window.menu_actions["instrument_management"].trigger()
    assert not errors
    assert len(list((tmp_path / "规则备份").glob("*.sqlite3"))) == 1
    window.close()


def test_dictionary_cancel_duplicate_and_used_code_protection(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db = fixture(tmp_path)
    dialog = DictionaryDialog(db, "instrument")
    dialog.show()
    dialog.code.setText("10")
    dialog.name.setText("长笛")
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.No)
    dialog.save_entry()
    assert "10" not in db.instrument_codes()
    warnings = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args[2]))
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    dialog.code.setText("07")
    dialog.name.setText("错误覆盖")
    dialog.save_entry()
    assert "该编号已存在" in warnings[-1]
    assert next(row for row in db.list_instruments() if row["code"] == "07")["name"] == "打击乐"
    add_existing(db, tmp_path, "007", "天空之城")
    dialog.table.selectRow(0)
    assert dialog.code.isReadOnly() and dialog.code.text() == "01"
    dialog.name.setText("不能移动旧文件")
    dialog.save_entry()
    assert "不能直接改名" in warnings[-1]
    dialog.close()
    app.processEvents()


def test_category_menu_edits_and_refreshes_category_choices(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db = fixture(tmp_path)
    window = MainWindow(db)
    errors = []
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    def edit():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "分类管理"
        dialog.code.setText("10")
        dialog.name.setText("新增风格")
        dialog.save_entry()
        dialog.reject()
    schedule(app, edit, errors)
    window.menu_actions["category_management"].trigger()
    assert not errors
    assert "10" in db.category_codes()
    assert window.manual_category_input.findData("10") >= 0
    window.close()


def target_fixture(tmp_path, code="04"):
    db = fixture(tmp_path)
    song_id = add_existing(db, tmp_path, "007", "天空之城")
    paths = []
    for number, title in ((code, "天空之成"), ("05", "天空城"), ("06", "独立作品")):
        path = tmp_path / f"{number}02{title}.pdf"
        path.write_bytes(f"%PDF added {number}".encode())
        paths.append(path)
    batch = ImportService(db).import_paths(paths)
    return db, song_id, batch, paths


def choose_target(app, followup, errors):
    dialog = app.activeModalWidget()
    assert dialog.windowTitle() == "统一为库内名称"
    table = dialog.findChild(QTableWidget, "correction_candidates")
    table.selectRow(0)
    schedule(app, followup, errors)
    next(button for button in dialog.findChildren(QPushButton) if button.text() == "采用库内名称").click()


def test_native_right_click_multiselection_assigns_existing_standard_and_archives(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, song_id, batch, sources = target_fixture(tmp_path)
    before = {path: sha256_file(path) for path in sources}
    unselected = dict(db.list_import_items(batch)[2])
    window = MainWindow(db)
    window.show()
    app.processEvents()
    window.score_table.selectRow(0)
    window.score_table.selectionModel().select(window.score_table.model().index(1, 0), QItemSelectionModel.Select | QItemSelectionModel.Rows)
    errors = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 2
        assert {table.item(row, 3).text() for row in range(2)} == {"0401007天空之城.pdf", "0501007天空之城.pdf"}
        dialog.findChild(QCheckBox, "force_archive_confirm").setChecked(True)
        dialog.accept()
    def preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        assert dialog.findChild(QTableWidget, "archive_correction_changes").rowCount() == 2
        archive()
    def choose():
        choose_target(app, preview, errors)
    def menu():
        popup = app.activePopupWidget()
        assert isinstance(popup, QMenu)
        assert popup.grab().save(str(tmp_path / "draft-context.png"))
        assert {cell.row() for cell in window.score_table.selectedItems()} == {0, 1}
        action = window.menu_actions["force_assign_library"]
        assert action in popup.actions()
        schedule(app, choose, errors)
        QTest.mouseClick(popup, Qt.LeftButton, pos=popup.actionGeometry(action).center())
    schedule(app, menu, errors)
    position = window.score_table.visualItemRect(window.score_table.item(1, 1)).center()
    window.score_table._show_content_menu(position)
    assert not errors
    assert len(db.list_archived_scores(song_id)) == 5
    assert [dict(row) for row in db.list_import_items(batch)] == [unselected]
    assert {path: sha256_file(path) for path in sources} == before
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    window.close()


@pytest.mark.parametrize("cancel_at", ["choose", "correction", "archive"])
def test_explicit_assign_cancel_never_writes_formal_library(tmp_path, monkeypatch, cancel_at):
    app = QApplication.instance() or QApplication([])
    db, song_id, batch, _ = target_fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(window, "show_archive_failure_details", lambda *args: None)
    def archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        dialog.reject()
    def preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        if cancel_at == "correction":
            dialog.reject()
        else:
            archive()
    def choose():
        if cancel_at == "choose":
            app.activeModalWidget().reject()
        else:
            choose_target(app, preview, errors)
    schedule(app, choose, errors)
    window.menu_actions["force_assign_library"].trigger()
    assert not errors
    assert len(db.list_archived_scores(song_id)) == 3
    after = [dict(row) for row in db.list_import_items(batch)]
    assert before == after
    window.close()


def test_explicit_assign_does_not_overwrite_existing_instrument(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, song_id, batch, _ = target_fixture(tmp_path, code="01")
    hashes = {path: sha256_file(path) for path in (tmp_path / "out").rglob("*.pdf")}
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    def conflict():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "已有入库乐谱：确认是否转入重复库"
        assert dialog.findChild(QTableWidget, "archived_duplicate_parts").rowCount() >= 2
        dialog.reject()
    def preview():
        dialog = app.activeModalWidget()
        schedule(app, conflict, errors)
        dialog.accept()
    schedule(app, lambda: choose_target(app, preview, errors), errors)
    window.assign_selected_to_library()
    assert not errors
    assert len(db.list_archived_scores(song_id)) == 3 and len(db.list_import_items(batch)) == 3
    assert all(sha256_file(path) == digest for path, digest in hashes.items())
    window.close()
