from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from score_organizer.services.grouping_service import GroupingService
from score_organizer.storage.database import Database
from score_organizer.domain.score_naming import format_song_folder


@dataclass(frozen=True)
class ScorePreviewRow:
    source_name: str
    instrument_code: str
    preview_name: str
    status: str


@dataclass(frozen=True)
class SongPreviewReport:
    category_code: str
    title: str
    instrument_count: int
    target_folder_preview: str
    blocking_warning_count: int
    warning_count: int
    rows: tuple[ScorePreviewRow, ...]


@dataclass(frozen=True)
class BatchSummaryRow:
    category_code: str
    title: str
    instrument_count: int
    warning_count: int
    status: str


class ReportService:
    def __init__(self, db: Database, *, naming_version: int = 1) -> None:
        self.db = db
        self.naming_version = naming_version

    def build_song_reports(self, batch_id: int, *, refresh_warnings: bool = True) -> list[SongPreviewReport]:
        with self.db.transaction(reuse=True):
            return self._build_song_reports(batch_id, refresh_warnings=refresh_warnings)

    def _build_song_reports(self, batch_id: int, *, refresh_warnings: bool) -> list[SongPreviewReport]:
        scan_completeness = any(w["code"] == "insufficient_instruments" for w in self.db.list_warnings(batch_id))
        if refresh_warnings:
            GroupingService(self.db).refresh_warnings(batch_id, scan_completeness=scan_completeness)
        warnings = self.db.list_warnings(batch_id)
        reports: list[SongPreviewReport] = []
        items = self.db.list_import_items(batch_id)
        items_by_id = {int(item["id"]): item for item in items}
        for group in GroupingService(self.db).group_batch(batch_id):
            rows: list[ScorePreviewRow] = []
            group_items = [items_by_id[item_id] for item_id in group.item_ids]
            for item in group_items:
                fields = json.loads(item["parsed_fields"] or "{}")
                instrument_code = str(fields.get("instrument_code") or "")
                title = str(fields.get("title") or group.title)
                preview_name = f"{instrument_code}{group.category_code}待分配{title}.pdf"
                rows.append(
                    ScorePreviewRow(
                        source_name=Path(item["source_path"]).name,
                        instrument_code=instrument_code,
                        preview_name=preview_name,
                        status=str(item["status"]),
                    )
                )
            paths = {str(item["source_path"]) for item in group_items}
            blocking, total = self._warning_counts_for_group(warnings, group.category_code, group.title, paths)
            existing = self.db.find_archived_song(group.category_code, group.title)
            codes = set(group.instrument_codes)
            if existing:
                codes |= self.db.archived_instrument_codes(int(existing["id"]))
            eligible = self.db.archive_eligible_codes(group.category_code, set(group.instrument_codes), int(existing["id"]) if existing else None)
            modes = self.db.instrument_archive_modes()
            dual_codes = {code for code in eligible if modes.get(code) == "dual"}
            if existing:
                dual_codes |= self.db.song_copy_codes(int(existing["id"]))
            reports.append(
                SongPreviewReport(
                    category_code=group.category_code,
                    title=group.title,
                    instrument_count=len(codes),
                    target_folder_preview=(
                        (format_song_folder(group.category_code, existing['seq'], group.title, len(dual_codes), naming_version=self.naming_version)
                         if existing else f"{group.category_code}待分配{group.title}（{len(dual_codes):02d}）")
                        if dual_codes else ("仅按乐器分类" if eligible else "待补齐")
                    ),
                    blocking_warning_count=blocking,
                    warning_count=total,
                    rows=tuple(rows),
                )
            )
        return reports

    def build_batch_summary(self, batch_id: int) -> list[BatchSummaryRow]:
        rows: list[BatchSummaryRow] = []
        for report in self.build_song_reports(batch_id):
            if report.blocking_warning_count > 0:
                status = "待处理"
            elif report.warning_count > 0:
                status = "待确认"
            else:
                status = "可归组"
            rows.append(
                BatchSummaryRow(
                    category_code=report.category_code,
                    title=report.title,
                    instrument_count=report.instrument_count,
                    warning_count=report.warning_count,
                    status=status,
                )
            )
        return rows

    def _warning_counts_for_group(self, warnings, category_code: str, title: str, paths: set[str]) -> tuple[int, int]:
        blocking = 0
        total = 0
        for warning in warnings:
            details = json.loads(warning["details"] or "{}")
            titles = {details.get("title"), details.get("left_title"), details.get("right_title")}
            source_paths = details.get("source_paths") or []
            related = bool(paths.intersection(source_paths)) if source_paths else (details.get("category_code") == category_code and title in titles)
            if not related:
                continue
            total += 1
            if warning["severity"] == "blocking" and warning["state"] == "待处理":
                blocking += 1
        return blocking, total
