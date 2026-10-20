import json
import pytest

from score_organizer.services.grouping_service import GroupingService
from score_organizer.storage.database import Database


def _add_item(db, batch_id, filename, instrument, title, digest):
    return db.add_import_item(
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


def test_group_batch_by_category_and_title_counts_distinct_instruments(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")
    _add_item(db, batch_id, "0301天空之城.pdf", "03", "天空之城", "c")

    groups = GroupingService(db).group_batch(batch_id)

    assert len(groups) == 1
    assert groups[0].category_code == "01"
    assert groups[0].title == "天空之城"
    assert groups[0].instrument_codes == ("01", "02", "03")


def test_refresh_warnings_detects_same_instrument_and_duplicate_file_without_completeness_scan(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "same")
    _add_item(db, batch_id, "0101天空之城副本.pdf", "01", "天空之城", "same")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "other")

    warnings = GroupingService(db).refresh_warnings(batch_id)
    persisted = db.list_warnings(batch_id)

    assert {warning["code"] for warning in warnings} == {
        "same_instrument_conflict",
        "duplicate_file",
    }
    duplicate = next(warning for warning in warnings if warning["code"] == "duplicate_file")
    assert duplicate["severity"] == "blocking"
    assert len(persisted) == 2


def test_completeness_scan_detects_insufficient_instruments(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")

    warnings = GroupingService(db).refresh_warnings(batch_id, scan_completeness=True)

    assert any(warning["code"] == "insufficient_instruments" for warning in warnings)
    details = [warning["details"] for warning in warnings]
    assert any(detail.get("instrument_count") == 2 for detail in details)


def test_refresh_warnings_detects_similar_title_candidate_without_merging(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")
    _add_item(db, batch_id, "0301天空之城.pdf", "03", "天空之城", "c")
    _add_item(db, batch_id, "0101天空之城主题曲.pdf", "01", "天空之城主题曲", "d")
    _add_item(db, batch_id, "0201天空之城主题曲.pdf", "02", "天空之城主题曲", "e")
    _add_item(db, batch_id, "0301天空之城主题曲.pdf", "03", "天空之城主题曲", "f")

    warnings = GroupingService(db).refresh_warnings(batch_id)
    groups = GroupingService(db).group_batch(batch_id)

    assert any(warning["code"] == "similar_title_candidate" for warning in warnings)
    assert {group.title for group in groups} == {"天空之城", "天空之城主题曲"}


def test_refresh_warnings_detects_version_candidate_without_confirming_version(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城第二版.pdf", "01", "天空之城第二版", "a")
    _add_item(db, batch_id, "0201天空之城第二版.pdf", "02", "天空之城第二版", "b")
    _add_item(db, batch_id, "0301天空之城第二版.pdf", "03", "天空之城第二版", "c")

    warnings = GroupingService(db).refresh_warnings(batch_id)

    assert any(warning["code"] == "version_candidate" for warning in warnings)


def test_warning_state_survives_recalculation_when_warning_is_same(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城.pdf", "01", "天空之城", "a")
    _add_item(db, batch_id, "0201天空之城.pdf", "02", "天空之城", "b")

    GroupingService(db).refresh_warnings(batch_id, scan_completeness=True)
    warning = db.list_warnings(batch_id)[0]
    db.update_warning_state(warning["id"], "已确认无须修改", "暂存等待补谱")
    GroupingService(db).refresh_warnings(batch_id, scan_completeness=True)

    recalculated = db.list_warnings(batch_id)[0]
    assert recalculated["state"] == "已确认无须修改"
    assert recalculated["resolution"] == "暂存等待补谱"


def test_refresh_warnings_detects_duplicate_against_archived_catalog(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    song_id = db.create_song("01", "001", "天空之城", status="archived")
    db.add_score(song_id, "01", "old.pdf", "old.pdf", "same", "0101001天空之城.pdf")
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "new-copy.pdf", "02", "天空之城", "same")

    warnings = GroupingService(db).refresh_warnings(batch_id)

    duplicate = next(warning for warning in warnings if warning["code"] == "duplicate_existing_file")
    assert duplicate["severity"] == "blocking"
    assert duplicate["details"]["existing"][0]["output_name"] == "0101001天空之城.pdf"


def test_refresh_warnings_detects_existing_same_instrument_conflict(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    song_id = db.create_song("01", "001", "天空之城", status="archived")
    db.add_score(song_id, "01", "old.pdf", "old.pdf", "old", "0101001天空之城.pdf")
    batch_id = db.create_import_batch()
    _add_item(db, batch_id, "0101天空之城新版小提琴.pdf", "01", "天空之城", "new")

    warnings = GroupingService(db).refresh_warnings(batch_id)

    conflict = next(warning for warning in warnings if warning["code"] == "existing_instrument_conflict")
    assert conflict["severity"] == "blocking"
    assert conflict["details"]["instrument_code"] == "01"


@pytest.mark.parametrize("spaced", ["萤火虫之墓 《可爱的家 》", "萤火虫之墓\u3000《可爱的家》", "萤火虫之墓\t《可爱的家》"])
def test_whitespace_only_titles_block_without_automatic_merge(tmp_path, spaced):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch = db.create_import_batch()
    _add_item(db, batch, "a.pdf", "01", spaced, "a")
    _add_item(db, batch, "b.pdf", "02", "萤火虫之墓《可爱的家》", "b")
    warnings = GroupingService(db).refresh_warnings(batch)
    conflict = next(w for w in warnings if w["code"] == "whitespace_title_conflict")
    assert conflict["severity"] == "blocking"
    assert set(conflict["details"]["source_paths"]) == {"a.pdf", "b.pdf"}
    assert len(GroupingService(db).group_batch(batch)) == 2


def test_whitespace_title_checks_existing_catalog_and_respects_categories(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    song = db.create_song("01", "007", "萤火虫之墓《可爱的家》", status="archived")
    db.add_score(song, "01", "old.pdf", "old.pdf", "old", "0101007萤火虫之墓《可爱的家》.pdf")
    batch = db.create_import_batch()
    item = _add_item(db, batch, "new.pdf", "02", "萤火虫之墓 《可爱的家》", "new")
    warnings = GroupingService(db).refresh_warnings(batch)
    conflict = next(w for w in warnings if w["code"] == "whitespace_title_conflict")
    assert conflict["severity"] == "blocking"
    assert conflict["details"]["existing"][0]["seq"] == "007"
    assert conflict["details"]["source_paths"] == ["new.pdf"]
    db.upsert_category("02", "电影音乐", True)
    db.update_import_item_manual_fields(item, category_code="02")
    assert not any(w["code"] == "whitespace_title_conflict" for w in GroupingService(db).refresh_warnings(batch))
