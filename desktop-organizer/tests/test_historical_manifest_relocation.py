from contextlib import closing, contextmanager
import json
import os
from pathlib import Path
import sqlite3

import pytest

from score_organizer.services import historical_manifests
from score_organizer.services.duplicate_layout import DuplicateLayoutStore
from score_organizer.services.duplicate_service import ConfirmedDuplicateService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.reset_service import ResetService
from score_organizer.services.review_service import ReviewService
from score_organizer.services.unused_service import UnusedScoreService
from score_organizer.storage.database import Database
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards
from test_library_upgrade_snapshot import make_library, contents, db_state


class CandidateDatabase(Database):
    """Test-only format-1 adapter for exercising unchanged real service methods.

    No product entry point uses this adapter; it checks the complete write guard
    at each transaction and retains normal reuse and rollback semantics.
    """
    def connect(self):
        connection = sqlite3.connect(self.path, uri=True)
        connection.row_factory = sqlite3.Row
        register_format_one_writer(connection)
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    @contextmanager
    def session(self):
        if self._transaction_connection is not None:
            yield self._transaction_connection
            return
        with closing(self.connect()) as connection:
            connection.execute("BEGIN")
            require_format_one_guards(connection)
            try:
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise

    @contextmanager
    def transaction(self, *, reuse=False):
        if self._transaction_connection is not None:
            if reuse:
                yield self._transaction_connection
                return
            raise ValueError("已有事务")
        with closing(self.connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            require_format_one_guards(connection)
            self._transaction_connection = connection
            try:
                yield connection
                connection.commit()
            except BaseException:
                connection.rollback()
                raise
            finally:
                self._transaction_connection = None


def pending_library(tmp_path, kind="review"):
    db, root, archived_originals = make_library(tmp_path)
    originals = []
    for number in range(2):
        path = tmp_path / f"pending{number}" / "0101待处理.pdf"
        path.parent.mkdir()
        path.write_bytes(f"%PDF pending independent {number}".encode())
        originals.append(path)
    batch = ImportService(db).import_paths(originals)
    service_type = {"review": ReviewService, "legacy_duplicate": ConfirmedDuplicateService, "unused": UnusedScoreService}[kind]
    service = service_type(db, root)
    if kind == "review":
        package, remaining = service.commit_plan(batch, service.build_plan(batch))
    elif kind == "legacy_duplicate":
        plan = [{"item_id": row["id"], "source_path": row["source_path"], "source_sha256": row["source_sha256"],
                 "status": row["status"], "parsed_fields": json.loads(row["parsed_fields"]), "reasons": ["用户已确认重复"],
                 "details": ["原始来源保留"], "relative_path": f"分谱{row['id']:06d}/{Path(row['source_path']).name}", "restored": False}
                for row in db.list_import_items(batch)]
        package, remaining = service._store_plan(batch, plan)
    else:
        plan = service.build_plan(batch, [row["id"] for row in db.list_import_items(batch)])
        package, remaining = service.commit_plan(batch, plan)
    assert remaining == 0
    return db, root, originals, package, service_type, archived_originals


@pytest.mark.parametrize("kind", ["review", "legacy_duplicate", "unused"])
def test_actual_list_and_restore_only_use_new_tree_after_old_clear(tmp_path, kind):
    db, root, originals, package, service_type, archived = pending_library(tmp_path, kind)
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    manifest = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert manifest["historicalPendingFilesVerified"]
    proof = manifest["historicalPendingManifestProof"]
    assert proof["pdfsVerified"] == 2 and not proof["externalProvenanceFilesOpened"]
    assert not proof["clearBackupCompatibilityVerified"] and not manifest["readyForActivation"]
    assert db_state(db.path) == before
    assert contents(root) == contents(target / "snapshot/files") == contents(target / "runtime/files") == tree
    # Only synthetic files are moved. Their old provenance strings remain as
    # evidence while the real old-format clear moves all old pending packages.
    renamed = []
    for source in originals:
        new = source.with_suffix(".retained")
        source.rename(new)
        renamed.append((new, sha256_file(new)))
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    future = CandidateDatabase(target / "runtime/library.sqlite3")
    service = service_type(future, target / "runtime/files")
    entries = service.list_pending()
    assert len(entries) == 2 and all(entry["pdf_path"].is_relative_to(target / "runtime/files") for entry in entries)
    assert all(not Path(entry["source_path"]).exists() for entry in entries)
    restored_batch = service.restore_to_draft([entries[0]])
    rows = future.list_import_items(restored_batch)
    assert len(rows) == 1 and Path(rows[0]["source_path"]) == entries[0]["pdf_path"]
    assert rows[0]["source_sha256"] == sha256_file(entries[0]["pdf_path"])
    assert len(service.list_pending()) == 1
    service.restore_to_draft([entries[0]])  # Idempotent retry must not duplicate it.
    assert len(future.list_import_items(restored_batch)) == 1
    assert all(sha256_file(path) == digest for path, digest in renamed)
    assert all(path.is_file() for path in archived)
    assert contents(target / "snapshot/files") == tree
    with pytest.raises(ValueError, match="不支持"):
        Database(future.path).connect()


def flat_library(tmp_path):
    db, root, originals = make_library(tmp_path)
    source = tmp_path / "已脱离草稿的来源.pdf"
    source.write_bytes(b"%PDF duplicate flat retained content")
    store = DuplicateLayoutStore(db, root)
    store.store([dict(source_path=str(source), source_sha256=sha256_file(source), display_title="曲目",
                     parsed_fields={"instrument_code": "01", "category_code": "01"}, reasons=["人工确认"], details=["来源审计"])],
                lambda: None, lambda: None)
    return db, root, source


def test_flat_duplicate_global_and_song_manifest_survive_old_clear_and_allow_append(tmp_path):
    db, root, source = flat_library(tmp_path)
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert proof["historicalPendingManifestProof"]["duplicateLayoutItems"] == 1
    old_hash = sha256_file(source)
    source.rename(source.with_suffix(".retained"))
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    future = CandidateDatabase(target / "runtime/library.sqlite3")
    store = DuplicateLayoutStore(future, target / "runtime/files")
    old_manifest = store._manifest()
    old_pdf = store.root / old_manifest["items"][0]["relative_path"]
    assert sha256_file(old_pdf) == old_hash
    new_source = tmp_path / "新增来源.pdf"
    new_source.write_bytes(b"%PDF new synthetic duplicate")
    store.store([dict(source_path=str(new_source), source_sha256=sha256_file(new_source), display_title="曲目",
                     parsed_fields={"instrument_code": "01", "category_code": "01"}, reasons=["人工确认"], details=[])],
                lambda: None, lambda: None)
    assert len(store._manifest()["items"]) == 2 and sha256_file(old_pdf) == old_hash
    assert store._manifest()["items"][0] == old_manifest["items"][0]


def test_legacy_merge_absolute_coordinate_pair_resolves_only_inside_new_package(tmp_path):
    db, root, _ = make_library(tmp_path)
    package = root / "已确认重复" / ("曲目合并_" + "1" * 32)
    package.mkdir(parents=True)
    pdf = package / "旧重复谱.pdf"
    pdf.write_bytes(b"%PDF legacy merged copy")
    merge = dict(duplicate_root=str(package), original_records=[dict(id=123, source_path=str(tmp_path / "missing-original.pdf"))],
                 files=[dict(score_id=123, instrument_code="01", new_instrument=str(pdf), new_song=None, sha256=sha256_file(pdf))],
                 selected_score_ids=[], title="旧合并曲目", category="01")
    (package / "合并对比记录.json").write_text(json.dumps(merge, ensure_ascii=False), encoding="utf-8")
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert proof["historicalPendingManifestProof"]["legacyDuplicatePlanPackages"] == 1
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    store = DuplicateLayoutStore(CandidateDatabase(target / "runtime/library.sqlite3"), target / "runtime/files")
    plan = store.legacy_plan()
    assert len(plan["entries"]) == 1
    assert Path(plan["entries"][0]["copy_source"]).is_relative_to(target / "runtime/files")
    assert Path(plan["entries"][0]["copy_source"]).read_bytes() == b"%PDF legacy merged copy"
    assert plan["entries"][0]["source_path"] == merge["original_records"][0]["source_path"]


@pytest.mark.parametrize("damage", ["version", "duplicate_key", "absolute", "escape", "hash", "missing_pdf", "orphan", "restored", "fields", "duplicate_target", "hidden_stage"])
def test_invalid_review_packages_never_publish_a_prepared_candidate(tmp_path, damage):
    db, root, _, package, _, _ = pending_library(tmp_path)
    path = package / "审查清单.json"
    value = json.loads(path.read_text("utf-8"))
    if damage == "version": value["version"] = 99
    elif damage == "absolute": value["items"][0]["relative_path"] = str(package / value["items"][0]["relative_path"])
    elif damage == "escape": value["items"][0]["relative_path"] = "../outside.pdf"
    elif damage == "hash": value["items"][0]["source_sha256"] = "f" * 64
    elif damage == "missing_pdf": (package / value["items"][0]["relative_path"]).unlink()
    elif damage == "orphan": (package / "未登记.pdf").write_bytes(b"%PDF orphan")
    elif damage == "restored": value["items"][0]["restored"] = "false"
    elif damage == "fields": value["items"][0].pop("parsed_fields")
    elif damage == "duplicate_target": value["items"].append(dict(value["items"][0]))
    elif damage == "hidden_stage":
        renamed = package.with_name("." + package.name)
        package.rename(renamed)
        package, path = renamed, renamed / "审查清单.json"
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    if damage == "duplicate_key":
        path.write_text(path.read_text("utf-8").replace('"version": 1', '"version": 99, "version": 1'), encoding="utf-8")
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    with pytest.raises((ValueError, FileNotFoundError)):
        prepare_relocated_library(db.path, target)
    assert not (target / "prepared.json").exists() and (target / "failed.json").exists()
    assert db_state(db.path) == before and contents(root) == tree


@pytest.mark.parametrize("damage", ["global_missing", "song_missing", "song_changed", "orphan"])
def test_flat_duplicate_manifest_disagreement_blocks_preparation(tmp_path, damage):
    db, root, _ = flat_library(tmp_path)
    folder = root / "已确认重复"
    if damage == "global_missing": (folder / "重复清单.json").unlink()
    elif damage == "song_missing": (folder / "曲目/重复清单.json").unlink()
    elif damage == "song_changed": (folder / "曲目/重复清单.json").write_text('{"version":2,"items":[]}', encoding="utf-8")
    else: (folder / "未登记.pdf").write_bytes(b"%PDF orphan")
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    with pytest.raises((ValueError, FileNotFoundError)):
        prepare_relocated_library(db.path, target)
    assert not (target / "prepared.json").exists()
    assert db_state(db.path) == before and contents(root) == tree


def test_empty_unused_directory_is_portable_without_inventing_a_manifest(tmp_path):
    db, root, _ = make_library(tmp_path)
    (root / "暂时无用乐谱").mkdir()
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert proof["historicalPendingManifestProof"]["unusedItems"] == 0
    assert not (target / "runtime/files/暂时无用乐谱/暂存清单.json").exists()


def test_already_restored_items_keep_state_but_their_copies_are_still_verified(tmp_path):
    db, _, _, package, _, _ = pending_library(tmp_path)
    path = package / "审查清单.json"
    value = json.loads(path.read_text("utf-8"))
    value["items"][0]["restored"] = True
    path.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    target = tmp_path / "candidate"
    proof = json.loads(prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert proof["historicalPendingManifestProof"]["reviewItems"] == 2
    service = ReviewService(CandidateDatabase(target / "runtime/library.sqlite3"), target / "runtime/files")
    assert len(service.list_pending()) == 1
    assert (target / "runtime/files" / path.relative_to(package.parent.parent)).read_bytes() == path.read_bytes()


@pytest.mark.parametrize("damage", ["manifest", "pdf", "extra_pdf"])
def test_late_pending_change_after_validation_prevents_completion(tmp_path, damage):
    db, root, _, package, _, _ = pending_library(tmp_path)
    target = tmp_path / "candidate"
    reached = []
    def mutate(stage):
        if stage != "historical_manifests_verified": return
        reached.append(stage)
        copied = target / "runtime/files" / package.relative_to(root)
        if damage == "manifest":
            path = copied / "审查清单.json"
            info = path.stat()
            raw = path.read_bytes()
            path.write_bytes(raw.replace(b'false', b' true', 1))
            os.utime(path, ns=(info.st_atime_ns, info.st_mtime_ns))
        elif damage == "pdf": next(copied.rglob("*.pdf")).write_bytes(b"%PDF late corruption")
        else: (copied / "extra.pdf").write_bytes(b"%PDF extra")
    with pytest.raises(ValueError):
        prepare_relocated_library(db.path, target, progress=mutate)
    assert reached == ["historical_manifests_verified"] and not (target / "prepared.json").exists()


def test_failed_actual_restore_rolls_back_candidate_rows_and_keeps_copies(tmp_path, monkeypatch):
    db, _, _, _, _, _ = pending_library(tmp_path)
    target = tmp_path / "candidate"
    prepare_relocated_library(db.path, target)
    future = CandidateDatabase(target / "runtime/library.sqlite3")
    service = ReviewService(future, target / "runtime/files")
    entries = service.list_pending()
    before, tree = db_state(future.path), contents(target / "runtime/files")
    def fail(*args): raise OSError("synthetic manifest write failure")
    monkeypatch.setattr(service, "_write_manifest", fail)
    with pytest.raises(OSError, match="manifest write"):
        service.restore_to_draft(entries)
    assert db_state(future.path) == before and contents(target / "runtime/files") == tree


def test_validation_database_adapter_cannot_write_the_pristine_backup(tmp_path):
    db, _, _ = make_library(tmp_path)
    before = db_state(db.path)
    snapshot = historical_manifests._ReadOnlySnapshot(db.path)
    with snapshot.session() as connection:
        with pytest.raises(sqlite3.OperationalError):
            connection.execute("UPDATE settings SET value='changed'")
    assert db_state(db.path) == before
