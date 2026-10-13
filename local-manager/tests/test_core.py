from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from score_manager import Catalog, Cancelled, Invalid, format_sequence, parse_sequence
from score_manager.pdf import inspect_pdf
from fixtures import pdf_bytes, write_pdf


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = Catalog(self.root / "library.sqlite", create=True)
        self.addCleanup(self.catalog.close)

    def test_global_boundary_generation_across_categories(self):
        a = self.catalog.add_category("17", "User category, not composer")
        b = self.catalog.add_category("03", "Another category")
        for index in range(4):
            self.catalog.add_piece(f"Synthetic {index}", a if index % 2 else b,
                                   number=998 if index == 0 else None)
        self.assertEqual([998, 999, 1000, 1001], [p["number"] for p in self.catalog.numbered_pieces()])
        self.assertEqual(["998", "999", "1000", "1001"], [format_sequence(x) for x in range(998, 1002)])

    def test_numeric_sort_not_text_or_category(self):
        for value in (1001, 998, 1000, 999, 2, 10):
            self.catalog.add_piece(str(value), number=value)
        self.assertEqual([2, 10, 998, 999, 1000, 1001], [p["number"] for p in self.catalog.numbered_pieces()])

    def test_parse_variable_width_and_invalid_numbers(self):
        self.assertEqual(1, parse_sequence("001"))
        self.assertEqual(1001, parse_sequence("1001"))
        for value in ("", "0", "-1", "1.0", "1e3", " 1", "１", "١", "9" * 20):
            with self.subTest(value=value), self.assertRaises(Invalid):
                parse_sequence(value)
        for value in (True, 0, -1, 1.5, 2**63):
            with self.subTest(value=value), self.assertRaises(Invalid):
                format_sequence(value)

    def test_duplicate_global_number_rejected_across_categories(self):
        a, b = self.catalog.add_category("11", "A"), self.catalog.add_category("22", "B")
        self.catalog.add_piece("First", a, number=1000)
        with self.assertRaises(Invalid):
            self.catalog.add_piece("Duplicate", b, number=1000)
        self.assertEqual(1, len(self.catalog.rows("pieces")))

    def test_archive_and_renumber_never_recycle_numbers(self):
        first = self.catalog.add_piece("First", number=998)
        self.catalog.assign_number(first, number=1000)
        self.catalog.archive_piece(first)
        for old in (998, 1000):
            with self.assertRaises(Invalid):
                self.catalog.add_piece("Cannot reuse", number=old)
        self.catalog.add_piece("Next")
        self.assertEqual([1000, 1001], [p["number"] for p in self.catalog.numbered_pieces()])

    def test_same_piece_parts_share_number_and_identity_on_rename(self):
        cat = self.catalog.add_category("01", "自定义类别")
        piece = self.catalog.add_piece("Same music", cat, number=1001)
        instruments = [self.catalog.add_instrument(f"Sound {n}") for n in range(12)]
        parts = [self.catalog.add_part(piece, i) for i in instruments]
        self.catalog.edit_dictionary("category", cat, name="New label", code="92")
        self.catalog.edit_dictionary("instrument", instruments[0], name="Renamed instrument")
        self.assertEqual([1001] * 12, [self.catalog.part_number(p) for p in parts])
        self.assertEqual(cat, self.catalog.rows("local_numbers")[0]["categoryId"])
        self.assertEqual(instruments[0], self.catalog._get("piece_parts", parts[0])["partDefinitionId"])

    def test_dictionary_starts_empty_and_imports_user_ids(self):
        self.assertEqual([], self.catalog.rows("part_definitions"))
        self.assertEqual([], self.catalog.rows("local_categories"))
        ids = self.catalog.import_dictionary_csv("category", 'id,code,name,isArchived\noriginal-id,88,"自定义,类别",0\n,02,Other,0\n')
        self.assertEqual("original-id", ids[0])
        with Catalog(self.root / "copy.sqlite", create=True) as other:
            other.import_dictionary_csv("category", self.catalog.export_dictionary_csv("category"))
            self.assertEqual(self.catalog.rows("local_categories"), other.rows("local_categories"))

    def test_instrument_dictionary_csv_preserves_stable_identity(self):
        self.catalog.import_dictionary_csv("instrument", "id,name,family,sortOrder,isArchived\ninst-1,Custom,Family,0,0\ninst-2,Archived,,1,1\n")
        with Catalog(self.root / "copy.sqlite", create=True) as other:
            other.import_dictionary_csv("instrument", self.catalog.export_dictionary_csv("instrument"))
            self.assertEqual(self.catalog.rows("part_definitions"), other.rows("part_definitions"))

    def test_dictionary_conflict_rolls_back_entire_batch(self):
        self.catalog.add_category("01", "Existing")
        with self.assertRaises(sqlite3.IntegrityError):
            self.catalog.import_dictionary_csv("category", "id,code,name,isArchived\nnew-id,02,First,0\nconflict,01,Second,0\n")
        self.assertEqual(1, len(self.catalog.rows("local_categories")))

    def test_dictionary_bad_codes_headers_and_rows_rejected(self):
        for code in ("1", "100", "ab", "１1", " 1"):
            with self.subTest(code=code), self.assertRaises(Invalid):
                self.catalog.add_category(code, "Label")
        for text in ("id,code,name,name\nx,01,A,B\n", "id,code,name,isArchived\nx,01,A\n", "id,code,name,isArchived\nx,01,A,2\n"):
            with self.subTest(text=text), self.assertRaises(Invalid):
                self.catalog.import_dictionary_csv("category", text)

    def test_archived_dictionary_prevents_new_use_but_keeps_references(self):
        cat = self.catalog.add_category("01", "Category")
        piece = self.catalog.add_piece("Piece", cat)
        self.catalog.edit_dictionary("category", cat, code="01", name="Category", archived=True)
        with self.assertRaises(Invalid):
            self.catalog.add_piece("New", cat)
        self.assertEqual(piece, self.catalog.rows("local_numbers")[0]["pieceId"])

    def test_concurrent_allocations_are_unique(self):
        def add(n):
            with Catalog(self.catalog.path) as c:
                return c.add_piece(f"Concurrent {n}")
        with ThreadPoolExecutor(max_workers=4) as pool:
            ids = list(pool.map(add, range(12)))
        self.assertEqual(12, len(set(ids)))
        self.assertEqual(list(range(1, 13)), [x["number"] for x in self.catalog.numbered_pieces()])

    def test_reopening_preserves_number_reservations(self):
        self.catalog.add_piece("Before restart", number=1000)
        with Catalog(self.catalog.path) as other:
            other.add_piece("After restart")
        self.assertEqual([1000, 1001], [x["number"] for x in self.catalog.numbered_pieces()])

    def test_create_never_overwrites_existing_asset(self):
        path = self.root / "existing"
        path.write_bytes(b"keep me")
        with self.assertRaises(FileExistsError):
            Catalog(path, create=True)
        self.assertEqual(b"keep me", path.read_bytes())

    def test_duplicate_pdf_rejected_by_bytes_not_name(self):
        source = write_pdf(self.root)
        self.catalog.import_pdf(source)
        renamed = self.root / "another-name.pdf"
        renamed.write_bytes(source.read_bytes())
        with self.assertRaises(Invalid):
            self.catalog.import_pdf(renamed)
        self.assertEqual(1, len(self.catalog.rows("score_pdf_versions")))

    def test_pdf_batch_failure_has_no_pseudo_success(self):
        good = write_pdf(self.root)
        bad = self.root / "damaged.pdf"
        bad.write_bytes(b"%PDF-1.7\nnot a valid document")
        with self.assertRaises(Invalid):
            self.catalog.import_pdfs([good, bad])
        self.assertEqual([], self.catalog.rows("score_pdfs"))
        self.assertEqual(0, self.catalog._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])

    def test_pdf_duplicate_in_batch_rolls_back(self):
        source = write_pdf(self.root)
        with self.assertRaises(Invalid):
            self.catalog.import_pdfs([source, source])
        self.assertEqual([], self.catalog.rows("score_pdf_versions"))

    def test_missing_pdf_rolls_back_batch(self):
        with self.assertRaises(FileNotFoundError):
            self.catalog.import_pdfs([write_pdf(self.root), self.root / "missing.pdf"])
        self.assertEqual([], self.catalog.rows("score_pdfs"))

    def test_cancel_after_pdf_rows_inserted_rolls_back_bytes_and_metadata(self):
        def cancel():
            return bool(self.catalog.rows("score_pdf_versions"))
        with self.assertRaises(Cancelled):
            self.catalog.import_pdf(write_pdf(self.root), cancel=cancel)
        self.assertEqual([], self.catalog.rows("score_pdf_versions"))
        self.assertEqual(0, self.catalog._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])

    def test_final_validation_failure_rolls_back(self):
        # Fail final validation after bytes and metadata are staged in SQLite.
        with patch.object(self.catalog, "_validate", side_effect=OSError("synthetic storage failure")):
            with self.assertRaises(OSError):
                self.catalog.import_pdf(write_pdf(self.root))
        self.assertEqual([], self.catalog.rows("score_pdfs"))

    def test_sqlite_rejects_blob_write_without_partial_metadata(self):
        self.catalog._db.execute("CREATE TRIGGER test_abort BEFORE INSERT ON local_pdf_bytes BEGIN SELECT RAISE(ABORT,'synthetic disk write rejection'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            self.catalog.import_pdf(write_pdf(self.root))
        self.assertEqual([], self.catalog.rows("score_pdfs"))
        self.assertEqual([], self.catalog.rows("score_pdf_versions"))

    def test_process_exit_before_commit_recovers_previous_complete_state(self):
        self.catalog.add_piece("Already committed", number=998)
        source = write_pdf(self.root)
        code = """import os, sys
from score_manager import Catalog
with Catalog(sys.argv[1]) as c:
    def cancel():
        if c.rows('score_pdf_versions'):
            os._exit(7)
        return False
    c.import_pdf(sys.argv[2], cancel=cancel)
"""
        result = subprocess.run([sys.executable, "-c", code, str(self.catalog.path), str(source)], capture_output=True, text=True, timeout=60)
        self.assertEqual(7, result.returncode, result.stderr)
        with Catalog(self.catalog.path) as reopened:
            self.assertEqual([], reopened.rows("score_pdfs"))
            self.assertEqual(0, reopened._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])
            self.assertEqual([998], [x["number"] for x in reopened.numbered_pieces()])

    def test_cancellation_while_pdf_worker_running_terminates_child(self):
        calls = 0
        def cancel():
            nonlocal calls
            calls += 1
            return calls > 1
        with self.assertRaises(Cancelled):
            inspect_pdf(pdf_bytes(), cancel=cancel)

    def test_pdf_cancel_before_worker_and_timeout(self):
        with self.assertRaises(Cancelled):
            inspect_pdf(pdf_bytes(), cancel=lambda: True)
        with self.assertRaises(Invalid):
            inspect_pdf(pdf_bytes(), timeout=0)

    def test_pdf_encrypted_empty_and_corrupt_rejected(self):
        for data in (pdf_bytes(encrypted=True), pdf_bytes(pages=0), b"not pdf", pdf_bytes()[:100]):
            with self.subTest(size=len(data)), self.assertRaises(Invalid):
                inspect_pdf(data)

    def test_bindings_keep_source_pages_and_geometry(self):
        piece = self.catalog.add_piece("Piece")
        a, b = [self.catalog.add_part(piece, self.catalog.add_instrument(f"Instrument {n}")) for n in range(2)]
        pdf = self.catalog.import_pdf(write_pdf(self.root, geometry=True))
        binding = self.catalog.bind(a, pdf["scorePdfId"], 2, 3)
        self.catalog.bind(b, pdf["scorePdfId"], 1, 1)
        source = self.catalog.source_pages(binding)
        self.assertEqual([1, 2], [x["sourcePage"] for x in source])
        self.assertEqual(pdf["versionId"], source[0]["versionId"])
        self.assertEqual(90, source[0]["geometry"]["rotation"])
        self.assertEqual([10.0, 20.0, 380.0, 580.0], source[0]["geometry"]["cropBox"])

    def test_invalid_binding_range_duplicate_and_cross_piece_movement(self):
        piece = self.catalog.add_piece("Piece")
        part = self.catalog.add_part(piece, self.catalog.add_instrument("Sound"))
        pdf = self.catalog.import_pdf(write_pdf(self.root))
        for first, last in ((0, 1), (2, 1), (1, 4), (True, 1)):
            with self.subTest(first=first,last=last), self.assertRaises(Invalid):
                self.catalog.bind(part, pdf["scorePdfId"], first, last)
        other = self.catalog.add_piece("Other")
        movement = self.catalog.add_movement(other, "Foreign", 0)
        with self.assertRaises(Invalid):
            self.catalog.bind(part, pdf["scorePdfId"], 1, 1, movement_id=movement)
        self.catalog.bind(part, pdf["scorePdfId"], 1, 3)
        with self.assertRaises(Invalid):
            self.catalog.bind(part, pdf["scorePdfId"], 1, 3)
        movement = self.catalog.add_movement(piece, "Same", 0)
        with self.assertRaises(Invalid):
            self.catalog.bind(part, pdf["scorePdfId"], 1, 1, movement_id=movement)

    def test_version_import_is_inactive_and_activation_checks_existing_ranges(self):
        piece = self.catalog.add_piece("Piece")
        part = self.catalog.add_part(piece, self.catalog.add_instrument("Sound"))
        first = self.catalog.import_pdf(write_pdf(self.root))
        binding = self.catalog.bind(part, first["scorePdfId"], 2, 3)
        second = self.catalog.import_pdf(write_pdf(self.root, "second.pdf", pages=1), pdf_id=first["scorePdfId"])
        self.assertEqual(first["versionId"], self.catalog.source_pages(binding)[0]["versionId"])
        with self.assertRaises(Invalid):
            self.catalog.activate_version(first["scorePdfId"], second["versionId"])
        self.assertEqual(first["versionId"], self.catalog.source_pages(binding)[0]["versionId"])
        self.assertEqual(2, len(self.catalog.rows("score_pdf_versions")))

    def test_explicit_version_activation_and_rollback_keep_source_version_identity(self):
        first = self.catalog.import_pdf(write_pdf(self.root))
        second = self.catalog.import_pdf(write_pdf(self.root, "second.pdf", pages=4), pdf_id=first["scorePdfId"])
        self.catalog.activate_version(first["scorePdfId"], second["versionId"])
        self.assertEqual(second["versionId"], self.catalog.rows("score_pdfs")[0]["activeVersionId"])
        self.catalog.activate_version(first["scorePdfId"], first["versionId"])
        self.assertEqual(first["versionId"], self.catalog.rows("score_pdfs")[0]["activeVersionId"])
        other = self.catalog.import_pdf(write_pdf(self.root, "other.pdf", pages=2))
        with self.assertRaises(Invalid):
            self.catalog.activate_version(first["scorePdfId"], other["versionId"])

    def test_file_audit_detects_missing_or_corrupted_original(self):
        pdf = self.catalog.import_pdf(write_pdf(self.root))
        self.assertEqual(1, self.catalog.audit_files())
        self.catalog._db.execute("UPDATE local_pdf_bytes SET data=?", (b"bad",))
        with self.assertRaises(Invalid):
            self.catalog.audit_files()
        self.catalog._db.execute("DELETE FROM local_pdf_bytes")
        with self.assertRaises(Invalid):
            self.catalog.audit_files()


if __name__ == "__main__":
    unittest.main()
