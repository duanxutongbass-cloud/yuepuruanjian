from pathlib import Path
import time

from PySide6.QtWidgets import QApplication, QFileDialog, QDialog

from score_organizer.ui.library_transfer_dialog import LibraryTransferDialog
from score_organizer.ui.main_window import MainWindow
from score_organizer.storage.database import Database
from test_library_upgrade_snapshot import make_library


def app_instance():
    return QApplication.instance() or QApplication([])


def finish(app, dialog):
    deadline = time.monotonic() + 30
    while dialog.busy() and time.monotonic() < deadline:
        app.processEvents()
        time.sleep(.01)
    assert not dialog.busy()
    app.processEvents()


def test_menu_has_both_transfer_actions_and_export_defaults_to_desktop(tmp_path):
    app = app_instance()
    db = Database(tmp_path / 'library.sqlite3')
    db.initialize()
    window = MainWindow(db)
    dialog = LibraryTransferDialog(db)
    try:
        assert window.menu_actions['export_whole_library'].text() == '导出整库迁移包…'
        assert window.menu_actions['import_whole_library'].text() == '从整库迁移包恢复…'
        assert window.menu_actions['latest_migration_report'].text() == '查看最近迁移报告…'
        assert '0.1.7' in window.windowTitle()
        assert '曲谱库整库迁移-' in Path(dialog.path.text()).name
        assert '桌面' in dialog.summary.text()
    finally:
        dialog.close()
        window.close()


def test_background_export_then_inspect_enables_restore_without_blocking_return(tmp_path):
    app = app_instance()
    db, root, _ = make_library(tmp_path)
    export = LibraryTransferDialog(db)
    importer = LibraryTransferDialog(db, importing=True)
    try:
        package = tmp_path / 'gui-transfer.zip'
        export.path.setText(str(package))
        export.export()
        assert export.busy() and export.close_button.isEnabled()
        finish(app, export)
        assert package.is_file() and '导出成功并已回读校验' in export.status.text()
        importer.path.setText(str(package))
        assert not importer.action.isEnabled()
        importer.inspect()
        finish(app, importer)
        assert importer.action.isEnabled() and '3份正式分谱' in importer.summary.text()
        importer.path.setText(str(tmp_path / 'different.zip'))
        assert not importer.action.isEnabled() and importer.preview is None
    finally:
        export.close()
        importer.close()


def test_cancel_background_operation_returns_after_worker_safe_exit(tmp_path, monkeypatch):
    app = app_instance()
    db = Database(tmp_path / 'empty.sqlite3')
    db.initialize()
    dialog = LibraryTransferDialog(db)
    def long_export(_db, _path, *, progress):
        for _ in range(1000):
            time.sleep(.002)
            progress('copying isolated fixture')
        raise AssertionError('cancel was not delivered')
    monkeypatch.setattr('score_organizer.ui.library_transfer_dialog.export_library', long_export)
    dialog.export()
    dialog.reject()
    finish(app, dialog)
    assert dialog.result() == QDialog.DialogCode.Rejected
    assert '取消' in dialog.status.text()


def test_full_gui_restore_checks_preview_confirms_and_switches_current_library(tmp_path, monkeypatch):
    from score_organizer.services.library_transfer import export_library
    from score_organizer.main import startup_database
    from PySide6.QtWidgets import QMessageBox
    app = app_instance()
    old, _, _ = make_library(tmp_path)
    package = tmp_path / 'gui.zip'
    export_library(old, package)
    current = Database(tmp_path / 'new-computer-empty.sqlite3')
    current.initialize()
    monkeypatch.setenv('SCORE_ORGANIZER_EXPANDED_PROFILE', str(tmp_path / 'profile'))
    dialog = LibraryTransferDialog(current, importing=True)
    dialog.path.setText(str(package))
    dialog.inspect()
    finish(app, dialog)
    monkeypatch.setattr(QFileDialog, 'getExistingDirectory', lambda *_args: str(tmp_path))
    monkeypatch.setattr(QMessageBox, 'question', lambda *_args: QMessageBox.Yes)
    # An environmental guard must fail this test, never leave an unattended
    # offscreen modal dialog waiting forever.
    warnings = []
    monkeypatch.setattr(QMessageBox, 'warning', lambda *_args: warnings.append(_args))
    dialog.restore()
    finish(app, dialog)
    assert dialog.result_database is not None and dialog.result_database.count_scores() == 3
    # Exercise the actual MainWindow switch, including startup persistence.
    monkeypatch.setattr(LibraryTransferDialog, 'exec', lambda self: QDialog.DialogCode.Accepted)
    original_init = LibraryTransferDialog.__init__
    def restored_dialog(self, *args, **kwargs):
        original_init(self, *args, **kwargs)
        self.result_database = dialog.result_database
    monkeypatch.setattr(LibraryTransferDialog, '__init__', restored_dialog)
    window = MainWindow(current)
    try:
        window.import_whole_library()
        assert not warnings, warnings
        transferred = window._transferred_window
        assert transferred.db.path == startup_database().path
        assert transferred.db.count_scores() == 3
        assert current.count_scores() == 0
        transferred.close()
        transferred._runtime_lease.close()
    finally:
        window.close()
