from __future__ import annotations

import json
import shutil
import tempfile
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from score_organizer.services.grouping_service import GroupingService
from score_organizer.services.import_service import sha256_file
from score_organizer.storage.database import Database


REASON_LABELS = {
    "duplicate_file": "重复",
    "duplicate_existing_file": "重复",
    "same_instrument_conflict": "同乐器多份分谱",
    "existing_instrument_conflict": "同乐器多份分谱",
    "insufficient_instruments": "乐器不足",
    "similar_title_candidate": "疑似同曲",
    "similar_existing_title_candidate": "库内相似曲名待确认归属",
    "instrument_title_suffix": "乐器后缀待确认",
    "version_candidate": "版本待确认",
}


class ReviewService:
    manifest_name = "审查清单.json"
    text_manifest_name = "审查清单.txt"
    explanation_name = "审查说明.txt"

    def __init__(self, db: Database, output_root: str | Path) -> None:
        self.db = db
        self.root = Path(output_root) / "待审查"

    def build_plan(self, batch_id: int, item_ids=None) -> list[dict]:
        GroupingService(self.db).refresh_warnings(batch_id, scan_completeness=True)
        items = self.db.list_import_items(batch_id)
        selected = None if item_ids is None else set(item_ids)
        if selected is not None and (not selected or not selected.issubset({int(item["id"]) for item in items})):
            raise ValueError("请重新选择当前草稿中的待审查分谱")
        warnings = self.db.list_warnings(batch_id)
        reasons = {int(item["id"]): [] for item in items}
        details = {int(item["id"]): [] for item in items}
        owners = {}
        with self.db.session() as connection:
            for row in connection.execute("SELECT song.seq, category.code, song.display_title FROM song JOIN category ON category.id = song.category_id WHERE seq IS NOT NULL"):
                owners[str(row["seq"])] = (str(row["code"]), str(row["display_title"]))
        groups = {}
        fields_by_id = {int(item["id"]): json.loads(item["parsed_fields"] or "{}") for item in items}
        seq_keys = {}
        group_seqs = {}
        title_categories = {}
        for item in items:
            if item["status"] != "recognized":
                continue
            fields = fields_by_id[int(item["id"])]
            key = (str(fields.get("category_code")), str(fields.get("title")))
            title_categories.setdefault(key[1], set()).add(key[0])
            if fields.get("seq"):
                seq = str(fields["seq"])
                seq_keys.setdefault(seq, set()).add(key)
                group_seqs.setdefault(key, set()).add(seq)
        for item in items:
            item_id = int(item["id"])
            fields = json.loads(item["parsed_fields"] or "{}")
            if item["status"] == "skipped":
                continue
            if item["status"] != "recognized":
                reasons[item_id].append("需要手动命名")
                details[item_id].append(str(fields.get("reason") or "乐器、分类或曲名不明确"))
            else:
                key = (str(fields.get("category_code")), str(fields.get("title")))
                groups.setdefault(key, []).append(item_id)
                if len(title_categories[key[1]]) > 1:
                    reasons[item_id].append("分类冲突")
                    details[item_id].append("同名曲目出现在多个分类，请人工核对")
                seq = fields.get("seq")
                if seq:
                    seq = str(seq)
                    existing = self.db.find_archived_song(*key)
                    if (seq in owners and owners[seq] != key) or (existing and existing["seq"] != seq) or len(seq_keys[seq]) > 1 or len(group_seqs[key]) > 1:
                        reasons[item_id].append("编号冲突")
                        details[item_id].append(f"已有曲目序号 {seq} 冲突，需要人工核对")
            for warning in warnings:
                if warning["state"] != "待处理":
                    continue
                info = json.loads(warning["details"] or "{}")
                if str(item["source_path"]) in (info.get("source_paths") or []):
                    reason = REASON_LABELS.get(warning["code"], "其他预警")
                    if reason not in reasons[item_id]:
                        reasons[item_id].append(reason)
                        explanation = reason
                        if "instrument_count" in info:
                            explanation += f"：目前仅有 {info['instrument_count']} 种不同乐器"
                        if info.get("instrument_code"):
                            explanation += f"；乐器编号 {info['instrument_code']}"
                        if info.get("left_title"):
                            explanation += f"：{info['left_title']} / {info['right_title']}"
                        if info.get("source_paths"):
                            explanation += "\n关联源文件：\n" + "\n".join(info["source_paths"])
                        for archived in info.get("existing") or []:
                            explanation += "\n已入库文件：" + str(archived.get("output_name", ""))
                            explanation += "\n已入库源路径：" + str(archived.get("source_path", ""))
                        details[item_id].append(explanation)
        for key, item_ids in groups.items():
            codes = {str(fields_by_id[item_id].get("instrument_code")) for item_id in item_ids}
            existing = self.db.find_archived_song(*key)
            eligible = self.db.archive_eligible_codes(key[0], codes, int(existing["id"]) if existing else None)
            if codes - eligible:
                for item_id in item_ids:
                    if fields_by_id[item_id].get("instrument_code") in eligible:
                        continue
                    if "乐器不足" not in reasons[item_id]:
                        reasons[item_id].append("乐器不足")
                        details[item_id].append(f"普通分谱尚未达到分类要求的 {self.db.minimum_instruments(key[0])} 种不同乐器")
            group_reasons = list(dict.fromkeys(reason for item_id in item_ids for reason in reasons[item_id]))
            if group_reasons:
                for item_id in item_ids:
                    if not reasons[item_id] and self.db.instrument_archive_modes().get(fields_by_id[item_id].get("instrument_code")) != "instrument_only":
                        reasons[item_id] = ["关联曲目待审查"]
                        details[item_id] = ["同曲其他分谱存在问题：" + "、".join(group_reasons)]
        plan = []
        for item in items:
            item_id = int(item["id"])
            if selected is not None and item_id not in selected:
                continue
            if selected is not None and not reasons[item_id]:
                reasons[item_id] = ["人工指定待审查"]
                details[item_id] = ["用户明确选择暂存审查，未正式入库"]
            if not reasons[item_id]:
                continue
            source = Path(item["source_path"])
            if not source.is_file() or source.suffix.casefold() != ".pdf":
                raise ValueError(f"找不到源 PDF，不能转存：{source}")
            # Keep the original filename even when different folders share a name.
            relative = Path(reasons[item_id][0]) / f"分谱{item_id:06d}" / source.name
            plan.append({
                "item_id": item_id, "source_path": str(source),
                "source_sha256": str(item["source_sha256"]),
                "status": item["status"], "parsed_fields": json.loads(item["parsed_fields"] or "{}"),
                "reasons": reasons[item_id], "details": details[item_id],
                "relative_path": relative.as_posix(), "restored": False,
            })
        return plan

    def commit_plan(self, batch_id: int, plan: list[dict], item_ids=None) -> tuple[Path, int]:
        if not plan:
            raise ValueError("当前没有需要转存到待审查的 PDF")
        scope = None if item_ids is None else set(item_ids)
        def validate():
            if plan != self.build_plan(batch_id, scope):
                raise ValueError("草稿或预警已变化，请重新预览待审查清单")
        validate()
        return self._store_plan(batch_id, plan, validate_before_commit=validate)

    def _store_plan(self, batch_id: int, plan: list[dict], validate_before_commit=None) -> tuple[Path, int]:
        self.root.mkdir(parents=True, exist_ok=True)
        stage = Path(tempfile.mkdtemp(prefix=".review-", dir=self.root))
        destination = self.root / f"{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}"
        published = False
        try:
            for item in plan:
                target = stage / item["relative_path"]
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(item["source_path"], target)
                if sha256_file(target) != item["source_sha256"]:
                    raise ValueError("源 PDF 已变化，转存取消；请重新导入该文件")
                explanation = "文件：" + Path(item["source_path"]).name + "\n原因：" + "、".join(item["reasons"])
                explanation += "\n原始路径：" + item["source_path"] + "\n\n" + "\n".join(item["details"])
                (target.parent / self.explanation_name).write_text(explanation, encoding="utf-8-sig")
            manifest = {"version": 1, "items": plan}
            (stage / self.manifest_name).write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
            (stage / self.text_manifest_name).write_text("\n".join(
                f"{item['relative_path']}\n原因：{'、'.join(item['reasons'])}\n原始路径：{item['source_path']}\n"
                for item in plan), encoding="utf-8-sig")
            with self.db.transaction():
                if validate_before_commit is not None:
                    validate_before_commit()
                stage.rename(destination)
                published = True
                remaining = self.db.remove_import_items_from_draft(batch_id, [item["item_id"] for item in plan])
            return destination, remaining
        except Exception:
            if published:
                shutil.rmtree(destination)
            raise
        finally:
            if stage.exists():
                shutil.rmtree(stage)

    def list_pending(self) -> list[dict]:
        pending = []
        if not self.root.is_dir():
            return pending
        for package in sorted(self.root.iterdir()):
            if not package.is_dir() or package.name.startswith("."):
                continue
            manifest_path = package / self.manifest_name
            if not manifest_path.is_file():
                continue
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            if manifest.get("version") != 1:
                raise ValueError(f"待审查清单版本不支持：{package}")
            for index, item in enumerate(manifest["items"]):
                if not item.get("restored"):
                    pending.append({**item, "package": package, "index": index,
                                    "pdf_path": self._pdf_path(package, item)})
        return pending

    def _pdf_path(self, package: Path, item: dict) -> Path:
        path = (package / item["relative_path"]).resolve()
        if not path.is_relative_to(package.resolve()) or path.suffix.casefold() != ".pdf":
            raise ValueError("待审查清单包含无效文件路径")
        return path

    def _write_manifest(self, path: Path, content: bytes) -> None:
        with tempfile.NamedTemporaryFile(prefix=".manifest-", dir=path.parent, delete=False) as temporary:
            temporary.write(content)
            temporary_path = Path(temporary.name)
        try:
            temporary_path.replace(path)
        finally:
            temporary_path.unlink(missing_ok=True)

    def restore_to_draft(self, entries: list[dict], batch_id: int | None = None) -> int:
        if not entries:
            raise ValueError("请先选择待审查文件")
        manifests = {}
        originals = {}
        try:
            with self.db.transaction():
                if batch_id is None:
                    batch_id = self.db.latest_draft_batch_id() or self.db.create_import_batch()
                with self.db.session() as connection:
                    batch = connection.execute("SELECT state FROM import_batch WHERE id = ?", (batch_id,)).fetchone()
                    if batch is None or batch["state"] != "draft":
                        raise ValueError("只能放回尚未归档的草稿")
                for entry in entries:
                    package = Path(entry["package"]).resolve()
                    if not package.is_relative_to(self.root.resolve()):
                        raise ValueError("待审查文件不属于当前乐谱库")
                    manifest_path = package / self.manifest_name
                    if manifest_path not in manifests:
                        originals[manifest_path] = manifest_path.read_bytes()
                        manifests[manifest_path] = json.loads(originals[manifest_path])
                    item = manifests[manifest_path]["items"][entry["index"]]
                    if item.get("restored"):
                        continue
                    path = self._pdf_path(package, item)
                    if not path.is_file() or sha256_file(path) != item["source_sha256"]:
                        raise ValueError(f"待审查 PDF 缺失或变化：{path}")
                    self.db.add_import_item(batch_id, str(path), item["source_sha256"], item["status"], item["parsed_fields"])
                    item["restored"] = True
                for path, manifest in manifests.items():
                    self._write_manifest(path, json.dumps(manifest, ensure_ascii=False, indent=2).encode("utf-8"))
            return batch_id
        except Exception:
            for path, content in originals.items():
                if path.read_bytes() != content:
                    self._write_manifest(path, content)
            raise
