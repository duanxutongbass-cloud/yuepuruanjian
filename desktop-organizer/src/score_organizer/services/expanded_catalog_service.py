"""Format-1 candidate catalog editing; not a product activation entry."""
from contextlib import closing
from pathlib import Path
import sqlite3

from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.services.catalog_service import CatalogService
from score_organizer.services.expanded_runtime_guard import require_expanded_runtime
from score_organizer.storage.number_upgrade import _integrity, _no_links
from score_organizer.storage.writer_guard import require_format_one_guards


class ExpandedCatalogService(CatalogService):
    _numbering_version = EXPANDED_NAMING_VERSION

    def _require_runtime(self) -> None:
        require_expanded_runtime(self.db, self.root)

    def _verify_backup(self, path: Path) -> None:
        _no_links(path)
        if not path.is_file() or path.stat().st_nlink != 1 or any(
                path.with_name(path.name + suffix).exists() for suffix in ('-journal', '-wal', '-shm')):
            raise ValueError('修改前数据库备份不是完整的独立文件')
        with closing(sqlite3.connect(path.absolute().as_uri() + '?mode=ro', uri=True)) as connection:
            connection.execute('BEGIN')
            require_format_one_guards(connection)
            _integrity(connection)
