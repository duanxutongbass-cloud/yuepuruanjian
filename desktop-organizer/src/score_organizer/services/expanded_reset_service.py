"""Format-1 candidate reset support. No application entry point enables it yet.

An isolated candidate runtime must supply its guarded database connection. The
normal Database still rejects format 1; this service does not migrate or activate
a library and cannot establish that an old organizer process has been stopped.
"""
from __future__ import annotations

import sqlite3
from pathlib import Path

from score_organizer.services.number_upgrade_service import _inventory, _read_hash, _verify_tree
from score_organizer.services.reset_service import MANAGED_FOLDERS, ResetPlan, ResetService
from score_organizer.storage.writer_guard import require_format_one_guards


class ExpandedResetService(ResetService):
    MANIFEST_VERSION = 2

    def _require_snapshot_format(self, connection: sqlite3.Connection, *, schema: str = "main") -> None:
        own_transaction = not connection.in_transaction
        if own_transaction:
            connection.execute("BEGIN")
        try:
            require_format_one_guards(connection, schema=schema)
        finally:
            if own_transaction:
                connection.rollback()

    def _new_manifest(self, plan: ResetPlan) -> dict:
        with self.db.session() as connection:
            self._require_snapshot_format(connection)
        manifest = super()._new_manifest(plan)
        proofs = {}
        for folder in plan.folders:
            initial = _inventory(folder)
            directories = sorted(name for name, (kind, _) in initial.items() if kind == "directory")
            files = {name: _read_hash(folder / name, identity)
                     for name, (kind, identity) in initial.items() if kind == "file"}
            _verify_tree(folder, directories, files, inventory=initial)
            proofs[folder.name] = {"directories": directories, "files": files}
        manifest.update(database_format=1, folder_proofs=proofs)
        return manifest

    def _verify_folders(self, backup: Path, manifest: dict) -> None:
        if type(manifest.get("database_format")) is not int or manifest["database_format"] != 1:
            raise ValueError("扩位清空备份数据库格式无效")
        proofs = manifest.get("folder_proofs")
        if not isinstance(proofs, dict) or set(proofs) != set(manifest["folders"]):
            raise ValueError("扩位清空备份缺少完整目录证明")
        present = {name for name in MANAGED_FOLDERS
                   if (backup / name).exists() or (backup / name).is_symlink() or (backup / name).is_junction()}
        if present != set(manifest["folders"]):
            raise ValueError("扩位清空备份实际受管目录与清单不一致")
        for name, proof in proofs.items():
            if not isinstance(proof, dict) or set(proof) != {"directories", "files"}:
                raise ValueError("扩位清空备份目录证明无效")
            directories, files = proof["directories"], proof["files"]
            if not isinstance(directories, list) or not isinstance(files, dict):
                raise ValueError("扩位清空备份目录清单无效")
            # Verify the actual tree first: no manifest-controlled path is opened
            # until its names exactly match the independently observed inventory.
            _verify_tree(backup / name, directories, files, independent=True)

    def _write_manifest(self, backup: Path, manifest: dict) -> None:
        if manifest.get("state") == "completed":
            self._verify_folders(backup, manifest)
        elif manifest.get("state") == "restored":
            self._verify_folders(Path(manifest["output_root"]) if manifest["output_root"] else backup, manifest)
        super()._write_manifest(backup, manifest)

    def _validate_restore(self, backup: Path) -> dict:
        manifest = super()._validate_restore(backup)
        self._verify_folders(backup, manifest)
        return manifest
