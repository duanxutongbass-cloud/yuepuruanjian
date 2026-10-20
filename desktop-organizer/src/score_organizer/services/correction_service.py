from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path

from score_organizer.services.import_service import sha256_file
from score_organizer.storage.database import Database
from score_organizer.services.grouping_service import GroupingService
from rapidfuzz import fuzz


class RelatedDraftPartsError(ValueError):
    def __init__(self, item_ids):
        super().__init__("草稿还有未选中的同名或仅空格不同的分谱，请一起选中或填写可区分的曲名")
        self.item_ids = tuple(sorted(item_ids))


@dataclass(frozen=True)
class CorrectionRow:
    item_id: int
    source_path: str
    source_sha256: str
    old_fields: str
    new_fields: str


@dataclass(frozen=True)
class CorrectionPlan:
    batch_id: int
    target_song_id: int | None
    title: str | None
    category: str | None
    instruments: tuple[tuple[int, str | None], ...]
    rows: tuple[CorrectionRow, ...]
    target_draft_item_id: int | None = None
    target_draft_snapshot: str | None = None
    new_song: bool = False


class CorrectionService:
    def __init__(self, db: Database):
        self.db = db

    def build_plan(self, batch_id, item_ids, title=None, category=None, instruments=None, target_song_id=None, target_draft_item_id=None, new_song=False):
        selected = set(item_ids)
        if not selected:
            raise ValueError("请先选择草稿分谱或一条有关联分谱的预警")
        with self.db.session() as connection:
            batch = connection.execute("SELECT state FROM import_batch WHERE id=?", (batch_id,)).fetchone()
        items = {int(row["id"]): row for row in self.db.list_import_items(batch_id)}
        if batch is None or batch["state"] != "draft" or not selected.issubset(items):
            raise ValueError("草稿已变化或已经归档，请重新选择")
        if new_song and (target_song_id is not None or target_draft_item_id is not None):
            raise ValueError("新建曲目不能同时归入已有曲目")
        draft_fields = None
        draft_snapshot = None
        if target_draft_item_id is not None:
            if target_song_id is not None or target_draft_item_id in selected:
                raise ValueError("目标草稿不能同时作为本次修改的分谱，请分别处理")
            candidate = items.get(int(target_draft_item_id))
            if candidate is None or candidate["status"] != "recognized":
                raise ValueError("目标草稿不存在或尚未完成识别，请重新选择")
            candidate_path = Path(candidate["source_path"])
            if candidate_path.suffix.casefold() != ".pdf" or not candidate_path.is_file() or sha256_file(candidate_path) != candidate["source_sha256"]:
                raise ValueError("目标草稿PDF缺失或变化，请重新选择")
            draft_snapshot = json.dumps(dict(candidate), ensure_ascii=False, sort_keys=True)
            draft_fields = json.loads(candidate["parsed_fields"] or "{}")
            title, category = draft_fields.get("title"), draft_fields.get("category_code")
            if not title or not category:
                raise ValueError("目标草稿缺少曲名或分类，请先校对目标")
            target_song_id = draft_fields.get("target_song_id")
        target = self.db.get_archived_song(target_song_id) if target_song_id is not None else None
        if target_song_id is not None:
            if target is None:
                raise ValueError("目标曲目不存在或已合并，请重新选择")
            with self.db.session() as connection:
                matches = connection.execute("SELECT id FROM song WHERE category_id=? AND display_title=? AND status='archived'",
                                             (target["category_id"], target["display_title"])).fetchall()
            if len(matches) != 1:
                raise ValueError("目标曲目存在同名记录，请先合并误拆或区分版本名称，不能猜测归属")
            title, category = str(target["display_title"]), str(target["category_code"])
        title = title.strip() if title is not None else None
        instrument_changes = tuple(sorted((int(key), value) for key, value in (instruments or {}).items()))
        if not {key for key, _ in instrument_changes}.issubset(selected):
            raise ValueError("乐器修改包含未选中的分谱")
        changes = dict(instrument_changes)
        rows = []
        for item_id in sorted(selected):
            item = items[item_id]
            source = Path(item["source_path"])
            if source.suffix.casefold() != ".pdf" or not source.is_file() or sha256_file(source) != item["source_sha256"]:
                raise ValueError(f"原始PDF缺失或变化，请重新导入：{source}")
            old = json.loads(item["parsed_fields"] or "{}")
            new = dict(old)
            if changes.get(item_id) is not None:
                new["instrument_code"] = changes[item_id]
            if title is not None:
                new["title"] = title
            if category is not None:
                new["category_code"] = category
            if new.get("instrument_code") not in self.db.instrument_codes():
                raise ValueError(f"请为{source.name}选择已定义的乐器")
            if new.get("category_code") not in self.db.category_codes():
                raise ValueError(f"请为{source.name}选择已启用的分类")
            name = str(new.get("title") or "").strip()
            if not name or re.search(r'[<>:"/\\|?*\x00-\x1f]', name) or name.endswith(".") or len(name.encode("utf-16-le")) // 2 > 180:
                raise ValueError(f"曲名为空、过长或包含非法字符：{source.name}")
            new["title"] = name
            if not new_song:
                new.pop("new_song_confirmation", None)
            if new_song:
                if old.get("seq"):
                    new.setdefault("manual_original_seq", old["seq"])
                new["seq"] = None
                new.pop("target_song_id", None)
                new["new_song_confirmation"] = {"category_code": new["category_code"], "title": name}
            elif target is not None:
                if old.get("seq") and old["seq"] != target["seq"]:
                    new.setdefault("manual_original_seq", old["seq"])
                new["seq"] = str(target["seq"])
                new["target_song_id"] = int(target["id"])
            elif draft_fields is not None:
                new.pop("target_song_id", None)
                if draft_fields.get("seq"):
                    if old.get("seq") and old["seq"] != draft_fields["seq"]:
                        new.setdefault("manual_original_seq", old["seq"])
                    new["seq"] = draft_fields["seq"]
            elif any(new.get(key) != old.get(key) for key in ("title", "category_code")):
                new.pop("target_song_id", None)
            rows.append(CorrectionRow(item_id, str(source), str(item["source_sha256"]),
                                      json.dumps(old, ensure_ascii=False, sort_keys=True), json.dumps(new, ensure_ascii=False, sort_keys=True)))
        if new_song:
            keys = {(json.loads(row.new_fields)["category_code"], json.loads(row.new_fields)["title"]) for row in rows}
            if len(keys) != 1:
                raise ValueError("新建一首曲目需统一选中分谱的曲名和分类；不同曲目请分别处理")
            key = next(iter(keys))
            normalize = lambda value: re.sub(r"\s+", "", value).casefold()
            with self.db.session() as connection:
                existing_titles = [row[0] for row in connection.execute(
                    "SELECT song.display_title FROM song JOIN category ON category.id=song.category_id WHERE song.status='archived' AND category.code=?", (key[0],))]
            if any(normalize(name) == normalize(key[1]) for name in existing_titles):
                raise ValueError("库内已有同分类同名或仅空格不同的曲目。请归入已有曲目，或填写可区分的名称；不能强制误拆")
            related_ids = []
            for item_id, item in items.items():
                field = json.loads(item["parsed_fields"] or "{}")
                if item_id not in selected and item["status"] == "recognized" and field.get("category_code") == key[0] and normalize(str(field.get("title") or "")) == normalize(key[1]):
                    related_ids.append(item_id)
            if related_ids:
                raise RelatedDraftPartsError(related_ids)
        # A draft candidate can carry a formal target; the draft identity remains the explicit choice.
        formal_target = None if target_draft_item_id is not None else target_song_id
        return CorrectionPlan(batch_id, formal_target, title, category, instrument_changes, tuple(rows),
                              target_draft_item_id, draft_snapshot, new_song)

    def apply(self, plan: CorrectionPlan):
        with self.db.transaction() as connection:
            self._apply(plan, connection)

    def preview_archive(self, plan: CorrectionPlan, output_root, force=False):
        from score_organizer.services.archive_service import ArchiveService
        # Preview corrected fields and warnings in a transaction that never persists.
        with self.db.transaction() as connection:
            try:
                self._apply(plan, connection)
                return ArchiveService(self.db, output_root).build_plan(plan.batch_id, {row.item_id for row in plan.rows}, force=force)
            finally:
                connection.rollback()

    def _apply(self, plan: CorrectionPlan, connection):
        current = self.build_plan(plan.batch_id, [row.item_id for row in plan.rows], plan.title, plan.category,
                                  dict(plan.instruments), plan.target_song_id, plan.target_draft_item_id, plan.new_song)
        if current != plan:
            raise ValueError("预览后分谱或目标曲目已变化，请重新校对")
        for row in plan.rows:
            connection.execute("UPDATE import_item SET parsed_fields=?, status='recognized', decision='manual_correction' WHERE id=? AND batch_id=?",
                               (row.new_fields, row.item_id, plan.batch_id))
            connection.execute("INSERT INTO operation_log(batch_id, action, old_path, new_path, state) VALUES (?, 'correct_draft', ?, ?, 'committed')",
                               (plan.batch_id, json.dumps({"source": row.source_path, "fields": json.loads(row.old_fields)}, ensure_ascii=False),
                                json.dumps({"source": row.source_path, "fields": json.loads(row.new_fields)}, ensure_ascii=False)))

    def matching_candidates(self, batch_id, item_ids, title=None):
        selected = set(item_ids)
        items = self.db.list_import_items(batch_id)
        fields = {int(item["id"]): json.loads(item["parsed_fields"] or "{}") for item in items}
        if not selected or not selected.issubset(fields):
            raise ValueError("请先选择要查找归属的分谱")
        queries = [title] if title else [fields[item_id].get("title") or "" for item_id in selected]
        categories = {fields[item_id].get("category_code") for item_id in selected}
        groups = {}
        for score in self.db.list_archived_scores():
            key = ("archived", int(score["song_id"]))
            groups.setdefault(key, {"kind": "archived", "id": key[1], "title": score["display_title"],
                                    "category": score["category_code"], "seq": score["seq"], "pdf_paths": []})["pdf_paths"].append(str(score["source_path"]))
        for item in items:
            item_id = int(item["id"])
            field = fields[item_id]
            if item_id in selected or item["status"] != "recognized" or not field.get("title") or not field.get("category_code"):
                continue
            key = ("draft", field["category_code"], field["title"], field.get("seq"))
            groups.setdefault(key, {"kind": "draft", "id": item_id, "title": field["title"],
                                    "category": field["category_code"], "seq": field.get("seq"), "pdf_paths": []})["pdf_paths"].append(str(item["source_path"]))
        normalize = GroupingService(self.db)._normalize_title
        candidates = []
        for candidate in groups.values():
            other = normalize(candidate["title"])
            ratios = [fuzz.ratio(normalize(query), other) if normalize(query) and other else 0 for query in queries]
            contains = any(normalize(query) and other and (normalize(query) in other or other in normalize(query)) for query in queries)
            threshold = GroupingService.LIBRARY_TITLE_THRESHOLD if candidate["kind"] == "archived" else GroupingService.SIMILAR_TITLE_THRESHOLD
            candidates.append({**candidate, "similarity": round(max(ratios)),
                               "suggested": max(ratios) >= threshold or bool(contains)})
        return sorted(candidates, key=lambda row: (not row["suggested"], row["category"] not in categories,
                                                  -row["similarity"], row["title"], row["kind"], row["id"]))
