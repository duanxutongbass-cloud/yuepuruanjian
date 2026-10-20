import json
import sqlite3
from pathlib import Path

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from test_correction_service import add_existing


def fixture(tmp_path, codes=("01", "02", "03"), existing=True):
    db = Database(tmp_path / "new.sqlite3")
    db.initialize()
    root = tmp_path / "输出"
    db.set_setting("output_root", str(root))
    old_id = add_existing(db, tmp_path, "007", "天空之城") if existing else None
    paths = []
    for index, code in enumerate(codes):
        path = tmp_path / f"{code}02新曲-{index}.pdf"
        path.write_bytes(f"%PDF new {index}".encode())
        paths.append(path)
    batch = ImportService(db).import_paths(paths)
    ids = [row["id"] for row in db.list_import_items(batch)]
    return db, root, old_id, batch, ids, paths


@pytest.mark.parametrize("existing,codes", [(True, ("01", "02", "03")), (False, ("01", "02", "03")), (True, ("08",))])
def test_new_song_allocates_global_number_and_never_changes_original_or_existing(tmp_path, existing, codes):
    db, root, old_id, batch, ids, sources = fixture(tmp_path, codes, existing)
    hashes = {path: sha256_file(path) for path in [*sources, *root.rglob("*.pdf")]}
    before_old = [dict(row) for row in db.list_archived_scores()]
    service = CorrectionService(db)
    if existing:
        service.apply(service.build_plan(batch, ids, target_song_id=old_id))
    plan = service.build_plan(batch, ids, title="天空之城现场编曲", category="02", new_song=True)
    assert plan.new_song and plan.target_song_id is None
    service.apply(plan)
    for row in db.list_import_items(batch):
        fields = json.loads(row["parsed_fields"])
        assert fields["seq"] is None and "target_song_id" not in fields
        assert fields["new_song_confirmation"] == {"category_code": "02", "title": "天空之城现场编曲"}
        if existing:
            assert fields["manual_original_seq"] == "007"
    GroupingService(db).refresh_warnings(batch)
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch)
    assert len(plans) == 1 and plans[0].existing_song_id is None
    assert plans[0].seq == "001"
    archive.commit_plan(batch, plans)
    assert db.count_songs() == (2 if existing else 1)
    assert all(sha256_file(path) == file.source_sha256 for file in plans[0].files for path in file.targets)
    assert {path: sha256_file(path) for path in hashes} == hashes
    if existing:
        assert [dict(row) for row in db.list_archived_scores(old_id)] == before_old


def test_new_song_does_not_bypass_minimum_or_duplicate_instrument(tmp_path):
    db, root, _, batch, ids, _ = fixture(tmp_path, ("01",))
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, ids, "独立曲目", "02", new_song=True))
    GroupingService(db).refresh_warnings(batch, scan_completeness=True)
    assert "insufficient_instruments" in {row["code"] for row in db.list_warnings(batch)}
    assert ArchiveService(db, root).build_plan(batch) == []
    assert len(db.list_import_items(batch)) == 1 and db.allocate_next_sequence() == "001"
    other = root.parent / "另一份.pdf"
    other.write_bytes(b"%PDF other same instrument")
    ImportService(db).import_paths([other], batch_id=batch)
    all_ids = [row["id"] for row in db.list_import_items(batch)]
    service.apply(service.build_plan(batch, all_ids, "独立曲目", "02", {all_ids[1]: "01"}, new_song=True))
    GroupingService(db).refresh_warnings(batch)
    assert "same_instrument_conflict" in {row["code"] for row in db.list_warnings(batch)}
    assert ArchiveService(db, root).build_plan(batch) == []


@pytest.mark.parametrize("title", ["天空之城", "天空 之城"])
def test_new_same_name_or_whitespace_only_is_not_a_new_song(tmp_path, title):
    db, _, _, batch, ids, _ = fixture(tmp_path)
    before = [dict(row) for row in db.list_import_items(batch)]
    with pytest.raises(ValueError, match="不能强制误拆"):
        CorrectionService(db).build_plan(batch, ids, title, "01", new_song=True)
    assert [dict(row) for row in db.list_import_items(batch)] == before


def test_new_song_cannot_silently_apply_to_another_unselected_piece(tmp_path):
    db, _, _, batch, ids, _ = fixture(tmp_path)
    service = CorrectionService(db)
    with pytest.raises(ValueError, match="统一"):
        service.build_plan(batch, ids, new_song=True)
    with pytest.raises(ValueError, match="未选中"):
        service.build_plan(batch, ids[:1], "新曲-1", "02", new_song=True)


def test_new_confirmation_is_invalidated_by_edit_and_library_changes(tmp_path):
    db, root, _, batch, ids, _ = fixture(tmp_path, ("08",))
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "独立新谱", "02", new_song=True)
    new_id = db.create_song("02", "008", "独立新谱", status="archived")
    with pytest.raises(ValueError, match="已有"):
        service.apply(plan)
    with db.session() as connection:
        connection.execute("DELETE FROM song WHERE id=?", (new_id,))
    service.apply(plan)
    archive = ArchiveService(db, root)
    GroupingService(db).refresh_warnings(batch)
    archive_plan = archive.build_plan(batch)
    db.create_song("02", "008", "独立新谱", status="archived")
    with pytest.raises(ValueError, match="不能自动改为补入"):
        archive.commit_plan(batch, archive_plan)
    assert len(db.list_import_items(batch)) == 1
    service.apply(service.build_plan(batch, ids, title="修改为另一名称"))
    assert "new_song_confirmation" not in json.loads(db.get_import_item(ids[0])["parsed_fields"])


def test_explicit_new_suffix_confirmation_and_duplicate_hash_safety(tmp_path):
    db, root, _, batch, ids, sources = fixture(tmp_path, ("08",))
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, ids, "新曲-总谱", "02", new_song=True))
    GroupingService(db).refresh_warnings(batch)
    assert "instrument_title_suffix" not in {row["code"] for row in db.list_warnings(batch)}
    assert ArchiveService(db, root).build_plan(batch)
    digest = db.list_archived_scores()[0]["source_sha256"]
    source = db.list_archived_scores()[0]["source_path"]
    sources[0].write_bytes(Path(source).read_bytes())
    with db.session() as connection:
        connection.execute("UPDATE import_item SET source_sha256=? WHERE id=?", (digest, ids[0]))
    GroupingService(db).refresh_warnings(batch)
    assert "duplicate_existing_file" in {row["code"] for row in db.list_warnings(batch)}
    assert ArchiveService(db, root).build_plan(batch) == []


def test_new_song_archive_failure_preserves_confirmation_originals_and_number(tmp_path, monkeypatch):
    db, root, _, batch, ids, sources = fixture(tmp_path)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, ids, "独立曲目", "02", new_song=True))
    GroupingService(db).refresh_warnings(batch)
    archive = ArchiveService(db, root)
    plan = archive.build_plan(batch)
    before = [dict(row) for row in db.list_import_items(batch)]
    hashes = {path: sha256_file(path) for path in [*sources, *root.rglob("*.pdf")]}
    monkeypatch.setattr(db, "add_score", lambda *args, **kwargs: (_ for _ in ()).throw(OSError("injected write failure")))
    with pytest.raises(OSError, match="injected"):
        archive.commit_plan(batch, plan)
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    assert {path: sha256_file(path) for path in root.rglob("*.pdf")} == {path: value for path, value in hashes.items() if path.is_relative_to(root)}
    assert {path: sha256_file(path) for path in sources} == {path: hashes[path] for path in sources}


def test_switching_to_normal_or_library_assignment_clears_new_confirmation(tmp_path):
    db, _, old_id, batch, ids, _ = fixture(tmp_path, ("08",))
    service = CorrectionService(db)
    with pytest.raises(ValueError, match="不能同时"):
        service.build_plan(batch, ids, new_song=True, target_song_id=old_id)
    service.apply(service.build_plan(batch, ids, "新曲", "02", new_song=True))
    service.apply(service.build_plan(batch, ids))
    assert "new_song_confirmation" not in json.loads(db.get_import_item(ids[0])["parsed_fields"])
    service.apply(service.build_plan(batch, ids, "新曲", "02", new_song=True))
    service.apply(service.build_plan(batch, ids, target_song_id=old_id))
    fields = json.loads(db.get_import_item(ids[0])["parsed_fields"])
    assert "new_song_confirmation" not in fields and fields["target_song_id"] == old_id


def test_new_mixed_score_archives_total_first_and_continues_same_number(tmp_path):
    db, root, _, batch, ids, sources = fixture(tmp_path, ("08", "01", "02"))
    CorrectionService(db).apply(CorrectionService(db).build_plan(batch, ids, "独立曲目", "02", new_song=True))
    GroupingService(db).refresh_warnings(batch)
    archive = ArchiveService(db, root)
    assert archive.commit_plan(batch, archive.build_plan(batch)) == 2
    new_song = db.find_archived_song("02", "独立曲目")
    assert new_song["seq"] == "001"
    for item in db.list_import_items(batch):
        fields = json.loads(item["parsed_fields"])
        assert "new_song_confirmation" not in fields
        assert fields["target_song_id"] == new_song["id"] and fields["seq"] == "001"
    cello = tmp_path / "0302独立曲目.pdf"
    cello.write_bytes(b"%PDF supplement viola")
    ImportService(db).import_paths([cello], batch_id=batch)
    GroupingService(db).refresh_warnings(batch)
    plans = archive.build_plan(batch)
    assert len(plans) == 1 and plans[0].seq == "001" and plans[0].existing_song_id == new_song["id"]
    archive.commit_plan(batch, plans)
    assert db.count_songs() == 2 and len(db.list_archived_scores(new_song["id"])) == 4
    assert db.allocate_next_sequence() == "002"


def test_new_mixed_continuation_record_failure_rolls_back_everything(tmp_path, monkeypatch):
    db, root, _, batch, ids, sources = fixture(tmp_path, ("08", "01", "02"))
    CorrectionService(db).apply(CorrectionService(db).build_plan(batch, ids, "独立曲目", "02", new_song=True))
    GroupingService(db).refresh_warnings(batch)
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch)
    items = [dict(row) for row in db.list_import_items(batch)]
    before = {path: sha256_file(path) for path in [*sources, *root.rglob("*.pdf")]}
    with db.session() as connection:
        connection.execute("CREATE TRIGGER reject_continuation BEFORE UPDATE ON import_item BEGIN SELECT RAISE(ABORT,'injected continuation failure'); END")
    with pytest.raises(sqlite3.IntegrityError, match="injected"):
        archive.commit_plan(batch, plans)
    assert [dict(row) for row in db.list_import_items(batch)] == items
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    assert {path: sha256_file(path) for path in root.rglob("*.pdf")} == {path: value for path, value in before.items() if path.is_relative_to(root)}
    assert {path: sha256_file(path) for path in sources} == {path: before[path] for path in sources}
