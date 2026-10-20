import json

from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QComboBox, QLineEdit, QPushButton, QTableWidget, QMessageBox

from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from test_correction_service import correction_fixture, add_existing


def confirm_dialog(app, expected_title, button_text, screenshot=None):
    dialog = app.activeModalWidget()
    assert dialog.windowTitle() == expected_title
    if screenshot:
        dialog.grab().save(str(screenshot))
    next(b for b in dialog.findChildren(QPushButton) if b.text() == button_text).click()


def test_warning_correction_real_preview_then_archive_and_remove_drafts(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in sources}
    window = MainWindow(db)
    window.show()
    app.processEvents()
    window.grab().save(str(tmp_path / "correction-main.png"))
    information = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: information.append(args[-1]))
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    def archive_preview():
        confirm_dialog(app, "确认归档", "确认归档", tmp_path / "correction-archive.png")
    def correction_preview():
        preview = app.activeModalWidget()
        assert preview.windowTitle() == "确认归档"
        assert preview.findChild(QTableWidget, "archive_correction_changes").rowCount() == 3
        assert preview.grab().save(str(tmp_path / "correction-preview.png"))
        archive_preview()
    def fill():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "分谱校对与入库"
        assert dialog.findChild(QTableWidget, "correction_parts").rowCount() == 3
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城")
        table = dialog.findChild(QTableWidget, "correction_parts")
        table.selectAll()
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "打开选中PDF").click()
        assert set(opened) == set(sources)
        dialog.grab().save(str(tmp_path / "correction-editor.png"))
        QTimer.singleShot(50, correction_preview)
        dialog.findChild(QPushButton, "correction_archive").click()
    window.scan_completeness()
    assert window.warning_table.rowCount() == 2
    window.warning_table.selectAll()
    QTimer.singleShot(50, fill)
    window.findChild(QPushButton, "process_selected_warning").click()
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert window.current_batch_id is None and window.score_table.rowCount() == 0
    assert window.warning_table.rowCount() == 0
    assert len(list((root / "按曲目分类").rglob("*.pdf"))) == 3
    assert len(list((root / "按乐器分类").rglob("*.pdf"))) == 3
    assert {p: sha256_file(p) for p in sources} == hashes
    assert not information and "已入库 1 首、3 份" in window.guidance_label.text()
    window.close()
    app.processEvents()


def test_correction_selects_existing_target_and_keeps_other_song_untouched(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, _, _, _ = correction_fixture(tmp_path)
    target = add_existing(db, tmp_path, "007", "目标曲目")
    other = add_existing(db, tmp_path, "008", "另一曲目")
    before = {p: sha256_file(p) for p in root.rglob("*.pdf")}
    source = tmp_path / "0402008错误名称.pdf"
    source.write_bytes(b"%PDF supplement")
    batch = ImportService(db).import_paths([source])
    item_id = int(db.list_import_items(batch)[0]["id"])
    window = MainWindow(db)
    window.current_batch_id = batch
    window.refresh_import_items()
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def archive_preview():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 1
        assert table.item(0, 3).text() == "0401007目标曲目.pdf"
        confirm_dialog(app, "确认归档", "确认归档")
    def correction_preview():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archive_correction_changes")
        assert table.item(0, 4).text() == "008 → 007"
        archive_preview()
    def fill():
        dialog = app.activeModalWidget()
        destination = dialog.findChild(QComboBox, "correction_destination")
        destination.setCurrentIndex(destination.findData(target))
        assert dialog.findChild(QLineEdit, "correction_title").text() == "目标曲目"
        assert dialog.findChild(QLineEdit, "correction_title").isReadOnly()
        QTimer.singleShot(50, correction_preview)
        dialog.findChild(QPushButton, "correction_archive").click()
    window.score_table.selectRow(0)
    QTimer.singleShot(50, fill)
    window.findChild(QPushButton, "correct_selected_scores").click()
    assert len(db.list_archived_scores(target)) == 4
    assert len(db.list_archived_scores(other)) == 3
    assert db.get_archived_song(other)["seq"] == "008"
    assert db.list_import_items(batch) == []
    for path, digest in before.items():
        if "01008" in str(path) or "按乐器分类" in str(path):
            assert sha256_file(path) == digest
    assert source.read_bytes() == b"%PDF supplement"
    window.close()
    app.processEvents()


def test_cancel_correction_preview_has_no_edits_or_output(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    before = [dict(r) for r in db.list_import_items(batch)]
    window = MainWindow(db)
    def cancel_preview():
        app.activeModalWidget().reject()
    def fill():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城")
        QTimer.singleShot(50, cancel_preview)
        dialog.findChild(QPushButton, "correction_save").click()
        dialog.reject()
    QTimer.singleShot(50, fill)
    window.show_correction_dialog(ids)
    assert [dict(r) for r in db.list_import_items(batch)] == before
    assert not root.exists() and db.count_scores() == 0
    window.close()
    app.processEvents()


def test_cancel_combined_archive_preview_keeps_original_draft_not_formal_library(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    window = MainWindow(db)
    before = [dict(row) for row in db.list_import_items(batch)]
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    def cancel_archive():
        app.activeModalWidget().reject()
    def fill():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城")
        QTimer.singleShot(50, cancel_archive)
        dialog.findChild(QPushButton, "correction_archive").click()
        dialog.reject()
    QTimer.singleShot(50, fill)
    window.show_correction_dialog(ids)
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert not root.exists() and db.count_scores() == 0
    window.close()
    app.processEvents()


def test_incomplete_correction_shows_reason_and_stays_draft(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    window = MainWindow(db)
    messages = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    def fill():
        dialog = app.activeModalWidget()
        dialog.findChild(QLineEdit, "correction_title").setText("另一首作品")
        dialog.findChild(QPushButton, "correction_archive").click()
        assert "3种不同乐器" in dialog.findChild(type(window.guidance_label), "correction_state").text()
        assert not dialog.findChild(QPushButton, "correction_failure_details").isHidden()
        dialog.reject()
    QTimer.singleShot(50, fill)
    window.show_correction_dialog([ids[0]])
    assert not messages
    assert len(db.list_import_items(batch)) == 3 and db.count_scores() == 0
    assert not root.exists()
    assert db.clear_draft_batch(batch) == 3
    assert db.latest_draft_batch_id() is None
    window.close()
    app.processEvents()


def test_unselected_destination_text_cannot_silently_assign_a_song(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    messages = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    def fill():
        dialog = app.activeModalWidget()
        dialog.findChild(QComboBox, "correction_destination").setEditText("不存在的曲目")
        dialog.findChild(QPushButton, "correction_archive").click()
        assert messages and "选定目标曲目" in messages[-1]
        dialog.reject()
    QTimer.singleShot(50, fill)
    window.show_correction_dialog(ids)
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert not root.exists()
    window.close()
    app.processEvents()


def test_duplicate_entry_does_not_apply_unsaved_edits(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    calls = []
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    monkeypatch.setattr(window, "show_duplicate_files", lambda **kwargs: calls.append(kwargs))
    def fill():
        dialog = app.activeModalWidget()
        dialog.findChild(QLineEdit, "correction_title").setText("未保存的修改")
        dialog.findChild(QPushButton, "correction_duplicates").click()
    QTimer.singleShot(50, fill)
    window.show_correction_dialog(ids)
    assert calls == [{"item_ids": {ids[0]}}]
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert not root.exists()
    window.close()
    app.processEvents()
