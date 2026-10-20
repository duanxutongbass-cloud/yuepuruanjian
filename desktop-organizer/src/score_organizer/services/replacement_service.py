from contextlib import closing
from dataclasses import asdict, dataclass
import json
from pathlib import Path
import re
import shutil
import sqlite3
from uuid import uuid4

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.duplicate_layout import DuplicateLayoutStore
from score_organizer.services.import_service import sha256_file


@dataclass(frozen=True)
class ReplacementRow:
    item_id: int
    score_id: int
    song_id: int
    seq: str
    category: str
    title: str
    instrument: str
    source: Path
    sha256: str
    old_sha256: str
    targets: tuple[Path, ...]
    item_snapshot: str
    score_snapshot: str
    song_snapshot: str


@dataclass(frozen=True)
class ReplacementPlan:
    batch_id: int
    event_id: str
    rows: tuple[ReplacementRow, ...]
    duplicates: str


class ReplacementService:
    def __init__(self, db, output_root):
        self.db = db
        self.root = Path(output_root).resolve()
        self.archive = ArchiveService(db, self.root)
        self.duplicates = DuplicateLayoutStore(db, self.root)

    def build_plan(self, batch_id, choices, event_id=None):
        event_id = event_id or uuid4().hex
        if not re.fullmatch(r"[0-9a-f]{32}", event_id):
            raise ValueError("替换批次无效")
        if not choices or len(set(choices.values())) != len(choices):
            raise ValueError("每份库内分谱只能选择一份新谱替换，请分别确认")
        items = {int(row["id"]): row for row in self.db.list_import_items(batch_id)}
        scores = {int(row["id"]): row for row in self.db.list_archived_scores()}
        with self.db.session() as connection:
            batch = connection.execute("SELECT state FROM import_batch WHERE id=?", (batch_id,)).fetchone()
            protected = {str(Path(row[0]).resolve()).casefold() for row in connection.execute(
                "SELECT source_path FROM score UNION SELECT source_path FROM import_item") if row[0]}
        if batch is None or batch["state"] != "draft":
            raise ValueError("草稿批次已变化，请重新对比")
        rows, entries, occupied = [], [], set()
        for item_id, score_id in sorted(choices.items()):
            item, score = items.get(item_id), scores.get(score_id)
            if item is None or score is None or item["status"] != "recognized":
                raise ValueError("新谱或库内分谱已变化，请先完成校对再重新选择")
            fields = json.loads(item["parsed_fields"] or "{}")
            if fields.get("instrument_code") != score["instrument_code"]:
                raise ValueError("替换必须是同一种乐器；不能替换其他乐器分谱")
            if Path(score["output_name"]).name != score["output_name"]:
                raise ValueError("库内文件名无效")
            source = Path(item["source_path"])
            if source.suffix.casefold() != ".pdf" or not source.is_file() or sha256_file(source) != item["source_sha256"]:
                raise ValueError("新谱源文件缺失或变化，请重新导入")
            targets = self.archive.score_paths(score)
            for target in targets:
                self._safe_library_path(target)
                key = str(target.resolve()).casefold()
                if key in protected:
                    raise ValueError("库内副本正在被当作源PDF使用，不能替换原件")
                if key in occupied:
                    raise ValueError("替换目标路径冲突")
                occupied.add(key)
                if not target.is_file() or sha256_file(target) != score["source_sha256"]:
                    raise ValueError("库内存档缺失或变化，不能替换")
            song = self.db.get_archived_song(int(score["song_id"]))
            snapshot = lambda value: json.dumps(dict(value), ensure_ascii=False, sort_keys=True)
            rows.append(ReplacementRow(item_id, score_id, int(score["song_id"]), score["seq"], score["category_code"],
                score["display_title"], score["instrument_code"], source, item["source_sha256"], score["source_sha256"],
                targets, snapshot(item), snapshot(score), snapshot(song)))
            entries.append({"source_path": score["source_path"], "copy_source": str(targets[0]),
                "source_sha256": score["source_sha256"], "display_title": score["display_title"],
                "display_instrument_code": score["instrument_code"],
                "parsed_fields": {"category_code": score["category_code"], "instrument_code": score["instrument_code"],
                                  "title": score["display_title"], "seq": score["seq"]},
                "reasons": ["用户选择新谱替换库内旧谱"],
                "details": [f"替换事件：{event_id}", f"原编号：{score['seq']}；旧库记录：{score['id']}",
                            f"新谱源文件：{source}", f"新谱SHA-256：{item['source_sha256']}",
                            "旧库路径：" + "；".join(map(str, targets)), "旧库记录：" + snapshot(score)]})
        allocated = self.duplicates.allocate(entries)
        return ReplacementPlan(batch_id, event_id, tuple(rows), json.dumps(allocated, ensure_ascii=False, sort_keys=True))

    def _safe_library_path(self, path):
        if not path.resolve().is_relative_to(self.root):
            raise ValueError("库内路径越界，不能替换")
        for ancestor in (path, *path.parents):
            if ancestor == self.root:
                break
            if ancestor.is_symlink() or ancestor.is_junction():
                raise ValueError("库内路径存在链接，不能替换")

    def commit_plan(self, plan):
        choices = {row.item_id: row.score_id for row in plan.rows}
        def validate():
            if self.build_plan(plan.batch_id, choices, plan.event_id) != plan:
                raise ValueError("预览后新谱、库内记录或重复目录已变化，请重新预览")
        validate()
        backup = self.root / "修改备份" / f"替换分谱_{plan.event_id}"
        self._safe_library_path(backup)
        backup.mkdir(parents=True, exist_ok=False)
        (backup / "替换方案.json").write_text(json.dumps(asdict(plan), ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        with closing(self.db.connect()) as source, closing(sqlite3.connect(backup / "修改前.sqlite3")) as destination:
            source.backup(destination)
        old_copies, new_copies, removed, created = {}, {}, [], []
        replacement_started = False
        try:
            for row in plan.rows:
                new = backup / f"新谱_{row.item_id}.pdf"
                shutil.copy2(row.source, new)
                if sha256_file(new) != row.sha256:
                    raise ValueError("新谱暂存校验失败")
                new_copies[row.item_id] = new
                for target in row.targets:
                    old = backup / f"旧谱_{len(old_copies):04d}.pdf"
                    shutil.copy2(target, old)
                    if sha256_file(old) != row.old_sha256:
                        raise ValueError("旧谱备份校验失败")
                    old_copies[target] = (old, row.old_sha256)

            def replace():
                nonlocal replacement_started
                replacement_started = True
                for row in plan.rows:
                    if sha256_file(row.source) != row.sha256 or sha256_file(new_copies[row.item_id]) != row.sha256:
                        raise ValueError("新谱在提交前发生变化")
                    for target in row.targets:
                        self._safe_library_path(target)
                        if sha256_file(target) != row.old_sha256:
                            raise ValueError("旧谱在提交前发生变化")
                        target.unlink()
                        removed.append(target)
                        with target.open("xb") as destination:
                            created.append(target)
                            with new_copies[row.item_id].open("rb") as source:
                                shutil.copyfileobj(source, destination)
                        if sha256_file(target) != row.sha256:
                            raise ValueError("新谱入库校验失败")
                with self.db.session() as connection:
                    for row in plan.rows:
                        connection.execute("UPDATE score SET source_name=?,source_path=?,source_sha256=? WHERE id=? AND song_id=?",
                                           (row.source.name, str(row.source), row.sha256, row.score_id, row.song_id))
                        connection.execute("INSERT INTO operation_log(song_id,action,old_path,new_path,state) VALUES (?, 'replace_score', ?, ?, 'committed')",
                                           (row.song_id, row.score_snapshot, json.dumps({"event_id": plan.event_id, "source": str(row.source), "sha256": row.sha256}),))
                remaining = self.db.remove_import_items_from_draft(plan.batch_id, list(choices))
                (backup / "完成.txt").write_text("用户确认保留新谱、旧谱转入已确认重复；编号及存档位置不变，原始PDF未改动。", encoding="utf-8-sig")
                return remaining

            _, remaining = self.duplicates.store(json.loads(plan.duplicates), validate, replace)
            return backup, remaining
        except Exception as exc:
            if replacement_started:
                try:
                    with self.db.session() as connection:
                        committed = any(json.loads(row[0]).get("event_id") == plan.event_id for row in connection.execute(
                            "SELECT new_path FROM operation_log WHERE action='replace_score'"))
                except (OSError, sqlite3.Error, ValueError) as error:
                    raise ValueError(f"无法确认替换提交状态，请停止整理，不要重试。备份：{backup}\n{error}") from exc
                if committed:
                    raise ValueError(f"替换已提交，但后续清理失败；库内新谱和记录保留，请停止整理并检查。备份：{backup}\n{exc}") from exc
            failures = []
            for target in reversed(created):
                try:
                    self._safe_library_path(target)
                    target.unlink(missing_ok=True)
                except (ValueError, OSError):
                    failures.append(str(target))
            for target in removed:
                try:
                    self._safe_library_path(target)
                    with target.open("xb") as destination, old_copies[target][0].open("rb") as source:
                        shutil.copyfileobj(source, destination)
                    if sha256_file(target) != old_copies[target][1]:
                        failures.append(str(target))
                except (ValueError, OSError):
                    failures.append(str(target))
            try:
                (backup / "完成.txt").unlink(missing_ok=True)
            except OSError:
                failures.append(str(backup / "完成.txt"))
            if failures:
                raise ValueError(f"替换失败且恢复未完成，请停止整理。备份：{backup}\n" + "\n".join(failures)) from exc
            if "回退未完成" in str(exc):
                raise ValueError(f"替换失败且重复目录回退未完成，请停止整理。备份：{backup}\n{exc}") from exc
            raise ValueError(f"替换未完成，本次修改已回退；草稿保留。备份：{backup}\n{exc}") from exc
