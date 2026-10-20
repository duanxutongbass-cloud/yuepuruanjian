import json

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QComboBox, QDialogButtonBox, QLabel, QLineEdit, QPlainTextEdit, QPushButton, QTableWidget, QMessageBox

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.report_service import ReportService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from test_correction_service import add_existing
from test_correction_assignment import native_callback


def setup(tmp_path, title="天空之城"):
    db = Database(tmp_path / "alignment.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    target = add_existing(db, tmp_path, "007", title)
    return db, root, target


def import_file(db, tmp_path, name, content=b"%PDF new"):
    source = tmp_path / name
    source.write_bytes(content)
    batch = ImportService(db).import_paths([source])
    item_id = int(db.list_import_items(batch)[0]["id"])
    return source, batch, item_id


def names(warnings):
    return {row["code"] for row in warnings if row["state"] == "待处理"}


def test_library_typo_candidate_visible_without_auto_rename(tmp_path):
    db, root, target = setup(tmp_path)
    source, batch, item = import_file(db, tmp_path, "0401天空之成.pdf")
    before = dict(db.get_import_item(item))
    warnings = GroupingService(db).refresh_warnings(batch)
    hint = next(row for row in warnings if row["code"] == "similar_existing_title_candidate")
    assert hint["severity"] == "warning"
    assert hint["details"]["right_title"] == "天空之城"
    assert {row["song_id"] for row in hint["details"]["existing"]} == {target}
    assert dict(db.get_import_item(item)) == before


@pytest.mark.parametrize("title,filename", [("HANDCLAP-钢琴", "0601HANDCLAP-钢琴.pdf"),
                                           ("天空之城（第二版）", "0401天空之城 (第二版).pdf")])
def test_exact_library_identity_stops_suffix_or_version_name_warnings_and_supplements(tmp_path, title, filename):
    db, root, target = setup(tmp_path, title)
    source, batch, item = import_file(db, tmp_path, filename)
    original = sha256_file(source)
    before = names(GroupingService(db).refresh_warnings(batch))
    assert before.intersection({"instrument_title_suffix", "version_candidate"})
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [item], target_song_id=target))
    for _ in range(3):
        after = names(GroupingService(db).refresh_warnings(batch))
        assert not after.intersection({"instrument_title_suffix", "version_candidate", "similar_existing_title_candidate", "whitespace_title_conflict"})
    report = ReportService(db).build_song_reports(batch)[0]
    assert report.blocking_warning_count == report.warning_count == 0
    archive = ArchiveService(db, root)
    plan = archive.build_plan(batch, [item])
    assert len(plan) == 1 and plan[0].seq == "007" and plan[0].title == title
    archive.commit_plan(batch, plan)
    assert db.count_songs() == 1 and db.count_scores() == 4
    assert sha256_file(source) == original
    assert all(sha256_file(file.by_song_path) == sha256_file(file.by_instrument_path) == original for file in plan[0].files)


@pytest.mark.parametrize("change", ["title", "category", "seq", "deleted_target", "ambiguous_target"])
def test_invalid_or_ambiguous_target_marker_does_not_suppress_name_checks(tmp_path, change):
    db, root, target = setup(tmp_path, "HANDCLAP-钢琴")
    source, batch, item = import_file(db, tmp_path, "0601HANDCLAP-钢琴.pdf")
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [item], target_song_id=target))
    field = json.loads(db.get_import_item(item)["parsed_fields"])
    if change == "title":
        field["title"] = "HANDCLAP新增-钢琴"
    elif change == "category":
        field["category_code"] = "02"
    elif change == "seq":
        field["seq"] = "008"
    elif change == "deleted_target":
        with db.session() as connection:
            connection.execute("UPDATE song SET status='merged' WHERE id=?", (target,))
    else:
        other = db.create_song("01", "008", "HANDCLAP-钢琴", status="archived")
        db.add_score(other, "01", "old.pdf", str(source), "other", "0101008HANDCLAP-钢琴.pdf")
    with db.session() as connection:
        connection.execute("UPDATE import_item SET parsed_fields=? WHERE id=?", (json.dumps(field), item))
    assert "instrument_title_suffix" in names(GroupingService(db).refresh_warnings(batch))


def test_confirmed_library_side_not_blocked_by_other_unresolved_whitespace_name(tmp_path):
    db, root, target = setup(tmp_path)
    one, batch, selected = import_file(db, tmp_path, "0401天空之城.pdf")
    other = tmp_path / "0501天空 之城.pdf"
    other.write_bytes(b"%PDF unresolved")
    ImportService(db).import_paths([other], batch)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [selected], target_song_id=target))
    warnings = GroupingService(db).refresh_warnings(batch)
    for row in warnings:
        if row["code"] == "whitespace_title_conflict":
            assert str(one) not in row["details"]["source_paths"]
            assert str(other) in row["details"]["source_paths"]
    reports = ReportService(db).build_song_reports(batch)
    confirmed = next(row for row in reports if row.title == "天空之城")
    pending = next(row for row in reports if row.title == "天空 之城")
    assert confirmed.blocking_warning_count == 0 and pending.blocking_warning_count > 0
    assert len(ArchiveService(db, root).build_plan(batch, [selected])) == 1


def test_two_confirmed_distinct_library_names_are_not_repeatedly_questioned_or_merged(tmp_path):
    db, root, target = setup(tmp_path, "千与千寻《再度》")
    other = add_existing(db, tmp_path, "008", "千与千寻《与你+再度》")
    one, batch, first = import_file(db, tmp_path, "0401千与千寻《再度》.pdf")
    second_path = tmp_path / "0501千与千寻《与你+再度》.pdf"
    second_path.write_bytes(b"%PDF different song")
    ImportService(db).import_paths([second_path], batch)
    second = int(db.list_import_items(batch)[1]["id"])
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [first], target_song_id=target))
    service.apply(service.build_plan(batch, [second], target_song_id=other))
    assert "similar_title_candidate" not in names(GroupingService(db).refresh_warnings(batch))
    plans = ArchiveService(db, root).build_plan(batch)
    assert {row.seq for row in plans} == {"007", "008"}
    assert db.count_songs() == 2


def test_name_confirmation_never_bypasses_duplicate_or_instrument_conflicts(tmp_path):
    db, root, target = setup(tmp_path)
    source, batch, item = import_file(db, tmp_path, "0101天空之成.pdf", (tmp_path / "0101007天空之城.pdf").read_bytes())
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [item], target_song_id=target))
    warnings = names(GroupingService(db).refresh_warnings(batch))
    assert {"duplicate_existing_file", "existing_instrument_conflict"}.issubset(warnings)
    assert not ArchiveService(db, root).build_plan(batch, [item])
    assert db.count_scores() == 3 and db.get_import_item(item) is not None


def test_native_library_name_button_unifies_selected_parts_to_exact_catalog_then_reopen(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, target = setup(tmp_path)
    one, batch, first = import_file(db, tmp_path, "0402天空之成.pdf")
    second_path = tmp_path / "0502天空 之城.pdf"
    second_path.write_bytes(b"%PDF second")
    ImportService(db).import_paths([second_path], batch)
    ids = [int(row["id"]) for row in db.list_import_items(batch)]
    window = MainWindow(db)
    window.show()
    app.processEvents()
    errors = []
    previews = []
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: previews.append(plan) or True)
    def choose():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "统一为库内名称"
        table = dialog.findChild(QTableWidget, "correction_candidates")
        assert table.rowCount() == 1 and table.item(0, 0).text() == "已入库"
        table.selectRow(0)
        dialog.findChild(QDialogButtonBox).button(QDialogButtonBox.Ok).click()
    def operate():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        QTimer.singleShot(50, native_callback(app, choose, errors))
        dialog.findChild(QPushButton, "correction_use_library_name").click()
        assert dialog.findChild(QLineEdit, "correction_title").text() == "天空之城"
        dialog.findChild(QPushButton, "correction_save").click()
        assert len(previews[0].rows) == 2
        assert "已采用库内名称：007" in dialog.findChild(QLabel, "correction_state").text()
        app.processEvents()
        assert dialog.findChild(QLabel, "correction_state").height() >= dialog.findChild(QLabel, "correction_state").heightForWidth(dialog.width() - 40)
        dialog.grab().save(str(tmp_path / "library-name-editor.png"))
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog(ids)
    assert not errors
    for item in db.list_import_items(batch):
        field = json.loads(item["parsed_fields"])
        assert (field["title"], field["category_code"], field["seq"], field["target_song_id"]) == ("天空之城", "01", "007", target)
    def reopened():
        dialog = app.activeModalWidget()
        assert dialog.findChild(QComboBox, "correction_destination").currentData() == target
        assert dialog.findChild(QLineEdit, "correction_title").isReadOnly()
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, reopened, errors))
    window.show_correction_dialog(ids)
    assert not errors and db.count_songs() == 1 and db.count_scores() == 3
    window.close()
    app.processEvents()


def test_incomplete_reason_reports_true_instrument_count_and_file_not_info_warning(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, root, target = setup(tmp_path)
    source, batch, item = import_file(db, tmp_path, "0401天空之成.pdf")
    window = MainWindow(db)
    message = window.archive_blocking_details([item])
    assert "目前只有1种" in message and source.name in message
    assert "库内候选标准名称：007 天空之城" in message
    assert "疑似同曲不同名" not in message and "待确认归属" not in message
    window.close()
    app.processEvents()


def test_native_name_resolved_but_duplicate_reason_persists_with_full_details(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, target = setup(tmp_path)
    source, batch, item = import_file(db, tmp_path, "0101天空之成.pdf", (tmp_path / "0101007天空之城.pdf").read_bytes())
    window = MainWindow(db)
    window.show()
    app.processEvents()
    monkeypatch.setattr(window, "show_correction_preview", lambda plan: True)
    monkeypatch.setattr(window, "resolve_archived_duplicates", lambda *args, **kwargs: False)
    errors = []
    def read_details():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "未入库原因"
        text = dialog.findChild(QPlainTextEdit, "archive_failure_text").toPlainText()
        assert "已采用库内名称：007" in text and source.name in text
        assert "内容重复" in text and "名称统一后仍需确认" in text
        dialog.grab().save(str(tmp_path / "library-name-failure-details.png"))
        dialog.reject()
    def operate():
        dialog = app.activeModalWidget()
        dialog.findChild(QComboBox, "correction_destination").setCurrentIndex(dialog.findChild(QComboBox, "correction_destination").findData(target))
        dialog.findChild(QPushButton, "correction_archive").click()
        state = dialog.findChild(QLabel, "correction_state")
        assert "已采用库内名称：007" in state.text()
        assert "内容重复" in state.toolTip() and source.name in state.toolTip()
        details = dialog.findChild(QPushButton, "correction_failure_details")
        assert not details.isHidden()
        QTimer.singleShot(50, native_callback(app, read_details, errors))
        details.click()
        dialog.reject()
    QTimer.singleShot(50, native_callback(app, operate, errors))
    window.show_correction_dialog([item])
    assert not errors
    assert db.get_import_item(item) is not None and db.count_scores() == 3
    assert source.exists()
    window.close()
    app.processEvents()
