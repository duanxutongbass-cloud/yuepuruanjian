from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from score_manager import Catalog, Cancelled, Invalid
from score_manager.archive import restore_archive
from score_manager.validation import json_bytes, sha
from score_manager.workflow import Workflow, export_committed_bundle
from fixtures import legacy_archive, write_pdf, pdf_bytes


class WorkflowTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.live = self.root / "live.sqlite"
        self.original = self.root / "original.zip"
        legacy_archive(self.original)
        with Catalog(self.live, create=True) as c:
            restore_archive(c, self.original, expected_sha256=sha(self.original.read_bytes()))
            c.assign_number("legacy-piece", number=998)
        self.workflow = Workflow(self.live)

    def state(self, path=None):
        with Catalog(path or self.live) as c:
            return tuple(c._db.iterdump())

    def staged_piece(self):
        draft = self.workflow.start()["draftId"]
        source = write_pdf(self.root, "new-source.pdf", pages=5)
        pdf = self.workflow.edit(draft, "import_pdf", {"path": str(source)}, request_id="pdf")["result"]
        piece = self.workflow.edit(draft, "add_piece", {"title": "Synthetic new piece"}, request_id="piece")["result"]
        part = self.workflow.edit(draft, "add_part", {"piece_id": piece, "instrument_id": "legacy-instrument"}, request_id="part")["result"]
        self.workflow.edit(draft, "bind", {"part_id": part, "pdf_id": pdf["scorePdfId"], "start_page": 2, "end_page": 3}, request_id="binding")
        return draft, source, piece, pdf

    def review(self, draft):
        return self.workflow.review(draft, "legacy-player")

    def confirm(self, draft, review, **kwargs):
        return self.workflow.confirm(draft, "legacy-player", review["confirmationHash"], **kwargs)

    def test_draft_operations_and_review_do_not_write_live_or_preview_files(self):
        before = self.state()
        draft, _, piece, _ = self.staged_piece()
        self.assertEqual(before, self.state())
        live_bytes = self.live.read_bytes()
        draft_bytes = self.workflow.draft_path(draft).read_bytes()
        staged_state = self.state(self.workflow.draft_path(draft))
        reviewed = self.review(draft)
        self.assertFalse(reviewed["committed"])
        self.assertEqual(live_bytes, self.live.read_bytes())
        self.assertEqual(draft_bytes, self.workflow.draft_path(draft).read_bytes())
        self.assertEqual(staged_state, self.state(self.workflow.draft_path(draft)))
        item = next(p for p in reviewed["memberPreview"]["pieces"] if p["pieceId"] == piece)
        self.assertEqual(999, item["number"])
        self.assertEqual([1, 2], item["visibleParts"][0]["segments"][0]["sourcePages"])
        for field in ("managedFilePath", "originalUri", "fileHash", "pageCount", "data"):
            self.assertNotIn('"' + field + '"', json.dumps(reviewed))

    def test_explicit_confirmation_saves_original_draft_ids_and_repeat_is_idempotent(self):
        draft, _, piece, pdf = self.staged_piece()
        reviewed = self.review(draft)
        result = self.confirm(draft, reviewed)
        after = self.state()
        self.assertTrue(result["committed"])
        self.assertEqual(result, self.confirm(draft, reviewed))
        self.assertEqual(after, self.state())
        self.assertEqual(result, self.workflow.status(draft))
        with Catalog(self.live) as c:
            self.assertEqual(piece, next(p for p in c.numbered_pieces() if p["number"] == 999)["id"])
            self.assertIn(pdf["versionId"], [x["id"] for x in c.rows("score_pdf_versions")])
            self.assertEqual(3, len(c.rows("score_pdf_versions")))
        with self.assertRaises(Invalid):
            self.workflow.cancel(draft)
        with self.assertRaises(Invalid):
            self.workflow.edit(draft, "add_piece", {"title": "Too late"}, request_id="late")

    def test_duplicate_operation_request_replays_without_duplicate_import(self):
        draft = self.workflow.start()["draftId"]
        args = {"path": str(write_pdf(self.root))}
        first = self.workflow.edit(draft, "import_pdf", args, request_id="same")
        before = self.state(self.workflow.draft_path(draft))
        second = self.workflow.edit(draft, "import_pdf", args, request_id="same")
        self.assertEqual(first["result"], second["result"])
        self.assertTrue(second["replayed"])
        self.assertEqual(before, self.state(self.workflow.draft_path(draft)))
        with self.assertRaises(Invalid):
            self.workflow.edit(draft, "import_pdf", {**args, "title": "Changed"}, request_id="same")

    def test_cancel_clears_staged_pdf_references_and_never_changes_live(self):
        before = self.state()
        draft, _, _, _ = self.staged_piece()
        result = self.workflow.cancel(draft)
        self.assertEqual("CANCELLED", result["state"])
        self.assertEqual(before, self.state())
        self.assertEqual(result, self.workflow.cancel(draft))
        with Catalog(self.workflow.draft_path(draft)) as c:
            self.assertEqual(0, c._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0])
        with self.assertRaises(Invalid):
            self.review(draft)
        with self.assertRaises(Invalid):
            self.workflow.edit(draft, "add_piece", {"title": "No"}, request_id="late")

    def test_failed_edit_preserves_whole_draft_and_live(self):
        draft = self.workflow.start()["draftId"]
        before, live_before = self.state(self.workflow.draft_path(draft)), self.state()
        bad = self.root / "bad.pdf"
        bad.write_bytes(b"%PDF-1.7 invalid")
        with self.assertRaises(Invalid):
            self.workflow.edit(draft, "import_pdfs", {"paths": [str(write_pdf(self.root)), str(bad)]}, request_id="batch")
        self.assertEqual(before, self.state(self.workflow.draft_path(draft)))
        self.assertEqual(live_before, self.state())

    def test_stale_preview_after_edit_is_rejected_and_can_be_reviewed_again(self):
        draft, _, _, _ = self.staged_piece()
        old = self.review(draft)
        self.workflow.edit(draft, "add_category", {"code": "33", "name": "Later edit"}, request_id="later")
        before = self.state()
        with self.assertRaisesRegex(Invalid, "STALE_PREVIEW"):
            self.confirm(draft, old)
        self.assertEqual(before, self.state())
        self.assertTrue(self.confirm(draft, self.review(draft))["committed"])

    def test_live_concurrent_number_change_invalidates_draft(self):
        draft, _, _, _ = self.staged_piece()
        reviewed = self.review(draft)
        with Catalog(self.live) as c:
            c.assign_number("legacy-piece", number=1001)
        before = self.state()
        with self.assertRaisesRegex(Invalid, "STALE_DRAFT"):
            self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())

    def test_two_drafts_cannot_commit_the_same_auto_number(self):
        a = self.workflow.start()["draftId"]
        b = self.workflow.start()["draftId"]
        for draft in (a, b):
            self.workflow.edit(draft, "add_piece", {"title": draft}, request_id="piece")
        ar, br = self.review(a), self.review(b)
        self.confirm(a, ar)
        before = self.state()
        with self.assertRaisesRegex(Invalid, "STALE_DRAFT"):
            self.confirm(b, br)
        self.assertEqual(before, self.state())

    def test_concurrent_confirmation_has_one_receipt_and_one_import(self):
        draft, _, _, _ = self.staged_piece()
        reviewed = self.review(draft)
        def confirm(_):
            return Workflow(self.live).confirm(draft, "legacy-player", reviewed["confirmationHash"])
        with ThreadPoolExecutor(max_workers=2) as pool:
            a, b = list(pool.map(confirm, range(2)))
        self.assertEqual(a, b)
        with Catalog(self.live) as c:
            self.assertEqual(3, len(c.rows("score_pdf_versions")))
            self.assertEqual(1, c._db.execute("SELECT count(*) FROM local_workflow_receipts").fetchone()[0])

    def test_changed_original_file_invalidates_confirmation(self):
        draft, source, _, _ = self.staged_piece()
        reviewed = self.review(draft)
        source.write_bytes(pdf_bytes(5, "changed source"))
        before = self.state()
        with self.assertRaisesRegex(Invalid, "Source file changed"):
            self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())

    def test_corrupt_staged_file_cannot_be_confirmed(self):
        draft, _, _, pdf = self.staged_piece()
        reviewed = self.review(draft)
        with Catalog(self.workflow.draft_path(draft)) as c:
            c._db.execute("UPDATE local_pdf_bytes SET data=? WHERE versionId=?", (b"corrupt", pdf["versionId"]))
        before = self.state()
        with self.assertRaises(Invalid):
            self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())

    def test_failure_after_applying_rows_rolls_back_everything(self):
        draft, _, _, _ = self.staged_piece()
        reviewed = self.review(draft)
        before = self.state()
        original = self.workflow._apply
        def failure(*args):
            original(*args)
            raise OSError("synthetic commit failure")
        with patch.object(self.workflow, "_apply", side_effect=failure):
            with self.assertRaises(OSError):
                self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())
        self.assertFalse(self.workflow.status(draft)["committed"])
        self.assertTrue(self.confirm(draft, reviewed)["committed"])

    def test_cancel_after_applying_rows_rolls_back_everything(self):
        draft, _, _, _ = self.staged_piece()
        reviewed = self.review(draft)
        before = self.state()
        original = self.workflow._apply
        cancelled = False
        def apply(*args):
            nonlocal cancelled
            original(*args)
            cancelled = True
        with patch.object(self.workflow, "_apply", side_effect=apply):
            with self.assertRaises(Cancelled):
                self.confirm(draft, reviewed, cancel=lambda: cancelled)
        self.assertEqual(before, self.state())

    def test_frozen_guard_is_rechecked_at_confirm_not_just_manifest_hash(self):
        draft = self.workflow.start()["draftId"]
        self.workflow.edit(draft, "add_category", {"code": "22", "name": "Draft"}, request_id="category")
        with Catalog(self.workflow.draft_path(draft)) as c:
            # Simulate an alternate/tampered draft writer that bypassed edit().
            c._db.execute("INSERT INTO movements VALUES ('unsafe','legacy-piece','Unbound',2,0)")
            row = c._db.execute("SELECT operations FROM local_draft").fetchone()
            ops = json.loads(row[0])
            args = {"piece_id": "legacy-piece", "title": "Unbound", "sort_order": 2}
            ops.append({"action": "add_movement", "arguments": args, "result": "unsafe", "sources": [], "requestId": "unsafe", "requestHash": sha(json_bytes(["add_movement", args]))})
            c._db.execute("UPDATE local_draft SET operations=?", (json.dumps(ops),))
        reviewed = self.review(draft)
        before = self.state()
        with self.assertRaisesRegex(Invalid, "frozen concert"):
            self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())

    def test_existing_frozen_operations_rejected_during_staging(self):
        draft = self.workflow.start()["draftId"]
        before = self.state(self.workflow.draft_path(draft))
        for action, args in [("add_movement", {"piece_id": "legacy-piece", "title": "No", "sort_order": 2}),
                             ("import_pdf", {"path": str(write_pdf(self.root)), "pdf_id": "legacy-pdf"})]:
            with self.subTest(action=action), self.assertRaises(Invalid):
                self.workflow.edit(draft, action, args, request_id=action)
        self.assertEqual(before, self.state(self.workflow.draft_path(draft)))

    def test_new_members_have_no_implicit_grants_or_admin_bypass(self):
        draft = self.workflow.start()["draftId"]
        for n in range(9):
            member = self.workflow.edit(draft, "add_member", {"name": f"Dynamic {n}", "administrator": n == 8}, request_id=str(n))["result"]
            self.assertEqual([], self.workflow.review(draft, member)["memberPreview"]["pieces"])

    def test_pending_draft_cannot_be_exported_or_used_as_committed_database(self):
        draft, _, _, _ = self.staged_piece()
        with self.assertRaises(Invalid):
            Workflow(self.workflow.draft_path(draft))
        with self.assertRaises(Invalid):
            export_committed_bundle(self.workflow.draft_path(draft), self.root / "bad.zip", "legacy-admin")
        self.assertFalse((self.root / "bad.zip").exists())

    def test_complete_bundle_and_legacy_import_have_explicit_recovery_scope(self):
        bundle = self.root / "complete.zip"
        exported = export_committed_bundle(self.live, bundle, "legacy-admin")
        self.assertEqual("COMPLETE_LOCAL_BUNDLE", exported["bundleKind"])
        for source, scope, numbers in ((bundle, "COMPLETE_LOCAL_BUNDLE", 1),
                                       (self.original, "ANDROID_V1_ONLY_NO_NUMBERING_CATEGORIES_HISTORY", 0)):
            with self.subTest(scope=scope):
                target = self.root / (scope + ".sqlite")
                with Catalog(target, create=True):
                    pass
                w = Workflow(target)
                draft = w.start()["draftId"]
                result = w.edit(draft, "restore_archive", {"source": str(source), "expected_sha256": sha(source.read_bytes())}, request_id="restore")
                self.assertEqual(scope, result["result"]["recoveryScope"])
                with Catalog(target) as c:
                    self.assertEqual([], c.rows("pieces"))
                reviewed = w.review(draft, "legacy-player")
                w.confirm(draft, "legacy-player", reviewed["confirmationHash"])
                with Catalog(target) as c:
                    self.assertEqual(numbers, len(c.rows("local_numbers")))
                    self.assertEqual("legacy-v1", c.rows("score_pdfs")[0]["activeVersionId"])

    def test_unrecorded_permission_changes_cannot_be_committed(self):
        draft = self.workflow.start()["draftId"]
        self.workflow.edit(draft, "add_category", {"code": "44", "name": "Draft"}, request_id="category")
        with Catalog(self.workflow.draft_path(draft)) as c:
            c._db.execute("INSERT INTO member_part_permissions VALUES ('unrecorded','legacy-admin','legacy-instrument',0)")
        reviewed = self.review(draft)
        before = self.state()
        with self.assertRaisesRegex(Invalid, "Unrecorded draft change"):
            self.confirm(draft, reviewed)
        self.assertEqual(before, self.state())

    def test_process_crash_before_and_after_commit_recovers_authoritative_receipt(self):
        for after in (False, True):
            with self.subTest(after=after):
                draft = self.workflow.start()["draftId"]
                self.workflow.edit(draft, "add_piece", {"title": "Crash candidate"}, request_id="piece")
                reviewed = self.review(draft)
                before = self.state()
                code = """import sys, os
from contextlib import contextmanager
from score_manager import Catalog
from score_manager.workflow import Workflow, receipt
path,draft,token,after=sys.argv[1:]
original=Catalog._transaction
@contextmanager
def crash(self,cancel=None):
    with original(self,cancel):
        yield
        if str(self.path)==path and after=='False': os._exit(7)
    if str(self.path)==path and after=='True': os._exit(8)
Catalog._transaction=crash
Workflow(path).confirm(draft,'legacy-player',token)
"""
                result = subprocess.run([sys.executable, "-c", code, str(self.live), draft, reviewed["confirmationHash"], str(after)], capture_output=True, text=True, timeout=60)
                self.assertEqual(8 if after else 7, result.returncode, result.stderr)
                if not after:
                    self.assertEqual(before, self.state())
                    self.assertFalse(self.workflow.status(draft)["committed"])
                else:
                    self.assertTrue(self.workflow.status(draft)["committed"])
                result = self.confirm(draft, reviewed)
                committed = self.state()
                self.assertEqual(result, self.confirm(draft, reviewed))
                self.assertEqual(committed, self.state())


if __name__ == "__main__":
    unittest.main()
