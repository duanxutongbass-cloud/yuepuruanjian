from __future__ import annotations

import json
import re
import shutil
import sqlite3
import tempfile
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from score_organizer.services.import_service import sha256_file
from score_organizer.services.review_service import ReviewService


def safe_duplicate_name(value, limit=90):
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", str(value)).strip().rstrip(". ") or "未命名乐谱"
    while len(value.encode("utf-16-le")) // 2 > limit:
        value = value[:-1]
    if re.fullmatch(r"(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\..*)?", value):
        value += "_乐谱"
    return value


class DuplicateLayoutStore:
    def __init__(self, db, output_root):
        self.db = db
        self.output_root = Path(output_root).resolve()
        self.root = self.output_root / "已确认重复"
        self.writer = ReviewService(db, output_root)

    def _safe_path(self, path):
        if not path.resolve().is_relative_to(self.root.resolve()) or path.is_symlink() or path.is_junction():
            raise ValueError(f"重复目录存在不安全路径，不能操作：{path}")
        if self.root.is_symlink() or self.root.is_junction():
            raise ValueError("重复目录不能是链接")

    def _manifest(self):
        path = self.root / "重复清单.json"
        self._safe_path(path)
        if not path.exists():
            if self.root.exists():
                for folder in self.root.iterdir():
                    manifest = folder / "重复清单.json"
                    if folder.is_dir() and manifest.is_file():
                        self._safe_path(manifest)
                        if json.loads(manifest.read_text(encoding="utf-8")).get("version") == 2:
                            raise ValueError("重复总清单缺失，不能覆盖已有曲目确认记录")
            return {"version": 2, "items": []}
        content = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(content, dict) or content.get("version") != 2 or not isinstance(content.get("items"), list):
            raise ValueError("重复总清单格式不支持，不能覆盖")
        for item in content["items"]:
            if not isinstance(item, dict) or not {"relative_path", "display_title", "source_path", "source_sha256", "reasons", "details"}.issubset(item):
                raise ValueError("重复总清单记录不完整，不能覆盖")
            if any(not isinstance(item[key], str) for key in ("relative_path", "display_title", "source_path", "source_sha256")) or any(
                not isinstance(item[key], list) or any(not isinstance(value, str) for value in item[key]) for key in ("reasons", "details")):
                raise ValueError("重复总清单字段格式不支持，不能覆盖")
            target = self.root / item["relative_path"]
            self._safe_path(target)
            if len(Path(item["relative_path"]).parts) != 2 or target.suffix.casefold() != ".pdf":
                raise ValueError("重复清单包含无效PDF路径")
        return content

    def allocate(self, entries):
        existing = self._manifest()["items"]
        folders = {item["display_title"]: Path(item["relative_path"]).parts[0] for item in existing}
        occupied = {path.name.casefold(): path.name for path in self.root.iterdir()} if self.root.exists() else {}
        used_folders = {name.casefold(): title for title, name in folders.items()}
        with self.db.session() as connection:
            instruments = {row["code"]: row["name"] for row in connection.execute("SELECT code, name FROM instrument")}
        counters = {}
        allocated = []
        for original in entries:
            item = dict(original)
            fields = item.get("parsed_fields") or {}
            if not isinstance(fields, dict):
                raise ValueError("重复分谱校对字段无效，不能猜测命名")
            matches = self.db.find_scores_by_hash(item["source_sha256"])
            titles = {str(score["display_title"]) for score in matches}
            codes = {str(score["instrument_code"]) for score in matches}
            title = item.get("display_title") or (next(iter(titles)) if len(titles) == 1 else fields.get("title")) or Path(item["source_path"]).stem
            code = item.get("display_instrument_code") or (next(iter(codes)) if len(codes) == 1 else fields.get("instrument_code")) or ""
            folder = folders.get(title)
            if folder is None:
                base = safe_duplicate_name(title)
                folder = occupied.get(base.casefold(), base)
                index = 2
                while (folder.casefold() in used_folders and used_folders[folder.casefold()] != title) or (
                    (self.root / folder).exists() and not (self.root / folder).is_dir()):
                    folder = f"{base}（同名{index}）"
                    index += 1
                # Never mix a legacy package into a curve folder.
                legacy = self.root / folder / "重复清单.json"
                if legacy.is_file() and json.loads(legacy.read_text(encoding="utf-8")).get("version") == 1:
                    raise ValueError("目标目录是旧批次，请先整理旧重复目录")
                folders[title] = folder
                used_folders[folder.casefold()] = title
            parent = self.root / folder
            self._safe_path(parent)
            if folder not in counters:
                names = [path.name for path in parent.iterdir()] if parent.exists() else []
                names.extend(Path(item["relative_path"]).name for item in existing if Path(item["relative_path"]).parts[0] == folder)
                numbers = [int(match.group(1)) for name in names if (match := re.search(r"（重复(\d+)）\.pdf$", name, re.IGNORECASE))]
                counters[folder] = max(numbers, default=0)
            label = f"{code}{instruments.get(code, '未标明乐器')}-{title}"
            counters[folder] += 1
            name = f"{safe_duplicate_name(label, 140)}（重复{counters[folder]}）.pdf"
            item.update(display_title=title, display_instrument_code=code, relative_path=(Path(folder) / name).as_posix())
            self._safe_path(self.root / item["relative_path"])
            allocated.append(item)
        return allocated

    def store(self, entries, validate, commit):
        plan = self.allocate(entries)
        old_manifest = self._manifest()
        self.root.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix=".duplicate-", dir=self.root))
        created = []
        directories = []
        originals = {}
        try:
            for index, item in enumerate(plan):
                target = stage / f"{index}.pdf"
                shutil.copy2(item.get("copy_source") or item["source_path"], target)
                if sha256_file(target) != item["source_sha256"]:
                    raise ValueError("源PDF已变化，重复转存取消")
            with self.db.transaction():
                validate()
                if self._manifest() != old_manifest or self.allocate(entries) != plan:
                    raise ValueError("重复目录已变化，请重新预览")
                for folder in {Path(item["relative_path"]).parts[0] for item in plan}:
                    metadata_path = self.root / folder / "重复清单.json"
                    if metadata_path.exists():
                        self._safe_path(metadata_path)
                        expected = {"version": 2, "items": [item for item in old_manifest["items"] if Path(item["relative_path"]).parts[0] == folder]}
                        if json.loads(metadata_path.read_text(encoding="utf-8")) != expected:
                            raise ValueError("曲目重复清单与总清单不一致，不能覆盖确认记录")
                for index, item in enumerate(plan):
                    target = self.root / item["relative_path"]
                    self._safe_path(target.parent)
                    if not target.parent.exists():
                        target.parent.mkdir()
                        directories.append(target.parent)
                    with target.open("xb") as destination:
                        created.append(target)
                        with (stage / f"{index}.pdf").open("rb") as source:
                            shutil.copyfileobj(source, destination)
                    if sha256_file(target) != item["source_sha256"]:
                        raise ValueError("重复PDF复制校验失败")
                records = old_manifest["items"] + [{key: value for key, value in item.items() if key != "copy_source"} for item in plan]
                metadata = {self.root / "重复清单.json": {"version": 2, "items": records}}
                for folder in {Path(item["relative_path"]).parts[0] for item in plan}:
                    metadata[self.root / folder / "重复清单.json"] = {
                        "version": 2, "items": [item for item in records if Path(item["relative_path"]).parts[0] == folder]}
                for path, manifest in metadata.items():
                    texts = []
                    for item in manifest["items"]:
                        texts.append(f"文件：{item['relative_path']}\n曲名：{item['display_title']}\n乐器：{item['display_instrument_code']}\n分类：{(item.get('parsed_fields') or {}).get('category_code') or '未确定'}\n原始文件名：{Path(item['source_path']).name}\n原因：{'、'.join(item['reasons'])}\n原始路径：{item['source_path']}\n" + "\n".join(item["details"]))
                    contents = {path: json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"),
                                path.with_suffix(".txt"): "\n\n".join(texts).encode("utf-8-sig")}
                    if path.parent != self.root:
                        contents[path.parent / "重复确认说明.txt"] = contents[path.with_suffix(".txt")]
                    for target, content in contents.items():
                        self._safe_path(target)
                        originals[target] = target.read_bytes() if target.exists() else None
                        self.writer._write_manifest(target, content)
                result = commit()
            return self.root, result
        except Exception as exc:
            failures = []
            for path, content in reversed(list(originals.items())):
                try:
                    if content is None:
                        path.unlink(missing_ok=True)
                    elif not path.exists() or path.read_bytes() != content:
                        self.writer._write_manifest(path, content)
                except OSError:
                    failures.append(str(path))
            for path in reversed(created):
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    failures.append(str(path))
            for directory in reversed(directories):
                try:
                    directory.rmdir()
                except OSError:
                    failures.append(str(directory))
            if failures:
                raise ValueError("重复转存失败且回退未完成，请停止整理：\n" + "\n".join(failures)) from exc
            raise
        finally:
            self._safe_path(stage)
            shutil.rmtree(stage, ignore_errors=True)

    def current_merge_audits(self):
        """Read current merge duplicates without moving or rewriting packages."""
        from score_organizer.services.merge_audit import read_current_merge_audits
        return read_current_merge_audits(self.root)

    def legacy_plan(self):
        entries = []
        packages = {}
        if not self.root.exists():
            return {"entries": [], "packages": {}}
        for package in sorted(self.root.iterdir()):
            if not package.is_dir() or not re.fullmatch(r"\d{8}_\d{6}_[0-9a-f]{8}|曲目合并_[0-9a-f]{32}", package.name):
                continue
            self._safe_path(package)
            path = package / "重复清单.json"
            merge_path = package / "合并对比记录.json"
            source_entries = []
            if path.is_file():
                manifest = json.loads(path.read_text(encoding="utf-8"))
                if manifest.get("version") == 2:
                    continue
                if manifest.get("version") != 1:
                    raise ValueError(f"旧重复清单格式不支持：{package}")
                source_entries = [dict(item, copy_source=str(package / item["relative_path"])) for item in manifest["items"]]
            elif merge_path.is_file():
                manifest = json.loads(merge_path.read_text(encoding="utf-8"))
                original_records = {item["id"]: item for item in manifest.get("original_records", [])}
                for file in manifest["files"]:
                    if file["score_id"] in manifest["selected_score_ids"]:
                        continue
                    for key in ("new_instrument", "new_song"):
                        if not file.get(key):
                            continue
                        relative = Path(file[key]).relative_to(Path(manifest["duplicate_root"]))
                        source_entries.append({"source_path": original_records.get(file["score_id"], {}).get("source_path", file[key]), "copy_source": str(package / relative),
                            "source_sha256": file["sha256"], "display_title": manifest["title"],
                            "parsed_fields": {"title": manifest["title"], "instrument_code": file["instrument_code"], "category_code": manifest["category"]},
                            "reasons": ["用户已确认重复"], "details": ["人工确认误拆合并；原始对比记录随旧目录保存在整理备份中。"]})
            else:
                raise ValueError(f"旧重复目录缺少清单，不能猜测移动：{package}")
            expected = set()
            for item in source_entries:
                source = Path(item["copy_source"])
                self._safe_path(source)
                if not source.resolve().is_relative_to(package.resolve()) or not source.is_file() or sha256_file(source) != item["source_sha256"]:
                    raise ValueError(f"旧重复PDF缺失或变化：{source}")
                item["legacy_package"] = package.name
                expected.add(source.resolve())
            if expected != {path.resolve() for path in package.rglob("*.pdf")}:
                raise ValueError(f"旧重复目录有未登记PDF，不能移动：{package}")
            snapshot = {}
            for path in package.rglob("*"):
                self._safe_path(path)
                if path.is_file():
                    snapshot[path.relative_to(package).as_posix()] = sha256_file(path)
            packages[str(package)] = snapshot
            entries.extend(source_entries)
        with self.db.session() as connection:
            referenced = [row[0] for row in connection.execute("SELECT source_path FROM import_item UNION SELECT source_path FROM score")]
        for source in referenced:
            path = Path(source)
            if path.exists() and any(path.resolve().is_relative_to(Path(package).resolve()) for package in packages):
                raise ValueError("旧重复目录中有正在被草稿或正式库引用的源PDF，不能移动")
        return {"entries": self.allocate(entries), "packages": packages}

    def migrate(self, plan):
        if not plan["entries"] or self.legacy_plan() != plan:
            raise ValueError("旧重复目录已变化或没有文件，请重新预览")
        backup = self.output_root / "修改备份" / f"重复目录整理_{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}"
        if not backup.resolve().is_relative_to(self.output_root) or backup.parent.is_symlink() or backup.parent.is_junction():
            raise ValueError("修改备份目录存在不安全链接，不能整理")
        backup.mkdir(parents=True)
        with sqlite3.connect(self.db.path) as source, sqlite3.connect(backup / "整理前数据库.sqlite3") as destination:
            source.backup(destination)
        (backup / "整理计划.json").write_text(json.dumps(plan, ensure_ascii=False, indent=2), encoding="utf-8")
        moved = []
        def validate():
            if self.legacy_plan() != plan:
                raise ValueError("预览后旧重复目录已变化，已取消整理")
        def commit():
            if self.legacy_plan()["packages"] != plan["packages"]:
                raise ValueError("旧目录在复制后发生变化，已停止整理")
            for source in plan["packages"]:
                old = Path(source)
                if not old.resolve().is_relative_to(self.root.resolve()):
                    raise ValueError("旧重复目录越界，不能移动")
                target = backup / old.name
                if target.exists():
                    raise ValueError("整理备份目标已存在，不能覆盖")
                old.rename(target)
                moved.append((old, target))
            return len(plan["entries"])
        try:
            entries = [dict(item, legacy_backup=str(backup / item["legacy_package"])) for item in plan["entries"]]
            self.store(entries, validate, commit)
        except Exception as exc:
            failures = []
            for old, target in reversed(moved):
                try:
                    if old.exists():
                        raise OSError("原位置已有文件，不能覆盖")
                    target.rename(old)
                except OSError:
                    failures.append(str(target))
            if failures:
                raise ValueError(f"旧重复目录恢复未完成，请停止整理；备份：{backup}\n" + "\n".join(failures)) from exc
            raise
        return backup
