from score_organizer.services.report_service import ReportService
from score_organizer.storage.database import Database


def _add_item(db, batch_id, filename, instrument, title, digest):
    db.add_import_item(
        batch_id,
        filename,
        digest,
        "recognized",
        {
            "format": "old",
            "instrument_code": instrument,
            "category_code": "01",
            "title": title,
        },
    )


def test_song_report_preview_does_not_allocate_number_or_write_files(tmp_path):
    db_path = tmp_path / "scores.sqlite3"
    db = Database(db_path)
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, str(tmp_path / "0101天空之城.pdf"), "01", "天空之城", "a")
    _add_item(db, batch_id, str(tmp_path / "0201天空之城.pdf"), "02", "天空之城", "b")
    _add_item(db, batch_id, str(tmp_path / "0301天空之城.pdf"), "03", "天空之城", "c")

    reports = ReportService(db).build_song_reports(batch_id)

    assert len(reports) == 1
    report = reports[0]
    assert report.category_code == "01"
    assert report.title == "天空之城"
    assert report.instrument_count == 3
    assert report.target_folder_preview == "01待分配天空之城（03）"
    assert report.blocking_warning_count == 0
    assert report.rows[0].preview_name == "0101待分配天空之城.pdf"
    assert report.rows[1].preview_name == "0201待分配天空之城.pdf"
    assert db.allocate_next_sequence() == "001"
    assert not (tmp_path / "乐谱库输出").exists()


def test_song_report_preview_does_not_mark_missing_instruments_until_scan(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")

    reports = ReportService(db).build_song_reports(batch_id)

    assert reports[0].blocking_warning_count == 0


def test_batch_summary_marks_ready_group_without_warnings(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")
    _add_item(db, batch_id, "0301天空之城.pdf", "03", "天空之城", "c")

    summary = ReportService(db).build_batch_summary(batch_id)

    assert summary[0].status == "可归组"
    assert summary[0].warning_count == 0


def test_batch_summary_marks_conflicting_group_as_needs_handling(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0101天空之城副本.pdf", "01", "天空之城", "b")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "c")
    _add_item(db, batch_id, "0301天空之城.pdf", "03", "天空之城", "d")

    summary = ReportService(db).build_batch_summary(batch_id)

    assert summary[0].status == "待处理"
