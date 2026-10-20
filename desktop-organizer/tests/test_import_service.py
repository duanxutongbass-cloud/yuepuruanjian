import hashlib

from score_organizer.services.import_service import ImportService
from score_organizer.storage.database import Database


def test_import_service_saves_batch_items_and_hash_without_changing_source(tmp_path):
    pdf = tmp_path / "0101天空之城.pdf"
    original_bytes = b"%PDF-1.4 fake score bytes"
    pdf.write_bytes(original_bytes)
    no_number = tmp_path / "天空之城.pdf"
    no_number.write_bytes(b"%PDF-1.4 needs manual entry")
    text_file = tmp_path / "0101天空之城.txt"
    text_file.write_text("not a pdf", encoding="utf-8")

    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths([pdf, no_number, text_file])

    items = db.list_import_items(batch_id)
    assert [item["status"] for item in items] == [
        "recognized",
        "needs_manual_entry",
        "skipped",
    ]
    assert items[0]["source_sha256"] == hashlib.sha256(original_bytes).hexdigest()
    assert pdf.read_bytes() == original_bytes


def test_import_service_imports_pdf_files_from_folder_recursively(tmp_path):
    root_pdf = tmp_path / "0101天空之城.pdf"
    nested = tmp_path / "nested"
    nested.mkdir()
    nested_pdf = nested / "0201天空之城.pdf"
    ignored = nested / "0101天空之城.txt"
    root_pdf.write_bytes(b"%PDF root")
    nested_pdf.write_bytes(b"%PDF nested")
    ignored.write_text("not imported from folder", encoding="utf-8")

    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    batch_id = ImportService(db).import_paths([tmp_path])

    items = db.list_import_items(batch_id)
    assert len(items) == 2
    assert {item["source_path"] for item in items} == {str(root_pdf), str(nested_pdf)}
    assert all(item["status"] == "recognized" for item in items)


def test_import_service_can_append_to_existing_draft_batch(tmp_path):
    first = tmp_path / "0101天空之城.pdf"
    second = tmp_path / "0201天空之城.pdf"
    first.write_bytes(b"%PDF first")
    second.write_bytes(b"%PDF second")

    db = Database(tmp_path / "scores.sqlite3")
    db.initialize()
    service = ImportService(db)

    batch_id = service.import_paths([first])
    same_batch_id = service.import_paths([second], batch_id=batch_id)

    items = db.list_import_items(batch_id)
    assert same_batch_id == batch_id
    assert [item["source_path"] for item in items] == [str(first), str(second)]
