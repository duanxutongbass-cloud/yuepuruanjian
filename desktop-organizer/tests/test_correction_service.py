import json
import sqlite3

import pytest

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.correction_service import CorrectionService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database


def correction_fixture(tmp_path):
    db = Database(tmp_path / "correction.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    sources = []
    for code, title in [("01", "天空之成"), ("02", "天空之城"), ("03", "天空之城")]:
        source = tmp_path / f"{code}01{title}.pdf"
        source.write_bytes(f"%PDF {code} {title}".encode("utf-8"))
        sources.append(source)
    batch = ImportService(db).import_paths(sources)
    ids = [int(row["id"]) for row in db.list_import_items(batch)]
    return db, root, batch, ids, sources


def add_existing(db, tmp_path, seq, title):
    files = []
    for code in ("01", "02", "03"):
        pdf = tmp_path / f"{code}01{seq}{title}.pdf"
        pdf.write_bytes(f"%PDF {seq} {code} {title}".encode("utf-8"))
        files.append(pdf)
    batch = ImportService(db).import_paths(files)
    archive = ArchiveService(db, db.get_setting("output_root"))
    archive.commit_plan(batch, archive.build_plan(batch))
    return int(db.find_archived_song("01", title)["id"])


def test_correction_preview_readonly_then_corrects_and_archives_both_directories(tmp_path):
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in sources}
    before = [dict(r) for r in db.list_import_items(batch)]
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "天空之城")
    assert [dict(r) for r in db.list_import_items(batch)] == before
    assert not root.exists()
    service.apply(plan)
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch, ids)
    assert len(plans) == 1 and plans[0].seq == "001"
    archive.commit_plan(batch, plans)
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert db.list_import_items(batch) == []
    for file in plans[0].files:
        assert sha256_file(file.by_song_path) == sha256_file(file.by_instrument_path) == file.source_sha256
    assert {p: sha256_file(p) for p in sources} == hashes


def test_unnumbered_files_get_manual_fields_then_archive_without_renaming_sources(tmp_path):
    db = Database(tmp_path / "manual.sqlite3")
    db.initialize()
    sources = [tmp_path / f"无编号分谱{index}.pdf" for index in range(3)]
    for index, path in enumerate(sources):
        path.write_bytes(f"%PDF manual {index}".encode())
    original = {path: sha256_file(path) for path in sources}
    batch = ImportService(db).import_paths(sources)
    items = db.list_import_items(batch)
    ids = [int(item["id"]) for item in items]
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "手动确认曲目", "02",
                              {item_id: f"{index:02d}" for index, item_id in enumerate(ids, 1)})
    service.apply(plan)
    archive = ArchiveService(db, tmp_path / "out")
    plans = archive.build_plan(batch, ids)
    assert len(plans) == 1 and plans[0].seq == "001"
    archive.commit_plan(batch, plans)
    for file in plans[0].files:
        assert file.output_name == f"{file.instrument_code}02001手动确认曲目.pdf"
        assert sha256_file(file.by_song_path) == sha256_file(file.by_instrument_path) == original[file.source_path]
    assert {path: sha256_file(path) for path in sources} == original
    assert db.list_import_items(batch) == []


def test_explicit_destination_replaces_only_draft_identity_keeps_library_numbers(tmp_path):
    db, root, _, _, _ = correction_fixture(tmp_path)
    target = add_existing(db, tmp_path, "007", "目标曲目")
    other = add_existing(db, tmp_path, "008", "另一曲目")
    source = tmp_path / "0402008错误名字.pdf"
    source.write_bytes(b"%PDF new cello")
    original = sha256_file(source)
    batch = ImportService(db).import_paths([source])
    item_id = int(db.list_import_items(batch)[0]["id"])
    service = CorrectionService(db)
    plan = service.build_plan(batch, [item_id], target_song_id=target)
    fields = json.loads(plan.rows[0].new_fields)
    assert (fields["title"], fields["category_code"], fields["seq"], fields["manual_original_seq"]) == ("目标曲目", "01", "007", "008")
    service.apply(plan)
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch, [item_id])
    assert len(plans) == 1 and plans[0].existing_song_id == target and plans[0].seq == "007"
    archive.commit_plan(batch, plans)
    assert db.get_archived_song(other)["seq"] == "008"
    assert db.allocate_next_sequence() == "001"
    assert len(db.list_archived_scores(target)) == 4
    assert sha256_file(source) == original


def test_corrected_group_can_archive_despite_unrelated_number_conflict(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    add_existing(db, tmp_path, "007", "已有曲目")
    bad = tmp_path / "0101007别的曲目.pdf"
    bad.write_bytes(b"%PDF unrelated wrong sequence")
    ImportService(db).import_paths([bad], batch)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, ids, "天空之城"))
    archive = ArchiveService(db, root)
    with pytest.raises(ValueError, match="冲突"):
        archive.build_plan(batch)
    plans = archive.build_plan(batch, ids)
    assert len(plans) == 1 and plans[0].seq == "001"
    archive.commit_plan(batch, plans)
    assert len(db.list_import_items(batch)) == 1
    assert db.list_import_items(batch)[0]["source_path"] == str(bad)


def test_archives_ready_whole_group_not_unrelated_ready_song(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    more = []
    for code in ("01", "02", "03"):
        pdf = tmp_path / f"{code}02独立作品.pdf"
        pdf.write_bytes(f"%PDF other {code}".encode())
        more.append(pdf)
    ImportService(db).import_paths(more, batch)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [ids[0]], "天空之城"))
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch, [ids[0]])
    assert len(plans) == 1 and len(plans[0].files) == 3
    archive.commit_plan(batch, plans)
    assert len(db.list_import_items(batch)) == 3
    assert db.count_songs() == 1


@pytest.mark.parametrize("problem", ["invalid_name", "missing_source", "changed_source", "stale_record", "wrong_batch"])
def test_correction_rejects_invalid_or_stale_data_without_partial_changes(tmp_path, problem):
    db, root, batch, ids, sources = correction_fixture(tmp_path)
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, "天空之城")
    if problem == "invalid_name":
        with pytest.raises(ValueError):
            service.build_plan(batch, ids, "非法/曲名")
        return
    if problem == "missing_source":
        sources[1].unlink()
    elif problem == "changed_source":
        sources[1].write_bytes(b"changed")
    elif problem == "stale_record":
        db.update_import_item_manual_fields(ids[1], title="后来修改")
    else:
        batch = db.create_import_batch()
        with pytest.raises(ValueError):
            service.build_plan(batch, ids, "天空之城")
        return
    before = [dict(r) for r in db.list_import_items(batch)]
    with pytest.raises(ValueError):
        service.apply(plan)
    assert [dict(r) for r in db.list_import_items(batch)] == before
    assert not root.exists()


def test_correction_failure_rolls_back_all_edits_and_audit(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    before = [dict(r) for r in db.list_import_items(batch)]
    plan = CorrectionService(db).build_plan(batch, ids, "天空之城")
    with db.session() as connection:
        connection.execute(f"CREATE TRIGGER fail_correction BEFORE UPDATE ON import_item WHEN NEW.id={ids[1]} BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
    with pytest.raises(sqlite3.Error):
        CorrectionService(db).apply(plan)
    assert [dict(r) for r in db.list_import_items(batch)] == before
    with db.session() as connection:
        assert connection.execute("SELECT COUNT(*) FROM operation_log").fetchone()[0] == 0


def test_changed_target_and_ambiguous_library_identity_block_assignment(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    target = add_existing(db, tmp_path, "007", "目标曲目")
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, target_song_id=target)
    before = [dict(r) for r in db.list_import_items(batch)]
    with db.session() as connection:
        connection.execute("UPDATE song SET display_title='修改后曲名' WHERE id=?", (target,))
    with pytest.raises(ValueError, match="变化"):
        service.apply(plan)
    assert [dict(r) for r in db.list_import_items(batch)] == before
    db.create_song("01", "008", "修改后曲名", status="archived")
    with pytest.raises(ValueError, match="同名"):
        service.build_plan(batch, ids, target_song_id=target)


def test_corrected_but_incomplete_or_duplicate_stays_draft(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, [ids[0]], "另一首"))
    assert ArchiveService(db, root).build_plan(batch, [ids[0]]) == []
    service.apply(service.build_plan(batch, ids, "天空之城", instruments={ids[1]: "01"}))
    assert ArchiveService(db, root).build_plan(batch, ids) == []
    assert len(db.list_import_items(batch)) == 3 and db.count_scores() == 0


def test_archive_preview_detects_changed_correction_before_writing(tmp_path):
    db, root, batch, ids, _ = correction_fixture(tmp_path)
    service = CorrectionService(db)
    service.apply(service.build_plan(batch, ids, "天空之城"))
    archive = ArchiveService(db, root)
    plans = archive.build_plan(batch, ids)
    db.update_import_item_manual_fields(ids[0], title="改动后")
    with pytest.raises(ValueError, match="变化"):
        archive.commit_plan(batch, plans)
    assert not root.exists() and len(db.list_import_items(batch)) == 3
