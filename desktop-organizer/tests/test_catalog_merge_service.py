import json
import shutil
import sqlite3

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.catalog_merge_service import CatalogMergeService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.import_service import ImportService
from score_organizer.services.reset_service import ResetService
from score_organizer.storage.database import Database


def merge_fixture(tmp_path):
    db = Database(tmp_path / "merge.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    archive = ArchiveService(db, root)
    sources = []
    ids = []
    for seq, title, instruments in [("007", "萤火虫之墓 《可爱的家》", ("02", "03", "04")),
                                    ("008", "萤火虫之墓《可爱的家》", ("02", "05", "06"))]:
        song_id = db.create_song("01", seq, title, status="archived")
        ids.append(song_id)
        for code in instruments:
            original = tmp_path / f"{code}01{title}.pdf"
            original.write_bytes(f"%PDF-1.4 {seq} {code}".encode())
            sources.append(original)
            name = f"{code}01{seq}{title}.pdf"
            for path in [root / "按乐器分类" / archive._instrument_folder(code) / name,
                         root / "按曲目分类" / f"01{seq}{title}（03）" / name]:
                path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(original, path)
            db.add_score(song_id, code, original.name, str(original), sha256_file(original), name)
    return db, root, ids, sources


def selected_ids(db, ids, keep_conflict_from=0):
    return [int(r["id"]) for r in db.list_archived_scores()
            if r["instrument_code"] != "02" or r["song_id"] == ids[keep_conflict_from]]


def formal_hashes(root):
    return {p.relative_to(root): sha256_file(p) for folder in ("按乐器分类", "按曲目分类")
            for p in (root / folder).rglob("*.pdf")}


@pytest.mark.parametrize("retained", [0, 1])
def test_merge_keeps_chosen_number_reserves_other_and_transfers_confirmed_duplicate(tmp_path, retained):
    db, root, ids, sources = merge_fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in sources}
    service = CatalogMergeService(db, root)
    plan = service.build_plan(ids[retained], ids[1 - retained], selected_ids(db, ids, 1 - retained), "萤火虫之墓《可爱的家》")
    before = formal_hashes(root)
    assert len(before) == 12 and len(plan.files) == 6
    backup = service.commit_plan(plan)
    assert (backup / "修改前.sqlite3").is_file()
    assert len(list(backup.glob("*.pdf"))) == 12
    scores = db.list_archived_scores()
    assert len(scores) == 5 and {r["song_id"] for r in scores} == {ids[retained]}
    assert {r["seq"] for r in scores} == {"007" if retained == 0 else "008"}
    assert db.get_archived_song(ids[1 - retained]) is None
    assert db.allocate_next_sequence() == "001"
    with db.session() as c:
        assert c.execute("SELECT status FROM song WHERE id=?", (ids[1 - retained],)).fetchone()[0] == "merged"
        assert c.execute("PRAGMA foreign_key_check").fetchall() == []
        assert c.execute("SELECT action FROM operation_log").fetchone()[0] == "merge_catalog"
    for file in plan.files:
        assert sha256_file(file.new_song) == sha256_file(file.new_instrument) == file.sha256
        if file.score_id not in plan.selected_score_ids:
            assert not db.find_scores_by_hash(file.sha256)
    assert plan.duplicate_root.name == plan.title
    assert all(file.new_song.parent == plan.duplicate_root and file.new_instrument.parent == plan.duplicate_root
               for file in plan.files if file.score_id not in plan.selected_score_ids)
    manifest = json.loads((plan.duplicate_root / f"合并对比记录_{plan.event_id}.json").read_text(encoding="utf-8"))
    assert len(manifest["original_records"]) == 6
    assert len(manifest["selected_score_ids"]) == 5
    assert (plan.duplicate_root / f"合并确认说明_{plan.event_id}.txt").is_file()
    assert len(formal_hashes(root)) == 10
    assert {p: sha256_file(p) for p in sources} == hashes
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert len(formal_hashes(root)) == 10


def test_merge_requires_exactly_one_confirmed_copy_per_instrument(tmp_path):
    db, root, ids, _ = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    before = formal_hashes(root)
    all_ids = [int(r["id"]) for r in db.list_archived_scores()]
    with pytest.raises(ValueError, match="只能保留"):
        service.build_plan(*ids, all_ids, "萤火虫之墓《可爱的家》")
    with pytest.raises(ValueError, match="只能保留"):
        service.build_plan(*ids, [], "萤火虫之墓《可爱的家》")
    assert formal_hashes(root) == before
    assert not (root / "修改备份").exists()


def test_merge_rollback_database_failure_keeps_both_songs_and_all_pdfs(tmp_path, monkeypatch):
    db, root, ids, sources = merge_fixture(tmp_path)
    before = formal_hashes(root)
    records = [dict(r) for r in db.list_archived_scores()]
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
    original = db.update_archived_song_metadata
    def fail(*args):
        original(*args)
        raise sqlite3.OperationalError("模拟登记失败")
    monkeypatch.setattr(db, "update_archived_song_metadata", fail)
    with pytest.raises(ValueError, match="已恢复"):
        service.commit_plan(plan)
    assert formal_hashes(root) == before
    assert [dict(r) for r in db.list_archived_scores()] == records
    assert not list(plan.duplicate_root.rglob("*.pdf"))
    assert not (plan.duplicate_root / f"合并确认说明_{plan.event_id}.txt").exists()
    assert db.allocate_next_sequence() == "001"
    assert all(p.is_file() for p in sources)


def test_merge_rollback_output_failure_and_retry(tmp_path, monkeypatch):
    db, root, ids, _ = merge_fixture(tmp_path)
    before = formal_hashes(root)
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
    original = shutil.copyfileobj
    failed = False
    def fail_once(source, destination, *args):
        nonlocal failed
        if not failed and "按曲目分类" in str(destination.name):
            failed = True
            raise OSError("模拟文件写入失败")
        return original(source, destination, *args)
    monkeypatch.setattr(shutil, "copyfileobj", fail_once)
    with pytest.raises(ValueError, match="已恢复"):
        service.commit_plan(plan)
    assert formal_hashes(root) == before
    assert len(db.list_archived_scores()) == 6
    service.commit_plan(service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》"))
    assert len(db.list_archived_scores()) == 5


def test_supplement_after_merge_keeps_number_and_full_clear_restore_keeps_reservation(tmp_path):
    db, root, ids, _ = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    service.commit_plan(service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》"))
    pdf = tmp_path / "0701萤火虫之墓《可爱的家》.pdf"
    pdf.write_bytes(b"%PDF new percussion")
    batch = ImportService(db).import_paths([pdf])
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch)
    assert len(plans) == 1 and plans[0].seq == "007"
    archive.commit_plan(batch, plans)
    assert len(db.list_archived_scores()) == 6
    assert db.allocate_next_sequence() == "001"
    before = formal_hashes(root)
    reset = ResetService(db)
    backup = reset.clear(reset.build_plan())
    reset.restore(backup)
    assert formal_hashes(root) == before
    assert len(db.list_archived_scores()) == 6
    assert db.allocate_next_sequence() == "001"
    assert db.get_archived_song(ids[1]) is None


def test_preview_metadata_change_blocks_before_file_changes(tmp_path):
    db, root, ids, _ = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
    with db.session() as c:
        c.execute("UPDATE song SET display_title=display_title || ' ' WHERE id=?", (ids[1],))
    before = formal_hashes(root)
    with pytest.raises(ValueError, match="变化"):
        service.commit_plan(plan)
    assert formal_hashes(root) == before


def test_target_directory_with_unrelated_files_is_not_merged(tmp_path):
    db, root, ids, _ = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
    path = next(f.new_song for f in plan.files if f.score_id in plan.selected_score_ids).parent / "other.pdf"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(b"unrelated")
    with pytest.raises(ValueError, match="目录已存在"):
        service.commit_plan(plan)
    assert path.read_bytes() == b"unrelated"


@pytest.mark.parametrize("damage", ["changed", "missing", "untracked", "collision", "source_reference"])
def test_merge_blocks_unsafe_files_without_modification(tmp_path, damage):
    db, root, ids, _ = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
    if damage == "changed":
        plan.files[0].old_song.write_bytes(b"external change")
    elif damage == "missing":
        plan.files[0].old_instrument.unlink()
    elif damage == "untracked":
        (plan.files[0].old_song.parent / "unknown.pdf").write_bytes(b"unknown")
    elif damage == "collision":
        plan.files[0].new_instrument.parent.mkdir(parents=True, exist_ok=True)
        plan.files[0].new_instrument.write_bytes(b"existing target")
    else:
        batch = db.create_import_batch()
        db.add_import_item(batch, str(plan.files[0].old_song), plan.files[0].sha256, "recognized", {})
    before = formal_hashes(root)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert formal_hashes(root) == before
    assert len(db.list_archived_scores()) == 6


@pytest.mark.parametrize("change", ["version", "category", "title"])
def test_merge_rejects_different_version_category_or_unrelated_title(tmp_path, change):
    db, root, ids, _ = merge_fixture(tmp_path)
    with db.session() as c:
        if change == "version":
            c.execute("UPDATE song SET version_no=2 WHERE id=?", (ids[1],))
        elif change == "category":
            c.execute("UPDATE song SET category_id=(SELECT id FROM category WHERE code='02') WHERE id=?", (ids[1],))
        else:
            c.execute("UPDATE song SET display_title='另一首' WHERE id=?", (ids[1],))
    with pytest.raises(ValueError):
        CatalogMergeService(db, root).build_plan(*ids, selected_ids(db, ids), "萤火虫之墓《可爱的家》")
