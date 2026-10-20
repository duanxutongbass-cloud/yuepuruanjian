from contextlib import closing
import json
from pathlib import Path
import os
import sqlite3

import pytest

from score_organizer.services.import_service import sha256_file
from score_organizer.services.reset_service import MANAGED_FOLDERS, ResetService
from score_organizer.storage.database import UnsupportedDatabaseVersion
from test_reset_service import filled_library


def cleared_library(tmp_path):
    database, root, sources = filled_library(tmp_path)
    service = ResetService(database)
    backup = service.clear(service.build_plan())
    return database, root, sources, service, backup


def update_manifest_hash(backup):
    manifest_path = backup / "清空记录.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    manifest["database_sha256"] = sha256_file(backup / "清空前.sqlite3")
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


def preserved_files(database, sources, backup):
    paths = [database.path, *sources, *backup.rglob("*.pdf")]
    return {path: sha256_file(path) for path in paths}


def assert_empty_and_preserved(database, root, backup, before):
    assert database.count_songs() == database.count_scores() == 0
    assert all(not (root / name).exists() and (backup / name).is_dir() for name in MANAGED_FOLDERS)
    assert all(sha256_file(path) == digest for path, digest in before.items())
    assert json.loads((backup / "清空记录.json").read_text(encoding="utf-8"))["state"] == "completed"


@pytest.mark.parametrize("version", [1, 3, -1])
def test_future_backup_refused_even_when_columns_and_manifest_hash_match(tmp_path, version):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    with closing(sqlite3.connect(snapshot)) as connection:
        connection.execute(f"PRAGMA user_version={version}")
        connection.commit()
    update_manifest_hash(backup)
    before = preserved_files(database, sources, backup)
    original_snapshot = sha256_file(snapshot)
    original_manifest = (backup / "清空记录.json").read_bytes()
    with pytest.raises(UnsupportedDatabaseVersion):
        service.restore(backup)
    assert_empty_and_preserved(database, root, backup, before)
    assert sha256_file(snapshot) == original_snapshot
    assert (backup / "清空记录.json").read_bytes() == original_manifest
    assert not list(backup.glob("恢复前_*.sqlite3"))
    # Read-only preflight must close its connection on the rejection path.
    renamed = snapshot.with_name("原样保留.sqlite3")
    snapshot.rename(renamed)
    assert sha256_file(renamed) == original_snapshot


def test_corrupt_snapshot_refused_before_new_recovery_files_or_moves(tmp_path):
    database, root, sources, service, backup = cleared_library(tmp_path)
    (backup / "清空前.sqlite3").write_bytes(b"not a SQLite database")
    update_manifest_hash(backup)
    before = preserved_files(database, sources, backup)
    with pytest.raises(ValueError, match="无法读取"):
        service.restore(backup)
    assert_empty_and_preserved(database, root, backup, before)
    assert not list(backup.glob("恢复前_*.sqlite3"))


def test_foreign_key_damage_refused_before_live_records_are_changed(tmp_path):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    with closing(sqlite3.connect(snapshot)) as connection:
        connection.execute("PRAGMA foreign_keys=OFF")
        connection.execute("UPDATE score SET song_id=987654")
        connection.commit()
    update_manifest_hash(backup)
    before = preserved_files(database, sources, backup)
    with pytest.raises(ValueError, match="关联数据"):
        service.restore(backup)
    assert_empty_and_preserved(database, root, backup, before)
    assert not list(backup.glob("恢复前_*.sqlite3"))


def test_committed_wal_outside_manifest_hash_is_not_silently_restored(tmp_path):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    before = preserved_files(database, sources, backup)
    with closing(sqlite3.connect(snapshot)) as writer:
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("PRAGMA wal_autocheckpoint=0")
        update_manifest_hash(backup)
        recorded_hash = sha256_file(snapshot)
        writer.execute("INSERT INTO settings VALUES('unhashed-wal-record','do not import')")
        writer.commit()
        assert sha256_file(snapshot) == recorded_hash
        assert Path(str(snapshot) + "-wal").stat().st_size > 0
        with pytest.raises(ValueError, match="日志文件"):
            service.restore(backup)
        assert_empty_and_preserved(database, root, backup, before)
        assert database.get_setting("unhashed-wal-record") is None


@pytest.mark.parametrize("suffix", ["-journal", "-shm"])
def test_unaccounted_sidecars_block_restore_without_deleting_them(tmp_path, suffix):
    database, root, sources, service, backup = cleared_library(tmp_path)
    sidecar = Path(str(backup / "清空前.sqlite3") + suffix)
    sidecar.write_bytes(b"preserve incomplete snapshot evidence")
    before = preserved_files(database, sources, backup)
    with pytest.raises(ValueError, match="日志文件"):
        service.restore(backup)
    assert sidecar.read_bytes() == b"preserve incomplete snapshot evidence"
    assert_empty_and_preserved(database, root, backup, before)


def test_backup_changed_after_preflight_is_rejected_before_restore(tmp_path, monkeypatch):
    database, root, sources, service, backup = cleared_library(tmp_path)
    before = preserved_files(database, sources, backup)
    original = service._snapshot_database

    def change_after_preflight(destination):
        original(destination)
        with closing(sqlite3.connect(backup / "清空前.sqlite3")) as writer:
            writer.execute("PRAGMA user_version=3")
            writer.commit()

    monkeypatch.setattr(service, "_snapshot_database", change_after_preflight)
    with pytest.raises(ValueError, match="备份保留"):
        service.restore(backup)
    assert_empty_and_preserved(database, root, backup, before)


def test_attached_snapshot_rechecks_version_before_deleting_any_records(tmp_path):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    with closing(sqlite3.connect(snapshot)) as connection:
        connection.execute("PRAGMA user_version=3")
        connection.commit()
    expected = sha256_file(snapshot)
    before = preserved_files(database, sources, backup)
    with pytest.raises(UnsupportedDatabaseVersion):
        with database.transaction():
            service._restore_database(snapshot, expected_sha256=expected)
    assert_empty_and_preserved(database, root, backup, before)
    assert sha256_file(snapshot) == expected


def test_attached_snapshot_is_read_only_and_successful_restore_preserves_it(tmp_path, monkeypatch):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    expected = sha256_file(snapshot)
    original = service._validate_snapshot_database
    observed = []

    def check_read_only(connection, *, schema="main"):
        original(connection, schema=schema)
        if schema == "reset_backup":
            with pytest.raises(sqlite3.OperationalError, match="readonly"):
                connection.execute("UPDATE reset_backup.song SET display_title='must not write'")
            observed.append(True)

    monkeypatch.setattr(service, "_validate_snapshot_database", check_read_only)
    service.restore(backup)
    assert observed == [True]
    assert database.count_scores() == 3
    assert sha256_file(snapshot) == expected
    assert all((root / name).exists() for name in MANAGED_FOLDERS)


def test_live_wal_library_produces_standalone_restorable_snapshot(tmp_path):
    database, root, sources = filled_library(tmp_path)
    with closing(sqlite3.connect(database.path)) as writer:
        writer.execute("PRAGMA journal_mode=WAL")
        writer.execute("INSERT INTO settings VALUES('wal-value','preserve')")
        writer.commit()
        service = ResetService(database)
        backup = service.clear(service.build_plan())
        snapshot = backup / "清空前.sqlite3"
        assert not Path(str(snapshot) + "-wal").exists()
        service.restore(backup)
        assert database.count_scores() == 3
        assert database.get_setting("wal-value") == "preserve"


def test_legacy_wal_header_snapshot_restores_without_opening_or_modifying_original(tmp_path):
    database, root, sources, service, backup = cleared_library(tmp_path)
    snapshot = backup / "清空前.sqlite3"
    with closing(sqlite3.connect(snapshot)) as connection:
        connection.execute("PRAGMA journal_mode=WAL")
    assert not Path(str(snapshot) + "-wal").exists()
    update_manifest_hash(backup)
    before = sha256_file(snapshot)
    service.restore(backup)
    assert database.count_scores() == 3
    assert sha256_file(snapshot) == before
    assert all(not Path(str(snapshot) + suffix).exists() for suffix in ("-wal", "-shm", "-journal"))


def test_temporary_copy_cleanup_lock_does_not_undo_a_committed_restore(tmp_path, monkeypatch):
    database, root, sources, service, backup = cleared_library(tmp_path)
    original = service._restore_database
    held = []

    def retain_open_handle(snapshot, **kwargs):
        original(snapshot, **kwargs)
        held.append((snapshot, snapshot.open("rb")))

    monkeypatch.setattr(service, "_restore_database", retain_open_handle)
    try:
        service.restore(backup)
        assert database.count_scores() == 3
        assert all((root / name).exists() for name in MANAGED_FOLDERS)
        assert json.loads((backup / "清空记录.json").read_text(encoding="utf-8"))["state"] == "restored"
        assert len(held) == 1
        if os.name == "nt":
            # An actual open Windows handle prevents deletion, rather than a
            # mocked cleanup exception that might bypass tempfile's behavior.
            assert held[0][0].is_file()
    finally:
        for path, handle in held:
            handle.close()
            if path.exists():
                assert path.name == "snapshot.sqlite3" and path.parent.name.startswith("score-restore-")
                path.unlink()
                path.parent.rmdir()
