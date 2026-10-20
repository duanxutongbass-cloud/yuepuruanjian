import json

import pytest

from score_organizer.services.duplicate_service import ConfirmedDuplicateService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database


def duplicate_batch(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = []
    for folder in ("a", "b"):
        path = tmp_path / folder / "0101曲目.pdf"
        path.parent.mkdir()
        path.write_bytes(b"%PDF same")
        files.append(path)
    batch = ImportService(db).import_paths(files)
    GroupingService(db).refresh_warnings(batch)
    return db, batch, files


def test_confirmed_duplicate_copies_file_and_keeps_other_draft_and_originals(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    items = db.list_import_items(batch)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    package, remaining = service.confirm(batch, [int(items[1]["id"])])
    assert package.name == "已确认重复"
    assert remaining == 1
    assert db.list_import_items(batch)[0]["id"] == items[0]["id"]
    copy = next(package.rglob("*.pdf"))
    assert copy.parent.name == "曲目"
    assert copy.name == "01第一小提琴-曲目（重复1）.pdf"
    assert sha256_file(copy) == sha256_file(files[1])
    assert all(path.read_bytes() == b"%PDF same" for path in files)
    text = (copy.parent / "重复确认说明.txt").read_text(encoding="utf-8-sig")
    assert str(files[0]) in text
    assert "用户已确认重复" in text
    assert (package / "重复清单.txt").is_file()
    assert json.loads((package / "重复清单.json").read_text(encoding="utf-8"))["items"][0]["parsed_fields"]["title"] == "曲目"


def test_confirmed_duplicate_same_names_do_not_overwrite(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    keeper = tmp_path / "c" / files[0].name
    keeper.parent.mkdir()
    keeper.write_bytes(files[0].read_bytes())
    ImportService(db).import_paths([keeper], batch)
    GroupingService(db).refresh_warnings(batch)
    files.append(keeper)
    ids = [int(item["id"]) for item in db.list_import_items(batch)[:2]]
    package, remaining = ConfirmedDuplicateService(db, tmp_path / "out").confirm(batch, ids)
    assert sorted(path.name for path in (package / "曲目").glob("*.pdf")) == [
        "01第一小提琴-曲目（重复1）.pdf", "01第一小提琴-曲目（重复2）.pdf"]
    assert remaining == 1
    assert db.latest_draft_batch_id() == batch
    assert all(path.exists() for path in files)


def test_bulk_cannot_transfer_last_copy_of_a_group(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    ids = [int(item["id"]) for item in db.list_import_items(batch)]
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    with pytest.raises(ValueError, match="每组至少"):
        service.confirm(batch, ids)
    assert len(db.list_import_items(batch)) == 2
    assert not service.root.exists()
    assert all(p.is_file() for p in files)


def test_bulk_retained_file_change_during_copy_aborts_all(tmp_path, monkeypatch):
    import shutil
    db, batch, files = duplicate_batch(tmp_path)
    item_id = int(db.list_import_items(batch)[1]["id"])
    original = shutil.copy2
    def copy_then_change(source, target, *args):
        result = original(source, target, *args)
        files[0].write_bytes(b"externally changed keeper")
        return result
    monkeypatch.setattr(shutil, "copy2", copy_then_change)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    with pytest.raises(ValueError, match="每组至少"):
        service.confirm(batch, [item_id])
    assert len(db.list_import_items(batch)) == 2
    assert not list(service.root.rglob("*.pdf"))


def test_confirm_duplicate_failure_keeps_draft_and_cleans_output(tmp_path, monkeypatch):
    db, batch, files = duplicate_batch(tmp_path)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    item_id = int(db.list_import_items(batch)[1]["id"])
    def fail(*args):
        raise ValueError("模拟记录保存失败")
    monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    with pytest.raises(ValueError):
        service.confirm(batch, [item_id])
    assert len(db.list_import_items(batch)) == 2
    assert not list(service.root.iterdir())
    assert all(path.exists() for path in files)


def test_confirm_duplicate_changed_source_stops_before_removing_draft(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    item_id = int(db.list_import_items(batch)[1]["id"])
    files[1].write_bytes(b"%PDF changed")
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    with pytest.raises(ValueError, match="变化"):
        service.confirm(batch, [item_id])
    assert len(db.list_import_items(batch)) == 2
    assert not list(service.root.rglob("*.pdf"))


def test_confirm_rejects_unrelated_nonduplicate_file(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    path = tmp_path / "0101独立曲.pdf"
    path.write_bytes(b"%PDF unique")
    batch = ImportService(db).import_paths([path])
    GroupingService(db).refresh_warnings(batch)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    with pytest.raises(ValueError, match="变化"):
        service.confirm(batch, [int(db.list_import_items(batch)[0]["id"])])
    assert path.is_file()
