from __future__ import annotations

import json
import re
import shutil
import sqlite3
from contextlib import closing
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from uuid import uuid4

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import sha256_file
from score_organizer.storage.database import Database
from score_organizer.domain.score_naming import format_score_filename, format_song_folder


@dataclass(frozen=True)
class CatalogFileChange:
    score_id: int
    instrument_code: str
    old_instrument: Path
    new_instrument: Path
    old_song: Path | None
    new_song: Path | None
    sha256: str

    @property
    def path_changes(self) -> tuple[tuple[Path, Path], ...]:
        pairs = ((self.old_instrument, self.new_instrument),)
        return pairs + ((self.old_song, self.new_song),) if self.old_song and self.new_song else pairs


@dataclass(frozen=True)
class CatalogEditPlan:
    song_id: int
    seq: str
    old_category: str
    category: str
    old_title: str
    title: str
    version_no: int
    files: tuple[CatalogFileChange, ...]


class CatalogService:
    _numbering_version = 1

    def __init__(self, db: Database, output_root: str | Path) -> None:
        self.db = db
        self.root = Path(output_root).resolve()

    def build_plan(self, song_id: int, category: str, title: str) -> CatalogEditPlan:
        self._require_runtime()
        song = self.db.get_archived_song(song_id)
        if song is None:
            raise ValueError("已入库曲目不存在")
        title = title.strip()
        if not title or re.search(r'[<>:"/\\|?*\x00-\x1f]', title) or title.endswith("."):
            raise ValueError("曲名不能为空，也不能包含Windows文件名非法字符或以句点结尾")
        if len(title.encode("utf-16-le")) // 2 > 180:
            raise ValueError("曲名过长，请缩短后再保存")
        categories = {str(row["code"]): bool(row["active"]) for row in self.db.list_categories()}
        if category not in categories or (not categories[category] and category != song["category_code"]):
            raise ValueError("请选择一个已启用的分类")
        if category == song["category_code"] and title == song["display_title"]:
            raise ValueError("曲名和分类没有变化")
        if category == song["category_code"] and title.casefold() == str(song["display_title"]).casefold():
            raise ValueError("仅大小写变化请先改为临时曲名，保存后再改为目标曲名")
        with self.db.session() as connection:
            conflict = connection.execute(
                """SELECT song.id FROM song JOIN category ON category.id = song.category_id
                   WHERE category.code = ? AND song.display_title = ? AND song.id != ? AND song.status = 'archived'""",
                (category, title, song_id),
            ).fetchone()
        if conflict:
            raise ValueError("同分类已有同名曲目，请核对名称或版本，不能自动合并")
        scores = self.db.list_archived_scores(song_id)
        if not scores:
            raise ValueError("曲目没有可修改的已入库分谱")
        count = len(self.db.song_copy_codes(song_id))
        old_folder = self.root / "按曲目分类" / format_song_folder(song['category_code'], song['seq'], song['display_title'], count, naming_version=self._numbering_version)
        new_folder = self.root / "按曲目分类" / format_song_folder(category, song['seq'], title, count, naming_version=self._numbering_version)
        if count and new_folder.exists() and str(new_folder).casefold() != str(old_folder).casefold():
            raise ValueError("修改后的曲目目录已存在，不能合并或覆盖")
        files = []
        archive = ArchiveService(self.db, self.root)
        for score in scores:
            old_name = str(score["output_name"])
            if Path(old_name).name != old_name:
                raise ValueError("入库记录的文件名无效，请先核对目录")
            new_name = format_score_filename(score['instrument_code'], category, song['seq'], title, naming_version=self._numbering_version)
            instrument_folder = self.root / "按乐器分类" / archive._instrument_folder(score["instrument_code"])
            files.append(CatalogFileChange(int(score["id"]), score["instrument_code"],
                         instrument_folder / old_name, instrument_folder / new_name,
                         old_folder / old_name if score["storage_layout"] == "dual" else None,
                         new_folder / new_name if score["storage_layout"] == "dual" else None, score["source_sha256"]))
        plan = CatalogEditPlan(song_id, song["seq"], song["category_code"], category,
                               song["display_title"], title, int(song["version_no"]), tuple(files))
        self._validate_files(plan)
        return plan

    def _validate_files(self, plan: CatalogEditPlan) -> None:
        old_hashes = {}
        new_owners = {}
        with self.db.session() as connection:
            original_sources = {str(Path(row[0]).resolve()).casefold() for row in connection.execute(
                "SELECT source_path FROM score UNION SELECT source_path FROM import_item"
            ) if row[0]}
        for file in plan.files:
            for old, new in file.path_changes:
                if not old.resolve().is_relative_to(self.root) or not new.resolve().is_relative_to(self.root):
                    raise ValueError("分谱路径不在乐谱库内，不能修改")
                old_key = str(old).casefold()
                new_key = str(new).casefold()
                if str(old.resolve()).casefold() in original_sources:
                    raise ValueError(f"库内文件正在被当作源PDF使用，不能改动原件：{old}")
                if old_key in old_hashes and old_hashes[old_key] != file.sha256:
                    raise ValueError("同一库内路径对应不同PDF记录，请先核对重复分谱")
                old_hashes[old_key] = file.sha256
                owner = (old_key, file.sha256)
                if new_key in new_owners and new_owners[new_key] != owner:
                    raise ValueError("同乐器多份分谱会产生重名，请先核对重复或版本")
                new_owners[new_key] = owner
                if not old.is_file() or sha256_file(old) != file.sha256:
                    raise ValueError(f"库内PDF缺失或变化，不能修改：{old}")
        for file in plan.files:
            for _, new in file.path_changes:
                if new.exists() and str(new).casefold() not in old_hashes:
                    raise ValueError(f"目标文件已存在，不能覆盖：{new}")

    def commit_plan(self, plan: CatalogEditPlan) -> Path:
        return self._commit_changes(
            plan, lambda: self.build_plan(plan.song_id, plan.category, plan.title),
            lambda: self.db.update_archived_song_metadata(plan.song_id, plan.category, plan.title,
                {file.score_id: file.new_instrument.name for file in plan.files}),
            "edit_catalog", f"序号：{plan.seq}\n{plan.old_title} → {plan.title}\n分类：{plan.old_category} → {plan.category}",
        )

    def _commit_changes(self, plan, rebuild, update_records, action, summary, extra_files=None) -> Path:
        self._require_runtime()
        if rebuild() != plan:
            raise ValueError("曲目记录已变化，请重新预览")
        backup_root = self.root / "修改备份" / f"{datetime.now():%Y%m%d_%H%M%S}_{uuid4().hex[:8]}"
        if not backup_root.resolve().is_relative_to(self.root):
            raise ValueError("备份目录不在乐谱库内，不能修改")
        backup_root.mkdir(parents=True, exist_ok=False)
        (backup_root / "修改方案.json").write_text(json.dumps(asdict(plan), ensure_ascii=False, indent=2, default=str), encoding="utf-8")
        with closing(self.db.connect()) as source, closing(sqlite3.connect(backup_root / "修改前.sqlite3")) as destination:
            source.backup(destination)
        self._verify_backup(backup_root / "修改前.sqlite3")
        backup_database_hash = sha256_file(backup_root / "修改前.sqlite3")
        originals = {}
        outputs = {}
        created = []
        removed = []
        try:
            for file in plan.files:
                for old, new in file.path_changes:
                    if old not in originals:
                        backup = backup_root / f"{len(originals):06d}.pdf"
                        shutil.copy2(old, backup)
                        if sha256_file(backup) != file.sha256:
                            raise ValueError("修改前备份校验失败")
                        originals[old] = (backup, file.sha256)
                    outputs[new] = originals[old]
            with self.db.transaction():
                self._require_runtime()
                if rebuild() != plan:
                    raise ValueError("预览后曲目或文件已变化，已取消修改")
                if sha256_file(backup_root / "修改前.sqlite3") != backup_database_hash:
                    raise ValueError("修改前数据库备份已变化，已停止修改")
                self._verify_backup(backup_root / "修改前.sqlite3")
                for backup, digest in originals.values():
                    if sha256_file(backup) != digest:
                        raise ValueError("修改前PDF备份已变化，已停止修改")
                for old, (_, digest) in originals.items():
                    if sha256_file(old) != digest:
                        raise ValueError("库内文件已变化，已停止修改")
                    old.unlink()
                    removed.append(old)
                for new, (backup, digest) in outputs.items():
                    new.parent.mkdir(parents=True, exist_ok=True)
                    with new.open("xb") as destination:
                        created.append(new)
                        with backup.open("rb") as source:
                            shutil.copyfileobj(source, destination)
                    if sha256_file(new) != digest:
                        raise ValueError("修改后的PDF校验失败")
                for path, content in (extra_files or {}).items():
                    if not path.resolve().is_relative_to(self.root):
                        raise ValueError("说明文件不在乐谱库内")
                    path.parent.mkdir(parents=True, exist_ok=True)
                    with path.open("x", encoding="utf-8") as destination:
                        created.append(path)
                        destination.write(content)
                update_records()
                with self.db.session() as connection:
                    connection.execute(
                        "INSERT INTO operation_log(song_id, action, old_path, new_path, state) VALUES (?, ?, ?, ?, ?)",
                        (plan.song_id, action, str(plan.files[0].old_instrument.parent), str(plan.files[0].new_instrument.parent), "committed"),
                    )
                (backup_root / "完成.txt").write_text(summary, encoding="utf-8-sig")
        except Exception as exc:
            failures = []
            for path in reversed(created):
                try:
                    path.unlink(missing_ok=True)
                except OSError:
                    failures.append(str(path))
            for old in removed:
                try:
                    with old.open("xb") as destination, originals[old][0].open("rb") as source:
                        shutil.copyfileobj(source, destination)
                    if sha256_file(old) != originals[old][1]:
                        failures.append(str(old))
                except OSError:
                    failures.append(str(old))
            (backup_root / "完成.txt").unlink(missing_ok=True)
            self._remove_empty_folders(outputs)
            if failures:
                raise ValueError(f"修改失败且部分文件恢复未完成，请停止整理。备份：{backup_root}\n" + "\n".join(failures)) from exc
            raise ValueError(f"修改失败，已恢复原目录和记录。备份：{backup_root}\n{exc}") from exc
        self._remove_empty_folders(originals)
        return backup_root

    def _require_runtime(self) -> None:
        """Normal catalog editing retains the existing Database version gate."""

    def _verify_backup(self, path: Path) -> None:
        """A future runtime may require a stricter snapshot format check."""

    def _remove_empty_folders(self, paths) -> None:
        for folder in {path.parent for path in paths}:
            try:
                folder.rmdir()
            except OSError:
                pass
