import sqlite3

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.catalog_service import CatalogService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database


def archived_song(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    sources = []
    for code in ("01", "02", "03"):
        path = tmp_path / f"{code}01曲目.pdf"
        path.write_bytes(f"%PDF {code}".encode())
        sources.append(path)
    batch = ImportService(db).import_paths(sources)
    root = tmp_path / "out"
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch)
    archive.commit_plan(batch, plans)
    song = db.find_archived_song("01", "曲目")
    return db, root, int(song["id"]), sources, plans[0]


def test_catalog_edit_preserves_sequence_sources_and_updates_both_directories(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    hashes = {path: sha256_file(path) for path in sources}
    service = CatalogService(db, root)
    plan = service.build_plan(song_id, "02", "新曲名")
    assert not (root / "按曲目分类" / "02001新曲名（03）").exists()
    backup = service.commit_plan(plan)
    assert (backup / "修改前.sqlite3").is_file()
    assert len(list(backup.glob("*.pdf"))) == 6
    assert (backup / "完成.txt").is_file()
    song = db.get_archived_song(song_id)
    assert (song["seq"], song["category_code"], song["display_title"]) == ("001", "02", "新曲名")
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert not (root / "按曲目分类" / old.folder_name).exists()
    for change in plan.files:
        assert sha256_file(change.new_instrument) == sha256_file(change.new_song) == change.sha256
        assert not change.old_instrument.exists() and not change.old_song.exists()
        assert change.new_instrument.name == f"{change.instrument_code}02001新曲名.pdf"
    assert all(sha256_file(path) == digest for path, digest in hashes.items())
    assert {row["output_name"] for row in db.list_archived_scores(song_id)} == {file.new_instrument.name for file in plan.files}


def test_catalog_edit_blocks_target_collision_without_changes(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    conflict = root / "按乐器分类" / "01第一小提琴" / "0101001新曲名.pdf"
    conflict.write_bytes(b"existing target")
    with pytest.raises(ValueError, match="覆盖"):
        CatalogService(db, root).build_plan(song_id, "01", "新曲名")
    assert conflict.read_bytes() == b"existing target"
    assert db.get_archived_song(song_id)["display_title"] == "曲目"
    assert all(file.by_song_path.is_file() for file in old.files)


def test_catalog_edit_rolls_back_files_and_metadata_when_database_write_fails(tmp_path, monkeypatch):
    db, root, song_id, sources, old = archived_song(tmp_path)
    service = CatalogService(db, root)
    plan = service.build_plan(song_id, "02", "新曲名")
    original = db.update_archived_song_metadata
    def fail_after_update(*args):
        original(*args)
        raise sqlite3.OperationalError("模拟登记失败")
    monkeypatch.setattr(db, "update_archived_song_metadata", fail_after_update)
    with pytest.raises(ValueError, match="已恢复"):
        service.commit_plan(plan)
    assert db.get_archived_song(song_id)["display_title"] == "曲目"
    assert db.get_archived_song(song_id)["category_code"] == "01"
    for file in old.files:
        assert sha256_file(file.by_instrument_path) == sha256_file(file.by_song_path) == file.source_sha256
    assert all(not file.new_song.exists() and not file.new_instrument.exists() for file in plan.files)
    monkeypatch.setattr(db, "update_archived_song_metadata", original)
    service.commit_plan(service.build_plan(song_id, "02", "新曲名"))
    assert db.get_archived_song(song_id)["display_title"] == "新曲名"


def test_catalog_edit_blocks_files_changed_after_preview(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    service = CatalogService(db, root)
    plan = service.build_plan(song_id, "02", "新曲名")
    old.files[0].by_song_path.write_bytes(b"changed externally")
    with pytest.raises(ValueError, match="变化"):
        service.commit_plan(plan)
    assert old.files[0].by_song_path.read_bytes() == b"changed externally"
    assert db.get_archived_song(song_id)["display_title"] == "曲目"


@pytest.mark.parametrize("title", ["", "曲/目", "曲目:", "曲目."])
def test_catalog_edit_rejects_invalid_title(tmp_path, title):
    db, root, song_id, sources, old = archived_song(tmp_path)
    with pytest.raises(ValueError):
        CatalogService(db, root).build_plan(song_id, "01", title)


def test_catalog_edit_does_not_merge_another_existing_song(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    db.create_song("02", "002", "另一首", status="archived")
    with pytest.raises(ValueError, match="同名"):
        CatalogService(db, root).build_plan(song_id, "02", "另一首")
    assert db.get_archived_song(song_id)["seq"] == "001"


def test_catalog_edit_then_supplement_reuses_original_sequence(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    service = CatalogService(db, root)
    service.commit_plan(service.build_plan(song_id, "02", "新曲名"))
    extra = tmp_path / "0402新曲名.pdf"
    extra.write_bytes(b"%PDF fourth")
    batch = ImportService(db).import_paths([extra])
    archive = ArchiveService(db, root)
    plan = archive.build_plan(batch)
    assert plan[0].seq == "001" and plan[0].existing_song_id == song_id
    archive.commit_plan(batch, plan)
    assert db.count_songs() == 1 and db.count_scores() == 4
    assert len(list((root / "按曲目分类" / "02001新曲名（04）").glob("*.pdf"))) == 4


def test_catalog_edit_output_write_failure_restores_all_original_files(tmp_path, monkeypatch):
    import shutil
    db, root, song_id, sources, old = archived_song(tmp_path)
    service = CatalogService(db, root)
    plan = service.build_plan(song_id, "02", "新曲名")
    original = shutil.copyfileobj
    attempts = []
    def fail_second_new_output(source, destination, *args):
        if "新曲名" in str(destination.name):
            attempts.append(str(destination.name))
            if len(attempts) == 2:
                raise OSError("模拟写入失败")
        return original(source, destination, *args)
    monkeypatch.setattr(shutil, "copyfileobj", fail_second_new_output)
    with pytest.raises(ValueError, match="已恢复"):
        service.commit_plan(plan)
    assert len(attempts) == 2
    assert db.get_archived_song(song_id)["display_title"] == "曲目"
    for file in old.files:
        assert sha256_file(file.by_song_path) == sha256_file(file.by_instrument_path) == file.source_sha256
    assert all(not file.new_song.exists() and not file.new_instrument.exists() for file in plan.files)


def test_catalog_edit_protects_archived_pdf_referenced_as_original_source(tmp_path):
    db, root, song_id, sources, old = archived_song(tmp_path)
    batch = db.create_import_batch()
    file = old.files[0]
    db.add_import_item(batch, str(file.by_song_path), file.source_sha256, "recognized",
                       {"category_code": "01", "instrument_code": "01", "title": "曲目", "seq": "001"})
    with pytest.raises(ValueError, match="源PDF"):
        CatalogService(db, root).build_plan(song_id, "02", "新曲名")
    assert sha256_file(file.by_song_path) == file.source_sha256
