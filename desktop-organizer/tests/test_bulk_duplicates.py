import hashlib
import json

from score_organizer.services.duplicate_service import DuplicateService, ConfirmedDuplicateService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.services.archive_service import ArchiveService
import pytest


def bulk_fixture(tmp_path, count=110):
    db = Database(tmp_path / "bulk.sqlite3")
    db.initialize()
    files = []
    for copy in ("a", "b"):
        folder = tmp_path / copy
        folder.mkdir()
        for index in range(count):
            title = "曲目" + hashlib.sha256(str(index).encode()).hexdigest()[:16]
            pdf = folder / f"0101{title}.pdf"
            pdf.write_bytes(f"%PDF-1.4 group {index}".encode())
            files.append(pdf)
    batch = ImportService(db).import_paths(files)
    GroupingService(db).refresh_warnings(batch)
    db.set_setting("output_root", str(tmp_path / "out"))
    return db, batch, files


def test_110_conflict_groups_one_batch_preserves_keeper_and_source_hashes(tmp_path):
    db, batch, files = bulk_fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in files}
    ids = [int(r["id"]) for r in db.list_import_items(batch)]
    service = DuplicateService(db)
    selected = service.exact_redundant_item_ids(batch, ids)
    assert len(selected) == 110
    assert selected == ids[110:]
    package, remaining = ConfirmedDuplicateService(db, tmp_path / "out").confirm(batch, selected)
    assert remaining == 110
    assert [r["id"] for r in db.list_import_items(batch)] == ids[:110]
    assert len(list(package.rglob("*.pdf"))) == 110
    manifest = json.loads((package / "重复清单.json").read_text(encoding="utf-8"))
    assert len(manifest["items"]) == 110
    for item in manifest["items"]:
        assert sha256_file(package / item["relative_path"]) == item["source_sha256"]
    assert {p: sha256_file(p) for p in files} == hashes
    assert db.allocate_next_sequence() == "001"


def test_exact_picker_respects_filter_and_does_not_select_different_content(tmp_path):
    db, batch, files = bulk_fixture(tmp_path, 2)
    different = tmp_path / "c" / files[0].name
    different.parent.mkdir()
    different.write_bytes(b"%PDF different arrangement")
    ImportService(db).import_paths([different], batch)
    GroupingService(db).refresh_warnings(batch)
    items = db.list_import_items(batch)
    candidates = {int(r["id"]) for r in items[2:]}
    assert DuplicateService(db).exact_redundant_item_ids(batch, candidates) == [items[2]["id"], items[3]["id"]]
    assert DuplicateService(db).exact_redundant_item_ids(batch, [items[0]["id"]]) == []


def test_bulk_catalog_matches_can_all_transfer_but_missing_catalog_copy_blocks(tmp_path):
    db = Database(tmp_path / "bulk.sqlite3")
    db.initialize()
    sources = []
    for code in ("01", "02", "03"):
        pdf = tmp_path / f"{code}01曲目.pdf"
        pdf.write_bytes(f"%PDF {code}".encode())
        sources.append(pdf)
    first = ImportService(db).import_paths(sources)
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    archive = ArchiveService(db, root)
    plans = archive.build_plan(first)
    archive.commit_plan(first, plans)
    batch = ImportService(db).import_paths([sources[0], sources[1]])
    GroupingService(db).refresh_warnings(batch)
    ids = [r["id"] for r in db.list_import_items(batch)]
    assert DuplicateService(db).exact_redundant_item_ids(batch, ids) == ids
    plans[0].files[0].by_song_path.unlink()
    with pytest.raises(ValueError, match="每组至少"):
        ConfirmedDuplicateService(db, root).confirm(batch, ids)
    assert len(db.list_import_items(batch)) == 2
    assert not (root / "已确认重复").exists()
    plans[0].files[0].by_song_path.write_bytes(sources[0].read_bytes())
    package, remaining = ConfirmedDuplicateService(db, root).confirm(batch, ids)
    assert remaining == 0 and len(list(package.rglob("*.pdf"))) == 2
    assert len(db.list_archived_scores()) == 3
