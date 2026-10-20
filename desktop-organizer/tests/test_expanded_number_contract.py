"""Protocol tests plus the live v1 import/write gate. No real user files are used."""
import json

import pytest

from score_organizer.domain.filename_parser import FilenameFormat, parse_score_filename
from score_organizer.domain.numbering import (
    MAX_EXPANDED_SEQUENCE, SequenceExhaustedError, next_global_sequence, normalize_seq,
)
from score_organizer.domain.score_naming import (
    format_score_filename, format_score_number, format_song_folder, parse_score_number,
)
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database


@pytest.mark.parametrize("sequence,number,filename,folder", [
    ("001", "0201001", "02010012001太空漫游.pdf", "010012001太空漫游（07）"),
    ("999", "0201999", "02019992001太空漫游.pdf", "019992001太空漫游（07）"),
    ("1000", "02-01-1000", "02-01-1000-2001太空漫游.pdf", "01-1000-2001太空漫游（07）"),
    ("1001", "02-01-1001", "02-01-1001-2001太空漫游.pdf", "01-1001-2001太空漫游（07）"),
    ("9223372036854775807", "02-01-9223372036854775807",
     "02-01-9223372036854775807-2001太空漫游.pdf", "01-9223372036854775807-2001太空漫游（07）"),
])
def test_canonical_outputs_roundtrip_without_changing_legacy_identity(sequence, number, filename, folder):
    assert format_score_number("02", "01", sequence, naming_version=2) == number
    assert format_score_filename("02", "01", sequence, "2001太空漫游", naming_version=2) == filename
    assert format_song_folder("01", sequence, "2001太空漫游", 7, naming_version=2) == folder
    parsed = parse_score_filename(filename, {"02"}, {"01"}, naming_version=2)
    assert parsed.format == FilenameFormat.NEW
    assert (parsed.seq, parsed.title) == (sequence, "2001太空漫游")
    assert parse_score_number(number, naming_version=2).piece_key == f"sequence:{sequence}"


def test_global_smallest_unused_sequence_keeps_assigned_numbers_exact():
    assert next_global_sequence(["003", "999", "001"], naming_version=2).value == "002"
    assert next_global_sequence(["1000", "009", "999"], naming_version=2).value == "001"
    assert next_global_sequence(["1001", "001"], naming_version=2).value == "002"
    assert parse_score_number("0101001", naming_version=2).piece_key != parse_score_number("01-01-1001", naming_version=2).piece_key
    assert next_global_sequence([str(MAX_EXPANDED_SEQUENCE)], naming_version=2).value == "001"
    assert next_global_sequence([f"{n:03d}" for n in range(1,1000)], naming_version=2).value == "1000"


@pytest.mark.parametrize("value", [True, False, 1.0, None, "", " 1", "+1", "-1", "１", "١", "²", "1\x00", "1.0", "1e3", "0", "000", -1, 0, MAX_EXPANDED_SEQUENCE + 1, "9" * 5000])
def test_invalid_sequences_fail_closed(value):
    with pytest.raises(ValueError):
        normalize_seq(value, naming_version=2)


@pytest.mark.parametrize("version", [0, 3, -1, True, "2", 2.0, None])
def test_unknown_protocol_versions_are_not_treated_as_legacy_or_expanded(version):
    with pytest.raises(ValueError):
        next_global_sequence([], naming_version=version)
    with pytest.raises(ValueError):
        parse_score_filename("0101001曲.pdf", {"01"}, {"01"}, naming_version=version)
    with pytest.raises(ValueError):
        format_score_filename("01", "01", "001", "曲", naming_version=version)


@pytest.mark.parametrize("separator", ["-", "_"])
def test_both_separated_input_styles_preserve_title_and_output_is_hyphen(separator):
    filename = separator.join(("01", "02", "1001", "2001-A_B（第二版）")) + ".PDF"
    parsed = parse_score_filename(filename, {"01"}, {"02"}, naming_version=2)
    assert parsed.format == FilenameFormat.NEW
    assert (parsed.seq, parsed.title, parsed.category_code) == ("1001", "2001-A_B（第二版）", "02")
    assert format_score_filename(parsed.instrument_code, parsed.category_code, parsed.seq, parsed.title, naming_version=2) == "01-02-1001-2001-A_B（第二版）.pdf"
    assert parse_score_number(separator.join(("01", "02", "1001")), naming_version=2).piece_key == "sequence:1001"


@pytest.mark.parametrize("filename", [
    "01-01_1000-曲.pdf", "01_01_1000-曲.pdf", "01-01-01000-曲.pdf", "01-01-001-曲.pdf",
    "01-01-999-曲.pdf", "01-01-1000.pdf", "01-01-1000-.pdf", "01-01-1000-   .pdf",
    "01-01-１０００-曲.pdf", "01-01-+1000-曲.pdf", "01-01-9223372036854775808-曲.pdf",
    "01-01-1000-曲.txt", "08-01-1000-曲.pdf", "01-08-1000-曲.pdf",
])
def test_invalid_expanded_names_never_fall_back_to_unnumbered_import(filename):
    parsed = parse_score_filename(filename, {"01"}, {"01"}, naming_version=2)
    assert parsed.format == FilenameFormat.INVALID
    assert parsed.seq is None


@pytest.mark.parametrize("number", ["01011000", "0101_1000", "01-01_1000", "01-01-01000", "01-01-001", "0101000", "01-01-1000-曲", "01-01-1000\n"])
def test_wire_numbers_have_one_unambiguous_identity(number):
    with pytest.raises(ValueError):
        parse_score_number(number, naming_version=2)


def test_legacy_numeric_titles_are_not_reinterpreted_as_expanded_numbers():
    for version in (1, 2):
        parsed = parse_score_filename("01011000天空之城.pdf", {"01"}, {"01"}, naming_version=version)
        assert (parsed.seq, parsed.title) == ("100", "0天空之城")
        parsed = parse_score_filename("01010012001太空漫游.pdf", {"01"}, {"01"}, naming_version=version)
        assert (parsed.seq, parsed.title) == ("001", "2001太空漫游")
    assert format_song_folder("01", "001", "曲", 100) == "01001曲（100）"


def test_default_production_contract_stays_v1():
    with pytest.raises(ValueError):
        format_score_filename("01", "01", "1000", "曲")
    with pytest.raises(ValueError):
        format_song_folder("01", "1000", "曲", 7)
    with pytest.raises(ValueError):
        parse_score_number("01-01-1000")
    with pytest.raises(SequenceExhaustedError):
        next_global_sequence([f"{n:03d}" for n in range(1, 1000)])


def test_current_importer_records_expanded_files_as_blocked_without_allocating_or_copying(tmp_path):
    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    original = tmp_path / "01-01-1000-2001太空漫游.pdf"
    original.write_bytes(b"%PDF-1.4 synthetic contract fixture")
    before = sha256_file(original)
    batch_id = ImportService(db).import_paths([original])
    item, = db.list_import_items(batch_id)
    fields = json.loads(item["parsed_fields"])
    assert item["status"] == "needs_manual_entry"
    assert fields["seq"] is None
    assert "尚未启用1000" in fields["reason"]
    archive = ArchiveService(db, tmp_path / "output")
    assert archive.build_plan(batch_id) == []
    assert db.count_songs() == db.count_scores() == 0
    assert not (tmp_path / "output").exists()
    assert sha256_file(original) == before
    with db.session() as connection:
        assert connection.execute("PRAGMA user_version").fetchone()[0] == 0
