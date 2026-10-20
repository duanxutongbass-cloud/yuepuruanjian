import pytest
from PySide6.QtWidgets import QApplication, QCheckBox, QLineEdit, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.import_service import sha256_file
from score_organizer.ui.main_window import MainWindow
from test_correction_service import correction_fixture
from test_menu_workflows import schedule


def database_snapshot(db):
    with db.session() as connection:
        return tuple(connection.iterdump())


def test_corrected_archive_preview_is_readonly_and_matches_real_commit(tmp_path):
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "天空之城")
    before = database_snapshot(db)
    preview = service.preview_archive(plan, root)
    assert len(preview) == 1 and preview[0].seq == "001"
    assert len(preview[0].files) == 3
    assert database_snapshot(db) == before
    assert service.preview_archive(plan, root) == preview
    assert database_snapshot(db) == before
    assert not root.exists()
    service.apply(plan)
    archive = ArchiveService(db, root)
    assert archive.build_plan(batch, ids) == preview
    archive.commit_plan(batch, preview)
    for file in preview[0].files:
        assert all(sha256_file(path) == file.source_sha256 for path in file.targets)
        assert sha256_file(file.source_path) == file.source_sha256


def test_preview_failure_rolls_back_correction_warnings_and_audit(tmp_path, monkeypatch):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "天空之城")
    before = database_snapshot(db)
    def fail(*args, **kwargs):
        raise ValueError("injected preview failure")
    monkeypatch.setattr(ArchiveService, "build_plan", fail)
    with pytest.raises(ValueError, match="injected"):
        service.preview_archive(plan, root)
    assert database_snapshot(db) == before
    assert db._transaction_connection is None
    assert not root.exists()


@pytest.mark.parametrize("force,cancel", [(False, False), (True, False), (False, True), (True, True)])
def test_native_one_final_confirmation_includes_changes_and_no_success_popup(tmp_path, monkeypatch, force, cancel):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    before = database_snapshot(db)
    hashes = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    errors, reviews, notices = [], [], []
    def unexpected(*args, **kwargs):
        raise AssertionError("extra correction confirmation")
    monkeypatch.setattr(window, "show_correction_preview", unexpected)
    monkeypatch.setattr(QMessageBox, "information", lambda *args: notices.append(args))
    def preview():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        reviews.append(1)
        assert database_snapshot(db) == before
        changes = dialog.findChild(QTableWidget, "archive_correction_changes")
        assert changes.rowCount() == 3
        assert changes.item(0, 3).text() == "天空之成 → 天空之城"
        files = dialog.findChild(QTableWidget)
        assert files.rowCount() == 3 and files.item(0, 3).text() == "0101001天空之城.pdf"
        assert dialog.grab().save(str(tmp_path / "single-final-preview.png"))
        if cancel:
            dialog.reject()
        else:
            consent = dialog.findChild(QCheckBox, "force_archive_confirm")
            if force:
                assert consent is not None and not consent.isChecked()
                consent.setChecked(True)
            else:
                assert consent is None
            next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档").click()
    def editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城")
        schedule(app, preview, errors)
        dialog.findChild(QPushButton, "correction_archive").click()
        if cancel or errors:
            dialog.reject()
    try:
        schedule(app, editor, errors)
        window.show_correction_dialog(ids, force=force)
        assert not errors and reviews == [1] and notices == []
        assert {path: sha256_file(path) for path in sources} == hashes
        if cancel:
            assert database_snapshot(db) == before
            assert not root.exists() and db.count_songs() == 0
        else:
            assert db.count_scores() == 3 and not db.list_import_items(batch)
            assert "已入库 1 首、3 份" in window.guidance_label.text()
            assert "已入库" in window.statusBar().currentMessage()
            for score in db.list_archived_scores():
                assert all(sha256_file(path) == score["source_sha256"] for path in ArchiveService(db, root).score_paths(score))
    finally:
        window.close()


def test_changed_output_after_approval_cannot_commit_unreviewed_plan(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    window = MainWindow(db)
    errors, reviews = [], []
    changed_root = tmp_path / "changed-output"
    original_apply = CorrectionService.apply
    def apply(self, plan):
        original_apply(self, plan)
        db.set_setting("output_root", str(changed_root))
    monkeypatch.setattr(CorrectionService, "apply", apply)
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: None)
    def approve(plans, **kwargs):
        reviews.append(1)
        return True
    monkeypatch.setattr(window, "show_archive_review", approve)
    def editor():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        dialog.findChild(QLineEdit, "correction_title").setText("天空之城")
        dialog.findChild(QPushButton, "correction_archive").click()
        assert "方案已变化" in window.last_archive_failure
        dialog.reject()
    try:
        schedule(app, editor, errors)
        window.show_correction_dialog(ids)
        assert not errors and reviews == [1]
        assert db.count_scores() == 0 and len(db.list_import_items(batch)) == 3
        assert not root.exists() and not changed_root.exists()
        assert all(path.is_file() for path in sources)
    finally:
        window.close()
