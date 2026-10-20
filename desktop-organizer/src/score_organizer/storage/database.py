from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from score_organizer.domain.numbering import next_global_sequence, normalize_seq


# Existing organizer databases use SQLite's default user_version. Do not bump
# this until the complete numbering migration and old-reader isolation ship.
DATABASE_FORMAT_VERSION = 0


class UnsupportedDatabaseVersion(ValueError):
    def __init__(self, version: int) -> None:
        self.version = version
        super().__init__(
            f"此乐谱库使用当前整理器不支持的数据格式（{version}）。"
            "请使用创建该库的版本或更新后的整理器打开，不要清空或重新初始化乐谱库。"
        )


def require_supported_database(connection: sqlite3.Connection, *, schema: str = "main") -> None:
    # PRAGMA does not accept bound schema identifiers. Quote even internal aliases.
    qualified = '"' + schema.replace('"', '""') + '"'
    version = connection.execute(f"PRAGMA {qualified}.user_version").fetchone()[0]
    if version != DATABASE_FORMAT_VERSION:
        raise UnsupportedDatabaseVersion(version)


INITIAL_INSTRUMENTS = (
    ("01", "第一小提琴"),
    ("02", "第二小提琴"),
    ("03", "中提琴"),
    ("04", "大提琴"),
    ("05", "低音提琴"),
    ("06", "钢琴"),
    ("07", "打击乐"),
    ("08", "总谱"),
    ("09", "总谱+分谱"),
)

INITIAL_CATEGORIES = (
    ("01", "久石让"),
    ("02", "电影音乐"),
    ("03", "中国风格"),
    ("04", "迪士尼"),
    ("05", "古典"),
    ("06", "游戏动漫"),
    ("07", "周宏德"),
    ("08", "杂项"),
    ("09", "三重奏"),
)


class Database:
    # Overridden only by a future guarded runtime; normal startup keeps v1.
    _numbering_version = 1

    def __init__(self, path: str | Path) -> None:
        self.path = Path(path)
        self._transaction_connection: sqlite3.Connection | None = None

    def _require_supported(self, connection):
        require_supported_database(connection)

    def connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        # URI support also lets restore attach a backup with mode=ro.
        connection = sqlite3.connect(self.path, uri=True)
        try:
            self._require_supported(connection)
            connection.row_factory = sqlite3.Row
            connection.execute("PRAGMA foreign_keys = ON")
            return connection
        except BaseException:
            connection.close()
            raise

    @contextmanager
    def session(self) -> Iterator[sqlite3.Connection]:
        if self._transaction_connection is not None:
            yield self._transaction_connection
            return
        connection = self.connect()
        try:
            # Pin the checked version to this read/write snapshot. A concurrent
            # format upgrade cannot slip between the check and a later write.
            connection.execute("BEGIN")
            self._require_supported(connection)
            yield connection
            self._require_supported(connection)
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    @contextmanager
    def transaction(self, *, reuse: bool = False) -> Iterator[sqlite3.Connection]:
        if self._transaction_connection is not None:
            if reuse:
                yield self._transaction_connection
                return
            raise ValueError("已有归档事务正在执行")
        connection = self.connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            self._require_supported(connection)
            self._transaction_connection = connection
            yield connection
            self._require_supported(connection)
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            self._transaction_connection = None
            connection.close()

    def initialize(self) -> None:
        with self.transaction() as db:
            schema = """
                CREATE TABLE IF NOT EXISTS instrument (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL UNIQUE CHECK(length(code) = 2),
                    name TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1
                );

                CREATE TABLE IF NOT EXISTS category (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL UNIQUE CHECK(length(code) = 2),
                    name TEXT NOT NULL,
                    active INTEGER NOT NULL DEFAULT 1
                );

                CREATE TABLE IF NOT EXISTS settings (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS song (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category_id INTEGER NOT NULL REFERENCES category(id),
                    seq TEXT UNIQUE CHECK(seq IS NULL OR length(seq) = 3),
                    base_title TEXT NOT NULL,
                    normalized_title TEXT NOT NULL,
                    display_title TEXT NOT NULL,
                    version_no INTEGER NOT NULL DEFAULT 1,
                    status TEXT NOT NULL DEFAULT 'draft',
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS score (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    song_id INTEGER NOT NULL REFERENCES song(id),
                    instrument_id INTEGER NOT NULL REFERENCES instrument(id),
                    source_name TEXT NOT NULL,
                    source_path TEXT NOT NULL,
                    source_sha256 TEXT NOT NULL,
                    output_name TEXT,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS title_alias (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category_id INTEGER NOT NULL REFERENCES category(id),
                    alias_title TEXT NOT NULL,
                    song_id INTEGER NOT NULL REFERENCES song(id),
                    confirmed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS import_batch (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    state TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS import_item (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id INTEGER NOT NULL REFERENCES import_batch(id),
                    source_path TEXT NOT NULL,
                    source_sha256 TEXT,
                    status TEXT NOT NULL,
                    parsed_fields TEXT,
                    decision TEXT
                );

                CREATE TABLE IF NOT EXISTS warning (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id INTEGER REFERENCES import_batch(id),
                    song_id INTEGER REFERENCES song(id),
                    code TEXT NOT NULL,
                    fingerprint TEXT,
                    severity TEXT NOT NULL,
                    state TEXT NOT NULL,
                    details TEXT NOT NULL,
                    resolution TEXT,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );

                CREATE TABLE IF NOT EXISTS operation_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    batch_id INTEGER REFERENCES import_batch(id),
                    song_id INTEGER REFERENCES song(id),
                    action TEXT NOT NULL,
                    old_path TEXT,
                    new_path TEXT,
                    state TEXT NOT NULL,
                    timestamp TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                );
                """
            # These are fixed, simple CREATE TABLE statements without embedded
            # semicolons. executescript would commit our version-check lock.
            for statement in schema.split(";"):
                if statement.strip():
                    db.execute(statement)
            db.executemany(
                "INSERT OR IGNORE INTO instrument(code, name) VALUES (?, ?)",
                INITIAL_INSTRUMENTS,
            )
            db.executemany(
                "INSERT OR IGNORE INTO category(code, name) VALUES (?, ?)",
                INITIAL_CATEGORIES,
            )
            db.execute(
                "INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)",
                ("numbering_scope", "global"),
            )
            columns = {
                row["name"]
                for row in db.execute("PRAGMA table_info(import_item)").fetchall()
            }
            if "source_sha256" not in columns:
                db.execute("ALTER TABLE import_item ADD COLUMN source_sha256 TEXT")
            warning_columns = {
                row["name"]
                for row in db.execute("PRAGMA table_info(warning)").fetchall()
            }
            if "fingerprint" not in warning_columns:
                db.execute("ALTER TABLE warning ADD COLUMN fingerprint TEXT")
            score_columns = {row["name"] for row in db.execute("PRAGMA table_info(score)")}
            if "storage_layout" not in score_columns:
                db.execute("ALTER TABLE score ADD COLUMN storage_layout TEXT NOT NULL DEFAULT 'dual'")
            song_columns = {row["name"] for row in db.execute("PRAGMA table_info(song)")}
            if "minimum_waived" not in song_columns:
                db.execute("ALTER TABLE song ADD COLUMN minimum_waived INTEGER NOT NULL DEFAULT 0 CHECK(minimum_waived IN (0,1))")

    def instrument_archive_modes(self) -> dict[str, str]:
        overrides = json.loads(self.get_setting("instrument_archive_modes") or "{}")
        return {code: overrides.get(code, "instrument_only" if code in {"08", "09"} else "dual")
                for code in self.instrument_codes()}

    def minimum_instruments(self, category_code: str) -> int:
        return int(json.loads(self.get_setting("category_minimum_instruments") or "{}").get(category_code, 3))

    def archive_eligible_codes(self, category_code: str, codes: set[str], song_id: int | None = None, force: bool = False) -> set[str]:
        modes = self.instrument_archive_modes()
        if force and not codes.issubset(modes):
            raise ValueError("乐器配置已变化，强制入库不能使用未启用的编号")
        song = self.get_archived_song(song_id) if song_id is not None else None
        if force or (song is not None and song["minimum_waived"]):
            return codes
        standalone = {code for code in codes if modes.get(code) == "instrument_only"}
        regular = codes - standalone
        existing = self.song_copy_codes(song_id) if song_id is not None else set()
        return codes if len(regular | existing) >= self.minimum_instruments(category_code) else standalone

    def song_copy_codes(self, song_id: int) -> set[str]:
        return {str(score["instrument_code"]) for score in self.list_archived_scores(song_id)
                if score["storage_layout"] == "dual"}

    def save_archive_rules(self, instrument_modes: dict[str, str], category_minimums: dict[str, int]) -> None:
        with self.transaction():
            if set(instrument_modes) != self.instrument_codes() or any(
                    mode not in {"dual", "instrument_only"} for mode in instrument_modes.values()):
                raise ValueError("乐器规则已变化或无效，请重新打开设置")
            categories = {str(row["code"]) for row in self.list_categories()}
            if set(category_minimums) != categories or any(type(n) is not int or not 1 <= n <= 99 for n in category_minimums.values()):
                raise ValueError("分类规则已变化，或最少乐器数不在1到99之间")
            self.set_setting("instrument_archive_modes", json.dumps(instrument_modes))
            self.set_setting("category_minimum_instruments", json.dumps(category_minimums))

    def list_categories(self) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(db.execute("SELECT code, name, active FROM category ORDER BY code"))

    def get_setting(self, key: str, default: str | None = None) -> str | None:
        with self.session() as db:
            row = db.execute("SELECT value FROM settings WHERE key = ?", (key,)).fetchone()
            if row is None:
                return default
            return str(row["value"])

    def set_setting(self, key: str, value: str) -> None:
        if not key.strip():
            raise ValueError("设置名称不能为空")
        with self.session() as db:
            db.execute(
                "INSERT OR REPLACE INTO settings(key, value) VALUES (?, ?)",
                (key.strip(), value),
            )

    def instrument_codes(self) -> set[str]:
        with self.session() as db:
            return {row["code"] for row in db.execute("SELECT code FROM instrument WHERE active = 1")}

    def category_codes(self) -> set[str]:
        with self.session() as db:
            return {row["code"] for row in db.execute("SELECT code FROM category WHERE active = 1")}

    def create_import_batch(self, state: str = "draft") -> int:
        with self.session() as db:
            cursor = db.execute("INSERT INTO import_batch(state) VALUES (?)", (state,))
            return int(cursor.lastrowid)

    def latest_draft_batch_id(self) -> int | None:
        with self.session() as db:
            row = db.execute(
                """
                SELECT import_batch.id
                FROM import_batch
                JOIN import_item ON import_item.batch_id = import_batch.id
                WHERE import_batch.state = 'draft'
                ORDER BY import_batch.id DESC
                LIMIT 1
                """
            ).fetchone()
            return None if row is None else int(row["id"])

    def latest_import_batch_id(self) -> int | None:
        with self.session() as db:
            row = db.execute(
                """
                SELECT import_batch.id
                FROM import_batch
                JOIN import_item ON import_item.batch_id = import_batch.id
                ORDER BY import_batch.id DESC
                LIMIT 1
                """
            ).fetchone()
            return None if row is None else int(row["id"])

    def add_import_item(
        self,
        batch_id: int,
        source_path: str,
        source_sha256: str | None,
        status: str,
        parsed_fields: dict[str, object],
        decision: str | None = None,
    ) -> int:
        with self.session() as db:
            cursor = db.execute(
                """
                INSERT INTO import_item(
                    batch_id, source_path, source_sha256, status, parsed_fields, decision
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                (
                    batch_id,
                    source_path,
                    source_sha256,
                    status,
                    json.dumps(parsed_fields, ensure_ascii=False),
                    decision,
                ),
            )
            return int(cursor.lastrowid)

    def list_import_items(self, batch_id: int) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(
                db.execute(
                    """
                    SELECT id, source_path, source_sha256, status, parsed_fields, decision
                    FROM import_item
                    WHERE batch_id = ?
                    ORDER BY id
                    """,
                    (batch_id,),
                )
            )

    def get_import_item(self, item_id: int) -> sqlite3.Row | None:
        with self.session() as db:
            return db.execute(
                """
                SELECT id, batch_id, source_path, source_sha256, status, parsed_fields, decision
                FROM import_item
                WHERE id = ?
                """,
                (item_id,),
            ).fetchone()

    def list_recent_import_items(self, limit: int = 200) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(
                db.execute(
                    """
                    SELECT import_item.id,
                           import_item.source_path,
                           import_item.source_sha256,
                           import_item.status,
                           import_item.parsed_fields,
                           import_item.decision,
                           import_item.batch_id
                    FROM import_item
                    ORDER BY import_item.id DESC
                    LIMIT ?
                    """,
                    (limit,),
                )
            )

    def import_batch_summary(self, batch_id: int) -> dict[str, int]:
        with self.session() as db:
            rows = db.execute(
                """
                SELECT status, COUNT(*) AS count
                FROM import_item
                WHERE batch_id = ?
                GROUP BY status
                """,
                (batch_id,),
            ).fetchall()
        summary = {"total": 0, "recognized": 0, "needs_manual_entry": 0, "skipped": 0}
        for row in rows:
            status = row["status"]
            count = int(row["count"])
            summary["total"] += count
            if status in summary:
                summary[status] = count
        return summary

    def clear_draft_batch(self, batch_id: int) -> int:
        with self.session() as db:
            batch = db.execute(
                "SELECT state FROM import_batch WHERE id = ?",
                (batch_id,),
            ).fetchone()
            if batch is None:
                return 0
            if batch["state"] != "draft":
                raise ValueError("只能清除尚未归档的草稿")
            count = int(
                db.execute(
                    "SELECT COUNT(*) AS count FROM import_item WHERE batch_id = ?",
                    (batch_id,),
                ).fetchone()["count"]
            )
            db.execute("DELETE FROM warning WHERE batch_id = ?", (batch_id,))
            db.execute("DELETE FROM import_item WHERE batch_id = ?", (batch_id,))
            if db.execute("SELECT 1 FROM operation_log WHERE batch_id=? LIMIT 1", (batch_id,)).fetchone():
                db.execute("UPDATE import_batch SET state='processed' WHERE id=?", (batch_id,))
            else:
                db.execute("DELETE FROM import_batch WHERE id = ?", (batch_id,))
            return count

    def remove_import_items_from_draft(self, batch_id: int, item_ids: list[int]) -> int:
        if not item_ids:
            return self.import_batch_summary(batch_id)["total"]
        placeholders = ",".join("?" for _ in item_ids)
        with self.session() as db:
            batch = db.execute(
                "SELECT state FROM import_batch WHERE id = ?",
                (batch_id,),
            ).fetchone()
            if batch is None:
                return 0
            if batch["state"] != "draft":
                raise ValueError("只能更新尚未归档的草稿")
            db.execute("DELETE FROM warning WHERE batch_id = ?", (batch_id,))
            db.execute(
                f"DELETE FROM import_item WHERE batch_id = ? AND id IN ({placeholders})",
                [batch_id, *item_ids],
            )
            remaining = int(
                db.execute(
                    "SELECT COUNT(*) AS count FROM import_item WHERE batch_id = ?",
                    (batch_id,),
                ).fetchone()["count"]
            )
            if remaining == 0:
                if db.execute("SELECT 1 FROM operation_log WHERE batch_id=? LIMIT 1", (batch_id,)).fetchone():
                    db.execute("UPDATE import_batch SET state='processed' WHERE id=?", (batch_id,))
                else:
                    db.execute("DELETE FROM import_batch WHERE id = ?", (batch_id,))
            return remaining

    def replace_batch_warnings(self, batch_id: int, warnings: list[dict[str, object]]) -> None:
        with self.session() as db:
            previous = {
                row["fingerprint"]: (row["state"], row["resolution"], row["id"])
                for row in db.execute(
                    """
                    SELECT id, fingerprint, state, resolution
                    FROM warning
                    WHERE batch_id = ? AND song_id IS NULL AND fingerprint IS NOT NULL
                    """,
                    (batch_id,),
                )
            }
            db.execute("DELETE FROM warning WHERE batch_id = ? AND song_id IS NULL", (batch_id,))
            db.executemany(
                """
                INSERT INTO warning(id, batch_id, code, fingerprint, severity, state, details, resolution)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                [
                    (
                        previous.get(str(warning["fingerprint"]), ("待处理", None, None))[2],
                        batch_id,
                        str(warning["code"]),
                        str(warning["fingerprint"]),
                        str(warning["severity"]),
                        previous.get(str(warning["fingerprint"]), ("待处理", None))[0],
                        json.dumps(warning["details"], ensure_ascii=False),
                        previous.get(str(warning["fingerprint"]), ("待处理", None))[1],
                    )
                    for warning in warnings
                ],
            )

    def list_warnings(self, batch_id: int) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(
                db.execute(
                    """
                    SELECT id, code, fingerprint, severity, state, details, resolution
                    FROM warning
                    WHERE batch_id = ?
                    ORDER BY id
                    """,
                    (batch_id,),
                )
            )

    def update_warning_state(self, warning_id: int, state: str, resolution: str | None = None) -> None:
        allowed = {"待处理", "已处理", "已确认无须修改"}
        if state not in allowed:
            raise ValueError("预警状态无效")
        with self.session() as db:
            warning = db.execute("SELECT code FROM warning WHERE id = ?", (warning_id,)).fetchone()
            if warning and warning["code"] in {"duplicate_file", "duplicate_existing_file", "same_instrument_conflict", "existing_instrument_conflict"} and state != "待处理":
                raise ValueError("请先对比 PDF，移除重复分谱或修改曲目信息；重复冲突不能直接忽略")
            cursor = db.execute(
                """
                UPDATE warning
                SET state = ?, resolution = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """,
                (state, resolution, warning_id),
            )
            if cursor.rowcount == 0:
                raise ValueError("预警不存在")

    def update_import_item_manual_fields(
        self,
        item_id: int,
        instrument_code: str | None = None,
        category_code: str | None = None,
        title: str | None = None,
    ) -> None:
        with self.session() as db:
            row = db.execute(
                "SELECT parsed_fields FROM import_item WHERE id = ?", (item_id,)
            ).fetchone()
            if row is None:
                raise ValueError("导入条目不存在")
            fields = json.loads(row["parsed_fields"] or "{}")
            if instrument_code is not None:
                if instrument_code not in self.instrument_codes():
                    raise ValueError("乐器编号未定义")
                fields["instrument_code"] = instrument_code
            if category_code is not None:
                if category_code not in self.category_codes():
                    raise ValueError("分类编号未定义")
                fields["category_code"] = category_code
            if title is not None:
                if not title.strip():
                    raise ValueError("曲名不能为空")
                fields["title"] = title.strip()
            has_required = fields.get("instrument_code") and fields.get("category_code") and fields.get("title")
            status = "recognized" if has_required else "needs_manual_entry"
            db.execute(
                """
                UPDATE import_item
                SET parsed_fields = ?, status = ?, decision = ?
                WHERE id = ?
                """,
                (json.dumps(fields, ensure_ascii=False), status, "manual_entry", item_id),
            )

    def upsert_category(self, code: str, name: str, active: bool = True) -> None:
        if not code.isdigit() or len(code) != 2:
            raise ValueError("分类编号必须是两位数字")
        if not name.strip():
            raise ValueError("分类名称不能为空")
        with self.session() as db:
            db.execute(
                """
                INSERT INTO category(code, name, active) VALUES (?, ?, ?)
                ON CONFLICT(code) DO UPDATE SET name = excluded.name, active = excluded.active
                """,
                (code, name.strip(), int(active)),
            )

    def list_instruments(self):
        with self.session() as connection:
            return list(connection.execute("SELECT code, name, active FROM instrument ORDER BY code"))

    def upsert_instrument(self, code: str, name: str, active: bool = True, archive_mode: str = "dual") -> None:
        import re

        code, name = code.strip(), name.strip()
        if not re.fullmatch(r"[0-9]{2}", code):
            raise ValueError("乐器编号必须是两位数字")
        if not name or re.search(r'[<>:"/\\|?*\x00-\x1f]', name) or name.endswith(".") or len(name.encode("utf-16-le")) // 2 > 80:
            raise ValueError("乐器名称不能为空、不能超过80字或包含Windows非法字符")
        if archive_mode not in {"dual", "instrument_only"}:
            raise ValueError("请选择有效的归档方式")
        with self.transaction() as connection:
            existing = connection.execute("SELECT id, name FROM instrument WHERE code=?", (code,)).fetchone()
            if existing and existing["name"] != name and connection.execute(
                    "SELECT 1 FROM score WHERE instrument_id=? LIMIT 1", (existing["id"],)).fetchone():
                raise ValueError("该乐器已有入库分谱，不能直接改名；现有编号和存档路径必须保留")
            if not active:
                for item in connection.execute("SELECT parsed_fields FROM import_item JOIN import_batch ON import_batch.id=import_item.batch_id WHERE import_batch.state='draft'"):
                    if json.loads(item["parsed_fields"] or "{}").get("instrument_code") == code:
                        raise ValueError("草稿中仍有该乐器的分谱，请处理草稿后再停用")
            connection.execute("INSERT INTO instrument(code,name,active) VALUES (?,?,?) ON CONFLICT(code) DO UPDATE SET name=excluded.name,active=excluded.active",
                               (code, name, int(active)))
            modes = json.loads(self.get_setting("instrument_archive_modes") or "{}")
            modes[code] = archive_mode
            self.set_setting("instrument_archive_modes", json.dumps(modes))

    def allocate_next_sequence(self) -> str:
        with self.session() as db:
            rows = db.execute(
                "SELECT seq FROM song WHERE seq IS NOT NULL ORDER BY CAST(seq AS INTEGER)"
            )
            return next_global_sequence([row["seq"] for row in rows], naming_version=self._numbering_version).value

    def create_song(
        self,
        category_code: str,
        seq: str | None,
        base_title: str,
        version_no: int = 1,
        status: str = "draft",
    ) -> int:
        if not base_title.strip():
            raise ValueError("曲名不能为空")
        normalized_seq = normalize_seq(seq, naming_version=self._numbering_version) if seq else None
        display_title = base_title.strip()
        with self.session() as db:
            category = db.execute(
                "SELECT id FROM category WHERE code = ?", (category_code,)
            ).fetchone()
            if category is None:
                raise ValueError("分类不存在")
            cursor = db.execute(
                """
                INSERT INTO song(
                    category_id, seq, base_title, normalized_title, display_title, version_no, status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    category["id"],
                    normalized_seq,
                    base_title.strip(),
                    base_title.strip().casefold(),
                    display_title,
                    version_no,
                    status,
                ),
            )
            return int(cursor.lastrowid)

    def add_score(
        self,
        song_id: int,
        instrument_code: str,
        source_name: str,
        source_path: str,
        source_sha256: str,
        output_name: str,
        storage_layout: str = "dual",
    ) -> int:
        if storage_layout not in {"dual", "instrument_only"}:
            raise ValueError("分谱存档规则无效")
        with self.session() as db:
            instrument = db.execute(
                "SELECT id FROM instrument WHERE code = ?", (instrument_code,)
            ).fetchone()
            if instrument is None:
                raise ValueError("乐器不存在")
            cursor = db.execute(
                """
                INSERT INTO score(
                    song_id, instrument_id, source_name, source_path, source_sha256, output_name, storage_layout
                )
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    song_id,
                    instrument["id"],
                    source_name,
                    source_path,
                    source_sha256,
                    output_name,
                    storage_layout,
                ),
            )
            return int(cursor.lastrowid)

    def mark_import_batch_archived(self, batch_id: int) -> None:
        with self.session() as db:
            db.execute("UPDATE import_batch SET state = ? WHERE id = ?", ("archived", batch_id))

    def count_songs(self) -> int:
        with self.session() as db:
            return int(db.execute("SELECT COUNT(*) AS count FROM song").fetchone()["count"])

    def count_scores(self) -> int:
        with self.session() as db:
            return int(db.execute("SELECT COUNT(*) AS count FROM score").fetchone()["count"])

    def clear_library_data(self) -> None:
        with self.session() as db:
            for table in ("operation_log", "warning", "title_alias", "score", "import_item", "song", "import_batch"):
                db.execute(f"DELETE FROM {table}")

    def find_archived_song(self, category_code: str, title: str) -> sqlite3.Row | None:
        with self.session() as db:
            return db.execute(
                """
                SELECT song.id, song.seq, song.display_title, song.minimum_waived
                FROM song
                JOIN category ON category.id = song.category_id
                WHERE category.code = ? AND song.display_title = ? AND song.status = 'archived'
                ORDER BY song.id
                LIMIT 1
                """,
                (category_code, title),
            ).fetchone()

    def archived_instrument_count(self, song_id: int) -> int:
        with self.session() as db:
            return int(
                db.execute(
                    "SELECT COUNT(DISTINCT instrument_id) AS count FROM score WHERE song_id = ?",
                    (song_id,),
                ).fetchone()["count"]
            )

    def archived_instrument_codes(self, song_id: int) -> set[str]:
        with self.session() as db:
            return {
                str(row["code"])
                for row in db.execute(
                    """
                    SELECT DISTINCT instrument.code
                    FROM score
                    JOIN instrument ON instrument.id = score.instrument_id
                    WHERE score.song_id = ?
                    """,
                    (song_id,),
                )
            }

    def list_archived_scores(self, song_id: int | None = None) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(db.execute(
                """SELECT score.*, song.seq, song.display_title,
                          category.code AS category_code, instrument.code AS instrument_code,
                          instrument.name AS instrument_name
                   FROM score JOIN song ON song.id = score.song_id
                   JOIN category ON category.id = song.category_id
                   JOIN instrument ON instrument.id = score.instrument_id
                   WHERE song.status = 'archived' AND (? IS NULL OR song.id = ?)
                   ORDER BY CAST(song.seq AS INTEGER), instrument.code, score.id""",
                (song_id, song_id),
            ))

    def get_archived_song(self, song_id: int) -> sqlite3.Row | None:
        with self.session() as db:
            return db.execute(
                """SELECT song.*, category.code AS category_code
                   FROM song JOIN category ON category.id = song.category_id
                   WHERE song.id = ? AND song.status = 'archived'""", (song_id,),
            ).fetchone()

    def update_archived_song_metadata(self, song_id: int, category_code: str, title: str, output_names: dict[int, str]) -> None:
        with self.session() as db:
            category = db.execute("SELECT id FROM category WHERE code = ?", (category_code,)).fetchone()
            if category is None:
                raise ValueError("分类不存在")
            cursor = db.execute(
                """UPDATE song SET category_id = ?, base_title = ?, display_title = ?, normalized_title = ?
                   WHERE id = ? AND status = 'archived'""",
                (category["id"], title, title, title.casefold(), song_id),
            )
            if cursor.rowcount != 1:
                raise ValueError("已入库曲目不存在")
            for score_id, output_name in output_names.items():
                cursor = db.execute("UPDATE score SET output_name = ? WHERE id = ? AND song_id = ?", (output_name, score_id, song_id))
                if cursor.rowcount != 1:
                    raise ValueError("分谱记录已变化")

    def find_scores_by_hash(self, source_sha256: str) -> list[sqlite3.Row]:
        with self.session() as db:
            return list(
                db.execute(
                    """
                    SELECT score.id,
                           score.source_name,
                           score.source_path,
                           score.output_name,
                           song.display_title,
                           song.seq,
                           category.code AS category_code,
                           instrument.code AS instrument_code,
                           instrument.name AS instrument_name
                    FROM score
                    JOIN song ON song.id = score.song_id
                    JOIN category ON category.id = song.category_id
                    JOIN instrument ON instrument.id = score.instrument_id
                    WHERE score.source_sha256 = ? AND song.status != 'merged'
                    ORDER BY CAST(song.seq AS INTEGER), instrument.code, score.id
                    """,
                    (source_sha256,),
                )
            )
