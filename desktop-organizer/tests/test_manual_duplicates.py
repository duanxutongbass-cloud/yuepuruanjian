import json
import shutil

import pytest
from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.duplicate_service import ConfirmedDuplicateService, DuplicateService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from test_menu_workflows import schedule


def fixture(tmp_path):
    db = Database(tmp_path / "manual.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    sources = []
    for index, name in enumerate(("0101完全不同的名字.pdf", "0202另一种分类和乐器.pdf", "没有编号未命名.pdf")):
        source = tmp_path / name
        source.write_bytes(f"%PDF different content {index}".encode())
        sources.append(source)
    batch = ImportService(db).import_paths(sources)
    return db, root, batch, sources, db.list_import_items(batch)


@pytest.mark.parametrize("index", [0, 1, 2])
def test_manual_confirmation_does_not_require_name_category_instrument_or_hash_match(tmp_path, index):
    db, root, batch, sources, items = fixture(tmp_path)
    hashes = {source: sha256_file(source) for source in sources}
    service = ConfirmedDuplicateService(db, root)
    folder, remaining = service.confirm(batch, [items[index]["id"]], manual=True)
    assert remaining == 2
    assert [dict(row) for row in db.list_import_items(batch)] == [dict(row) for i, row in enumerate(items) if i != index]
    manifest = json.loads((folder / "重复清单.json").read_text(encoding="utf-8"))
    entry = manifest["items"][0]
    assert entry["item_id"] == items[index]["id"]
    assert "人工确认" in "\n".join(entry["details"])
    assert sha256_file(folder / entry["relative_path"]) == hashes[sources[index]]
    assert {source: sha256_file(source) for source in sources} == hashes
    assert db.count_songs() == db.count_scores() == 0
    assert db.allocate_next_sequence() == "001"


def test_manual_confirmation_can_transfer_all_selected_without_retained_group(tmp_path):
    db, root, batch, sources, items = fixture(tmp_path)
    _, remaining = ConfirmedDuplicateService(db, root).confirm(batch, [row["id"] for row in items], manual=True)
    assert remaining == 0 and db.latest_draft_batch_id() is None
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 3
    assert all(source.is_file() for source in sources)


@pytest.mark.parametrize("failure", ["source_changed", "database", "record_changed"])
def test_manual_confirmation_still_rejects_file_or_record_failure_and_rolls_back(tmp_path, monkeypatch, failure):
    db, root, batch, sources, items = fixture(tmp_path)
    service = ConfirmedDuplicateService(db, root)
    if failure == "source_changed":
        sources[0].write_bytes(b"%PDF external change")
    elif failure == "database":
        def fail(*args):
            raise ValueError("injected database failure")
        monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    else:
        original_copy = shutil.copy2
        def change(source, target, *args, **kwargs):
            result = original_copy(source, target, *args, **kwargs)
            db.update_import_item_manual_fields(items[0]["id"], instrument_code="03", category_code="03", title="changed during copy")
            return result
        monkeypatch.setattr(shutil, "copy2", change)
    with pytest.raises(ValueError):
        service.confirm(batch, [items[0]["id"]], manual=True)
    assert len(db.list_import_items(batch)) == 3
    assert not list(service.root.rglob("*.pdf"))
    assert all(source.is_file() for source in sources)


def test_manual_selection_must_belong_to_current_draft_batch(tmp_path):
    db, root, batch, sources, items = fixture(tmp_path)
    other_batch = ImportService(db).import_paths([sources[0]])
    with pytest.raises(ValueError, match="重新选择"):
        ConfirmedDuplicateService(db, root).confirm(other_batch, [items[0]["id"]], manual=True)
    assert len(db.list_import_items(batch)) == 3
    assert not (root / "已确认重复").exists()


@pytest.mark.parametrize("accept", [False, True])
def test_native_selected_manual_rows_can_confirm_despite_no_duplicate_warning(tmp_path, monkeypatch, accept):
    app = QApplication.instance() or QApplication([])
    db, root, batch, sources, items = fixture(tmp_path)
    window = MainWindow(db)
    errors, questions, warnings = [], [], []
    selected = {items[0]["id"], items[2]["id"]}
    assert DuplicateService(db).list_rows(batch) == []
    def question(*args):
        questions.append(args[2])
        return QMessageBox.Yes if accept else QMessageBox.No
    monkeypatch.setattr(QMessageBox, "question", question)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args))
    def operate():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "duplicate_parts")
        assert table.rowCount() == 2
        assert dialog.grab().save(str(tmp_path / "manual-confirmation.png"))
        dialog.findChild(QPushButton, "duplicate_select_visible").click()
        dialog.findChild(QPushButton, "duplicate_confirm_selected").click()
        dialog.reject()
    try:
        schedule(app, operate, errors)
        window.show_duplicate_files(item_ids=selected)
        assert not errors and not warnings and len(questions) == 1
        assert "不要求名称或内容指纹相同" in questions[0]
        remaining = db.list_import_items(batch)
        assert {row["id"] for row in remaining} == ({items[1]["id"]} if accept else {row["id"] for row in items})
        assert len(list((root / "已确认重复").rglob("*.pdf"))) == (2 if accept else 0)
        assert all(source.is_file() for source in sources)
    finally:
        window.close()
