from pathlib import Path

from score_organizer.services.duplicate_service import DuplicateService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService
from score_organizer.storage.database import Database


def test_duplicate_rows_list_all_files_in_same_named_different_folders(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for folder in ("a", "b", "c", "d"):
        path = tmp_path / folder / "0101曲目.pdf"
        path.parent.mkdir()
        path.write_bytes(b"%PDF identical")
        files.append(path)
    batch = ImportService(db).import_paths(files)
    GroupingService(db).refresh_warnings(batch)
    rows = DuplicateService(db).list_rows(batch)
    identical = [row for row in rows if row["issue"] == "内容完全相同"]
    assert len(identical) == 4
    assert {row["path"] for row in identical} == {str(path) for path in files}
    assert all(row["item_id"] is not None for row in identical)


def test_duplicate_rows_include_catalog_match_and_only_matching_draft_ids(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    song = db.create_song("01", "001", "曲目", status="archived")
    db.add_score(song, "01", "old.pdf", "old.pdf", "hash", "0101001曲目.pdf")
    batch = db.create_import_batch()
    first = db.add_import_item(batch, "new.pdf", "hash", "recognized", {"instrument_code": "01", "category_code": "01", "title": "曲目"})
    db.add_import_item(batch, "other.pdf", "different", "recognized", {"instrument_code": "02", "category_code": "01", "title": "曲目"})
    GroupingService(db).refresh_warnings(batch)
    service = DuplicateService(db)
    rows = service.list_rows(batch)
    assert service.already_archived_item_ids(batch) == [first]
    assert any(row["location"] == "已入库" and row["item_id"] is None and row["name"] == "0101001曲目.pdf" for row in rows)
    assert any(row["location"] == "草稿" and row["item_id"] == first for row in rows)
