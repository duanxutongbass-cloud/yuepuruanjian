"""Read-only portability checks for copied review/duplicate/unused PDF packages.

Source-path strings remain provenance. Only the actual consumer's current-root
locators may select files; old clear/restore database backups are a separate gate.
"""
from __future__ import annotations

import json
from pathlib import Path
import re
import sqlite3

from score_organizer.services.duplicate_layout import DuplicateLayoutStore
from score_organizer.services.number_upgrade_service import _identity, _inventory, _read_hash, _regular
from score_organizer.services.unused_service import UnusedScoreService
from score_organizer.storage.database import Database, require_supported_database
from score_organizer.storage.number_upgrade import _no_links


class _ReadOnlySnapshot(Database):
    def connect(self):
        db = sqlite3.connect(self.path.absolute().as_uri() + "?mode=ro", uri=True)
        try:
            require_supported_database(db)
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("PRAGMA query_only=ON")
            return db
        except BaseException:
            db.close()
            raise


def _unique_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError("历史清单含重复字段，不能猜测生效值")
        value[key] = item
    return value


def verify_pending_manifests(output_root: Path, database_snapshot: Path) -> dict:
    """Validate every stored PDF in supported live pending areas; never rewrite.

    The database argument is the immutable format-0 snapshot, not an active or
    format-1 candidate. No original external PDF is opened for provenance text.
    """
    root = Path(output_root).absolute()
    _no_links(root)
    _no_links(Path(database_snapshot).absolute())
    initial = _inventory(root)
    checked, packages = {}, []
    counts = dict(reviewItems=0, legacyDuplicateItems=0, duplicateLayoutItems=0, unusedItems=0)

    def record(path):
        relative = path.relative_to(root).as_posix()
        info = _regular(path)
        if info.st_nlink != 1:
            raise ValueError("历史清单副本不能共享文件实体")
        proof = _read_hash(path, _identity(info))
        if relative in checked and checked[relative] != proof:
            raise ValueError("核验期间历史文件发生变化")
        checked[relative] = proof
        return proof

    def read_manifest(path):
        proof = record(path)
        content = path.read_text(encoding="utf-8")
        if record(path) != proof:
            raise ValueError("读取期间历史清单发生变化")
        value = json.loads(content, object_pairs_hook=_unique_object)
        if not isinstance(value, dict) or not isinstance(value.get("items"), list):
            raise ValueError("历史清单结构不受支持，不能丢弃记录")
        return value

    def target(base, relative):
        if not isinstance(relative, str) or not relative:
            raise ValueError("历史清单缺少活动相对路径")
        name = Path(relative)
        if name.is_absolute() or name.drive or ".." in name.parts or name.suffix.lower() != ".pdf":
            raise ValueError("历史清单活动路径必须是本目录内的相对PDF路径")
        path = base / name
        _no_links(path)
        if not path.resolve().is_relative_to(base.resolve()):
            raise ValueError("历史清单活动路径越界")
        return path

    def pdf(path, digest):
        if not isinstance(digest, str) or not re.fullmatch("[0-9a-f]{64}", digest):
            raise ValueError("历史PDF缺少可核验哈希")
        if record(path)["sha256"] != digest:
            raise ValueError("历史PDF缺失或内容已变化")
        return path.relative_to(root).as_posix()

    def legacy_packages(folder, manifest_name, kind, *, allow_v2=False):
        registered = set()
        if not folder.exists():
            return registered
        for package in sorted(folder.iterdir()):
            if not package.is_dir() or package.name.startswith(".") or not (package / manifest_name).exists():
                continue
            path = package / manifest_name
            value = read_manifest(path)
            version = value.get("version")
            if type(version) is not int or version not in ((1, 2) if allow_v2 else (1,)):
                raise ValueError("历史清单版本不受支持")
            if version == 2:
                continue  # Checked against the global v2 manifest below.
            seen = set()
            for item in value["items"]:
                required = {"relative_path", "source_path", "source_sha256", "status", "parsed_fields", "reasons", "details"}
                if not isinstance(item, dict) or not required.issubset(item):
                    raise ValueError("历史分谱记录不完整")
                if not isinstance(item["source_path"], str) or not isinstance(item["status"], str) or not isinstance(item["parsed_fields"], dict):
                    raise ValueError("历史分谱字段无效")
                if "restored" in item and type(item["restored"]) is not bool:
                    raise ValueError("历史恢复状态无效")
                if any(not isinstance(item[key], list) or any(not isinstance(text, str) for text in item[key]) for key in ("reasons", "details")):
                    raise ValueError("历史来源说明无效")
                name = pdf(target(package, item["relative_path"]), item["source_sha256"])
                if name.casefold() in seen:
                    raise ValueError("历史清单重复引用同一活动PDF，不能合并恢复记录")
                seen.add(name.casefold())
                registered.add(name)
            counts[kind] += len(value["items"])
            packages.append(dict(kind=kind, manifest=path.relative_to(root).as_posix(), items=len(value["items"])))
        return registered

    def require_all_pdfs(folder, registered):
        actual = {path.relative_to(root).as_posix() for path in folder.rglob("*") if path.is_file() and path.suffix.lower() == ".pdf"} if folder.exists() else set()
        if actual != registered:
            raise ValueError("历史目录存在未登记或缺失的PDF，不能跳过迁移")

    review_root = root / "待审查"
    review_files = legacy_packages(review_root, "审查清单.json", "reviewItems")
    require_all_pdfs(review_root, review_files)

    duplicates_root = root / "已确认重复"
    duplicate_files = legacy_packages(duplicates_root, "重复清单.json", "legacyDuplicateItems", allow_v2=True)
    store = DuplicateLayoutStore(_ReadOnlySnapshot(database_snapshot), root)
    global_manifest = duplicates_root / "重复清单.json"
    if global_manifest.exists():
        value = read_manifest(global_manifest)
        if type(value.get("version")) is not int or value["version"] != 2 or value != store._manifest():
            raise ValueError("重复总清单格式不匹配")
        seen, by_folder = set(), {}
        for item in value["items"]:
            path = target(duplicates_root, item["relative_path"])
            name = pdf(path, item["source_sha256"])
            if name.casefold() in seen:
                raise ValueError("重复总清单重复引用同一PDF")
            seen.add(name.casefold())
            duplicate_files.add(name)
            by_folder.setdefault(Path(item["relative_path"]).parts[0], []).append(item)
        for folder, items in by_folder.items():
            path = duplicates_root / folder / "重复清单.json"
            if read_manifest(path) != {"version": 2, "items": items}:
                raise ValueError("重复总清单与曲目清单不一致")
        counts["duplicateLayoutItems"] = len(value["items"])
        packages.append(dict(kind="duplicateLayoutItems", manifest=global_manifest.relative_to(root).as_posix(), items=len(value["items"])))
    else:
        store._manifest()  # Detect a missing v2 global manifest, even with no pending rows.
    plan = store.legacy_plan()
    for entry in plan["entries"]:
        duplicate_files.add(pdf(Path(entry["copy_source"]), entry["source_sha256"]))
    for package in plan["packages"]:
        path = Path(package) / "合并对比记录.json"
        if path.exists():
            record(path)
            json.loads(path.read_text("utf-8"), object_pairs_hook=_unique_object)
    current_merges = store.current_merge_audits()
    for relative, proof in current_merges['checkedFiles'].items():
        if record(duplicates_root / relative) != proof:
            raise ValueError('合并记录核验后发生变化')
    for entry in current_merges['entries']:
        name = pdf(Path(entry['copy_source']), entry['source_sha256'])
        if name in duplicate_files:
            raise ValueError('合并记录与重复清单重复登记同一PDF')
        duplicate_files.add(name)
    counts['mergeAuditItems'] = len(current_merges['entries'])
    counts['mergeAuditRecords'] = len(current_merges['records'])
    require_all_pdfs(duplicates_root, duplicate_files)

    unused_root = root / "暂时无用乐谱"
    unused_files = set()
    if unused_root.exists():
        service = UnusedScoreService(None, root)
        path = unused_root / service.manifest_name
        value = read_manifest(path) if path.exists() else service._manifest()
        if type(value.get("version")) is not int or value["version"] != 1 or value != service._manifest():
            raise ValueError("暂存清单格式不匹配")
        for item in value["items"]:
            unused_files.add(pdf(service._pdf_path(unused_root, item), item["source_sha256"]))
        counts["unusedItems"] = len(value["items"])
        if path.exists():
            packages.append(dict(kind="unusedItems", manifest=path.relative_to(root).as_posix(), items=len(value["items"])))
    require_all_pdfs(unused_root, unused_files)

    for relative, expected in checked.items():
        if record(root / relative) != expected:
            raise ValueError("复核时历史清单或PDF已变化")
    if _inventory(root) != initial:
        raise ValueError("核验期间历史目录结构或文件实体已变化")
    return dict(**counts, legacyDuplicatePlanPackages=len(plan["packages"]),
                pdfsVerified=len(review_files | duplicate_files | unused_files), packages=packages,
                checkedFiles=checked, provenanceTextRewritten=False,
                externalProvenanceFilesOpened=False, clearBackupCompatibilityVerified=False)
