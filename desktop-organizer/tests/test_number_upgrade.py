from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys

import pytest

from score_organizer.storage.database import Database, UnsupportedDatabaseVersion
from score_organizer.storage import number_upgrade as upgrade
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards


def state(path):
    with closing(sqlite3.connect(path)) as db:
        return upgrade._state(db)


def make_library(tmp_path, *, empty=False):
    path = tmp_path / "原库 & #.sqlite3"
    db = Database(path)
    db.initialize()
    root = tmp_path / "整理输出"
    root.mkdir()
    db.set_setting("output_root", str(root))
    original = tmp_path / "原始乐谱.pdf"
    original.write_bytes(b"%PDF-1.4\nsynthetic original\n%%EOF")
    for folder in ("按曲目分类", "按乐器分类"):
        target = root / folder
        target.mkdir()
        (target / "0101001旧曲.pdf").write_bytes(original.read_bytes())
    if not empty:
        first = db.create_song("01", "001", "旧曲", status="archived")
        db.create_song("02", "999", "保留占号", status="merged")
        with closing(sqlite3.connect(path)) as sql:
            sql.execute("PRAGMA foreign_keys=ON")
            sql.execute("INSERT INTO score(song_id,instrument_id,source_name,source_path,source_sha256,output_name) VALUES(?,1,?,?,?,?)",
                        (first, original.name, str(original), hashlib.sha256(original.read_bytes()).hexdigest(), "0101001旧曲.pdf"))
            sql.execute("CREATE TABLE custom_child(id INTEGER PRIMARY KEY, song_id INTEGER REFERENCES song(id), payload BLOB)")
            sql.execute("INSERT INTO custom_child VALUES(1,?,?)", (first, b"\x00\xffunchanged"))
            sql.execute("CREATE INDEX custom_song_title ON song(display_title)")
            sql.execute("CREATE VIEW custom_song_view AS SELECT seq,display_title FROM song")
            sql.execute("CREATE VIEW dependent_view AS SELECT seq FROM custom_song_view")
            sql.execute("CREATE TRIGGER custom_song_edit AFTER UPDATE OF display_title ON song BEGIN UPDATE settings SET value=NEW.display_title WHERE key='custom_event'; END")
            sql.execute("INSERT INTO settings VALUES('custom_event','unchanged')")
            sql.execute("UPDATE sqlite_sequence SET seq=4321 WHERE name='song'")
            sql.commit()
    return path


def pdf_bytes(tmp_path):
    return {str(p.relative_to(tmp_path)): p.read_bytes() for p in tmp_path.rglob("*.pdf")}


def test_separate_candidate_preserves_records_schema_backup_and_pdf(tmp_path):
    source = make_library(tmp_path)
    before = state(source)
    raw = source.read_bytes()
    originals = pdf_bytes(tmp_path)
    destination = tmp_path / "候选"
    manifest = json.loads(upgrade.prepare_number_upgrade(source, destination).read_text("utf-8"))
    assert source.read_bytes() == raw
    assert state(source) == before
    assert pdf_bytes(tmp_path) == originals
    assert state(destination / "before.sqlite3") == before
    assert state(destination / "restored.sqlite3") == before
    assert manifest["databaseOnly"] and not manifest["readyForActivation"] and not manifest["pdfSnapshotIncluded"]
    assert manifest["preservedTables"] == before["tables"]
    for name, proof in manifest["artifacts"].items():
        file = destination / name
        assert file.stat().st_size == proof["bytes"]
        assert hashlib.sha256(file.read_bytes()).hexdigest() == proof["sha256"]
    candidate = destination / "candidate.sqlite3"
    with pytest.raises(UnsupportedDatabaseVersion):
        Database(candidate).initialize()
    with closing(sqlite3.connect(candidate)) as db:
        register_format_one_writer(db)
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("BEGIN IMMEDIATE")
        require_format_one_guards(db)
        assert db.execute("SELECT seq FROM dependent_view ORDER BY seq").fetchall() == [("001",), ("999",)]
        assert db.execute("SELECT seq FROM sqlite_sequence WHERE name='song'").fetchone()[0] == 4321
        db.execute("UPDATE song SET display_title='trigger kept' WHERE seq='001'")
        assert db.execute("SELECT value FROM settings WHERE key='custom_event'").fetchone()[0] == "trigger kept"
        db.execute("INSERT INTO song(category_id,seq,base_title,normalized_title,display_title) VALUES(1,'1000','new','new','new')")
        assert db.execute("SELECT id FROM song WHERE seq='1000'").fetchone()[0] == 4322
        with pytest.raises(sqlite3.IntegrityError, match="FOREIGN KEY"):
            db.execute("DELETE FROM song WHERE seq='001'")
        with pytest.raises(sqlite3.IntegrityError, match="UNIQUE"):
            db.execute("INSERT INTO song(category_id,seq,base_title,normalized_title,display_title) VALUES(2,'999','dup','dup','dup')")
        db.rollback()


def test_empty_library_candidate_does_not_invent_autoincrement_state(tmp_path):
    source = make_library(tmp_path, empty=True)
    before = state(source)
    upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
    assert state(tmp_path / "candidate/candidate.sqlite3")["tables"] == before["tables"]


@pytest.mark.parametrize("sequence", ["000", "01000", "-01", "１２３", "1e3", "1.0", "1000\x00", "9223372036854775808", "9" * 20])
def test_candidate_refuses_ambiguous_or_out_of_range_numbers(tmp_path, sequence):
    source = make_library(tmp_path)
    upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
    with closing(sqlite3.connect(tmp_path / "candidate/candidate.sqlite3")) as db:
        register_format_one_writer(db)
        with pytest.raises(sqlite3.IntegrityError, match="CHECK"):
            db.execute("UPDATE song SET seq=? WHERE seq='001'", (sequence,))
        db.rollback()
        db.execute("UPDATE song SET seq='9223372036854775807' WHERE seq='001'")
        db.rollback()


@pytest.mark.parametrize("stage", ["dependencies_saved", "rows_copied", "old_table_dropped", "dependencies_restored", "write_guards_installed", "before_candidate_commit"])
def test_every_migration_interruption_rolls_back_candidate_and_keeps_source(tmp_path, stage):
    source = make_library(tmp_path)
    before = state(source)
    original = pdf_bytes(tmp_path)
    destination = tmp_path / "candidate"
    def interrupt(current):
        if current == stage:
            raise RuntimeError("injected " + stage)
    with pytest.raises(RuntimeError, match="injected"):
        upgrade.prepare_number_upgrade(source, destination, progress=interrupt)
    assert state(source) == before
    assert state(destination / "candidate.sqlite3") == before
    assert state(destination / "before.sqlite3") == before
    assert state(destination / "restored.sqlite3") == before
    assert pdf_bytes(tmp_path) == original
    assert not (destination / "prepared.json").exists()
    assert (destination / "failed.json").exists()


def test_process_exit_after_drop_recovers_without_completion_manifest(tmp_path):
    source = make_library(tmp_path)
    before = state(source)
    destination = tmp_path / "candidate"
    code = "from score_organizer.storage.number_upgrade import prepare_number_upgrade; import os,sys; prepare_number_upgrade(sys.argv[1],sys.argv[2],progress=lambda stage: os._exit(71) if stage=='old_table_dropped' else None)"
    environment = dict(os.environ)
    environment["PYTHONPATH"] = str(Path(__file__).resolve().parents[1] / "src") + os.pathsep + environment.get("PYTHONPATH", "")
    result = subprocess.run([sys.executable, "-c", code, str(source), str(destination)], env=environment, capture_output=True, timeout=30)
    assert result.returncode == 71, result.stderr.decode(errors="replace")
    assert state(destination / "candidate.sqlite3") == before
    assert state(source) == before
    assert not (destination / "prepared.json").exists()


def test_wal_committed_records_are_in_backup_and_recovery(tmp_path):
    source = make_library(tmp_path)
    with closing(sqlite3.connect(source)) as writer:
        assert writer.execute("PRAGMA journal_mode=WAL").fetchone()[0] == "wal"
        writer.execute("PRAGMA wal_autocheckpoint=0")
        writer.execute("INSERT INTO settings VALUES('wal_only','keep')")
        writer.commit()
        assert Path(str(source) + "-wal").stat().st_size > 0
        before = state(source)
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
        assert state(tmp_path / "candidate/before.sqlite3") == before
        assert state(tmp_path / "candidate/restored.sqlite3") == before
        assert state(source) == before


def test_source_change_during_preparation_refuses_prepared_marker(tmp_path):
    source = make_library(tmp_path)
    def change(stage):
        if stage == "restore_verified":
            with closing(sqlite3.connect(source)) as writer:
                writer.execute("INSERT INTO settings VALUES('concurrent','retained')")
                writer.commit()
    with pytest.raises(ValueError, match="源曲库已变化"):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate", progress=change)
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert Database(source).get_setting("concurrent") == "retained"


@pytest.mark.parametrize("problem", ["unknown_version", "invalid_sequence", "reserved_name", "different_check", "missing_unique"])
def test_unrecognized_or_invalid_source_is_not_silently_fixed(tmp_path, problem):
    source = make_library(tmp_path)
    with closing(sqlite3.connect(source)) as db:
        if problem == "unknown_version":
            db.execute("PRAGMA user_version=7")
        elif problem == "invalid_sequence":
            db.execute("UPDATE song SET seq='-01' WHERE seq='001'")
        elif problem == "reserved_name":
            db.execute("CREATE TABLE _seq_song_new(value TEXT)")
        else:
            # Synthetic schema variants; reopen before preparing to reload schema.
            db.execute("PRAGMA writable_schema=ON")
            old = db.execute("SELECT sql FROM sqlite_schema WHERE name='song'").fetchone()[0]
            new = old.replace(upgrade.OLD_SEQUENCE_CHECK, "CHECK(seq IS NULL OR length(seq) <= 3)") if problem == "different_check" else old.replace("seq TEXT UNIQUE", "seq TEXT")
            db.execute("UPDATE sqlite_schema SET sql=? WHERE name='song'", (new,))
            if problem == "missing_unique":
                db.execute("DELETE FROM sqlite_schema WHERE type='index' AND name='sqlite_autoindex_song_1'")
        db.commit()
    raw = source.read_bytes()
    with pytest.raises((ValueError, sqlite3.DatabaseError)):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
    assert source.read_bytes() == raw
    assert not (tmp_path / "candidate/prepared.json").exists()


def test_existing_destination_and_output_folder_never_overwritten(tmp_path):
    source = make_library(tmp_path)
    destination = tmp_path / "existing"
    destination.mkdir()
    evidence = destination / "keep.bin"
    evidence.write_bytes(b"must keep")
    with pytest.raises(ValueError, match="已存在"):
        upgrade.prepare_number_upgrade(source, destination)
    assert evidence.read_bytes() == b"must keep"
    with pytest.raises(ValueError, match="整理目录之外"):
        upgrade.prepare_number_upgrade(source, tmp_path / "整理输出/candidate")
    assert not (tmp_path / "整理输出/candidate").exists()


def test_manifest_fsync_failure_never_leaves_a_prepared_marker(tmp_path, monkeypatch):
    source = make_library(tmp_path)
    real_fsync = upgrade.os.fsync
    calls = 0
    def fail_second(fd):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("simulated disk failure")
        real_fsync(fd)
    monkeypatch.setattr(upgrade.os, "fsync", fail_second)
    with pytest.raises(OSError, match="disk failure"):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert (tmp_path / "candidate/failed.json").exists()


@pytest.mark.parametrize("stage", ["backup_created", "restore_verified"])
def test_interrupted_preparation_retains_existing_artifacts_for_review(tmp_path, stage):
    source = make_library(tmp_path)
    before = state(source)
    def stop(current):
        if current == stage:
            raise KeyboardInterrupt("cancel preparation")
    with pytest.raises(KeyboardInterrupt):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate", progress=stop)
    assert state(source) == before
    assert state(tmp_path / "candidate/before.sqlite3") == before
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert json.loads((tmp_path / "candidate/failed.json").read_text("utf-8"))["errorType"] == "KeyboardInterrupt"


def test_changed_backup_is_rejected_before_restore(tmp_path):
    source = make_library(tmp_path)
    before = state(source)
    def corrupt(stage):
        if stage == "backup_created":
            with closing(sqlite3.connect(tmp_path / "candidate/before.sqlite3")) as backup:
                backup.execute("DELETE FROM settings")
                backup.commit()
    with pytest.raises(ValueError, match="读取快照不一致"):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate", progress=corrupt)
    assert state(source) == before
    assert not (tmp_path / "candidate/restored.sqlite3").exists()
    assert not (tmp_path / "candidate/prepared.json").exists()


def test_completed_manifest_is_atomic_and_never_overwrites(tmp_path, monkeypatch):
    path = tmp_path / "prepared.json"
    partial = path.with_name("prepared.json.partial")
    original_link = os.link
    def inspect_link(source, destination):
        assert json.loads(Path(source).read_text("utf-8")) == {"ready": False}
        assert not Path(destination).exists()
        original_link(source, destination)
    monkeypatch.setattr(upgrade.os, "link", inspect_link)
    upgrade._write_json_exclusive(path, {"ready": False})
    before = path.read_bytes()
    assert not partial.exists()
    monkeypatch.setattr(upgrade.os, "link", original_link)
    with pytest.raises(FileExistsError):
        upgrade._write_json_exclusive(path, {"ready": True})
    assert path.read_bytes() == before


def test_manifest_link_failure_leaves_no_completion_marker(tmp_path, monkeypatch):
    source = make_library(tmp_path)
    original_link = os.link
    def no_final_link(origin, destination):
        if Path(destination).name == "prepared.json":
            raise OSError("filesystem cannot publish manifest")
        original_link(origin, destination)
    monkeypatch.setattr(upgrade.os, "link", no_final_link)
    with pytest.raises(OSError, match="cannot publish"):
        upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
    assert not (tmp_path / "candidate/prepared.json").exists()
    assert (tmp_path / "candidate/failed.json").exists()


def test_busy_source_is_refused_without_creating_candidate(tmp_path):
    source = make_library(tmp_path)
    with closing(sqlite3.connect(source)) as active:
        active.execute("BEGIN EXCLUSIVE")
        with pytest.raises(sqlite3.OperationalError, match="locked"):
            upgrade.prepare_number_upgrade(source, tmp_path / "candidate")
        active.rollback()
    assert not (tmp_path / "candidate").exists()
