import json

import pytest

from score_organizer.services.correction_service import CorrectionService, RelatedDraftPartsError
from score_organizer.services.import_service import ImportService
from test_force_archive import fixture


def add_related(db, tmp_path, batch):
    paths = []
    for index, name in enumerate(("0201独立 曲目.pdf", "0301独立曲目.pdf", "0402独立曲目.pdf", "0501独立曲目（第二版）.pdf", "0601独立曲目变奏.pdf")):
        path = tmp_path / name
        path.write_bytes(f"%PDF extra {index}".encode())
        paths.append(path)
    assert ImportService(db).import_paths(paths, batch_id=batch) == batch
    return paths


def test_new_song_reports_all_related_draft_ids_without_changing_any_record(tmp_path):
    db, _, _, batch, ids = fixture(tmp_path)
    add_related(db, tmp_path, batch)
    before = [dict(row) for row in db.list_import_items(batch)]
    service = CorrectionService(db)
    with pytest.raises(RelatedDraftPartsError) as caught:
        service.build_plan(batch, ids, new_song=True)
    related = tuple(row["id"] for row in before if json.loads(row["parsed_fields"])["instrument_code"] in {"02", "03"})
    assert caught.value.item_ids == related
    assert [dict(row) for row in db.list_import_items(batch)] == before
    plan = service.build_plan(batch, ids | set(related), "独立曲目", "01", new_song=True)
    assert {row.item_id for row in plan.rows} == ids | set(related)
    service.apply(plan)
    assert [dict(row) for row in db.list_import_items(batch) if row["id"] not in ids | set(related)] == [row for row in before if row["id"] not in ids | set(related)]
    assert db.count_songs() == 0 and db.allocate_next_sequence() == "001"


def test_related_conflict_after_preview_rolls_back_all_corrections(tmp_path):
    db, _, _, batch, ids = fixture(tmp_path)
    service = CorrectionService(db)
    plan = service.build_plan(batch, ids, new_song=True)
    add_related(db, tmp_path, batch)
    before = [dict(row) for row in db.list_import_items(batch)]
    with pytest.raises(RelatedDraftPartsError):
        service.apply(plan)
    assert [dict(row) for row in db.list_import_items(batch)] == before
    assert db.count_songs() == 0


def test_visible_but_unselected_related_parts_are_reported_too(tmp_path):
    db, _, _, batch, ids = fixture(tmp_path, ("01", "02", "03"))
    with pytest.raises(RelatedDraftPartsError) as caught:
        CorrectionService(db).build_plan(batch, {min(ids)}, new_song=True)
    assert set(caught.value.item_ids) == ids - {min(ids)}
