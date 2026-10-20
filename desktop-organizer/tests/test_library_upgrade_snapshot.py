from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.reset_service import ResetService
from score_organizer.services import number_upgrade_service as upgrade
from score_organizer.storage.database import Database
from score_organizer.storage.number_upgrade import _state


def make_library(tmp_path):
    db = Database(tmp_path / "原库.sqlite3")
    db.initialize()
    root = tmp_path / "旧整理目录"
    originals = []
    for code in ("01", "02", "03"):
        source = tmp_path / f"{code}01旧曲.pdf"
        source.write_bytes(f"%PDF-1.4\nsynthetic {code}\n%%EOF".encode())
        originals.append(source)
    batch = ImportService(db).import_paths(originals)
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch))
    db.set_setting("output_root", str(root))
    (root / "待审查/空子目录").mkdir(parents=True)
    (root / "待审查/记录.json").write_text('{"old_path":"historical value"}', encoding="utf-8")
    (root / "用户说明.txt").write_text("保留原内容", encoding="utf-8")
    return db, root, originals


def contents(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def db_state(path):
    with closing(sqlite3.connect(path)) as connection:
        return _state(connection)


def test_full_output_snapshot_is_independent_when_old_library_is_cleared(tmp_path):
    db, root, originals = make_library(tmp_path)
    before_db = db_state(db.path)
    before_files = contents(root)
    before_originals = {p: p.read_bytes() for p in originals}
    destination = tmp_path / "新候选"
    manifest_path = upgrade.prepare_library_upgrade(db.path, destination)
    manifest = json.loads(manifest_path.read_text("utf-8"))
    assert db_state(db.path) == before_db
    assert contents(root) == before_files == contents(destination / "files")
    assert manifest["archivedScoresVerified"] == 3
    assert manifest["pdfFiles"] == 6
    assert manifest["outputTreeSnapshotIncluded"]
    assert not manifest["sourcePathsRebased"] and not manifest["readyForActivation"]
    assert not manifest["originalSourceFilesOutsideOutputIncluded"]
    assert "待审查/空子目录" in manifest["directories"]
    for name, proof in manifest["files"].items():
        target = destination / "files" / name
        assert sha256_file(target) == proof["sha256"]
        assert target.stat().st_size == proof["bytes"]
        assert not os.path.samefile(root / name, target)
        assert target.stat().st_nlink == 1
    # Real existing clear operation moves the old folders; no simulated recovery
    # is required to keep the independent copies readable and byte-identical.
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    assert db.count_scores() == 0
    assert not (root / "按曲目分类").exists()
    assert contents(destination / "files") == before_files
    assert {p: p.read_bytes() for p in originals} == before_originals
    assert db_state(destination / "database/before.sqlite3") == before_db


def test_editing_old_pdf_after_preparation_cannot_change_new_copy(tmp_path):
    db, root, _ = make_library(tmp_path)
    destination = tmp_path / "candidate"
    upgrade.prepare_library_upgrade(db.path, destination)
    pdf = next(root.rglob("*.pdf"))
    copy = destination / "files" / pdf.relative_to(root)
    expected = copy.read_bytes()
    pdf.write_bytes(b"%PDF deliberately changed old output")
    assert copy.read_bytes() == expected


@pytest.mark.parametrize("damage", ["missing", "changed"])
def test_missing_or_changed_archived_copy_blocks_preparation(tmp_path, damage):
    db, root, _ = make_library(tmp_path)
    pdf = next((root / "按曲目分类").rglob("*.pdf"))
    if damage == "missing":
        pdf.unlink()
    else:
        pdf.write_bytes(b"%PDF changed")
    before = contents(root)
    with pytest.raises(ValueError, match="分谱缺失或变化"):
        upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate")
    assert contents(root) == before
    assert not (tmp_path / "candidate/prepared.json").exists()


def test_instrument_only_parts_do_not_require_a_nonexistent_second_copy(tmp_path):
    db, root, _ = make_library(tmp_path)
    song = db.create_song("02", "999", "总谱", status="archived")
    original = tmp_path / "总谱原件.pdf"
    original.write_bytes(b"%PDF full score")
    db.add_score(song, "08", original.name, str(original), sha256_file(original), "0802999总谱.pdf", "instrument_only")
    row = db.list_archived_scores(song)[0]
    target, = ArchiveService(db, root).score_paths(row)
    target.parent.mkdir(parents=True)
    target.write_bytes(original.read_bytes())
    manifest = json.loads(upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate").read_text("utf-8"))
    assert manifest["archivedScoresVerified"] == 4 and manifest["pdfFiles"] == 7


def test_empty_unconfigured_library_has_an_explicit_empty_snapshot(tmp_path):
    db = Database(tmp_path / "empty.sqlite3")
    db.initialize()
    manifest = json.loads(upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate").read_text("utf-8"))
    assert manifest["sourceOutputRoot"] is None
    assert manifest["directories"] == ["."] and manifest["files"] == {}
    assert manifest["archivedScoresVerified"] == 0


@pytest.mark.parametrize("problem", ["unset", "relative", "missing", "database_inside"])
def test_invalid_root_configuration_cannot_skip_files(tmp_path, problem):
    db, root, _ = make_library(tmp_path)
    if problem == "database_inside":
        source = root / "live.sqlite3"
        source.write_bytes(db.path.read_bytes())
    else:
        source = db.path
        db.set_setting("output_root", {"unset": "", "relative": "relative", "missing": str(tmp_path / "missing")}[problem])
    with pytest.raises(ValueError):
        upgrade.prepare_library_upgrade(source, tmp_path / "candidate")
    assert not (tmp_path / "candidate").exists()


@pytest.mark.parametrize("change", ["edit", "edit_same_metadata", "add", "delete", "rename", "new_directory"])
def test_source_tree_change_during_copy_is_rejected(tmp_path, change):
    db, root, _ = make_library(tmp_path)
    seen = []
    def change_tree(stage):
        if stage != "files_copied":
            return
        seen.append(stage)
        target = root / "用户说明.txt"
        if change == "edit":
            target.write_text("changed", encoding="utf-8")
        elif change == "edit_same_metadata":
            info = target.stat()
            target.write_bytes(b"x" * info.st_size)
            os.utime(target, ns=(info.st_atime_ns, info.st_mtime_ns))
        elif change == "add":
            (root / "added.txt").write_text("added")
        elif change == "delete":
            target.unlink()
        elif change == "rename":
            target.rename(root / "renamed.txt")
        else:
            (root / "new_directory").mkdir()
    with pytest.raises(ValueError, match="已变化|哈希不一致"):
        upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate", progress=change_tree)
    assert seen == ["files_copied"]
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert (tmp_path / "candidate/failed.json").exists()


@pytest.mark.parametrize("change", ["source_database", "copied_database", "copied_file", "hardlink_file", "source_file"])
def test_changes_after_initial_copy_validation_are_caught(tmp_path, change):
    db, root, _ = make_library(tmp_path)
    destination = tmp_path / "candidate"
    seen = []
    def change_after_check(stage):
        if stage != "files_verified":
            return
        seen.append(stage)
        if change.endswith("database"):
            path = db.path if change == "source_database" else destination / "database/before.sqlite3"
            with closing(sqlite3.connect(path)) as writer:
                writer.execute("INSERT INTO settings VALUES('late_change','keep')")
                writer.commit()
        elif change == "hardlink_file":
            target = destination / "files/用户说明.txt"
            target.unlink()
            os.link(root / "用户说明.txt", target)
        else:
            base = root if change == "source_file" else destination / "files"
            (base / "用户说明.txt").write_text("late change", encoding="utf-8")
    with pytest.raises(ValueError):
        upgrade.prepare_library_upgrade(db.path, destination, progress=change_after_check)
    assert seen == ["files_verified"]
    assert not (destination / "prepared.json").exists()


def test_existing_destination_and_nested_destination_are_rejected(tmp_path):
    db, root, _ = make_library(tmp_path)
    destination = tmp_path / "existing"
    destination.mkdir()
    (destination / "keep").write_text("keep")
    with pytest.raises(ValueError, match="不能覆盖"):
        upgrade.prepare_library_upgrade(db.path, destination)
    assert contents(destination) == {"keep": b"keep"}
    with pytest.raises(ValueError, match="必须与原整理目录分离"):
        upgrade.prepare_library_upgrade(db.path, root / "candidate")
    assert not (root / "candidate").exists()


def test_real_directory_link_is_refused_without_reading_or_copying_target(tmp_path):
    db, root, _ = make_library(tmp_path)
    outside = tmp_path / "external"
    outside.mkdir()
    (outside / "keep.txt").write_text("external")
    link = root / "linked"
    if os.name == "nt":
        result = subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(outside)], capture_output=True, timeout=15)
        assert result.returncode == 0, result.stderr.decode(errors="replace")
    else:
        link.symlink_to(outside, target_is_directory=True)
    try:
        with pytest.raises(ValueError, match="链接|联接|重解析"):
            upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate")
        assert contents(outside) == {"keep.txt": b"external"}
        assert not (tmp_path / "candidate/files").exists()
    finally:
        if os.name == "nt":
            link.rmdir()  # Remove only the synthetic junction, never its target.
        else:
            link.unlink()


def test_copy_failure_preserves_partial_candidate_and_all_originals(tmp_path, monkeypatch):
    db, root, _ = make_library(tmp_path)
    before = contents(root)
    real_read = upgrade._read_hash
    def failed_read(path, expected, destination=None):
        if destination:
            destination.write_bytes(b"partial data")
            raise OSError("injected disk full")
        return real_read(path, expected)
    monkeypatch.setattr(upgrade, "_read_hash", failed_read)
    with pytest.raises(OSError, match="disk full"):
        upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate")
    assert contents(root) == before
    assert (tmp_path / "candidate/failed.json").exists()
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert any(p.read_bytes() == b"partial data" for p in (tmp_path / "candidate/files").rglob("*") if p.is_file())


def test_child_exit_after_files_copy_never_marks_library_ready(tmp_path):
    db, root, _ = make_library(tmp_path)
    before = contents(root)
    original_db = db.path.read_bytes()
    destination = tmp_path / "candidate"
    code = "from score_organizer.services.number_upgrade_service import prepare_library_upgrade; import os,sys; prepare_library_upgrade(sys.argv[1],sys.argv[2],progress=lambda stage: os._exit(73) if stage=='files_copied' else None)"
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[1] / "src") + os.pathsep + environment.get("PYTHONPATH", "")
    result = subprocess.run([sys.executable, "-c", code, str(db.path), str(destination)], env=environment, capture_output=True, timeout=30)
    assert result.returncode == 73, result.stderr.decode(errors="replace")
    assert contents(root) == before == contents(destination / "files")
    assert db.path.read_bytes() == original_db
    assert not (destination / "prepared.json").exists()


@pytest.mark.parametrize("field,value", [("storage_layout", "unknown"), ("output_name", "../outside.pdf"),
                                         ("output_name", "..\\outside.pdf"), ("output_name", None)])
def test_invalid_stored_layout_or_filename_cannot_become_a_ready_snapshot(tmp_path, field, value):
    db, root, _ = make_library(tmp_path)
    with closing(sqlite3.connect(db.path)) as connection:
        connection.execute(f"UPDATE score SET {field}=? WHERE id=1", (value,))
        connection.commit()
    before = contents(root)
    with pytest.raises(ValueError, match="类型不受支持|文件名无效"):
        upgrade.prepare_library_upgrade(db.path, tmp_path / "candidate")
    assert contents(root) == before
    assert not (tmp_path / "candidate/prepared.json").exists()
