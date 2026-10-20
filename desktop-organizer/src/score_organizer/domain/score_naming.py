"""Numbering wire/file contract. Production callers keep v1 until migration is ready."""
from __future__ import annotations

from dataclasses import dataclass
import re

from score_organizer.domain.numbering import LEGACY_NAMING_VERSION, normalize_seq, sequence_limit


@dataclass(frozen=True)
class ScoreNumber:
    instrument_code: str
    category_code: str
    seq: str

    @property
    def piece_key(self) -> str:
        return f"sequence:{self.seq}"


def _code(value: str) -> str:
    if not isinstance(value, str) or re.fullmatch(r"[0-9]{2}", value) is None:
        raise ValueError("乐器和分类编号必须是两位ASCII数字")
    return value


def format_score_number(instrument_code: str, category_code: str, seq: str | int, *,
                        naming_version: int = LEGACY_NAMING_VERSION) -> str:
    instrument, category = _code(instrument_code), _code(category_code)
    sequence = normalize_seq(seq, naming_version=naming_version)
    if len(sequence) == 3:
        return f"{instrument}{category}{sequence}"
    return f"{instrument}-{category}-{sequence}"


def parse_score_number(value: str, *, naming_version: int = LEGACY_NAMING_VERSION) -> ScoreNumber:
    sequence_limit(naming_version)
    if not isinstance(value, str):
        raise ValueError("乐谱编号无效")
    if re.fullmatch(r"[0-9]{7}", value):
        instrument, category, sequence = value[:2], value[2:4], value[4:]
    else:
        # Consistent delimiters, canonical 1000+ only; never take the final 3 digits.
        match = re.fullmatch(r"([0-9]{2})([-_])([0-9]{2})\2([1-9][0-9]{3,18})", value)
        if match is None:
            raise ValueError("乐谱编号无效")
        instrument, category, sequence = match[1], match[3], match[4]
        if naming_version == LEGACY_NAMING_VERSION:
            raise ValueError("当前曲库尚未启用1000及以上编号，请完成兼容升级后导入")
    normalized = normalize_seq(sequence, naming_version=naming_version)
    if normalized != sequence:
        raise ValueError("曲目序号格式无效")
    return ScoreNumber(instrument, category, normalized)


def format_score_filename(instrument_code: str, category_code: str, seq: str | int, title: str, *,
                          naming_version: int = LEGACY_NAMING_VERSION) -> str:
    number = format_score_number(instrument_code, category_code, seq, naming_version=naming_version)
    if not isinstance(title, str) or not title.strip():
        raise ValueError("曲名为空")
    # Do not normalize existing titles or reinterpret digit-leading legacy titles.
    separator = "-" if "-" in number else ""
    return f"{number}{separator}{title}.pdf"


def format_song_folder(category_code: str, seq: str | int, title: str, instrument_count: int, *,
                       naming_version: int = LEGACY_NAMING_VERSION) -> str:
    category = _code(category_code)
    sequence = normalize_seq(seq, naming_version=naming_version)
    if not isinstance(title, str) or not title.strip():
        raise ValueError("曲名为空")
    if type(instrument_count) is not int or instrument_count < 0:
        raise ValueError("独立乐器数量必须是非负整数")
    prefix = f"{category}{sequence}" if len(sequence) == 3 else f"{category}-{sequence}-"
    return f"{prefix}{title}（{instrument_count:02d}）"
