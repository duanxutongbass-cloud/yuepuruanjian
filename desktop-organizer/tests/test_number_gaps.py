import json

import pytest
from PySide6.QtWidgets import QApplication

from score_organizer.domain.numbering import next_global_sequence, SequenceExhaustedError
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file
from score_organizer.storage.database import Database
from score_organizer.ui.main_window import MainWindow


@pytest.fixture
def library(tmp_path):
    db = Database(tmp_path / 'library.db')
    db.initialize()
    return db, ArchiveService(db, tmp_path / 'output')


def imported(db, tmp_path, titles, *, batch=None):
    paths = []
    for index, title in enumerate(titles):
        path = tmp_path / f'0801{title}.pdf'
        path.write_bytes(f'%PDF synthetic {index} {title}'.encode())
        paths.append(path)
    return ImportService(db).import_paths(paths, batch_id=batch), paths


@pytest.mark.parametrize('reserved,expected', [([], '001'), (['007', '001', '003'], '002'),
    (['999', '002'], '001'), (['01', '001', '002', '004'], '003')])
def test_smallest_free_is_global_and_does_not_change_reserved_labels(reserved, expected):
    before = list(reserved)
    assert next_global_sequence(reserved).value == expected
    assert reserved == before


def test_only_full_production_range_is_exhausted():
    reserved = [f'{n:03}' for n in range(1, 1000)]
    with pytest.raises(SequenceExhaustedError):
        next_global_sequence(reserved)
    reserved.remove('450')
    assert next_global_sequence(reserved).value == '450'
    with pytest.raises(ValueError):
        next_global_sequence(['1000'])


def test_all_song_states_remain_reserved_without_mutation(library):
    db, service = library
    for code, seq, state in [('01', '001', 'archived'), ('02', '002', 'merged'),
                              ('03', '004', 'draft'), ('04', '999', 'archived')]:
        db.create_song(code, seq, 'Reserved ' + seq, status=state)
    with db.session() as connection:
        before = [tuple(r) for r in connection.execute('SELECT * FROM song ORDER BY id')]
    assert db.allocate_next_sequence() == '003'
    assert service.next_candidate_number({('01', 'Manual'): '003'}) == 5
    with db.session() as connection:
        assert [tuple(r) for r in connection.execute('SELECT * FROM song ORDER BY id')] == before


def test_multi_group_plan_skips_nonconsecutive_reservations_and_keeps_original_pdfs(library, tmp_path):
    db, service = library
    for seq in ['002', '004', '007']:
        db.create_song('02', seq, 'Original ' + seq, status='archived')
    batch, originals = imported(db, tmp_path, ['新甲', '新乙', '新丙'])
    original_hashes = {p: sha256_file(p) for p in originals}
    plan = service.build_plan(batch)
    assert [p.seq for p in plan] == ['001', '003', '005']
    assert service.commit_plan(batch, plan) == 0
    assert db.allocate_next_sequence() == '006'
    assert {p: sha256_file(p) for p in originals} == original_hashes
    for song in plan:
        for part in song.files:
            assert all(sha256_file(target) == original_hashes[part.source_path] for target in part.targets)
    with db.session() as connection:
        assert [r[0] for r in connection.execute("SELECT seq FROM song WHERE display_title LIKE 'Original %' ORDER BY id")] == ['002', '004', '007']


def test_unselected_manual_number_is_reserved_before_partial_archive(library, tmp_path):
    db, service = library
    db.create_song('02', '002', 'Reserved', status='merged')
    batch, _ = imported(db, tmp_path, ['新曲', '001指定曲'])
    items = db.list_import_items(batch)
    selected = [r['id'] for r in items if json.loads(r['parsed_fields'])['title'] == '新曲']
    plan = service.build_plan(batch, selected)
    assert len(plan) == 1 and plan[0].seq == '003'
    service.commit_plan(batch, plan)
    remaining = service.build_plan(batch)
    assert len(remaining) == 1 and remaining[0].seq == '001'


def test_previews_and_real_archive_share_same_reservations(library, tmp_path):
    db, service = library
    for seq in ['002', '004']:
        db.create_song('02', seq, 'Reserved ' + seq, status='merged')
    batch, _ = imported(db, tmp_path, ['新甲', '003指定曲', '新乙'])
    app = QApplication.instance() or QApplication([])
    window = MainWindow(db)
    try:
        window.current_batch_id = batch
        items = db.list_import_items(batch)
        context = window._build_draft_view_context(items)
        plan = service.build_plan(batch)
        assert context['seq_by_group'] == {(p.category_code, p.title): p.seq for p in plan}
        assert sorted(p.seq for p in plan) == ['001', '003', '005']
        fallback = {'seq_by_group': {('01', 'already'): '001'}}
        assert window._candidate_sequence_for_group(('01', 'other'), fallback) == '005'
        # The preview is backed by the real Qt widgets, not an alternate numbering implementation.
        window.refresh_import_items()
        assert window.score_table.rowCount() == len(items)
        displayed = {window.score_table.item(i, 2).text(): window.score_table.item(i, 4).text()
                     for i in range(window.score_table.rowCount())}
        assert displayed == {p.title: '0801' + p.seq for p in plan}
        window.show(); app.processEvents()
        assert window.grab().save(str(tmp_path / 'number-gap-window.png'))
    finally:
        window.close(); app.processEvents()


def test_full_library_still_previews_existing_work_and_manual_slots(library, tmp_path):
    db, service = library
    for n in range(1, 1000):
        db.create_song('02', f'{n:03}', 'Reserved ' + str(n), status='merged')
    assert service.next_candidate_number({}) == 1000
    batch, originals = imported(db, tmp_path, ['新曲'])
    before = sha256_file(originals[0])
    with pytest.raises(ValueError, match='001.*999'):
        service.build_plan(batch)
    assert sha256_file(originals[0]) == before and not service.output_root.exists()
