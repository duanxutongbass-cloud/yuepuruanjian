from contextlib import closing
import hashlib
import sqlite3

import pytest

from score_organizer.storage.database import Database, UnsupportedDatabaseVersion


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def mark_version(path, version):
    with closing(sqlite3.connect(path)) as connection:
        connection.execute(f"PRAGMA user_version={int(version)}")
        connection.commit()


def initialized(tmp_path):
    database = Database(tmp_path / "乐谱库.sqlite3")
    database.initialize()
    database.create_song("01", "001", "2001太空漫游", status="archived")
    database.create_song("02", "998", "保留占号", status="merged")
    database.create_song("01", "999", "第二版", version_no=2, status="archived")
    return database


def test_existing_version_zero_preserves_numbering_and_rows(tmp_path):
    database = initialized(tmp_path)
    with closing(sqlite3.connect(database.path)) as connection:
        before = connection.execute("SELECT * FROM song ORDER BY id").fetchall()
    database.initialize()
    with closing(sqlite3.connect(database.path)) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 0
        assert connection.execute("SELECT * FROM song ORDER BY id").fetchall() == before
        assert connection.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert connection.execute("PRAGMA foreign_key_check").fetchall() == []


@pytest.mark.parametrize("version", [1, 3, -1, 2147483647])
def test_unknown_version_rejected_before_initialize_read_write_or_transaction(tmp_path, version):
    database = initialized(tmp_path)
    mark_version(database.path, version)
    before = digest(database.path)
    for operation in (database.initialize, database.count_songs,
                      lambda: database.create_song("01", None, "不得写入")):
        with pytest.raises(UnsupportedDatabaseVersion) as caught:
            operation()
        assert caught.value.version == version
        assert digest(database.path) == before
    with pytest.raises(UnsupportedDatabaseVersion):
        with database.transaction() as connection:
            connection.execute("DELETE FROM song")
    assert digest(database.path) == before


def test_rejection_closes_connection_and_does_not_leave_windows_file_locked(tmp_path):
    database = initialized(tmp_path)
    mark_version(database.path, 3)
    before = digest(database.path)
    for _ in range(3):
        with pytest.raises(UnsupportedDatabaseVersion):
            database.connect()
    moved = database.path.with_name("保留的原库.sqlite3")
    database.path.rename(moved)
    assert digest(moved) == before


def test_existing_database_object_checks_each_new_session(tmp_path):
    database = initialized(tmp_path)
    assert database.count_songs() == 3
    mark_version(database.path, 3)
    before = digest(database.path)
    with pytest.raises(UnsupportedDatabaseVersion):
        database.set_setting("after-upgrade", "must not write")
    assert digest(database.path) == before


def test_wal_upgrade_cannot_write_through_an_old_checked_session(tmp_path):
    database = initialized(tmp_path)
    with closing(sqlite3.connect(database.path)) as connection:
        assert connection.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
    with pytest.raises(sqlite3.OperationalError):
        with database.session() as older:
            # A second real connection commits a format change while the first
            # has only read the version. The old snapshot must not write later.
            mark_version(database.path, 3)
            older.execute("INSERT INTO settings VALUES('late-write','must not exist')")
    with closing(sqlite3.connect(database.path)) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 3
        assert connection.execute("SELECT value FROM settings WHERE key='late-write'").fetchone() is None
        assert connection.execute("SELECT count(*) FROM song").fetchone()[0] == 3


@pytest.mark.parametrize("entry", ["initialize", "session", "transaction"])
def test_version_rechecked_after_connect_before_transaction_work(tmp_path, entry):
    original = initialized(tmp_path)

    class UpgradedBetweenConnectAndBegin(Database):
        def connect(self):
            connection = super().connect()
            mark_version(self.path, 3)
            return connection

    database = UpgradedBetweenConnectAndBegin(original.path)
    with pytest.raises(UnsupportedDatabaseVersion):
        if entry == "initialize":
            database.initialize()
        else:
            with getattr(database, entry)() as connection:
                connection.execute("INSERT INTO settings VALUES('late-write','must not exist')")
    with closing(sqlite3.connect(database.path)) as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 3
        assert connection.execute("SELECT value FROM settings WHERE key='late-write'").fetchone() is None
        assert connection.execute("SELECT count(*) FROM song").fetchone()[0] == 3


def test_initialization_failure_rolls_back_ddl_and_default_rows(tmp_path):
    path = tmp_path / "old.sqlite3"
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("CREATE TABLE preserved(id INTEGER PRIMARY KEY, value TEXT)")
        connection.execute("INSERT INTO preserved VALUES(1,'keep')")
        connection.commit()
        before_schema = connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall()

    class DeniedMigration(Database):
        def connect(self):
            connection = super().connect()
            connection.set_authorizer(lambda action, *args:
                sqlite3.SQLITE_DENY if action == sqlite3.SQLITE_ALTER_TABLE else sqlite3.SQLITE_OK)
            return connection

    with pytest.raises(sqlite3.DatabaseError, match="authorized"):
        DeniedMigration(path).initialize()
    with closing(sqlite3.connect(path)) as connection:
        assert connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall() == before_schema
        assert connection.execute("SELECT * FROM preserved").fetchall() == [(1, "keep")]
    # A failed first attempt must not leave a partial schema that poisons retry.
    Database(path).initialize()
    assert Database(path).count_songs() == 0


def test_nested_sessions_reuse_transaction_and_rollback_all_changes(tmp_path):
    database = initialized(tmp_path)
    with pytest.raises(RuntimeError, match="interrupt"):
        with database.transaction():
            database.set_setting("pending", "rollback")
            database.create_song("01", None, "new draft")
            raise RuntimeError("interrupt")
    assert database.get_setting("pending") is None
    assert database.count_songs() == 3


def test_session_interrupt_rolls_back_and_closes_connection(tmp_path):
    database = initialized(tmp_path)
    with pytest.raises(KeyboardInterrupt):
        with database.session() as connection:
            connection.execute("INSERT INTO settings VALUES('pending','rollback')")
            raise KeyboardInterrupt()
    assert database.get_setting("pending") is None
    assert database._transaction_connection is None


def test_startup_displays_version_error_without_opening_library(tmp_path, monkeypatch):
    from PySide6.QtWidgets import QApplication, QMessageBox
    from score_organizer import main

    app = QApplication.instance() or QApplication([])
    database = initialized(tmp_path)
    mark_version(database.path, 3)
    before = digest(database.path)
    notices = []
    monkeypatch.setattr(main, "default_db_path", lambda: database.path)
    monkeypatch.setattr(main, "expanded_profile", lambda: tmp_path / 'isolated-profile')
    monkeypatch.setattr(QMessageBox, "critical", lambda parent, title, text:
                        notices.append((title, text, QApplication.instance() is app)))
    assert main.main() == 2
    assert len(notices) == 1 and notices[0][0] == "乐谱库版本不兼容"
    assert "不支持的数据格式（3）" in notices[0][1]
    assert notices[0][2]
    assert digest(database.path) == before
