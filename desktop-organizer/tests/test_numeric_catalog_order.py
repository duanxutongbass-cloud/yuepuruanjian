"""Read paths only: expanded test connections never enable production startup."""
import pytest
from PySide6.QtWidgets import QApplication, QComboBox, QDialog, QLineEdit, QTableWidget

from score_organizer.domain.numbering import MAX_EXPANDED_SEQUENCE, SequenceExhaustedError
from score_organizer.domain.score_naming import format_score_filename, format_song_folder
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.program_service import ProgramService
from score_organizer.storage.database import Database, UnsupportedDatabaseVersion
from score_organizer.ui.main_window import MainWindow
from score_organizer.ui.program_dialog import ProgramDialog
from test_expanded_archive_service import ExpandedCandidateDatabase
from test_library_upgrade_snapshot import make_library, contents, db_state


@pytest.fixture
def library(tmp_path):
    old, old_root, originals = make_library(tmp_path)
    manifest = prepare_relocated_library(old.path, tmp_path / 'candidate')
    db = ExpandedCandidateDatabase(manifest.parent / 'runtime/library.sqlite3')
    root = manifest.parent / 'runtime/files'
    numbers = ('10000', '999', str(MAX_EXPANDED_SEQUENCE), '007', '1000', '9999', str(MAX_EXPANDED_SEQUENCE - 1))
    ids = {}
    for index, seq in enumerate(numbers):
        category = '01' if seq in ('9999', '10000') else ('02' if index % 2 else '03')
        title = '同曲' if seq in ('9999', '10000') else f'曲目{seq}'
        song_id = db.create_song(category, seq, title, status='archived')
        ids[seq] = song_id
        for code in ('03', '02'):
            name = format_score_filename(code, category, seq, title, naming_version=2)
            original = tmp_path / name
            original.write_bytes(b'%PDF synthetic duplicate content\n%%EOF')
            originals.append(original)
            for target in (root / '按乐器分类' / ArchiveService(db, root)._instrument_folder(code) / name,
                           root / '按曲目分类' / format_song_folder(category, seq, title, 2, naming_version=2) / name):
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(original.read_bytes())
            db.add_score(song_id, code, name, str(original), sha256_file(original), name)
    expected = ['001', *sorted(numbers, key=int)]
    before = (db_state(old.path), contents(old_root), db_state(db.path), contents(root),
              {p: p.read_bytes() for p in originals})
    yield db, root, ids, expected
    assert (db_state(old.path), contents(old_root), db_state(db.path), contents(root),
            {p: p.read_bytes() for p in originals}) == before


def test_archived_rows_numeric_across_categories_and_instrument_ties(library):
    db, _, ids, expected = library
    rows = db.list_archived_scores()
    assert list(dict.fromkeys(row['seq'] for row in rows)) == expected
    for seq, song in ids.items():
        assert [(r['seq'], r['instrument_code']) for r in db.list_archived_scores(song)] == [(seq, '02'), (seq, '03')]


def test_hash_matches_keep_numeric_order_and_stable_score_ids(library):
    db, _, _, expected = library
    digest = db.list_archived_scores()[-1]['source_sha256']
    rows = db.find_scores_by_hash(digest)
    assert list(dict.fromkeys(row['seq'] for row in rows)) == expected[1:]
    assert [(int(r['seq']), r['instrument_code'], r['id']) for r in rows] == sorted(
        (int(r['seq']), r['instrument_code'], r['id']) for r in rows)


def test_legacy_hash_results_keep_null_drafts_exclude_merged_and_preserve_id_ties(tmp_path):
    db = Database(tmp_path / 'legacy.sqlite3')
    db.initialize()
    expected = []
    for seq, status in [('999', 'archived'), ('002', 'merged'), (None, 'draft'), ('001', 'archived')]:
        song = db.create_song('01', seq, status, status=status)
        for _ in range(2):
            score = db.add_score(song, '02', 'source.pdf', str(tmp_path / 'source.pdf'), 'a' * 64, 'output.pdf')
            if status != 'merged':
                expected.append((seq, score))
    before = db_state(db.path)
    rows = db.find_scores_by_hash('a' * 64)
    assert [(row['seq'], row['id']) for row in rows] == sorted(expected, key=lambda row: (int(row[0] or '-1'), row[1]))
    assert [row['seq'] for row in db.list_archived_scores()] == ['001', '001', '999', '999']
    assert db_state(db.path) == before


def test_program_catalog_sorts_even_when_source_rows_reversed(library, monkeypatch):
    db, root, _, expected = library
    rows = db.list_archived_scores()
    monkeypatch.setattr(db, 'list_archived_scores', lambda: list(reversed(rows)))
    catalog = ProgramService(db, root).catalog()
    assert [song['seq'] for song in catalog] == expected
    assert all(song['instruments'] == {'02', '03'} for song in catalog[1:])


def test_catalog_dialog_and_filters_preserve_numeric_display(library, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, _, expected = library
    window = MainWindow(db)
    def inspect(dialog):
        table = dialog.findChild(QTableWidget)
        assert [table.item(row, 1).text() for row in range(table.rowCount())] == expected
        category = dialog.findChild(QComboBox, 'catalog_category_filter')
        category.setCurrentIndex(category.findData('01'))
        visible = [table.item(row, 1).text() for row in range(table.rowCount()) if not table.isRowHidden(row)]
        assert visible == ['001', '9999', '10000']
        dialog.findChild(QLineEdit).setText('10000')
        assert [table.item(row, 1).text() for row in range(table.rowCount()) if not table.isRowHidden(row)] == ['10000']
        return 0
    monkeypatch.setattr(QDialog, 'exec', inspect)
    try:
        window.show_archived_catalog()
    finally:
        window.close()
        app.processEvents()


def test_program_selection_numeric_but_concert_order_remains_user_defined(library):
    app = QApplication.instance() or QApplication([])
    db, _, _, expected = library
    dialog = ProgramDialog(db)
    try:
        assert [dialog.results.item(row, 0).text() for row in range(dialog.results.rowCount())] == expected
        for seq in (str(MAX_EXPANDED_SEQUENCE), '007'):
            row = expected.index(seq)
            dialog.results.clearSelection()
            dialog.results.selectRow(row)
            dialog.add_selected()
        dialog.program.cellWidget(0, 0).setValue(2)
        dialog.program.cellWidget(1, 0).setValue(8)
        dialog.sort_orders()
        assert [(e['order'], e['seq']) for e in dialog.entries] == [(2, str(MAX_EXPANDED_SEQUENCE)), (8, '007')]
    finally:
        dialog.close()
        app.processEvents()


def test_merge_comparison_keeps_instrument_groups_then_numeric_sequence(library, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, ids, _ = library
    window = MainWindow(db)
    def inspect(dialog):
        table = dialog.findChild(QTableWidget, 'merge_parts')
        assert [(table.item(row, 1).text().split()[0], table.item(row, 0).text())
                for row in range(table.rowCount())] == [('02', '9999'), ('02', '10000'), ('03', '9999'), ('03', '10000')]
        return 0
    monkeypatch.setattr(QDialog, 'exec', inspect)
    try:
        assert not window.merge_archived_song(ids['10000'])
    finally:
        window.close()
        app.processEvents()


@pytest.mark.parametrize('seqs', [('10000', '999', '007', '1000'), ('003', '001', '002'), ('', '1000', '999')])
def test_warning_comparison_numbers_are_numeric_without_rewriting_labels(seqs):
    details = dict(left_title='同 曲', right_title='同曲', existing=[dict(seq=s) for s in seqs])
    text = MainWindow._warning_details(None, details)
    expected = sorted(set(seqs), key=lambda s: int(s or '-1'))
    assert text.endswith('；对照已入库编号：' + '、'.join(expected))


def test_read_sorting_keeps_reserved_numbers_and_fills_smallest_gap(library):
    db, _, _, _ = library
    assert db.allocate_next_sequence() == "002"
    with pytest.raises(UnsupportedDatabaseVersion):
        Database(db.path).initialize()
