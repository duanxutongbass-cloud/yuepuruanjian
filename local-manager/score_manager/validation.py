"""Schema-3 shape/relationship validation, based on the checked-in Room schema.

This does not run Android Room or replace Android restoration acceptance.
"""
import hashlib
import json
from pathlib import Path
import re

from .errors import Invalid, require

REPO = Path(__file__).resolve().parents[2]
SCHEMA_PATH = REPO / "app/schemas/com.ensemble.scorelibrary.core.database.ScoreLibraryDatabase/3.json"
SCHEMA = json.loads(SCHEMA_PATH.read_text())["database"]
ENTITIES = {e["tableName"]: e for e in SCHEMA["entities"] if e["tableName"] != "score_cache_entries"}
TABLES = tuple(ENTITIES)
JSON_LIMIT = 16 * 1024**2
TOTAL_LIMIT = 2 * 1024**3
MAX_INT = 2**63 - 1


def identity(value):
    require(isinstance(value, str) and value.strip() and len(value) <= 200
            and not any(c in value for c in ":|#\x00"), "Invalid stable identity")
    return value


def integer(value, minimum=0, maximum=MAX_INT):
    require(type(value) is int and minimum <= value <= maximum, "Invalid integer")
    return value


def nonempty(value):
    require(isinstance(value, str) and value.strip() and "\x00" not in value, "Empty/invalid label")
    return value


def sha(data):
    return hashlib.sha256(data).hexdigest()


def entry(version_id):
    return "files/" + sha(identity(version_id).encode("utf-8")) + ".pdf"


def json_bytes(value):
    result = json.dumps(value, ensure_ascii=False, allow_nan=False, sort_keys=True, indent=2).encode("utf-8")
    require(len(result) <= JSON_LIMIT, "JSON exceeds metadata budget")
    return result


def parse_json(data):
    require(len(data) <= JSON_LIMIT, "JSON exceeds metadata budget")
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "Duplicate JSON field")
            result[key] = value
        return result
    def constant(value):
        raise Invalid("Non-finite JSON number")
    try:
        value = json.loads(data, object_pairs_hook=pairs, parse_constant=constant)
        def depth(v, level=0):
            require(level <= 32, "JSON nested too deeply")
            if isinstance(v, dict):
                for item in v.values():
                    depth(item, level + 1)
            elif isinstance(v, list):
                for item in v:
                    depth(item, level + 1)
        depth(value)
        return value
    except (UnicodeError, RecursionError, json.JSONDecodeError) as error:
        raise Invalid("Invalid JSON") from error


def create_schema(db):
    require(SCHEMA["version"] == 3, "Only source schema 3 is supported")
    for name, entity in ENTITIES.items():
        db.execute(entity["createSql"].replace("${TABLE_NAME}", name))
        for index in entity.get("indices", []):
            db.execute(index["createSql"].replace("${TABLE_NAME}", name))


def validate_rows(table, rows):
    fields = {f["columnName"]: f for f in ENTITIES[table]["fields"]}
    require(isinstance(rows, list) and len(rows) <= 100_000, "Invalid row list")
    for row in rows:
        require(isinstance(row, dict) and set(row) == set(fields), "Unknown/missing schema-3 fields: " + table)
        for name, field in fields.items():
            value = row[name]
            if value is None:
                require(not field.get("notNull"), "Null in required column")
            elif field["affinity"] == "INTEGER":
                integer(value, -MAX_INT - 1)
                if name.startswith("is"):
                    require(value in (0, 1), "Invalid Boolean integer")
            else:
                require(isinstance(value, str), "Invalid text column")
                if name == "id" or name.endswith("Id"):
                    identity(value)


def insert_rows(db, table, rows):
    require(table in ENTITIES, "Unknown table")
    validate_rows(table, rows)
    fields = [f["columnName"] for f in ENTITIES[table]["fields"]]
    db.executemany(f'INSERT INTO "{table}" ({",".join(fields)}) VALUES ({",".join("?" for _ in fields)})',
                   [[row[f] for f in fields] for row in rows])


SYMBOLS = {"ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "sfz", "fp", "cresc.", "dim.", "<", ">",
           "♯", "♭", "♮", "fermata", "accent", "staccato", "tenuto", "rit.", "a tempo", "D.C.", "D.S.", "Fine"}


def validate_ink(payload):
    require(len(payload) <= 8_000_000, "Ink payload too large")
    marks = parse_json(payload)
    require(isinstance(marks, list) and len(marks) <= 2000, "Invalid ink payload")
    ids, total = set(), 0
    for mark in marks:
        require(isinstance(mark, dict) and {"id", "kind", "color", "width", "symbol", "points"} <= set(mark), "Invalid ink mark")
        require(isinstance(mark["id"], str) and mark["id"] not in ids, "Duplicate ink identity")
        ids.add(mark["id"])
        require(mark["kind"] in {"PEN", "HIGHLIGHT", "SYMBOL"} and mark["symbol"] in SYMBOLS, "Unknown ink value")
        integer(mark["color"], 0, 0xffffffff)
        width = mark["width"]
        require(type(width) in (float, int) and .0005 <= width <= .05, "Invalid ink width")
        points = mark["points"]
        require(isinstance(points, list) and 1 <= len(points) <= 20_000, "Invalid ink points")
        total += len(points)
        require(total <= 100_000, "Too many ink points")
        for point in points:
            require(isinstance(point, list) and len(point) == 2
                    and all(type(x) in (int, float) and 0 <= x <= 1 for x in point), "Invalid normalized point")


def snapshot_manifest(tables, concert_id):
    items = [x for x in tables["concert_items"] if x["concertId"] == concert_id and not x["isArchived"] and x["isEnabled"]]
    pieces = {x["pieceId"] for x in items}
    parts = {x["id"] for x in tables["piece_parts"] if x["pieceId"] in pieces and not x["isArchived"]}
    pdfs = {x["id"]: x for x in tables["score_pdfs"] if not x["isArchived"]}
    versions = {x["id"]: x for x in tables["score_pdf_versions"]}
    bindings = sorted((x for x in tables["part_score_bindings"] if x["piecePartId"] in parts and not x["isArchived"]), key=lambda x: x["id"])
    result = []
    for b in bindings:
        pdf = pdfs.get(b["scorePdfId"])
        v = versions.get(pdf["activeVersionId"]) if pdf else None
        require(v is not None and v["scorePdfId"] == pdf["id"] and 1 <= b["startPage"] <= b["endPage"] <= v["pageCount"], "Invalid frozen binding")
        result.append(":".join(str(x) if x is not None else "null" for x in
                    [b["id"], b["piecePartId"], b["movementId"], v["id"], v["fileHash"], v["pageCount"], b["startPage"], b["endPage"]]))
    return "|".join(result) + "#" + "|".join(f'{x["id"]}:{x["pieceId"]}:{x["sortOrder"]}' for x in sorted(items, key=lambda x: x["sortOrder"]))


def validate_library(db, require_admin=False):
    require(not db.execute("PRAGMA foreign_key_check").fetchall(), "Invalid foreign key")
    t = {table: [dict(x) for x in db.execute(f'SELECT * FROM "{table}"')] for table in TABLES}
    for table, rows in t.items():
        validate_rows(table, rows)
    members = {x["id"]: x for x in t["members"]}
    pieces = {x["id"]: x for x in t["pieces"]}
    definitions = {x["id"]: x for x in t["part_definitions"]}
    for item in t["concert_items"]:
        if not item["isArchived"] and item["isEnabled"]:
            require(not pieces[item["pieceId"]]["isArchived"], "Disable or remove enabled concert items before archiving the piece")
    for part in t["piece_parts"]:
        if not part["isArchived"]:
            require(not definitions[part["partDefinitionId"]]["isArchived"], "Active piece part uses an archived instrument")
    for member in members.values():
        if not member["isArchived"] and member["defaultPartDefinitionId"] is not None:
            require(not definitions[member["defaultPartDefinitionId"]]["isArchived"], "Active member default uses an archived instrument")
    require("shared" not in members, "Reserved member identity")
    require(all(x["role"] in {"ADMIN", "MEMBER"} for x in members.values()), "Unknown role")
    if require_admin:
        require(any(x["role"] == "ADMIN" and not x["isArchived"] for x in members.values()), "Archive needs an active administrator")
    versions = {x["id"]: x for x in t["score_pdf_versions"]}
    pdfs = {x["id"]: x for x in t["score_pdfs"]}
    parts = {x["id"]: x for x in t["piece_parts"]}
    movements = {x["id"]: x for x in t["movements"]}
    for pdf in pdfs.values():
        v = versions.get(pdf["activeVersionId"])
        require(pdf["activeVersionId"] is None or v is not None and v["scorePdfId"] == pdf["id"], "Active version belongs to another PDF")
    for v in versions.values():
        integer(v["versionNumber"], 1)
        integer(v["pageCount"], 1, 10_000)
        integer(v["fileSizeBytes"], 1, 256 * 1024**2)
        require(re.fullmatch(r"[a-fA-F0-9]{64}", v["fileHash"]), "Invalid PDF hash")
        require(v["managedFilePath"] == entry(v["id"]), "Invalid managed entry")
        require(v["createdByMemberId"] is None or v["createdByMemberId"] in members, "Missing version creator")
    seen, by_part = set(), {}
    for b in t["part_score_bindings"]:
        if b["movementId"] is not None:
            require(movements[b["movementId"]]["pieceId"] == parts[b["piecePartId"]]["pieceId"], "Movement belongs to another piece")
        require(1 <= b["startPage"] <= b["endPage"], "Invalid page range")
        if not b["isArchived"]:
            key = (b["piecePartId"], b["movementId"])
            require(key not in seen, "Duplicate active binding")
            seen.add(key)
            by_part.setdefault(b["piecePartId"], []).append(b["movementId"])
            v = versions.get(pdfs[b["scorePdfId"]]["activeVersionId"])
            require(v is not None and b["endPage"] <= v["pageCount"], "Page range exceeds active source")
    require(all(len(v) == 1 or None not in v for v in by_part.values()), "Whole-piece and movement bindings conflict")
    assignments = [(x["memberId"], parts[x["piecePartId"]]["pieceId"]) for x in t["piece_part_assignments"] if not x["isArchived"]]
    require(len(assignments) == len(set(assignments)), "Ambiguous playing assignment")
    pages = {x["id"]: x for x in t["annotation_pages"]}
    revisions = {x["id"]: x for x in t["annotation_revisions"]}
    for p in pages.values():
        integer(p["pageIndex"], 0, versions[p["versionId"]]["pageCount"] - 1)
        integer(p["generation"])
        require(p["ownerKey"] == "shared" or p["ownerKey"] in members, "Unknown annotation owner")
        validate_ink(p["payload"])
        if p["ownerKey"] != "shared":
            require(p["publishedRevisionId"] is None, "Personal ink cannot be published")
        if p["publishedRevisionId"] is not None:
            r = revisions.get(p["publishedRevisionId"])
            require(r is not None and r["documentId"] == p["id"], "Invalid published revision")
    for r in revisions.values():
        require(r["createdBy"] in members and pages[r["documentId"]]["ownerKey"] == "shared", "Invalid shared revision owner")
        validate_ink(r["payload"])
    for c in t["concerts"]:
        require(c["status"] in {"DRAFT", "READY", "PUBLISHED", "PERFORMING", "FINISHED"}, "Unknown concert status")
    for snap in t["annotation_concert_snapshots"]:
        require(snap["createdBy"] in members and snap["manifest"] == snapshot_manifest(t, snap["concertId"]), "Frozen concert manifest mismatch")
        payload = parse_json(snap["payload"])
        require(isinstance(payload, dict), "Invalid frozen payload")
        for key, ink in payload.items():
            version, _, page = key.rpartition(":")
            require(version in versions and re.fullmatch(r"[0-9]+", page), "Invalid frozen source page")
            integer(int(page), 0, versions[version]["pageCount"] - 1)
            require(isinstance(ink, str), "Invalid frozen ink")
            validate_ink(ink)
    return t
