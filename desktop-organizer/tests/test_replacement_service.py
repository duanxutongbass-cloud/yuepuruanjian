import json
import shutil
from pathlib import Path

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.replacement_service import ReplacementService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.reset_service import ResetService
from score_organizer.storage.database import Database


def replacement_fixture(tmp_path, special=False):
    db = Database(tmp_path / "replacement.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    originals = []
    for code in (["08"] if special else ["01", "02", "03"]):
        source = tmp_path / f"{code}01007天空之城.pdf"
        source.write_bytes(f"%PDF old {code}".encode())
        originals.append(source)
    archive = ArchiveService(db, root)
    first = ImportService(db).import_paths(originals)
    archive.commit_plan(first, archive.build_plan(first))
    incoming = tmp_path / f"{'08' if special else '01'}02新版天空之城.pdf"
    incoming.write_bytes(b"%PDF better copy")
    unrelated = tmp_path / "0602另一作品.pdf"
    unrelated.write_bytes(b"%PDF unrelated")
    batch = ImportService(db).import_paths([incoming, unrelated])
    item_id = int(db.list_import_items(batch)[0]["id"])
    score_id = int(db.list_archived_scores()[0]["id"])
    return db, root, batch, item_id, score_id, originals + [incoming, unrelated]


@pytest.mark.parametrize("special", [False, True])
def test_replace_preserves_number_layout_originals_and_stores_old_once(tmp_path, special):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path, special)
    source_hashes = {path: sha256_file(path) for path in sources}
    untouched = dict(db.list_import_items(batch)[1])
    old = dict(db.list_archived_scores()[0])
    service = ReplacementService(db, root)
    plan = service.build_plan(batch, {item_id: score_id})
    backup, remaining = service.commit_plan(plan)
    assert remaining == 1 and [dict(row) for row in db.list_import_items(batch)] == [untouched]
    assert db.count_songs() == 1 and db.allocate_next_sequence() == "001"
    record = dict(db.list_archived_scores()[0])
    assert record["id"] == old["id"] and record["seq"] == "007" and record["storage_layout"] == old["storage_layout"]
    assert record["source_sha256"] == sha256_file(sources[-2])
    assert all(sha256_file(path) == record["source_sha256"] for path in plan.rows[0].targets)
    duplicate_pdfs = list((root / "已确认重复").rglob("*.pdf"))
    assert len(duplicate_pdfs) == 1 and sha256_file(duplicate_pdfs[0]) == old["source_sha256"]
    assert "天空之城" in str(duplicate_pdfs[0]) and "重复1" in duplicate_pdfs[0].name
    assert (backup / "修改前.sqlite3").is_file() and (backup / "完成.txt").is_file()
    assert {path: sha256_file(path) for path in sources} == source_hashes
    assert db.find_scores_by_hash(old["source_sha256"]) == []
    assert db.find_scores_by_hash(record["source_sha256"])
    assert len(plan.rows[0].targets) == (1 if special else 2)
    GroupingService(db).refresh_warnings(batch)
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert len(list((root / "已确认重复").rglob("*.pdf"))) == 1


@pytest.mark.parametrize("change", ["new_pdf", "old_pdf", "item", "song", "duplicate_folder"])
def test_stale_preview_blocks_without_replacing_files(tmp_path, change):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    plan = service.build_plan(batch, {item_id: score_id})
    if change == "new_pdf":
        sources[-2].write_bytes(b"changed source")
    elif change == "old_pdf":
        plan.rows[0].targets[0].write_bytes(b"changed saved copy")
    elif change == "item":
        db.update_import_item_manual_fields(item_id, title="changed name")
    elif change == "song":
        with db.session() as connection:
            connection.execute("UPDATE song SET version_no=2 WHERE id=?", (plan.rows[0].song_id,))
    else:
        parent = root / "已确认重复" / "天空之城"
        parent.mkdir(parents=True)
        (parent / "existing（重复1）.pdf").write_bytes(b"existing duplicate")
    before = {path: path.read_bytes() for path in plan.rows[0].targets}
    with pytest.raises(ValueError):
        service.commit_plan(plan)
    assert {path: path.read_bytes() for path in before} == before
    assert len(db.list_import_items(batch)) == 2
    assert not (root / "修改备份").exists()


@pytest.mark.parametrize("failure", ["partial_library_write", "database", "duplicate_manifest", "backup"])
def test_failures_restore_both_copies_and_drafts(tmp_path, monkeypatch, failure):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    plan = service.build_plan(batch, {item_id: score_id})
    before_db = [dict(row) for row in db.list_archived_scores()]
    before = {path: path.read_bytes() for path in plan.rows[0].targets}
    if failure == "database":
        original = db.remove_import_items_from_draft
        def fail(*args):
            original(*args)
            raise OSError("injected database failure")
        monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    elif failure == "partial_library_write":
        original = shutil.copyfileobj
        def fail(source, destination, *args, **kwargs):
            if str(destination.name) == str(plan.rows[0].targets[1]) and "新谱_" in str(source.name):
                destination.write(b"partial")
                raise OSError("injected write failure")
            return original(source, destination, *args, **kwargs)
        monkeypatch.setattr(shutil, "copyfileobj", fail)
    elif failure == "duplicate_manifest":
        monkeypatch.setattr(service.duplicates.writer, "_write_manifest", lambda *args: (_ for _ in ()).throw(OSError("injected manifest failure")))
    else:
        original = shutil.copy2
        def fail(source, destination, *args, **kwargs):
            if "旧谱_" in str(destination):
                raise OSError("injected backup failure")
            return original(source, destination, *args, **kwargs)
        monkeypatch.setattr(shutil, "copy2", fail)
    with pytest.raises(ValueError, match="替换"):
        service.commit_plan(plan)
    assert [dict(row) for row in db.list_archived_scores()] == before_db
    assert {path: path.read_bytes() for path in before} == before
    assert len(db.list_import_items(batch)) == 2
    assert not list((root / "已确认重复").rglob("*.pdf"))
    assert not list((root / "修改备份").rglob("完成.txt"))


def test_replacement_refuses_wrong_instrument_and_source_referenced_output(tmp_path):
    db, root, batch, item_id, score_id, _ = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    other = db.list_archived_scores()[1]["id"]
    with pytest.raises(ValueError, match="同一种乐器"):
        service.build_plan(batch, {item_id: other})
    old_path = service.archive.score_paths(db.list_archived_scores()[0])[0]
    ImportService(db).import_paths([old_path], batch)
    with pytest.raises(ValueError, match="源PDF"):
        service.build_plan(batch, {item_id: score_id})


def test_replacement_duplicate_history_clear_restore_and_next_replace(tmp_path):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    service.commit_plan(service.build_plan(batch, {item_id: score_id}))
    reset = ResetService(db)
    backup = reset.clear(reset.build_plan())
    reset.restore(backup)
    sources[-2].write_bytes(b"%PDF even better")
    next_batch = ImportService(db).import_paths([sources[-2]])
    next_item = db.list_import_items(next_batch)[0]["id"]
    service.commit_plan(service.build_plan(next_batch, {next_item: score_id}))
    duplicates = list((root / "已确认重复").rglob("*.pdf"))
    assert len(duplicates) == 2 and any("重复2" in path.name for path in duplicates)
    manifest = json.loads((root / "已确认重复" / "重复清单.json").read_text(encoding="utf-8"))
    assert len(manifest["items"]) == 2


def test_late_cleanup_error_never_rolls_back_files_after_database_commit(tmp_path, monkeypatch):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    plan = service.build_plan(batch, {item_id: score_id})
    original = service.duplicates.store
    def fail(*args):
        original(*args)
        raise OSError("injected post-commit cleanup failure")
    monkeypatch.setattr(service.duplicates, "store", fail)
    with pytest.raises(ValueError, match="已提交"):
        service.commit_plan(plan)
    assert len(db.list_import_items(batch)) == 1
    assert db.list_archived_scores()[0]["source_sha256"] == sha256_file(sources[-2])
    assert all(sha256_file(path) == sha256_file(sources[-2]) for path in plan.rows[0].targets)


def test_failed_second_replacement_preserves_existing_duplicate_manifest(tmp_path, monkeypatch):
    db, root, batch, item_id, score_id, _ = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    service.commit_plan(service.build_plan(batch, {item_id: score_id}))
    new_source = tmp_path / "0101最佳版本.pdf"
    new_source.write_bytes(b"%PDF next replacement")
    next_batch = ImportService(db).import_paths([new_source])
    new_id = db.list_import_items(next_batch)[0]["id"]
    plan = service.build_plan(next_batch, {new_id: score_id})
    before_duplicates = {path: path.read_bytes() for path in (root / "已确认重复").rglob("*") if path.is_file()}
    before_library = {path: path.read_bytes() for path in plan.rows[0].targets}
    monkeypatch.setattr(db, "remove_import_items_from_draft", lambda *args: (_ for _ in ()).throw(OSError("injected database failure")))
    with pytest.raises(ValueError, match="回退"):
        service.commit_plan(plan)
    assert {path: path.read_bytes() for path in (root / "已确认重复").rglob("*") if path.is_file()} == before_duplicates
    assert {path: path.read_bytes() for path in before_library} == before_library
    assert len(db.list_import_items(next_batch)) == 1


def test_persistent_restore_failure_is_blocking_and_keeps_backups(tmp_path, monkeypatch):
    db, root, batch, item_id, score_id, _ = replacement_fixture(tmp_path)
    service = ReplacementService(db, root)
    plan = service.build_plan(batch, {item_id: score_id})
    original = shutil.copyfileobj
    def fail(source, destination, *args, **kwargs):
        if str(destination.name) == str(plan.rows[0].targets[1]):
            destination.write(b"partial")
            raise OSError("persistent write failure")
        return original(source, destination, *args, **kwargs)
    monkeypatch.setattr(shutil, "copyfileobj", fail)
    with pytest.raises(ValueError, match="恢复未完成.*停止整理"):
        service.commit_plan(plan)
    backup = next((root / "修改备份").iterdir())
    assert len(list(backup.glob("旧谱_*.pdf"))) == 2
    assert all(sha256_file(path) == plan.rows[0].old_sha256 for path in backup.glob("旧谱_*.pdf"))
    assert db.list_archived_scores()[0]["source_sha256"] == plan.rows[0].old_sha256
    assert len(db.list_import_items(batch)) == 2


@pytest.mark.parametrize("fail", [False, True])
def test_batch_replacement_is_atomic_and_rejects_two_new_parts_for_same_old(tmp_path, monkeypatch, fail):
    db, root, batch, item_id, score_id, sources = replacement_fixture(tmp_path)
    extra = tmp_path / "0201天空之城.pdf"
    extra.write_bytes(b"%PDF better second violin")
    ImportService(db).import_paths([extra], batch_id=batch)
    new_id = next(row["id"] for row in db.list_import_items(batch) if row["source_path"] == str(extra))
    second_id = db.list_archived_scores()[1]["id"]
    service = ReplacementService(db, root)
    with pytest.raises(ValueError, match="只能选择一份"):
        service.build_plan(batch, {item_id: score_id, new_id: score_id})
    plan = service.build_plan(batch, {item_id: score_id, new_id: second_id})
    before = {path: path.read_bytes() for row in plan.rows for path in row.targets}
    before_records = [dict(row) for row in db.list_archived_scores()]
    originals = {path: path.read_bytes() for path in [*sources, extra]}
    if fail:
        monkeypatch.setattr(db, "remove_import_items_from_draft", lambda *args: (_ for _ in ()).throw(OSError("batch failure")))
        with pytest.raises(ValueError, match="已回退"):
            service.commit_plan(plan)
        assert [dict(row) for row in db.list_archived_scores()] == before_records
        assert {path: path.read_bytes() for path in before} == before
        assert len(db.list_import_items(batch)) == 3
        assert not list((root / "已确认重复").rglob("*.pdf"))
    else:
        _, remaining = service.commit_plan(plan)
        assert remaining == 1
        assert len(list((root / "已确认重复").rglob("*.pdf"))) == 2
        for row in plan.rows:
            assert all(sha256_file(path) == row.sha256 for path in row.targets)
        assert db.allocate_next_sequence() == "001"
    assert {path: path.read_bytes() for path in originals} == originals
