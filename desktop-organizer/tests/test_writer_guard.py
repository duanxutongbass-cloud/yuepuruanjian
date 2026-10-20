from contextlib import closing
import sqlite3

import pytest

from score_organizer.storage.database import Database, UnsupportedDatabaseVersion
from score_organizer.storage.writer_guard import (
    REQUIRED_TABLES, TRIGGER_PREFIX, WRITER_FUNCTION, WriteGuardError,
    install_format_one_guards, register_format_one_writer, require_format_one_guards,
)


def make_guarded(tmp_path, *, extra_sql=None):
    path = tmp_path / "candidate.sqlite3"
    db = Database(path)
    db.initialize()
    db.create_song("01", "999", "保留原号", status="archived")
    with closing(sqlite3.connect(path)) as connection:
        if extra_sql:
            connection.execute(extra_sql)
        connection.execute("PRAGMA user_version=1")
        connection.commit()
        register_format_one_writer(connection)
        connection.execute("BEGIN IMMEDIATE")
        install_format_one_guards(connection)
        connection.commit()
    return path


def rows(connection):
    return {table: connection.execute(f'SELECT * FROM "{table}" ORDER BY 1').fetchall()
            for table in sorted(REQUIRED_TABLES)}


@pytest.mark.parametrize("table", sorted(REQUIRED_TABLES))
@pytest.mark.parametrize("operation", ["INSERT", "UPDATE", "DELETE"])
def test_unregistered_old_connection_cannot_mutate_any_application_table(tmp_path, table, operation):
    path = make_guarded(tmp_path)
    before_bytes = path.read_bytes()
    with closing(sqlite3.connect(path)) as old:
        before = rows(old)
        column = old.execute(f'PRAGMA table_info("{table}")').fetchone()[1]
        sql = {"INSERT": f'INSERT INTO "{table}" DEFAULT VALUES',
               "UPDATE": f'UPDATE "{table}" SET "{column}"="{column}"',
               "DELETE": f'DELETE FROM "{table}"'}[operation]
        with pytest.raises(sqlite3.OperationalError, match=WRITER_FUNCTION):
            old.execute(sql)
        old.rollback()
        assert rows(old) == before
    assert path.read_bytes() == before_bytes


def test_supported_connection_writes_and_backup_retains_protection(tmp_path):
    path = make_guarded(tmp_path)
    backup = tmp_path / "backup.sqlite3"
    with closing(sqlite3.connect(path)) as new:
        register_format_one_writer(new)
        new.execute("BEGIN IMMEDIATE")
        require_format_one_guards(new)
        new.execute("UPDATE song SET display_title='核验后更名' WHERE seq='999'")
        new.commit()
        with closing(sqlite3.connect(backup)) as target:
            new.backup(target)
            assert target.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    with closing(sqlite3.connect(backup)) as old:
        assert old.execute("SELECT display_title FROM song").fetchone()[0] == "核验后更名"
        with pytest.raises(sqlite3.OperationalError, match=WRITER_FUNCTION):
            old.execute("DELETE FROM song")


def test_existing_prepared_statement_is_recompiled_after_guard_install(tmp_path):
    path = tmp_path / "candidate.sqlite3"
    db = Database(path)
    db.initialize()
    db.set_setting("retained", "before")
    with closing(sqlite3.connect(path)) as old, closing(sqlite3.connect(path)) as migration:
        sql = "UPDATE settings SET value=? WHERE key='retained'"
        old.execute(sql, ("before",))
        old.commit()
        migration.execute("PRAGMA user_version=1")
        register_format_one_writer(migration)
        migration.execute("BEGIN IMMEDIATE")
        install_format_one_guards(migration)
        migration.commit()
        with pytest.raises(sqlite3.OperationalError, match=WRITER_FUNCTION):
            old.execute(sql, ("must not persist",))
        old.rollback()
        assert old.execute("SELECT value FROM settings WHERE key='retained'").fetchone()[0] == "before"


def test_wal_reader_cannot_upgrade_stale_snapshot_to_a_writer(tmp_path):
    path = tmp_path / "candidate.sqlite3"
    Database(path).initialize()
    with closing(sqlite3.connect(path)) as old, closing(sqlite3.connect(path)) as migration:
        old.execute("PRAGMA journal_mode=WAL")
        old.execute("BEGIN")
        old.execute("SELECT * FROM instrument").fetchall()
        migration.execute("BEGIN IMMEDIATE")
        migration.execute("PRAGMA user_version=1")
        register_format_one_writer(migration)
        install_format_one_guards(migration)
        migration.commit()
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            old.execute("UPDATE instrument SET name='must not persist'")
        old.rollback()
        with pytest.raises(sqlite3.OperationalError, match=WRITER_FUNCTION):
            old.execute("UPDATE instrument SET name='must not persist'")


@pytest.mark.parametrize("capability", [None, 0, 2, "1"])
def test_wrong_capability_fails_instead_of_sql_null_bypassing_guard(tmp_path, capability):
    path = make_guarded(tmp_path)
    with closing(sqlite3.connect(path)) as writer:
        writer.create_function(WRITER_FUNCTION, 0, lambda: capability)
        with pytest.raises(sqlite3.IntegrityError, match="新版整理器"):
            writer.execute("DELETE FROM song")
        writer.rollback()
        assert writer.execute("SELECT seq FROM song").fetchone()[0] == "999"


@pytest.mark.parametrize("damage", ["drop", "replace", "extra", "new_table"])
def test_missing_changed_or_incomplete_guard_set_is_rejected(tmp_path, damage):
    path = make_guarded(tmp_path)
    with closing(sqlite3.connect(path)) as connection:
        register_format_one_writer(connection)
        connection.execute("BEGIN IMMEDIATE")
        name = connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger' ORDER BY name").fetchone()[0]
        if damage in ("drop", "replace"):
            connection.execute(f'DROP TRIGGER "{name}"')
        if damage == "replace":
            connection.execute(f'CREATE TRIGGER "{name}" BEFORE INSERT ON song BEGIN SELECT 1; END')
        if damage == "extra":
            connection.execute(f'CREATE TRIGGER "{TRIGGER_PREFIX}extra" BEFORE INSERT ON song BEGIN SELECT 1; END')
        if damage == "new_table":
            connection.execute("CREATE TABLE plugin_data(value TEXT)")
        with pytest.raises(WriteGuardError, match="缺失或变化"):
            require_format_one_guards(connection)
        connection.rollback()


def test_additional_quoted_table_is_guarded_without_replacing_custom_trigger(tmp_path):
    path = make_guarded(tmp_path, extra_sql='CREATE TABLE "自定义""表"(value TEXT)')
    with closing(sqlite3.connect(path)) as connection:
        with pytest.raises(sqlite3.OperationalError, match=WRITER_FUNCTION):
            connection.execute('INSERT INTO "自定义""表" VALUES (\'keep out\')')
        connection.rollback()
        register_format_one_writer(connection)
        connection.execute('CREATE TRIGGER custom AFTER UPDATE ON song BEGIN INSERT INTO "自定义""表" VALUES(NEW.seq); END')
        connection.execute("BEGIN IMMEDIATE")
        require_format_one_guards(connection)
        connection.execute("UPDATE song SET display_title='new'")
        assert connection.execute('SELECT value FROM "自定义""表"').fetchall() == [("999",)]
        connection.rollback()


def test_install_failure_rolls_back_its_ddl_even_if_caller_commits(tmp_path):
    path = tmp_path / "candidate.sqlite3"
    Database(path).initialize()
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("PRAGMA user_version=1")
        register_format_one_writer(connection)
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("INSERT INTO settings VALUES('outer_work','keep')")
        create_count = 0
        def deny_third_trigger(action, *_):
            nonlocal create_count
            if action == sqlite3.SQLITE_CREATE_TRIGGER:
                create_count += 1
                if create_count == 3:
                    return sqlite3.SQLITE_DENY
            return sqlite3.SQLITE_OK
        connection.set_authorizer(deny_third_trigger)
        with pytest.raises(sqlite3.DatabaseError, match="authorized"):
            install_format_one_guards(connection)
        connection.set_authorizer(None)
        connection.commit()
        assert create_count == 3
        assert connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger'").fetchall() == []
        assert connection.execute("SELECT value FROM settings WHERE key='outer_work'").fetchone()[0] == "keep"


def test_duplicate_install_and_out_of_transaction_checks_do_not_change_existing_guard(tmp_path):
    path = make_guarded(tmp_path)
    with closing(sqlite3.connect(path)) as connection:
        register_format_one_writer(connection)
        for action in (install_format_one_guards, require_format_one_guards):
            with pytest.raises(WriteGuardError, match="事务"):
                action(connection)
        connection.execute("BEGIN IMMEDIATE")
        before = connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall()
        with pytest.raises(WriteGuardError, match="不能覆盖"):
            install_format_one_guards(connection)
        assert connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall() == before
        require_format_one_guards(connection)
        connection.rollback()


def test_live_database_version_is_not_advanced_or_automatically_authorized(tmp_path):
    path = tmp_path / "live.sqlite3"
    db = Database(path)
    db.initialize()
    with closing(db.connect()) as connection:
        with pytest.raises(WriteGuardError, match="1版"):
            register_format_one_writer(connection)
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 0
        assert connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger'").fetchall() == []
    guarded = make_guarded(tmp_path)
    with pytest.raises(UnsupportedDatabaseVersion):
        Database(guarded).initialize()


@pytest.mark.parametrize("problem", ["missing_table", "reserved_table", "no_capability", "wrong_capability"])
def test_install_preflight_refuses_unsupported_or_occupied_schema_without_partial_guards(tmp_path, problem):
    path = tmp_path / "candidate.sqlite3"
    Database(path).initialize()
    with closing(sqlite3.connect(path)) as connection:
        connection.execute("PRAGMA user_version=1")
        connection.execute("BEGIN IMMEDIATE")
        if problem == "missing_table":
            connection.execute("DROP TABLE operation_log")
        if problem == "reserved_table":
            connection.execute(f'CREATE TABLE "{TRIGGER_PREFIX.upper()}occupied"(value TEXT)')
        if problem != "no_capability":
            register_format_one_writer(connection)
        if problem == "wrong_capability":
            connection.create_function(WRITER_FUNCTION, 0, lambda: 0)
        before = connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall()
        with pytest.raises(WriteGuardError):
            install_format_one_guards(connection)
        assert connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall() == before
        connection.rollback()


def test_outer_migration_rollback_removes_guard_and_version_together(tmp_path):
    path = tmp_path / "candidate.sqlite3"
    Database(path).initialize()
    with closing(sqlite3.connect(path)) as connection:
        before = connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall()
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("PRAGMA user_version=1")
        register_format_one_writer(connection)
        install_format_one_guards(connection)
        connection.rollback()
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 0
        assert connection.execute("SELECT * FROM sqlite_schema ORDER BY name").fetchall() == before
    Database(path).create_song("01", "001", "仍可正常使用")
