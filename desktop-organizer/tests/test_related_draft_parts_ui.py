import json

import pytest
from PySide6.QtWidgets import QApplication, QCheckBox, QComboBox, QLabel, QLineEdit, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.ui.main_window import MainWindow
from test_correction_archive_menu_ui import click_menu, silence_messages
from test_force_archive import fixture
from test_menu_workflows import schedule
from test_related_draft_parts import add_related


def submit_preview(app):
    preview = app.activeModalWidget()
    if preview.windowTitle() == "确认归档":
        confirmation = preview.findChild(QCheckBox, "force_archive_confirm")
        if confirmation:
            confirmation.setChecked(True)
        next(button for button in preview.findChildren(QPushButton) if button.text() == "确认归档").click()
        return
    assert preview.windowTitle() == "确认校对修改"
    next(button for button in preview.findChildren(QPushButton) if button.text() == "确认保存校对").click()


@pytest.mark.parametrize("entry", ["menu", "new_song", "ordinary_new"])
@pytest.mark.parametrize("preloaded", [False, True])
def test_matching_parts_are_brought_into_same_editor_and_confirmed_together(tmp_path, monkeypatch, entry, preloaded):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, original_ids = fixture(tmp_path)
    sources += add_related(db, tmp_path, batch)
    rows = db.list_import_items(batch)
    related_ids = {row["id"] for row in rows if json.loads(row["parsed_fields"])["instrument_code"] in {"02", "03"}}
    collected_ids = original_ids | related_ids
    untouched = [dict(row) for row in rows if row["id"] not in collected_ids]
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    errors = []
    messages = silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_parts")
        table.selectRow(0)
        dialog.findChild(QLineEdit, "correction_title").setText("独立   曲目")
        instrument = dialog.findChild(QComboBox, f"correction_instrument_{min(original_ids)}")
        instrument.setCurrentIndex(instrument.findData("07"))
        def archive():
            preview = app.activeModalWidget()
            assert preview.windowTitle() == "确认归档"
            assert preview.findChild(QTableWidget).rowCount() == 3
            confirmation = preview.findChild(QCheckBox, "force_archive_confirm")
            if entry == "ordinary_new":
                assert confirmation is None
            else:
                assert confirmation is not None and not confirmation.isChecked()
                confirmation.setChecked(True)
            next(button for button in preview.findChildren(QPushButton) if button.text() == "确认归档").click()
        def correction():
            preview = app.activeModalWidget()
            assert preview.windowTitle() == "确认归档"
            assert preview.findChild(QTableWidget).rowCount() == 3
            assert preview.findChild(QTableWidget, "archive_correction_changes").rowCount() == 3
            assert dialog.isVisible() and table.rowCount() == 3
            assert dialog.findChild(QLabel, "correction_count").text() == "本次校对 3 份草稿分谱"
            assert "选中 3 / 3" in dialog.findChild(QLabel, "correction_scope").text()
            assert instrument.currentData() == "07"
            assert dialog.findChild(QLineEdit, "correction_title").text() == "独立   曲目"
            assert dialog.grab().save(str(tmp_path / "related-parts-editor.png"))
            assert preview.grab().save(str(tmp_path / "related-parts-preview.png"))
            archive()
        if entry == "menu":
            click_menu(app, dialog, "correction_force_new_song", correction, errors)
        else:
            schedule(app, correction, errors)
            dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(collected_ids if preloaded else original_ids, new_song=entry != "menu", force=entry == "new_song")
    assert not errors and not messages
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert db.find_archived_song("01", "独立   曲目")["seq"] == "001"
    assert [dict(row) for row in db.list_import_items(batch)] == untouched
    assert {path: sha256_file(path) for path in sources} == hashes
    for score in db.list_archived_scores():
        assert all(sha256_file(path) == score["source_sha256"] for path in ArchiveService(db, root).score_paths(score))
    window.close()


def test_cancel_collection_preview_preserves_edits_and_all_drafts(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path)
    sources += add_related(db, tmp_path, batch)
    before = [dict(row) for row in db.list_import_items(batch)]
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "correction_title")
        title.setText("独立  曲目")
        def returned():
            assert app.activeModalWidget() is dialog and dialog.isVisible()
            table = dialog.findChild(QTableWidget, "correction_parts")
            assert table.rowCount() == 3
            assert title.text() == "独立  曲目"
            table.selectRow(0)
            assert title.text() == "独立  曲目"
            assert [dict(row) for row in db.list_import_items(batch)] == before
            dialog.reject()
        def cancel():
            preview = app.activeModalWidget()
            assert preview.findChild(QTableWidget).rowCount() == 3
            preview.reject()
            schedule(app, returned, errors)
        schedule(app, cancel, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors and [dict(row) for row in db.list_import_items(batch)] == before
    assert {path: sha256_file(path) for path in sources} == hashes
    assert db.count_songs() == 0 and not list(root.rglob("*.pdf"))
    window.close()


def test_bad_related_source_is_displayed_but_never_saved_or_archived(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, _, batch, ids = fixture(tmp_path)
    related = add_related(db, tmp_path, batch)
    related[0].unlink()
    before = [dict(row) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    errors = []
    messages = silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QPushButton, "correction_archive").click()
        assert dialog.findChild(QTableWidget, "correction_parts").rowCount() == 3
        assert any(str(related[0]) in message for message in messages)
        dialog.reject()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors and [dict(row) for row in db.list_import_items(batch)] == before
    assert db.count_scores() == 0 and not list(root.rglob("*.pdf"))
    window.close()


def test_parts_arriving_after_preview_are_loaded_and_require_new_confirmation(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, sources, batch, ids = fixture(tmp_path)
    before = dict(db.get_import_item(min(ids)))
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        def returned():
            assert app.activeModalWidget() is dialog and dialog.isVisible()
            assert dialog.findChild(QTableWidget, "correction_parts").rowCount() == 2
            assert "请核对后重新" in dialog.findChild(QLabel, "correction_state").text()
            assert dict(db.get_import_item(min(ids))) == before
            assert db.count_songs() == 0 and not list(root.rglob("*.pdf"))
            dialog.reject()
        def changed():
            extra = tmp_path / "0201独立曲目.pdf"
            extra.write_bytes(b"%PDF arriving during preview")
            ImportService(db).import_paths([extra], batch_id=batch)
            submit_preview(app)
            schedule(app, returned, errors)
        schedule(app, changed, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors and dict(db.get_import_item(min(ids))) == before
    assert sha256_file(sources[0]) == before["source_sha256"]
    window.close()


def test_user_can_exclude_collected_parts_and_distinguish_a_different_work(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, _, batch, ids = fixture(tmp_path)
    add_related(db, tmp_path, batch)
    untouched = [dict(row) for row in db.list_import_items(batch) if row["id"] not in ids]
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        def archive():
            preview = app.activeModalWidget()
            assert preview.findChild(QTableWidget).rowCount() == 1
            preview.findChild(QCheckBox, "force_archive_confirm").setChecked(True)
            next(button for button in preview.findChildren(QPushButton) if button.text() == "确认归档").click()
        def second_preview():
            assert app.activeModalWidget().findChild(QTableWidget).rowCount() == 1
            archive()
        def returned():
            assert app.activeModalWidget() is dialog
            dialog.findChild(QTableWidget, "correction_parts").selectRow(0)
            dialog.findChild(QLineEdit, "correction_title").setText("确实不同的作品")
            schedule(app, second_preview, errors)
            dialog.findChild(QPushButton, "correction_archive").click()
        def cancel():
            first = app.activeModalWidget()
            assert first.findChild(QTableWidget).rowCount() == 3
            first.reject()
            schedule(app, returned, errors)
        schedule(app, cancel, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors and db.count_scores() == 1
    assert db.find_archived_song("01", "确实不同的作品")["minimum_waived"]
    assert [dict(row) for row in db.list_import_items(batch)] == untouched
    assert len(list(root.rglob("*.pdf"))) == 2
    window.close()


def test_collected_same_instrument_conflicts_remain_visible_and_block_archive(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, _, batch, ids = fixture(tmp_path)
    extra = tmp_path / "0101独立 曲目.pdf"
    extra.write_bytes(b"%PDF alternate same instrument")
    ImportService(db).import_paths([extra], batch_id=batch)
    window = MainWindow(db)
    errors = []
    silence_messages(monkeypatch)
    def editor():
        dialog = app.activeModalWidget()
        def returned():
            assert app.activeModalWidget() is dialog
            assert dialog.findChild(QTableWidget, "correction_parts").rowCount() == 2
            assert "同曲同乐器冲突" in dialog.findChild(QLabel, "correction_state").text()
            dialog.reject()
        def preview():
            assert app.activeModalWidget().findChild(QTableWidget).rowCount() == 2
            schedule(app, returned, errors)
            submit_preview(app)
        schedule(app, preview, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
    schedule(app, editor, errors)
    window.show_correction_dialog(ids, new_song=True, force=True)
    assert not errors
    assert db.count_scores() == 0 and db.allocate_next_sequence() == "001"
    assert len(db.list_import_items(batch)) == 2 and not list(root.rglob("*.pdf"))
    assert any(warning["code"] == "same_instrument_conflict" for warning in db.list_warnings(batch))
    window.close()
