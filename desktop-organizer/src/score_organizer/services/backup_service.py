from __future__ import annotations

import shutil
from datetime import datetime
from pathlib import Path

from score_organizer.storage.database import Database


class BackupService:
    def __init__(self, db: Database) -> None:
        self.db = db

    def create_backup(self, backup_dir: str | Path) -> Path:
        backup_root = Path(backup_dir)
        backup_root.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        backup_path = backup_root / f"score_organizer_{timestamp}.sqlite3"
        shutil.copy2(self.db.path, backup_path)
        return backup_path

    def restore_backup(self, backup_path: str | Path) -> None:
        source = Path(backup_path)
        if not source.exists():
            raise ValueError("备份文件不存在")
        self.db.path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, self.db.path)
