import json

import pytest
from PySide6.QtCore import QItemSelectionModel, Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QCheckBox, QComboBox, QLabel, QLineEdit, QMenu, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from test_menu_workflows import schedule
from test_new_song import fixture


@pytest.mark.parametrize("existing,special,context", [(True, False, True), (False, False, False), (True, True, False)])
def test_native_new_song_entry_can_create_without_any_existing_target(tmp_path, monkeypatch, existing, special, context):
    app = QApplication.instance() or QApplication([])
    db, root, old_id, batch, ids, sources = fixture(tmp_path, ("08",) if special else ("01", "02", "03"), existing)
    if existing:
        service = CorrectionService(db)
        service.apply(service.build_plan(batch, ids, target_song_id=old_id))
    other = tmp_path / "0602另一首.pdf"
    other.write_bytes(b"%PDF unrelated piece")
    ImportService(db).import_paths([other], batch_id=batch)
    untouched = dict(db.list_import_items(batch)[-1])
    hashes = {path: sha256_file(path) for path in [*sources, other, *root.rglob("*.pdf")]}
    window = MainWindow(db)
    window.show()
    app.processEvents()
    window.score_table.clearSelection()
    for row in range(len(ids)):
        window.score_table.selectionModel().select(window.score_table.model().index(row, 0), QItemSelectionModel.Select | QItemSelectionModel.Rows)
    errors, warnings = [], []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args[-1]))

    def archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == len(ids)
        sequence = "001"
        assert all(sequence + "天空之城舞曲.pdf" in table.item(row, 3).text() for row in range(len(ids)))
        dialog.findChild(QCheckBox, "force_archive_confirm").setChecked(True)
        assert dialog.grab().save(str(tmp_path / "new-song-archive.png"))
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档").click()

    def preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget, "archive_correction_changes")
        assert table.rowCount() == len(ids)
        if existing:
            assert all("007" in table.item(row, 4).text() and "待分配" in table.item(row, 4).text() for row in range(len(ids)))
        archive()

    def editor():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "新建独立曲目与入库"
        table = dialog.findChild(QTableWidget, "correction_parts")
        assert len({cell.row() for cell in table.selectedItems()}) == len(ids)
        assert dialog.findChild(QComboBox, "correction_destination").currentData() == "new_song"
        dialog.findChild(QComboBox, "correction_category").setCurrentIndex(dialog.findChild(QComboBox, "correction_category").findData("02"))
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城舞曲")
        assert dialog.grab().save(str(tmp_path / "new-song-editor.png"))
        schedule(app, preview, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
        if errors or warnings:
            dialog.reject()

    def menu():
        popup = app.activePopupWidget()
        assert isinstance(popup, QMenu)
        action = window.menu_actions["force_new_song"]
        assert action in popup.actions()
        assert dialog_entry_in_top_menu(window, action)
        assert popup.grab().save(str(tmp_path / "new-song-right-click.png"))
        schedule(app, editor, errors)
        QTest.mouseClick(popup, Qt.LeftButton, pos=popup.actionGeometry(action).center())

    if context:
        schedule(app, menu, errors)
        window.score_table._show_content_menu(window.score_table.visualItemRect(window.score_table.item(0, 1)).center())
    else:
        schedule(app, editor, errors)
        window.menu_actions["force_new_song"].trigger()
    assert not errors and not warnings
    assert db.count_songs() == (2 if existing else 1)
    assert db.find_archived_song("02", "天空之城舞曲")["seq"] == "001"
    assert [dict(row) for row in db.list_import_items(batch)] == [untouched]
    assert {path: sha256_file(path) for path in hashes} == hashes
    new_scores = db.list_archived_scores(db.find_archived_song("02", "天空之城舞曲")["id"])
    assert len(new_scores) == len(ids)
    assert all(row["storage_layout"] == ("instrument_only" if special else "dual") for row in new_scores)
    window.close()


def dialog_entry_in_top_menu(window, action):
    return any(action in menu.menu().actions() for menu in window.menuBar().actions() if menu.menu())


@pytest.mark.parametrize("cancel_at", ["editor", "correction", "archive"])
def test_new_song_cancel_never_creates_formal_song(tmp_path, monkeypatch, cancel_at):
    app = QApplication.instance() or QApplication([])
    db, root, _, batch, ids, sources = fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    hashes = {path: sha256_file(path) for path in [*sources, *root.rglob("*.pdf")]}
    window = MainWindow(db)
    window.score_table.selectAll()
    errors = []

    def archive():
        assert app.activeModalWidget().windowTitle() == "确认归档"
        app.activeModalWidget().reject()

    def preview():
        dialog = app.activeModalWidget()
        if cancel_at == "archive":
            archive()
        else:
            dialog.reject()

    def editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QLineEdit, "correction_title").setText("独立新曲目")
        if cancel_at != "editor":
            schedule(app, preview, errors)
            dialog.findChild(QPushButton, "correction_archive").click()
        dialog.reject()

    schedule(app, editor, errors)
    window.menu_actions["force_new_song"].trigger()
    assert not errors
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    after = [dict(row) for row in db.list_import_items(batch)]
    assert after == before
    assert {path: sha256_file(path) for path in hashes} == hashes
    window.close()


def test_new_song_under_minimum_stays_draft_with_actual_reason_and_persistent_choice(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, _, batch, ids, _ = fixture(tmp_path, ("01",))
    window = MainWindow(db)
    window.score_table.selectRow(0)
    errors = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)

    def editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QLineEdit, "correction_title").setText("独立新曲目")
        dialog.findChild(QPushButton, "correction_archive").click()
        assert "未入库" in dialog.findChild(QLabel, "correction_state").text()
        assert "需要至少3种" in window.last_archive_failure and "目前只有1种" in window.last_archive_failure
        dialog.reject()

    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True)
    assert not errors and len(db.list_import_items(batch)) == 1
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    def reopen():
        dialog = app.activeModalWidget()
        assert dialog.findChild(QComboBox, "correction_destination").currentData() == "new_song"
        dialog.reject()
    schedule(app, reopen, errors)
    window.show_correction_dialog(ids)
    assert not errors
    window.close()
