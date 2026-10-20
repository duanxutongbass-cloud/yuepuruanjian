"""Prepare a separate database candidate. Never activate it or touch PDF paths.

The candidate still contains old path references and is NOT safe to open as a
live library until the separate file/old-instance isolation stage is complete.
"""
from __future__ import annotations

from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
from typing import Callable

from score_organizer.storage.writer_guard import (
    TRIGGER_PREFIX, install_format_one_guards, register_format_one_writer, require_format_one_guards,
)


OLD_SEQUENCE_CHECK = "CHECK(seq IS NULL OR length(seq) = 3)"
EXPANDED_SEQUENCE_CHECK = """CHECK(seq IS NULL OR (
    typeof(seq) = 'text' AND length(seq) BETWEEN 3 AND 19
    AND length(CAST(seq AS BLOB)) = length(seq)
    AND seq NOT GLOB '*[^0-9]*' AND seq != '000'
    AND (length(seq) = 3 OR substr(seq, 1, 1) != '0')
    AND (length(seq) < 19 OR seq <= '9223372036854775807')
))"""


def _quote(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _no_links(path: Path) -> None:
    if any(item.is_symlink() or item.is_junction() for item in (path, *path.parents)):
        raise ValueError("数据库升级路径不能包含符号链接或目录联接")


def _integrity(connection: sqlite3.Connection) -> None:
    if connection.execute("PRAGMA integrity_check").fetchall() != [("ok",)]:
        raise ValueError("数据库完整性检查失败")
    if connection.execute("PRAGMA foreign_key_check").fetchall():
        raise ValueError("数据库关联检查失败")


def _state(connection: sqlite3.Connection) -> dict:
    schema = connection.execute("SELECT type,name,tbl_name,sql FROM sqlite_schema ORDER BY type,name").fetchall()
    tables = {}
    for kind, name, _, _ in schema:
        if kind != "table":
            continue
        # Typed repr distinguishes TEXT/INTEGER/REAL/BLOB and ignores row order.
        encoded = sorted(repr(tuple((type(value).__name__, value) for value in row)).encode("utf-8")
                         for row in connection.execute("SELECT * FROM " + _quote(name)))
        digest = hashlib.sha256()
        for row in encoded:
            digest.update(len(row).to_bytes(8, "big"))
            digest.update(row)
        tables[name] = {"rows": len(encoded), "sha256": digest.hexdigest()}
    return {"version": connection.execute("PRAGMA user_version").fetchone()[0], "schema": schema, "tables": tables}


def _backup(source: sqlite3.Connection, target: Path) -> None:
    with target.open("xb"):
        pass
    def progress(status, _remaining, _total):
        if status in (sqlite3.SQLITE_BUSY, sqlite3.SQLITE_LOCKED):
            raise ValueError("数据库正忙，升级副本未完成；请关闭整理操作后重试")
    with closing(sqlite3.connect(target, timeout=0)) as destination:
        source.backup(destination, pages=256, progress=progress)
        destination.execute("PRAGMA journal_mode=DELETE")
        _integrity(destination)


def _write_json_exclusive(path: Path, value: dict) -> None:
    # Publish only a complete, flushed record. A failed write/fsync must not
    # leave a prepared.json which could be mistaken for a completed candidate.
    partial = path.with_name(path.name + ".partial")
    with partial.open("x", encoding="utf-8") as handle:
        json.dump(value, handle, ensure_ascii=False, indent=2)
        handle.flush()
        os.fsync(handle.fileno())
    # Hard-link creation is atomic and refuses an existing target on Windows
    # and POSIX. Unsupported filesystems fail closed, retaining the partial.
    os.link(partial, path)
    try:
        partial.unlink()
    except OSError:
        pass  # Cleanup cannot turn an already published record into a failure.


def _migrate_candidate(path: Path, progress: Callable[[str], None]) -> dict:
    with closing(sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, isolation_level=None, timeout=0)) as db:
        db.execute("PRAGMA foreign_keys=OFF")
        db.execute("BEGIN IMMEDIATE")
        try:
            before = _state(db)
            if before["version"] != 0:
                raise ValueError("升级候选必须来自0版数据库")
            row = db.execute("SELECT sql FROM sqlite_schema WHERE type='table' AND name='song'").fetchone()
            if row is None or row[0].count(OLD_SEQUENCE_CHECK) != 1:
                raise ValueError("曲目表结构不匹配，不能自动升级")
            indexes_before = db.execute("PRAGMA index_list(song)").fetchall()
            if not any(index[2] and not index[4] and
                       [column[2] for column in db.execute("PRAGMA index_info(" + _quote(index[1]) + ")")] == ["seq"]
                       for index in indexes_before):
                raise ValueError("曲库缺少全局编号唯一约束，不能自动升级")
            if db.execute("SELECT 1 FROM sqlite_schema WHERE name='_seq_song_new'").fetchone():
                raise ValueError("升级临时表名称已被占用")
            create = re.sub(r'^CREATE TABLE\s+(?:song|"song")\s*\(', 'CREATE TABLE _seq_song_new (', row[0], count=1, flags=re.I)
            if create == row[0]:
                raise ValueError("曲目表声明不匹配，不能自动升级")
            create = create.replace(OLD_SEQUENCE_CHECK, EXPANDED_SEQUENCE_CHECK)
            columns = [r[1] for r in db.execute("PRAGMA table_info(song)")]
            dependencies = db.execute("SELECT type,name,sql FROM sqlite_schema WHERE type IN ('view','trigger') ORDER BY type,name").fetchall()
            indexes = db.execute("SELECT sql FROM sqlite_schema WHERE type='index' AND tbl_name='song' AND sql IS NOT NULL ORDER BY name").fetchall()
            previous_sequence = db.execute("SELECT seq FROM sqlite_sequence WHERE name='song'").fetchone()
            for kind, name, _ in dependencies:
                db.execute("DROP " + kind.upper() + " " + _quote(name))
            progress("dependencies_saved")
            db.execute(create)
            names = ",".join(map(_quote, columns))
            db.execute(f"INSERT INTO _seq_song_new ({names}) SELECT {names} FROM song")
            progress("rows_copied")
            db.execute("DROP TABLE song")
            progress("old_table_dropped")
            db.execute("ALTER TABLE _seq_song_new RENAME TO song")
            for (definition,) in indexes:
                db.execute(definition)
            for _, _, definition in dependencies:
                db.execute(definition)
            db.execute("DELETE FROM sqlite_sequence WHERE name='song'")
            if previous_sequence is not None:
                db.execute("INSERT INTO sqlite_sequence(name,seq) VALUES('song',?)", previous_sequence)
            progress("dependencies_restored")
            db.execute("PRAGMA user_version=1")
            register_format_one_writer(db)
            install_format_one_guards(db)
            require_format_one_guards(db)
            progress("write_guards_installed")
            after = _state(db)
            expected_schema = [r for r in before["schema"] if not (r[0] == "table" and r[1] == "song")]
            preserved_schema = [r for r in after["schema"] if not (r[0] == "table" and r[1] == "song")
                                and not r[1].startswith(TRIGGER_PREFIX)]
            if before["tables"] != after["tables"] or expected_schema != preserved_schema:
                raise ValueError("升级改变了原记录或关联结构，已拒绝候选")
            _integrity(db)
            progress("before_candidate_commit")
            db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.execute("PRAGMA foreign_keys=ON")
        return after["tables"]


def prepare_number_upgrade(source_path: str | Path, destination: str | Path, *,
                           progress: Callable[[str], None] = lambda _stage: None) -> Path:
    """Create backup, independently restored copy and guarded candidate, never cut over.

    destination must not exist. Failures retain evidence and never emit prepared.json.
    Returned manifest explicitly says database-only and readyForActivation=false.
    """
    source_path, destination = Path(source_path).absolute(), Path(destination).absolute()
    _no_links(source_path)
    _no_links(destination)
    if not source_path.is_file() or not destination.parent.is_dir():
        raise ValueError("源数据库或升级目录的父文件夹不存在")
    if destination.exists():
        raise ValueError("升级目录已存在，不能覆盖备份或候选")
    with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True, timeout=0)) as source:
        source.execute("PRAGMA query_only=ON")
        source.execute("BEGIN")
        if source.execute("PRAGMA user_version").fetchone()[0] != 0:
            raise ValueError("仅支持从0版数据库准备升级，不初始化未知库")
        _integrity(source)
        before = _state(source)
        output = source.execute("SELECT value FROM settings WHERE key='output_root'").fetchone()
        if output and output[0]:
            output_root = Path(output[0]).resolve()
            if destination.resolve().is_relative_to(output_root) or output_root.is_relative_to(destination.resolve()):
                raise ValueError("升级候选必须位于现有整理目录之外")
        destination.mkdir(exist_ok=False)
        backup, restored, candidate = (destination / name for name in ("before.sqlite3", "restored.sqlite3", "candidate.sqlite3"))
        try:
            _write_json_exclusive(destination / "preparing.json", {"source": str(source_path), "readyForActivation": False})
            _backup(source, backup)
            source.rollback()
            progress("backup_created")
            with closing(sqlite3.connect(backup.as_uri() + "?mode=ro", uri=True)) as saved:
                if _state(saved) != before:
                    raise ValueError("数据库备份与读取快照不一致")
                _backup(saved, restored)
            with closing(sqlite3.connect(restored.as_uri() + "?mode=ro", uri=True)) as recovery:
                if _state(recovery) != before:
                    raise ValueError("独立恢复核验失败")
                _backup(recovery, candidate)
            progress("restore_verified")
            tables = _migrate_candidate(candidate, progress)
            with closing(sqlite3.connect(candidate.as_uri() + "?mode=ro", uri=True)) as check:
                check.execute("BEGIN")
                require_format_one_guards(check)
                _integrity(check)
                if _state(check)["tables"] != tables:
                    raise ValueError("重新打开候选后的记录校验失败")
            source.execute("BEGIN")
            if _state(source) != before:
                raise ValueError("准备期间源曲库已变化，请保留本次备份并重新准备")
            source.rollback()
            artifacts = {path.name: {"bytes": path.stat().st_size, "sha256": _hash(path)} for path in (backup, restored, candidate)}
            manifest = destination / "prepared.json"
            _write_json_exclusive(manifest, {"format": "score-organizer-number-upgrade", "version": 1,
                "source": str(source_path), "sourceDatabaseVersion": 0, "candidateDatabaseVersion": 1,
                "databaseOnly": True, "readyForActivation": False, "pdfSnapshotIncluded": False,
                "artifacts": artifacts, "preservedTables": tables})
            return manifest
        except BaseException as error:
            try:
                _write_json_exclusive(destination / "failed.json", {"readyForActivation": False,
                    "errorType": type(error).__name__, "error": str(error)})
            except OSError:
                pass
            raise
