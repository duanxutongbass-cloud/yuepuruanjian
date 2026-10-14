import io
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from score_manager import Catalog, Cancelled, Invalid
from score_manager.archive import export_bundle, restore_archive
from score_manager.validation import TABLES, REPO, insert_rows, sha
from fixtures import legacy_archive, mutate_zip, change_manifest, write_zip, write_pdf, ink


class ArchiveTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.legacy = self.root / "legacy.zip"
        self.expected = legacy_archive(self.legacy)
        self.catalog = Catalog(self.root / "catalog.sqlite", create=True)
        self.addCleanup(self.catalog.close)

    def restore(self, path=None, **kwargs):
        path = path or self.legacy
        return restore_archive(self.catalog, path, expected_sha256=sha(path.read_bytes()), **kwargs)

    def assert_empty(self):
        self.assertTrue(all(not self.catalog.rows(t) for t in TABLES))
        self.assertEqual(0, self.catalog._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])

    def test_independent_legacy_v1_import_keeps_all_rows_and_no_invented_numbers(self):
        self.restore()
        for table in TABLES:
            self.assertEqual(sorted(self.expected["tables"][table], key=lambda x:x["id"]), self.catalog.rows(table), table)
        self.assertEqual([], self.catalog.rows("local_numbers"))
        source = self.catalog.source_pages("legacy-binding")
        self.assertEqual([1, 2], [x["sourcePage"] for x in source])
        self.assertEqual("legacy-v1", source[0]["versionId"])
        self.assertEqual(90, source[0]["geometry"]["rotation"])

    def test_full_round_trip_keeps_ids_versions_bindings_and_boundary_numbers(self):
        self.restore()
        category = self.catalog.add_category("73", "用户自定义分类")
        self.catalog.assign_number("legacy-piece", category, number=998)
        for n in range(999, 1002):
            self.catalog.add_piece(f"Boundary {n}", category)
        bundle = self.root / "bundle.zip"
        export_bundle(self.catalog, bundle, "legacy-admin")
        with Catalog(self.root / "restored.sqlite", create=True) as target:
            restore_archive(target, bundle, expected_sha256=sha(bundle.read_bytes()))
            for table in TABLES + ("local_categories", "local_numbers", "local_number_history"):
                self.assertEqual(self.catalog.rows(table), target.rows(table), table)
            self.assertEqual(self.catalog.source_pages("legacy-binding"), target.source_pages("legacy-binding"))
            self.assertEqual([998, 999, 1000, 1001], [x["number"] for x in target.numbered_pieces()])
            self.assertEqual(2, target.audit_files())
            with self.assertRaises(Invalid):
                target.add_piece("Duplicate", number=1000)

    def test_renumber_reservations_survive_round_trip(self):
        self.restore()
        self.catalog.assign_number("legacy-piece", number=998)
        self.catalog.assign_number("legacy-piece", number=1000)
        bundle = self.root / "bundle.zip"
        export_bundle(self.catalog, bundle, "legacy-admin")
        with Catalog(self.root / "restored.sqlite", create=True) as target:
            restore_archive(target, bundle, expected_sha256=sha(bundle.read_bytes()))
            with self.assertRaises(Invalid):
                target.add_piece("Reuse old number", number=998)

    def test_embedded_library_passes_existing_repository_verifier(self):
        self.restore()
        self.catalog.assign_number("legacy-piece", number=1000)
        bundle = self.root / "bundle.zip"
        export_bundle(self.catalog, bundle, "legacy-admin")
        with zipfile.ZipFile(bundle) as z:
            standalone = self.root / "library.zip"
            standalone.write_bytes(z.read("library.zip"))
        result = subprocess.run([sys.executable, str(REPO / "tools/Verify-AssetArchive.py"), str(standalone)], capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertTrue(json.loads(result.stdout)["valid"])
        with Catalog(self.root / "legacy-consumer.sqlite", create=True) as target:
            restore_archive(target, standalone, expected_sha256=sha(standalone.read_bytes()))
            self.assertEqual(self.catalog.rows("annotation_concert_snapshots"), target.rows("annotation_concert_snapshots"))
            self.assertEqual([], target.rows("local_numbers"))

    def test_mother_export_excludes_every_personal_layer(self):
        self.restore()
        with self.catalog._transaction():
            insert_rows(self.catalog._db, "annotation_pages", [
                {"id": "admin-private", "versionId": "legacy-v1", "pageIndex": 1, "ownerKey": "legacy-admin", "payload": ink(), "generation": 1, "publishedRevisionId": None},
                {"id": "player-private", "versionId": "legacy-v1", "pageIndex": 1, "ownerKey": "legacy-player", "payload": ink(), "generation": 2, "publishedRevisionId": None}])
        bundle = self.root / "bundle.zip"
        export_bundle(self.catalog, bundle, "legacy-admin")
        with zipfile.ZipFile(bundle) as outer, zipfile.ZipFile(io.BytesIO(outer.read("library.zip"))) as inner:
            root = json.loads(inner.read("manifest.json"))
        self.assertEqual(self.expected["tables"]["annotation_pages"], root["tables"]["annotation_pages"])
        self.assertEqual(3, len(self.catalog.rows("annotation_pages")))
        self.assertEqual(self.expected["tables"]["annotation_revisions"], root["tables"]["annotation_revisions"])
        self.assertEqual(self.expected["tables"]["annotation_concert_snapshots"], root["tables"]["annotation_concert_snapshots"])

    def test_nonadmin_cannot_export_mother_library(self):
        self.restore()
        output = self.root / "denied.zip"
        with self.assertRaises(Invalid):
            export_bundle(self.catalog, output, "legacy-player")
        self.assertFalse(output.exists())

    def test_personal_package_is_not_imported_as_mother_library(self):
        wrong = self.root / "personal.zip"
        change_manifest(self.legacy, wrong, lambda r:r.update(kind="PERSONAL"))
        with self.assertRaises(Invalid):
            self.restore(wrong)
        self.assert_empty()

    def test_private_ink_in_mother_package_is_rejected(self):
        wrong = self.root / "private.zip"
        change_manifest(self.legacy, wrong, lambda r:r["tables"]["annotation_pages"][0].update(ownerKey="legacy-player", publishedRevisionId=None))
        with self.assertRaises(Invalid):
            self.restore(wrong)
        self.assert_empty()

    def test_existing_workbench_is_never_overwritten(self):
        original = self.catalog.add_piece("Keep existing")
        with self.assertRaises(Invalid):
            self.restore()
        self.assertEqual([original], [x["id"] for x in self.catalog.rows("pieces")])

    def test_changed_source_hash_is_rejected(self):
        with self.assertRaises(Invalid):
            restore_archive(self.catalog, self.legacy, expected_sha256="0" * 64)
        self.assert_empty()

    def test_unknown_archive_versions_fields_and_types_rejected(self):
        mutations = [lambda r:r.update(formatVersion=2), lambda r:r.update(formatVersion=True),
                     lambda r:r.update(databaseVersion=4), lambda r:r.update(extra="unrecognized"),
                     lambda r:r["tables"]["pieces"][0].update(number=1000),
                     lambda r:r["tables"]["pieces"][0].update(isArchived=True)]
        for n, mutation in enumerate(mutations):
            with self.subTest(n=n):
                wrong = self.root / f"wrong-{n}.zip"
                change_manifest(self.legacy, wrong, mutation)
                with self.assertRaises(Invalid):
                    self.restore(wrong)
                self.assert_empty()

    def test_bad_identity_version_binding_and_frozen_manifest_rejected(self):
        mutations = [lambda r:r["tables"]["pieces"][0].update(id="forbidden:id"),
                     lambda r:r["tables"]["score_pdf_versions"][0].update(pageCount=2),
                     lambda r:r["tables"]["score_pdfs"][0].update(activeVersionId="legacy-v2"),
                     lambda r:r["tables"]["part_score_bindings"][0].update(endPage=4),
                     lambda r:r["tables"]["annotation_concert_snapshots"][0].update(manifest="rewritten"),
                     lambda r:r["tables"]["annotation_pages"][0].update(pageIndex=3)]
        for n, mutation in enumerate(mutations):
            with self.subTest(n=n):
                wrong = self.root / f"bad-model-{n}.zip"
                change_manifest(self.legacy, wrong, mutation)
                with self.assertRaises((Invalid, sqlite3.IntegrityError)):
                    self.restore(wrong)
                self.assert_empty()

    def test_frozen_manifest_prevents_silent_active_version_change(self):
        self.restore()
        with self.assertRaises(Invalid):
            self.catalog.activate_version("legacy-pdf", "legacy-v2")
        self.assertEqual("legacy-v1", self.catalog.rows("score_pdfs")[0]["activeVersionId"])
        self.assertEqual(self.expected["tables"]["annotation_concert_snapshots"], self.catalog.rows("annotation_concert_snapshots"))

    def test_corrupt_pdf_missing_pdf_unknown_entry_rejected(self):
        pdf_entry = self.expected["files"][0]["entry"]
        changes = [lambda e:e.update({pdf_entry:b"broken"}), lambda e:e.pop(pdf_entry), lambda e:e.update({"../../escape.pdf":b"extra"})]
        for n, change in enumerate(changes):
            with self.subTest(n=n):
                wrong = self.root / f"corrupt-{n}.zip"
                mutate_zip(self.legacy, wrong, change)
                with self.assertRaises(Invalid):
                    self.restore(wrong)
                self.assert_empty()

    def test_duplicate_zip_and_json_fields_rejected(self):
        duplicate = self.root / "duplicate.zip"
        with zipfile.ZipFile(self.legacy) as z:
            entries = [(x,z.read(x)) for x in z.namelist()]
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            write_zip(duplicate, entries + [entries[0]])
        with self.assertRaises(Invalid):
            self.restore(duplicate)
        duplicate_json = self.root / "duplicate-json.zip"
        mutate_zip(self.legacy, duplicate_json, lambda e:e.update({"manifest.json": e["manifest.json"].replace(b'"formatVersion": 1', b'"formatVersion": 1, "formatVersion": 1')}))
        with self.assertRaises(Invalid):
            self.restore(duplicate_json)
        self.assert_empty()

    def test_sidecar_wrong_hash_unknown_version_and_duplicate_number_rejected(self):
        self.restore()
        self.catalog.assign_number("legacy-piece", number=998)
        other = self.catalog.add_piece("Other", number=999)
        original = self.root / "bundle.zip"
        export_bundle(self.catalog, original, "legacy-admin")
        mutations = [lambda r:r.update(librarySha256="0" * 64), lambda r:r.update(formatVersion=2),
                     lambda r:r["numbers"].append({"pieceId":other,"number":998,"categoryId":None}),
                     lambda r:r["numberHistory"].append({"pieceId":other,"number":998}),
                     lambda r:r["numbers"][0].update(categoryId="missing"),
                     lambda r:r["numbers"][0].update(number=True)]
        for n, mutate in enumerate(mutations):
            with self.subTest(n=n):
                wrong = self.root / f"bad-sidecar-{n}.zip"
                def change(entries):
                    root = json.loads(entries["catalog.json"])
                    mutate(root)
                    entries["catalog.json"] = json.dumps(root).encode()
                mutate_zip(original, wrong, change)
                with Catalog(self.root / f"rejected-{n}.sqlite", create=True) as target:
                    with self.assertRaises((Invalid, sqlite3.IntegrityError)):
                        restore_archive(target, wrong, expected_sha256=sha(wrong.read_bytes()))
                    self.assertTrue(all(not target.rows(t) for t in TABLES))
                    self.assertEqual(0,target._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])

    def test_restore_cancellation_after_first_pdf_has_no_partial_assets(self):
        def cancel():
            return self.catalog._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0] > 0
        with self.assertRaises(Cancelled):
            self.restore(cancel=cancel)
        self.assert_empty()
        self.restore()  # Retry succeeds with original identities.

    def test_export_cancellation_and_publish_failure_leave_no_final_file(self):
        self.restore()
        for mode in ("cancel", "io"):
            output = self.root / f"{mode}.zip"
            if mode == "cancel":
                with self.assertRaises(Cancelled):
                    export_bundle(self.catalog, output, "legacy-admin", cancel=lambda:True)
            else:
                with patch("score_manager.archive.os.link", side_effect=OSError("synthetic publication failure")):
                    with self.assertRaises(OSError):
                        export_bundle(self.catalog, output, "legacy-admin")
            self.assertFalse(output.exists())
            self.assertEqual([], list(self.root.glob(".score-export-*")))

    def test_export_refuses_existing_target(self):
        self.restore()
        output = self.root / "keep.zip"
        output.write_bytes(b"existing asset")
        with self.assertRaises(Invalid):
            export_bundle(self.catalog, output, "legacy-admin")
        self.assertEqual(b"existing asset", output.read_bytes())


if __name__ == "__main__":
    unittest.main()
