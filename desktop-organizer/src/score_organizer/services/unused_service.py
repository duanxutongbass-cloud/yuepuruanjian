from __future__ import annotations

import json
import shutil
import tempfile
from datetime import datetime
from pathlib import Path

from score_organizer.services.duplicate_layout import safe_duplicate_name
from score_organizer.services.import_service import sha256_file
from score_organizer.services.review_service import ReviewService


class UnusedScoreService(ReviewService):
    manifest_name = "暂存清单.json"
    text_manifest_name = "暂存清单.txt"

    def __init__(self, db, output_root):
        super().__init__(db, output_root)
        self.root = Path(output_root).resolve() / "暂时无用乐谱"

    def _safe_path(self, path):
        if self.root.is_symlink() or self.root.is_junction():
            raise ValueError("暂时无用乐谱目录不能是链接")
        if path.is_symlink() or path.is_junction() or not path.resolve().is_relative_to(self.root):
            raise ValueError(f"暂存目录存在不安全路径：{path}")

    def _pdf_path(self, package, item):
        relative = item.get("relative_path")
        if (not isinstance(relative, str) or Path(relative).name != relative
                or Path(relative).suffix.casefold() != ".pdf"
                or safe_duplicate_name(Path(relative).stem, limit=10000) != Path(relative).stem):
            raise ValueError("暂存清单包含无效PDF路径")
        if Path(package).resolve() != self.root:
            raise ValueError("暂存文件不属于当前乐谱库")
        path = self.root / relative
        self._safe_path(path)
        return path

    def _write_manifest(self, path, content):
        self._safe_path(path)
        super()._write_manifest(path, content)

    def _manifest(self):
        path = self.root / self.manifest_name
        self._safe_path(path)
        if not path.exists():
            if self.root.exists() and any(self.root.iterdir()):
                raise ValueError("暂存清单缺失，不能覆盖已有文件或记录")
            return {"version": 1, "items": []}
        manifest = json.loads(path.read_bytes())
        if not isinstance(manifest, dict) or manifest.get("version") != 1 or not isinstance(manifest.get("items"), list):
            raise ValueError("暂存清单格式不支持")
        names = set()
        for item in manifest["items"]:
            if not isinstance(item, dict) or not {"relative_path", "source_path", "source_sha256", "status", "parsed_fields", "reasons", "details", "restored"}.issubset(item):
                raise ValueError("暂存清单记录不完整")
            if not all(isinstance(item[key], str) for key in ("source_path", "source_sha256", "status")) or not isinstance(item["parsed_fields"], dict) or not isinstance(item["restored"], bool):
                raise ValueError("暂存清单字段无效")
            if any(not isinstance(item[key], list) or any(not isinstance(value, str) for value in item[key]) for key in ("reasons", "details")):
                raise ValueError("暂存清单说明无效")
            name = self._pdf_path(self.root, item).name.casefold()
            if name in names:
                raise ValueError("暂存清单存在重复目标路径")
            names.add(name)
        self._safe_path(self.root / self.text_manifest_name)
        return manifest

    def build_plan(self, batch_id, item_ids):
        ids = {int(value) for value in item_ids}
        if not ids:
            raise ValueError("请先选中需要暂存的草稿分谱")
        with self.db.session() as connection:
            batch = connection.execute("SELECT state FROM import_batch WHERE id = ?", (batch_id,)).fetchone()
        if batch is None or batch["state"] != "draft":
            raise ValueError("只能转存尚未归档的草稿")
        items = [row for row in self.db.list_import_items(batch_id) if int(row["id"]) in ids]
        if len(items) != len(ids):
            raise ValueError("选中的草稿已变化，请重新选择")
        manifest = self._manifest()
        occupied = {item["relative_path"].casefold() for item in manifest["items"]}
        if self.root.exists():
            occupied.update(path.name.casefold() for path in self.root.iterdir())
        plan = []
        for row in items:
            source = Path(row["source_path"])
            if not source.is_file() or source.suffix.casefold() != ".pdf" or sha256_file(source) != row["source_sha256"]:
                raise ValueError(f"源PDF缺失或变化：{source}")
            stem = safe_duplicate_name(source.stem, limit=100)
            name = f"{stem}.pdf"
            number = 2
            while name.casefold() in occupied:
                name = f"{stem}（同名{number}）.pdf"
                number += 1
            occupied.add(name.casefold())
            plan.append({"id": int(row["id"]), "source_path": str(source),
                         "source_sha256": row["source_sha256"], "status": row["status"],
                         "parsed_fields": json.loads(row["parsed_fields"] or "{}"),
                         "decision": row["decision"], "relative_path": name,
                         "reasons": ["暂时无用"], "details": ["用户选择暂时不整理，未进入正式乐谱库"],
                         "restored": False})
        return plan

    def _text_manifest(self, manifest):
        lines = ["暂时无用乐谱转存历史（当前待整理状态以软件列表为准）"]
        for item in manifest["items"]:
            lines.extend((f"原文件：{item['source_path']}", f"暂存文件：{item['relative_path']}",
                          f"指纹：{item['source_sha256']}", "原因：暂时无用", ""))
        return "\n".join(lines).encode("utf-8-sig")

    def _remove_stage(self, stage):
        if (stage.parent != self.root.parent or not stage.resolve().is_relative_to(self.root.parent)
                or not stage.name.startswith(".unused-") or stage.is_symlink() or stage.is_junction()):
            raise ValueError(f"暂存工作目录不安全，需要检查：{stage}")
        if stage.exists():
            shutil.rmtree(stage)

    def commit_plan(self, batch_id, plan):
        ids = [item["id"] for item in plan]
        if not plan or self.build_plan(batch_id, ids) != plan:
            raise ValueError("暂存预览已变化，请重新预览")
        before = self._manifest()
        self.root.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix=".unused-", dir=self.root.parent))
        created = []
        originals = {}
        written = {}
        try:
            for index, item in enumerate(plan):
                target = stage / f"{index}.pdf"
                shutil.copy2(item["source_path"], target)
                if sha256_file(target) != item["source_sha256"]:
                    raise ValueError("暂存PDF复制校验失败")
            with self.db.transaction():
                # Recheck after staging, before publishing any PDF or removing draft records.
                if self.build_plan(batch_id, ids) != plan or self._manifest() != before:
                    raise ValueError("草稿或暂存清单已变化，请重新预览")
                for index, item in enumerate(plan):
                    destination = self._pdf_path(self.root, item)
                    handle = destination.open("xb")
                    created.append((destination, None))
                    try:
                        with handle, (stage / f"{index}.pdf").open("rb") as source:
                            shutil.copyfileobj(source, handle)
                    finally:
                        self._safe_path(destination)
                        created[-1] = (destination, sha256_file(destination))
                    if sha256_file(destination) != item["source_sha256"]:
                        raise ValueError("暂存PDF保存校验失败")
                saved = {**before, "items": before["items"] + [
                    {**item, "stored_at": datetime.now().isoformat(timespec="seconds")} for item in plan]}
                outputs = {self.root / self.manifest_name: json.dumps(saved, ensure_ascii=False, indent=2).encode("utf-8"),
                           self.root / self.text_manifest_name: self._text_manifest(saved)}
                for path, content in outputs.items():
                    self._safe_path(path)
                    originals[path] = path.read_bytes() if path.exists() else None
                    written[path] = content
                    self._write_manifest(path, content)
                for item in plan:
                    if sha256_file(Path(item["source_path"])) != item["source_sha256"]:
                        raise ValueError("源PDF在保存期间变化，未移出草稿")
                self._remove_stage(stage)
                remaining = self.db.remove_import_items_from_draft(batch_id, ids)
            return self.root, remaining
        except Exception as exc:
            failures = []
            for path, content in originals.items():
                try:
                    self._safe_path(path)
                    current = path.read_bytes() if path.exists() else None
                    if current not in (content, written[path]):
                        raise ValueError("清单被外部修改")
                    if content is None:
                        path.unlink(missing_ok=True)
                    elif current != content:
                        self._write_manifest(path, content)
                except (ValueError, OSError):
                    failures.append(str(path))
            for path, digest in created:
                try:
                    self._safe_path(path)
                    if path.exists():
                        if digest is None or sha256_file(path) != digest:
                            raise ValueError("暂存副本被外部修改")
                        path.unlink()
                except (ValueError, OSError):
                    failures.append(str(path))
            if failures:
                raise ValueError("暂存失败，草稿保留；部分输出未能回退，请勿重试，需检查：" + "、".join(failures)) from exc
            raise
        finally:
            self._remove_stage(stage)

    def list_pending(self):
        return [{**item, "package": self.root, "index": index, "pdf_path": self._pdf_path(self.root, item)}
                for index, item in enumerate(self._manifest()["items"]) if not item["restored"]]

    def restore_to_draft(self, entries, batch_id=None):
        if not entries:
            raise ValueError("请先选中暂时无用乐谱")
        manifest = self._manifest()
        for entry in entries:
            index = entry.get("index")
            if not isinstance(index, int) or index < 0 or index >= len(manifest["items"]):
                raise ValueError("暂存记录已变化，请刷新列表")
            item = manifest["items"][index]
            if Path(entry["package"]).resolve() != self.root or any(entry.get(key) != item[key] for key in ("relative_path", "source_sha256", "source_path")):
                raise ValueError("暂存记录已变化，请刷新列表")
        return super().restore_to_draft(entries, batch_id)
