from __future__ import annotations

import json
import shutil
import tempfile
from dataclasses import dataclass
from pathlib import Path

from score_organizer.services.import_service import sha256_file
from score_organizer.services.report_service import ReportService
from score_organizer.storage.database import Database
from score_organizer.domain.numbering import normalize_seq, next_global_sequence, sequence_limit, SequenceExhaustedError
from score_organizer.domain.score_naming import format_score_filename, format_song_folder


@dataclass(frozen=True)
class ArchiveFilePlan:
    item_id: int
    source_path: Path
    instrument_code: str
    output_name: str
    by_instrument_path: Path
    by_song_path: Path | None
    source_sha256: str

    @property
    def targets(self) -> tuple[Path, ...]:
        return (self.by_instrument_path, self.by_song_path) if self.by_song_path else (self.by_instrument_path,)


@dataclass(frozen=True)
class ArchiveSongPlan:
    category_code: str
    seq: str
    title: str
    folder_name: str
    files: tuple[ArchiveFilePlan, ...]
    existing_song_id: int | None = None
    force: bool = False


class ArchiveService:
    _numbering_version = 1

    def __init__(self, db: Database, output_root: str | Path) -> None:
        self.db = db
        self.output_root = Path(output_root)

    def build_plan(self, batch_id: int, item_ids=None, force: bool = False) -> list[ArchiveSongPlan]:
        if force and not item_ids:
            raise ValueError("强制入库必须明确选择分谱，不能对整个草稿箱自动强制")
        reports = ReportService(self.db, naming_version=self._numbering_version).build_song_reports(batch_id)
        warnings = self.db.list_warnings(batch_id)
        blocked_source_paths = self._blocked_source_paths(warnings)
        plans: list[ArchiveSongPlan] = []
        with self.db.session() as connection:
            pending_migrations = {(row[0], row[1]) for row in connection.execute(
                "SELECT category.code,song.display_title FROM song JOIN category ON category.id=song.category_id WHERE song.status='migration_pending'")}
        scoped_ids = None
        scoped_keys = None
        if item_ids is not None:
            selected = set(item_ids)
            items = self.db.list_import_items(batch_id)
            if not selected or not selected.issubset({int(row["id"]) for row in items}):
                raise ValueError("校对分谱已变化，请重新选择")
            scoped_keys = set()
            for item in items:
                if item["id"] in selected and item["status"] == "recognized":
                    fields = json.loads(item["parsed_fields"] or "{}")
                    scoped_keys.add((fields.get("category_code"), fields.get("title")))
            scoped_ids = set()
            for item in items:
                fields = json.loads(item["parsed_fields"] or "{}")
                if (int(item["id"]) in selected if force else (fields.get("category_code"), fields.get("title")) in scoped_keys):
                    scoped_ids.add(int(item["id"]))
        requested = self.requested_sequences(batch_id, scoped_ids)
        reserved = self.reserved_sequences(requested, batch_id=batch_id)
        for report in reports:
            if (report.category_code, report.title) in pending_migrations:
                continue  # Preserve the reserved identity; never silently renumber a skipped song.
            if scoped_keys is not None and (report.category_code, report.title) not in scoped_keys:
                continue
            report_items = list(self._items_for_report(batch_id, report.category_code, report.title))
            if force:
                report_items = [item for item in report_items if item["id"] in scoped_ids]
            existing_song = self.db.find_archived_song(report.category_code, report.title)
            for item in report_items:
                fields = json.loads(item["parsed_fields"] or "{}")
                confirmation = fields.get("new_song_confirmation")
                if confirmation is not None and (confirmation != {"category_code": report.category_code, "title": report.title}
                                                 or fields.get("seq") or fields.get("target_song_id") or existing_song is not None):
                    raise ValueError("新建曲目的确认已失效或库内已有该曲目，请重新选择归属；不能自动改为补入已有曲目")
            eligible = self.db.archive_eligible_codes(report.category_code, self._instrument_codes_for_items(report_items),
                                                      int(existing_song["id"]) if existing_song else None, force=force)
            report_items = [item for item in report_items if json.loads(item["parsed_fields"])["instrument_code"] in eligible]
            if not report_items:
                continue
            source_paths = {str(item["source_path"]) for item in report_items}
            if source_paths & blocked_source_paths:
                continue
            if self._has_blocking_warning(report.category_code, report.title, warnings, source_paths):
                continue
            if existing_song is None:
                seq = requested.get((report.category_code, report.title))
                if seq is None:
                    try:
                        seq = next_global_sequence(list(reserved), naming_version=self._numbering_version).value
                    except SequenceExhaustedError as error:
                        raise ValueError(str(error)) from error
                reserved.add(seq)
                existing_song_id = None
            else:
                seq = str(existing_song["seq"])
                existing_song_id = int(existing_song["id"])
            modes = self.db.instrument_archive_modes()
            copy_codes = {code for code in eligible if modes[code] == "dual"}
            total_instruments = len(copy_codes | (self.db.song_copy_codes(existing_song_id) if existing_song_id else set()))
            folder_name = format_song_folder(report.category_code, seq, report.title, total_instruments, naming_version=self._numbering_version)
            files = []
            for item in report_items:
                fields = json.loads(item["parsed_fields"] or "{}")
                instrument_code = str(fields["instrument_code"])
                output_name = format_score_filename(instrument_code, report.category_code, seq, report.title, naming_version=self._numbering_version)
                files.append(
                    ArchiveFilePlan(
                        item_id=int(item["id"]),
                        source_path=Path(item["source_path"]),
                        instrument_code=instrument_code,
                        output_name=output_name,
                        by_instrument_path=(
                            self.output_root
                            / "按乐器分类"
                            / self._instrument_folder(instrument_code)
                            / output_name
                        ),
                        by_song_path=(self.output_root / "按曲目分类" / folder_name / output_name
                                      if modes[instrument_code] == "dual" else None),
                        source_sha256=str(item["source_sha256"]),
                    )
                )
            plans.append(
                ArchiveSongPlan(
                    category_code=report.category_code,
                    seq=seq,
                    title=report.title,
                    folder_name=folder_name,
                    files=tuple(files),
                    existing_song_id=existing_song_id,
                    force=force,
                )
            )
        self._validate_plan(plans)
        return plans

    def requested_sequences(self, batch_id: int, item_ids=None) -> dict[tuple[str, str], str]:
        requested: dict[tuple[str, str], str] = {}
        owners: dict[str, tuple[str, str]] = {}
        with self.db.session() as connection:
            for row in connection.execute("SELECT song.seq, category.code, song.display_title FROM song JOIN category ON category.id = song.category_id WHERE song.seq IS NOT NULL"):
                owners[str(row["seq"])] = (str(row["code"]), str(row["display_title"]))
        for item in self.db.list_import_items(batch_id):
            if item_ids is not None and item["id"] not in item_ids:
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            if item["status"] != "recognized" or not fields.get("seq"):
                continue
            key = (str(fields["category_code"]), str(fields["title"]))
            seq = normalize_seq(str(fields["seq"]), naming_version=self._numbering_version)
            existing = self.db.find_archived_song(*key)
            if (key in requested and requested[key] != seq) or (seq in owners and owners[seq] != key) or (existing and existing["seq"] != seq):
                raise ValueError(f"曲目 {key[1]} 的已有序号 {seq} 与其他分谱或曲目冲突，请人工核对")
            requested[key] = seq
            owners[seq] = key
        return requested

    def reserved_sequences(self, requested: dict[tuple[str, str], str], *, batch_id: int | None = None) -> set[str]:
        """All song states reserve numbers; explicit unselected drafts in this batch also reserve their slots."""
        with self.db.session() as connection:
            sequences = {normalize_seq(row[0], naming_version=self._numbering_version)
                         for row in connection.execute("SELECT seq FROM song WHERE seq IS NOT NULL")}
        sequences.update(normalize_seq(seq, naming_version=self._numbering_version) for seq in requested.values())
        if batch_id is not None:
            for item in self.db.list_import_items(batch_id):
                fields = json.loads(item["parsed_fields"] or "{}")
                if item["status"] == "recognized" and fields.get("seq"):
                    try:
                        sequences.add(normalize_seq(str(fields["seq"]), naming_version=self._numbering_version))
                    except ValueError:
                        # Invalid unselected input is still handled by the warning/correction flow.
                        pass
        return sequences

    def next_candidate_number(self, requested: dict[tuple[str, str], str]) -> int:
        try:
            return int(next_global_sequence(list(self.reserved_sequences(requested)), naming_version=self._numbering_version).value)
        except SequenceExhaustedError:
            # Preview callers can still show existing/manual entries in a full library.
            return sequence_limit(self._numbering_version) + 1

    def _has_blocking_warning(self, category_code: str, title: str, warnings, source_paths: set[str]) -> bool:
        for warning in warnings:
            if warning["severity"] != "blocking" or warning["state"] != "待处理":
                continue
            details = json.loads(warning["details"] or "{}")
            if details.get("category_code") != category_code or details.get("title") != title:
                continue
            if warning["code"] == "insufficient_instruments":
                continue
            if details.get("source_paths") and not source_paths.intersection(details["source_paths"]):
                continue
            return True
        return False

    def _blocked_source_paths(self, warnings) -> set[str]:
        paths: set[str] = set()
        for warning in warnings:
            if warning["severity"] != "blocking" or warning["state"] != "待处理":
                continue
            if warning["code"] == "insufficient_instruments":
                continue
            details = json.loads(warning["details"] or "{}")
            for source_path in details.get("source_paths") or []:
                paths.add(str(source_path))
        return paths

    def _instrument_codes_for_items(self, items) -> set[str]:
        codes: set[str] = set()
        for item in items:
            fields = json.loads(item["parsed_fields"] or "{}")
            if fields.get("instrument_code"):
                codes.add(str(fields["instrument_code"]))
        return codes

    def commit_plan(self, batch_id: int, plans: list[ArchiveSongPlan]) -> int:
        force = bool(plans) and all(song.force for song in plans)
        if any(song.force for song in plans) and not force:
            raise ValueError("强制与普通方案不能混合提交，请重新预览")
        if plans and self.build_plan(batch_id, {file.item_id for song in plans for file in song.files}, force=force) != plans:
            raise ValueError("预览后分谱或曲目信息已变化，请重新预览")
        self._validate_plan(plans)
        self.output_root.mkdir(parents=True, exist_ok=True)
        staging_root = Path(tempfile.mkdtemp(prefix=".staging-", dir=self.output_root))
        copied_paths: list[Path] = []
        renamed_folders: list[tuple[Path, Path]] = []
        archived_item_ids: list[int] = []
        try:
            # All catalog writes and draft removal share one rollback boundary.
            with self.db.transaction():
                if plans and self.build_plan(batch_id, {file.item_id for song in plans for file in song.files}, force=force) != plans:
                    raise ValueError("预览后分谱或曲目信息已变化，请重新预览")
                for song in plans:
                    song_id = song.existing_song_id
                    if song_id is None:
                        song_id = self.db.create_song(song.category_code, song.seq, song.title, status="archived")
                    else:
                        old_count = len(self.db.song_copy_codes(song_id))
                        old_folder = self.output_root / "按曲目分类" / format_song_folder(song.category_code, song.seq, song.title, old_count, naming_version=self._numbering_version)
                        new_folder = self.output_root / "按曲目分类" / song.folder_name
                        for score in self.db.list_archived_scores(song_id):
                            if any(not pdf.is_file() or sha256_file(pdf) != score["source_sha256"] for pdf in self.score_paths(score)):
                                raise ValueError("已归档分谱缺失或变化，请先核对实际存档文件后再补谱")
                        if old_count and old_folder != new_folder:
                            if not old_folder.is_dir() or new_folder.exists():
                                raise ValueError("补谱目录缺失或目标目录已存在，请先核对已归档目录")
                            old_folder.rename(new_folder)
                            renamed_folders.append((old_folder, new_folder))
                    regular_count = len(self.db.song_copy_codes(song_id) | {file.instrument_code for file in song.files if file.by_song_path})
                    if song.force:
                        waived = regular_count > 0 and regular_count < self.db.minimum_instruments(song.category_code)
                        with self.db.session() as connection:
                            if waived:
                                connection.execute("UPDATE song SET minimum_waived=1 WHERE id=?", (song_id,))
                            connection.execute("INSERT INTO operation_log(batch_id,song_id,action,new_path,state) VALUES (?,?,'force_archive',?,'committed')",
                                (batch_id, song_id, json.dumps({"seq": song.seq, "title": song.title, "selected_item_ids": [file.item_id for file in song.files],
                                                              "regular_instrument_count": regular_count, "minimum_instruments": self.db.minimum_instruments(song.category_code),
                                                              "minimum_waived": waived, "reason": "用户确认强制正式入库，仅在声部不足时豁免本曲目的最少声部数"}, ensure_ascii=False)))
                    for file_plan in song.files:
                        stage = staging_root / file_plan.output_name
                        shutil.copy2(file_plan.source_path, stage)
                        if sha256_file(stage) != file_plan.source_sha256:
                            raise ValueError("源文件已变化或暂存校验失败，已停止归档")
                        for target in file_plan.targets:
                            target.parent.mkdir(parents=True, exist_ok=True)
                            with target.open("xb") as destination:
                                copied_paths.append(target)
                                with stage.open("rb") as source:
                                    shutil.copyfileobj(source, destination)
                            if sha256_file(target) != file_plan.source_sha256:
                                raise ValueError("输出 PDF 校验失败")
                        self.db.add_score(song_id, file_plan.instrument_code, file_plan.source_path.name,
                                          str(file_plan.source_path), file_plan.source_sha256, file_plan.output_name,
                                          "dual" if file_plan.by_song_path else "instrument_only")
                        archived_item_ids.append(file_plan.item_id)
                    if song.existing_song_id is None and not song.force:
                        saved_ids = {file.item_id for file in song.files}
                        for item in self._items_for_report(batch_id, song.category_code, song.title):
                            fields = json.loads(item["parsed_fields"] or "{}")
                            if item["id"] in saved_ids or fields.get("new_song_confirmation") != {"category_code": song.category_code, "title": song.title}:
                                continue
                            old_fields = dict(fields)
                            fields.pop("new_song_confirmation")
                            fields.update(seq=song.seq, target_song_id=song_id)
                            with self.db.session() as connection:
                                connection.execute("UPDATE import_item SET parsed_fields=? WHERE id=? AND batch_id=?",
                                                   (json.dumps(fields, ensure_ascii=False), item["id"], batch_id))
                                connection.execute("INSERT INTO operation_log(batch_id,song_id,action,old_path,new_path,state) VALUES (?,?,'continue_new_song',?,?,'committed')",
                                    (batch_id, song_id, json.dumps({"source": item["source_path"], "fields": old_fields}, ensure_ascii=False),
                                     json.dumps({"source": item["source_path"], "fields": fields}, ensure_ascii=False)))
                remaining = self.db.remove_import_items_from_draft(batch_id, archived_item_ids)
            return remaining
        except Exception:
            for path in reversed(copied_paths):
                path.unlink(missing_ok=True)
            for old_folder, new_folder in reversed(renamed_folders):
                new_folder.rename(old_folder)
            raise
        finally:
            if staging_root.exists():
                shutil.rmtree(staging_root, ignore_errors=True)

    def _items_for_report(self, batch_id: int, category_code: str, title: str):
        for item in self.db.list_import_items(batch_id):
            if item["status"] != "recognized":
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            if fields.get("category_code") == category_code and fields.get("title") == title:
                yield item

    def _validate_plan(self, plans: list[ArchiveSongPlan]) -> None:
        targets: set[Path] = set()
        for song in plans:
            codes = {file.instrument_code for file in song.files}
            if codes != self.db.archive_eligible_codes(song.category_code, codes, song.existing_song_id, force=song.force):
                raise ValueError("普通分谱不足分类要求的乐器数，不能正式归档")
            for file_plan in song.files:
                if not file_plan.source_path.exists():
                    raise ValueError("源文件不存在，不能归档")
                for target in file_plan.targets:
                    normalized = Path(str(target).casefold())
                    if normalized in targets:
                        raise ValueError("目标路径冲突，不能归档")
                    targets.add(normalized)
                    if target.exists():
                        raise ValueError("目标文件已存在，不能覆盖")

    def _instrument_folder(self, instrument_code: str) -> str:
        with self.db.session() as connection:
            row = connection.execute("SELECT name FROM instrument WHERE code = ?", (instrument_code,)).fetchone()
        if row is None:
            raise ValueError("乐器编号不存在")
        return f"{instrument_code}{row['name']}"

    def score_paths(self, score) -> tuple[Path, ...]:
        count = len(self.db.song_copy_codes(int(score['song_id']))) if score['storage_layout'] != 'instrument_only' else 0
        return self._score_paths(score, self._instrument_folder(score['instrument_code']), count)

    def score_paths_for_rows(self, scores, selected):
        """One caller-owned metadata snapshot; file/hash checks remain per part."""
        scores = tuple(scores)
        copies = {}
        for score in scores:
            if score['storage_layout'] == 'dual':
                copies.setdefault(int(score['song_id']), set()).add(str(score['instrument_code']))
        return {int(score['id']): self._score_paths(score, f"{score['instrument_code']}{score['instrument_name']}",
                   len(copies.get(int(score['song_id']), ()))) for score in scores if int(score['id']) in selected}

    def _score_paths(self, score, instrument_folder, count):
        instrument = self.output_root / "按乐器分类" / instrument_folder / score["output_name"]
        if score["storage_layout"] == "instrument_only":
            return (instrument,)
        folder = format_song_folder(score['category_code'], score['seq'], score['display_title'], count, naming_version=self._numbering_version)
        return (instrument, self.output_root / "按曲目分类" / folder / score["output_name"])
