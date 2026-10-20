"""Shared candidate checks, not activation or an OS isolation mechanism."""
from pathlib import Path

from score_organizer.domain.numbering import EXPANDED_NAMING_VERSION
from score_organizer.storage.number_upgrade import _no_links
from score_organizer.storage.writer_guard import require_format_one_guards


def require_expanded_runtime(db, output_root: Path | None = None) -> Path:
    if db._numbering_version != EXPANDED_NAMING_VERSION:
        raise ValueError('扩位操作需要支持v2编号的独立运行库')
    output = db.path.parent / 'files' if output_root is None else output_root
    if not db.path.is_absolute() or db.path.name != 'library.sqlite3' or output != db.path.parent / 'files':
        raise ValueError('扩位操作必须使用迁移候选的独立运行路径')
    _no_links(db.path)
    _no_links(output)
    if not output.is_dir():
        raise ValueError('独立运行输出目录缺失，不能自动新建或回退')
    with db.session() as connection:
        own_transaction = not connection.in_transaction
        if own_transaction:
            connection.execute('BEGIN')
        try:
            require_format_one_guards(connection)
            setting = connection.execute("SELECT value FROM settings WHERE key='output_root'").fetchone()
            if setting is None or setting[0] != str(output):
                raise ValueError('运行库输出位置与独立候选不一致')
        finally:
            if own_transaction:
                connection.rollback()
    return output
