import json
import shutil

import pytest
from PySide6.QtCore import QTimer
from PySide6.QtWidgets import QApplication, QMessageBox, QPushButton, QTableWidget

from score_organizer.services.duplicate_layout import DuplicateLayoutStore, safe_duplicate_name
from score_organizer.services.duplicate_service import ConfirmedDuplicateService
from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.services.review_service import ReviewService
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow
from test_confirmed_duplicates import duplicate_batch


def tree_hashes(root):
    return {str(path.relative_to(root)): sha256_file(path) for path in root.rglob("*") if path.is_file()}


def legacy_fixture(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    root = tmp_path / "out"
    db.set_setting("output_root", str(root))
    item = db.list_import_items(batch)[1]
    plan = [{"item_id": int(item["id"]), "source_path": item["source_path"],
             "source_sha256": item["source_sha256"], "status": item["status"],
             "parsed_fields": json.loads(item["parsed_fields"]), "reasons": ["用户已确认重复"],
             "details": ["保留或库内对比文件：" + str(files[0])], "restored": False,
             "relative_path": "分谱000002/0101曲目.pdf"}]
    package, _ = ConfirmedDuplicateService(db, root)._store_plan(batch, plan)
    return db, root, package, files


def test_five_copies_go_directly_in_one_song_folder_and_continue_numbering(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    for index in range(4):
        path = tmp_path / f"copy{index}" / files[0].name
        path.parent.mkdir()
        path.write_bytes(files[0].read_bytes())
        files.append(path)
    ImportService(db).import_paths(files[2:], batch)
    GroupingService(db).refresh_warnings(batch)
    ids = [int(item["id"]) for item in db.list_import_items(batch)]
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    root, remaining = service.confirm(batch, ids[1:])
    assert remaining == 1
    curve = root / "曲目"
    assert {path.name for path in curve.glob("*.pdf")} == {f"01第一小提琴-曲目（重复{number}）.pdf" for number in range(1, 6)}
    assert not any(path.is_dir() for path in curve.iterdir())
    assert {path.name for path in root.iterdir() if path.is_dir()} == {"曲目"}
    before = tree_hashes(root)
    new = tmp_path / "more" / files[0].name
    new.parent.mkdir()
    new.write_bytes(files[0].read_bytes())
    ImportService(db).import_paths([new], batch)
    GroupingService(db).refresh_warnings(batch)
    service.confirm(batch, [int(db.list_import_items(batch)[-1]["id"])])
    assert (curve / "01第一小提琴-曲目（重复6）.pdf").is_file()
    assert all(sha256_file(root / name) == digest for name, digest in before.items() if name.endswith(".pdf"))
    assert all(path.read_bytes() == b"%PDF same" for path in files)
    assert db.count_songs() == 0
    assert len(json.loads((root / "重复清单.json").read_text(encoding="utf-8"))["items"]) == 6


def test_metadata_failure_preserves_existing_duplicates_and_drafts(tmp_path, monkeypatch):
    db, batch, files = duplicate_batch(tmp_path)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    service.confirm(batch, [int(db.list_import_items(batch)[1]["id"])])
    root = service.root
    before = tree_hashes(root)
    new = tmp_path / "c" / files[0].name
    new.parent.mkdir()
    new.write_bytes(files[0].read_bytes())
    ImportService(db).import_paths([new], batch)
    GroupingService(db).refresh_warnings(batch)
    before_items = [dict(item) for item in db.list_import_items(batch)]
    original = ReviewService._write_manifest
    failed = False
    def fail_once(self, path, content):
        nonlocal failed
        if not failed and path.parent.name == "曲目":
            failed = True
            raise PermissionError("模拟清单写入失败")
        return original(self, path, content)
    monkeypatch.setattr(ReviewService, "_write_manifest", fail_once)
    with pytest.raises(PermissionError):
        service.confirm(batch, [int(db.list_import_items(batch)[-1]["id"])])
    assert failed and tree_hashes(root) == before
    assert [dict(item) for item in db.list_import_items(batch)] == before_items
    assert new.is_file()


@pytest.mark.parametrize("title", ["CON", "A/B:非法*曲名", "很长的标题" * 40])
def test_safe_layout_names_stay_inside_root_and_do_not_exceed_windows_limit(tmp_path, title):
    db, batch, files = duplicate_batch(tmp_path)
    store = DuplicateLayoutStore(db, tmp_path / "out")
    entries = [{"source_path": str(files[0]), "source_sha256": sha256_file(files[0]),
                "display_title": title, "parsed_fields": {"instrument_code": "01"}}]
    first, second = store.allocate(entries * 2)
    assert first["relative_path"] != second["relative_path"]
    for item in (first, second):
        assert (store.root / item["relative_path"]).resolve().is_relative_to(store.root.resolve())
        assert len((store.root / item["relative_path"]).name.encode("utf-16-le")) // 2 < 255
    assert safe_duplicate_name("CON") == "CON_乐谱"


def test_migrate_old_package_previews_backups_and_keeps_sources_and_db(tmp_path):
    db, root, package, files = legacy_fixture(tmp_path)
    hashes = tree_hashes(package)
    sources = {path: sha256_file(path) for path in files}
    before_items = [dict(item) for item in db.list_import_items(db.latest_draft_batch_id())]
    store = DuplicateLayoutStore(db, root)
    plan = store.legacy_plan()
    assert len(plan["entries"]) == 1 and package.is_dir()
    assert not (store.root / "曲目").exists()
    backup = store.migrate(plan)
    assert (backup / "整理前数据库.sqlite3").is_file()
    assert tree_hashes(backup / package.name) == hashes
    assert not package.exists()
    copy = store.root / "曲目" / "01第一小提琴-曲目（重复1）.pdf"
    assert sha256_file(copy) == sources[files[1]]
    assert {path: sha256_file(path) for path in files} == sources
    assert [dict(item) for item in db.list_import_items(db.latest_draft_batch_id())] == before_items
    records = json.loads((store.root / "重复清单.json").read_text(encoding="utf-8"))["items"]
    assert records[0]["legacy_backup"] == str(backup / package.name)
    assert not store.legacy_plan()["entries"]


@pytest.mark.parametrize("failure", ["changed", "missing", "referenced", "unregistered"])
def test_unsafe_or_changed_legacy_files_do_not_move(tmp_path, failure):
    db, root, package, files = legacy_fixture(tmp_path)
    copy = next(package.rglob("*.pdf"))
    store = DuplicateLayoutStore(db, root)
    plan = store.legacy_plan()
    if failure == "changed":
        copy.write_bytes(b"%PDF externally changed")
    elif failure == "missing":
        copy.unlink()
    elif failure == "referenced":
        ImportService(db).import_paths([copy], db.latest_draft_batch_id())
    else:
        (package / "用户额外文件.pdf").write_bytes(b"%PDF not registered")
    with pytest.raises(ValueError):
        store.migrate(plan)
    assert package.exists() and not (store.root / "曲目").exists()
    assert all(path.is_file() for path in files)


def test_migration_folder_move_failure_rolls_back_all_outputs(tmp_path, monkeypatch):
    from pathlib import Path
    db, root, package, files = legacy_fixture(tmp_path)
    store = DuplicateLayoutStore(db, root)
    plan = store.legacy_plan()
    before = tree_hashes(store.root)
    original = Path.rename
    def fail_legacy(self, target):
        if self == package:
            raise PermissionError("模拟旧目录被锁定")
        return original(self, target)
    monkeypatch.setattr(Path, "rename", fail_legacy)
    with pytest.raises(PermissionError):
        store.migrate(plan)
    assert tree_hashes(store.root) == before
    assert package.exists() and not (store.root / "曲目").exists()
    assert all(path.is_file() for path in files)


def test_partial_migration_move_failure_restores_already_moved_packages(tmp_path, monkeypatch):
    from pathlib import Path
    db, root, first, files = legacy_fixture(tmp_path)
    third = tmp_path / "c" / files[0].name
    third.parent.mkdir()
    third.write_bytes(files[0].read_bytes())
    batch = ImportService(db).import_paths([third], db.latest_draft_batch_id())
    item = db.list_import_items(batch)[-1]
    ConfirmedDuplicateService(db, root)._store_plan(batch, [{
        "item_id": int(item["id"]), "source_path": str(third), "source_sha256": item["source_sha256"],
        "status": item["status"], "parsed_fields": json.loads(item["parsed_fields"]),
        "reasons": ["用户已确认重复"], "details": [str(files[0])], "relative_path": "分谱000003/0101曲目.pdf"}])
    store = DuplicateLayoutStore(db, root)
    plan = store.legacy_plan()
    packages = list(plan["packages"])
    assert len(packages) == 2
    before = tree_hashes(store.root)
    original = Path.rename
    moved_first = False
    def fail_second(self, target):
        nonlocal moved_first
        if str(self) == packages[1]:
            assert moved_first
            raise PermissionError("第二个目录无法移动")
        result = original(self, target)
        if str(self) == packages[0]:
            moved_first = True
        return result
    monkeypatch.setattr(Path, "rename", fail_second)
    with pytest.raises(PermissionError):
        store.migrate(plan)
    assert moved_first and tree_hashes(store.root) == before
    assert all(Path(package).exists() for package in packages)
    assert all(path.is_file() for path in [*files, third])


def test_missing_global_or_out_of_sync_curve_manifest_cannot_overwrite_records(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    service = ConfirmedDuplicateService(db, tmp_path / "out")
    service.confirm(batch, [int(db.list_import_items(batch)[1]["id"])])
    new = tmp_path / "more" / files[0].name
    new.parent.mkdir()
    new.write_bytes(files[0].read_bytes())
    ImportService(db).import_paths([new], batch)
    GroupingService(db).refresh_warnings(batch)
    selected = [int(db.list_import_items(batch)[-1]["id"])]
    global_path = service.root / "重复清单.json"
    original_global = global_path.read_bytes()
    global_path.unlink()
    with pytest.raises(ValueError, match="总清单缺失"):
        service.confirm(batch, selected)
    global_path.write_bytes(original_global)
    curve_manifest = service.root / "曲目" / "重复清单.json"
    curve_manifest.write_text('{"version": 2, "items": []}', encoding="utf-8")
    before = tree_hashes(service.root)
    with pytest.raises(ValueError, match="不一致"):
        service.confirm(batch, selected)
    assert tree_hashes(service.root) == before
    assert len(db.list_import_items(batch)) == 2


def test_migration_of_old_catalog_merge_preserves_both_saved_copies_and_original_records(tmp_path):
    db, batch, files = duplicate_batch(tmp_path)
    store = DuplicateLayoutStore(db, tmp_path / "out")
    package = store.root / ("曲目合并_" + "a" * 32)
    targets = []
    for channel in ("按乐器分类", "按曲目分类"):
        path = package / "002" / channel / "0101007曲目.pdf"
        path.parent.mkdir(parents=True)
        shutil.copy2(files[1], path)
        targets.append(path)
    manifest = {"duplicate_root": str(package), "title": "曲目", "category": "01", "selected_score_ids": [],
                "original_records": [{"id": 2, "source_path": str(files[1])}],
                "files": [{"score_id": 2, "instrument_code": "01", "sha256": sha256_file(files[1]),
                           "new_instrument": str(targets[0]), "new_song": str(targets[1])}]}
    (package / "合并对比记录.json").write_text(json.dumps(manifest), encoding="utf-8")
    plan = store.legacy_plan()
    assert len(plan["entries"]) == 2
    before = tree_hashes(package)
    backup = store.migrate(plan)
    assert tree_hashes(backup / package.name) == before
    assert len(list((store.root / "曲目").glob("*.pdf"))) == 2
    records = json.loads((store.root / "重复清单.json").read_text(encoding="utf-8"))["items"]
    assert {item["source_path"] for item in records} == {str(files[1])}
    assert files[0].read_bytes() == files[1].read_bytes() == b"%PDF same"


@pytest.mark.parametrize("accept", [False, True])
def test_native_legacy_migration_preview_cancel_or_confirm(tmp_path, monkeypatch, accept):
    app = QApplication.instance() or QApplication([])
    db, root, package, files = legacy_fixture(tmp_path)
    original = {path: sha256_file(path) for path in files}
    messages = []
    monkeypatch.setattr(QMessageBox, "information", lambda *args: messages.append(args[-1]))
    window = MainWindow(db)
    def inspect():
        dialog = app.activeModalWidget()
        assert dialog.windowTitle() == "预览旧重复目录整理"
        table = dialog.findChild(QTableWidget, "duplicate_layout_preview")
        assert table.rowCount() == 1 and table.item(0, 0).text() == "曲目"
        assert table.item(0, 2).text() == "01第一小提琴-曲目（重复1）.pdf"
        dialog.grab().save(str(tmp_path / "duplicate-layout-preview.png"))
        if accept:
            next(button for button in dialog.findChildren(QPushButton) if button.text() == "备份并确认整理").click()
        else:
            dialog.reject()
    QTimer.singleShot(50, inspect)
    window.menu_actions["reorganize_duplicates"].trigger()
    assert package.exists() is not accept
    assert (root / "已确认重复" / "曲目").exists() is accept
    assert bool(messages) is accept
    assert {path: sha256_file(path) for path in files} == original
    window.close()
    app.processEvents()
