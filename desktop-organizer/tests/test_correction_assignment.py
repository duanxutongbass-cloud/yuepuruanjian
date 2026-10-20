import json

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QLineEdit, QComboBox, QPushButton, QTableWidget, QDialogButtonBox, QMessageBox

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from test_correction_service import add_existing


def setup(tmp_path):
    db = Database(tmp_path / "assignment.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    sources = []
    for name in ("0201千与千寻《再度》.pdf", "0501千与千寻《与你+再度》.pdf", "0101天空之城.pdf"):
        path = tmp_path / name
        path.write_bytes(b"%PDF " + name.encode())
        sources.append(path)
    batch = ImportService(db).import_paths(sources)
    ids = [int(row["id"]) for row in db.list_import_items(batch)]
    return db, root, batch, ids, sources


def fields(db, item_id):
    return json.loads(db.get_import_item(item_id)["parsed_fields"])


def test_individual_correction_never_changes_other_similar_song(tmp_path):
    db, root, batch, ids, sources = setup(tmp_path)
    other = dict(db.get_import_item(ids[1]))
    hashes = {path: sha256_file(path) for path in sources}
    service = CorrectionService(db)
    plan = service.build_plan(batch, [ids[0]], "千与千寻《再度》新版名称")
    service.apply(plan)
    assert dict(db.get_import_item(ids[1])) == other
    assert fields(db, ids[0])["title"] != fields(db, ids[1])["title"]
    assert {path: sha256_file(path) for path in sources} == hashes
    assert db.count_songs() == 0 and not root.exists()


def test_candidates_include_drafts_and_library_without_auto_assigning(tmp_path):
    db, root, batch, ids, sources = setup(tmp_path)
    archived = add_existing(db, tmp_path, "007", "千与千寻《再度》")
    before = [dict(row) for row in db.list_import_items(batch)]
    service = CorrectionService(db)
    candidates = service.matching_candidates(batch, [ids[0]])
    assert any(row["kind"] == "archived" and row["id"] == archived and row["seq"] == "007" and row["suggested"] for row in candidates)
    assert any(row["kind"] == "draft" and row["id"] == ids[1] and row["suggested"] for row in candidates)
    assert any(row["id"] == ids[2] and not row["suggested"] for row in candidates if row["kind"] == "draft")
    assert not any(row["id"] == ids[0] for row in candidates if row["kind"] == "draft")
    assert [dict(row) for row in db.list_import_items(batch)] == before


def test_explicit_draft_target_keeps_other_rows_and_rechecks_candidate(tmp_path):
    db, root, batch, ids, sources = setup(tmp_path)
    target_before = dict(db.get_import_item(ids[2]))
    unrelated_before = dict(db.get_import_item(ids[1]))
    service = CorrectionService(db)
    plan = service.build_plan(batch, [ids[0]], target_draft_item_id=ids[2])
    assert len(plan.rows) == 1 and plan.target_draft_item_id == ids[2]
    assert [fields(db, item_id)["title"] for item_id in ids] == ["千与千寻《再度》", "千与千寻《与你+再度》", "天空之城"]
    service.apply(plan)
    assert fields(db, ids[0])["title"] == "天空之城"
    assert dict(db.get_import_item(ids[2])) == target_before
    assert dict(db.get_import_item(ids[1])) == unrelated_before
    assert all(path.exists() for path in sources)


@pytest.mark.parametrize("change", ["fields", "source", "remove", "decision"])
def test_draft_candidate_changes_after_preview_block_entire_save(tmp_path, change):
    db, _, batch, ids, sources = setup(tmp_path)
    service = CorrectionService(db)
    plan = service.build_plan(batch, [ids[0]], target_draft_item_id=ids[2])
    before = dict(db.get_import_item(ids[0]))
    if change == "fields":
        service.apply(service.build_plan(batch, [ids[2]], "新的目标"))
    elif change == "source":
        sources[2].write_bytes(b"%PDF changed")
    elif change == "remove":
        db.remove_import_items_from_draft(batch, [ids[2]])
    else:
        with db.session() as connection:
            connection.execute("UPDATE import_item SET decision='changed' WHERE id=?", (ids[2],))
    with pytest.raises(ValueError):
        service.apply(plan)
    assert dict(db.get_import_item(ids[0])) == before


def test_draft_target_preserves_requested_number_and_formal_identity(tmp_path):
    db, root, batch, ids, _ = setup(tmp_path)
    archived = add_existing(db, tmp_path, "007", "天空之城")
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [ids[2]], target_song_id=archived))
    plan = service.build_plan(batch, [ids[0]], target_draft_item_id=ids[2])
    service.apply(plan)
    assert fields(db, ids[0])["seq"] == "007"
    assert fields(db, ids[0])["target_song_id"] == archived
    assert db.get_archived_song(archived)["seq"] == "007"


def test_ambiguous_or_selected_draft_target_blocked(tmp_path):
    db, _, batch, ids, _ = setup(tmp_path)
    service = CorrectionService(db)
    with pytest.raises(ValueError):
        service.build_plan(batch, ids[:2], target_draft_item_id=ids[1])
    with pytest.raises(ValueError):
        service.build_plan(batch, ids[:1], target_song_id=999, target_draft_item_id=ids[2])
    with pytest.raises(ValueError):
        service.build_plan(batch, ids[:1], target_draft_item_id=999)


def native_callback(app, callback, errors):
    def wrapped():
        try:
            callback()
        except Exception as exc:
            errors.append(exc)
            modal = app.activeModalWidget()
            if modal:
                modal.reject()
    return wrapped


def test_native_editor_saves_two_distinct_songs_individually_in_same_window(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = setup(tmp_path)
    window = MainWindow(db)
    window.show()
    app.processEvents()
    previews = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: previews.append(plan) or True)
    errors = []
    def operate():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_parts")
        title = dialog.findChild(QLineEdit, "correction_title")
        assert {cell.row() for cell in table.selectedItems()} == {0}
        assert title.text() == "千与千寻《再度》"
        title.setText("再度")
        dialog.findChild(QPushButton, "correction_save").click()
        assert len(previews) == 1 and len(previews[0].rows) == 1
        assert fields(db, ids[1])["title"] == "千与千寻《与你+再度》"
        assert app.activeModalWidget() is dialog
        table.selectRow(1)
        assert title.text() == "千与千寻《与你+再度》"
        title.setText("与你+再度")
        dialog.findChild(QPushButton, "correction_save").click()
        dialog.grab().save(str(tmp_path / "individual-editor.png"))
        assert [fields(db, item_id)["title"] for item_id in ids] == ["再度", "与你+再度", "天空之城"]
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids[:2])
    assert not errors
    assert db.count_songs() == 0 and all(path.exists() for path in sources)
    window.close()
    app.processEvents()


def test_native_candidate_draft_compare_cancel_choose_and_preview(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = setup(tmp_path)
    window = MainWindow(db)
    window.show()
    app.processEvents()
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    plans = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: plans.append(plan) or True)
    errors = []
    before = [dict(row) for row in db.list_import_items(batch)]
    def candidate():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "查找疑似对应曲目"
        table = dialog.findChild(QTableWidget, "correction_candidates")
        assert any(not table.isRowHidden(row) and table.item(row, 1).text() == "千与千寻《与你+再度》" for row in range(table.rowCount()))
        dialog.findChild(QLineEdit, "candidate_search").setText("天空之城")
        row = next(row for row in range(table.rowCount()) if table.item(row, 1).text() == "天空之城")
        table.setCurrentCell(row, 0)
        table.selectRow(row)
        assert not table.isRowHidden(row)
        dialog.findChild(QPushButton, "candidate_open_pdf").click()
        assert opened == [sources[2]]
        dialog.grab().save(str(tmp_path / "candidate-picker.png"))
        dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.Ok).click()
    def operate():
        dialog = app.activeModalWidget()
        button = dialog.findChild(QPushButton, "correction_find_candidate")
        QTimer.singleShot(50, lambda: app.activeModalWidget().reject())
        button.click()
        assert [dict(row) for row in db.list_import_items(batch)] == before
        QTimer.singleShot(50, native_callback(app, candidate, errors))
        button.click()
        assert dialog.findChild(QLineEdit, "correction_title").text() == "天空之城"
        assert [dict(row) for row in db.list_import_items(batch)] == before
        dialog.findChild(QPushButton, "correction_save").click()
        assert len(plans) == 1 and plans[0].target_draft_item_id == ids[2]
        assert fields(db, ids[0])["title"] == "天空之城"
        assert fields(db, ids[1])["title"] == "千与千寻《与你+再度》"
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids[:2])
    assert not errors
    window.close()
    app.processEvents()


def test_native_switch_keeps_unsaved_individual_edits_without_applying_them(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = setup(tmp_path)
    window = MainWindow(db)
    before = [dict(row) for row in db.list_import_items(batch)]
    errors = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    def operate():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_parts")
        title = dialog.findChild(QLineEdit, "correction_title")
        title.setText("第一份的新曲名")
        table.selectRow(1)
        title.setText("第二份的新曲名")
        table.selectRow(0)
        assert title.text() == "第一份的新曲名"
        assert [dict(row) for row in db.list_import_items(batch)] == before
        dialog.findChild(QPushButton, "correction_save").click()
        table.selectRow(1)
        assert title.text() == "第二份的新曲名"
        assert fields(db, ids[1])["title"] == "千与千寻《与你+再度》"
        assert fields(db, ids[0])["title"] == "第一份的新曲名"
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids[:2])
    assert not errors
    window.close()
    app.processEvents()


def test_native_candidate_formal_compare_then_scope_archive_keeps_other_song(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = setup(tmp_path)
    target = add_existing(db, tmp_path, "007", "千与千寻《再度》")
    db.update_import_item_manual_fields(ids[0], instrument_code="04")
    old_other = dict(db.get_import_item(ids[1]))
    original_hashes = {(p.relative_to(root).parts[0], p.name): sha256_file(p) for p in root.rglob("*.pdf")}
    window = MainWindow(db)
    window.show()
    errors = []
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def choose():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_candidates")
        row = next(row for row in range(table.rowCount()) if table.item(row, 0).text() == "已入库" and table.item(row, 3).text() == "007")
        table.selectRow(row)
        def parts():
            parts_dialog = app.activeModalWidget()
            assert "已入库分谱" in parts_dialog.windowTitle()
            parts_table = parts_dialog.findChild(QTableWidget)
            parts_table.setCurrentCell(0, 0)
            next(b for b in parts_dialog.findChildren(QPushButton) if b.text() == "打开选中PDF").click()
            assert opened[-1].is_relative_to(root / "按乐器分类")
            parts_dialog.reject()
        QTimer.singleShot(50, native_callback(app, parts, errors))
        dialog.findChild(QPushButton, "candidate_open_pdf").click()
        dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.Ok).click()
    def archive_preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        assert dialog.findChild(QTableWidget).rowCount() == 1
        dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.Ok).click()
    def correction_preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        assert dialog.findChild(QTableWidget, "archive_correction_changes").rowCount() == 1
        dialog.grab().save(str(tmp_path / "individual-formal-preview.png"))
        archive_preview()
    def operate():
        dialog = app.activeModalWidget()
        QTimer.singleShot(50, native_callback(app, choose, errors))
        dialog.findChild(QPushButton, "correction_find_candidate").click()
        assert dialog.findChild(QComboBox, "correction_destination").currentData() == target
        QTimer.singleShot(50, native_callback(app, correction_preview, errors))
        dialog.findChild(QPushButton, "correction_archive").click()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids[:2])
    assert not errors
    assert db.count_songs() == 1 and db.count_scores() == 4
    assert db.get_import_item(ids[0]) is None
    assert dict(db.get_import_item(ids[1])) == old_other
    current_hashes = {(p.relative_to(root).parts[0], p.name): sha256_file(p) for p in root.rglob("*.pdf")}
    assert {key: current_hashes[key] for key in original_hashes} == original_hashes
    assert (root / "按曲目分类" / "01007千与千寻《再度》（04）").is_dir()
    assert not (root / "按曲目分类" / "01007千与千寻《再度》（03）").exists()
    assert len(list((root / "按曲目分类").rglob("*.pdf"))) == 4
    assert len(list((root / "按乐器分类").rglob("*.pdf"))) == 4
    assert all(p.exists() for p in sources)
    window.close()
    app.processEvents()


def test_native_hidden_candidate_cannot_be_confirmed_after_search(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, batch, ids, _ = setup(tmp_path)
    window = MainWindow(db)
    candidates = CorrectionService(db).matching_candidates(batch, [ids[0]])
    messages = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: messages.append(args[-1]))
    errors = []
    def operate():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_candidates")
        row = next(row for row in range(table.rowCount()) if not table.isRowHidden(row))
        table.selectRow(row)
        dialog.findChild(QLineEdit, "candidate_search").setText("不存在的乐曲")
        dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.Ok).click()
        assert messages and app.activeModalWidget() is dialog
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    assert window.choose_correction_candidate(candidates, window) is None
    assert not errors
    window.close()
    app.processEvents()


def test_native_changing_instrument_selects_its_row_without_reducing_existing_bulk_selection(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = setup(tmp_path)
    window = MainWindow(db)
    previews = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: previews.append(plan) or True)
    errors = []
    first = dict(db.get_import_item(ids[0]))
    def operate():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "correction_parts")
        other_instrument = dialog.findChild(QComboBox, f"correction_instrument_{ids[1]}")
        other_instrument.setCurrentIndex(other_instrument.findData("04"))
        assert {cell.row() for cell in table.selectedItems()} == {1}
        assert dialog.findChild(QLineEdit, "correction_title").text() == "千与千寻《与你+再度》"
        dialog.findChild(QPushButton, "correction_save").click()
        assert fields(db, ids[1])["instrument_code"] == "04"
        assert dict(db.get_import_item(ids[0])) == first
        assert [row.item_id for row in previews[0].rows] == [ids[1]]
        table.selectAll()
        other_instrument.setCurrentIndex(other_instrument.findData("05"))
        assert {cell.row() for cell in table.selectedItems()} == {0, 1}
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids[:2])
    assert not errors
    window.close()
    app.processEvents()
