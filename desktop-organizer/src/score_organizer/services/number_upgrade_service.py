"""Prepare independent library files alongside a database upgrade candidate.

No cutover: database and historical manifests still contain original paths.
Rebasing, runtime gates and cross-client compatibility must precede activation.
"""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import stat
from typing import Callable

from score_organizer.services.archive_service import ArchiveService
from score_organizer.storage.database import Database, require_supported_database
from score_organizer.storage.number_upgrade import (
    _hash, _no_links, _state, _write_json_exclusive, prepare_number_upgrade,
)


def _identity(info: os.stat_result) -> tuple:
    # On Windows lstat and fstat can expose different historical meanings for
    # ctime. The explicit birth time agrees; inode/device/mtime and full hashes
    # still detect replacement and content changes.
    return (info.st_mode, info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            getattr(info, "st_birthtime_ns", info.st_ctime_ns), info.st_nlink)


def _regular(path: Path) -> os.stat_result:
    _no_links(path)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError(f"升级只接受普通文件，不能跟随链接或特殊文件：{path}")
    return info


def _inventory(root: Path) -> dict[str, tuple[str, tuple]]:
    """Keep directory entries (including empty folders) and stable file identities."""
    result = {}
    pending = [root]
    while pending:
        path = pending.pop()
        _no_links(path)
        info = path.lstat()
        if getattr(info, "st_file_attributes", 0) & 0x400:
            raise ValueError(f"升级目录不能包含重解析点：{path}")
        relative = path.relative_to(root).as_posix()
        if stat.S_ISDIR(info.st_mode):
            result[relative] = ("directory", _identity(info))
            with os.scandir(path) as entries:
                pending.extend(sorted((Path(entry.path) for entry in entries), reverse=True))
        elif stat.S_ISREG(info.st_mode):
            result[relative] = ("file", _identity(info))
        else:
            raise ValueError(f"升级目录包含特殊文件，不能复制：{path}")
    return result


def _read_hash(path: Path, expected: tuple, destination: Path | None = None) -> dict:
    if _identity(_regular(path)) != expected:
        raise ValueError(f"复制期间源文件已变化：{path}")
    digest = hashlib.sha256()
    count = 0
    with path.open("rb") as source:
        if _identity(os.fstat(source.fileno())) != expected:
            raise ValueError(f"读取的文件已被替换：{path}")
        target = destination.open("xb") if destination else None
        try:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
                count += len(chunk)
                if target:
                    target.write(chunk)
            if target:
                target.flush()
                os.fsync(target.fileno())
            if _identity(os.fstat(source.fileno())) != expected:
                raise ValueError(f"读取期间源文件已变化：{path}")
        finally:
            if target:
                target.close()
    if _identity(_regular(path)) != expected or count != expected[3]:
        raise ValueError(f"复制期间源文件已变化：{path}")
    return {"bytes": count, "sha256": digest.hexdigest()}


def _verify_tree(root: Path, directories: list[str], files: dict[str, dict], *, inventory=None, independent=False) -> None:
    current = _inventory(root)
    if inventory is not None and current != inventory:
        raise ValueError("准备期间源整理目录已变化，请停止整理后重新准备")
    if sorted(name for name, (kind, _) in current.items() if kind == "directory") != directories or \
            set(name for name, (kind, _) in current.items() if kind == "file") != set(files):
        raise ValueError("整理目录文件清单已变化或副本不完整")
    for name, expected in files.items():
        if independent and _regular(root / name).st_nlink != 1:
            raise ValueError("升级副本不能与其他文件共享实体")
        if _read_hash(root / name, current[name][1]) != expected:
            raise ValueError(f"整理文件哈希不一致：{name}")
    if _inventory(root) != current:
        raise ValueError("核验期间整理目录已变化")


def _copy_tree(source: Path | None, destination: Path, progress: Callable[[str], None]) -> tuple:
    initial = _inventory(source) if source else {}
    destination.mkdir(exist_ok=False)
    directories = sorted(name for name, (kind, _) in initial.items() if kind == "directory") if source else ["."]
    for relative in sorted(directories, key=lambda value: (len(Path(value).parts), value)):
        if relative != ".":
            (destination / relative).mkdir(exist_ok=False)
    files = {}
    progress("copy_started")
    for relative, (kind, identity) in sorted(initial.items()):
        if kind != "file":
            continue
        copied = destination / relative
        files[relative] = _read_hash(source / relative, identity, copied)
        if os.path.samefile(source / relative, copied) or copied.stat().st_nlink != 1:
            raise ValueError("升级副本不能与原件共享文件实体")
        progress("file_copied:" + relative)
    progress("files_copied")
    _verify_tree(destination, directories, files, independent=True)
    if source:
        _verify_tree(source, directories, files, inventory=initial)
    return directories, files, initial


def _output_root(db: sqlite3.Connection) -> Path | None:
    row = db.execute("SELECT value FROM settings WHERE key='output_root'").fetchone()
    if not row or not row[0]:
        if db.execute("SELECT 1 FROM score LIMIT 1").fetchone():
            raise ValueError("已有分谱但未设置原整理目录，不能准备完整快照")
        return None
    root = Path(row[0])
    if not root.is_absolute():
        raise ValueError("原整理目录必须是明确的绝对路径")
    _no_links(root)
    if not root.is_dir():
        raise ValueError("原整理目录不存在，不能跳过PDF准备升级")
    return root.resolve()


def _verify_archived_parts(backup: Path, root: Path | None, files: dict) -> int:
    # This uses only the verified, format-0 backup. No live database calls and
    # no duplicated filename/layout rules that might drift from the organizer.
    db = Database(backup)
    rows = db.list_archived_scores()
    if rows and root is None:
        raise ValueError("缺少已入库分谱的原整理目录")
    archive = ArchiveService(db, root) if root else None
    for score in rows:
        if score["storage_layout"] not in ("dual", "instrument_only"):
            raise ValueError("分谱目录类型不受支持，不能自动准备升级")
        name = score["output_name"]
        if not name or "/" in name or "\\" in name or Path(name).name != name or Path(name).suffix.lower() != ".pdf":
            raise ValueError("存档分谱文件名无效，不能自动准备升级")
        for path in archive.score_paths(score):
            # A malformed stored filename must not escape the copied tree.
            try:
                relative = path.resolve().relative_to(root).as_posix()
            except ValueError as error:
                raise ValueError("分谱路径超出原整理目录") from error
            proof = files.get(relative)
            if proof is None or proof["sha256"] != score["source_sha256"]:
                raise ValueError(f"已入库分谱缺失或变化：{score['output_name']}")
    return len(rows)


def prepare_library_upgrade(source_path: str | Path, destination: str | Path, *,
                            progress: Callable[[str], None] = lambda _stage: None) -> Path:
    """Copy a whole output tree and validate it against a frozen database.

    This deliberately does NOT rebase paths or enable the candidate. All work
    stays in a new directory; failed and interrupted preparations are retained.
    """
    source_path, destination = Path(source_path).absolute(), Path(destination).absolute()
    _no_links(source_path)
    _no_links(destination)
    if not source_path.is_file() or not destination.parent.is_dir() or destination.exists():
        raise ValueError("源数据库/父目录不存在或升级目标已存在，不能覆盖")
    with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True, timeout=0)) as live:
        live.execute("BEGIN")
        require_supported_database(live)
        root = _output_root(live)
    if root:
        if destination.resolve().is_relative_to(root) or root.is_relative_to(destination.resolve()):
            raise ValueError("升级目录必须与原整理目录分离")
        if source_path.resolve().is_relative_to(root):
            raise ValueError("活动数据库位于整理目录内，请先单独规划库文件位置")
    destination.mkdir(exist_ok=False)
    try:
        _write_json_exclusive(destination / "preparing.json", {"source": str(source_path), "readyForActivation": False})
        database_manifest = prepare_number_upgrade(source_path, destination / "database", progress=progress)
        database_proof = json.loads(database_manifest.read_text(encoding="utf-8"))
        backup = destination / "database/before.sqlite3"
        with closing(sqlite3.connect(backup.as_uri() + "?mode=ro", uri=True)) as saved:
            before = _state(saved)
            if _output_root(saved) != root:
                raise ValueError("准备期间原整理目录配置已变化")
        directories, files, inventory = _copy_tree(root, destination / "files", progress)
        checked_scores = _verify_archived_parts(backup, root, files)
        progress("files_verified")
        # Repeat after callbacks/validation so a concurrent old process cannot
        # silently turn this into a mixed database-and-files preparation.
        if root:
            _verify_tree(root, directories, files, inventory=inventory)
        _verify_tree(destination / "files", directories, files, independent=True)
        with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True, timeout=0)) as live:
            live.execute("BEGIN")
            if _state(live) != before:
                raise ValueError("复制PDF期间源曲库已变化，请重新准备")
        for name, proof in database_proof["artifacts"].items():
            path = destination / "database" / name
            info = _regular(path)
            if info.st_nlink != 1 or info.st_size != proof["bytes"] or _hash(path) != proof["sha256"]:
                raise ValueError("准备期间数据库候选或备份已变化")
        manifest = destination / "prepared.json"
        _write_json_exclusive(manifest, {
            "format": "score-organizer-library-upgrade", "version": 1,
            "sourceDatabase": str(source_path), "sourceOutputRoot": str(root) if root else None,
            "databaseManifest": "database/prepared.json", "databaseManifestSha256": _hash(database_manifest),
            "filesRoot": "files", "directories": directories, "files": files,
            "archivedScoresVerified": checked_scores,
            "pdfFiles": sum(Path(name).suffix.lower() == ".pdf" for name in files),
            "fileBytes": sum(item["bytes"] for item in files.values()),
            "outputTreeSnapshotIncluded": True, "originalSourceFilesOutsideOutputIncluded": False,
            "sourcePathsRebased": False, "readyForActivation": False,
        })
        return manifest
    except BaseException as error:
        try:
            _write_json_exclusive(destination / "failed.json", {"readyForActivation": False,
                "errorType": type(error).__name__, "error": str(error)})
        except OSError:
            pass
        raise
