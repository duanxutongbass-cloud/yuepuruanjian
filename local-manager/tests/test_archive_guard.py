"""PM regression: enabled concert items must never point at archived pieces."""
from pathlib import Path
import tempfile
import unittest

from score_manager import Catalog, Invalid
from score_manager.archive import export_bundle, restore_archive
from score_manager.validation import sha
from fixtures import legacy_archive, change_manifest, write_pdf


class ArchivePieceGuardTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.archive = self.root / "legacy.zip"
        legacy_archive(self.archive)
        self.catalog = Catalog(self.root / "catalog.sqlite", create=True)
        self.addCleanup(self.catalog.close)
        restore_archive(self.catalog, self.archive, expected_sha256=sha(self.archive.read_bytes()))
        self.catalog.assign_number("legacy-piece", number=998)

    def state(self):
        # Includes every schema-3/local table, number reservation and PDF BLOB.
        return tuple(self.catalog._db.iterdump())

    def remove_freeze(self):
        self.catalog._db.execute("DELETE FROM annotation_concert_snapshots")

    def assert_archive_rejected_unchanged(self):
        before = self.state()
        with self.assertRaisesRegex(Invalid, "Disable or remove enabled concert items"):
            self.catalog.archive_piece("legacy-piece")
        self.assertEqual(before, self.state())

    def assert_rejected_unchanged(self, operation):
        before = self.state()
        with self.assertRaises(Invalid):
            operation()
        self.assertEqual(before, self.state())

    def test_enabled_unfrozen_item_blocks_archive_and_preserves_entire_transaction(self):
        self.remove_freeze()
        self.assert_archive_rejected_unchanged()

    def test_enabled_frozen_item_blocks_archive_without_rewriting_snapshot(self):
        self.assert_archive_rejected_unchanged()
        self.assertEqual("legacy-v1", self.catalog.source_pages("legacy-binding")[0]["versionId"])

    def test_archived_concert_does_not_bypass_enabled_item_guard(self):
        self.remove_freeze()
        self.catalog._db.execute("UPDATE concerts SET isArchived=1")
        self.assert_archive_rejected_unchanged()

    def test_disabled_item_allows_archive_and_keeps_number_reserved(self):
        self.remove_freeze()
        self.catalog._db.execute("UPDATE concert_items SET isEnabled=0")
        self.catalog.archive_piece("legacy-piece")
        self.assertEqual(1, self.catalog.rows("pieces")[0]["isArchived"])
        with self.assertRaises(Invalid):
            self.catalog.add_piece("Cannot reuse", number=998)

    def test_archived_item_allows_archive(self):
        self.remove_freeze()
        self.catalog._db.execute("UPDATE concert_items SET isArchived=1")
        self.catalog.archive_piece("legacy-piece")
        self.assertEqual(1, self.catalog.rows("pieces")[0]["isArchived"])

    def test_validation_rolls_back_other_writes_together_with_invalid_archive(self):
        before = self.state()
        with self.assertRaises(Invalid):
            with self.catalog._transaction():
                self.catalog._db.execute("UPDATE pieces SET notes='must roll back',isArchived=1")
                self.catalog._db.execute("UPDATE local_numbers SET categoryId=NULL")
        self.assertEqual(before, self.state())

    def test_import_rejects_enabled_archived_piece_in_unfrozen_and_frozen_packages(self):
        for frozen in (False, True):
            with self.subTest(frozen=frozen):
                invalid = self.root / f"invalid-{frozen}.zip"
                def mutate(root):
                    root["tables"]["pieces"][0]["isArchived"] = 1
                    if not frozen:
                        root["tables"]["annotation_concert_snapshots"] = []
                change_manifest(self.archive, invalid, mutate)
                with Catalog(self.root / f"target-{frozen}.sqlite", create=True) as target:
                    before = tuple(target._db.iterdump())
                    with self.assertRaises(Invalid):
                        restore_archive(target, invalid, expected_sha256=sha(invalid.read_bytes()))
                    self.assertEqual(before, tuple(target._db.iterdump()))

    def test_export_rejects_preexisting_invalid_library_without_final_file(self):
        self.catalog._db.execute("UPDATE pieces SET isArchived=1")
        before = self.state()
        output = self.root / "invalid-export.zip"
        with self.assertRaises(Invalid):
            export_bundle(self.catalog, output, "legacy-admin")
        self.assertEqual(before, self.state())
        self.assertFalse(output.exists())

    def test_frozen_piece_rejects_unbound_part_addition(self):
        instrument = self.catalog.add_instrument("New synthetic instrument")
        self.assert_rejected_unchanged(lambda: self.catalog.add_part("legacy-piece", instrument))

    def test_frozen_piece_rejects_unbound_movement_addition(self):
        self.assert_rejected_unchanged(lambda: self.catalog.add_movement("legacy-piece", "Not in manifest", 2))

    def test_frozen_piece_rejects_used_instrument_rename_and_archive(self):
        for archived in (False, True):
            with self.subTest(archived=archived):
                self.assert_rejected_unchanged(lambda: self.catalog.edit_dictionary(
                    "instrument", "legacy-instrument", name="Changed name", archived=archived))

    def test_active_part_blocks_instrument_archive_without_frozen_concert(self):
        self.remove_freeze()
        self.assert_rejected_unchanged(lambda: self.catalog.edit_dictionary(
            "instrument", "legacy-instrument", name="Same", archived=True))

    def test_active_member_default_blocks_instrument_archive_without_parts(self):
        instrument = self.catalog.add_instrument("Default-only instrument")
        member = self.catalog.add_member("Default-only member")
        self.catalog._db.execute("UPDATE members SET defaultPartDefinitionId=? WHERE id=?", (instrument, member))
        self.assert_rejected_unchanged(lambda: self.catalog.edit_dictionary(
            "instrument", instrument, name="Same", archived=True))

    def test_unused_instrument_can_be_archived(self):
        instrument = self.catalog.add_instrument("Unused instrument")
        self.catalog.edit_dictionary("instrument", instrument, name="Unused instrument", archived=True)
        self.assertEqual(1, self.catalog._get("part_definitions", instrument)["isArchived"])

    def test_frozen_pdf_rejects_inactive_version_import(self):
        source = write_pdf(self.root, "new-version.pdf", pages=5)
        self.assert_rejected_unchanged(lambda: self.catalog.import_pdf(source, pdf_id="legacy-pdf"))

    def test_frozen_pdf_rejects_activation_even_if_manifest_would_stay_equal(self):
        self.assert_rejected_unchanged(lambda: self.catalog.activate_version("legacy-pdf", "legacy-v1"))

    def test_binding_rejects_archived_owner_piece(self):
        piece = self.catalog.add_piece("Unfrozen piece")
        part = self.catalog.add_part(piece, self.catalog.add_instrument("Unfrozen instrument"))
        self.catalog.archive_piece(piece)
        self.assert_rejected_unchanged(lambda: self.catalog.bind(part, "legacy-pdf", 1, 1))

    def test_frozen_piece_rejects_binding_to_preexisting_unbound_part(self):
        # Valid fixture state: this unused part existed before snapshot capture.
        instrument = self.catalog.add_instrument("Preexisting unbound instrument")
        self.catalog._db.execute("INSERT INTO piece_parts VALUES ('unbound-part','legacy-piece',?,NULL,0)", (instrument,))
        self.assert_rejected_unchanged(lambda: self.catalog.bind("unbound-part", "legacy-pdf", 1, 1))

    def test_archived_part_and_member_default_do_not_block_unused_instrument_archive(self):
        instrument = self.catalog.add_instrument("Historically used instrument")
        piece = self.catalog.add_piece("Historical piece")
        part = self.catalog.add_part(piece, instrument)
        member = self.catalog.add_member("Archived member")
        self.catalog._db.execute("UPDATE piece_parts SET isArchived=1 WHERE id=?", (part,))
        self.catalog._db.execute("UPDATE members SET isArchived=1,defaultPartDefinitionId=? WHERE id=?", (instrument, member))
        self.catalog.edit_dictionary("instrument", instrument, name="Historically used instrument", archived=True)
        self.assertEqual(1, self.catalog._get("part_definitions", instrument)["isArchived"])

    def test_binding_does_not_replace_archived_whole_piece_binding(self):
        piece = self.catalog.add_piece("Unfrozen piece")
        part = self.catalog.add_part(piece, self.catalog.add_instrument("Unfrozen instrument"))
        binding = self.catalog.bind(part, "legacy-pdf", 1, 1)
        self.catalog._db.execute("UPDATE part_score_bindings SET isArchived=1 WHERE id=?", (binding,))
        self.assert_rejected_unchanged(lambda: self.catalog.bind(part, "legacy-pdf", 1, 1))

    def test_unrelated_new_records_and_sidecar_edits_preserve_frozen_snapshot(self):
        frozen = self.catalog.rows("annotation_concert_snapshots")
        category = self.catalog.add_category("33", "Synthetic category")
        self.catalog.edit_dictionary("category", category, name="Renamed category", code="34")
        self.catalog.assign_number("legacy-piece", category, number=999)
        self.catalog.add_member("Unrelated member")
        piece = self.catalog.add_piece("Unrelated piece", category)
        instrument = self.catalog.add_instrument("Unrelated instrument")
        self.catalog.edit_dictionary("instrument", instrument, name="Unrelated renamed")
        movement = self.catalog.add_movement(piece, "Unrelated movement", 0)
        part = self.catalog.add_part(piece, instrument)
        source = self.catalog.import_pdf(write_pdf(self.root, "unrelated.pdf"))
        self.catalog.bind(part, source["scorePdfId"], 1, 1, movement_id=movement)
        self.catalog.activate_version(source["scorePdfId"], source["versionId"])
        self.assertEqual(frozen, self.catalog.rows("annotation_concert_snapshots"))

    def test_import_rejects_archived_instrument_with_active_part_or_member_default(self):
        invalid = self.root / "invalid-instrument.zip"
        change_manifest(self.archive, invalid, lambda root: root["tables"]["part_definitions"][0].update(isArchived=1))
        with Catalog(self.root / "invalid-instrument.sqlite", create=True) as target:
            before = tuple(target._db.iterdump())
            with self.assertRaises(Invalid):
                restore_archive(target, invalid, expected_sha256=sha(invalid.read_bytes()))
            self.assertEqual(before, tuple(target._db.iterdump()))

    def test_public_management_input_bounds_fail_without_mutation(self):
        piece = self.catalog.add_piece("Editable")
        instrument = self.catalog.add_instrument("Editable instrument")
        source = write_pdf(self.root, "input.pdf")
        operations = [
            lambda: self.catalog.add_member("x" * 101),
            lambda: self.catalog.add_member("Role flag", administrator="yes"),
            lambda: self.catalog.add_piece("x" * 201),
            lambda: self.catalog.add_instrument("x" * 101),
            lambda: self.catalog.add_instrument("Family", family="x" * 101),
            lambda: self.catalog.add_instrument("Order", sort_order=2**31),
            lambda: self.catalog.add_instrument("😀" * 51),
            lambda: self.catalog.edit_dictionary("instrument", instrument, name="x" * 101),
            lambda: self.catalog.add_movement(piece, "x" * 201, 0),
            lambda: self.catalog.add_movement(piece, "Movement", 2**31),
            lambda: self.catalog.import_pdf(source, title="x" * 201),
            lambda: self.catalog.import_pdf(source, title=""),
            lambda: self.catalog.import_dictionary_csv("instrument", "id,name,family,sortOrder,isArchived\nfirst,Valid,,0,0\nsecond," + "x" * 101 + ",,1,0\n"),
        ]
        for n, operation in enumerate(operations):
            with self.subTest(operation=n):
                self.assert_rejected_unchanged(operation)

    def test_management_labels_trim_before_unique_checks(self):
        instrument = self.catalog.add_instrument("  Trimmed  ", family=" Family ")
        self.assertEqual("Trimmed", self.catalog._get("part_definitions", instrument)["name"])
        self.assertEqual("Family", self.catalog._get("part_definitions", instrument)["family"])
        import sqlite3
        before = self.state()
        with self.assertRaises(sqlite3.IntegrityError):
            self.catalog.add_instrument("Trimmed")
        self.assertEqual(before, self.state())

    def test_activation_checks_original_bytes_before_changing_pointer(self):
        self.remove_freeze()
        self.catalog._db.execute("UPDATE local_pdf_bytes SET data=? WHERE versionId='legacy-v2'", (b"corrupt",))
        self.assert_rejected_unchanged(lambda: self.catalog.activate_version("legacy-pdf", "legacy-v2"))


if __name__ == "__main__":
    unittest.main()
