from pathlib import Path
import json

from PySide6.QtWidgets import QApplication, QMessageBox

from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def test_bulk_duplicate_110_groups_check_once_and_confirm_once_native(tmp_path):
    from PySide6.QtCore import Qt, QTimer
    from PySide6.QtWidgets import QTableWidget, QPushButton
    from test_bulk_duplicates import bulk_fixture
    from score_organizer.services.import_service import sha256_file
    app = QApplication.instance() or QApplication([])
    db, batch, files = bulk_fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in files}
    window = MainWindow(db)
    questions = []
    def answer():
        box = app.activeModalWidget()
        assert isinstance(box, QMessageBox)
        questions.append(box.text())
        assert "110 组、110 份" in box.text()
        box.grab().save(str(tmp_path / "bulk-confirm.png"))
        box.button(QMessageBox.Yes).click()
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "duplicate_parts")
        assert table.rowCount() == 220
        dialog.findChild(QPushButton, "duplicate_select_exact").click()
        assert sum(table.item(row, 0).checkState() == Qt.Checked for row in range(220)) == 110
        dialog.grab().save(str(tmp_path / "bulk-selection.png"))
        QTimer.singleShot(50, answer)
        dialog.findChild(QPushButton, "duplicate_confirm_selected").click()
        assert table.rowCount() == 0
        dialog.reject()
    QTimer.singleShot(50, inspect)
    window.show_duplicate_files()
    assert len(questions) == 1
    assert len(db.list_import_items(batch)) == 110
    assert len(list((tmp_path / "out" / "已确认重复").rglob("*.pdf"))) == 110
    assert {p: sha256_file(p) for p in files} == hashes
    window.close()
    app.processEvents()


def test_duplicate_filter_hidden_checks_are_not_transferred_and_cancel_keeps_draft(tmp_path, monkeypatch):
    from PySide6.QtCore import Qt, QTimer
    from PySide6.QtWidgets import QTableWidget, QPushButton, QLineEdit
    from test_bulk_duplicates import bulk_fixture
    app = QApplication.instance() or QApplication([])
    db, batch, _ = bulk_fixture(tmp_path, 2)
    window = MainWindow(db)
    messages = []
    def answer(*args):
        messages.append(args[2])
        return QMessageBox.No
    monkeypatch.setattr(QMessageBox, "question", answer)
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "duplicate_parts")
        dialog.findChild(QPushButton, "duplicate_select_exact").click()
        assert sum(table.item(row, 0).checkState() == Qt.Checked for row in range(4)) == 2
        search = dialog.findChild(QLineEdit)
        name = table.item(0, 4).text()
        search.setText(name)
        dialog.findChild(QPushButton, "duplicate_confirm_selected").click()
        assert "1 组、1 份" in messages[0]
        dialog.findChild(QPushButton, "duplicate_clear_selection").click()
        assert all(table.item(row, 0).checkState() == Qt.Unchecked for row in range(4))
        assert table.selectedItems() == []
        dialog.findChild(QPushButton, "duplicate_select_visible").click()
        assert sum(table.item(row, 0).checkState() == Qt.Checked for row in range(4)) == 2
        dialog.reject()
    QTimer.singleShot(50, inspect)
    window.show_duplicate_files()
    assert len(db.list_import_items(batch)) == 4
    assert not (tmp_path / "out").exists()
    window.close()
    app.processEvents()


def test_whitespace_warning_is_visible_blocks_and_opens_all_related_pdfs(tmp_path, monkeypatch):
    from score_organizer.services.import_service import ImportService
    from PySide6.QtCore import Qt
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "ui.sqlite3")
    db.initialize()
    files = []
    for index, (code, title) in enumerate([
        ("02", "萤火虫之墓 《可爱的家 》"), ("03", "萤火虫之墓 《可爱的家 》"),
        ("04", "萤火虫之墓 《可爱的家 》"), ("02", "萤火虫之墓《可爱的家》"),
        ("05", "萤火虫之墓《可爱的家》"), ("06", "萤火虫之墓《可爱的家》")]):
        path = tmp_path / f"{code}01{title}.pdf"
        path.write_bytes(f"%PDF {index}".encode())
        files.append(path)
    batch = ImportService(db).import_paths(files)
    window = MainWindow(db)
    window.current_batch_id = batch
    window.refresh_warnings(batch)
    window.refresh_import_items()
    window.refresh_song_reports()
    window.show()
    app.processEvents()
    assert window.warning_table.rowCount() == 1
    assert "空格" in window.warning_table.item(0, 0).text()
    assert window.warning_table.item(0, 1).text() == "阻断"
    assert window.warning_table.item(0, 0).toolTip() == "曲名仅空格不同，需校对"
    assert "[空格]" in window.warning_table.item(0, 3).toolTip()
    assert "[空格]" in window._warning_details(json.loads(db.list_warnings(batch)[0]["details"]))
    assert all(window.score_table.item(row, 5).text() == "待处理" for row in range(6))
    assert all(window.report_table.item(row, 3).text() == "1" for row in range(2))
    assert len(window.warning_table.item(0, 0).data(Qt.UserRole + 1)) == 6
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    window.warning_table.selectRow(0)
    window.open_selected_warning_pdf()
    assert set(opened) == set(files)
    assert window.grab().save(str(tmp_path / "whitespace-warning.png"))
    window.close()
    app.processEvents()


def test_catalog_merge_button_comparison_preview_commit_and_catalog_refresh(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QCheckBox, QComboBox, QLineEdit, QTableWidget, QPushButton
    from test_catalog_merge_service import merge_fixture, formal_hashes
    app = QApplication.instance() or QApplication([])
    db, root, ids, sources = merge_fixture(tmp_path)
    window = MainWindow(db)
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    errors = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: errors.append(str(args[-1])))
    observed = []
    def inspect_catalog():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        observed.append((table.rowCount(), table.item(0, 1).text(), table.item(0, 3).text()))
        dialog.grab().save(str(tmp_path / "merge-catalog.png"))
        dialog.reject()
    def confirm_preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认误拆合并方案"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 6
        assert table.columnCount() == 6
        assert table.horizontalHeader().sectionViewportPosition(5) + table.columnWidth(5) <= table.viewport().width()
        assert sum(table.item(row, 3).text() == "确认重复并转存" for row in range(6)) == 1
        dialog.grab().save(str(tmp_path / "merge-preview.png"))
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "备份并确认合并").click()
    def fill_merge():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "merge_parts")
        title = dialog.findChild(QLineEdit, "merge_title")
        title.setText("萤火虫之墓《可爱的家》")
        submit = next(b for b in dialog.findChildren(QPushButton) if b.text() == "预览合并")
        assert not submit.isEnabled()
        for row in range(table.rowCount()):
            combo = table.cellWidget(row, 4)
            if combo.isEnabled():
                combo.setCurrentIndex(combo.findData("keep" if table.item(row, 0).text() == "007" else "duplicate"))
        table.selectAll()
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "打开选中PDF").click()
        assert len(opened) == 6
        dialog.findChild(QCheckBox, "merge_consent").setChecked(True)
        dialog.grab().save(str(tmp_path / "merge-editor.png"))
        QTimer.singleShot(50, confirm_preview)
        submit.click()
        QTimer.singleShot(50, inspect_catalog)
    def open_merge():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget).selectRow(0)
        QTimer.singleShot(50, fill_merge)
        dialog.findChild(QPushButton, "catalog_merge").click()
    QTimer.singleShot(50, open_merge)
    window.show_archived_catalog()
    assert errors == []
    assert observed == [(1, "007", "5")]
    assert len(formal_hashes(root)) == 10
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 2
    assert db.allocate_next_sequence() == "001"
    assert all(path.is_file() for path in sources)
    window.close()
    app.processEvents()


def test_merge_cancel_after_real_preview_preserves_records_and_files(tmp_path):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QCheckBox, QLineEdit, QTableWidget, QPushButton
    from test_catalog_merge_service import merge_fixture, formal_hashes
    app = QApplication.instance() or QApplication([])
    db, root, ids, _ = merge_fixture(tmp_path)
    before = formal_hashes(root)
    window = MainWindow(db)
    def cancel_preview():
        app.activeModalWidget().reject()
    def fill():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget, "merge_parts")
        dialog.findChild(QLineEdit, "merge_title").setText("萤火虫之墓《可爱的家》")
        for row in range(table.rowCount()):
            combo = table.cellWidget(row, 4)
            if combo.isEnabled():
                combo.setCurrentIndex(combo.findData("keep" if table.item(row, 0).text() == "007" else "duplicate"))
        dialog.findChild(QCheckBox, "merge_consent").setChecked(True)
        QTimer.singleShot(50, cancel_preview)
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "预览合并").click()
        dialog.reject()
    QTimer.singleShot(50, fill)
    assert window.merge_archived_song(ids[0]) is False
    assert formal_hashes(root) == before
    assert len(db.list_archived_scores()) == 6
    assert not (root / "修改备份").exists()
    assert not (root / "已确认重复").exists()
    window.close()
    app.processEvents()


def test_category_saved_from_window_persists(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    window = MainWindow(db)

    window.category_code_input.setText("02")
    window.category_name_input.setText("迪士尼")
    window.category_active_input.setChecked(True)
    window.save_category()

    reopened = Database(db_path)
    reopened.initialize()
    categories = reopened.list_categories()
    assert ("02", "迪士尼") in [(row["code"], row["name"]) for row in categories]

    window.close()
    app.processEvents()


def test_output_path_saved_from_window_persists(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    window = MainWindow(db)

    window.output_path_input.setText(str(tmp_path / "乐谱库输出"))
    window.save_output_path()

    reopened = Database(db_path)
    reopened.initialize()
    assert reopened.get_setting("output_root") == str(tmp_path / "乐谱库输出")

    window.close()
    app.processEvents()


def test_import_items_show_after_window_reopens(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    pdf = tmp_path / "0101天空之城.pdf"
    pdf.write_bytes(b"%PDF-1.4 fake score")

    db = Database(db_path)
    db.initialize()
    window = MainWindow(db)
    window.db.create_import_batch()
    from score_organizer.services.import_service import ImportService

    ImportService(window.db).import_paths([pdf])
    window.current_batch_id = window.db.latest_draft_batch_id()
    window.refresh_import_items()
    assert window.score_table.rowCount() == 1
    assert window.score_table.item(0, 1).text() == "0101天空之城.pdf"
    window.close()

    reopened = MainWindow(Database(db_path))
    reopened.refresh_import_items()
    assert reopened.score_table.rowCount() == 1
    assert reopened.score_table.item(0, 5).text() == "待补齐"
    assert reopened.current_batch_id is not None
    assert "已载入最近的草稿" in reopened.guidance_label.text()

    reopened.close()
    app.processEvents()


def test_cancel_archive_review_keeps_sources_draft_and_catalog(tmp_path, monkeypatch):
    from score_organizer.services.import_service import ImportService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01测试.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    batch = ImportService(db).import_paths(files)
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    monkeypatch.setattr(window, "show_archive_review", lambda *args, **kwargs: False)
    window.commit_current_archive_plan()
    assert db.count_songs() == db.count_scores() == 0
    assert len(db.list_import_items(batch)) == 3
    assert not (tmp_path / "out").exists()
    window.close()
    app.processEvents()


def test_duplicate_status_and_report_remain_blocked_after_refresh(tmp_path, monkeypatch):
    from score_organizer.services.import_service import ImportService
    from PySide6.QtWidgets import QAbstractItemView
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01测试.pdf"
        path.write_bytes(b"%PDF duplicate" if code != "03" else b"%PDF third")
        files.append(path)
    batch = ImportService(db).import_paths(files)
    window = MainWindow(db)
    window.refresh_song_reports()
    window.refresh_import_items()
    assert window.score_table.item(0, 5).text() == "待处理"
    assert int(window.report_table.item(0, 3).text()) >= 1
    assert window.score_table.editTriggers() == QAbstractItemView.NoEditTriggers
    warnings = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args))
    window.warning_table.selectRow(0)
    window.mark_selected_warning_accepted()
    assert warnings
    assert all(w["state"] == "待处理" for w in db.list_warnings(batch))
    window.close()
    app.processEvents()


def test_scanning_then_reporting_retains_incomplete_warning(tmp_path):
    from score_organizer.services.import_service import ImportService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    pdf = tmp_path / "0101测试.pdf"
    pdf.write_bytes(b"%PDF incomplete")
    batch = ImportService(db).import_paths([pdf])
    window = MainWindow(db)
    window.scan_completeness()
    warning_id = db.list_warnings(batch)[0]["id"]
    window.refresh_song_reports()
    assert any(w["code"] == "insufficient_instruments" for w in db.list_warnings(batch))
    assert db.list_warnings(batch)[0]["id"] == warning_id
    assert window.report_table.item(0, 3).text() == "1"
    window.close()
    app.processEvents()


def test_archive_review_dialog_shows_each_rename_and_can_cancel(tmp_path):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QDialog, QTableWidget
    from score_organizer.services.import_service import ImportService
    from score_organizer.services.archive_service import ArchiveService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01测试.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    batch = ImportService(db).import_paths(files)
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    plans = ArchiveService(db, tmp_path / "out").build_plan(batch)
    observed = []
    def inspect():
        dialog = app.activeModalWidget()
        if isinstance(dialog, QDialog):
            table = dialog.findChild(QTableWidget)
            observed.extend((table.item(row, 2).text(), table.item(row, 3).text()) for row in range(table.rowCount()))
            dialog.reject()
    QTimer.singleShot(100, inspect)
    assert window.show_archive_review(plans, confirm=True) is False
    assert observed == [(file.source_path.name, file.output_name) for file in plans[0].files]
    assert db.count_scores() == 0
    window.close()


def test_catalog_can_search_and_open_archived_pdf(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QDialog, QTableWidget, QLineEdit
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    song = db.create_song("01", "001", "天空之城", status="archived")
    db.add_score(song, "01", "source.pdf", str(tmp_path / "source.pdf"), "hash", "0101001天空之城.pdf")
    root = tmp_path / "out"
    archived = root / "按乐器分类" / "01第一小提琴" / "0101001天空之城.pdf"
    archived.parent.mkdir(parents=True)
    archived.write_bytes(b"%PDF archived")
    db.set_setting("output_root", str(root))
    window = MainWindow(db)
    observed = []
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", lambda path: opened.append(path))
    def inspect():
        dialog = app.activeModalWidget()
        if isinstance(dialog, QDialog):
            table = dialog.findChild(QTableWidget)
            search = dialog.findChild(QLineEdit)
            observed.append(table.rowCount())
            search.setText("不存在")
            observed.append(table.isRowHidden(0))
            search.setText("天空")
            observed.append(table.isRowHidden(0))
            table.cellDoubleClicked.emit(0, 0)
            dialog.reject()
    QTimer.singleShot(100, inspect)
    window.show_archived_catalog()
    assert observed == [1, True, False]
    assert opened == [archived]
    window.close()


def test_catalog_groups_parts_by_song_keeps_versions_and_refreshes(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QTableWidget
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    first = db.create_song("01", "001", "曲目", status="archived")
    second = db.create_song("01", "002", "曲目", version_no=2, status="archived")
    for code in ("01", "02", "03"):
        db.add_score(first, code, "source.pdf", "source.pdf", code, f"{code}01001曲目.pdf")
    db.add_score(second, "01", "a.pdf", "a.pdf", "a", "a.pdf")
    db.add_score(second, "01", "b.pdf", "b.pdf", "b", "b.pdf")
    window = MainWindow(db)
    observed = []
    opened = []
    monkeypatch.setattr(window, "show_archived_parts", lambda scores: opened.append(len(scores)))
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        observed.append((table.rowCount(), table.item(0, 3).text(), table.item(0, 4).text(), table.item(1, 5).text()))
        table.cellDoubleClicked.emit(0, 0)
        dialog.reject()
    QTimer.singleShot(100, inspect)
    window.show_archived_catalog()
    db.add_score(first, "04", "fourth.pdf", "fourth.pdf", "fourth", "0401001曲目.pdf")
    QTimer.singleShot(100, inspect)
    window.show_archived_catalog()
    assert observed == [(2, "3", "3", "同乐器多份，需核对"), (2, "4", "4", "同乐器多份，需核对")]
    assert opened == [3, 4]
    assert db.count_scores() == 6
    window.close()
    app.processEvents()


def _catalog_edit_fixture(tmp_path):
    from score_organizer.services.archive_service import ArchiveService
    from score_organizer.services.import_service import ImportService
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    batch = ImportService(db).import_paths(files)
    root = tmp_path / "out"
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch))
    db.set_setting("output_root", str(root))
    return db, root, int(db.find_archived_song("01", "曲目")["id"])


def test_catalog_edit_button_updates_catalog_and_both_directories(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QTableWidget, QLineEdit, QComboBox, QPushButton
    app = QApplication.instance() or QApplication([])
    db, root, song_id = _catalog_edit_fixture(tmp_path)
    window = MainWindow(db)
    previews = []
    monkeypatch.setattr(window, "show_catalog_edit_preview", lambda plan: previews.append(plan) or True)
    observed = []
    def inspect_saved():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        observed.append((table.item(0, 0).text(), table.item(0, 1).text(), table.item(0, 2).text()))
        dialog.reject()
    def fill_editor():
        dialog = app.activeModalWidget()
        title = dialog.findChild(QLineEdit, "catalog_title")
        category = dialog.findChild(QComboBox, "catalog_category")
        title.setText("已修改曲名")
        category.setCurrentIndex(category.findData("02"))
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "预览修改").click()
        QTimer.singleShot(50, inspect_saved)
    def open_editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget).selectRow(0)
        QTimer.singleShot(50, fill_editor)
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "修改选中曲目的曲名和分类").click()
    QTimer.singleShot(50, open_editor)
    window.show_archived_catalog()
    assert observed == [("02", "001", "已修改曲名")]
    assert len(previews) == 1 and len(previews[0].files) == 3
    assert db.get_archived_song(song_id)["seq"] == "001"
    assert len(list((root / "按曲目分类" / "02001已修改曲名（03）").glob("*.pdf"))) == 3
    assert len(list((root / "修改备份").rglob("修改前.sqlite3"))) == 1
    window.close()
    app.processEvents()


def test_catalog_edit_preview_cancel_does_not_write_changes(tmp_path):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QTableWidget
    from score_organizer.services.catalog_service import CatalogService
    app = QApplication.instance() or QApplication([])
    db, root, song_id = _catalog_edit_fixture(tmp_path)
    window = MainWindow(db)
    plan = CatalogService(db, root).build_plan(song_id, "02", "新曲名")
    observed = []
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        observed.append((table.rowCount(), table.item(0, 2).text(), table.item(0, 3).text()))
        dialog.reject()
    QTimer.singleShot(100, inspect)
    assert window.show_catalog_edit_preview(plan) is False
    assert observed == [(6, "0101001曲目.pdf", "0102001新曲名.pdf")]
    assert not (root / "修改备份").exists()
    assert db.get_archived_song(song_id)["display_title"] == "曲目"
    window.close()
    app.processEvents()


def test_full_clear_button_clears_library_and_restores_last_clear(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QPushButton
    from score_organizer.services.import_service import sha256_file
    app = QApplication.instance() or QApplication([])
    db, root, song_id = _catalog_edit_fixture(tmp_path)
    files = {path.relative_to(root): sha256_file(path) for path in root.rglob("*.pdf")}
    window = MainWindow(db)
    monkeypatch.setattr(window, "show_clear_all_confirmation", lambda plan: True)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    window.menu_actions["clear_all"].trigger()
    assert db.count_songs() == db.count_scores() == 0
    assert window.score_table.rowCount() == window.report_table.rowCount() == window.warning_table.rowCount() == 0
    assert window.current_batch_id is None
    assert db.allocate_next_sequence() == "001"
    assert "全部整理数据已清空" in window.import_summary_label.text()
    assert db.get_setting("last_reset_backup")
    window.restore_last_clear()
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert all(sha256_file(root / path) == digest for path, digest in files.items())
    window.close()
    app.processEvents()


def test_full_clear_dialog_requires_confirmation_and_cancel_keeps_library(tmp_path):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QCheckBox, QPushButton
    from score_organizer.services.reset_service import ResetService
    app = QApplication.instance() or QApplication([])
    db, root, song_id = _catalog_edit_fixture(tmp_path)
    window = MainWindow(db)
    states = []
    def inspect():
        dialog = app.activeModalWidget()
        button = next(button for button in dialog.findChildren(QPushButton) if button.text() == "备份并清空全部")
        states.append(button.isEnabled())
        dialog.findChild(QCheckBox).setChecked(True)
        states.append(button.isEnabled())
        dialog.reject()
    QTimer.singleShot(100, inspect)
    assert not window.show_clear_all_confirmation(ResetService(db).build_plan())
    assert states == [False, True]
    assert db.count_scores() == 3
    assert (root / "按曲目分类").is_dir()
    assert not (root / "清空备份").exists()
    window.close()
    app.processEvents()


def test_review_confirmation_cancel_keeps_draft(tmp_path, monkeypatch):
    from score_organizer.services.import_service import ImportService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    pdf = tmp_path / "0101曲目.pdf"
    pdf.write_bytes(b"%PDF incomplete")
    batch = ImportService(db).import_paths([pdf])
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    monkeypatch.setattr(window, "show_review_confirmation", lambda plan: False)
    window.score_table.selectRow(0)
    window.archive_to_review()
    assert len(db.list_import_items(batch)) == 1
    assert not (tmp_path / "out" / "待审查").exists()
    window.close()
    app.processEvents()


def test_duplicate_dialog_keeps_chosen_score_and_then_archives(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QTableWidget, QPushButton
    from score_organizer.services.import_service import ImportService, sha256_file
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    duplicate = tmp_path / "duplicate" / "0101曲目.pdf"
    duplicate.parent.mkdir()
    duplicate.write_bytes(files[0].read_bytes())
    files.append(duplicate)
    hashes = {path: sha256_file(path) for path in files}
    ImportService(db).import_paths(files)
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(window, "show_archive_review", lambda *args, **kwargs: True)
    observed = []
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        row = next(row for row in range(table.rowCount()) if table.item(row, 5).text() == str(files[0]))
        table.selectRow(row)
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "保留选中，其他分谱转存已确认重复").click()
        observed.append(table.rowCount())
        dialog.reject()
    QTimer.singleShot(100, inspect)
    window.show_duplicate_files()
    assert observed == [0]
    assert window.score_table.rowCount() == 3
    window.commit_current_archive_plan()
    assert db.count_scores() == 3
    assert len(list((tmp_path / "out" / "按曲目分类").rglob("*.pdf"))) == 3
    assert len(list((tmp_path / "out" / "按乐器分类").rglob("*.pdf"))) == 3
    assert len(list((tmp_path / "out" / "已确认重复").rglob("*.pdf"))) == 1
    assert all(sha256_file(path) == digest for path, digest in hashes.items())
    window.close()
    app.processEvents()


def test_blocked_archive_opens_duplicate_list_instead_of_missing_instruments_message(tmp_path, monkeypatch):
    from score_organizer.services.import_service import ImportService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(b"%PDF same" if code != "03" else b"%PDF third")
        files.append(path)
    ImportService(db).import_paths(files)
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    opened = []
    monkeypatch.setattr(window, "show_duplicate_files", lambda: opened.append(True))
    window.commit_current_archive_plan()
    assert opened == [True]
    assert "重复" in window.guidance_label.text()
    assert db.count_scores() == 0
    window.close()
    app.processEvents()


def test_remove_already_saved_duplicate_then_archive_new_supplement(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QPushButton
    from score_organizer.services.archive_service import ArchiveService
    from score_organizer.services.import_service import ImportService, sha256_file
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for code in ("01", "02", "03", "04"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    initial = ImportService(db).import_paths(files[:3])
    root = tmp_path / "out"
    archive = ArchiveService(db, root)
    archive.commit_plan(initial, archive.build_plan(initial))
    ImportService(db).import_paths([files[0], files[3]])
    db.set_setting("output_root", str(root))
    window = MainWindow(db)
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    monkeypatch.setattr(window, "show_archive_review", lambda *args, **kwargs: True)
    def inspect():
        dialog = app.activeModalWidget()
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认并转存全批已入库相同副本").click()
        dialog.reject()
    QTimer.singleShot(100, inspect)
    window.show_duplicate_files()
    assert window.score_table.rowCount() == 1
    assert db.count_scores() == 3
    window.commit_current_archive_plan()
    assert db.count_scores() == 4
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 1
    folder = root / "按曲目分类" / "01001曲目（04）"
    assert len(list(folder.glob("*.pdf"))) == 4
    assert all(path.is_file() for path in files)
    for path in folder.glob("*.pdf"):
        assert sha256_file(path) == sha256_file(next((root / "按乐器分类").rglob(path.name)))
    window.close()
    app.processEvents()


def test_review_window_saves_opens_and_restores_selected_pdf(tmp_path, monkeypatch):
    from PySide6.QtCore import QTimer
    from PySide6.QtWidgets import QTableWidget, QPushButton
    from score_organizer.services.import_service import ImportService
    from score_organizer.services.review_service import ReviewService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    pdf = tmp_path / "0101曲目.pdf"
    pdf.write_bytes(b"%PDF incomplete")
    ImportService(db).import_paths([pdf])
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    monkeypatch.setattr(window, "show_review_confirmation", lambda plan: True)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    window.score_table.selectRow(0)
    window.archive_to_review()
    assert window.current_batch_id is None
    assert window.score_table.rowCount() == 0
    opened = []
    observed = []
    monkeypatch.setattr(window, "open_pdf_path", lambda path: opened.append(path))
    def inspect():
        dialog = app.activeModalWidget()
        table = dialog.findChild(QTableWidget)
        observed.append((table.rowCount(), table.item(0, 1).text()))
        table.selectRow(0)
        table.cellDoubleClicked.emit(0, 0)
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "放回草稿继续整理").click()
        observed.append(table.rowCount())
        dialog.reject()
    QTimer.singleShot(100, inspect)
    window.show_pending_review()
    assert observed == [(1, "乐器不足"), 0]
    assert len(opened) == 1 and opened[0].is_file()
    assert window.score_table.rowCount() == 1
    assert window.score_table.item(0, 5).text() == "待补齐"
    assert db.count_songs() == 0
    assert ReviewService(db, tmp_path / "out").list_pending() == []
    window.close()
    app.processEvents()


def test_supplement_window_and_scan_count_archived_instruments(tmp_path):
    from score_organizer.services.import_service import ImportService
    app = QApplication.instance() or QApplication([])
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    song = db.create_song("01", "001", "测试", status="archived")
    for code in ("01", "02", "03"):
        db.add_score(song, code, "old.pdf", "old.pdf", code, f"{code}01001测试.pdf")
    pdf = tmp_path / "0401测试.pdf"
    pdf.write_bytes(b"%PDF supplement")
    batch = ImportService(db).import_paths([pdf])
    window = MainWindow(db)
    window.scan_completeness()
    window.refresh_song_reports()
    window.refresh_import_items()
    assert window.score_table.item(0, 5).text() == "可归档"
    assert window.report_table.item(0, 2).text() == "4"
    assert not any(w["code"] == "insufficient_instruments" for w in db.list_warnings(batch))
    window.close()
    app.processEvents()


def test_manual_entry_from_window_updates_selected_import_item(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    pdf = tmp_path / "天空之城.pdf"
    pdf.write_bytes(b"%PDF-1.4 needs manual entry")

    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    ImportService(db).import_paths([pdf])
    window = MainWindow(db)
    window.score_table.selectRow(0)
    window.manual_instrument_input.setText("01")
    window.manual_category_input.setCurrentIndex(window.manual_category_input.findData("01"))
    window.manual_title_input.setText("天空之城")
    window.apply_manual_entry_to_selected()

    assert window.score_table.item(0, 0).text() == "01"
    assert window.score_table.item(0, 2).text() == "天空之城"
    assert window.score_table.item(0, 5).text() == "待补齐"

    window.close()
    app.processEvents()


def test_import_summary_label_updates(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    db.add_import_item(batch_id, "a.pdf", "1", "recognized", {})
    db.add_import_item(batch_id, "b.pdf", "2", "needs_manual_entry", {})

    window = MainWindow(db)
    window.show_import_summary(batch_id)

    assert "已存入草稿" in window.import_summary_label.text()
    assert "共 2 份" in window.import_summary_label.text()
    assert "已识别 1" in window.import_summary_label.text()
    assert "待补录 1" in window.import_summary_label.text()

    window.close()
    app.processEvents()


def test_guidance_updates_after_output_path_saved(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    window = MainWindow(db)

    window.output_path_input.setText(str(tmp_path / "out"))
    window.save_output_path()

    assert "输出文件夹已保存" in window.guidance_label.text()

    window.close()
    app.processEvents()


def test_warning_table_updates_for_current_batch(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    db.add_import_item(
        batch_id,
        "0101天空之城.pdf",
        "a",
        "recognized",
        {"instrument_code": "01", "category_code": "01", "title": "天空之城"},
    )
    db.add_import_item(
        batch_id,
        "0201天空之城.pdf",
        "b",
        "recognized",
        {"instrument_code": "02", "category_code": "01", "title": "天空之城"},
    )

    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.scan_completeness()

    assert window.warning_table.rowCount() == 1
    assert window.warning_table.item(0, 0).text() == "分谱不足"
    assert window.warning_table.item(0, 2).text() == "待处理"
    assert "天空之城" in window.warning_table.item(0, 3).text()

    window.close()
    app.processEvents()


def test_warning_state_can_be_marked_from_window(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    db.add_import_item(
        batch_id,
        "0101天空之城.pdf",
        "a",
        "recognized",
        {"instrument_code": "01", "category_code": "01", "title": "天空之城"},
    )
    db.add_import_item(
        batch_id,
        "0201天空之城.pdf",
        "b",
        "recognized",
        {"instrument_code": "02", "category_code": "01", "title": "天空之城"},
    )

    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.scan_completeness()
    window.warning_table.selectRow(0)
    window.mark_selected_warning_accepted()

    assert window.warning_table.item(0, 2).text() == "已确认无须修改"

    window.close()
    app.processEvents()


def test_open_selected_pdf_uses_selected_score_source_path(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    pdf = tmp_path / "0101天空之城.pdf"
    pdf.write_bytes(b"%PDF selected")
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths([pdf])
    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_import_items()
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", lambda path: opened.append(path))

    window.score_table.selectRow(0)
    window.open_selected_pdf()

    assert opened == [pdf]

    window.close()
    app.processEvents()


def test_open_selected_warning_pdf_uses_warning_source_path(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    first = tmp_path / "0101天空之城.pdf"
    second = tmp_path / "0101天空之城复印.pdf"
    first.write_bytes(b"%PDF first")
    second.write_bytes(b"%PDF second")
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths([first, second])
    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_warnings(batch_id)
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", lambda path: opened.append(path))

    window.warning_table.selectRow(0)
    window.open_selected_warning_pdf()

    assert set(opened) == {first, second}

    window.close()
    app.processEvents()


def test_selecting_warning_highlights_related_score_rows(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    first = tmp_path / "0101天空之城.pdf"
    second = tmp_path / "0101天空之城复印.pdf"
    third = tmp_path / "0201天空之城.pdf"
    first.write_bytes(b"%PDF first")
    second.write_bytes(b"%PDF second")
    third.write_bytes(b"%PDF third")
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    db.add_import_item(
        batch_id,
        str(first),
        "a",
        "recognized",
        {"instrument_code": "01", "category_code": "01", "title": "天空之城"},
    )
    db.add_import_item(
        batch_id,
        str(second),
        "b",
        "recognized",
        {"instrument_code": "01", "category_code": "01", "title": "天空之城"},
    )
    db.add_import_item(
        batch_id,
        str(third),
        "c",
        "recognized",
        {"instrument_code": "02", "category_code": "01", "title": "天空之城"},
    )
    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_import_items()
    window.refresh_warnings(batch_id)

    warning_row = next(
        row
        for row in range(window.warning_table.rowCount())
        if window.warning_table.item(row, 0).text() == "同曲同乐器冲突"
    )
    window.warning_table.selectRow(warning_row)

    selected_names = {
        window.score_table.item(item.row(), 1).text()
        for item in window.score_table.selectedItems()
    }
    assert {"0101天空之城.pdf", "0101天空之城复印.pdf"}.issubset(selected_names)

    window.close()
    app.processEvents()


def test_remove_selected_import_items_from_window_keeps_source_pdf(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    first = tmp_path / "0101天空之城.pdf"
    second = tmp_path / "0201天空之城.pdf"
    first.write_bytes(b"%PDF first")
    second.write_bytes(b"%PDF second")
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths([first, second])
    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_import_items()
    monkeypatch.setattr(
        "score_organizer.ui.main_window.QMessageBox.question",
        lambda *args: QMessageBox.StandardButton.Yes,
    )

    window.score_table.selectRow(0)
    window.remove_selected_import_items()

    remaining_names = {Path(item["source_path"]).name for item in db.list_import_items(batch_id)}
    assert remaining_names == {"0201天空之城.pdf"}
    assert first.exists()
    assert second.exists()

    window.close()
    app.processEvents()


def test_song_report_table_updates(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    for instrument in ("01", "02", "03"):
        db.add_import_item(
            batch_id,
            f"{instrument}01天空之城.pdf",
            instrument,
            "recognized",
            {"instrument_code": instrument, "category_code": "01", "title": "天空之城"},
        )

    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_song_reports()

    assert window.report_table.rowCount() == 1
    assert window.report_table.item(0, 1).text() == "天空之城"
    assert window.report_table.item(0, 2).text() == "3"
    assert window.report_table.item(0, 4).text() == "01待分配天空之城（03）"

    window.close()
    app.processEvents()


def test_import_table_shows_preview_name_and_ready_status(tmp_path):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    for instrument in ("01", "02", "03"):
        db.add_import_item(
            batch_id,
            f"{instrument}01天空之城.pdf",
            instrument,
            "recognized",
            {"instrument_code": instrument, "category_code": "01", "title": "天空之城"},
        )

    window = MainWindow(db)
    window.current_batch_id = batch_id
    window.refresh_import_items()

    assert window.score_table.item(0, 3).text() == "0101001天空之城.pdf"
    assert window.score_table.item(0, 4).text() == "0101001"
    assert window.score_table.item(1, 3).text() == "0201001天空之城.pdf"
    assert window.score_table.item(1, 4).text() == "0201001"
    assert window.score_table.item(0, 5).text() == "可归档"

    window.close()
    app.processEvents()


def test_archive_commit_from_window_writes_outputs(tmp_path, monkeypatch):
    monkeypatch.setattr(MainWindow, "show_archive_review", lambda *args, **kwargs: True)
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    source = tmp_path / "source"
    source.mkdir()
    files = []
    for instrument in ("01", "02", "03"):
        path = source / f"{instrument}01天空之城.pdf"
        path.write_bytes(f"%PDF {instrument}".encode("utf-8"))
        files.append(path)
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths(files)
    output_root = tmp_path / "out"
    db.set_setting("output_root", str(output_root))
    window = MainWindow(db)
    window.current_batch_id = batch_id
    monkeypatch.setattr("score_organizer.ui.main_window.QMessageBox.information", lambda *args: None)

    window.commit_current_archive_plan()

    assert (output_root / "按曲目分类" / "01001天空之城（03）").exists()
    assert db.count_songs() == 1
    assert db.count_scores() == 3
    assert window.current_batch_id is None

    window.close()
    app.processEvents()


def test_archive_commit_warns_when_draft_has_no_complete_song(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    pdf = tmp_path / "0101天空之城.pdf"
    pdf.write_bytes(b"%PDF one instrument")
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths([pdf])
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    window.current_batch_id = batch_id
    warnings = []
    monkeypatch.setattr(
        "score_organizer.ui.main_window.QMessageBox.warning",
        lambda *args: warnings.append(args),
    )

    window.commit_current_archive_plan()

    assert db.count_songs() == 0
    assert "当前草稿已经保存" in window.guidance_label.text()
    assert warnings

    window.close()
    app.processEvents()


def test_clear_current_draft_from_window_removes_draft_items(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    pdf = tmp_path / "0101天空之城.pdf"
    pdf.write_bytes(b"%PDF draft")
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    ImportService(db).import_paths([pdf])
    window = MainWindow(db)
    monkeypatch.setattr(
        "score_organizer.ui.main_window.QMessageBox.question",
        lambda *args: QMessageBox.StandardButton.Yes,
    )
    monkeypatch.setattr("score_organizer.ui.main_window.QMessageBox.information", lambda *args: None)

    window.clear_current_draft()

    assert window.current_batch_id is None
    assert window.score_table.rowCount() == 0
    assert window.warning_table.rowCount() == 0
    assert window.report_table.rowCount() == 0
    assert "已清空" in window.import_summary_label.text()
    assert db.latest_draft_batch_id() is None

    window.close()
    app.processEvents()


def test_window_does_not_show_archived_import_history_as_current_draft(tmp_path, monkeypatch):
    monkeypatch.setattr(MainWindow, "show_archive_review", lambda *args, **kwargs: True)
    app = QApplication.instance() or QApplication([])
    db_path = Path(tmp_path) / "ui.sqlite3"
    source = tmp_path / "source"
    source.mkdir()
    files = []
    for instrument in ("01", "02", "03"):
        path = source / f"{instrument}01天空之城.pdf"
        path.write_bytes(f"%PDF {instrument}".encode("utf-8"))
        files.append(path)
    db = Database(db_path)
    db.initialize()
    from score_organizer.services.import_service import ImportService

    batch_id = ImportService(db).import_paths(files)
    db.set_setting("output_root", str(tmp_path / "out"))
    window = MainWindow(db)
    window.current_batch_id = batch_id
    monkeypatch.setattr("score_organizer.ui.main_window.QMessageBox.information", lambda *args: None)
    window.commit_current_archive_plan()
    window.close()

    reopened = MainWindow(Database(db_path))

    assert reopened.current_batch_id is None
    assert reopened.score_table.rowCount() == 0

    reopened.close()
    app.processEvents()
