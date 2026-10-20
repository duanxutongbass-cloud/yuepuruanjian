import json

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QLineEdit, QMessageBox, QPushButton, QTableWidget

from score_organizer.domain.filename_parser import suggest_title_without_instrument
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.report_service import ReportService
from score_organizer.services.review_service import ReviewService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


@pytest.mark.parametrize("title,name,expected", [
    ("HANDCLAP-小提琴_1", "第一小提琴", "HANDCLAP"),
    ("HANDCLAP-小提琴_2", "第二小提琴", "HANDCLAP"),
    ("HANDCLAP－中提琴", "中提琴", "HANDCLAP"),
    ("HANDCLAP_大提琴", "大提琴", "HANDCLAP"),
    ("HANDCLAP 低音提琴", "低音提琴", "HANDCLAP"),
    ("HANDCLAP（钢琴）", "钢琴", "HANDCLAP"),
    ("HANDCLAP(打击乐)", "打击乐", "HANDCLAP"),
    ("天空之城-第一提琴", "第一小提琴", "天空之城"),
    ("天空之城-第二小提琴", "第二小提琴", "天空之城"),
    ("作品（第二版）-钢琴", "钢琴", "作品（第二版）"),
    ("作品-自定义乐器", "自定义乐器", "作品"),
    ("作品-小提琴_2", "第一小提琴", None),
    ("作品-大提琴", "中提琴", None),
    ("小提琴协奏曲", "第一小提琴", None),
    ("献给钢琴", "钢琴", None),
    ("钢琴", "钢琴", None),
    ("作品-钢琴-第二版", "钢琴", None),
    ("作品（钢琴)", "钢琴", None),
    ("作品-小提琴_3", "第一小提琴", None),
])
def test_suffix_suggestion_is_bounded_to_matching_instrument(title, name, expected):
    assert suggest_title_without_instrument(title, name) == expected


def suffix_fixture(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    root = tmp_path / "输出"
    db.set_setting("output_root", str(root))
    suffixes = ("小提琴_1", "小提琴_2", "中提琴", "大提琴", "低音提琴", "钢琴", "打击乐")
    sources = []
    for index, suffix in enumerate(suffixes, 1):
        path = tmp_path / f"{index:02d}08 HANDCLAP-{suffix}.pdf"
        path.write_bytes(f"%PDF original {index}".encode())
        sources.append(path)
    batch = ImportService(db).import_paths(sources)
    ids = [int(item["id"]) for item in db.list_import_items(batch)]
    return db, root, batch, ids, sources


def test_seven_suffixes_make_one_candidate_without_silent_title_changes(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    before = [dict(item) for item in db.list_import_items(batch)]
    warnings = GroupingService(db).refresh_warnings(batch)
    assert len(warnings) == 1 and warnings[0]["code"] == "instrument_title_suffix"
    details = warnings[0]["details"]
    assert details["suggested_title"] == "HANDCLAP"
    assert set(details["source_paths"]) == {str(path) for path in sources}
    assert set(details["item_ids"]) == set(ids)
    assert len(details["original_titles"]) == 7
    assert ArchiveService(db, root).build_plan(batch) == []
    assert all(report.blocking_warning_count for report in ReportService(db).build_song_reports(batch))
    assert [dict(item) for item in db.list_import_items(batch)] == before
    assert db.count_songs() == 0 and not root.exists()


def test_confirmed_common_title_archives_once_with_seven_distinct_instruments(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    original = {path: sha256_file(path) for path in sources}
    correction = CorrectionService(db)
    correction.apply(correction.build_plan(batch, ids, "HANDCLAP"))
    assert not GroupingService(db).refresh_warnings(batch)
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch, ids)
    assert len(plans) == 1 and plans[0].folder_name == "08001HANDCLAP（07）"
    assert len(plans[0].files) == 7
    archive.commit_plan(batch, plans)
    assert db.count_songs() == 1 and db.count_scores() == 7
    assert db.allocate_next_sequence() == "002"
    for file in plans[0].files:
        assert file.output_name == f"{file.instrument_code}08001HANDCLAP.pdf"
        assert sha256_file(file.by_instrument_path) == sha256_file(file.by_song_path) == original[file.source_path]
    assert {path: sha256_file(path) for path in sources} == original


def test_matching_plain_title_joins_candidate_and_other_categories_versions_stay_separate(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    paths = []
    for name in ("0108 HANDCLAP.pdf", "0102 HANDCLAP-小提琴_1.pdf", "0108 HANDCLAP（第二版）-小提琴_1.pdf"):
        path = tmp_path / name
        path.write_bytes(name.encode())
        paths.append(path)
    ImportService(db).import_paths(paths, batch)
    candidates = [warning["details"] for warning in GroupingService(db).refresh_warnings(batch)
                  if warning["code"] == "instrument_title_suffix"]
    assert len(candidates) == 3
    main = next(candidate for candidate in candidates if candidate["category_code"] == "08" and candidate["title"] == "HANDCLAP")
    assert set(main["source_paths"]) == {str(path) for path in [*sources, paths[0]]}
    second = next(candidate for candidate in candidates if candidate["title"] == "HANDCLAP（第二版）")
    assert second["source_paths"] == [str(paths[2])]
    assert [str(paths[1])] == next(candidate["source_paths"] for candidate in candidates if candidate["category_code"] == "02")


def test_corrected_suffixes_do_not_bypass_same_instrument_duplicate_or_number_conflicts(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    extra = tmp_path / "额外"
    extra.mkdir()
    duplicate = extra / sources[0].name
    duplicate.write_bytes(sources[0].read_bytes())
    ImportService(db).import_paths([duplicate], batch)
    all_ids = [int(item["id"]) for item in db.list_import_items(batch)]
    correction = CorrectionService(db)
    correction.apply(correction.build_plan(batch, all_ids, "HANDCLAP"))
    warnings = GroupingService(db).refresh_warnings(batch)
    assert {warning["code"] for warning in warnings} == {"duplicate_file", "same_instrument_conflict"}
    assert ArchiveService(db, root).build_plan(batch) == []
    db.remove_import_items_from_draft(batch, [all_ids[-1]])
    with db.session() as connection:
        fields = json.loads(db.get_import_item(ids[0])["parsed_fields"])
        fields["seq"] = "001"
        connection.execute("UPDATE import_item SET parsed_fields=? WHERE id=?", (json.dumps(fields), ids[0]))
        fields = json.loads(db.get_import_item(ids[1])["parsed_fields"])
        fields["seq"] = "002"
        connection.execute("UPDATE import_item SET parsed_fields=? WHERE id=?", (json.dumps(fields), ids[1]))
    with pytest.raises(ValueError, match="冲突"):
        ArchiveService(db, root).build_plan(batch)
    assert not root.exists() and db.count_songs() == 0


def test_suffix_confirmation_preserves_existing_number_when_supplementing(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    original_parts = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}08007HANDCLAP.pdf"
        path.write_bytes(f"%PDF library {code}".encode())
        original_parts.append(path)
    original_batch = ImportService(db).import_paths(original_parts)
    archive = ArchiveService(db, root)
    archive.commit_plan(original_batch, archive.build_plan(original_batch))
    GroupingService(db).refresh_warnings(batch)
    warning = next(row for row in db.list_warnings(batch) if row["code"] == "instrument_title_suffix")
    assert {score["seq"] for score in json.loads(warning["details"])["existing"]} == {"007"}
    # Keep only genuinely new instruments before supplementing the existing song.
    db.remove_import_items_from_draft(batch, ids[:3])
    correction = CorrectionService(db)
    correction.apply(correction.build_plan(batch, ids[3:], "HANDCLAP"))
    plans = archive.build_plan(batch, ids[3:])
    assert len(plans) == 1 and plans[0].seq == "007" and len(plans[0].files) == 4
    archive.commit_plan(batch, plans)
    assert db.count_songs() == 1 and db.count_scores() == 7
    assert db.allocate_next_sequence() == "001"
    for file in plans[0].files:
        assert sha256_file(file.by_instrument_path) == sha256_file(file.by_song_path) == sha256_file(file.source_path)


def test_pending_review_keeps_instrument_suffix_reason_and_originals(tmp_path):
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    plan = ReviewService(db, root).build_plan(batch)
    assert len(plan) == 7
    assert all("乐器后缀待确认" in item["reasons"] for item in plan)
    assert {item["parsed_fields"]["title"] for item in plan} == {f"HANDCLAP-{suffix}" for suffix in ("小提琴_1", "小提琴_2", "中提琴", "大提琴", "低音提琴", "钢琴", "打击乐")}


def test_warning_opens_all_seven_with_suggested_title_then_real_preview_archive(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    original = {path: sha256_file(path) for path in sources}
    window = MainWindow(db)
    window.show()
    monkeypatch.setattr(QMessageBox, "information", lambda *args: None)
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    app.processEvents()
    assert window.warning_table.rowCount() == 1
    assert window.warning_table.item(0, 0).text() == "乐器后缀需统一曲名"
    def confirm_archive():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 7
        assert {table.item(row, 0).text() for row in range(7)} == {"08001HANDCLAP（07）"}
        dialog.grab().save(str(tmp_path / "suffix-archive.png"))
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "确认归档").click()
    def confirm_correction():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认归档"
        table = dialog.findChild(QTableWidget, "archive_correction_changes")
        assert table.rowCount() == 7
        assert table.item(0, 3).text() == "HANDCLAP-小提琴_1 → HANDCLAP"
        dialog.grab().save(str(tmp_path / "suffix-preview.png"))
        confirm_archive()
    def correct():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "分谱校对与入库"
        table = dialog.findChild(QTableWidget, "correction_parts")
        assert table.rowCount() == 7
        table.selectAll()
        assert dialog.findChild(QLineEdit, "correction_title").text() == "HANDCLAP"
        next(button for button in dialog.findChildren(QPushButton) if button.text() == "打开选中PDF").click()
        assert set(opened) == set(sources)
        dialog.grab().save(str(tmp_path / "suffix-editor.png"))
        QTimer.singleShot(50, confirm_correction)
        dialog.findChild(QPushButton, "correction_archive").click()
    window.warning_table.selectRow(0)
    QTimer.singleShot(50, correct)
    window.findChild(QPushButton, "process_selected_warning").click()
    assert db.count_songs() == 1 and db.count_scores() == 7
    assert window.current_batch_id is None and window.warning_table.rowCount() == 0
    assert {path: sha256_file(path) for path in sources} == original
    assert len(list((root / "按曲目分类").rglob("*.pdf"))) == 7
    assert len(list((root / "按乐器分类").rglob("*.pdf"))) == 7
    window.close()
    app.processEvents()


def test_cancel_suggested_correction_does_not_change_titles_or_create_songs(tmp_path):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = suffix_fixture(tmp_path)
    before = [dict(item) for item in db.list_import_items(batch)]
    window = MainWindow(db)
    def cancel():
        dialog = app.activeModalWidget()
        dialog.findChild(QTableWidget, "correction_parts").selectAll()
        assert dialog.findChild(QLineEdit, "correction_title").text() == "HANDCLAP"
        dialog.reject()
    QTimer.singleShot(50, cancel)
    window.show_correction_dialog(ids)
    assert [dict(item) for item in db.list_import_items(batch)] == before
    assert db.count_songs() == 0 and not root.exists()
    window.close()
    app.processEvents()
