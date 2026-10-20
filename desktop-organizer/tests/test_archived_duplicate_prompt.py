import json

import pytest
from PySide6.QtCore import Qt, QTimer
from PySide6.QtWidgets import QApplication, QFileDialog, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.duplicate_service import DuplicateService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def library_fixture(tmp_path):
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "library.sqlite3")
    db.initialize()
    root = tmp_path / "输出"
    db.set_setting("output_root", str(root))
    source = tmp_path / "原始"
    source.mkdir()
    files = []
    for code in ("01", "02", "03"):
        path = source / f"{code}01天空之城.pdf"
        path.write_bytes(f"%PDF original {code}".encode())
        files.append(path)
    archive = ArchiveService(db, root)
    batch = ImportService(db).import_paths(files)
    archive.commit_plan(batch, archive.build_plan(batch))
    hashes = {path: sha256_file(path) for path in [*files, *root.rglob("*.pdf")]}
    return app, db, root, files, hashes


def make_duplicate(tmp_path, original, name="再次导入.pdf"):
    directory = tmp_path / "新导入"
    directory.mkdir(exist_ok=True)
    path = directory / name
    path.write_bytes(original.read_bytes())
    return path


def assert_library_unchanged(db, hashes):
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert {path: sha256_file(path) for path in hashes} == hashes


@pytest.mark.parametrize("folder", [False, True])
def test_import_entry_prompts_and_transfers_exact_copy_once(tmp_path, monkeypatch, folder):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0])
    window = MainWindow(db)
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    if folder:
        monkeypatch.setattr(QFileDialog, "getExistingDirectory", lambda *args: str(duplicate.parent))
    else:
        monkeypatch.setattr(QFileDialog, "getOpenFileNames", lambda *args: ([str(duplicate)], ""))
    def confirm():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "已有入库乐谱：确认是否转入重复库"
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        assert table.rowCount() == 2
        assert table.item(0, 0).checkState() == Qt.Unchecked
        assert "001 / 分类01 / 天空之城 / 乐器01" in table.item(1, 2).text()
        table.selectAll()
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "打开选中PDF对比").click()
        assert duplicate.resolve() in opened
        assert any("按乐器分类" in str(path) for path in opened)
        assert duplicate.read_bytes() == files[0].read_bytes()
        dialog.grab().save(str(tmp_path / "archived-duplicate-import.png"))
        dialog.findChild(QPushButton, "archived_duplicate_exact").click()
    QTimer.singleShot(50, confirm)
    (window.choose_import_folder if folder else window.choose_import_files)()
    assert window.current_batch_id is None and window.score_table.rowCount() == 0
    copies = list((root / "已确认重复").rglob("*.pdf"))
    assert len(copies) == 1 and sha256_file(copies[0]) == sha256_file(duplicate)
    manifest = json.loads(next((root / "已确认重复").rglob("重复清单.json")).read_text(encoding="utf-8"))
    assert "天空之城" in json.dumps(manifest, ensure_ascii=False)
    assert_library_unchanged(db, hashes)
    window.close()
    app.processEvents()


@pytest.mark.parametrize("archive", [False, True])
def test_cancel_keeps_copy_in_draft_and_does_not_archive_or_transfer(tmp_path, archive):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0], "0101天空之城.pdf")
    batch = ImportService(db).import_paths([duplicate]) if archive else None
    window = MainWindow(db)
    def cancel():
        dialog = app.activeModalWidget()
        assert dialog.findChild(QTableWidget, "archived_duplicate_parts").rowCount() == 2
        dialog.reject()
    QTimer.singleShot(50, cancel)
    if archive:
        assert window._commit_archive() is False
    else:
        window.import_score_paths([duplicate])
        batch = window.current_batch_id
    assert len(db.list_import_items(batch)) == 1
    assert not (root / "已确认重复").exists()
    assert_library_unchanged(db, hashes)
    assert duplicate.is_file()
    window.close()
    app.processEvents()


def test_same_song_instrument_different_content_requires_manual_selection(tmp_path):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0], "0101天空之城.pdf")
    duplicate.write_bytes(b"%PDF different arrangement manually compared")
    original_hash = sha256_file(duplicate)
    window = MainWindow(db)
    def confirm():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        assert not dialog.findChild(QPushButton, "archived_duplicate_exact").isEnabled()
        assert "需要对比" in table.item(0, 1).text()
        assert table.item(0, 0).checkState() == Qt.Unchecked
        assert not (table.item(1, 0).flags() & Qt.ItemIsUserCheckable)
        table.item(0, 0).setCheckState(Qt.Checked)
        dialog.findChild(QPushButton, "archived_duplicate_confirm").click()
    QTimer.singleShot(50, confirm)
    window.import_score_paths([duplicate])
    assert window.current_batch_id is None
    assert sha256_file(next((root / "已确认重复").rglob("*.pdf"))) == original_hash
    assert sha256_file(duplicate) == original_hash
    assert_library_unchanged(db, hashes)
    window.close()
    app.processEvents()


def test_import_only_prompts_for_new_ids_not_old_conflicting_drafts(tmp_path):
    app, db, root, files, hashes = library_fixture(tmp_path)
    old = make_duplicate(tmp_path, files[0], "旧的重复.pdf")
    batch = ImportService(db).import_paths([old])
    old_id = int(db.list_import_items(batch)[0]["id"])
    new = make_duplicate(tmp_path, files[1], "新的重复.pdf")
    window = MainWindow(db)
    def confirm():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        assert table.rowCount() == 2 and table.item(0, 3).text() == new.name
        dialog.findChild(QPushButton, "archived_duplicate_exact").click()
    QTimer.singleShot(50, confirm)
    window.import_score_paths([new])
    assert [int(item["id"]) for item in db.list_import_items(batch)] == [old_id]
    assert window.score_table.rowCount() == 1
    assert_library_unchanged(db, hashes)
    window.close()
    app.processEvents()


def test_archive_transfers_copy_then_continues_real_supplement_preview(tmp_path, monkeypatch):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0], "0101天空之城.pdf")
    supplement = duplicate.parent / "0401天空之城.pdf"
    supplement.write_bytes(b"%PDF new cello")
    batch = ImportService(db).import_paths([duplicate, supplement])
    window = MainWindow(db)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def confirm_archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 1 and table.item(0, 3).text() == "0401001天空之城.pdf"
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档").click()
    def confirm_duplicate():
        dialog = app.activeModalWidget()
        QTimer.singleShot(50, confirm_archive)
        dialog.findChild(QPushButton, "archived_duplicate_exact").click()
    QTimer.singleShot(50, confirm_duplicate)
    assert window._commit_archive() is True
    assert db.count_songs() == 1 and db.count_scores() == 4
    assert db.list_import_items(batch) == [] and window.current_batch_id is None
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 1
    for path in (root / "按曲目分类" / "01001天空之城（04）").glob("*.pdf"):
        assert sha256_file(path) == sha256_file(next((root / "按乐器分类").rglob(path.name)))
    assert sha256_file(duplicate) == hashes[files[0]]
    window.close()
    app.processEvents()


def test_corrected_draft_prompts_for_existing_instrument_before_archive(tmp_path):
    app, db, root, files, hashes = library_fixture(tmp_path)
    source = make_duplicate(tmp_path, files[0], "0102错误名称.pdf")
    source.write_bytes(b"%PDF corrected different content")
    batch = ImportService(db).import_paths([source])
    item_id = int(db.list_import_items(batch)[0]["id"])
    song_id = int(db.find_archived_song("01", "天空之城")["id"])
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [item_id], target_song_id=song_id))
    window = MainWindow(db)
    def confirm():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "archived_duplicate_parts")
        assert "001 / 分类01 / 天空之城" in table.item(1, 2).text()
        table.item(0, 0).setCheckState(Qt.Checked)
        dialog.findChild(QPushButton, "archived_duplicate_confirm").click()
    QTimer.singleShot(50, confirm)
    assert window._commit_archive({item_id}) is True
    assert db.list_import_items(batch) == []
    assert_library_unchanged(db, hashes)
    assert source.is_file()
    window.close()
    app.processEvents()


def test_defer_duplicates_can_archive_unrelated_ready_song(tmp_path, monkeypatch):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0], "0101天空之城.pdf")
    new_files = []
    for code in ("01", "02", "03"):
        path = duplicate.parent / f"{code}02独立曲目.pdf"
        path.write_bytes(f"%PDF unrelated {code}".encode())
        new_files.append(path)
    batch = ImportService(db).import_paths([duplicate, *new_files])
    window = MainWindow(db)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    def confirm_archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 3
        assert all("独立曲目" in table.item(row, 3).text() for row in range(3))
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档").click()
    def defer():
        dialog = app.activeModalWidget()
        QTimer.singleShot(50, confirm_archive)
        dialog.findChild(QPushButton, "archived_duplicate_continue").click()
    QTimer.singleShot(50, defer)
    assert window._commit_archive() is True
    remaining = db.list_import_items(batch)
    assert len(remaining) == 1 and remaining[0]["source_path"] == str(duplicate)
    assert db.count_songs() == 2 and db.count_scores() == 6
    assert not (root / "已确认重复").exists()
    assert {path: sha256_file(path) for path in hashes} == hashes
    window.close()
    app.processEvents()


@pytest.mark.parametrize("failure", ["source_changed", "library_missing", "missing_output"])
def test_failed_transfer_keeps_draft_and_does_not_report_success(tmp_path, monkeypatch, failure):
    app, db, root, files, hashes = library_fixture(tmp_path)
    duplicate = make_duplicate(tmp_path, files[0])
    messages = []
    window = MainWindow(db)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: messages.append(args[-1]))
    def confirm():
        dialog = app.activeModalWidget()
        if failure == "source_changed":
            duplicate.write_bytes(b"%PDF changed after preview")
        elif failure == "library_missing":
            next((root / "按乐器分类").rglob("0101001*.pdf")).unlink()
        else:
            db.set_setting("output_root", "")
        dialog.findChild(QPushButton, "archived_duplicate_exact").click()
        assert messages and dialog.isVisible()
        dialog.reject()
    QTimer.singleShot(50, confirm)
    window.import_score_paths([duplicate])
    assert len(db.list_import_items(window.current_batch_id)) == 1
    assert window.score_table.rowCount() == 1
    assert not list((root / "已确认重复").rglob("*.pdf"))
    assert db.count_scores() == 3 and duplicate.is_file()
    window.close()
    app.processEvents()


def test_service_scope_only_returns_selected_draft_and_library_rows(tmp_path):
    app, db, root, files, hashes = library_fixture(tmp_path)
    copies = [make_duplicate(tmp_path, files[0], name) for name in ("副本一.pdf", "副本二.pdf")]
    batch = ImportService(db).import_paths(copies)
    GroupingService(db).refresh_warnings(batch)
    ids = [int(item["id"]) for item in db.list_import_items(batch)]
    rows = DuplicateService(db).archived_conflict_rows(batch, {ids[1]})
    assert {row["item_id"] for row in rows if row["item_id"] is not None} == {ids[1]}
    assert any(row["existing"] is not None for row in rows)
    assert_library_unchanged(db, hashes)
    app.processEvents()
