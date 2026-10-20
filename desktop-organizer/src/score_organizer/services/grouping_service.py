from __future__ import annotations

import json
import re
import hashlib
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from rapidfuzz import fuzz

from score_organizer.domain.filename_parser import suggest_title_without_instrument
from score_organizer.storage.database import Database


def warning_fingerprint(code: str, details: dict[str, object]) -> str:
    """Stable identity shared by ordinary warning refresh and path migration."""
    stable_details = json.dumps(details, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(f"{code}:{stable_details}".encode("utf-8")).hexdigest()


@dataclass(frozen=True)
class DraftGroup:
    category_code: str
    title: str
    item_ids: tuple[int, ...]
    instrument_codes: tuple[str, ...]


class GroupingService:
    SIMILAR_TITLE_THRESHOLD = 78
    LIBRARY_TITLE_THRESHOLD = 75
    VERSION_PATTERN = re.compile(r"(第二版|第2版|第\s*二\s*版|v\s*2|V\s*2|version\s*2)", re.IGNORECASE)

    def __init__(self, db: Database) -> None:
        self.db = db

    def group_batch(self, batch_id: int) -> list[DraftGroup]:
        groups: dict[tuple[str, str], list[dict[str, object]]] = defaultdict(list)
        for item in self.db.list_import_items(batch_id):
            if item["status"] != "recognized":
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            category_code = fields.get("category_code")
            title = fields.get("title")
            instrument_code = fields.get("instrument_code")
            if not category_code or not title or not instrument_code:
                continue
            groups[(str(category_code), str(title))].append(
                {
                    "id": int(item["id"]),
                    "instrument_code": str(instrument_code),
                    "source_path": item["source_path"],
                    "source_sha256": item["source_sha256"],
                }
            )
        return [
            DraftGroup(
                category_code=category_code,
                title=title,
                item_ids=tuple(int(item["id"]) for item in items),
                instrument_codes=tuple(sorted({str(item["instrument_code"]) for item in items})),
            )
            for (category_code, title), items in sorted(groups.items())
        ]

    def refresh_warnings(self, batch_id: int, scan_completeness: bool = False) -> list[dict[str, object]]:
        with self.db.transaction(reuse=True):
            return self._refresh_warnings(batch_id, scan_completeness)

    def _refresh_warnings(self, batch_id: int, scan_completeness: bool) -> list[dict[str, object]]:
        items = self.db.list_import_items(batch_id)
        warnings: list[dict[str, object]] = []
        recognized_by_group: dict[tuple[str, str], list[object]] = defaultdict(list)
        hash_to_paths: dict[str, list[str]] = defaultdict(list)
        archived_scores = self.db.list_archived_scores()
        archived_ids = defaultdict(set)
        for score in archived_scores:
            archived_ids[(str(score["category_code"]), str(score["display_title"]))].add(int(score["song_id"]))
        confirmed_ids = set()
        targets = {}
        archived_keys = set(archived_ids)
        draft_keys = {(str(field.get("category_code") or ""), str(field.get("title") or ""))
                      for item in items if item["status"] == "recognized"
                      for field in [json.loads(item["parsed_fields"] or "{}")]}
        for item in items:
            if item["source_sha256"]:
                hash_to_paths[str(item["source_sha256"])].append(str(item["source_path"]))
                existing_scores = self.db.find_scores_by_hash(str(item["source_sha256"]))
                if existing_scores:
                    warnings.append(
                        self._warning(
                            "duplicate_existing_file",
                            "blocking",
                            {
                                "sha256": str(item["source_sha256"]),
                                "files": [Path(item["source_path"]).name],
                                "source_paths": [str(item["source_path"])],
                                "existing": [
                                    {
                                        "category_code": str(score["category_code"]),
                                        "seq": str(score["seq"]),
                                        "title": str(score["display_title"]),
                                        "instrument_code": str(score["instrument_code"]),
                                        "instrument_name": str(score["instrument_name"]),
                                        "output_name": str(score["output_name"]),
                                        "source_path": str(score["source_path"]),
                                    }
                                    for score in existing_scores
                                ],
                            },
                        )
                    )
            if item["status"] != "recognized":
                continue
            fields = json.loads(item["parsed_fields"] or "{}")
            key = (str(fields.get("category_code") or ""), str(fields.get("title") or ""))
            recognized_by_group[key].append(item)
            confirmation = fields.get("new_song_confirmation")
            if confirmation == {"category_code": key[0], "title": key[1]} and not fields.get("target_song_id") and not fields.get("seq"):
                normalize = lambda value: re.sub(r"\s+", "", value).casefold()
                conflicts = {other for other in archived_keys | draft_keys if other[0] == key[0] and normalize(other[1]) == normalize(key[1])}
                if key not in archived_keys and conflicts == {key}:
                    confirmed_ids.add(int(item["id"]))
            target_id = fields.get("target_song_id")
            if type(target_id) is int and archived_ids[key] == {target_id}:
                if target_id not in targets:
                    targets[target_id] = self.db.get_archived_song(target_id)
                target = targets[target_id]
                if target is not None and str(fields.get("seq") or "") == str(target["seq"]):
                    confirmed_ids.add(int(item["id"]))

        modes = self.db.instrument_archive_modes()
        minimums = {code: self.db.minimum_instruments(code) for code in {key[0] for key in recognized_by_group}}
        for (category_code, title), group_items in recognized_by_group.items():
            instruments: dict[str, list[object]] = defaultdict(list)
            for item in group_items:
                fields = json.loads(item["parsed_fields"] or "{}")
                instruments[str(fields.get("instrument_code") or "")].append(item)
            existing_song = self.db.find_archived_song(category_code, title)
            archived_codes = self.db.archived_instrument_codes(int(existing_song["id"])) if existing_song else set()
            regular = {code for code in instruments if modes.get(code) == "dual"}
            instrument_count = len(regular | (self.db.song_copy_codes(int(existing_song["id"])) if existing_song else set()))
            minimum = minimums[category_code]
            minimum_waived = bool(existing_song and existing_song["minimum_waived"])
            if scan_completeness and regular and instrument_count < minimum:
                warnings.append(
                    self._warning(
                        "insufficient_instruments",
                        "warning" if minimum_waived else "blocking",
                        {
                            "category_code": category_code,
                            "title": title,
                            "instrument_count": instrument_count,
                            "minimum_instruments": minimum,
                            **({"minimum_waived": True} if minimum_waived else {}),
                            "source_paths": [str(item["source_path"]) for code in regular for item in instruments[code]],
                        },
                    )
                )
            for instrument_code, duplicates in instruments.items():
                if instrument_code and len(duplicates) > 1:
                    warnings.append(
                        self._warning(
                            "same_instrument_conflict",
                            "blocking",
                            {
                                "category_code": category_code,
                                "title": title,
                                "instrument_code": instrument_code,
                                "files": [Path(item["source_path"]).name for item in duplicates],
                                "source_paths": [str(item["source_path"]) for item in duplicates],
                            },
                        )
                    )
            if existing_song is not None:
                for instrument_code, instrument_items in instruments.items():
                    if instrument_code and instrument_code in archived_codes:
                        warnings.append(
                            self._warning(
                                "existing_instrument_conflict",
                                "blocking",
                                {
                                    "category_code": category_code,
                                    "title": title,
                                    "seq": str(existing_song["seq"]),
                                    "instrument_code": instrument_code,
                                    "files": [Path(item["source_path"]).name for item in instrument_items],
                                    "source_paths": [str(item["source_path"]) for item in instrument_items],
                                    "existing": [dict(score) for score in self.db.list_archived_scores(int(existing_song["id"])) if score["instrument_code"] == instrument_code],
                                },
                            )
                        )

        suffix_candidates = defaultdict(list)
        with self.db.session() as connection:
            names = {row["code"]: row["name"] for row in connection.execute("SELECT code, name FROM instrument WHERE active=1")}
        for (category_code, title), group_items in recognized_by_group.items():
            for item in group_items:
                if int(item["id"]) in confirmed_ids:
                    continue
                fields = json.loads(item["parsed_fields"] or "{}")
                base = suggest_title_without_instrument(title, names.get(fields.get("instrument_code"), ""))
                if base:
                    suffix_candidates[(category_code, base)].append(item)
        suggested_keys = {}
        for (category_code, base), candidate_items in sorted(suffix_candidates.items()):
            candidate_items = candidate_items + [item for item in recognized_by_group.get((category_code, base), [])
                                                  if int(item["id"]) not in confirmed_ids]
            candidate_items = list({int(item["id"]): item for item in candidate_items}.values())
            titles = sorted({json.loads(item["parsed_fields"])["title"] for item in candidate_items})
            for title in titles:
                suggested_keys[(category_code, title)] = base
            existing = self.db.find_archived_song(category_code, base)
            warnings.append(self._warning("instrument_title_suffix", "blocking", {
                "category_code": category_code, "title": base, "suggested_title": base,
                "original_titles": titles,
                "source_paths": [str(item["source_path"]) for item in candidate_items],
                "item_ids": [int(item["id"]) for item in candidate_items],
                "existing": [dict(score) for score in self.db.list_archived_scores(int(existing["id"]))] if existing else [],
            }))

        group_keys = sorted(recognized_by_group.keys())
        archived_by_group: dict[tuple[str, str], list[object]] = defaultdict(list)
        for score in archived_scores:
            archived_by_group[(str(score["category_code"]), str(score["display_title"]))].append(score)
        for category_code, title in group_keys:
            pending = [item for item in recognized_by_group[(category_code, title)] if int(item["id"]) not in confirmed_ids]
            if not pending:
                continue
            candidates = []
            for (existing_category, existing_title), scores in archived_by_group.items():
                if category_code != existing_category or title == existing_title:
                    continue
                if re.sub(r"\s+", "", title) != re.sub(r"\s+", "", existing_title):
                    left, right = self._normalize_title(title), self._normalize_title(existing_title)
                    similarity = fuzz.ratio(left, right) if left and right else 0
                    if (category_code, title) not in archived_by_group and (similarity >= self.LIBRARY_TITLE_THRESHOLD or (left and right and (left in right or right in left))):
                        candidates.append((similarity, existing_title, scores))
                    continue
                warnings.append(self._warning(
                    "whitespace_title_conflict", "blocking", {
                        "category_code": category_code,
                        "left_title": title,
                        "right_title": existing_title,
                        "source_paths": [str(item["source_path"]) for item in pending],
                        "existing": [dict(score) for score in scores],
                    },
                ))
            if candidates:
                candidates.sort(key=lambda entry: (-entry[0], entry[1]))
                best_score, best_title, _ = candidates[0]
                warnings.append(self._warning("similar_existing_title_candidate", "warning", {
                    "category_code": category_code, "left_title": title, "right_title": best_title,
                    "score": best_score, "source_paths": [str(item["source_path"]) for item in pending],
                    "existing": [dict(score) for _, _, scores in candidates[:5] for score in scores],
                }))
        normalized_titles = {title: self._normalize_title(title) for _, title in group_keys}
        compact_titles = {title: re.sub(r"\s+", "", title) for _, title in group_keys}
        for index, (left_category, left_title) in enumerate(group_keys):
            for right_category, right_title in group_keys[index + 1 :]:
                if left_category != right_category or left_title == right_title:
                    continue
                if suggested_keys.get((left_category, left_title)) is not None and suggested_keys.get((left_category, left_title)) == suggested_keys.get((right_category, right_title)):
                    continue
                left_normalized = normalized_titles[left_title]
                right_normalized = normalized_titles[right_title]
                score = fuzz.ratio(left_normalized, right_normalized)
                contains_title = left_normalized in right_normalized or right_normalized in left_normalized
                whitespace_only = compact_titles[left_title] == compact_titles[right_title]
                if score >= self.SIMILAR_TITLE_THRESHOLD or contains_title:
                    unresolved = [item for key in ((left_category, left_title), (right_category, right_title))
                                  for item in recognized_by_group[key] if int(item["id"]) not in confirmed_ids]
                    if not unresolved:
                        continue
                    existing = [dict(score) for key in ((left_category, left_title), (right_category, right_title))
                                if all(int(item["id"]) in confirmed_ids for item in recognized_by_group[key])
                                for score in archived_by_group.get(key, [])]
                    warnings.append(
                        self._warning(
                            "whitespace_title_conflict" if whitespace_only else "similar_title_candidate",
                            "blocking" if whitespace_only else "warning",
                            {
                                "category_code": left_category,
                                "left_title": left_title,
                                "right_title": right_title,
                                "score": score,
                                "source_paths": [str(item["source_path"]) for item in unresolved],
                                **({"existing": existing} if existing else {}),
                            },
                        )
                    )

        for category_code, title in group_keys:
            pending = [item for item in recognized_by_group[(category_code, title)] if int(item["id"]) not in confirmed_ids]
            if pending and self.VERSION_PATTERN.search(title):
                warnings.append(
                    self._warning(
                        "version_candidate",
                        "warning",
                        {
                            "category_code": category_code,
                            "title": title,
                            "source_paths": [
                                str(item["source_path"]) for item in pending
                            ],
                        },
                    )
                )

        for digest, paths in hash_to_paths.items():
            if len(paths) > 1:
                warnings.append(
                    self._warning(
                        "duplicate_file",
                        "blocking",
                        {
                            "sha256": digest,
                            "files": [Path(path).name for path in paths],
                            "source_paths": paths,
                        },
                    )
                )

        self.db.replace_batch_warnings(batch_id, warnings)
        return warnings

    def _warning(self, code: str, severity: str, details: dict[str, object]) -> dict[str, object]:
        return {
            "code": code,
            "fingerprint": warning_fingerprint(code, details),
            "severity": severity,
            "state": "待处理",
            "details": details,
        }

    def _normalize_title(self, title: str) -> str:
        return re.sub(r"[\s《》（）()_\-·.。:：]+", "", title).casefold()
