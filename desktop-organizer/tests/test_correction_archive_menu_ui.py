import json

import pytest
from PySide6.QtCore import Qt
from PySide6.QtTest import QTest
from PySide6.QtWidgets import QApplication, QCheckBox, QComboBox, QLineEdit, QMenu, QMessageBox, QPushButton, QTableWidget, QToolButton

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from test_force_archive import fixture
from test_force_archive_ui import check_force_dialog
from test_menu_workflows import schedule


def click_menu(app, editor, name, followup, errors, screenshot=None):
    options = editor.findChild(QToolButton, "correction_archive_options")
    assert options is not None and options.toolTip() == "入库方式"
    editor.activateWindow()
    assert QTest.qWaitForWindowActive(editor)
    def popup():
        menu = app.activePopupWidget()
        assert isinstance(menu, QMenu) and menu is options.menu()
        assert [action.objectName() for action in menu.actions()] == [
            "correction_archive_normal", "correction_force_new_song", "correction_force_existing_song"]
        if screenshot:
            assert editor.grab().save(str(screenshot / "correction-archive-button.png"))
            assert menu.grab().save(str(screenshot / "correction-archive-menu.png"))
        action = next(action for action in menu.actions() if action.objectName() == name)
        menu.close()
        schedule(app, followup, errors)
        action.trigger()
    schedule(app, popup, errors)
    QTest.mouseClick(options, Qt.LeftButton)


def confirm_correction(app, followup, errors):
    preview = app.activeModalWidget()
    if preview.windowTitle() == "确认归档":
        assert preview.findChild(QTableWidget, "archive_correction_changes") is not None
        followup()
        return
    assert preview.windowTitle() == "确认校对修改"
    schedule(app, followup, errors)
    next(button for button in preview.findChildren(QPushButton) if button.text() == "确认保存校对").click()


def silence_messages(monkeypatch):
    messages = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    return messages


def test_force_new_from_failed_normal_editor_preserves_unsaved_fields_and_selection(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path, ("01", "02", "03"))
    untouched = [dict(row) for row in db.list_import_items(batch)][1:]
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    errors = []
    messages = silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "correction_title")
        category = dialog.findChild(QComboBox, "correction_category")
        title.setText("重新校对的名称")
        category.setCurrentIndex(category.findData("02"))
        instrument = dialog.findChild(QComboBox, f"correction_instrument_{min(ids)}")
        instrument.setCurrentIndex(instrument.findData("02"))
        def failed():
            assert app.activeModalWidget() is dialog and dialog.isVisible()
            assert db.count_songs() == 0
            assert "未入库" in dialog.findChild(QPushButton, "correction_failure_details").text()
            title.setText("最终手动命名")
            category.setCurrentIndex(category.findData("03"))
            click_menu(app, dialog, "correction_force_new_song",
                       lambda: confirm_correction(app, lambda: check_force_dialog(app, tmp_path, errors), errors),
                       errors, tmp_path)
        schedule(app, lambda: confirm_correction(app, failed, errors), errors)
        dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids)
    assert not errors and not messages
    song = db.find_archived_song("03", "最终手动命名")
    assert song["seq"] == "001" and song["minimum_waived"]
    score = db.list_archived_scores()[0]
    assert score["instrument_code"] == "02"
    assert all(sha256_file(path) == hashes[sources[0]] for path in ArchiveService(db, root).score_paths(score))
    assert [dict(row) for row in db.list_import_items(batch)] == untouched
    assert {path: sha256_file(path) for path in sources} == hashes
    window.close()


@pytest.mark.parametrize("preselected", [False, True])
def test_force_existing_inside_editor_uses_current_instrument_and_canonical_target(tmp_path, monkeypatch, preselected):
    app = QApplication.instance() or QApplication([])
    db, root, originals, batch, _ = fixture(tmp_path, ("08",))
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch))
    old = dict(db.list_archived_scores()[0])
    song_id = old["song_id"]
    incoming = tmp_path / "0102手动校对前.pdf"
    incoming.write_bytes(b"%PDF violin for existing song")
    next_batch = ImportService(db).import_paths([incoming])
    ids = {row["id"] for row in db.list_import_items(next_batch)}
    window = MainWindow(db)
    errors = []
    messages = silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "correction_title")
        title.setText("尚未保存的手改名称")
        instrument = dialog.findChild(QComboBox, f"correction_instrument_{min(ids)}")
        instrument.setCurrentIndex(instrument.findData("02"))
        if preselected:
            destination = dialog.findChild(QComboBox, "correction_destination")
            destination.setCurrentIndex(destination.findData(song_id))
        def correction():
            confirm_correction(app, lambda: check_force_dialog(app, tmp_path, errors), errors)
        def picker():
            candidate = app.activeModalWidget()
            assert candidate.windowTitle() == "统一为库内名称"
            candidate.findChild(QCheckBox).setChecked(False)
            candidate.findChild(QTableWidget, "correction_candidates").selectRow(0)
            schedule(app, correction, errors)
            next(button for button in candidate.findChildren(QPushButton) if button.text() == "采用库内名称").click()
        click_menu(app, dialog, "correction_force_existing_song", correction if preselected else picker, errors)
    schedule(app, editor, errors)
    window.show_correction_dialog(ids)
    assert not errors and not messages
    assert db.count_songs() == 1 and db.count_scores() == 2
    assert db.get_archived_song(song_id)["seq"] == "001" and db.get_archived_song(song_id)["minimum_waived"]
    assert dict(next(row for row in db.list_archived_scores() if row["instrument_code"] == "08")) == old
    assert sha256_file(originals[0]) == old["source_sha256"]
    incoming_score = next(row for row in db.list_archived_scores() if row["instrument_code"] == "02")
    assert all(sha256_file(path) == sha256_file(incoming) for path in archive.score_paths(incoming_score))
    window.close()


@pytest.mark.parametrize("cancel", ["candidate", "correction", "archive", "unconfirmed"])
def test_cancel_inline_force_keeps_same_editor_and_does_not_archive(tmp_path, monkeypatch, cancel):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path)
    if cancel == "candidate":
        total = tmp_path / "0802库内参考.pdf"
        total.write_bytes(b"%PDF total reference")
        total_batch = ImportService(db).import_paths([total])
        archive = ArchiveService(db, root)
        archive.commit_plan(total_batch, archive.build_plan(total_batch, {max(row["id"] for row in db.list_import_items(total_batch))}))
    before = dict(db.get_import_item(min(ids)))
    baseline = db.count_scores()
    source_hash = sha256_file(sources[0])
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "correction_title")
        title.setText("保留未保存内容")
        def still_open():
            assert app.activeModalWidget() is dialog and dialog.isVisible()
            assert title.text() == "保留未保存内容"
            assert dialog.findChild(QToolButton, "correction_archive_options").isEnabled()
            dialog.reject()
        def cancel_stage():
            modal = app.activeModalWidget()
            if cancel in {"archive", "unconfirmed"}:
                check_force_dialog(app, tmp_path, errors, "unconfirmed" if cancel == "unconfirmed" else "reject")
            else:
                modal.reject()
            schedule(app, still_open, errors)
        def correction():
            confirm_correction(app, cancel_stage, errors)
        action = "correction_force_existing_song" if cancel == "candidate" else "correction_force_new_song"
        click_menu(app, dialog, action, correction if cancel in {"archive", "unconfirmed"} else cancel_stage, errors)
    schedule(app, editor, errors)
    window.show_correction_dialog(ids)
    assert not errors
    assert db.count_scores() == baseline and db.allocate_next_sequence() == ("002" if baseline else "001")
    current = dict(db.get_import_item(min(ids)))
    assert current == before
    assert sha256_file(sources[0]) == source_hash
    window.close()


def test_normal_menu_in_force_editor_does_not_waive_minimum(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, _, batch, ids = fixture(tmp_path)
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        def failed():
            assert app.activeModalWidget() is dialog
            assert "未入库" in dialog.findChild(QPushButton, "correction_failure_details").text()
            assert db.count_songs() == 0
            dialog.reject()
        click_menu(app, dialog, "correction_archive_normal", lambda: confirm_correction(app, failed, errors), errors)
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors and db.count_scores() == 0 and db.allocate_next_sequence() == "001"
    assert not list(root.rglob("*.pdf"))
    window.close()


def test_force_existing_without_library_keeps_unsaved_editor_fields(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, _, batch, ids = fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    errors = []
    notices = []
    silence_messages(monkeypatch)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: notices.append(args[-1]))
    def editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "correction_title")
        title.setText("仍保留输入")
        def returned():
            assert app.activeModalWidget() is dialog and title.text() == "仍保留输入"
            assert dialog.findChild(QComboBox, "correction_destination").currentData() is None
            dialog.reject()
        click_menu(app, dialog, "correction_force_existing_song", returned, errors)
    schedule(app, editor, errors)
    window.show_correction_dialog(ids)
    assert not errors and notices == ["当前乐谱库还没有可选的正式曲目"]
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert db.count_songs() == 0 and db.allocate_next_sequence() == "001"
    window.close()


def test_inline_force_existing_still_requires_duplicate_choice(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, originals, batch, ids = fixture(tmp_path)
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch, ids, force=True))
    old_song = db.find_archived_song("01", "独立曲目")
    copies = {path: sha256_file(path) for path in root.rglob("*.pdf")}
    incoming = tmp_path / "0102补录的谱子.pdf"
    incoming.write_bytes(originals[0].read_bytes())
    new_batch = ImportService(db).import_paths([incoming])
    new_ids = {row["id"] for row in db.list_import_items(new_batch)}
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        destination = dialog.findChild(QComboBox, "correction_destination")
        destination.setCurrentIndex(destination.findData(old_song["id"]))
        def returned():
            assert app.activeModalWidget() is dialog and dialog.isVisible()
            dialog.reject()
        def conflict():
            duplicate = app.activeModalWidget()
            assert duplicate.windowTitle() == "已有入库乐谱：确认是否转入重复库"
            assert duplicate.findChild(QTableWidget, "archived_duplicate_parts").rowCount() == 2
            duplicate.reject()
            schedule(app, returned, errors)
        click_menu(app, dialog, "correction_force_existing_song", lambda: confirm_correction(app, conflict, errors), errors)
    schedule(app, editor, errors)
    window.show_correction_dialog(new_ids)
    assert not errors
    assert db.count_songs() == 1 and db.count_scores() == 1 and len(db.list_import_items(new_batch)) == 1
    assert {path: sha256_file(path) for path in copies} == copies
    assert not (root / "已确认重复").exists()
    assert sha256_file(incoming) == sha256_file(originals[0])
    window.close()


@pytest.mark.parametrize("width", [900, 1100, 1600])
def test_archive_split_controls_fit_and_menu_opens_at_different_widths(tmp_path, monkeypatch, width):
    app = QApplication.instance() or QApplication([])
    db, _, _, _, ids = fixture(tmp_path)
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        dialog.resize(width, 700)
        app.processEvents()
        buttons = [dialog.findChild(QPushButton, name) for name in ("correction_save", "correction_duplicates", "correction_archive")]
        options = dialog.findChild(QToolButton, "correction_archive_options")
        for widget in buttons + [options]:
            assert widget.isVisible()
            assert dialog.rect().contains(widget.mapTo(dialog, widget.rect().topLeft()))
            assert dialog.rect().contains(widget.mapTo(dialog, widget.rect().bottomRight()))
        for button in buttons:
            assert button.width() >= button.fontMetrics().horizontalAdvance(button.text()) + 12
        assert buttons[-1].mapTo(dialog, buttons[-1].rect().topRight()).x() < options.mapTo(dialog, options.rect().topLeft()).x()
        click_menu(app, dialog, "correction_force_existing_song", dialog.reject, errors, tmp_path)
    schedule(app, editor, errors)
    window.show_correction_dialog(ids)
    assert not errors
    window.close()
