"""Prepare isolated runtime paths without activating an expanded-number library.

The original library and a pristine snapshot remain untouched. Historical output
manifests still need their own compatibility migration before any cutover.
"""
from __future__ import annotations

from contextlib import closing
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from typing import Callable

from score_organizer.services.number_upgrade_service import (
    _copy_tree, _identity, _inventory, _read_hash, _regular, _verify_tree, prepare_library_upgrade,
)
from score_organizer.services.grouping_service import warning_fingerprint
from score_organizer.services.historical_manifests import verify_pending_manifests
from score_organizer.storage.number_upgrade import (
    _backup, _hash, _integrity, _no_links, _state, _write_json_exclusive,
)
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards


def _records(db: sqlite3.Connection, table: str) -> tuple[list[str], list[list]]:
    columns = [row[1] for row in db.execute(f'PRAGMA table_info("{table}")')]
    return columns, [list(row) for row in db.execute(f'SELECT * FROM "{table}"')]


def _digest(rows: list[list]) -> dict:
    digest = hashlib.sha256()
    for row in sorted(repr(tuple((type(value).__name__, value) for value in row)).encode("utf-8") for row in rows):
        digest.update(len(row).to_bytes(8, "big"))
        digest.update(row)
    return {"rows": len(rows), "sha256": digest.hexdigest()}


def _standalone(path: Path) -> None:
    if _regular(path).st_nlink != 1:
        raise ValueError("数据库快照或清单不能与其他文件共享实体")
    if any(Path(str(path) + suffix).exists() or Path(str(path) + suffix).is_symlink()
           for suffix in ("-wal", "-shm", "-journal")):
        raise ValueError("数据库快照含未覆盖的附属日志，不能确认完成")


def _source_references(db: sqlite3.Connection) -> dict[str, str]:
    references = {}
    for table in ("score", "import_item"):
        for path, digest in db.execute(f'SELECT source_path,source_sha256 FROM "{table}"'):
            if not isinstance(path, str) or not Path(path).is_absolute() or Path(path).suffix.lower() != ".pdf":
                raise ValueError("源PDF路径无效，不能猜测或跳过迁移")
            if not isinstance(digest, str) or not re.fullmatch("[0-9a-f]{64}", digest):
                raise ValueError("源PDF缺少有效哈希，须先校对原记录")
            if path in references and references[path] != digest:
                raise ValueError("同一源PDF存在不同哈希，须先校对原记录")
            references[path] = digest
    return references


def _mapped_path(path, mapping: dict) -> str:
    if not isinstance(path, str) or path not in mapping:
        raise ValueError("预警引用不在已核验源PDF中，不能保留活动旧路径")
    return mapping[path]["path"]


def _warning_details(text: str, mapping: dict) -> str:
    details = json.loads(text)
    if not isinstance(details, dict):
        raise ValueError("预警记录格式不受支持，不能迁移定位路径")
    changed = False
    if "source_paths" in details:
        if not isinstance(details["source_paths"], list):
            raise ValueError("预警源PDF清单无效")
        details["source_paths"] = [_mapped_path(path, mapping) for path in details["source_paths"]]
        changed = bool(details["source_paths"])
    if "existing" in details:
        if not isinstance(details["existing"], list) or any(not isinstance(item, dict) for item in details["existing"]):
            raise ValueError("预警已入库分谱清单无效")
        for item in details["existing"]:
            if "source_path" in item:
                item["source_path"] = _mapped_path(item["source_path"], mapping)
                changed = True
    # Unknown descriptive fields and strings are deliberately not rewritten.
    return json.dumps(details, ensure_ascii=False) if changed else text


def _rebase_database(path: Path, mapping: dict, output: Path, progress: Callable[[str], None], *, numbering_version=2) -> dict:
    with closing(sqlite3.connect(path.as_uri() + "?mode=rw", uri=True, isolation_level=None, timeout=0)) as db:
        if numbering_version == 2:
            register_format_one_writer(db)
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("BEGIN IMMEDIATE")
        try:
            if numbering_version == 2:
                require_format_one_guards(db)
            else:
                from score_organizer.storage.database import require_supported_database
                require_supported_database(db)
            before = _state(db)
            expected = deepcopy(before)
            changes = {"warningFingerprintChanges": []}
            for table in ("score", "import_item", "warning", "settings"):
                columns, rows = _records(db, table)
                if table in ("score", "import_item"):
                    index = columns.index("source_path")
                    for row in rows:
                        row[index] = mapping[row[index]]["path"]
                elif table == "warning":
                    index, fingerprint = columns.index("details"), columns.index("fingerprint")
                    identities = set()
                    for row in rows:
                        old_details, old_fingerprint = row[index], row[fingerprint]
                        code = row[columns.index("code")]
                        # Unknown custom identities cannot safely be reassigned;
                        # otherwise a later refresh could silently lose decisions.
                        if old_fingerprint is not None and old_fingerprint != warning_fingerprint(code, json.loads(old_details)):
                            raise ValueError("预警标识与原详情不匹配，不能安全迁移处理状态")
                        row[index] = _warning_details(old_details, mapping)
                        row[fingerprint] = warning_fingerprint(code, json.loads(row[index]))
                        key = (row[columns.index("batch_id")], row[columns.index("song_id")], row[fingerprint])
                        if key in identities:
                            raise ValueError("预警迁移出现重复标识，不能丢弃或合并处理状态")
                        identities.add(key)
                        if old_fingerprint != row[fingerprint]:
                            changes["warningFingerprintChanges"].append({"id": row[columns.index("id")],
                                "old": old_fingerprint, "new": row[fingerprint]})
                else:
                    key, value = columns.index("key"), columns.index("value")
                    for row in rows:
                        if row[key] == "last_reset_backup":
                            changes["previousResetBackup"] = row[value]
                            row[value] = ""
                        elif row[key] == "output_root":
                            row[value] = str(output)
                    if not any(row[key] == "output_root" for row in rows):
                        if len(columns) != 2:
                            raise ValueError("设置表含未知字段，不能自动新增输出目录")
                        rows.append(["output_root", str(output)])
                expected["tables"][table] = _digest(rows)
                # Explicitly update only the known runtime columns. Audit logs,
                # timestamps, warning decisions and numbering stay put.
                if table == "settings":
                    for row in rows:
                        if row[columns.index("key")] in ("output_root", "last_reset_backup"):
                            db.execute("INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                                       (row[columns.index("key")], row[columns.index("value")]))
                else:
                    field = "details" if table == "warning" else "source_path"
                    for row in rows:
                        if table == "warning":
                            db.execute("UPDATE warning SET details=?,fingerprint=? WHERE id=?",
                                       (row[columns.index(field)], row[columns.index("fingerprint")], row[columns.index("id")]))
                        else:
                            db.execute(f'UPDATE "{table}" SET "{field}"=? WHERE id=?', (row[columns.index(field)], row[columns.index("id")]))
            if _state(db) != expected:
                raise ValueError("路径迁移改变了其他记录或结构，候选已回退")
            _integrity(db)
            progress("paths_rebased_before_commit")
            if _state(db) != expected:
                raise ValueError("提交前迁移候选已变化")
            db.commit()
            return {"state": expected, **changes}
        except BaseException:
            db.rollback()
            raise


def prepare_relocated_library(source_path: str | Path, destination: str | Path, *,
                              progress: Callable[[str], None] = lambda _stage: None) -> Path:
    """Prepare fresh snapshot + runtime copies; never switch the active library.

    Strictly refuse missing/changed/unverifiable original references. Descriptive
    history is preserved verbatim; historical file formats and activation remain
    explicit pending work. No existing candidate or original is overwritten.
    """
    source_path, destination = Path(source_path).absolute(), Path(destination).absolute()
    _no_links(source_path)
    _no_links(destination)
    if not source_path.is_file() or not destination.parent.is_dir() or destination.exists():
        raise ValueError("源数据库/父目录不存在或目标已存在，不能覆盖")
    # Let the established snapshot validator reject overlap before making our
    # parent directory as well: a failed attempt must not add to the old output.
    with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True, timeout=0)) as db:
        row = db.execute("SELECT value FROM settings WHERE key='output_root'").fetchone()
        if row and row[0]:
            root = Path(row[0]).resolve()
            if destination.resolve().is_relative_to(root) or root.is_relative_to(destination.resolve()):
                raise ValueError("新路径必须与原整理目录分离")
    destination.mkdir(exist_ok=False)
    try:
        snapshot_path = prepare_library_upgrade(source_path, destination / "snapshot", progress=progress)
        snapshot = json.loads(snapshot_path.read_text("utf-8"))
        snapshot_hash = _hash(snapshot_path)
        database_manifest = destination / "snapshot/database/prepared.json"
        database_proof = json.loads(database_manifest.read_text("utf-8"))
        database_manifest_hash = _hash(database_manifest)
        runtime = destination / "runtime"
        runtime.mkdir(exist_ok=False)
        directories, files, inventory = _copy_tree(destination / "snapshot/files", runtime / "files", progress)
        (runtime / "sources").mkdir(exist_ok=False)
        with closing(sqlite3.connect((destination / "snapshot/database/before.sqlite3").as_uri() + "?mode=ro", uri=True)) as db:
            original_state = _state(db)
            references = _source_references(db)
        mapping, source_identities = {}, {}
        for number, (old, digest) in enumerate(sorted(references.items()), 1):
            source = Path(old)
            identity = _identity(_regular(source))
            target = runtime / "sources" / f"{number:08d}" / source.name
            target.parent.mkdir(exist_ok=False)
            proof = _read_hash(source, identity, target)
            if proof["sha256"] != digest:
                raise ValueError(f"源PDF内容已变化：{source}")
            mapping[old] = {"path": str(target), **proof}
            source_identities[old] = identity
        source_inventory = _inventory(runtime / "sources")
        progress("sources_copied")
        candidate = runtime / "library.sqlite3"
        with closing(sqlite3.connect((destination / "snapshot/database/candidate.sqlite3").as_uri() + "?mode=ro", uri=True)) as db:
            _backup(db, candidate)
        rebased = _rebase_database(candidate, mapping, runtime / "files", progress)
        historical = verify_pending_manifests(runtime / "files", destination / "snapshot/database/before.sqlite3")
        progress("historical_manifests_verified")
        progress("runtime_verified")
        _verify_tree(runtime / "files", directories, files, independent=True)
        _verify_tree(destination / "snapshot/files", directories, files, inventory=inventory, independent=True)
        for old, proof in mapping.items():
            if _read_hash(Path(old), source_identities[old]) != {k: proof[k] for k in ("bytes", "sha256")}:
                raise ValueError("准备期间源PDF已变化")
            target = Path(proof["path"])
            if target.stat().st_nlink != 1 or _read_hash(target, _identity(_regular(target))) != {k: proof[k] for k in ("bytes", "sha256")}:
                raise ValueError("新源PDF副本不独立或内容变化")
        if _inventory(runtime / "sources") != source_inventory:
            raise ValueError("新源PDF目录结构已变化")
        root = Path(snapshot["sourceOutputRoot"]) if snapshot["sourceOutputRoot"] else None
        if root:
            _verify_tree(root, snapshot["directories"], snapshot["files"])
        with closing(sqlite3.connect(source_path.as_uri() + "?mode=ro", uri=True, timeout=0)) as db:
            db.execute("BEGIN")
            if _state(db) != original_state:
                raise ValueError("准备期间原曲库已变化")
        for name, proof in database_proof["artifacts"].items():
            path = destination / "snapshot/database" / name
            _standalone(path)
            if path.stat().st_size != proof["bytes"] or _hash(path) != proof["sha256"]:
                raise ValueError("原始数据库快照已变化")
        _standalone(snapshot_path)
        _standalone(database_manifest)
        if _hash(snapshot_path) != snapshot_hash or _hash(database_manifest) != database_manifest_hash:
            raise ValueError("原始快照清单已变化")
        with closing(sqlite3.connect(candidate.as_uri() + "?mode=ro", uri=True)) as db:
            db.execute("BEGIN")
            require_format_one_guards(db)
            _integrity(db)
            if _state(db) != rebased["state"] or db.execute("PRAGMA journal_mode").fetchone()[0] != "delete":
                raise ValueError("新路径数据库复核失败")
        _standalone(candidate)
        if verify_pending_manifests(runtime / "files", destination / "snapshot/database/before.sqlite3") != historical:
            raise ValueError("历史清单在完成前发生变化")
        manifest = destination / "prepared.json"
        _write_json_exclusive(manifest, {
            "format": "score-organizer-library-relocation", "version": 1,
            "sourceDatabase": str(source_path), "snapshotManifest": "snapshot/prepared.json",
            "snapshotManifestSha256": snapshot_hash, "databaseManifestSha256": database_manifest_hash,
            "runtimeDatabase": "runtime/library.sqlite3", "runtimeOutput": "runtime/files",
            "runtimeDatabaseProof": {"bytes": candidate.stat().st_size, "sha256": _hash(candidate)},
            "files": files, "directories": directories, "sourcePaths": mapping,
            "previousResetBackup": rebased.get("previousResetBackup"), "resetShortcutCleared": True,
            "sourcePathsRebased": True, "warningLocatorPathsRebased": True,
            "operationHistoryUnchanged": True, "historicalFileManifestsRebased": False,
            "historicalPendingFilesVerified": True, "historicalPendingManifestProof": historical,
            "warningFingerprintsReconciled": True, "warningFingerprintChanges": rebased["warningFingerprintChanges"],
            "originalSourceReferencesCopied": True,
            "readyForActivation": False,
        })
        return manifest
    except BaseException as error:
        try:
            _write_json_exclusive(destination / "failed.json", {"readyForActivation": False,
                "errorType": type(error).__name__, "error": str(error)})
        except OSError:
            pass
        raise
