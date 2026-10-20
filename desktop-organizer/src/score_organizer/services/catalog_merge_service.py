from __future__ import annotations

from score_organizer.domain.score_naming import format_score_filename, format_song_folder

import json
import re
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
from uuid import uuid4

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.catalog_service import CatalogEditPlan, CatalogFileChange, CatalogService
from score_organizer.services.duplicate_layout import DuplicateLayoutStore, safe_duplicate_name


@dataclass(frozen=True)
class CatalogMergePlan(CatalogEditPlan):
    source_song_id: int
    source_seq: str
    source_title: str
    selected_score_ids: tuple[int, ...]
    event_id: str
    duplicate_root: Path


class CatalogMergeService(CatalogService):
    def build_plan(self, target_id: int, source_id: int, selected_ids, title: str, event_id=None) -> CatalogMergePlan:
        self._require_runtime()
        target = self.db.get_archived_song(target_id)
        source = self.db.get_archived_song(source_id)
        if target_id == source_id or target is None or source is None:
            raise ValueError("请选择两首不同的已入库曲目")
        if target["category_code"] != source["category_code"] or target["version_no"] != source["version_no"]:
            raise ValueError("不同分类或不同版本不能合并，请保留独立记录")
        if re.sub(r"\s+", "", target["display_title"]) != re.sub(r"\s+", "", source["display_title"]):
            raise ValueError("此入口仅处理同名或仅空格不同的误拆曲目")
        title = title.strip()
        if not title or re.search(r'[<>:"/\\|?*\x00-\x1f]', title) or title.endswith(".") or len(title.encode("utf-16-le")) // 2 > 180:
            raise ValueError("请输入合法且不超过180字的曲名")
        with self.db.session() as connection:
            conflict = connection.execute(
                "SELECT id FROM song WHERE category_id = ? AND display_title = ? AND status = 'archived' AND id NOT IN (?, ?)",
                (target["category_id"], title, target_id, source_id),
            ).fetchone()
        if conflict:
            raise ValueError("还有另一首同名曲目，请先核对，不可覆盖")
        records = self.db.list_archived_scores(target_id) + self.db.list_archived_scores(source_id)
        if not self.db.list_archived_scores(target_id) or not self.db.list_archived_scores(source_id):
            raise ValueError("两首曲目均必须有已入库分谱")
        selected = set(selected_ids)
        if not selected.issubset({int(row["id"]) for row in records}):
            raise ValueError("分谱记录已变化，请重新选择")
        by_instrument = defaultdict(list)
        for row in records:
            by_instrument[str(row["instrument_code"])].append(row)
        for code, rows in by_instrument.items():
            if sum(int(row["id"]) in selected for row in rows) != 1:
                raise ValueError(f"乐器{code}必须且只能保留一份；其余需人工确认重复，不同版本请取消合并")
        retained = [row for row in records if int(row["id"]) in selected]
        regular_codes = {str(row["instrument_code"]) for row in retained if row["storage_layout"] == "dual"}
        if regular_codes and len(regular_codes) < self.db.minimum_instruments(str(target["category_code"])):
            raise ValueError("合并后普通分谱不足分类要求的乐器数，不能进入正式库")
        event_id = event_id or uuid4().hex
        if not re.fullmatch(r"[0-9a-f]{32}", event_id):
            raise ValueError("合并批次无效")
        duplicate_store = DuplicateLayoutStore(self.db, self.root)
        duplicates = duplicate_store.allocate([
            {"score_id": int(row["id"]), "channel": channel, "source_path": str(row["source_path"]),
             "source_sha256": str(row["source_sha256"]), "display_title": title,
             "display_instrument_code": row["instrument_code"]}
            for row in records if int(row["id"]) not in selected
            for channel in (("instrument", "song") if row["storage_layout"] == "dual" else ("instrument",))])
        duplicate_paths = {(item["score_id"], item["channel"]): duplicate_store.root / item["relative_path"] for item in duplicates}
        duplicate_root = next(iter(duplicate_paths.values())).parent if duplicate_paths else duplicate_store.root / safe_duplicate_name(title)
        archive = ArchiveService(self.db, self.root)
        files = []
        for row in records:
            name = str(row["output_name"])
            if Path(name).name != name:
                raise ValueError("入库文件名无效")
            owner = target if row["song_id"] == target_id else source
            count = len(self.db.song_copy_codes(int(owner["id"])))
            folder = self.root / "按曲目分类" / format_song_folder(owner['category_code'], owner['seq'], owner['display_title'], count, naming_version=self._numbering_version)
            instrument_folder = self.root / "按乐器分类" / archive._instrument_folder(row["instrument_code"])
            new_name = format_score_filename(row['instrument_code'], target['category_code'], target['seq'], title, naming_version=self._numbering_version)
            if int(row["id"]) in selected:
                new_instrument = instrument_folder / new_name
                new_song = (self.root / "按曲目分类" / format_song_folder(target['category_code'], target['seq'], title, len(regular_codes), naming_version=self._numbering_version) / new_name
                            if row["storage_layout"] == "dual" else None)
            else:
                new_instrument = duplicate_paths[(int(row["id"]), "instrument")]
                new_song = duplicate_paths.get((int(row["id"]), "song"))
            files.append(CatalogFileChange(int(row["id"]), row["instrument_code"], instrument_folder / name,
                                           new_instrument, folder / name if row["storage_layout"] == "dual" else None,
                                           new_song, row["source_sha256"]))
        plan = CatalogMergePlan(target_id, target["seq"], target["category_code"], target["category_code"],
                                target["display_title"], title, int(target["version_no"]), tuple(files),
                                source_id, source["seq"], source["display_title"], tuple(sorted(selected)), event_id, duplicate_root)
        old_folders = {f.old_song.parent for f in files if f.old_song}
        for folder in old_folders:
            expected = {f.old_song for f in files if f.old_song and f.old_song.parent == folder}
            if not folder.is_dir() or set(folder.iterdir()) != expected:
                raise ValueError("旧曲目目录缺失或有未登记文件，请先核对目录")
        for file in files:
            if file.score_id in selected and file.new_song and file.new_song.parent.exists() and file.new_song.parent not in old_folders:
                raise ValueError("合并目标曲目目录已存在，不能覆盖或混入其他文件")
        self._validate_files(plan)
        return plan

    def commit_plan(self, plan: CatalogMergePlan) -> Path:
        self._require_runtime()
        def rebuild():
            target = self.db.get_archived_song(plan.song_id)
            source = self.db.get_archived_song(plan.source_song_id)
            if (target is None or source is None or target["seq"] != plan.seq or source["seq"] != plan.source_seq
                    or target["display_title"] != plan.old_title or source["display_title"] != plan.source_title
                    or target["category_code"] != plan.category or source["category_code"] != plan.category
                    or target["version_no"] != plan.version_no or source["version_no"] != plan.version_no):
                raise ValueError("预览后曲目记录已变化，请重新选择并预览")
            return self.build_plan(plan.song_id, plan.source_song_id, plan.selected_score_ids, plan.title, plan.event_id)

        def update_records():
            with self.db.session() as connection:
                for file in plan.files:
                    owner = plan.song_id if file.score_id in plan.selected_score_ids else plan.source_song_id
                    connection.execute("UPDATE score SET song_id = ?, output_name = ? WHERE id = ?",
                                       (owner, file.new_instrument.name, file.score_id))
                connection.execute("UPDATE song SET status = 'merged' WHERE id = ? AND status = 'archived'", (plan.source_song_id,))
                connection.execute("UPDATE title_alias SET song_id = ? WHERE song_id = ?", (plan.song_id, plan.source_song_id))
            self.db.update_archived_song_metadata(plan.song_id, plan.category, plan.title,
                {f.score_id: f.new_instrument.name for f in plan.files if f.score_id in plan.selected_score_ids})

        summary = f"人工确认误拆合并：{plan.source_seq} {plan.source_title} → {plan.seq} {plan.title}\n原编号{plan.source_seq}保留占号，不再显示为独立曲目。\n未保留分谱经用户确认转存至已确认重复；原始PDF不改动。"
        extra = {}
        if any(f.score_id not in plan.selected_score_ids for f in plan.files):
            manifest = asdict(plan)
            with self.db.session() as connection:
                manifest["original_records"] = [dict(r) for r in connection.execute(
                    "SELECT * FROM score WHERE song_id IN (?, ?)", (plan.song_id, plan.source_song_id))]
            extra = {plan.duplicate_root / f"合并确认说明_{plan.event_id}.txt": summary,
                     plan.duplicate_root / f"合并对比记录_{plan.event_id}.json": json.dumps(manifest, ensure_ascii=False, indent=2, default=str)}
        return self._commit_changes(plan, rebuild, update_records, "merge_catalog", summary, extra)
