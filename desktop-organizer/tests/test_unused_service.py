import json
import shutil
import subprocess
from pathlib import Path

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QLineEdit, QPushButton, QTableWidget, QMessageBox

from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.reset_service import ResetService
from score_organizer.services.unused_service import UnusedScoreService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


def fixture(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    sources = []
    for folder, name in (("a", "0101天空之城.pdf"), ("b", "0101天空之城.pdf"), ("c", "无编号.pdf"), ("d", "0201保留曲目.pdf")):
        source = tmp_path / folder / name
        source.parent.mkdir()
        source.write_bytes(b"%PDF " + folder.encode())
        sources.append(source)
    batch = ImportService(db).import_paths(sources)
    ids = [int(row["id"]) for row in db.list_import_items(batch)]
    return db, root, batch, ids, sources


def test_flat_storage_preserves_originals_selection_and_global_sequence(tmp_path):
    db, root, batch, ids, sources = fixture(tmp_path)
    hashes = {p: sha256_file(p) for p in sources}
    service = UnusedScoreService(db, root)
    plan = service.build_plan(batch, ids[:3])
    assert not root.exists()
    folder, remaining = service.commit_plan(batch, plan)
    assert folder == root / "暂时无用乐谱" and remaining == 1
    assert not any(p.is_dir() for p in folder.iterdir())
    assert {p.name for p in folder.glob("*.pdf")} == {"0101天空之城.pdf", "0101天空之城（同名2）.pdf", "无编号.pdf"}
    assert {p: sha256_file(p) for p in sources} == hashes
    assert [int(row["id"]) for row in db.list_import_items(batch)] == ids[3:]
    assert db.count_songs() == db.count_scores() == 0
    assert db.allocate_next_sequence() == "001"
    assert not (root / "按曲目分类").exists() and not (root / "按乐器分类").exists()
    assert len(service.list_pending()) == 3
    manifest = json.loads((folder / service.manifest_name).read_bytes())
    assert all(sha256_file(folder / item["relative_path"]) == item["source_sha256"] for item in manifest["items"])
    text = (folder / service.text_manifest_name).read_text(encoding="utf-8-sig")
    assert all(str(p) in text for p in sources[:3])


def test_append_same_names_keeps_old_pdfs_and_restore_fields(tmp_path):
    db, root, batch, ids, sources = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:2]))
    before = {p: sha256_file(p) for p in service.root.glob("*.pdf")}
    new_batch = ImportService(db).import_paths([sources[0]])
    new_ids = [int(row["id"]) for row in db.list_import_items(new_batch) if row["source_path"] == str(sources[0])]
    service.commit_plan(new_batch, service.build_plan(new_batch, new_ids))
    assert (service.root / "0101天空之城（同名3）.pdf").is_file()
    assert {p: sha256_file(p) for p in before} == before
    entries = service.list_pending()
    restored_batch = service.restore_to_draft(entries[:1])
    row = next(row for row in db.list_import_items(restored_batch) if row["source_path"] == str(entries[0]["pdf_path"]))
    assert row["status"] == entries[0]["status"]
    assert json.loads(row["parsed_fields"]) == entries[0]["parsed_fields"]
    count = len(db.list_import_items(restored_batch))
    service.restore_to_draft(entries[:1], restored_batch)
    assert len(db.list_import_items(restored_batch)) == count
    assert len(service.list_pending()) == 2
    assert entries[0]["pdf_path"].is_file()


@pytest.mark.parametrize("change", ["source", "remove", "fields", "new_target"])
def test_stale_preview_blocks_without_removing_draft(tmp_path, change):
    db, root, batch, ids, sources = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    plan = service.build_plan(batch, ids[:1])
    if change == "source":
        sources[0].write_bytes(b"%PDF changed")
    elif change == "remove":
        sources[0].unlink()
    elif change == "fields":
        with db.session() as connection:
            connection.execute("UPDATE import_item SET parsed_fields = ? WHERE id = ?", ('{"title":"new"}', ids[0]))
    else:
        service.root.mkdir(parents=True)
        (service.root / plan[0]["relative_path"]).write_bytes(b"%PDF existing")
    with pytest.raises(ValueError):
        service.commit_plan(batch, plan)
    assert len(db.list_import_items(batch)) == 4
    assert not (service.root / service.manifest_name).exists()


@pytest.mark.parametrize("failure", ["database", "json", "text"])
def test_failure_rolls_back_new_outputs_keeps_previous_manifest(tmp_path, monkeypatch, failure):
    db, root, batch, ids, sources = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:1]))
    before = {p: p.read_bytes() for p in service.root.iterdir()}
    plan = service.build_plan(batch, ids[1:3])
    if failure == "database":
        remove = db.remove_import_items_from_draft
        def fail(*args):
            remove(*args)
            raise ValueError("模拟数据库失败")
        monkeypatch.setattr(db, "remove_import_items_from_draft", fail)
    else:
        writer = service._write_manifest
        target = service.manifest_name if failure == "json" else service.text_manifest_name
        failed = False
        def fail(path, content):
            nonlocal failed
            if path.name == target and not failed:
                failed = True
                raise OSError("模拟清单失败")
            writer(path, content)
        monkeypatch.setattr(service, "_write_manifest", fail)
    with pytest.raises((ValueError, OSError)):
        service.commit_plan(batch, plan)
    assert {p: p.read_bytes() for p in service.root.iterdir()} == before
    assert len(db.list_import_items(batch)) == 3
    assert not list(root.glob(".unused-*"))
    assert all(p.exists() for p in sources)


def test_invalid_selection_and_tampered_manifest_cannot_escape(tmp_path):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    for selection in ([], [999999]):
        with pytest.raises(ValueError):
            service.build_plan(batch, selection)
    service.commit_plan(batch, service.build_plan(batch, ids[:1]))
    entries = service.list_pending()
    with pytest.raises(ValueError):
        service.restore_to_draft([{**entries[0], "index": -1}])
    path = service.root / service.manifest_name
    manifest = json.loads(path.read_bytes())
    manifest["items"][0]["relative_path"] = "../scores.sqlite3.pdf"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="无效PDF路径"):
        service.list_pending()
    assert len(db.list_import_items(batch)) == 3


def test_restore_changed_pdf_keeps_draft_and_pending_list(tmp_path):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:1]))
    entries = service.list_pending()
    entries[0]["pdf_path"].write_bytes(b"%PDF changed")
    with pytest.raises(ValueError, match="变化"):
        service.restore_to_draft(entries, batch)
    assert len(db.list_import_items(batch)) == 3
    assert len(service.list_pending()) == 1


def test_full_clear_and_restore_include_unused_folder(tmp_path):
    db, root, batch, ids, sources = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:2]))
    original = {p.relative_to(root): p.read_bytes() for p in service.root.iterdir()}
    reset = ResetService(db)
    backup = reset.clear(reset.build_plan())
    assert not service.root.exists()
    assert (backup / "暂时无用乐谱").is_dir()
    reset.restore(backup)
    assert {p.relative_to(root): p.read_bytes() for p in service.root.iterdir()} == original
    assert len(service.list_pending()) == 2
    assert all(p.exists() for p in sources)


@pytest.mark.parametrize("phase", ["stage", "publish"])
def test_copy_interrupted_rolls_back_and_preserves_draft(tmp_path, monkeypatch, phase):
    db, root, batch, ids, sources = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    plan = service.build_plan(batch, ids[:2])
    if phase == "stage":
        def fail(*args):
            raise OSError("模拟磁盘写入失败")
        monkeypatch.setattr(shutil, "copy2", fail)
    else:
        original = shutil.copyfileobj
        def fail(source, target, *args):
            if Path(target.name).parent == service.root:
                target.write(b"partial")
                raise OSError("模拟磁盘写入失败")
            original(source, target, *args)
        monkeypatch.setattr(shutil, "copyfileobj", fail)
    with pytest.raises(OSError, match="模拟"):
        service.commit_plan(batch, plan)
    assert len(db.list_import_items(batch)) == 4
    assert not list(service.root.iterdir())
    assert not list(root.glob(".unused-*"))
    assert all(p.exists() for p in sources)


def test_staging_cleanup_failure_cannot_remove_draft_after_commit(tmp_path, monkeypatch):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    plan = service.build_plan(batch, ids[:1])
    remove = shutil.rmtree
    failed = False
    def fail_once(path, *args, **kwargs):
        nonlocal failed
        if not failed:
            failed = True
            raise OSError("模拟临时目录清理失败")
        return remove(path, *args, **kwargs)
    monkeypatch.setattr(shutil, "rmtree", fail_once)
    with pytest.raises(OSError, match="清理失败"):
        service.commit_plan(batch, plan)
    assert len(db.list_import_items(batch)) == 4
    assert not list(service.root.iterdir())
    assert not list(root.glob(".unused-*"))


def test_cleanup_rejects_directory_outside_the_staging_parent(tmp_path):
    db, root, _, _, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    outside = tmp_path / ".unused-other"
    outside.mkdir()
    original = outside / "keep.pdf"
    original.write_bytes(b"%PDF keep")
    with pytest.raises(ValueError, match="不安全"):
        service._remove_stage(outside)
    assert original.read_bytes() == b"%PDF keep"


@pytest.mark.parametrize("after_write", [False, True])
def test_restore_write_failure_rolls_back_database_and_manifest(tmp_path, monkeypatch, after_write):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:2]))
    before = (service.root / service.manifest_name).read_bytes()
    writer = service._write_manifest
    failed = False
    def fail(*args):
        nonlocal failed
        if failed:
            return writer(*args)
        failed = True
        if after_write:
            writer(*args)
        raise OSError("模拟恢复清单写入失败")
    monkeypatch.setattr(service, "_write_manifest", fail)
    with pytest.raises(OSError):
        service.restore_to_draft(service.list_pending(), batch)
    assert len(db.list_import_items(batch)) == 2
    assert (service.root / service.manifest_name).read_bytes() == before
    assert len(service.list_pending()) == 2


def test_formal_library_and_numbering_unchanged_by_unused_storage(tmp_path):
    db, root, batch, ids, sources = fixture(tmp_path)
    formal = []
    for code in ("01", "02", "03"):
        source = tmp_path / f"{code}01正式曲目.pdf"
        source.write_bytes(b"%PDF formal " + code.encode())
        formal.append(source)
    formal_batch = ImportService(db).import_paths(formal)
    archive = ArchiveService(db, root)
    archive.commit_plan(formal_batch, archive.build_plan(formal_batch))
    hashes = {p: sha256_file(p) for p in root.rglob("*.pdf")}
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids))
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert db.allocate_next_sequence() == "002"
    assert {p: sha256_file(p) for p in hashes} == hashes
    assert all(p.exists() for p in sources + formal)


def test_case_insensitive_names_do_not_overwrite(tmp_path):
    db, root, batch, _, _ = fixture(tmp_path)
    files = []
    for folder, name in (("upper", "0101SONG.pdf"), ("lower", "0101song.PDF")):
        path = tmp_path / folder / name
        path.parent.mkdir()
        path.write_bytes(b"%PDF " + folder.encode())
        files.append(path)
    extra = ImportService(db).import_paths(files)
    service = UnusedScoreService(db, root)
    plan = service.build_plan(extra, [row["id"] for row in db.list_import_items(extra)])
    service.commit_plan(extra, plan)
    assert len({item["relative_path"].casefold() for item in plan}) == 2
    assert {sha256_file(p) for p in service.root.glob("*.pdf")} == {sha256_file(p) for p in files}


def test_reset_protects_unused_pdf_after_restored_as_source(tmp_path):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:1]))
    entry = service.list_pending()[0]
    service.restore_to_draft([entry], batch)
    with pytest.raises(ValueError):
        ResetService(db).build_plan()
    assert entry["pdf_path"].is_file()
    assert len(db.list_import_items(batch)) == 4


def test_junction_folder_rejected_without_writing_external_target(tmp_path):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    root.mkdir()
    external = tmp_path / "external"
    external.mkdir()
    result = subprocess.run(["cmd", "/c", "mklink", "/J", str(service.root), str(external)], capture_output=True)
    assert result.returncode == 0
    try:
        with pytest.raises(ValueError, match="链接"):
            service.build_plan(batch, ids[:1])
        assert not list(external.iterdir())
        assert len(db.list_import_items(batch)) == 4
    finally:
        service.root.rmdir()


@pytest.mark.parametrize("relative", ["CON.pdf", "file.pdf:stream.pdf", "file?.pdf"])
def test_manifest_unsafe_windows_filename_rejected(tmp_path, relative):
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids[:1]))
    path = service.root / service.manifest_name
    manifest = json.loads(path.read_bytes())
    manifest["items"][0]["relative_path"] = relative
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError):
        service.list_pending()


def test_native_ui_preview_cancel_transfer_view_and_return(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, sources = fixture(tmp_path)
    window = MainWindow(db)
    window.show()
    app.processEvents()
    window.grab().save(str(tmp_path / "unused-main.png"))
    warnings = []
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: warnings.append(args[-1]))
    opened = []
    monkeypatch.setattr(window, "open_pdf_path", opened.append)
    window.menu_actions["store_unused_scores"].trigger()
    assert warnings and not root.exists()
    window.score_table.selectRow(0)
    def cancel():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认暂时无用乐谱"
        dialog.reject()
    QTimer.singleShot(50, cancel)
    window.menu_actions["store_unused_scores"].trigger()
    assert len(db.list_import_items(batch)) == 4 and not root.exists()
    window.score_table.selectAll()
    def confirm():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "确认暂时无用乐谱"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 4
        table.cellDoubleClicked.emit(0, 0)
        assert opened == [sources[0]]
        dialog.grab().save(str(tmp_path / "unused-preview.png"))
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "确认暂存").click()
    QTimer.singleShot(50, confirm)
    window.menu_actions["store_unused_scores"].trigger()
    assert window.score_table.rowCount() == 0 and window.current_batch_id is None
    assert window.warning_table.rowCount() == 0 and window.report_table.rowCount() == 0
    def restore():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "暂时无用乐谱"
        table = dialog.findChild(QTableWidget)
        assert table.rowCount() == 4
        table.setCurrentCell(0, 0)
        table.selectAll()
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "打开选中PDF").click()
        assert opened[-1].parent == root / "暂时无用乐谱"
        dialog.grab().save(str(tmp_path / "unused-list.png"))
        next(b for b in dialog.findChildren(QPushButton) if b.text() == "放回草稿继续整理").click()
        assert table.rowCount() == 0
        dialog.reject()
    timer_errors = []
    def safe_restore():
        try:
            restore()
        except Exception as exc:
            timer_errors.append(exc)
            app.activeModalWidget().reject()
    QTimer.singleShot(50, safe_restore)
    window.menu_actions["show_unused_scores"].trigger()
    assert not timer_errors
    assert window.current_batch_id is not None and window.score_table.rowCount() == 4
    assert len(list((root / "暂时无用乐谱").glob("*.pdf"))) == 4
    assert not any(p.is_dir() for p in (root / "暂时无用乐谱").iterdir())
    window.close()
    app.processEvents()


def test_native_search_restores_only_visible_selected_files(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, batch, ids, _ = fixture(tmp_path)
    service = UnusedScoreService(db, root)
    service.commit_plan(batch, service.build_plan(batch, ids))
    window = MainWindow(db)
    window.show()
    monkeypatch.setattr(QMessageBox, "warning", lambda *args: None)
    errors = []
    def operate():
        dialog = app.activeModalWidget()
        try:
            table = dialog.findChild(QTableWidget)
            table.setCurrentCell(0, 0)
            table.selectAll()
            search = dialog.findChild(QLineEdit)
            search.setText("无编号.pdf")
            assert len({item.row() for item in table.selectedItems()}) == 1
            next(b for b in dialog.findChildren(QPushButton) if b.text() == "放回草稿继续整理").click()
            assert len(db.list_import_items(window.current_batch_id)) == 1
            assert len(service.list_pending()) == 3
            search.clear()
            assert table.rowCount() == 3
        except Exception as exc:
            errors.append(exc)
        finally:
            dialog.reject()
    QTimer.singleShot(50, operate)
    window.menu_actions["show_unused_scores"].trigger()
    assert not errors
    window.close()
    app.processEvents()
