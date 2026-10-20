from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from pathlib import Path
import re

from score_organizer.domain.numbering import LEGACY_NAMING_VERSION, normalize_seq, sequence_limit
from score_organizer.domain.score_naming import parse_score_number


class FilenameFormat(str, Enum):
    OLD = "old"
    NEW = "new"
    INVALID = "invalid"


@dataclass(frozen=True)
class ParsedFilename:
    format: FilenameFormat
    instrument_code: str | None
    category_code: str | None
    title: str | None
    seq: str | None = None
    reason: str | None = None


def parse_score_filename(
    filename: str | Path,
    instrument_codes: set[str],
    category_codes: set[str],
    *,
    naming_version: int = LEGACY_NAMING_VERSION,
) -> ParsedFilename:
    sequence_limit(naming_version)
    name = Path(filename).name
    stem = Path(name).stem
    suffix = Path(name).suffix.casefold()
    if suffix != ".pdf":
        return ParsedFilename(FilenameFormat.INVALID, None, None, None, reason="不是PDF文件")

    if re.match(r"^[0-9]{2}[-_]", stem):
        match = re.fullmatch(r"([0-9]{2})([-_])([0-9]{2})\2([1-9][0-9]{3,18})\2(.*)", stem)
        if match is None:
            return ParsedFilename(FilenameFormat.INVALID, None, None, None, reason="扩位编号格式无效")
        instrument_code, category_code, title = match[1], match[3], match[5].strip()
        try:
            number = parse_score_number(
                f"{match[1]}{match[2]}{match[3]}{match[2]}{match[4]}", naming_version=naming_version)
        except ValueError as error:
            return ParsedFilename(FilenameFormat.INVALID, instrument_code, category_code, title or None, reason=str(error))
        if instrument_code not in instrument_codes:
            reason = "乐器编号未定义"
        elif category_code not in category_codes:
            reason = "分类编号未定义"
        elif not _is_valid_title(title):
            reason = "曲名为空"
        else:
            return ParsedFilename(FilenameFormat.NEW, instrument_code, category_code, title, seq=number.seq)
        return ParsedFilename(FilenameFormat.INVALID, instrument_code, category_code, title or None, reason=reason)

    if len(stem) >= 8 and stem[:7].isdigit():
        instrument_code = stem[:2]
        category_code = stem[2:4]
        seq_text = stem[4:7]
        title = stem[7:].strip()
        if instrument_code not in instrument_codes:
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                title or None,
                reason="乐器编号未定义",
            )
        if category_code not in category_codes:
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                title or None,
                reason="分类编号未定义",
            )
        if not _is_valid_title(title):
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                None,
                reason="曲名为空",
            )
        try:
            seq = normalize_seq(seq_text)
        except ValueError:
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                title,
                reason="曲目序号无效",
            )
        return ParsedFilename(
            FilenameFormat.NEW,
            instrument_code,
            category_code,
            title,
            seq=seq,
        )

    if len(stem) >= 5 and stem[:4].isdigit():
        instrument_code = stem[:2]
        category_code = stem[2:4]
        title = stem[4:].strip()
        if instrument_code not in instrument_codes:
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                title or None,
                reason="乐器编号未定义",
            )
        if category_code not in category_codes:
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                title or None,
                reason="分类编号未定义",
            )
        if not _is_valid_title(title):
            return ParsedFilename(
                FilenameFormat.INVALID,
                instrument_code,
                category_code,
                None,
                reason="曲名为空",
            )
        return ParsedFilename(FilenameFormat.OLD, instrument_code, category_code, title)

    return ParsedFilename(FilenameFormat.INVALID, None, None, None, reason="文件名编号无效")


def _is_valid_title(title: str) -> bool:
    return bool(title and title.strip())


def suggest_title_without_instrument(title: str, instrument_name: str) -> str | None:
    if not title or not instrument_name:
        return None
    labels = [re.escape(instrument_name)]
    if instrument_name in {"第一小提琴", "第二小提琴"}:
        number, chinese, roman = ("1", "一", "I") if instrument_name == "第一小提琴" else ("2", "二", "II")
        labels.extend((rf"小提琴[\s_\-]*(?:{number}|{chinese}|{roman})",
                       rf"第{chinese}提琴", rf"{chinese}提琴", "小提琴"))
    label = "(?:" + "|".join(labels) + ")"
    patterns = (rf"(?P<base>.+?)(?:\s*[-_－—:：]\s*|\s+){label}\s*",
                rf"(?P<base>.+?)\s*\({label}\)\s*",
                rf"(?P<base>.+?)\s*（{label}）\s*")
    for pattern in patterns:
        match = re.fullmatch(pattern, title, flags=re.IGNORECASE)
        if match and (base := match.group("base").strip()):
            return base
    return None
