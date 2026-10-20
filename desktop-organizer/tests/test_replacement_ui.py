import json

import pytest
from PySide6.QtCore import QItemSelectionModel, Qt
from PySide6.QtWidgets import QApplication, QCheckBox, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.replacement_service import ReplacementService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from test_menu_workflows import schedule


def fixture(tmp_path, special=False, unrelated=False):
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "ui.sqlite3")
    db.initialize()
    root = tmp_path / "输出"
    db.set_setting("output_root", str(root))
    originals = []
    for code in (["08"] if special else ["01", "02", "03"]):
        path = tmp_path / f"{code}01007天空之城.pdf"
        path.write_bytes(f"%PDF original {code}".encode())
        originals.append(path)
    archive = ArchiveService(db, root)
    batch = ImportService(db).import_paths(originals)
    archive.commit_plan(batch, archive.build_plan(batch))
    directory = tmp_path / "新导入"
    directory.mkdir()
    new = directory / f"{'08' if special else '01'}01天空之城.pdf"
    new.write_bytes(b"%PDF better new copy")
    sources = [*originals, new]
    if unrelated:
        other = directory / "0602另一作品.pdf"
        other.write_bytes(b"%PDF unrelated")
        sources.append(other)
        ImportService(db).import_paths([other])
    return app, db, root, sources, new


@pytest.mark.parametrize("special,unrelated,archive,checked", [
    (False, False, False, False), (True, False, False, True),
    (False, True, True, True), (True, True, True, False),
])
def test_keep_new_replaces_old_through_actual_comparison_and_preview(
    tmp_path, monkeypatch, special, unrelated, archive, checked
):
    app, db, root, sources, new = fixture(tmp_path, special, unrelated)
    old = dict(db.list_archived_scores()[0])
    original_hashes = {path: sha256_file(path) for path in sources}
    old_targets = ArchiveService(db, root).score_paths(old)
    if archive:
        ImportService(db).import_paths([new], batch_id=db.latest_draft_batch_id())
    window = MainWindow(db)
    window.show()
    opened, errors, messages = [], [], []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))

    def preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认保留新谱并替换库内分谱"
        table = dialog.findChild(QTableWidget, "replacement_preview")
        assert table.rowCount() == 1 and table.columnCount() == 7
        assert table.item(0, 0).text() == "007 / 分类01 / 天空之城"
        assert table.item(0, 2).text() == new.name
        assert "重复1" in table.item(0, 4).text()
        assert all(str(path) in table.item(0, 5).text() for path in old_targets)
        submit = dialog.findChild(QPushButton, "replacement_commit")
        assert not submit.isEnabled()
        table.selectRow(0)
        dialog.findChild(QPushButton, "replacement_open_pdf").click()
        assert opened == [new, old_targets[0]]
        dialog.findChild(QCheckBox, "replacement_confirm").setChecked(True)
        assert submit.isEnabled()
        app.processEvents()
        assert dialog.grab().save(str(tmp_path / "replacement-preview.png"))
        submit.click()

    def compare():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        assert table.rowCount() == 2
        if checked:
            table.clearSelection()
            table.item(0, 0).setCheckState(Qt.Checked)
        else:
            table.selectRow(0)
        assert dialog.grab().save(str(tmp_path / "replacement-comparison.png"))
        schedule(app, preview, errors)
        dialog.findChild(QPushButton, "replace_archived_score").click()
        if errors or messages:
            dialog.reject()

    schedule(app, compare, errors)
    if archive:
        new_id = next(int(row["id"]) for row in db.list_import_items(window.current_batch_id) if row["source_path"] == str(new))
        assert window._commit_archive({new_id})
    else:
        window.import_score_paths([new])
    assert not errors and not messages
    record = dict(db.list_archived_scores()[0])
    assert record["id"] == old["id"] and record["seq"] == "007"
    assert record["storage_layout"] == old["storage_layout"]
    assert record["source_sha256"] == original_hashes[new]
    assert db.count_songs() == 1 and db.count_scores() == (1 if special else 3)
    assert all(sha256_file(path) == original_hashes[new] for path in old_targets)
    copies = list((root / "已确认重复").rglob("*.pdf"))
    assert len(copies) == 1 and sha256_file(copies[0]) == old["source_sha256"]
    manifest = json.loads((root / "已确认重复" / "重复清单.json").read_text(encoding="utf-8"))
    assert "用户选择新谱替换" in json.dumps(manifest, ensure_ascii=False)
    if unrelated:
        assert [row["source_path"] for row in db.list_import_items(window.current_batch_id)] == [str(sources[-1])]
        assert window.score_table.rowCount() == 1
    else:
        assert window.current_batch_id is None and window.score_table.rowCount() == 0
    assert {path: sha256_file(path) for path in sources} == original_hashes
    assert "已替换入库" in window.guidance_label.text()
    window.close()
    app.processEvents()


@pytest.mark.parametrize("cancel", ["reject", "unconfirmed_accept"])
def test_cancel_or_unconfirmed_preview_cannot_change_library(tmp_path, monkeypatch, cancel):
    app, db, root, sources, new = fixture(tmp_path)
    hashes = {path: sha256_file(path) for path in [*sources, *root.rglob("*.pdf")]}
    batch = ImportService(db).import_paths([new])
    window = MainWindow(db)
    errors = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: pytest.fail(args[-1]))

    def preview():
        dialog = app.activeModalWidget()
        assert not dialog.findChild(QCheckBox, "replacement_confirm").isChecked()
        (dialog.reject if cancel == "reject" else dialog.accept)()

    def compare():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "archived_duplicate_parts").selectRow(0)
        schedule(app, preview, errors)
        dialog.findChild(QPushButton, "replace_archived_score").click()
        dialog.reject()

    schedule(app, compare, errors)
    assert not window.resolve_archived_duplicates()
    assert not errors
    assert len(db.list_import_items(batch)) == 1
    assert {path: sha256_file(path) for path in hashes} == hashes
    assert not (root / "已确认重复").exists() and not (root / "修改备份").exists()
    window.close()


def test_multiple_existing_candidates_require_explicit_old_row(tmp_path, monkeypatch):
    app, db, root, sources, new = fixture(tmp_path)
    # A legitimate separate song with the same PDF makes hash matching ambiguous.
    song_id = db.create_song("01", "008", "另一首", status="archived")
    old = db.list_archived_scores()[0]
    db.add_score(song_id, "01", old["source_name"], old["source_path"], old["source_sha256"], "0101008另一首.pdf")
    other = next(row for row in db.list_archived_scores() if row["song_id"] == song_id)
    for path in ArchiveService(db, root).score_paths(other):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(sources[0].read_bytes())
    new.write_bytes(sources[0].read_bytes())
    batch = ImportService(db).import_paths([new])
    GroupingService(db).refresh_warnings(batch)
    window = MainWindow(db)
    messages, errors = [], []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    monkeypatch.setattr(window, "show_replacement_preview", lambda plan: False)

    def compare():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        table.selectRow(0)
        dialog.findChild(QPushButton, "replace_archived_score").click()
        assert len(messages) == 1 and "不唯一" in messages[0]
        old_row = next(index for index in range(table.rowCount()) if table.item(index, 2).text().startswith("007 /"))
        table.selectionModel().select(table.model().index(old_row, 0), QItemSelectionModel.Select | QItemSelectionModel.Rows)
        dialog.findChild(QPushButton, "replace_archived_score").click()
        assert len(messages) == 1
        dialog.reject()

    schedule(app, compare, errors)
    assert not window.resolve_archived_duplicates()
    assert not errors and len(db.list_import_items(batch)) == 1
    assert not (root / "修改备份").exists()
    window.close()


@pytest.mark.parametrize("unrelated", [False, True])
def test_postcommit_failure_refreshes_actual_state_and_preserves_stop_message(tmp_path, monkeypatch, unrelated):
    app, db, root, sources, new = fixture(tmp_path, unrelated=unrelated)
    batch = ImportService(db).import_paths([new], batch_id=db.latest_draft_batch_id())
    window = MainWindow(db)
    original = ReplacementService.commit_plan
    def late_failure(service, plan):
        original(service, plan)
        raise ValueError("替换已提交，清理失败，请停止整理。备份保留")
    monkeypatch.setattr(ReplacementService, "commit_plan", late_failure)
    monkeypatch.setattr(window, "show_replacement_preview", lambda plan: True)
    messages, errors = [], []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    def compare():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "archived_duplicate_parts").selectRow(0)
        dialog.findChild(QPushButton, "replace_archived_score").click()
        if errors or not messages:
            dialog.reject()
    schedule(app, compare, errors)
    assert not window.resolve_archived_duplicates()
    assert not errors and "替换已提交" in messages[0]
    assert "停止整理" in window.guidance_label.text()
    assert "尚未确认重复" not in window.guidance_label.text()
    assert window.score_table.rowCount() == int(unrelated)
    assert len(db.list_import_items(batch)) == int(unrelated)
    assert db.list_archived_scores()[0]["source_sha256"] == sha256_file(new)
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 1
    window.close()
