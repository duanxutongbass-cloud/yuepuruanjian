"""Adapted contracts from existing Android ScoreResolverTest, not a Kotlin run."""
from copy import deepcopy
import json
import unittest

from score_manager import Invalid
from score_manager.validation import TABLES
from score_manager.visibility import Visibility


def resolver_fixture():
    t = {x: [] for x in TABLES}
    t["pieces"] = [{"id": "p", "title": "Allowed piece", "isArchived": 0}]
    t["part_definitions"] = [{"id": "d", "name": "I", "sortOrder": 0, "isArchived": 0},
                             {"id": "e", "name": "SECRET_UNAUTHORIZED_INSTRUMENT", "sortOrder": 1, "isArchived": 0}]
    t["piece_parts"] = [{"id": "a", "pieceId": "p", "partDefinitionId": "d", "displayName": None, "isArchived": 0},
                        {"id": "b", "pieceId": "p", "partDefinitionId": "e", "displayName": None, "isArchived": 0}]
    t["members"] = [{"id": "m", "displayName": "Member", "role": "MEMBER", "defaultPartDefinitionId": "d", "isArchived": 0}]
    t["member_part_permissions"] = [{"id": "permission", "memberId": "m", "partDefinitionId": "d", "isArchived": 0}]
    t["score_pdfs"] = [{"id": "pdf", "activeVersionId": "v", "title": "SECRET_MOTHER_TITLE", "isArchived": 0}]
    t["score_pdf_versions"] = [{"id": "v", "scorePdfId": "pdf", "versionNumber": 1, "pageCount": 10,
                                "managedFilePath": "SECRET_MOTHER_PATH", "fileHash": "SECRET_MOTHER_HASH"}]
    t["part_score_bindings"] = [{"id": "binding", "piecePartId": "a", "movementId": None, "scorePdfId": "pdf", "startPage": 2, "endPage": 3, "isArchived": 0}]
    return t


class VisibilityTest(unittest.TestCase):
    def setUp(self):
        self.t = resolver_fixture()

    def test_default_requires_permission_and_admin_has_no_bypass(self):
        r = Visibility(self.t)
        self.assertEqual(["a"], [x["id"] for x in r.allowed("m", "p")])
        self.assertEqual("a", r.selected("m", "p")["id"])
        self.t["member_part_permissions"] = []
        self.t["members"][0]["role"] = "ADMIN"
        with self.assertRaisesRegex(Invalid, "UNAUTHORIZED"):
            Visibility(self.t).selected("m", "p")
        self.assertEqual([], Visibility(self.t).member_preview("m")["pieces"])

    def test_assignment_never_grants_access_and_no_fallback_to_default(self):
        self.t["piece_part_assignments"] = [{"id": "assignment", "memberId": "m", "piecePartId": "b", "isArchived": 0}]
        r = Visibility(self.t)
        preview = r.member_preview("m")["pieces"][0]
        self.assertEqual(["a"], [p["partId"] for p in preview["visibleParts"]])
        self.assertEqual({"error": "UNAUTHORIZED"}, preview["playing"])
        self.assertNotIn("SECRET", json.dumps(preview))
        self.t["member_piece_part_permissions"] = [{"id": "extra", "memberId": "m", "piecePartId": "b", "isArchived": 0}]
        self.t["part_score_bindings"].append({**self.t["part_score_bindings"][0], "id": "binding-b", "piecePartId": "b", "startPage": 7, "endPage": 8})
        preview = Visibility(self.t).member_preview("m")["pieces"][0]
        self.assertEqual(["a", "b"], [p["partId"] for p in preview["visibleParts"]])
        self.assertEqual({"partId": "b"}, preview["playing"])
        self.t["members"][0]["defaultPartDefinitionId"] = None
        self.assertEqual("b", Visibility(self.t).selected("m", "p")["id"])

    def test_scoped_permission_does_not_leak_to_other_piece_or_member(self):
        self.t["pieces"].append({"id": "other", "title": "SECRET_OTHER_PIECE", "isArchived": 0})
        self.t["piece_parts"].append({"id": "other-part", "pieceId": "other", "partDefinitionId": "e", "displayName": None, "isArchived": 0})
        self.t["member_piece_part_permissions"] = [{"memberId": "m", "piecePartId": "b", "isArchived": 0},
                                                   {"memberId": "someone-else", "piecePartId": "other-part", "isArchived": 0}]
        r = Visibility(self.t)
        self.assertEqual([], r.allowed("m", "other"))
        self.assertNotIn("SECRET_OTHER_PIECE", json.dumps(r.member_preview("m")))

    def test_preview_is_pure_and_has_no_mother_file_metadata_or_unallowed_pages(self):
        before = deepcopy(self.t)
        preview = Visibility(self.t).member_preview("m")
        text = json.dumps(preview)
        for forbidden in ("SECRET", "managedFilePath", "fileHash", "pageCount", "scorePdfId", "originalUri"):
            self.assertNotIn(forbidden, text)
        self.assertEqual([1, 2], preview["pieces"][0]["visibleParts"][0]["segments"][0]["sourcePages"])
        self.assertEqual(before, self.t)

    def test_archived_members_pieces_permissions_definitions_and_parts(self):
        for table, code in (("members", "MEMBER_MISSING"), ("pieces", "PIECE_MISSING"),
                            ("member_part_permissions", "UNAUTHORIZED"), ("part_definitions", "UNAUTHORIZED"),
                            ("piece_parts", "PIECE_PART_MISSING")):
            with self.subTest(table=table):
                t = deepcopy(self.t)
                for row in t[table]:
                    row["isArchived"] = 1
                with self.assertRaisesRegex(Invalid, code):
                    Visibility(t).selected("m", "p")

    def test_default_missing_dangling_or_ambiguous_assignment_are_errors(self):
        self.t["members"][0]["defaultPartDefinitionId"] = None
        with self.assertRaisesRegex(Invalid, "DEFAULT_PART_MISSING"):
            Visibility(self.t).selected("m", "p")
        for ids, code in ((["missing"], "PIECE_PART_MISSING"), (["a", "b"], "AMBIGUOUS_ASSIGNMENT")):
            self.t["piece_part_assignments"] = [{"memberId": "m", "piecePartId": p, "isArchived": 0} for p in ids]
            with self.assertRaisesRegex(Invalid, code):
                Visibility(self.t).selected("m", "p")

    def test_active_version_never_falls_back_to_highest(self):
        self.t["score_pdf_versions"].append({**self.t["score_pdf_versions"][0], "id": "new", "versionNumber": 2})
        r = Visibility(self.t)
        self.assertEqual("v", r.segments(self.t["piece_parts"][0])[0]["sourceVersionId"])
        self.t["score_pdfs"][0]["activeVersionId"] = None
        with self.assertRaisesRegex(Invalid, "ACTIVE_VERSION_MISSING"):
            r.segments(self.t["piece_parts"][0])

    def test_version_ownership_and_pdf_archive_are_checked(self):
        self.t["score_pdf_versions"][0]["scorePdfId"] = "other"
        with self.assertRaisesRegex(Invalid, "VERSION_MISMATCH"):
            Visibility(self.t).segments(self.t["piece_parts"][0])
        self.t["score_pdfs"][0]["isArchived"] = 1
        with self.assertRaisesRegex(Invalid, "PDF_MISSING"):
            Visibility(self.t).segments(self.t["piece_parts"][0])

    def test_page_ranges_missing_and_ambiguous_bindings(self):
        for start, end in ((0, 1), (3, 2), (1, 11), (-1, 2)):
            with self.subTest(start=start, end=end):
                t = deepcopy(self.t)
                t["part_score_bindings"][0].update(startPage=start, endPage=end)
                with self.assertRaisesRegex(Invalid, "INVALID_PAGE_RANGE"):
                    Visibility(t).segments(t["piece_parts"][0])
        self.t["part_score_bindings"].append({**self.t["part_score_bindings"][0], "id": "duplicate"})
        with self.assertRaisesRegex(Invalid, "AMBIGUOUS_BINDING"):
            Visibility(self.t).segments(self.t["piece_parts"][0])
        self.t["part_score_bindings"] = []
        with self.assertRaisesRegex(Invalid, "BINDING_MISSING"):
            Visibility(self.t).segments(self.t["piece_parts"][0])

    def test_movement_order_completeness_and_ownership(self):
        self.t["movements"] = [{"id": "second", "pieceId": "p", "sortOrder": 2, "isArchived": 0},
                               {"id": "first", "pieceId": "p", "sortOrder": 1, "isArchived": 0}]
        binding = self.t["part_score_bindings"][0]
        self.t["part_score_bindings"] = [{**binding, "id": "b2", "movementId": "second", "startPage": 5, "endPage": 10},
                                          {**binding, "id": "b1", "movementId": "first", "startPage": 1, "endPage": 4}]
        self.assertEqual(["first", "second"], [x["movementId"] for x in Visibility(self.t).segments(self.t["piece_parts"][0])])
        self.t["part_score_bindings"].pop()
        with self.assertRaisesRegex(Invalid, "BINDING_MISSING"):
            Visibility(self.t).segments(self.t["piece_parts"][0])
        self.t["movements"] = []
        with self.assertRaisesRegex(Invalid, "MOVEMENT_MISMATCH"):
            Visibility(self.t).segments(self.t["piece_parts"][0])
