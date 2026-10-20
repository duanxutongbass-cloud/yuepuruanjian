from __future__ import annotations

import json
import hashlib
import sqlite3
import tempfile
import time
from contextlib import closing, contextmanager
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from score_organizer.services.import_service import sha256_file
from score_organizer.storage.database import Database, require_supported_database


DATA_TABLES = ("operation_log", "warning", "title_alias", "score", "import_item", "song", "import_batch")
MANAGED_FOLDERS = ("按乐器分类", "按曲目分类", "待审查", "已确认重复", "修改备份", "暂时无用乐谱")


def _unique_manifest_fields(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("清空备份清单包含重复字段，不能恢复")
        result[key] = value
    return result


@dataclass(frozen=True)
class ResetPlan:
    output_root: Path | None
    backup_parent: Path
    folders: tuple[Path, ...]
    counts: tuple[tuple[str, int], ...]


class ResetService:
    MANIFEST_VERSION = 1

    def __init__(self, db: Database) -> None:
        self.db = db

    def build_plan(self) -> ResetPlan:
        output = self.db.get_setting("output_root", "")
        root = Path(output).resolve() if output else None
        with self.db.session() as connection:
            counts = tuple((table, int(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0])) for table in DATA_TABLES)
            source_paths = [Path(row[0]) for row in connection.execute("SELECT source_path FROM score UNION SELECT source_path FROM import_item") if row[0]]
        if root is None and dict(counts)["score"]:
            raise ValueError("请先选择原来的输出文件夹，才能连同整理目录一起清空")
        folders = []
        if root:
            if root.exists() and not root.is_dir():
                raise ValueError("输出路径不是文件夹")
            for name in MANAGED_FOLDERS:
                folder = root / name
                if not folder.exists():
                    continue
                if not folder.is_dir() or folder.is_symlink() or folder.is_junction() or folder.resolve().parent != root:
                    raise ValueError(f"整理目录路径不安全，不能清空：{folder}")
                if self.db.path.resolve().is_relative_to(folder.resolve()):
                    raise ValueError("数据库位于整理目录内，不能移动该目录")
                for source in source_paths:
                    if not source.is_file():
                        continue
                    if source.resolve().is_relative_to(folder.resolve()) or source.absolute().is_relative_to(folder.absolute()):
                        raise ValueError(f"整理目录内有正在使用的源PDF，不能移动原件：{source}")
                folders.append(folder)
        backup_parent = (root or self.db.path.resolve().parent) / "清空备份"
        if backup_parent.exists() and (not backup_parent.is_dir() or backup_parent.is_symlink() or backup_parent.is_junction()):
            raise ValueError("清空备份位置无效，不能清空")
        return ResetPlan(root, backup_parent, tuple(folders), counts)

    def clear(self, plan: ResetPlan) -> Path:
        if plan != self.build_plan():
            raise ValueError("数据或目录已经变化，请重新确认清空范围")
        backup = plan.backup_parent / f"{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}"
        backup.mkdir(parents=True, exist_ok=False)
        moved = []
        try:
            manifest = self._new_manifest(plan)
        except BaseException:
            backup.rmdir()
            raise
        try:
            with self.db.transaction():
                if plan != self.build_plan():
                    raise ValueError("数据已经变化，请重新确认")
                self._snapshot_database(backup / "清空前.sqlite3")
                manifest["database_sha256"] = sha256_file(backup / "清空前.sqlite3")
                self._write_manifest(backup, manifest)
                for folder in plan.folders:
                    self._rename_folder(folder, backup / folder.name)
                    moved.append(folder)
                self.db.clear_library_data()
                self.db.set_setting("last_reset_backup", str(backup.resolve()))
                manifest["state"] = "completed"
                self._write_manifest(backup, manifest)
        except Exception as exc:
            failures = []
            for folder in reversed(moved):
                try:
                    if folder.exists():
                        raise ValueError("原位置已出现其他目录，不能覆盖")
                    self._rename_folder(backup / folder.name, folder)
                except (OSError, ValueError):
                    failures.append(str(folder))
            if failures:
                raise ValueError(f"清空失败，部分目录未恢复，请停止整理。备份：{backup}\n" + "\n".join(failures)) from exc
            manifest["state"] = "failed"
            manifest_error = ""
            try:
                self._write_manifest(backup, manifest)
            except OSError as error:
                manifest_error = f"\n备份清单无法更新：{error}"
            raise ValueError(f"清空未完成，原记录和目录已保留。备份：{backup}\n{exc}{manifest_error}") from exc
        return backup

    def _new_manifest(self, plan: ResetPlan) -> dict:
        return {"version": self.MANIFEST_VERSION, "state": "preparing", "database": str(self.db.path.resolve()),
                "output_root": str(plan.output_root) if plan.output_root else None,
                "folders": [folder.name for folder in plan.folders], "counts": dict(plan.counts)}

    def _write_manifest(self, backup: Path, manifest: dict) -> None:
        temporary = backup / "清空记录.tmp"
        content = json.dumps(manifest, ensure_ascii=False, indent=2)
        for attempt in range(3):
            try:
                temporary.write_text(content, encoding="utf-8")
                temporary.replace(backup / "清空记录.json")
                return
            except PermissionError:
                if attempt == 2:
                    raise
                time.sleep(0.1 * (attempt + 1))

    def _rename_folder(self, source: Path, target: Path) -> None:
        for attempt in range(3):
            if target.exists():
                raise ValueError(f"目标目录已存在，不能覆盖：{target}")
            try:
                source.rename(target)
                return
            except PermissionError:
                if attempt == 2:
                    raise
                time.sleep(0.1 * (attempt + 1))

    def _snapshot_database(self, destination_path: Path) -> None:
        with closing(self.db.connect()) as source, closing(sqlite3.connect(destination_path)) as destination:
            source.backup(destination)
            # A source using WAL can leave that mode in the snapshot header.
            # The manifest covers one standalone file, so finish in DELETE mode.
            destination.execute("PRAGMA journal_mode=DELETE")

    def _validate_snapshot_file(self, snapshot: Path, expected_sha256: str) -> None:
        if snapshot.is_symlink() or snapshot.is_junction() or not snapshot.is_file():
            raise ValueError("清空前数据库备份缺失或路径无效")
        # This manifest authenticates the standalone snapshot, not SQLite sidecars.
        # Do not silently read committed WAL rows outside the recorded file hash.
        if any(Path(str(snapshot) + suffix).exists() for suffix in ("-wal", "-shm", "-journal")):
            raise ValueError("数据库备份存在日志文件，请关闭使用它的程序并重新生成完整备份")
        if sha256_file(snapshot) != expected_sha256:
            raise ValueError("清空前数据库备份缺失或变化")

    def _validate_snapshot_database(self, connection: sqlite3.Connection, *, schema: str = "main") -> None:
        self._require_snapshot_format(connection, schema=schema)
        qualified = '"' + schema.replace('"', '""') + '"'
        try:
            if connection.execute(f"PRAGMA {qualified}.integrity_check").fetchone()[0] != "ok":
                raise ValueError("备份数据库完整性校验失败，不能恢复")
            if connection.execute(f"PRAGMA {qualified}.foreign_key_check").fetchone() is not None:
                raise ValueError("备份关联数据校验失败，不能恢复")
        except sqlite3.DatabaseError as exc:
            raise ValueError("备份数据库无法通过完整性校验，不能恢复") from exc

    def _require_snapshot_format(self, connection: sqlite3.Connection, *, schema: str = "main") -> None:
        require_supported_database(connection, schema=schema)

    @contextmanager
    def _prepared_snapshot(self, snapshot: Path, expected_sha256: str):
        self._validate_snapshot_file(snapshot, expected_sha256)
        # Never open the preserved backup in SQLite: even a mode=ro connection
        # can create WAL sidecars. Verify a private byte copy, then normalize only
        # that copy. Its lifetime must cover the attached restore transaction.
        # A Windows cleanup lock after the database commit must not enter the
        # restore failure handler and move successfully restored folders back.
        with tempfile.TemporaryDirectory(prefix="score-restore-", ignore_cleanup_errors=True) as directory:
            prepared = Path(directory) / "snapshot.sqlite3"
            digest = hashlib.sha256()
            with snapshot.open("rb") as source, prepared.open("xb") as destination:
                while chunk := source.read(1024 * 1024):
                    destination.write(chunk)
                    digest.update(chunk)
            if digest.hexdigest() != expected_sha256:
                raise ValueError("复制备份期间文件发生变化，不能恢复")
            self._validate_snapshot_file(snapshot, expected_sha256)
            try:
                with closing(sqlite3.connect(prepared)) as source:
                    self._require_snapshot_format(source)
                    source.execute("PRAGMA journal_mode=DELETE")
                    self._validate_snapshot_database(source)
            except sqlite3.DatabaseError as exc:
                raise ValueError("数据库备份无法读取，不能恢复") from exc
            yield prepared

    def _validate_restore(self, backup: Path) -> dict:
        output = self.db.get_setting("output_root", "")
        root = Path(output).resolve() if output else None
        expected_parent = (root or self.db.path.resolve().parent) / "清空备份"
        if backup.resolve().parent != expected_parent.resolve() or backup.is_symlink() or backup.is_junction():
            raise ValueError("备份不属于当前整理库")
        manifest_path = backup / "清空记录.json"
        if manifest_path.is_symlink() or manifest_path.is_junction() or not manifest_path.is_file():
            raise ValueError("清空备份清单缺失或路径无效")
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"), object_pairs_hook=_unique_manifest_fields)
        if not isinstance(manifest, dict) or not all(key in manifest for key in ("version", "state", "database", "output_root", "folders", "database_sha256")):
            raise ValueError("清空备份清单不完整，不能恢复")
        folders = manifest["folders"]
        if not isinstance(folders, list) or not all(isinstance(name, str) and name in MANAGED_FOLDERS for name in folders) or len(set(folders)) != len(folders):
            raise ValueError("清空备份包含无效目录清单")
        if type(manifest.get("version")) is not int or manifest["version"] != self.MANIFEST_VERSION or manifest.get("state") != "completed":
            raise ValueError("该清空备份不能恢复，或已经恢复过")
        if manifest["database"] != str(self.db.path.resolve()) or manifest["output_root"] != (str(root) if root else None):
            raise ValueError("数据库或输出位置已经变化，不能恢复到错误位置")
        snapshot = backup / "清空前.sqlite3"
        with self._prepared_snapshot(snapshot, manifest["database_sha256"]):
            pass
        with self.db.session() as connection:
            if any(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] for table in DATA_TABLES):
                raise ValueError("已有新的整理数据，恢复会产生覆盖；请先保留新数据")
        for name in manifest["folders"]:
            if root is None or name not in MANAGED_FOLDERS:
                raise ValueError("备份包含无效目录")
            source = backup / name
            if not source.is_dir() or source.is_symlink() or source.is_junction() or source.resolve().parent != backup.resolve():
                raise ValueError("备份目录缺失或路径无效")
            if (root / name).exists():
                raise ValueError(f"当前整理目录已存在，不能覆盖：{root / name}")
        return manifest

    def restore(self, backup_path: str | Path) -> None:
        backup = Path(backup_path).resolve()
        manifest = self._validate_restore(backup)
        root = Path(manifest["output_root"]) if manifest["output_root"] else None
        moved = []
        manifest_write_attempted = False
        try:
            snapshot = backup / "清空前.sqlite3"
            with self._prepared_snapshot(snapshot, manifest["database_sha256"]) as prepared:
                prepared_sha256 = sha256_file(prepared)
                with self.db.transaction():
                    if self._validate_restore(backup) != manifest:
                        raise ValueError("清空备份清单已经变化，请重新检查恢复范围")
                    before_restore = backup / f"恢复前_{uuid4().hex[:8]}.sqlite3"
                    self._snapshot_database(before_restore)
                    self._validate_snapshot_file(snapshot, manifest["database_sha256"])
                    self._restore_database(prepared, expected_sha256=prepared_sha256)
                    for name in manifest["folders"]:
                        self._rename_folder(backup / name, root / name)
                        moved.append(name)
                    self.db.set_setting("last_reset_backup", str(backup))
                    manifest["state"] = "restored"
                    manifest_write_attempted = True
                    self._write_manifest(backup, manifest)
        except Exception as exc:
            failures = []
            for name in reversed(moved):
                try:
                    if (backup / name).exists():
                        raise ValueError("备份原位置已有其他目录")
                    self._rename_folder(root / name, backup / name)
                except (ValueError, OSError):
                    failures.append(name)
            manifest["state"] = "completed"
            manifest_error = ""
            if manifest_write_attempted:
                try:
                    self._write_manifest(backup, manifest)
                except (OSError, ValueError) as error:
                    manifest_error = f"\n备份清单无法更新：{error}；备份：{backup}"
            if failures:
                raise ValueError(f"恢复未完成，请停止整理。备份：{backup}\n" + "、".join(failures) + manifest_error) from exc
            raise ValueError(f"恢复未完成，清空后的状态与备份保留：{exc}{manifest_error}") from exc

    def _restore_database(self, snapshot: Path, *, expected_sha256: str) -> None:
        self._validate_snapshot_file(snapshot, expected_sha256)
        with self.db.session() as connection:
            connection.execute("ATTACH DATABASE ? AS reset_backup", (snapshot.resolve().as_uri() + "?mode=ro",))
            # Validate the attached read snapshot again under the live transaction,
            # before deleting records or moving any directory. A preflight check
            # alone cannot protect against a backup changing before ATTACH.
            self._validate_snapshot_database(connection, schema="reset_backup")
            self._validate_snapshot_file(snapshot, expected_sha256)
            tables = ("category", "instrument", "settings", "import_batch", "song", "score", "title_alias", "import_item", "warning", "operation_log")
            for table in tables:
                current = [row[1] for row in connection.execute(f"PRAGMA main.table_info({table})")]
                previous = [row[1] for row in connection.execute(f"PRAGMA reset_backup.table_info({table})")]
                compatible_addition = {"score": "storage_layout", "song": "minimum_waived"}.get(table)
                if current != previous and not (compatible_addition and current == previous + [compatible_addition]):
                    raise ValueError("备份数据库结构与当前版本不同，不能直接恢复")
            self.db.clear_library_data()
            for table in ("category", "instrument", "settings"):
                connection.execute(f"DELETE FROM {table}")
            for table in tables:
                columns = [row[1] for row in connection.execute(f"PRAGMA reset_backup.table_info({table})")]
                names = ", ".join(columns)
                connection.execute(f"INSERT INTO {table} ({names}) SELECT {names} FROM reset_backup.{table}")
            connection.executemany("INSERT OR IGNORE INTO instrument(code, name) VALUES (?, ?)",
                                   [("08", "总谱"), ("09", "总谱+分谱")])
            if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
                raise ValueError("备份关联数据校验失败")
