import json

import pytest

from score_organizer.storage.database import Database
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.archive_service import ArchiveService


def database(tmp_path):
    db = Database(tmp_path / "library.sqlite3")
    db.initialize()
    return db


@pytest.mark.parametrize("seq", ["", "012"])
def test_custom_instrument_code_import_and_single_archive_persist(tmp_path, seq):
    db = database(tmp_path)
    db.upsert_instrument("10", "长笛", archive_mode="instrument_only")
    db.initialize()
    assert "10" in db.instrument_codes()
    assert db.instrument_archive_modes()["10"] == "instrument_only"
    source = tmp_path / f"1001{seq}新曲目.pdf"
    source.write_bytes(b"%PDF custom")
    before = sha256_file(source)
    batch = ImportService(db).import_paths([source])
    assert json.loads(db.list_import_items(batch)[0]["parsed_fields"])["instrument_code"] == "10"
    service = ArchiveService(db, tmp_path / "out")
    plans = service.build_plan(batch)
    assert plans[0].files[0].by_instrument_path.parent.name == "10长笛"
    service.commit_plan(batch, plans)
    assert sha256_file(source) == before == sha256_file(plans[0].files[0].by_instrument_path)
    assert plans[0].files[0].by_song_path is None
    with pytest.raises(ValueError, match="不能直接改名"):
        db.upsert_instrument("10", "短笛", archive_mode="instrument_only")
    assert next(row for row in db.list_instruments() if row["code"] == "10")["name"] == "长笛"


def test_inactive_standalone_library_type_does_not_count_as_regular_in_review(tmp_path):
    from score_organizer.services.review_service import ReviewService
    db = database(tmp_path)
    db.upsert_instrument("10", "长笛", archive_mode="instrument_only")
    source = tmp_path / "1001作品.pdf"
    source.write_bytes(b"%PDF solo")
    service = ArchiveService(db, tmp_path / "out")
    first = ImportService(db).import_paths([source])
    service.commit_plan(first, service.build_plan(first))
    db.upsert_instrument("10", "长笛", active=False, archive_mode="instrument_only")
    db.save_archive_rules(db.instrument_archive_modes(), {row["code"]: 2 for row in db.list_categories()})
    ordinary = tmp_path / "0101作品.pdf"
    ordinary.write_bytes(b"%PDF regular")
    batch = ImportService(db).import_paths([ordinary])
    assert service.build_plan(batch) == []
    plan = ReviewService(db, tmp_path / "out").build_plan(batch)
    assert len(plan) == 1 and "乐器不足" in plan[0]["reasons"]


def test_new_regular_instrument_requires_category_minimum(tmp_path):
    db = database(tmp_path)
    db.upsert_instrument("11", "圆号")
    source = tmp_path / "1101作品.pdf"
    source.write_bytes(b"%PDF horn")
    batch = ImportService(db).import_paths([source])
    assert ArchiveService(db, tmp_path / "out").build_plan(batch) == []
    db.upsert_instrument("11", "法国号")
    with pytest.raises(ValueError, match="草稿"):
        db.upsert_instrument("11", "法国号", active=False)
    assert "11" in db.instrument_codes()
    db.clear_draft_batch(batch)
    db.upsert_instrument("11", "法国号", active=False)
    assert "11" not in db.instrument_codes()
    db.upsert_instrument("11", "法国号", active=True)
    assert "11" in db.instrument_codes()


@pytest.mark.parametrize("code,name", [("1", "长笛"), ("100", "长笛"), ("ab", "长笛"), ("１０", "长笛"),
                                       ("10", ""), ("10", "a/b"), ("10", "a."), ("10", "a" * 81)])
def test_invalid_instrument_metadata_changes_nothing(tmp_path, code, name):
    db = database(tmp_path)
    with pytest.raises(ValueError):
        db.upsert_instrument(code, name)
    assert len(db.list_instruments()) == 9
    assert db.get_setting("instrument_archive_modes") is None


def test_instrument_save_failure_rolls_back_metadata_and_rules(tmp_path, monkeypatch):
    db = database(tmp_path)
    monkeypatch.setattr(db, "set_setting", lambda *args: (_ for _ in ()).throw(ValueError("injected failure")))
    with pytest.raises(ValueError):
        db.upsert_instrument("10", "长笛", archive_mode="instrument_only")
    assert "10" not in db.instrument_codes()
