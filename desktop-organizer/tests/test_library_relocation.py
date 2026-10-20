from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest

from score_organizer.services import library_relocation as relocation
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.grouping_service import warning_fingerprint
from score_organizer.services.reset_service import ResetService
from score_organizer.storage.database import Database
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards
from test_library_upgrade_snapshot import make_library, contents, db_state


def read_db(path, sql, args=()):
    with closing(sqlite3.connect(path)) as db:
        return db.execute(sql, args).fetchall()


def test_relocated_runtime_keeps_pristine_snapshot_and_survives_real_old_clear(tmp_path):
    db, root, originals = make_library(tmp_path)
    db.set_setting("last_reset_backup", str(root / "清空备份/旧记录"))
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "新路径候选"
    proof = json.loads(relocation.prepare_relocated_library(db.path, target).read_text("utf-8"))
    candidate = target / proof["runtimeDatabase"]
    assert proof["sourcePathsRebased"] and proof["warningLocatorPathsRebased"]
    assert proof["operationHistoryUnchanged"] and proof["originalSourceReferencesCopied"]
    assert not proof["readyForActivation"] and not proof["historicalFileManifestsRebased"]
    assert proof["warningFingerprintsReconciled"]
    assert proof["previousResetBackup"] == str(root / "清空备份/旧记录")
    assert read_db(candidate, "SELECT value FROM settings WHERE key='last_reset_backup'") == [("",)]
    assert read_db(candidate, "SELECT value FROM settings WHERE key='output_root'") == [(str(target / "runtime/files"),)]
    assert read_db(candidate, "PRAGMA user_version") == [(1,)]
    assert db_state(db.path) == db_state(target / "snapshot/database/before.sqlite3") == before
    assert contents(root) == contents(target / "snapshot/files") == contents(target / "runtime/files") == tree
    assert read_db(candidate, "SELECT * FROM operation_log") == read_db(db.path, "SELECT * FROM operation_log")
    assert read_db(candidate, "SELECT * FROM song") == read_db(db.path, "SELECT * FROM song")
    for original in originals:
        path = Path(proof["sourcePaths"][str(original)]["path"])
        assert path.read_bytes() == original.read_bytes()
        assert path.name == original.name and path.parent.parent == target / "runtime/sources"
        assert not os.path.samefile(path, original) and path.stat().st_nlink == 1
        assert proof["sourcePaths"][str(original)]["sha256"] == sha256_file(path)
    old_origins = {p: p.read_bytes() for p in originals}
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    assert db.count_scores() == 0 and not (root / "按曲目分类").exists()
    assert contents(target / "runtime/files") == tree
    assert {p: p.read_bytes() for p in originals} == old_origins
    assert read_db(candidate, "SELECT count(*) FROM score") == [(3,)]
    with closing(sqlite3.connect(candidate)) as check:
        check.execute("BEGIN")
        require_format_one_guards(check)
        with pytest.raises(sqlite3.OperationalError):
            check.execute("UPDATE settings SET value='old-writer'")


def test_drafts_and_warning_paths_are_rebased_but_descriptive_audit_is_verbatim(tmp_path):
    db, _, originals = make_library(tmp_path)
    source = tmp_path / "草稿目录/0101未归档.pdf"
    source.parent.mkdir()
    source.write_bytes(b"%PDF draft with independent content")
    batch = ImportService(db).import_paths([source])
    description = f"这是历史说明：{source}"
    details = json.dumps({"source_paths": [str(source)], "existing": [{"source_path": str(originals[0]),
        "title": str(source), "instrument_code": "01", "output_name": "旧名.pdf"}], "description": description}, ensure_ascii=False)
    with db.transaction() as connection:
        connection.execute("INSERT INTO warning(batch_id,code,fingerprint,severity,state,details) VALUES(?,?,?,?,?,?)",
                           (batch, "test", warning_fingerprint("test", json.loads(details)), "warning", "待处理", details))
    target = tmp_path / "candidate"
    proof = json.loads(relocation.prepare_relocated_library(db.path, target).read_text("utf-8"))
    candidate = target / "runtime/library.sqlite3"
    actual, fingerprint = read_db(candidate, "SELECT details,fingerprint FROM warning WHERE code='test'")[0]
    actual = json.loads(actual)
    assert actual["source_paths"] == [proof["sourcePaths"][str(source)]["path"]]
    assert actual["existing"][0]["source_path"] == proof["sourcePaths"][str(originals[0])]["path"]
    assert actual["existing"][0]["title"] == str(source) and actual["description"] == description
    assert fingerprint == warning_fingerprint("test", actual)
    assert proof["warningFingerprintsReconciled"] and proof["warningFingerprintChanges"]
    assert read_db(candidate, "SELECT source_path FROM import_item WHERE batch_id=?", (batch,)) == [(actual["source_paths"][0],)]
    assert read_db(db.path, "SELECT details FROM warning WHERE code='test'") == [(details,)]


def test_source_inside_old_output_gets_separate_original_copy(tmp_path):
    db, root, _ = make_library(tmp_path)
    source = next((root / "按曲目分类").rglob("*.pdf"))
    batch = ImportService(db).import_paths([source])
    target = tmp_path / "candidate"
    proof = json.loads(relocation.prepare_relocated_library(db.path, target).read_text("utf-8"))
    new = Path(proof["sourcePaths"][str(source)]["path"])
    assert new.is_relative_to(target / "runtime/sources") and new.read_bytes() == source.read_bytes()
    assert read_db(target / "runtime/library.sqlite3", "SELECT source_path FROM import_item WHERE batch_id=?", (batch,)) == [(str(new),)]


def test_same_named_different_sources_do_not_collide(tmp_path):
    db = Database(tmp_path / "library.sqlite3")
    db.initialize()
    paths = []
    for number in range(2):
        source = tmp_path / str(number) / "0101同名.pdf"
        source.parent.mkdir()
        source.write_bytes(f"%PDF synthetic {number}".encode())
        paths.append(source)
    ImportService(db).import_paths(paths)
    proof = json.loads(relocation.prepare_relocated_library(db.path, tmp_path / "candidate").read_text("utf-8"))
    assert len({value["path"] for value in proof["sourcePaths"].values()}) == 2
    assert all(Path(proof["sourcePaths"][str(path)]["path"]).read_bytes() == path.read_bytes() for path in paths)


@pytest.mark.parametrize("damage", ["missing", "changed", "relative", "no_hash", "conflicting_hash", "non_pdf"])
def test_unverifiable_originals_block_without_mutating_the_library(tmp_path, damage):
    db, root, originals = make_library(tmp_path)
    if damage == "missing":
        originals[0].unlink()
    elif damage == "changed":
        originals[0].write_bytes(b"%PDF original changed after archival")
    else:
        # Archival consumes its import items; create a real new draft before
        # damaging that row, otherwise this never exercises the migration.
        ImportService(db).import_paths([originals[0]])
        with db.transaction() as connection:
            item = connection.execute("SELECT id FROM import_item ORDER BY id LIMIT 1").fetchone()[0]
            if damage == "relative":
                connection.execute("UPDATE import_item SET source_path='relative.pdf' WHERE id=?", (item,))
            elif damage == "no_hash":
                connection.execute("UPDATE import_item SET source_sha256=NULL WHERE id=?", (item,))
            elif damage == "non_pdf":
                connection.execute("UPDATE import_item SET source_path=? WHERE id=?", (str(tmp_path / "text.txt"), item))
            else:
                connection.execute("UPDATE import_item SET source_sha256=? WHERE id=?", ("f" * 64, item))
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    with pytest.raises((ValueError, FileNotFoundError)):
        relocation.prepare_relocated_library(db.path, target)
    assert db_state(db.path) == before and contents(root) == tree
    assert not (target / "prepared.json").exists() and (target / "failed.json").exists()


@pytest.mark.parametrize("details", ["[]", "{bad json", '{"source_paths":"not-list"}', '{"source_paths":["unknown.pdf"]}',
                                     '{"existing":["bad"]}', '{"existing":[{"source_path":"unknown.pdf"}]}'])
def test_invalid_warning_locators_are_not_silently_left_active(tmp_path, details):
    db, root, _ = make_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("INSERT INTO warning(code,severity,state,details) VALUES('test','warning','待处理',?)", (details,))
    before = db_state(db.path)
    target = tmp_path / "candidate"
    with pytest.raises(ValueError):
        relocation.prepare_relocated_library(db.path, target)
    assert db_state(db.path) == before and not (target / "prepared.json").exists()
    # The new database transaction rolled back, including all source pointers.
    candidate = target / "runtime/library.sqlite3"
    assert read_db(candidate, "SELECT source_path FROM score") == read_db(db.path, "SELECT source_path FROM score")


@pytest.mark.parametrize("change", ["original", "original_same_metadata", "copy", "copy_hardlink", "extra_copy", "database", "snapshot", "manifest",
                                   "source_database", "old_output", "runtime_output", "database_wal", "snapshot_wal", "manifest_hardlink"])
def test_late_changes_never_publish_a_completed_candidate(tmp_path, change):
    db, root, originals = make_library(tmp_path)
    target = tmp_path / "candidate"
    reached = []
    def modify(stage):
        if stage != "runtime_verified":
            return
        reached.append(stage)
        if change.startswith("original"):
            info = originals[0].stat()
            originals[0].write_bytes(b"x" * info.st_size)
            if change == "original_same_metadata":
                os.utime(originals[0], ns=(info.st_atime_ns, info.st_mtime_ns))
        elif change in ("database", "source_database"):
            path = db.path if change == "source_database" else target / "runtime/library.sqlite3"
            with closing(sqlite3.connect(path)) as writer:
                register_format_one_writer(writer)
                writer.execute("UPDATE settings SET value='wrong'")
                writer.commit()
        elif change == "old_output":
            (root / "用户说明.txt").write_text("changed")
        elif change == "runtime_output":
            (target / "runtime/files/用户说明.txt").write_text("changed")
        elif change in ("database_wal", "snapshot_wal"):
            path = target / ("runtime/library.sqlite3" if change == "database_wal" else "snapshot/database/before.sqlite3")
            Path(str(path) + "-wal").write_bytes(b"uncovered sidecar")
        elif change == "manifest_hardlink":
            os.link(target / "snapshot/prepared.json", target / "linked-manifest.json")
        elif change == "snapshot":
            with closing(sqlite3.connect(target / "snapshot/database/before.sqlite3")) as writer:
                writer.execute("INSERT INTO settings VALUES('late','change')")
                writer.commit()
        elif change == "manifest":
            (target / "snapshot/prepared.json").write_text("{}")
        elif change == "extra_copy":
            (target / "runtime/sources/extra.pdf").write_bytes(b"%PDF extra")
        else:
            copied = next((target / "runtime/sources").rglob("*.pdf"))
            if change == "copy_hardlink":
                original = next(path for path in originals if path.name == copied.name)
                copied.unlink()
                os.link(original, copied)
            else:
                copied.write_bytes(b"%PDF tampered copy")
    with pytest.raises(ValueError):
        relocation.prepare_relocated_library(db.path, target, progress=modify)
    assert reached == ["runtime_verified"] and not (target / "prepared.json").exists()
    assert (target / "failed.json").exists()


def test_custom_trigger_side_effect_is_detected_and_transaction_rolled_back(tmp_path):
    db, _, _ = make_library(tmp_path)
    with db.transaction() as connection:
        connection.execute("CREATE TRIGGER path_side_effect AFTER UPDATE OF source_path ON score BEGIN UPDATE song SET display_title='unexpected'; END")
    target = tmp_path / "candidate"
    with pytest.raises(ValueError, match="其他记录"):
        relocation.prepare_relocated_library(db.path, target)
    assert read_db(target / "runtime/library.sqlite3", "SELECT * FROM song") == read_db(db.path, "SELECT * FROM song")


def test_empty_library_has_new_explicit_output_and_keeps_default_numbering_inactive(tmp_path):
    db = Database(tmp_path / "empty.sqlite3")
    db.initialize()
    target = tmp_path / "candidate"
    proof = json.loads(relocation.prepare_relocated_library(db.path, target).read_text("utf-8"))
    assert proof["sourcePaths"] == {} and proof["directories"] == ["."]
    assert read_db(target / "runtime/library.sqlite3", "SELECT value FROM settings WHERE key='output_root'") == [(str(target / "runtime/files"),)]
    assert read_db(db.path, "PRAGMA user_version") == [(0,)]


@pytest.mark.parametrize("location", ["existing", "inside_output", "ancestor_output"])
def test_destination_cannot_overwrite_or_interfere_with_original_output(tmp_path, location):
    db, root, _ = make_library(tmp_path)
    target = tmp_path / "exists" if location == "existing" else (root / "bad" if location == "inside_output" else tmp_path)
    if location == "existing":
        target.mkdir()
        (target / "keep.txt").write_text("keep")
    tree = contents(tmp_path)
    with pytest.raises(ValueError):
        relocation.prepare_relocated_library(db.path, target)
    assert contents(tmp_path) == tree


def test_subprocess_exit_before_rebase_commit_does_not_change_original_or_publish(tmp_path):
    db, root, _ = make_library(tmp_path)
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    program = """import os,sys
from score_organizer.services.library_relocation import prepare_relocated_library
def stop(stage):
    if stage == 'paths_rebased_before_commit': os._exit(74)
prepare_relocated_library(sys.argv[1], sys.argv[2], progress=stop)
"""
    result = subprocess.run([sys.executable, "-c", program, str(db.path), str(target)], env=os.environ.copy())
    assert result.returncode == 74
    assert db_state(db.path) == before and contents(root) == tree
    assert not (target / "prepared.json").exists()
    assert read_db(target / "runtime/library.sqlite3", "SELECT source_path FROM score") == read_db(db.path, "SELECT source_path FROM score")


def test_external_source_directory_junction_is_refused(tmp_path):
    db, _, originals = make_library(tmp_path)
    link = tmp_path / "linked-source"
    if os.name == "nt":
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(tmp_path)], capture_output=True, timeout=15)
        assert result.returncode == 0, result.stderr.decode(errors="replace")
    else:
        link.symlink_to(tmp_path, target_is_directory=True)
    try:
        batch = db.create_import_batch()
        db.add_import_item(batch, str(link / originals[0].name), sha256_file(originals[0]), "recognized", {})
        target = tmp_path / "candidate"
        with pytest.raises(ValueError, match="链接|联接"):
            relocation.prepare_relocated_library(db.path, target)
        assert not (target / "prepared.json").exists()
        assert originals[0].is_file()
    finally:
        if os.name == "nt":
            link.rmdir()
        else:
            link.unlink()


def test_source_copy_disk_failure_keeps_originals_and_failure_evidence(tmp_path, monkeypatch):
    db, root, originals = make_library(tmp_path)
    before, tree = db_state(db.path), contents(root)
    real_read = relocation._read_hash
    reached = []
    def fail(path, expected, destination=None):
        if destination:
            reached.append(path)
            destination.write_bytes(b"partial source copy")
            raise OSError("injected disk full")
        return real_read(path, expected)
    monkeypatch.setattr(relocation, "_read_hash", fail)
    target = tmp_path / "candidate"
    with pytest.raises(OSError, match="disk full"):
        relocation.prepare_relocated_library(db.path, target)
    assert len(reached) == 1
    assert db_state(db.path) == before and contents(root) == tree
    assert all(path.is_file() for path in originals)
    assert (target / "failed.json").exists() and not (target / "prepared.json").exists()


def test_final_manifest_failure_cannot_publish_completion(tmp_path, monkeypatch):
    db, root, _ = make_library(tmp_path)
    before, tree = db_state(db.path), contents(root)
    target = tmp_path / "candidate"
    real_write = relocation._write_json_exclusive
    reached = []
    def fail(path, value):
        if path == target / "prepared.json":
            reached.append(path)
            raise OSError("injected manifest failure")
        return real_write(path, value)
    monkeypatch.setattr(relocation, "_write_json_exclusive", fail)
    with pytest.raises(OSError, match="manifest failure"):
        relocation.prepare_relocated_library(db.path, target)
    assert reached == [target / "prepared.json"]
    assert db_state(db.path) == before and contents(root) == tree
    assert not (target / "prepared.json").exists() and (target / "failed.json").exists()
