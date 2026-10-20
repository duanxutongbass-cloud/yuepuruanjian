import json
import sqlite3

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.catalog_service import CatalogService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.reset_service import ResetService
from score_organizer.storage.database import Database


def fixture(tmp_path, codes=("01",), title="独立曲目"):
    db = Database(tmp_path / "force.sqlite3")
    db.initialize()
    root = tmp_path / "输出"
    db.set_setting("output_root", str(root))
    files = []
    for index, code in enumerate(codes):
        source = tmp_path / f"{code}01{title}.pdf"
        source.write_bytes(f"%PDF part {index}".encode())
        files.append(source)
    batch = ImportService(db).import_paths(files)
    ids = {row["id"] for row in db.list_import_items(batch)}
    return db, root, files, batch, ids


@pytest.mark.parametrize("codes", [("01",), ("01", "02"), ("08",), ("08", "01")])
def test_force_single_or_two_parts_enter_formal_library_with_actual_layout(tmp_path, codes):
    db, root, sources, batch, ids = fixture(tmp_path, codes)
    hashes = {path: sha256_file(path) for path in sources}
    service = ArchiveService(db, root)
    plans = service.build_plan(batch, ids, force=True)
    assert len(plans) == 1 and plans[0].seq == "001" and plans[0].force
    assert len(plans[0].files) == len(codes)
    service.commit_plan(batch, plans)
    song = db.get_archived_song(db.find_archived_song("01", "独立曲目")["id"])
    assert song["minimum_waived"] == int(any(code != "08" for code in codes))
    assert db.count_songs() == 1 and db.count_scores() == len(codes)
    assert db.minimum_instruments("01") == 3
    for file in plans[0].files:
        assert len(file.targets) == (1 if file.instrument_code == "08" else 2)
        assert all(sha256_file(target) == hashes[file.source_path] for target in file.targets)
        if file.by_song_path:
            assert f"（{len(set(codes) - {'08'}):02d}）" in str(file.by_song_path.parent)
    assert not (root / "待审查").exists()
    assert {path: sha256_file(path) for path in sources} == hashes
    with db.session() as connection:
        log = json.loads(connection.execute("SELECT new_path FROM operation_log WHERE action='force_archive'").fetchone()[0])
    assert log["selected_item_ids"] == sorted(ids)
    db2 = Database(db.path)
    db2.initialize()
    assert db2.get_archived_song(song["id"])["minimum_waived"] == song["minimum_waived"]


def test_only_explicit_selected_part_is_forced_not_other_same_song_drafts(tmp_path):
    db, root, sources, batch, ids = fixture(tmp_path, ("01", "02", "03"))
    selected = {min(ids)}
    untouched = [dict(row) for row in db.list_import_items(batch) if row["id"] not in selected]
    service = ArchiveService(db, root)
    plans = service.build_plan(batch, selected, force=True)
    assert len(plans[0].files) == 1 and "（01）" in plans[0].folder_name
    assert service.commit_plan(batch, plans) == 2
    assert [dict(row) for row in db.list_import_items(batch)] == untouched
    assert db.count_scores() == 1 and db.allocate_next_sequence() == "002"
    with pytest.raises(ValueError, match="明确选择"):
        service.build_plan(batch, force=True)


def test_normal_archive_still_blocks_incomplete_new_song_and_waived_song_accepts_supplements(tmp_path):
    db, root, sources, batch, ids = fixture(tmp_path)
    service = ArchiveService(db, root)
    assert service.build_plan(batch) == []
    service.commit_plan(batch, service.build_plan(batch, ids, force=True))
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    for code, expected_count in [("02", 2), ("03", 3)]:
        source = tmp_path / f"{code}01独立曲目.pdf"
        source.write_bytes(f"%PDF supplement {code}".encode())
        new_batch = ImportService(db).import_paths([source])
        warnings = GroupingService(db).refresh_warnings(new_batch, scan_completeness=True)
        if expected_count < 3:
            missing = next(w for w in warnings if w["code"] == "insufficient_instruments")
            assert missing["severity"] == "warning" and missing["details"]["minimum_waived"]
        plans = service.build_plan(new_batch)
        assert plans[0].seq == "001" and plans[0].existing_song_id == song_id and not plans[0].force
        service.commit_plan(new_batch, plans)
        assert len(db.song_copy_codes(song_id)) == expected_count
    assert db.count_songs() == 1 and db.minimum_instruments("01") == 3
    other = tmp_path / "0102别的作品.pdf"
    other.write_bytes(b"%PDF still incomplete normal song")
    other_batch = ImportService(db).import_paths([other])
    assert service.build_plan(other_batch) == []


@pytest.mark.parametrize("failure", ["database", "source", "target"])
def test_force_failure_preserves_draft_original_number_and_no_waiver(tmp_path, monkeypatch, failure):
    db, root, sources, batch, ids = fixture(tmp_path)
    service = ArchiveService(db, root)
    plan = service.build_plan(batch, ids, force=True)
    before = [dict(row) for row in db.list_import_items(batch)]
    if failure == "database":
        monkeypatch.setattr(db, "add_score", lambda *args, **kwargs: (_ for _ in ()).throw(sqlite3.IntegrityError("injected failure")))
    elif failure == "source":
        sources[0].write_bytes(b"%PDF changed after preview")
    else:
        target = plan[0].files[0].by_song_path
        target.parent.mkdir(parents=True)
        target.write_bytes(b"user existing file")
    with pytest.raises((ValueError, sqlite3.IntegrityError)):
        service.commit_plan(batch, plan)
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert db.count_songs() == 0 and db.count_scores() == 0 and db.allocate_next_sequence() == "001"
    assert not list((root / "按乐器分类").rglob("*.pdf"))
    if failure == "target":
        assert target.read_bytes() == b"user existing file"


def test_force_never_bypasses_duplicate_or_same_instrument_conflicts(tmp_path):
    db, root, sources, batch, ids = fixture(tmp_path)
    service = ArchiveService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids, force=True))
    new_dir = tmp_path / "补录"
    new_dir.mkdir()
    for same_bytes in (True, False):
        source = new_dir / f"0101独立曲目.pdf"
        source.write_bytes(sources[0].read_bytes() if same_bytes else b"%PDF different same instrument")
        next_batch = ImportService(db).import_paths([source])
        new_ids = {row["id"] for row in db.list_import_items(next_batch)}
        assert service.build_plan(next_batch, new_ids, force=True) == []
    assert db.count_songs() == 1 and db.count_scores() == 1


def test_force_into_existing_total_score_is_formal_supplement_without_changing_number(tmp_path):
    db, root, _, batch, ids = fixture(tmp_path, ("08",))
    service = ArchiveService(db, root)
    service.commit_plan(batch, service.build_plan(batch))
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    source = tmp_path / "0101独立曲目.pdf"
    source.write_bytes(b"%PDF single violin supplement")
    next_batch = ImportService(db).import_paths([source])
    assert service.build_plan(next_batch) == []
    new_ids = {row["id"] for row in db.list_import_items(next_batch)}
    plans = service.build_plan(next_batch, new_ids, force=True)
    assert plans[0].existing_song_id == song_id and plans[0].seq == "001"
    service.commit_plan(next_batch, plans)
    assert db.get_archived_song(song_id)["minimum_waived"] == 1
    assert db.count_songs() == 1 and db.count_scores() == 2


@pytest.mark.parametrize("legacy", [False, True])
def test_waiver_clear_restore_and_old_database_migration(tmp_path, legacy):
    db, root, _, batch, ids = fixture(tmp_path)
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch, ids, force=True))
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    reset = ResetService(db)
    backup = reset.clear(reset.build_plan())
    if legacy:
        snapshot = backup / "清空前.sqlite3"
        with sqlite3.connect(snapshot) as connection:
            connection.execute("ALTER TABLE song DROP COLUMN minimum_waived")
        manifest_path = backup / "清空记录.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["database_sha256"] = sha256_file(snapshot)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    reset.restore(backup)
    assert db.get_archived_song(song_id)["minimum_waived"] == int(not legacy)
    assert db.allocate_next_sequence() == "002" and db.count_scores() == 1


def test_force_flag_survives_catalog_rename(tmp_path):
    db, root, _, batch, ids = fixture(tmp_path)
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch, ids, force=True))
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    service = CatalogService(db, root)
    plan = service.build_plan(song_id, "02", "修改后的作品")
    service.commit_plan(plan)
    assert db.get_archived_song(song_id)["minimum_waived"] == 1
    assert db.get_archived_song(song_id)["seq"] == "001"


def test_initialize_old_library_does_not_waive_existing_songs(tmp_path):
    db, root, sources, batch, _ = fixture(tmp_path, ("01", "02", "03"))
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch)
    archive.commit_plan(batch, plans)
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    before = [dict(row) for row in db.list_archived_scores()]
    hashes = {path: sha256_file(path) for path in sources}
    with db.session() as connection:
        connection.execute("ALTER TABLE song DROP COLUMN minimum_waived")
    db.initialize()
    db.initialize()
    assert db.get_archived_song(song_id)["minimum_waived"] == 0
    assert db.get_archived_song(song_id)["seq"] == "001"
    assert [dict(row) for row in db.list_archived_scores()] == before
    assert {path: sha256_file(path) for path in sources} == hashes
    assert all(sha256_file(target) == file.source_sha256 for file in plans[0].files for target in file.targets)


@pytest.mark.parametrize("code", ["00", "01"])
def test_force_does_not_allow_unknown_or_disabled_instruments(tmp_path, code):
    db = Database(tmp_path / "instrument-safety.sqlite3")
    db.initialize()
    if code == "01":
        db.upsert_instrument("01", "第一小提琴", active=False)
    with pytest.raises(ValueError):
        db.archive_eligible_codes("01", {code}, force=True)
    assert db.count_songs() == 0 and db.allocate_next_sequence() == "001"


def test_failed_force_supplement_does_not_waive_existing_total_only_song(tmp_path, monkeypatch):
    db, root, sources, batch, _ = fixture(tmp_path, ("08",))
    archive = ArchiveService(db, root)
    old_plan = archive.build_plan(batch)
    archive.commit_plan(batch, old_plan)
    song_id = db.find_archived_song("01", "独立曲目")["id"]
    old_song = dict(db.get_archived_song(song_id))
    old_scores = [dict(row) for row in db.list_archived_scores()]
    original_hash = sha256_file(sources[0])
    new_source = tmp_path / "0101独立曲目.pdf"
    new_source.write_bytes(b"%PDF force supplement failure")
    new_hash = sha256_file(new_source)
    next_batch = ImportService(db).import_paths([new_source])
    drafts = [dict(row) for row in db.list_import_items(next_batch)]
    plans = archive.build_plan(next_batch, {row["id"] for row in drafts}, force=True)
    monkeypatch.setattr(db, "add_score", lambda *args, **kwargs: (_ for _ in ()).throw(sqlite3.IntegrityError("injected supplement failure")))
    with pytest.raises(sqlite3.IntegrityError):
        archive.commit_plan(next_batch, plans)
    assert dict(db.get_archived_song(song_id)) == old_song
    assert [dict(row) for row in db.list_archived_scores()] == old_scores
    assert [dict(row) for row in db.list_import_items(next_batch)] == drafts
    assert db.allocate_next_sequence() == "002"
    assert sha256_file(sources[0]) == original_hash and sha256_file(new_source) == new_hash
    assert all(not target.exists() for target in plans[0].files[0].targets)
    assert all(sha256_file(target) == old_plan[0].files[0].source_sha256 for target in old_plan[0].files[0].targets)
