from score_organizer.domain.filename_parser import FilenameFormat, parse_score_filename


INSTRUMENTS = {"01", "02", "03", "04", "05", "06", "07"}
CATEGORIES = {"01", "02", "03", "04", "05", "06", "07", "08", "09"}


def test_parse_old_format():
    parsed = parse_score_filename("0101天空之城.pdf", INSTRUMENTS, CATEGORIES)
    assert parsed.format == FilenameFormat.OLD
    assert parsed.instrument_code == "01"
    assert parsed.category_code == "01"
    assert parsed.title == "天空之城"
    assert parsed.seq is None


def test_parse_new_format_before_old_format():
    parsed = parse_score_filename("0201007天空之城.pdf", INSTRUMENTS, CATEGORIES)
    assert parsed.format == FilenameFormat.NEW
    assert parsed.category_code == "01"
    assert parsed.instrument_code == "02"
    assert parsed.seq == "007"
    assert parsed.title == "天空之城"


def test_filename_without_number_waits_for_manual_entry():
    parsed = parse_score_filename("天空之城.pdf", INSTRUMENTS, CATEGORIES)
    assert parsed.format == FilenameFormat.INVALID
    assert parsed.reason == "文件名编号无效"


def test_unknown_category_is_invalid():
    parsed = parse_score_filename("0108天空之城.pdf", INSTRUMENTS, {"01"})
    assert parsed.format == FilenameFormat.INVALID
    assert parsed.reason == "分类编号未定义"


def test_strict_new_format_unknown_instrument_does_not_fall_back_to_old_format():
    parsed = parse_score_filename("0801001天空之城.pdf", INSTRUMENTS, CATEGORIES)
    assert parsed.format == FilenameFormat.INVALID
    assert parsed.category_code == "01"
    assert parsed.instrument_code == "08"
    assert parsed.reason == "乐器编号未定义"


def test_non_pdf_is_invalid():
    parsed = parse_score_filename("0101天空之城.txt", INSTRUMENTS, CATEGORIES)
    assert parsed.format == FilenameFormat.INVALID
    assert parsed.reason == "不是PDF文件"
