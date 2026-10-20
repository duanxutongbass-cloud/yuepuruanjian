import json

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.reset_service import DATA_TABLES, MANAGED_FOLDERS, ResetService
from score_organizer.storage.database import Database


def filled_library(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    files = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        files.append(path)
    batch = ImportService(db).import_paths(files)
    archive = ArchiveService(db, root)
    archive.commit_plan(batch, archive.build_plan(batch))
    draft = tmp_path / "草稿.pdf"
    draft.write_bytes(b"%PDF draft")
    ImportService(db).import_paths([draft])
    files.append(draft)
    for name in MANAGED_FOLDERS[2:]:
        folder = root / name
        folder.mkdir()
        (folder / "record.txt").write_text(name, encoding="utf-8")
    (root / "用户文件.txt").write_text("keep", encoding="utf-8")
    db.set_setting("output_root", str(root))
    db.upsert_category("10", "保留自定义分类")
    return db, root, files


def test_clear_archives_all_managed_results_and_keeps_sources_and_configuration(tmp_path):
    db, root, sources = filled_library(tmp_path)
    hashes = {path: sha256_file(path) for path in sources}
    output_hashes = {path.relative_to(root): sha256_file(path) for path in root.rglob("*.pdf")}
    service = ResetService(db)
    plan = service.build_plan()
    backup = service.clear(plan)
    assert db.count_songs() == db.count_scores() == 0
    assert db.latest_draft_batch_id() is None
    assert db.allocate_next_sequence() == "001"
    with db.session() as connection:
        assert all(connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0] == 0 for table in DATA_TABLES)
    assert "10" in db.category_codes()
    assert db.get_setting("output_root") == str(root)
    assert (root / "用户文件.txt").read_text() == "keep"
    assert all(not (root / name).exists() and (backup / name).is_dir() for name in MANAGED_FOLDERS)
    assert all(sha256_file(backup / path) == digest for path, digest in output_hashes.items())
    assert all(sha256_file(path) == digest for path, digest in hashes.items())
    assert (backup / "清空前.sqlite3").is_file()
    assert json.loads((backup / "清空记录.json").read_text(encoding="utf-8"))["state"] == "completed"


def test_restore_last_clear_recovers_database_and_both_directories(tmp_path):
    db, root, sources = filled_library(tmp_path)
    hashes = {path.relative_to(root): sha256_file(path) for path in root.rglob("*.pdf")}
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    service.restore(backup)
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert db.latest_draft_batch_id() is not None
    assert db.allocate_next_sequence() == "002"
    assert all(sha256_file(root / path) == digest for path, digest in hashes.items())
    assert all((root / name).exists() for name in MANAGED_FOLDERS)
    assert all(path.exists() for path in sources)
    with pytest.raises(ValueError, match="已经恢复"):
        service.restore(backup)


def test_clear_database_failure_rolls_back_and_puts_directories_back(tmp_path, monkeypatch):
    db, root, sources = filled_library(tmp_path)
    original = db.clear_library_data
    def fail():
        original()
        raise ValueError("模拟清空登记失败")
    monkeypatch.setattr(db, "clear_library_data", fail)
    service = ResetService(db)
    with pytest.raises(ValueError, match="已保留"):
        service.clear(service.build_plan())
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert db.latest_draft_batch_id() is not None
    assert all((root / name).exists() for name in MANAGED_FOLDERS)
    assert all(path.exists() for path in sources)


def test_restore_refuses_to_overwrite_new_data_or_output_directories(tmp_path):
    db, root, sources = filled_library(tmp_path)
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    batch = db.create_import_batch()
    with pytest.raises(ValueError, match="新的整理数据"):
        service.restore(backup)
    db.clear_draft_batch(batch)
    (root / "按曲目分类").mkdir()
    (root / "按曲目分类" / "new.txt").write_text("new")
    with pytest.raises(ValueError, match="不能覆盖"):
        service.restore(backup)
    assert (root / "按曲目分类" / "new.txt").read_text() == "new"


def test_reset_protects_source_pdf_inside_managed_directory(tmp_path):
    db, root, sources = filled_library(tmp_path)
    archived_pdf = next((root / "按曲目分类").rglob("*.pdf"))
    ImportService(db).import_paths([archived_pdf], db.latest_draft_batch_id())
    with pytest.raises(ValueError, match="源PDF"):
        ResetService(db).build_plan()
    assert archived_pdf.is_file()
    assert db.count_scores() == 3


def test_reset_draft_only_without_output_path_is_backed_up_and_restorable(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    source = tmp_path / "0101曲目.pdf"
    source.write_bytes(b"%PDF original")
    batch = ImportService(db).import_paths([source])
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    assert db.latest_draft_batch_id() is None
    service.restore(backup)
    assert db.latest_draft_batch_id() == batch
    assert source.read_bytes() == b"%PDF original"


def test_restore_database_failure_preserves_empty_live_library_and_full_backup(tmp_path, monkeypatch):
    db, root, sources = filled_library(tmp_path)
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    original = service._restore_database
    def fail(path, **kwargs):
        original(path, **kwargs)
        raise ValueError("模拟恢复登记失败")
    monkeypatch.setattr(service, "_restore_database", fail)
    with pytest.raises(ValueError, match="备份保留"):
        service.restore(backup)
    assert db.count_scores() == 0
    assert all(not (root / name).exists() and (backup / name).exists() for name in MANAGED_FOLDERS)


def test_reset_directory_move_failure_restores_already_moved_folder(tmp_path, monkeypatch):
    from pathlib import Path
    db, root, sources = filled_library(tmp_path)
    original = Path.rename
    def fail_second_folder(path, target):
        if path.parent == root and path.name == "按曲目分类":
            raise OSError("模拟目录移动失败")
        return original(path, target)
    monkeypatch.setattr(Path, "rename", fail_second_folder)
    service = ResetService(db)
    with pytest.raises(ValueError, match="已保留"):
        service.clear(service.build_plan())
    assert db.count_scores() == 3
    assert all((root / name).exists() for name in MANAGED_FOLDERS)


def test_restore_directory_failure_rolls_back_restored_records_and_folders(tmp_path, monkeypatch):
    from pathlib import Path
    db, root, sources = filled_library(tmp_path)
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    original = Path.rename
    def fail_second_folder(path, target):
        if path.parent == backup and path.name == "按曲目分类":
            raise OSError("模拟恢复移动失败")
        return original(path, target)
    monkeypatch.setattr(Path, "rename", fail_second_folder)
    with pytest.raises(ValueError, match="备份保留"):
        service.restore(backup)
    assert db.count_scores() == 0
    assert all(not (root / name).exists() and (backup / name).exists() for name in MANAGED_FOLDERS)


@pytest.mark.parametrize("invalid", [[], {"version": 1, "state": "completed"}])
def test_restore_rejects_incomplete_manifest_without_touching_data(tmp_path, invalid):
    db, root, sources = filled_library(tmp_path)
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    (backup / "清空记录.json").write_text(json.dumps(invalid), encoding="utf-8")
    with pytest.raises(ValueError, match="不完整"):
        service.restore(backup)
    assert db.count_scores() == 0
    assert all((backup / name).exists() for name in MANAGED_FOLDERS)


def test_missing_source_record_does_not_prevent_reset_of_broken_library(tmp_path):
    db, root, sources = filled_library(tmp_path)
    with db.session() as connection:
        connection.execute("UPDATE score SET source_path = ? WHERE id = (SELECT MIN(id) FROM score)",
                           (str(root / "待审查" / "不存在.pdf"),))
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    assert db.count_scores() == 0
    assert len(list((backup / "按曲目分类").rglob("*.pdf"))) == 3
def test_manifest_transient_windows_lock_retries_without_losing_manifest(tmp_path, monkeypatch):
    from pathlib import Path
    import json
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    service = ResetService(db)
    original = Path.replace
    attempts = []
    def locked_once(path, target):
        attempts.append(path)
        if len(attempts) == 1:
            raise PermissionError("模拟短时Windows文件锁")
        return original(path, target)
    monkeypatch.setattr(Path, "replace", locked_once)
    service._write_manifest(tmp_path, {"state": "completed"})
    assert len(attempts) == 2
    assert json.loads((tmp_path / "清空记录.json").read_text(encoding="utf-8"))["state"] == "completed"


def test_manifest_persistent_lock_stops_with_original_manifest_intact(tmp_path, monkeypatch):
    from pathlib import Path
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    service = ResetService(db)
    service._write_manifest(tmp_path, {"state": "completed"})
    original = (tmp_path / "清空记录.json").read_bytes()
    attempts = []
    def locked(path, target):
        attempts.append(path)
        raise PermissionError("模拟持续文件锁")
    monkeypatch.setattr(Path, "replace", locked)
    with pytest.raises(PermissionError):
        service._write_manifest(tmp_path, {"state": "restored"})
    assert len(attempts) == 3
    assert (tmp_path / "清空记录.json").read_bytes() == original
def test_directory_transient_windows_lock_retries_but_never_overwrites(tmp_path, monkeypatch):
    from pathlib import Path
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    service = ResetService(db)
    source = tmp_path / "source"
    source.mkdir()
    (source / "keep.pdf").write_bytes(b"original")
    destination = tmp_path / "destination"
    original = Path.rename
    attempts = []
    def locked_once(path, target):
        attempts.append(path)
        if len(attempts) == 1:
            raise PermissionError("模拟短时Windows目录锁")
        return original(path, target)
    monkeypatch.setattr(Path, "rename", locked_once)
    service._rename_folder(source, destination)
    assert len(attempts) == 2
    assert (destination / "keep.pdf").read_bytes() == b"original"
    source.mkdir()
    with pytest.raises(ValueError, match="不能覆盖"):
        service._rename_folder(source, destination)
    assert (destination / "keep.pdf").read_bytes() == b"original"
