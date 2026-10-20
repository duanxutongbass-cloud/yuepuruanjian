import json

from PySide6.QtCore import Qt, QTimer
from PySide6.QtTest import QTest
from PySide6.QtWidgets import (
    QApplication, QComboBox, QHeaderView, QMenu, QMessageBox,
    QPlainTextEdit, QPushButton, QTableWidgetItem,
)

from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from score_organizer.ui.readable_table import ReadableTableWidget


def test_full_information_preserves_long_values_metadata_and_explicit_tooltip():
    app = QApplication.instance() or QApplication([])
    table = ReadableTableWidget(3, 3)
    table.setHorizontalHeaderLabels(["曲名", "文件", "乐器"])
    title = "很长的中文曲名" * 30
    item = QTableWidgetItem(title)
    item.setData(Qt.UserRole, 123)
    table.setItem(0, 0, item)
    file = QTableWidgetItem("源文件.pdf")
    file.setToolTip("C:/完整目录/源文件.pdf")
    file.setData(Qt.UserRole + 1, ["C:/源文件.pdf", "C:/库内文件.pdf", "C:/源文件.pdf"])
    table.setItem(0, 1, file)
    instrument = QComboBox()
    instrument.addItem("01 第一小提琴")
    table.setCellWidget(0, 2, instrument)
    table.setItem(1, 0, QTableWidgetItem("另一首完整曲名"))
    table.setItem(2, 0, QTableWidgetItem("隐藏的记录"))
    table.setRowHidden(2, True)
    content = table.full_information([0, 1, 2, -1, 99])
    assert title in content and "另一首完整曲名" in content
    assert "隐藏的记录" not in content
    assert "01 第一小提琴" in content
    assert "C:/完整目录/源文件.pdf" in content and "C:/库内文件.pdf" in content
    assert content.count("C:/源文件.pdf") == 1
    assert item.toolTip() == title and item.data(Qt.UserRole) == 123
    assert file.toolTip() == "C:/完整目录/源文件.pdf"
    assert not table.wordWrap()
    table.close()
    app.processEvents()


def test_no_selection_or_only_hidden_selection_does_not_show_old_record(monkeypatch):
    app = QApplication.instance() or QApplication([])
    table = ReadableTableWidget(1, 1)
    table.setItem(0, 0, QTableWidgetItem("旧记录"))
    messages = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: messages.append(args[-1]))
    table.show_full_information()
    table.selectRow(0)
    table.setRowHidden(0, True)
    table.show_full_information()
    assert messages == ["请先选择要查看的记录", "请先选择要查看的记录"]
    table.close()
    app.processEvents()


def test_native_context_menu_opens_clicked_row_readonly_and_does_not_edit(tmp_path):
    app = QApplication.instance() or QApplication([])
    table = ReadableTableWidget(2, 1)
    table.setHorizontalHeaderLabels(["曲名"])
    table.setSelectionBehavior(ReadableTableWidget.SelectRows)
    table.setItem(0, 0, QTableWidgetItem("原先选择的记录"))
    title = "一个非常长的曲目名称" * 12
    table.setItem(1, 0, QTableWidgetItem(title))
    table.resize(500, 220)
    table.show()
    app.processEvents()
    table.selectRow(0)
    def close_details():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "完整信息"
        text = dialog.findChild(QPlainTextEdit, "full_information_text")
        assert text.isReadOnly() and title in text.toPlainText()
        assert "原先选择的记录" not in text.toPlainText()
        dialog.grab().save(str(tmp_path / "context-full-text.png"))
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "关闭").click()
    def click_menu():
        menu = app.activePopupWidget()
        assert isinstance(menu, QMenu)
        QTimer.singleShot(50, close_details)
        QTest.mouseClick(menu, Qt.LeftButton, pos=menu.actionGeometry(menu.actions()[0]).center())
    QTimer.singleShot(50, click_menu)
    table._show_content_menu(table.visualItemRect(table.item(1, 0)).center())
    assert table.item(1, 0).text() == title
    assert table.item(0, 0).text() == "原先选择的记录"
    table.close()
    app.processEvents()


def test_main_long_names_warning_details_and_pdf_actions_native(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    title = "萤火虫之墓《可爱的家》电影原声长名称测试" * 2
    sources = []
    for code, suffix in [("01", "小提琴_1"), ("02", "小提琴_2"), ("03", "中提琴")]:
        path = tmp_path / f"{code}01{title}-{suffix}.pdf"
        path.write_bytes(f"%PDF original {code}".encode())
        sources.append(path)
    batch = ImportService(db).import_paths(sources)
    before = [dict(item) for item in db.list_import_items(batch)]
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    window.show()
    app.processEvents()
    for width, height in [(1100, 720), (900, 620)]:
        window.resize(width, height)
        app.processEvents()
        button = window.findChild(QPushButton, "score_full_information")
        assert button.geometry().right() < button.parentWidget().width()
        assert window.score_table.horizontalHeader().sectionResizeMode(2) == QHeaderView.Interactive
        window.grab().save(str(tmp_path / f"long-names-{width}.png"))
    assert window.score_table.columnViewportPosition(5) + window.score_table.columnWidth(5) <= window.score_table.viewport().width()
    assert window.score_table.item(0, 2).toolTip() == f"{title}-小提琴_1"
    assert window.report_table.item(0, 1).toolTip() == window.report_table.item(0, 1).text()
    old_height = window.score_table.rowHeight(0)
    window.score_table.horizontalHeader().resizeSection(2, 330)
    app.processEvents()
    assert window.score_table.columnWidth(2) == 330 and window.score_table.rowHeight(0) == old_height
    assert window.score_table.horizontalScrollBar().maximum() > 0
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    window.score_table.selectRow(0)
    window.score_table.cellDoubleClicked.emit(0, 1)
    assert opened == [sources[0]]
    def inspect_score():
        dialog = app.activeModalWidget()
        text = dialog.findChild(QPlainTextEdit, "full_information_text")
        assert text.isReadOnly()
        assert sources[0].name in text.toPlainText()
        assert str(sources[0]) in text.toPlainText()
        assert window.score_table.item(0, 3).text() in text.toPlainText()
        dialog.grab().save(str(tmp_path / "long-score-full.png"))
        dialog.reject()
    QTimer.singleShot(50, inspect_score)
    window.findChild(QPushButton, "score_full_information").click()
    window.warning_table.selectRow(0)
    warning_id = window.warning_table.item(0, 0).data(Qt.UserRole)
    warning = next(warning for warning in db.list_warnings(batch) if warning["id"] == warning_id)
    details = json.loads(warning["details"])
    assert details["suggested_title"] == title
    def inspect_warning():
        dialog = app.activeModalWidget()
        text = dialog.findChild(QPlainTextEdit, "full_information_text")
        assert "乐器后缀需统一曲名" in text.toPlainText()
        assert title in text.toPlainText() and "建议统一为" in text.toPlainText()
        assert all(str(path) in text.toPlainText() for path in sources)
        dialog.grab().save(str(tmp_path / "long-warning-full.png"))
        dialog.reject()
    QTimer.singleShot(50, inspect_warning)
    window.menu_actions["warning_full_information"].trigger()
    window.warning_table.cellDoubleClicked.emit(0, 0)
    assert set(opened) == set(sources)
    assert [dict(item) for item in db.list_import_items(batch)] == before
    assert {path: sha256_file(path) for path in sources} == hashes
    assert db.count_songs() == 0
    window.close()
    app.processEvents()
