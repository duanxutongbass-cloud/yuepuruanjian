"""Read-only, metadata-only projection of ScoreResolver's member rules.

No PDF bytes, paths, whole-source hashes, grants or administrator bypass are
returned. This is an administrative preview, not authentication/distribution.
"""
from .errors import Invalid


def fail(code):
    raise Invalid(code)


class Visibility:
    def __init__(self, tables):
        self.t = tables

    def active(self, table, record_id, error):
        matches = [x for x in self.t[table] if x["id"] == record_id and not x["isArchived"]]
        if len(matches) != 1:
            fail(error)
        return matches[0]

    def allowed(self, member_id, piece_id):
        self.active("members", member_id, "MEMBER_MISSING")
        self.active("pieces", piece_id, "PIECE_MISSING")
        global_ids = {x["partDefinitionId"] for x in self.t["member_part_permissions"]
                      if x["memberId"] == member_id and not x["isArchived"]}
        extra_ids = {x["piecePartId"] for x in self.t["member_piece_part_permissions"]
                     if x["memberId"] == member_id and not x["isArchived"]}
        definitions = {x["id"]: x for x in self.t["part_definitions"] if not x["isArchived"]}
        return sorted((x for x in self.t["piece_parts"] if x["pieceId"] == piece_id
                       and not x["isArchived"] and x["partDefinitionId"] in definitions
                       and (x["partDefinitionId"] in global_ids or x["id"] in extra_ids)),
                      key=lambda x: (definitions[x["partDefinitionId"]]["sortOrder"], x["id"]))

    def selected(self, member_id, piece_id):
        member = self.active("members", member_id, "MEMBER_MISSING")
        allowed = self.allowed(member_id, piece_id)
        parts = {x["id"]: x for x in self.t["piece_parts"]}
        assignments = [x for x in self.t["piece_part_assignments"] if x["memberId"] == member_id and not x["isArchived"]]
        if any(x["piecePartId"] not in parts for x in assignments):
            fail("PIECE_PART_MISSING")
        overrides = [x for x in assignments if parts[x["piecePartId"]]["pieceId"] == piece_id]
        if len(overrides) > 1:
            fail("AMBIGUOUS_ASSIGNMENT")
        if overrides:
            selected = self.active("piece_parts", overrides[0]["piecePartId"], "PIECE_PART_MISSING")
        else:
            default = member["defaultPartDefinitionId"]
            if default is None:
                fail("DEFAULT_PART_MISSING")
            matches = [x for x in parts.values() if x["pieceId"] == piece_id and x["partDefinitionId"] == default and not x["isArchived"]]
            if len(matches) != 1:
                fail("PIECE_PART_MISSING")
            selected = matches[0]
        if selected["id"] not in {x["id"] for x in allowed}:
            fail("UNAUTHORIZED")
        return selected

    def segments(self, part):
        bindings = [x for x in self.t["part_score_bindings"] if x["piecePartId"] == part["id"] and not x["isArchived"]]
        if not bindings:
            fail("BINDING_MISSING")
        movements = {x["id"]: x for x in self.t["movements"] if x["pieceId"] == part["pieceId"] and not x["isArchived"]}
        scopes = [x["movementId"] for x in bindings]
        if len(set(scopes)) != len(scopes) or None in scopes and len(scopes) > 1:
            fail("AMBIGUOUS_BINDING")
        if any(x is not None and x not in movements for x in scopes):
            fail("MOVEMENT_MISMATCH")
        if None not in scopes and set(movements) - set(scopes):
            fail("BINDING_MISSING")
        output = []
        for b in sorted(bindings, key=lambda x: (movements[x["movementId"]]["sortOrder"] if x["movementId"] is not None else -1, x["id"])):
            pdf = self.active("score_pdfs", b["scorePdfId"], "PDF_MISSING")
            versions = [x for x in self.t["score_pdf_versions"] if x["id"] == pdf["activeVersionId"]]
            if len(versions) != 1:
                fail("ACTIVE_VERSION_MISSING")
            v = versions[0]
            if v["scorePdfId"] != pdf["id"]:
                fail("VERSION_MISMATCH")
            if not 1 <= b["startPage"] <= b["endPage"] <= v["pageCount"]:
                fail("INVALID_PAGE_RANGE")
            output.append({"bindingId": b["id"], "movementId": b["movementId"],
                           "sourceVersionId": v["id"], "sourcePages": list(range(b["startPage"] - 1, b["endPage"]))})
        return output

    def member_preview(self, member_id):
        member = self.active("members", member_id, "MEMBER_MISSING")
        output = []
        for piece in sorted((x for x in self.t["pieces"] if not x["isArchived"]), key=lambda x: (x["title"], x["id"])):
            allowed = self.allowed(member_id, piece["id"])
            if not allowed:
                continue  # Do not reveal titles or identities with no allowed part.
            parts = []
            for part in allowed:
                definition = self.active("part_definitions", part["partDefinitionId"], "UNAUTHORIZED")
                view = {"partId": part["id"], "name": part["displayName"] if part["displayName"] is not None else definition["name"]}
                try:
                    view["segments"] = self.segments(part)
                except Invalid as error:
                    view["error"] = str(error)
                parts.append(view)
            try:
                selected = self.selected(member_id, piece["id"])
                self.segments(selected)
                playing = {"partId": selected["id"]}
            except Invalid as error:
                playing = {"error": str(error)}  # Never reveal an unauthorized assignment ID.
            output.append({"pieceId": piece["id"], "title": piece["title"], "visibleParts": parts, "playing": playing})
        return {"memberId": member["id"], "memberName": member["displayName"], "pieces": output,
                "previewKind": "AUTHORIZED_METADATA_ONLY", "pdfBytesIncluded": False,
                "distributionImplemented": False}
