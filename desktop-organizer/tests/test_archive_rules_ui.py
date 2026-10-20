from PySide6.QtCore import QTimer
from PySide6.QtGui import QAction
from PySide6.QtWidgets import QApplication, QComboBox, QDialog, QMessageBox, QPushButton, QSpinBox, QTableWidget

from score_organizer.ui.archive_rules_dialog import ArchiveRulesDialog
from score_organizer.ui.main_window import MainWindow
from test_archive_rules import setup_library, import_parts


def test_native_rules_edit_save_backup_and_cancel(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _ = setup_library(tmp_path)
    dialog = ArchiveRulesDialog(db)
    dialog.show()
    app.processEvents()
    assert dialog.findChild(QComboBox, "archive_mode_07").currentData() == "dual"
    assert dialog.findChild(QComboBox, "archive_mode_08").currentData() == "instrument_only"
    assert dialog.findChild(QComboBox, "archive_mode_09").currentData() == "instrument_only"
    assert dialog.grab().save(str(tmp_path / "rules-native.png"))
    dialog.findChild(QComboBox, "archive_mode_07").setCurrentIndex(1)
    dialog.findChild(QSpinBox, "archive_minimum_02").setValue(2)
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.No)
    dialog.save_rules()
    assert db.instrument_archive_modes()["07"] == "dual"
    assert not (tmp_path / "规则备份").exists()
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    dialog.findChild(QPushButton, "save_archive_rules").click()
    assert dialog.result() == QDialog.Accepted
    assert db.instrument_archive_modes()["07"] == "instrument_only"
    assert db.minimum_instruments("02") == 2
    assert len(list((tmp_path / "规则备份").glob("*.sqlite3"))) == 1
    dialog.close()


def test_native_standalone_draft_status_preview_and_library_edit_preview(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "08"])
    db.set_setting("output_root", str(service.output_root))
    window = MainWindow(db)
    window.show()
    app.processEvents()
    assert window.findChild(QAction, "archive_rules") is not None
    statuses = {window.score_table.item(i, 0).text(): window.score_table.item(i, 5).text() for i in range(2)}
    assert statuses == {"01": "待补齐", "08": "可单份归档"}
    plans = service.build_plan(batch)
    errors = []
    def check_preview():
        dialog = app.activeModalWidget()
        try:
            table = dialog.findChild(QTableWidget)
            assert table.rowCount() == 1
            assert "仅按乐器分类" in table.item(0, 0).text()
            assert dialog.grab().save(str(tmp_path / "standalone-preview-native.png"))
            dialog.accept()
        except Exception as exc:
            errors.append(exc)
            dialog.reject()
    QTimer.singleShot(100, check_preview)
    assert window.show_archive_review(plans, confirm=True)
    assert not errors
    service.commit_plan(batch, plans)
    from score_organizer.services.catalog_service import CatalogService
    edit = CatalogService(db, service.output_root).build_plan(db.list_archived_scores()[0]["song_id"], "01", "修正曲名")
    def check_edit():
        dialog = app.activeModalWidget()
        try:
            table = dialog.findChild(QTableWidget)
            assert table.rowCount() == 1 and table.item(0, 1).text() == "按乐器分类"
            assert dialog.grab().save(str(tmp_path / "standalone-edit-native.png"))
            dialog.accept()
        except Exception as exc:
            errors.append(exc)
            dialog.reject()
    QTimer.singleShot(100, check_edit)
    assert window.show_catalog_edit_preview(edit)
    assert not errors
    window.close()


def test_native_rule_entry_refreshes_draft_and_real_single_archive(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, service = setup_library(tmp_path)
    batch, paths = import_parts(db, tmp_path, ["07"])
    db.set_setting("output_root", str(service.output_root))
    window = MainWindow(db)
    window.show()
    app.processEvents()
    assert window.score_table.item(0, 5).text() == "待补齐"
    monkeypatch.setattr(QMessageBox, "question", lambda *args: QMessageBox.Yes)
    errors = []
    def change_rule():
        dialog = app.activeModalWidget()
        try:
            dialog.findChild(QComboBox, "archive_mode_07").setCurrentIndex(1)
            dialog.findChild(QPushButton, "save_archive_rules").click()
        except Exception as exc:
            errors.append(exc)
            dialog.reject()
    QTimer.singleShot(100, change_rule)
    window.edit_archive_rules()
    assert not errors
    assert window.score_table.item(0, 5).text() == "可单份归档"
    messages = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: messages.append(args[2]))
    def confirm():
        dialog = app.activeModalWidget()
        try:
            assert "确认归档" == dialog.windowTitle()
            assert dialog.findChild(QTableWidget).rowCount() == 1
            dialog.accept()
        except Exception as exc:
            errors.append(exc)
            dialog.reject()
    QTimer.singleShot(100, confirm)
    assert window._commit_archive()
    assert not errors and window.current_batch_id is None and window.score_table.rowCount() == 0
    assert db.count_scores() == 1 and len(list(service.output_root.rglob("*.pdf"))) == 1
    assert not (service.output_root / "按曲目分类").exists()
    assert not messages
    assert "仅保存在按乐器分类" in window.guidance_label.text()
    assert paths[0].read_bytes() == service.score_paths(db.list_archived_scores()[0])[0].read_bytes()
    window.close()


def test_native_incomplete_normal_conflicts_do_not_mark_ready_total_blocked(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, _ = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "08"])
    alternate = tmp_path / "alternate"
    alternate.mkdir()
    import_parts(db, alternate, ["01"], batch=batch)
    window = MainWindow(db)
    window.show()
    app.processEvents()
    for row in range(3):
        code = window.score_table.item(row, 0).text()
        assert window.score_table.item(row, 5).text() == ("可单份归档" if code == "08" else "待处理")
    window.close()
