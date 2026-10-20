import sqlite3

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.catalog_service import CatalogService
from score_organizer.services.catalog_merge_service import CatalogMergeService
from score_organizer.services.duplicate_service import ConfirmedDuplicateService
from score_organizer.services.reset_service import ResetService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database


def setup_library(tmp_path):
    db = Database(tmp_path / "library.sqlite3")
    db.initialize()
    return db, ArchiveService(db, tmp_path / "out")


def import_parts(db, tmp_path, codes, batch=None, title="测试曲", category="01", seq=""):
    paths = []
    for code in codes:
        path = tmp_path / f"{code}{category}{seq}{title}.pdf"
        path.write_bytes(f"%PDF {code} {title} {category}".encode())
        paths.append(path)
    return ImportService(db).import_paths(paths, batch), paths


@pytest.mark.parametrize("code", ["08", "09"])
@pytest.mark.parametrize("seq", ["", "013"])
def test_standalone_one_copy_preserves_original(tmp_path, code, seq):
    db, service = setup_library(tmp_path)
    batch, paths = import_parts(db, tmp_path, [code], seq=seq)
    before = sha256_file(paths[0])
    assert not GroupingService(db).refresh_warnings(batch, scan_completeness=True)
    plans = service.build_plan(batch)
    assert len(plans) == 1 and plans[0].seq == (seq or "001")
    file = plans[0].files[0]
    assert file.by_song_path is None and len(file.targets) == 1
    assert service.commit_plan(batch, plans) == 0
    assert db.list_archived_scores()[0]["storage_layout"] == "instrument_only"
    assert sha256_file(file.by_instrument_path) == before == sha256_file(paths[0])
    assert not (service.output_root / "按曲目分类").exists()
    assert len(list(service.output_root.rglob("*.pdf"))) == 1


def test_mixed_incomplete_regular_parts_remain_then_supplement_same_number(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "02", "08"])
    warnings = GroupingService(db).refresh_warnings(batch, scan_completeness=True)
    insufficient = next(w for w in warnings if w["code"] == "insufficient_instruments")
    assert insufficient["details"]["instrument_count"] == 2
    plans = service.build_plan(batch)
    assert [f.instrument_code for f in plans[0].files] == ["08"]
    assert service.commit_plan(batch, plans) == 2
    assert service.build_plan(batch) == []
    import_parts(db, tmp_path, ["03"], batch=batch)
    plans = service.build_plan(batch)
    assert plans[0].seq == "001" and plans[0].folder_name.endswith("（03）")
    assert service.commit_plan(batch, plans) == 0
    assert db.count_songs() == 1 and db.count_scores() == 4
    assert len(list((service.output_root / "按曲目分类").rglob("*.pdf"))) == 3


def test_rules_persist_and_do_not_reinterpret_existing_files(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["08"])
    service.commit_plan(batch, service.build_plan(batch))
    old = db.list_archived_scores()[0]
    modes = db.instrument_archive_modes()
    modes["08"] = "dual"
    minimums = {r["code"]: 3 for r in db.list_categories()}
    minimums["02"] = 1
    db.save_archive_rules(modes, minimums)
    db.initialize()
    assert db.instrument_archive_modes()["08"] == "dual"
    assert len(service.score_paths(old)) == 1
    batch, _ = import_parts(db, tmp_path, ["07"], category="02", title="独奏")
    plans = service.build_plan(batch)
    assert plans[0].seq == "002"
    service.commit_plan(batch, plans)
    assert len(plans[0].files[0].targets) == 2


def test_changed_rules_invalidate_preview(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["08"])
    plans = service.build_plan(batch)
    modes = db.instrument_archive_modes()
    modes["08"] = "dual"
    db.save_archive_rules(modes, {r["code"]: 3 for r in db.list_categories()})
    with pytest.raises(ValueError, match="预览"):
        service.commit_plan(batch, plans)
    assert db.count_scores() == 0 and len(db.list_import_items(batch)) == 1
    assert not service.output_root.exists()


def test_seven_remains_percussion_and_requires_regular_minimum(tmp_path):
    db, service = setup_library(tmp_path)
    with db.session() as connection:
        assert connection.execute("SELECT name FROM instrument WHERE code = '07'").fetchone()[0] == "打击乐"
    batch, _ = import_parts(db, tmp_path, ["07"])
    assert service.build_plan(batch) == []


def test_single_copy_rollback_on_database_failure(tmp_path, monkeypatch):
    db, service = setup_library(tmp_path)
    batch, paths = import_parts(db, tmp_path, ["09"])
    original = sha256_file(paths[0])
    plans = service.build_plan(batch)
    def fail(*args, **kwargs):
        raise OSError("injected failure")
    monkeypatch.setattr(db, "add_score", fail)
    with pytest.raises(OSError):
        service.commit_plan(batch, plans)
    assert not list(service.output_root.rglob("*.pdf"))
    assert db.count_songs() == 0 and len(db.list_import_items(batch)) == 1
    assert sha256_file(paths[0]) == original


def test_special_same_type_conflict_and_library_duplicate_must_be_confirmed(tmp_path):
    db, service = setup_library(tmp_path)
    batch, paths = import_parts(db, tmp_path, ["08"])
    plans = service.build_plan(batch)
    service.commit_plan(batch, plans)
    new = ImportService(db).import_paths(paths)
    assert service.build_plan(new) == []
    item_id = db.list_import_items(new)[0]["id"]
    copy, remaining = ConfirmedDuplicateService(db, service.output_root).confirm(new, [item_id])
    assert remaining == 0 and len(list(copy.rglob("*.pdf"))) == 1
    assert sha256_file(plans[0].files[0].by_instrument_path) == sha256_file(paths[0])
    alternate = tmp_path / "alternate"
    alternate.mkdir()
    conflict, _ = import_parts(db, alternate, ["09"], title="其他曲")
    import_parts(db, tmp_path, ["09"], batch=conflict, title="其他曲")
    assert service.build_plan(conflict) == []
    assert any(w["code"] == "same_instrument_conflict" for w in db.list_warnings(conflict))


def test_special_catalog_edit_and_full_reset_restore(tmp_path):
    db, service = setup_library(tmp_path)
    batch, sources = import_parts(db, tmp_path, ["09"])
    service.commit_plan(batch, service.build_plan(batch))
    score = db.list_archived_scores()[0]
    catalog = CatalogService(db, service.output_root)
    plan = catalog.build_plan(score["song_id"], "02", "新曲名")
    assert len(plan.files[0].path_changes) == 1
    assert plan.files[0].new_song is None
    catalog.commit_plan(plan)
    updated = db.list_archived_scores()[0]
    assert updated["seq"] == "001" and updated["storage_layout"] == "instrument_only"
    assert service.score_paths(updated)[0].name == "0902001新曲名.pdf"
    assert service.score_paths(updated)[0].read_bytes() == sources[0].read_bytes()
    assert not (service.output_root / "按曲目分类").exists()
    db.set_setting("output_root", str(service.output_root))
    reset = ResetService(db)
    backup = reset.clear(reset.build_plan())
    reset.restore(backup)
    assert db.list_archived_scores()[0]["storage_layout"] == "instrument_only"
    assert service.score_paths(db.list_archived_scores()[0])[0].read_bytes() == sources[0].read_bytes()


def test_special_manual_catalog_merge_preserves_number_and_stores_one_duplicate(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["08"], title="天空之城")
    service.commit_plan(batch, service.build_plan(batch))
    second, _ = import_parts(db, tmp_path, ["08"], title="独奏")
    service.commit_plan(second, service.build_plan(second))
    records = db.list_archived_scores()
    catalog = CatalogService(db, service.output_root)
    catalog.commit_plan(catalog.build_plan(records[1]["song_id"], "01", "天空之 城"))
    merge = CatalogMergeService(db, service.output_root)
    plan = merge.build_plan(records[0]["song_id"], records[1]["song_id"], [records[0]["id"]], "天空之城")
    assert all(file.new_song is None for file in plan.files)
    merge.commit_plan(plan)
    assert len(db.list_archived_scores()) == 1
    assert db.allocate_next_sequence() == "003"
    assert len(list((service.output_root / "已确认重复").rglob("*.pdf"))) == 1
    assert not (service.output_root / "按曲目分类").exists()


def test_invalid_rules_are_atomic(tmp_path):
    db, _ = setup_library(tmp_path)
    modes = db.instrument_archive_modes()
    modes["01"] = "instrument_only"
    with pytest.raises(ValueError):
        db.save_archive_rules(modes, {r["code"]: 0 for r in db.list_categories()})
    assert db.instrument_archive_modes()["01"] == "dual"


def test_historic_scores_keep_dual_layout_after_schema_migration(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "02", "03"])
    plans = service.build_plan(batch)
    service.commit_plan(batch, plans)
    with db.session() as connection:
        connection.execute("ALTER TABLE score DROP COLUMN storage_layout")
    db.initialize()
    assert all(score["storage_layout"] == "dual" for score in db.list_archived_scores())
    assert all(len(service.score_paths(score)) == 2 for score in db.list_archived_scores())


def test_old_reset_database_snapshot_restores_with_dual_default(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "02", "03"])
    service.commit_plan(batch, service.build_plan(batch))
    snapshot = tmp_path / "old.sqlite3"
    source = db.connect()
    destination = sqlite3.connect(snapshot)
    try:
        source.backup(destination)
        destination.execute("ALTER TABLE score DROP COLUMN storage_layout")
        destination.execute("DELETE FROM instrument WHERE code IN ('08', '09')")
        destination.commit()
    finally:
        source.close()
        destination.close()
    with db.transaction():
        ResetService(db)._restore_database(snapshot, expected_sha256=sha256_file(snapshot))
    assert all(score["storage_layout"] == "dual" for score in db.list_archived_scores())
    assert {"08", "09"}.issubset(db.instrument_codes())


def test_single_type_can_archive_while_incomplete_regular_conflicts_stay_draft(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "08"])
    alternate = tmp_path / "alternate"
    alternate.mkdir()
    import_parts(db, alternate, ["01"], batch=batch)
    plans = service.build_plan(batch)
    assert [file.instrument_code for file in plans[0].files] == ["08"]
    assert service.commit_plan(batch, plans) == 2
    assert service.build_plan(batch) == []


def test_special_supplement_does_not_change_regular_folder_count(tmp_path):
    db, service = setup_library(tmp_path)
    batch, _ = import_parts(db, tmp_path, ["01", "02", "03"])
    plans = service.build_plan(batch)
    service.commit_plan(batch, plans)
    original_paths = {path: sha256_file(path) for file in plans[0].files for path in file.targets}
    added, _ = import_parts(db, tmp_path, ["08", "09"])
    additional = service.build_plan(added)
    assert additional[0].seq == "001" and additional[0].folder_name.endswith("（03）")
    service.commit_plan(added, additional)
    assert all(sha256_file(path) == digest for path, digest in original_paths.items())
    assert len(list((service.output_root / "按曲目分类").rglob("*.pdf"))) == 3
    assert len(list((service.output_root / "按乐器分类").rglob("*.pdf"))) == 5


def test_special_missing_saved_copy_blocks_supplement_and_duplicate_confirmation(tmp_path):
    db, service = setup_library(tmp_path)
    batch, sources = import_parts(db, tmp_path, ["08"])
    plans = service.build_plan(batch)
    service.commit_plan(batch, plans)
    plans[0].files[0].by_instrument_path.unlink()
    added, _ = import_parts(db, tmp_path, ["09"])
    with pytest.raises(ValueError, match="缺失"):
        service.commit_plan(added, service.build_plan(added))
    duplicate = ImportService(db).import_paths(sources)
    GroupingService(db).refresh_warnings(duplicate)
    item = db.list_import_items(duplicate)[0]
    with pytest.raises(ValueError, match="保留"):
        ConfirmedDuplicateService(db, service.output_root).confirm(duplicate, [item["id"]])
    assert db.count_scores() == 1 and db.list_import_items(duplicate)
