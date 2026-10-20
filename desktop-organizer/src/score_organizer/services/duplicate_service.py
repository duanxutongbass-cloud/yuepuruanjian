from __future__ import annotations

import json
from pathlib import Path

from score_organizer.storage.database import Database
from score_organizer.services.review_service import ReviewService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.duplicate_layout import DuplicateLayoutStore


DUPLICATE_CODES = {"duplicate_file", "duplicate_existing_file", "same_instrument_conflict", "existing_instrument_conflict"}


class DuplicateService:
    def __init__(self, db: Database) -> None:
        self.db = db

    def list_rows(self, batch_id: int, warning_id: int | None = None) -> list[dict]:
        items_by_path = {}
        for item in self.db.list_import_items(batch_id):
            items_by_path.setdefault(str(item["source_path"]), []).append(item)
        rows = []
        warnings = self.db.list_warnings(batch_id)
        exact_catalog_paths = set()
        exact_draft_groups = []
        for warning in warnings:
            if warning["state"] != "待处理":
                continue
            paths = set(json.loads(warning["details"] or "{}").get("source_paths") or [])
            if warning["code"] == "duplicate_existing_file":
                exact_catalog_paths.update(paths)
            elif warning["code"] == "duplicate_file":
                exact_draft_groups.append(paths)
        for warning in warnings:
            if warning["code"] not in DUPLICATE_CODES or warning["state"] != "待处理":
                continue
            if warning_id is not None and int(warning["id"]) != warning_id:
                continue
            details = json.loads(warning["details"] or "{}")
            paths = set(details.get("source_paths") or [])
            if warning_id is None and paths:
                if warning["code"] == "existing_instrument_conflict" and paths.issubset(exact_catalog_paths):
                    continue
                if warning["code"] == "same_instrument_conflict" and any(paths.issubset(group) for group in exact_draft_groups):
                    continue
            issue = "内容完全相同" if warning["code"] in {"duplicate_file", "duplicate_existing_file"} else "同乐器多份，需要对比"
            seen = set()
            for path in details.get("source_paths") or []:
                for item in items_by_path.get(str(path), []):
                    if int(item["id"]) in seen:
                        continue
                    seen.add(int(item["id"]))
                    fields = json.loads(item["parsed_fields"] or "{}")
                    rows.append({"group": int(warning["id"]), "issue": issue, "location": "草稿",
                                 "title": fields.get("title") or "待命名", "instrument": fields.get("instrument_code") or "未知",
                                 "path": str(path), "name": Path(path).name,
                                 "item_id": int(item["id"]), "existing": None})
            for existing in details.get("existing") or []:
                rows.append({"group": int(warning["id"]), "issue": issue, "location": "已入库",
                             "title": existing.get("title") or existing.get("display_title") or "",
                             "instrument": existing["instrument_code"], "path": existing["source_path"],
                             "name": existing["output_name"], "item_id": None, "existing": existing})
        return rows

    def already_archived_item_ids(self, batch_id: int) -> list[int]:
        return [int(item["id"]) for item in self.db.list_import_items(batch_id)
                if item["source_sha256"] and self.db.find_scores_by_hash(str(item["source_sha256"]))]

    def confirmation_rows(self, batch_id: int, item_ids) -> list[dict]:
        selected = set(item_ids)
        rows = self.list_rows(batch_id)
        groups = {row["group"] for row in rows if row["item_id"] in selected}
        rows = [row for row in rows if row["group"] in groups]
        listed = {row["item_id"] for row in rows}
        for item in self.db.list_import_items(batch_id):
            item_id = int(item["id"])
            if item_id not in selected or item_id in listed:
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            rows.append({"group": -item_id, "issue": "人工确认，不要求名称相同", "location": "草稿",
                         "title": fields.get("title") or "待命名", "instrument": fields.get("instrument_code") or "未知",
                         "path": str(item["source_path"]), "name": Path(item["source_path"]).name,
                         "item_id": item_id, "existing": None})
        return rows

    def archived_conflict_rows(self, batch_id: int, item_ids=None) -> list[dict]:
        rows = self.list_rows(batch_id)
        archived_groups = {row["group"] for row in rows if row["existing"] is not None}
        selected_groups = {row["group"] for row in rows if row["item_id"] is not None
                           and (item_ids is None or row["item_id"] in item_ids)}
        groups = archived_groups & selected_groups
        return [row for row in rows if row["group"] in groups
                and (row["existing"] is not None or item_ids is None or row["item_id"] in item_ids)]

    def exact_redundant_item_ids(self, batch_id: int, candidate_ids) -> list[int]:
        candidates = set(candidate_ids)
        by_hash = {}
        for item in self.db.list_import_items(batch_id):
            if item["source_sha256"]:
                by_hash.setdefault(str(item["source_sha256"]), []).append(int(item["id"]))
        selected = []
        for digest, ids in by_hash.items():
            ids.sort()
            redundant = ids if self.db.find_scores_by_hash(digest) else ids[1:]
            selected.extend(item_id for item_id in redundant if item_id in candidates)
        return sorted(selected)


class ConfirmedDuplicateService(ReviewService):
    manifest_name = "重复清单.json"
    text_manifest_name = "重复清单.txt"
    explanation_name = "重复确认说明.txt"

    def __init__(self, db: Database, output_root: str | Path) -> None:
        super().__init__(db, output_root)
        self.root = Path(output_root) / "已确认重复"

    def confirm(self, batch_id: int, item_ids: list[int], *, manual: bool = False) -> tuple[Path, int]:
        selected = set(item_ids)
        if not selected:
            raise ValueError("请选择需要确认重复的草稿")
        items = self.db.list_import_items(batch_id)
        if not selected.issubset({int(item["id"]) for item in items}):
            raise ValueError("所选草稿已变化，请重新选择后确认")
        rows = (DuplicateService(self.db).confirmation_rows(batch_id, selected) if manual
                else DuplicateService(self.db).list_rows(batch_id))
        conflict_ids = {row["item_id"] for row in rows if row["item_id"] is not None}
        if not selected.issubset(conflict_ids):
            raise ValueError("重复清单已变化，请重新检查后确认")
        if not manual:
            self._validate_retained(batch_id, selected, rows)
        plan = []
        for item in items:
            item_id = int(item["id"])
            if item_id not in selected:
                continue
            source = Path(item["source_path"])
            if not source.is_file() or source.suffix.casefold() != ".pdf":
                raise ValueError(f"源PDF不存在，不能确认转存：{source}")
            groups = {row["group"] for row in rows if row["item_id"] == item_id}
            counterparts = list(dict.fromkeys(
                str(row["existing"]["output_name"]) + "；原路径：" + row["path"] if row["existing"] else row["path"]
                for row in rows if row["group"] in groups and row["item_id"] not in selected
            ))
            issues = list(dict.fromkeys(row["issue"] for row in rows if row["item_id"] == item_id))
            plan.append({"item_id": item_id, "source_path": str(source),
                         "source_sha256": str(item["source_sha256"]), "status": item["status"],
                         "parsed_fields": json.loads(item["parsed_fields"] or "{}"),
                         "reasons": ["用户已确认重复"],
                         "details": (["用户人工确认重复，不以名称、分类、乐器编号或内容指纹是否相同作为重复结论；原始PDF保留。"] if manual else [])
                                    + ["确认时的问题：" + "、".join(issues), "保留或库内对比文件：\n" + "\n".join(counterparts)],
                         "relative_path": (Path(f"分谱{item_id:06d}") / source.name).as_posix(), "restored": False})
        def validate_current():
            for item in plan:
                current = self.db.get_import_item(item["item_id"])
                if (current is None or current["batch_id"] != batch_id or current["status"] != item["status"]
                        or current["source_path"] != item["source_path"]
                        or current["source_sha256"] != item["source_sha256"]
                        or json.loads(current["parsed_fields"] or "{}") != item["parsed_fields"]):
                    raise ValueError("重复分谱记录已变化，请重新检查")
            if not manual:
                self._validate_retained(batch_id, selected, DuplicateService(self.db).list_rows(batch_id))
        return DuplicateLayoutStore(self.db, self.root.parent).store(plan, validate_current,
            lambda: self.db.remove_import_items_from_draft(batch_id, sorted(selected)))

    def _validate_retained(self, batch_id, selected, rows):
        if not selected.issubset({row["item_id"] for row in rows if row["item_id"] is not None}):
            raise ValueError("重复清单已变化，请重新检查")
        items = {int(item["id"]): item for item in self.db.list_import_items(batch_id)}
        groups = {}
        for row in rows:
            groups.setdefault(row["group"], []).append(row)
        checked_paths = {}
        def unchanged(path, digest):
            key = (str(path), digest)
            if key not in checked_paths:
                checked_paths[key] = path.is_file() and sha256_file(path) == digest
            return checked_paths[key]
        archived = self.db.list_archived_scores()
        archive = ArchiveService(self.db, self.root.parent)
        for group in groups.values():
            if not any(row["item_id"] in selected for row in group):
                continue
            retained = False
            for row in group:
                if row["item_id"] is not None:
                    if row["item_id"] not in selected:
                        item = items[row["item_id"]]
                        retained |= unchanged(Path(item["source_path"]), item["source_sha256"])
                else:
                    existing = row["existing"]
                    for score in archived:
                        if (score["seq"] == existing["seq"] and score["output_name"] == existing["output_name"]
                                and score["instrument_code"] == existing["instrument_code"]):
                            paths = archive.score_paths(score)
                            retained |= all(unchanged(path, score["source_sha256"]) for path in paths)
            if not retained:
                raise ValueError("每组至少要保留一份未转存且完整的草稿或库内分谱；不能把整组全部转存，请取消勾选要保留的一份")
