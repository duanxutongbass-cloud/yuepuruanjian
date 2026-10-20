import json
from pathlib import Path

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.review_service import ReviewService
from score_organizer.storage.database import Database


def setup_db(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    return db


def pdf(tmp_path, name, content):
    path = tmp_path / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(content)
    return path


def test_review_classifies_reasons_preserves_sources_and_leaves_ready_song(tmp_path):
    db = setup_db(tmp_path)
    files = [pdf(tmp_path, "0101缺谱.pdf", b"%PDF incomplete"),
             pdf(tmp_path, "无编号.pdf", b"%PDF unnamed")]
    files += [pdf(tmp_path, f"{code}01重复曲.pdf", b"%PDF duplicate" if code != "03" else b"%PDF third") for code in ("01", "02", "03")]
    files += [pdf(tmp_path, f"{code}01完整曲.pdf", f"%PDF complete {code}".encode()) for code in ("01", "02", "03")]
    hashes = [sha256_file(path) for path in files]
    batch = ImportService(db).import_paths(files)
    service = ReviewService(db, tmp_path / "out")
    plan = service.build_plan(batch)
    assert len(plan) == 5
    assert {reason for item in plan for reason in item["reasons"]} >= {"乐器不足", "需要手动命名", "重复", "关联曲目待审查"}
    package, remaining = service.commit_plan(batch, plan)
    assert remaining == 3
    assert db.count_songs() == db.count_scores() == 0
    assert [sha256_file(path) for path in files] == hashes
    assert not (tmp_path / "out" / "按曲目分类").exists()
    assert (package / "审查清单.txt").is_file()
    for item in plan:
        saved = package / item["relative_path"]
        assert sha256_file(saved) == item["source_sha256"]
        assert all(reason in (saved.parent / "审查说明.txt").read_text(encoding="utf-8-sig") for reason in item["reasons"])


def test_review_keeps_distinct_copies_with_same_filename(tmp_path):
    db = setup_db(tmp_path)
    files = [pdf(tmp_path, f"{folder}/0101曲目.pdf", b"%PDF same") for folder in ("a", "b")]
    batch = ImportService(db).import_paths(files)
    service = ReviewService(db, tmp_path / "out")
    package, remaining = service.commit_plan(batch, service.build_plan(batch))
    saved = list(package.rglob("*.pdf"))
    assert len(saved) == 2
    assert {path.name for path in saved} == {"0101曲目.pdf"}
    assert remaining == 0
    assert db.latest_draft_batch_id() is None


def test_changed_source_cancels_review_without_removing_draft(tmp_path):
    db = setup_db(tmp_path)
    source = pdf(tmp_path, "0101曲目.pdf", b"%PDF original")
    batch = ImportService(db).import_paths([source])
    service = ReviewService(db, tmp_path / "out")
    plan = service.build_plan(batch)
    source.write_bytes(b"%PDF changed outside application")
    with pytest.raises(ValueError, match="变化"):
        service.commit_plan(batch, plan)
    assert len(db.list_import_items(batch)) == 1
    assert not list(service.root.rglob("*.pdf"))


def test_review_database_failure_removes_published_package(tmp_path, monkeypatch):
    db = setup_db(tmp_path)
    source = pdf(tmp_path, "0101曲目.pdf", b"%PDF original")
    batch = ImportService(db).import_paths([source])
    service = ReviewService(db, tmp_path / "out")
    plan = service.build_plan(batch)
    def fail(*args):
        raise ValueError("模拟保存失败")
    monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    with pytest.raises(ValueError):
        service.commit_plan(batch, plan)
    assert len(db.list_import_items(batch)) == 1
    assert not list(service.root.iterdir())
    assert source.read_bytes() == b"%PDF original"


def test_review_restores_manual_fields_from_copy_and_prevents_double_restore(tmp_path):
    db = setup_db(tmp_path)
    source = pdf(tmp_path, "随意名称.pdf", b"%PDF original")
    batch = ImportService(db).import_paths([source])
    item = db.list_import_items(batch)[0]
    db.update_import_item_manual_fields(item["id"], instrument_code="01", category_code="02", title="已校对曲名")
    service = ReviewService(db, tmp_path / "out")
    service.commit_plan(batch, service.build_plan(batch))
    entries = service.list_pending()
    source.unlink()
    new_batch = service.restore_to_draft(entries)
    restored = db.list_import_items(new_batch)
    assert len(restored) == 1
    assert json.loads(restored[0]["parsed_fields"])["title"] == "已校对曲名"
    assert json.loads(restored[0]["parsed_fields"])["category_code"] == "02"
    assert Path(restored[0]["source_path"]).is_file()
    assert service.list_pending() == []
    service.restore_to_draft(entries, new_batch)
    assert len(db.list_import_items(new_batch)) == 1


def test_review_restore_failure_rolls_back_draft_and_manifest(tmp_path, monkeypatch):
    db = setup_db(tmp_path)
    files = [pdf(tmp_path, f"{code}01曲目.pdf", f"%PDF {code}".encode()) for code in ("01", "02")]
    batch = ImportService(db).import_paths(files)
    service = ReviewService(db, tmp_path / "out")
    service.commit_plan(batch, service.build_plan(batch))
    entries = service.list_pending()
    original = db.add_import_item
    calls = 0
    def fail(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ValueError("模拟恢复失败")
        return original(*args, **kwargs)
    monkeypatch.setattr(db, "add_import_item", fail)
    with pytest.raises(ValueError):
        service.restore_to_draft(entries)
    assert db.latest_draft_batch_id() is None
    assert len(service.list_pending()) == 2


def test_review_restore_then_supplement_can_formally_archive(tmp_path):
    db = setup_db(tmp_path)
    first = pdf(tmp_path, "0101曲目.pdf", b"%PDF first")
    batch = ImportService(db).import_paths([first])
    review = ReviewService(db, tmp_path / "out")
    review.commit_plan(batch, review.build_plan(batch))
    batch = review.restore_to_draft(review.list_pending())
    extra = [pdf(tmp_path, f"{code}01曲目.pdf", f"%PDF {code}".encode()) for code in ("02", "03")]
    ImportService(db).import_paths(extra, batch)
    archive = ArchiveService(db, tmp_path / "out")
    plans = archive.build_plan(batch)
    archive.commit_plan(batch, plans)
    assert db.count_songs() == 1
    assert db.count_scores() == 3
    for item in plans[0].files:
        assert sha256_file(item.by_song_path) == sha256_file(item.by_instrument_path) == item.source_sha256


def test_acknowledged_incomplete_song_can_still_go_to_review(tmp_path):
    db = setup_db(tmp_path)
    source = pdf(tmp_path, "0101曲目.pdf", b"%PDF original")
    batch = ImportService(db).import_paths([source])
    service = ReviewService(db, tmp_path / "out")
    service.build_plan(batch)
    warning = db.list_warnings(batch)[0]
    db.update_warning_state(warning["id"], "已确认无须修改")
    assert "乐器不足" in service.build_plan(batch)[0]["reasons"]


def test_review_marks_both_songs_when_imported_sequences_collide(tmp_path):
    db = setup_db(tmp_path)
    files = [pdf(tmp_path, "0101017甲曲.pdf", b"%PDF a"),
             pdf(tmp_path, "0102017乙曲.pdf", b"%PDF b")]
    batch = ImportService(db).import_paths(files)
    plan = ReviewService(db, tmp_path / "out").build_plan(batch)
    assert len(plan) == 2
    assert all("编号冲突" in item["reasons"] for item in plan)


def test_review_refuses_manifest_paths_outside_package(tmp_path):
    db = setup_db(tmp_path)
    source = pdf(tmp_path, "0101曲目.pdf", b"%PDF original")
    batch = ImportService(db).import_paths([source])
    service = ReviewService(db, tmp_path / "out")
    package, _ = service.commit_plan(batch, service.build_plan(batch))
    path = package / "审查清单.json"
    manifest = json.loads(path.read_text(encoding="utf-8"))
    manifest["items"][0]["relative_path"] = "../../outside.pdf"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="无效文件路径"):
        service.list_pending()
