from __future__ import annotations

from dataclasses import dataclass


MAX_SEQUENCE = 999
LEGACY_NAMING_VERSION = 1
EXPANDED_NAMING_VERSION = 2
# Matches SQLite INTEGER and Kotlin Long. Serialized sequences remain strings.
MAX_EXPANDED_SEQUENCE = 9_223_372_036_854_775_807


@dataclass(frozen=True)
class SequenceCandidate:
    value: str


class SequenceExhaustedError(RuntimeError):
    pass


def sequence_limit(naming_version: int) -> int:
    if type(naming_version) is not int or naming_version not in (LEGACY_NAMING_VERSION, EXPANDED_NAMING_VERSION):
        raise ValueError("不支持的编号格式版本")
    return MAX_SEQUENCE if naming_version == LEGACY_NAMING_VERSION else MAX_EXPANDED_SEQUENCE


def normalize_seq(value: str | int, *, naming_version: int = LEGACY_NAMING_VERSION) -> str:
    limit = sequence_limit(naming_version)
    if type(value) is int:
        number = value
    else:
        if not isinstance(value, str) or not value.isascii() or not value.isdecimal():
            raise ValueError("曲目序号必须是ASCII数字")
        digits = value.lstrip("0") or "0"
        if len(digits) > len(str(limit)):
            raise ValueError(f"曲目序号必须在001到{limit}之间")
        number = int(digits)
    if number < 1 or number > limit:
        raise ValueError(f"曲目序号必须在001到{limit}之间")
    return f"{number:03d}"


def next_global_sequence(used_sequences: list[str], *, naming_version: int = LEGACY_NAMING_VERSION) -> SequenceCandidate:
    limit = sequence_limit(naming_version)
    occupied = {int(normalize_seq(seq, naming_version=naming_version)) for seq in used_sequences}
    # Both protocols obey the user's global smallest genuinely unused number rule.
    candidate = 1
    while candidate in occupied:
        candidate += 1
    if candidate > limit:
        message = (f"曲目序号001到{limit}均已占用，无法继续分配" if naming_version == LEGACY_NAMING_VERSION
                   else f"曲目序号已到{limit}，无法继续分配")
        raise SequenceExhaustedError(message)
    return SequenceCandidate(f"{candidate:03d}")

