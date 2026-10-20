import pytest
from PySide6.QtCore import QItemSelectionModel, QTimer
from PySide6.QtWidgets import QApplication, QLabel, QMessageBox, QTableWidget

from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.review_service import ReviewService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def fixture(tmp_path):
    db = Database(tmp_path / "scope.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    paths = []
    for name in ("0101同曲.pdf", "0201同曲.pdf", "0102其他曲.pdf", "无编号.pdf"):
        path = tmp_path / name
        path.write_bytes(b"%PDF " + name.encode())
        paths.append(path)
    batch = ImportService(db).import_paths(paths)
    return db, root, batch, paths, db.list_import_items(batch)


def test_selected_one_does_not_expand_same_song_or_other_warnings(tmp_path):
    db, root, batch, paths, items = fixture(tmp_path)
    selected = {items[0]["id"]}
    before = [dict(row) for row in items[1:]]
    hashes = [sha256_file(path) for path in paths]
    service = ReviewService(db, root)
    plan = service.build_plan(batch, selected)
    assert [item["item_id"] for item in plan] == list(selected)
    destination, remaining = service.commit_plan(batch, plan, selected)
    assert remaining == 3
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert len(list(destination.rglob("*.pdf"))) == 1
    assert sha256_file(destination / plan[0]["relative_path"]) == hashes[0]
    assert [sha256_file(path) for path in paths] == hashes
    assert db.count_songs() == db.count_scores() == 0
    assert db.allocate_next_sequence() == "001"
    assert not (root / "按曲目分类").exists()
    entries = service.list_pending()
    assert len(entries) == 1
    service.restore_to_draft(entries, batch)
    assert len(db.list_import_items(batch)) == 4


@pytest.mark.parametrize("scope", [set(), {999999}])
def test_empty_or_stale_selection_rejected(tmp_path, scope):
    db, root, batch, _, _ = fixture(tmp_path)
    with pytest.raises(ValueError, match="重新选择"):
        ReviewService(db, root).build_plan(batch, scope)
    assert len(db.list_import_items(batch)) == 4
    assert not (root / "待审查").exists()


def test_unselected_missing_source_does_not_block_selected_pdf(tmp_path):
    db, root, batch, paths, items = fixture(tmp_path)
    paths[-1].unlink()
    service = ReviewService(db, root)
    selected = {items[0]["id"]}
    plan = service.build_plan(batch, selected)
    service.commit_plan(batch, plan, selected)
    assert len(db.list_import_items(batch)) == 3


def test_scoped_plan_cannot_be_committed_as_entire_batch(tmp_path):
    db, root, batch, _, items = fixture(tmp_path)
    service = ReviewService(db, root)
    selected = {items[0]["id"]}
    plan = service.build_plan(batch, selected)
    with pytest.raises(ValueError, match="变化"):
        service.commit_plan(batch, plan, {row["id"] for row in items})
    assert len(db.list_import_items(batch)) == 4


def test_ready_selected_pdf_can_be_explicitly_sent_for_review(tmp_path):
    db, root, batch, _, items = fixture(tmp_path)
    source = tmp_path / "0801总谱.pdf"
    source.write_bytes(b"%PDF standalone")
    ImportService(db).import_paths([source], batch)
    selected = {db.list_import_items(batch)[-1]["id"]}
    plan = ReviewService(db, root).build_plan(batch, selected)
    assert plan[0]["reasons"] == ["人工指定待审查"]
    ReviewService(db, root).commit_plan(batch, plan, selected)
    assert len(db.list_import_items(batch)) == len(items)


def test_selected_transfer_failure_preserves_entire_draft(tmp_path, monkeypatch):
    db, root, batch, paths, items = fixture(tmp_path)
    service = ReviewService(db, root)
    selected = {items[0]["id"]}
    plan = service.build_plan(batch, selected)
    def fail(*args):
        raise ValueError("injected database failure")
    monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    with pytest.raises(ValueError, match="injected"):
        service.commit_plan(batch, plan, selected)
    assert [dict(row) for row in db.list_import_items(batch)] == [dict(row) for row in items]
    assert not list(service.root.rglob("*.pdf"))
    assert all(path.is_file() for path in paths)


@pytest.mark.parametrize("rows", [[], [0], [0, 2]])
def test_native_menu_only_transfers_selected_rows(tmp_path, monkeypatch, rows):
    app = QApplication.instance() or QApplication([])
    db, root, batch, paths, items = fixture(tmp_path)
    window = MainWindow(db)
    window.show()
    app.processEvents()
    warnings, previews = [], []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args))
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def confirm(plan):
        previews.append({item["item_id"] for item in plan})
        return True
    monkeypatch.setattr(window, "show_review_confirmation", confirm)
    try:
        for row in rows:
            window.score_table.selectionModel().select(window.score_table.model().index(row, 0), QItemSelectionModel.Select | QItemSelectionModel.Rows)
        action = window.menu_actions['archive_review']
        assert action.text() == '选中分谱转入待审查'
        action.trigger()
        selected = {items[row]["id"] for row in rows}
        assert previews == ([selected] if rows else [])
        assert bool(warnings) == (not rows)
        assert {row["id"] for row in db.list_import_items(batch)} == {row["id"] for row in items} - selected
        assert len(ReviewService(db, root).list_pending()) == len(rows)
        assert window.score_table.rowCount() == 4 - len(rows)
        assert all(path.is_file() for path in paths)
    finally:
        window.close()


def test_native_confirmation_lists_one_and_cancel_preserves_all(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, root, batch, _, items = fixture(tmp_path)
    window = MainWindow(db)
    plan = ReviewService(db, root).build_plan(batch, {items[0]["id"]})
    observed, errors = [], []
    def inspect():
        dialog = app.activeModalWidget()
        try:
            table = dialog.findChild(QTableWidget)
            assert table.rowCount() == 1
            assert any("未选中分谱保留草稿" in label.text() for label in dialog.findChildren(QLabel))
            assert dialog.grab().save(str(tmp_path / "selected-review.png"))
            observed.append(1)
        except Exception as exc:
            errors.append(exc)
        finally:
            dialog.reject()
    QTimer.singleShot(100, inspect)
    try:
        assert not window.show_review_confirmation(plan)
        assert observed and not errors
        assert len(db.list_import_items(batch)) == 4
        assert not (root / "待审查").exists()
    finally:
        window.close()
