"""Write fence for a future format-1 database; not enabled on live format-0 data.

Old organizer builds ignore user_version. They do not register this connection-
local function, so guarded DML fails during SQL preparation. This is a format
compatibility fence, not access control against arbitrary SQLite/file editing.
Migration must still require old organizer processes to be closed: old file
operations may move PDFs before attempting their first database write.
"""
from __future__ import annotations

import hashlib
import sqlite3


GUARDED_DATABASE_VERSION = 1
WRITER_FUNCTION = "score_organizer_writer_format"
TRIGGER_PREFIX = "_score_format1_guard_"
REQUIRED_TABLES = frozenset(("category", "instrument", "settings", "import_batch", "song",
                            "score", "title_alias", "import_item", "warning", "operation_log"))


class WriteGuardError(ValueError):
    pass


def _quote(value: str) -> str:
    return '"' + value.replace('"', '""') + '"'


def _require_format(connection: sqlite3.Connection, schema: str = "main") -> None:
    if connection.execute(f"PRAGMA {_quote(schema)}.user_version").fetchone()[0] != GUARDED_DATABASE_VERSION:
        raise WriteGuardError("只能为已核验的1版曲库配置写入保护")


def register_format_one_writer(connection: sqlite3.Connection) -> None:
    """Only a future runtime supporting format 1 may call this after validation."""
    _require_format(connection)
    # Pure and connection-local: no I/O or mutable state exposed through schema.
    connection.create_function(WRITER_FUNCTION, 0, lambda: GUARDED_DATABASE_VERSION)


def _definitions(connection: sqlite3.Connection, schema: str = "main") -> dict[str, str]:
    rows = connection.execute(
        f"SELECT name,sql FROM {_quote(schema)}.sqlite_schema WHERE type='table' ORDER BY name"
    ).fetchall()
    tables = {name: sql for name, sql in rows if not name.startswith("sqlite_")}
    if not REQUIRED_TABLES.issubset(tables):
        raise WriteGuardError("曲库表不完整，不能配置写入保护")
    if any(sql is None or sql.lstrip().upper().startswith("CREATE VIRTUAL TABLE") for sql in tables.values()):
        raise WriteGuardError("曲库包含不支持的表类型，不能配置写入保护")
    definitions = {}
    for table in tables:
        token = hashlib.sha256(table.encode("utf-8")).hexdigest()
        for operation in ("INSERT", "UPDATE", "DELETE"):
            name = f"{TRIGGER_PREFIX}{token}_{operation.lower()}"
            definitions[name] = (
                f"CREATE TRIGGER {_quote(name)} BEFORE {operation} ON {_quote(table)} "
                f"BEGIN SELECT CASE WHEN {WRITER_FUNCTION}() IS NOT {GUARDED_DATABASE_VERSION} "
                "THEN RAISE(ABORT, '此曲库需要支持扩位编号的新版整理器') END; END"
            )
    return definitions


def _reserved_objects(connection: sqlite3.Connection, schema: str = "main") -> dict[str, tuple[str, str | None]]:
    return {name: (kind, sql) for kind, name, sql in connection.execute(
        f"SELECT type,name,sql FROM {_quote(schema)}.sqlite_schema"
    ) if name.casefold().startswith(TRIGGER_PREFIX)}


def require_format_one_guards(connection: sqlite3.Connection, *, schema: str = "main") -> None:
    """Check inside the caller's transaction so schema cannot race later DML."""
    if not connection.in_transaction:
        raise WriteGuardError("写入保护核验必须在数据库事务内进行")
    _require_format(connection, schema)
    expected = _definitions(connection, schema)
    actual = _reserved_objects(connection, schema)
    if actual != {name: ("trigger", sql) for name, sql in expected.items()}:
        raise WriteGuardError("曲库写入保护缺失或变化，请停止操作并核验备份")


def install_format_one_guards(connection: sqlite3.Connection) -> None:
    """Install atomically within the migration transaction; never overwrite DDL."""
    if not connection.in_transaction:
        raise WriteGuardError("写入保护只能在迁移事务内安装")
    _require_format(connection)
    definitions = _definitions(connection)
    if _reserved_objects(connection):
        raise WriteGuardError("写入保护名称已存在，请核验迁移状态；不能覆盖")
    try:
        writer = connection.execute(f"SELECT {WRITER_FUNCTION}()").fetchone()[0]
    except sqlite3.DatabaseError as error:
        raise WriteGuardError("当前连接尚未配置新版写入能力") from error
    if type(writer) is not int or writer != GUARDED_DATABASE_VERSION:
        raise WriteGuardError("当前连接的写入能力不匹配")
    connection.execute("SAVEPOINT score_format1_guards")
    try:
        for definition in definitions.values():
            connection.execute(definition)
        require_format_one_guards(connection)
    except BaseException:
        connection.execute("ROLLBACK TO score_format1_guards")
        raise
    finally:
        connection.execute("RELEASE score_format1_guards")
