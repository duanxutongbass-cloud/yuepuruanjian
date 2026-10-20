from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from pathlib import Path


def _write_pdf(path, content):
    path.write_bytes(content)
    return path


def test_whitespace_titles_cannot_silently_take_two_numbers_and_manual_correction_archives(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    files = [
        _write_pdf(tmp_path / f"{code}01{title}.pdf", f"%PDF {code}".encode())
        for code, title in [("01", "萤火虫之墓 《可爱的家 》"), ("02", "萤火虫之墓 《可爱的家 》"),
                            ("03", "萤火虫之墓 《可爱的家 》"), ("04", "萤火虫之墓《可爱的家》"),
                            ("05", "萤火虫之墓《可爱的家》"), ("06", "萤火虫之墓《可爱的家》")]
    ]
    hashes = {p: sha256_file(p) for p in files}
    batch = ImportService(db).import_paths(files)
    service = ArchiveService(db, tmp_path / "out")
    assert service.build_plan(batch) == []
    assert db.count_songs() == 0
    assert not (tmp_path / "out").exists()
    for item in db.list_import_items(batch):
        db.update_import_item_manual_fields(item["id"], title="萤火虫之墓《可爱的家》")
    plan = service.build_plan(batch)
    assert len(plan) == 1 and plan[0].seq == "001"
    service.commit_plan(batch, plan)
    assert db.count_songs() == 1
    assert db.count_scores() == 6
    for file in plan[0].files:
        assert sha256_file(file.by_instrument_path) == sha256_file(file.by_song_path) == hashes[file.source_path]
    assert {p: sha256_file(p) for p in files} == hashes


def test_whitespace_variant_of_existing_song_blocks_new_number_then_supplements(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    initial = [_write_pdf(tmp_path / f"{code}01萤火虫之墓《可爱的家》.pdf", f"%PDF {code}".encode()) for code in ("01", "02", "03")]
    service = ArchiveService(db, tmp_path / "out")
    first = ImportService(db).import_paths(initial)
    service.commit_plan(first, service.build_plan(first))
    added = [_write_pdf(tmp_path / f"{code}01萤火虫之墓 《可爱的家》.pdf", f"%PDF {code}".encode()) for code in ("04", "05", "06")]
    batch = ImportService(db).import_paths(added)
    assert service.build_plan(batch) == []
    for item in db.list_import_items(batch):
        db.update_import_item_manual_fields(item["id"], title="萤火虫之墓《可爱的家》")
    plan = service.build_plan(batch)
    assert len(plan) == 1 and plan[0].seq == "001" and plan[0].existing_song_id is not None
    service.commit_plan(batch, plan)
    assert db.count_songs() == 1 and db.count_scores() == 6


def test_unifying_whitespace_does_not_hide_different_files_for_same_instrument(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    files = [_write_pdf(tmp_path / f"{code}01{title}.pdf", f"%PDF {index}".encode())
             for index, (code, title) in enumerate([
                 ("02", "萤火虫之墓 《可爱的家 》"), ("03", "萤火虫之墓 《可爱的家 》"),
                 ("04", "萤火虫之墓 《可爱的家 》"), ("02", "萤火虫之墓《可爱的家》"),
                 ("05", "萤火虫之墓《可爱的家》"), ("06", "萤火虫之墓《可爱的家》")])]
    batch = ImportService(db).import_paths(files)
    service = ArchiveService(db, tmp_path / "out")
    assert service.build_plan(batch) == []
    for item in db.list_import_items(batch):
        db.update_import_item_manual_fields(item["id"], title="萤火虫之墓《可爱的家》")
    assert service.build_plan(batch) == []
    conflicts = [w for w in db.list_warnings(batch) if w["code"] == "same_instrument_conflict"]
    assert len(conflicts) == 1
    assert db.count_songs() == 0
    assert all(p.exists() for p in files)


def test_archive_plan_and_commit_writes_two_directories_without_changing_sources(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    files = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF violin1"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF violin2"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF viola"),
    ]
    original_hashes = {path: sha256_file(path) for path in files}
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths(files)
    output_root = tmp_path / "乐谱库输出"
    service = ArchiveService(db, output_root)

    plan = service.build_plan(batch_id)
    service.commit_plan(batch_id, plan)

    assert plan[0].seq == "001"
    assert plan[0].folder_name == "01001天空之城（03）"
    for path, digest in original_hashes.items():
        assert sha256_file(path) == digest
    for file_plan in plan[0].files:
        assert file_plan.by_instrument_path.exists()
        assert file_plan.by_song_path.exists()
        assert sha256_file(file_plan.by_instrument_path) == file_plan.source_sha256
        assert sha256_file(file_plan.by_song_path) == file_plan.source_sha256
    by_song_names = {
        path.name
        for path in (output_root / "按曲目分类" / "01001天空之城（03）").glob("*.pdf")
    }
    assert by_song_names == {
        "0101001天空之城.pdf",
        "0201001天空之城.pdf",
        "0301001天空之城.pdf",
    }
    assert db.count_songs() == 1
    assert db.count_scores() == 3


def test_archive_uses_next_global_sequence(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    db.create_song("01", "007", "已有曲目", status="archived")
    source = tmp_path / "source"
    source.mkdir()
    files = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF a"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF b"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF c"),
    ]
    batch_id = ImportService(db).import_paths(files)

    plan = ArchiveService(db, tmp_path / "out").build_plan(batch_id)

    assert plan[0].seq == "001"


def test_archive_blocks_when_target_exists(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    files = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF a"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF b"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF c"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths(files)
    output_root = tmp_path / "out"
    existing = output_root / "按乐器分类" / "01第一小提琴" / "0101001天空之城.pdf"
    existing.parent.mkdir(parents=True)
    existing.write_bytes(b"existing")

    try:
        ArchiveService(db, output_root).build_plan(batch_id)
    except ValueError as exc:
        assert "目标文件已存在" in str(exc)
    else:
        raise AssertionError("expected target conflict")


def test_archive_blocks_insufficient_instruments(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    files = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF a"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF b"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths(files)

    assert ArchiveService(db, tmp_path / "out").build_plan(batch_id) == []


def test_archive_blocks_exact_duplicate_pdf_even_with_three_instrument_names(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    files = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF same"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF same"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF different"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths(files)

    assert ArchiveService(db, tmp_path / "out").build_plan(batch_id) == []


def test_supplement_score_reuses_existing_sequence(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    initial = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF a"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF b"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF c"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    output_root = tmp_path / "out"
    first_batch = ImportService(db).import_paths(initial)
    service = ArchiveService(db, output_root)
    first_plan = service.build_plan(first_batch)
    service.commit_plan(first_batch, first_plan)

    supplement = [_write_pdf(source / "0401天空之城.pdf", b"%PDF d")]
    second_batch = ImportService(db).import_paths(supplement)
    second_plan = ArchiveService(db, output_root).build_plan(second_batch)
    ArchiveService(db, output_root).commit_plan(second_batch, second_plan)

    assert second_plan[0].seq == "001"
    assert second_plan[0].existing_song_id is not None
    assert (output_root / "按曲目分类" / "01001天空之城（04）" / "0401001天空之城.pdf").exists()
    assert db.count_songs() == 1
    assert db.count_scores() == 4
    folder = output_root / "按曲目分类" / "01001天空之城（04）"
    assert len(list(folder.glob("*.pdf"))) == 4
    assert not (output_root / "按曲目分类" / "01001天空之城（03）").exists()
    for pdf in folder.glob("*.pdf"):
        instrument_copy = next((output_root / "按乐器分类").rglob(pdf.name))
        assert sha256_file(pdf) == sha256_file(instrument_copy)


def test_failed_archive_rolls_back_catalog_outputs_and_keeps_draft(tmp_path, monkeypatch):
    import pytest
    import shutil
    files = [_write_pdf(tmp_path / f"{code}01测试.pdf", f"%PDF {code}".encode()) for code in ("01", "02", "03")]
    hashes = [sha256_file(path) for path in files]
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    batch = ImportService(db).import_paths(files)
    service = ArchiveService(db, tmp_path / "out")
    plan = service.build_plan(batch)
    original = shutil.copyfileobj
    calls = 0
    def failing_copy(*args, **kwargs):
        nonlocal calls
        calls += 1
        if calls == 4:
            raise OSError("模拟磁盘写入失败")
        return original(*args, **kwargs)
    monkeypatch.setattr(shutil, "copyfileobj", failing_copy)
    with pytest.raises(OSError):
        service.commit_plan(batch, plan)
    assert db.count_songs() == db.count_scores() == 0
    assert len(db.list_import_items(batch)) == 3
    assert not list((tmp_path / "out").rglob("*.pdf"))
    assert [sha256_file(path) for path in files] == hashes
    monkeypatch.setattr(shutil, "copyfileobj", original)
    service.commit_plan(batch, service.build_plan(batch))
    assert db.count_scores() == 3


def test_archive_preserves_imported_sequence_and_blocks_cross_category_conflict(tmp_path):
    import pytest
    files = [_write_pdf(tmp_path / f"{code}01017测试.pdf", f"%PDF {code}".encode()) for code in ("01", "02", "03")]
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    batch = ImportService(db).import_paths(files)
    service = ArchiveService(db, tmp_path / "out")
    assert service.build_plan(batch)[0].seq == "017"
    db.create_song("02", "017", "其他曲目", status="archived")
    with pytest.raises(ValueError, match="冲突"):
        service.build_plan(batch)


def test_supplement_failure_restores_original_folder_and_catalog(tmp_path, monkeypatch):
    import pytest
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = [_write_pdf(tmp_path / f"{code}01测试.pdf", f"%PDF {code}".encode()) for code in ("01", "02", "03")]
    service = ArchiveService(db, tmp_path / "out")
    batch = ImportService(db).import_paths(files)
    service.commit_plan(batch, service.build_plan(batch))
    supplement = _write_pdf(tmp_path / "0401测试.pdf", b"%PDF fourth")
    batch = ImportService(db).import_paths([supplement])
    plan = service.build_plan(batch)
    def fail_catalog(*args, **kwargs):
        raise ValueError("模拟目录登记失败")
    monkeypatch.setattr(db, "add_score", fail_catalog)
    with pytest.raises(ValueError):
        service.commit_plan(batch, plan)
    old_folder = tmp_path / "out" / "按曲目分类" / "01001测试（03）"
    assert len(list(old_folder.glob("*.pdf"))) == 3
    assert not (old_folder.parent / "01001测试（04）").exists()
    assert db.count_scores() == 3
    assert len(db.list_import_items(batch)) == 1
    assert not list((tmp_path / "out").rglob("0401001测试.pdf"))


def test_archived_instrument_count_counts_distinct_instruments(tmp_path):
    source = tmp_path / "source"
    source.mkdir()
    initial = [
        _write_pdf(source / "0101天空之城.pdf", b"%PDF a"),
        _write_pdf(source / "0201天空之城.pdf", b"%PDF b"),
        _write_pdf(source / "0301天空之城.pdf", b"%PDF c"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    output_root = tmp_path / "out"
    first_batch = ImportService(db).import_paths(initial)
    service = ArchiveService(db, output_root)
    service.commit_plan(first_batch, service.build_plan(first_batch))

    song = db.find_archived_song("01", "天空之城")
    assert song is not None
    db.add_score(int(song["id"]), "01", "extra.pdf", "extra.pdf", "x", "extra.pdf")

    assert db.archived_instrument_count(int(song["id"])) == 3
    assert db.archived_instrument_codes(int(song["id"])) == {"01", "02", "03"}


def test_archive_commit_removes_archived_items_but_keeps_incomplete_draft(tmp_path):
    ready_files = [
        _write_pdf(tmp_path / "0101天空之城.pdf", b"%PDF ready 1"),
        _write_pdf(tmp_path / "0201天空之城.pdf", b"%PDF ready 2"),
        _write_pdf(tmp_path / "0301天空之城.pdf", b"%PDF ready 3"),
    ]
    waiting_files = [
        _write_pdf(tmp_path / "0102菊次郎的夏天.pdf", b"%PDF waiting 1"),
        _write_pdf(tmp_path / "0202菊次郎的夏天.pdf", b"%PDF waiting 2"),
    ]
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths([*ready_files, *waiting_files])
    service = ArchiveService(db, tmp_path / "out")

    plan = service.build_plan(batch_id)
    remaining = service.commit_plan(batch_id, plan)

    assert remaining == 2
    remaining_names = {Path(item["source_path"]).name for item in db.list_import_items(batch_id)}
    assert remaining_names == {path.name for path in waiting_files}
    assert db.count_scores() == 3


def test_unrelated_duplicate_song_does_not_block_ready_song(tmp_path):
    db = Database(tmp_path / "test.sqlite3")
    db.initialize()
    files = [_write_pdf(tmp_path / f"{code}01完整曲.pdf", f"%PDF ready {code}".encode()) for code in ("01", "02", "03")]
    files += [_write_pdf(tmp_path / f"{code}02重复曲.pdf", b"%PDF duplicate") for code in ("01", "02", "03")]
    batch = ImportService(db).import_paths(files)
    archive = ArchiveService(db, tmp_path / "out")
    plans = archive.build_plan(batch)
    assert [plan.title for plan in plans] == ["完整曲"]
    remaining = archive.commit_plan(batch, plans)
    assert remaining == 3
    assert db.count_scores() == 3
