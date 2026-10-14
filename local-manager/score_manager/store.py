"""A local, trusted-administrator workbench, not a member-facing service.

SQLite transactions contain both original PDF bytes and metadata. No Android
database is opened. All IDs created here are UUIDs; imported IDs are preserved.
"""
from contextlib import contextmanager
import csv
import io
import json
from pathlib import Path
import re
import sqlite3
import time
import uuid

from .errors import checkpoint, require
from .pdf import inspect_pdf, read_pdf
from .validation import (TABLES, create_schema, entry, identity, integer,
                         nonempty, validate_library, json_bytes)

LOCAL_TABLES = ("local_categories", "local_numbers", "local_number_history")
LOCAL_SCHEMA = (
    "CREATE TABLE local_meta (version INTEGER NOT NULL CHECK(version=1))",
    "INSERT INTO local_meta VALUES (1)",
    "CREATE TABLE local_categories (id TEXT PRIMARY KEY NOT NULL, code TEXT UNIQUE NOT NULL, name TEXT NOT NULL, isArchived INTEGER NOT NULL CHECK(isArchived IN (0,1)))",
    "CREATE TABLE local_number_history (number INTEGER PRIMARY KEY CHECK(number>0), pieceId TEXT NOT NULL REFERENCES pieces(id))",
    "CREATE TABLE local_numbers (pieceId TEXT PRIMARY KEY NOT NULL REFERENCES pieces(id), number INTEGER UNIQUE NOT NULL REFERENCES local_number_history(number), categoryId TEXT REFERENCES local_categories(id))",
    "CREATE TABLE local_pdf_bytes (versionId TEXT PRIMARY KEY NOT NULL REFERENCES score_pdf_versions(id), data BLOB NOT NULL, geometry TEXT NOT NULL)",
)


def new_id():
    return str(uuid.uuid4())


def now():
    return time.time_ns() // 1_000_000


def parse_sequence(text):
    require(isinstance(text, str) and re.fullmatch(r"[0-9]+", text) and len(text) <= 19, "Sequence must contain ASCII digits")
    return integer(int(text), 1)


def format_sequence(number):
    return f"{integer(number, 1):03d}"


def category_code(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9]{2}", value), "Category code must be two ASCII digits")
    return value


def label(value, limit):
    value = nonempty(value).strip()
    require(len(value.encode("utf-16-le")) // 2 <= limit, "Label exceeds Android management limit")
    return value


def instrument_family(value):
    require(value is None or isinstance(value, str), "Invalid family")
    value = value.strip() or None if value is not None else None
    require(value is None or len(value.encode("utf-16-le")) // 2 <= 100, "Family exceeds Android management limit")
    return value


class Catalog:
    def __init__(self, path, *, create=False):
        self.path = Path(path).resolve()
        if create:
            # Never initialize over an existing database or user asset.
            with self.path.open("xb"):
                pass
        try:
            self._db = sqlite3.connect(self.path.as_uri() + "?mode=rw", uri=True,
                                       isolation_level=None, timeout=10)
            self._db.row_factory = sqlite3.Row
            self._db.execute("PRAGMA foreign_keys=ON")
            self._db.execute("PRAGMA synchronous=FULL")
            if create:
                self._db.execute("BEGIN IMMEDIATE")
                create_schema(self._db)
                for sql in LOCAL_SCHEMA:
                    self._db.execute(sql)
                self._db.commit()
            require(self._db.execute("SELECT version FROM local_meta").fetchall()[0][0] == 1,
                    "Unsupported local workbench version")
        except BaseException:
            if hasattr(self, "_db"):
                self._db.close()
            if create:
                self.path.unlink(missing_ok=True)
            raise

    def close(self):
        self._db.close()

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()

    @contextmanager
    def _transaction(self, cancel=None):
        checkpoint(cancel)
        self._db.execute("BEGIN IMMEDIATE")
        try:
            yield
            self._validate()
            checkpoint(cancel)
            self._db.commit()  # Cancellation after this point cannot undo success.
        except BaseException:
            self._db.rollback()
            raise

    def _validate(self):
        validate_library(self._db)
        for row in self.rows("local_categories"):
            identity(row["id"])
            category_code(row["code"])
            nonempty(row["name"])
            integer(row["isArchived"], 0, 1)
        for row in self.rows("local_number_history"):
            integer(row["number"], 1)
        for row in self.rows("local_numbers"):
            integer(row["number"], 1)
            owner = self._db.execute("SELECT pieceId FROM local_number_history WHERE number=?", (row["number"],)).fetchone()
            require(owner is not None and owner[0] == row["pieceId"], "Reserved sequence belongs to another piece")
        versions = {x["id"]: x for x in self.rows("score_pdf_versions")}
        blobs = {x[0] for x in self._db.execute("SELECT versionId FROM local_pdf_bytes")}
        require(set(versions) == blobs, "Missing or orphaned original PDF bytes")

    def rows(self, table):
        require(table in TABLES + LOCAL_TABLES, "Unknown table")
        key = "pieceId" if table == "local_numbers" else "number" if table == "local_number_history" else "id"
        return [dict(x) for x in self._db.execute(f'SELECT * FROM "{table}" ORDER BY "{key}"')]

    def _get(self, table, row_id, active=False):
        require(table in TABLES + ("local_categories",), "Unknown table")
        row = self._db.execute(f'SELECT * FROM "{table}" WHERE id=?', (identity(row_id),)).fetchone()
        require(row is not None and (not active or not row["isArchived"]), "Missing or archived record: " + table)
        return dict(row)

    def _guard_piece(self, piece_id):
        # Mirror LibraryManagement.lockedPieces/guardPiece. A frozen manifest
        # omits unbound parts/movements and dictionary names, so equality alone
        # cannot authorize structural edits. Do not filter archived concerts.
        protected = self._db.execute("""SELECT 1 FROM concert_items i
            JOIN annotation_concert_snapshots s ON s.concertId=i.concertId
            WHERE i.pieceId=? AND i.isArchived=0 AND i.isEnabled=1 LIMIT 1""", (piece_id,)).fetchone()
        require(protected is None, "Piece is referenced by a frozen concert; use a new piece and program")

    def _guard_pdf(self, pdf_id):
        parts = self._db.execute("""SELECT DISTINCT p.pieceId FROM part_score_bindings b
            JOIN piece_parts p ON p.id=b.piecePartId
            WHERE b.scorePdfId=? AND b.isArchived=0 AND p.isArchived=0""", (pdf_id,)).fetchall()
        for part in parts:
            self._guard_piece(part[0])

    def add_member(self, name, *, administrator=False, member_id=None):
        member_id = identity(member_id or new_id())
        require(member_id != "shared", "Reserved member identity")
        require(type(administrator) is bool, "Administrator flag must be Boolean")
        with self._transaction():
            self._db.execute("INSERT INTO members VALUES (?,?,?,?,?)",
                             (member_id, label(name, 100), "ADMIN" if administrator else "MEMBER", None, 0))
        return member_id

    def add_category(self, code, name, *, category_id=None):
        category_id = identity(category_id or new_id())
        with self._transaction():
            self._db.execute("INSERT INTO local_categories VALUES (?,?,?,0)",
                             (category_id, category_code(code), nonempty(name)))
        return category_id

    def add_instrument(self, name, *, family=None, instrument_id=None, sort_order=0):
        instrument_id = identity(instrument_id or new_id())
        with self._transaction():
            self._db.execute("INSERT INTO part_definitions VALUES (?,?,?,?,0)",
                             (instrument_id, label(name, 100), instrument_family(family), integer(sort_order, 0, 2**31 - 1)))
        return instrument_id

    def edit_dictionary(self, kind, row_id, *, name, code=None, archived=False):
        table = {"category": "local_categories", "instrument": "part_definitions"}.get(kind)
        require(table is not None and type(archived) is bool, "Invalid dictionary edit")
        with self._transaction():
            self._get(table, row_id)
            if kind == "category":
                self._db.execute("UPDATE local_categories SET name=?,code=?,isArchived=? WHERE id=?",
                                 (nonempty(name), category_code(code), int(archived), row_id))
            else:
                require(code is None, "Instrument codes are not a frozen product rule")
                parts = self._db.execute("SELECT pieceId FROM piece_parts WHERE partDefinitionId=? AND isArchived=0", (row_id,)).fetchall()
                for part in parts:
                    self._guard_piece(part[0])
                if archived:
                    defaults = self._db.execute("SELECT 1 FROM members WHERE defaultPartDefinitionId=? AND isArchived=0 LIMIT 1", (row_id,)).fetchone()
                    require(not parts and defaults is None, "Instrument is used by an active piece part or member default")
                self._db.execute("UPDATE part_definitions SET name=?,isArchived=? WHERE id=?",
                                 (label(name, 100), int(archived), row_id))

    def import_dictionary_csv(self, kind, text):
        """Additive UTF-8 CSV import; no same-name auto-merge or implicit updates."""
        require(kind in {"category", "instrument"} and isinstance(text, str), "Invalid dictionary kind")
        require(len(text.encode("utf-8")) <= 16 * 1024**2, "Dictionary too large")
        reader = csv.DictReader(io.StringIO(text.lstrip("\ufeff")))
        fields = ["id", "code", "name", "isArchived"] if kind == "category" else ["id", "name", "family", "sortOrder", "isArchived"]
        require(reader.fieldnames is not None and len(reader.fieldnames) == len(fields)
                and set(reader.fieldnames) == set(fields), "Unexpected CSV columns")
        ids = []
        with self._transaction():
            for row in reader:
                require(len(ids) < 100_000 and set(row) == set(fields) and all(x is not None for x in row.values()), "Malformed CSV row")
                row_id = identity(row["id"] or new_id())
                require(row["isArchived"] in ("0", "1"), "Archive flag must be 0 or 1")
                if kind == "category":
                    self._db.execute("INSERT INTO local_categories VALUES (?,?,?,?)",
                                     (row_id, category_code(row["code"]), nonempty(row["name"]), int(row["isArchived"])))
                else:
                    require(re.fullmatch(r"[0-9]+", row["sortOrder"]), "Invalid sort order")
                    self._db.execute("INSERT INTO part_definitions VALUES (?,?,?,?,?)",
                                     (row_id, label(row["name"], 100), instrument_family(row["family"]),
                                      integer(int(row["sortOrder"]), 0, 2**31 - 1), int(row["isArchived"])))
                ids.append(row_id)
        return ids

    def export_dictionary_csv(self, kind):
        table = {"category": "local_categories", "instrument": "part_definitions"}.get(kind)
        require(table is not None, "Invalid dictionary kind")
        rows = self.rows(table)
        fields = ["id", "code", "name", "isArchived"] if kind == "category" else ["id", "name", "family", "sortOrder", "isArchived"]
        output = io.StringIO(newline="")
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
        return output.getvalue()

    def _assign_number(self, piece_id, category_id, number):
        self._get("pieces", piece_id)
        if category_id is not None:
            self._get("local_categories", category_id, active=True)
        if number is None:
            number = self._db.execute("SELECT coalesce(max(number),0)+1 FROM local_number_history").fetchone()[0]
        integer(number, 1)
        owner = self._db.execute("SELECT pieceId FROM local_number_history WHERE number=?", (number,)).fetchone()
        require(owner is None or owner[0] == piece_id, "Duplicate or reserved global sequence")
        if owner is None:
            self._db.execute("INSERT INTO local_number_history VALUES (?,?)", (number, piece_id))
        self._db.execute("INSERT INTO local_numbers VALUES (?,?,?) ON CONFLICT(pieceId) DO UPDATE SET number=excluded.number,categoryId=excluded.categoryId",
                         (piece_id, number, category_id))
        return number

    def add_piece(self, title, category_id=None, *, number=None, piece_id=None):
        piece_id = identity(piece_id or new_id())
        with self._transaction():
            self._db.execute("INSERT INTO pieces VALUES (?,?,?,?,?,?,?)",
                             (piece_id, label(title, 200), None, None, None, 0, now()))
            self._assign_number(piece_id, category_id, number)
        return piece_id

    def assign_number(self, piece_id, category_id=None, *, number=None):
        with self._transaction():
            return self._assign_number(piece_id, category_id, number)

    def archive_piece(self, piece_id):
        with self._transaction():
            self._get("pieces", piece_id)
            enabled = self._db.execute("SELECT 1 FROM concert_items WHERE pieceId=? AND isArchived=0 AND isEnabled=1 LIMIT 1", (piece_id,)).fetchone()
            require(enabled is None, "Disable or remove enabled concert items before archiving the piece")
            self._db.execute("UPDATE pieces SET isArchived=1 WHERE id=?", (piece_id,))

    def numbered_pieces(self):
        return [dict(x) for x in self._db.execute("SELECT p.*,n.number,n.categoryId FROM pieces p LEFT JOIN local_numbers n ON p.id=n.pieceId ORDER BY n.number IS NULL,n.number,p.id")]

    def add_part(self, piece_id, instrument_id):
        part_id = new_id()
        with self._transaction():
            self._get("pieces", piece_id, active=True)
            self._get("part_definitions", instrument_id, active=True)
            self._guard_piece(piece_id)
            self._db.execute("INSERT INTO piece_parts VALUES (?,?,?,?,0)", (part_id, piece_id, instrument_id, None))
        return part_id

    def part_number(self, part_id):
        part = self._get("piece_parts", part_id)
        row = self._db.execute("SELECT number FROM local_numbers WHERE pieceId=?", (part["pieceId"],)).fetchone()
        return None if row is None else row[0]

    def add_movement(self, piece_id, title, sort_order):
        movement_id = new_id()
        with self._transaction():
            self._get("pieces", piece_id, active=True)
            self._guard_piece(piece_id)
            self._db.execute("INSERT INTO movements VALUES (?,?,?,?,0)",
                             (movement_id, piece_id, label(title, 200), integer(sort_order, 0, 2**31 - 1)))
        return movement_id

    def import_pdfs(self, paths, *, cancel=None):
        """All-or-nothing batch; duplicates reuse via bindings, never new IDs."""
        ids = []
        with self._transaction(cancel):
            for path in paths:
                ids.append(self._import_pdf(path, Path(path).stem, None, cancel))
        return ids

    def import_pdf(self, path, *, title=None, pdf_id=None, cancel=None):
        """A later version is stored inactive; explicit activation is separate."""
        with self._transaction(cancel):
            return self._import_pdf(path, Path(path).stem if title is None else title, pdf_id, cancel)

    def _import_pdf(self, path, title, pdf_id, cancel):
        title = label(title, 200)
        if pdf_id is not None:
            self._get("score_pdfs", pdf_id, active=True)
            self._guard_pdf(pdf_id)
        data = read_pdf(path, cancel)
        info = inspect_pdf(data, cancel)
        require(self._db.execute("SELECT 1 FROM score_pdf_versions WHERE lower(fileHash)=?", (info["sha256"],)).fetchone() is None,
                "Duplicate PDF bytes; bind the existing source instead")
        version_id = new_id()
        if pdf_id is None:
            pdf_id = new_id()
            self._db.execute("INSERT INTO score_pdfs VALUES (?,?,NULL,0,?)", (pdf_id, title, now()))
            version_number, activate = 1, True
        else:
            self._get("score_pdfs", pdf_id, active=True)
            version_number = self._db.execute("SELECT coalesce(max(versionNumber),0)+1 FROM score_pdf_versions WHERE scorePdfId=?", (pdf_id,)).fetchone()[0]
            integer(version_number, 1, 2**31 - 1)
            activate = False
        self._db.execute("INSERT INTO score_pdf_versions VALUES (?,?,?,?,?,?,?,?,?,?)",
                         (version_id, pdf_id, version_number, entry(version_id), "", info["sha256"], len(data), info["pageCount"], now(), None))
        self._db.execute("INSERT INTO local_pdf_bytes VALUES (?,?,?)", (version_id, data, json_bytes(info["pages"]).decode()))
        if activate:
            self._db.execute("UPDATE score_pdfs SET activeVersionId=? WHERE id=?", (version_id, pdf_id))
        checkpoint(cancel)
        return {"scorePdfId": pdf_id, "versionId": version_id, **info}

    def activate_version(self, pdf_id, version_id):
        with self._transaction():
            self._get("score_pdfs", pdf_id, active=True)
            version = self._get("score_pdf_versions", version_id)
            require(version["scorePdfId"] == pdf_id, "Version belongs to another PDF")
            self._guard_pdf(pdf_id)
            stored = self._db.execute("SELECT data,geometry FROM local_pdf_bytes WHERE versionId=?", (version_id,)).fetchone()
            require(stored is not None, "Missing original PDF")
            info = inspect_pdf(stored[0])
            require(info["sha256"] == version["fileHash"].lower() and info["bytes"] == version["fileSizeBytes"]
                    and info["pageCount"] == version["pageCount"] and info["pages"] == json.loads(stored[1]), "Original PDF integrity mismatch")
            self._db.execute("UPDATE score_pdfs SET activeVersionId=? WHERE id=?", (version_id, pdf_id))
            # validate_library rejects out-of-range bindings and frozen manifest changes.

    def bind(self, part_id, pdf_id, start_page, end_page, *, movement_id=None):
        binding_id = new_id()
        integer(start_page, 1)
        integer(end_page, start_page)
        with self._transaction():
            part = self._get("piece_parts", part_id, active=True)
            self._get("pieces", part["pieceId"], active=True)
            self._get("part_definitions", part["partDefinitionId"], active=True)
            self._guard_piece(part["pieceId"])
            self._get("score_pdfs", pdf_id, active=True)
            if movement_id is not None:
                self._get("movements", movement_id, active=True)
            existing = self._db.execute("SELECT 1 FROM part_score_bindings WHERE piecePartId=? AND movementId IS ? LIMIT 1", (part_id, movement_id)).fetchone()
            require(existing is None, "Binding already exists, including archived records; do not replace its identity")
            self._db.execute("INSERT INTO part_score_bindings VALUES (?,?,?,?,?,?,0)",
                             (binding_id, part_id, movement_id, pdf_id, start_page, end_page))
        return binding_id

    def source_pages(self, binding_id):
        """Resolve current original version and zero-based pages; never delivery IDs."""
        b = self._get("part_score_bindings", binding_id, active=True)
        pdf = self._get("score_pdfs", b["scorePdfId"], active=True)
        v = self._get("score_pdf_versions", pdf["activeVersionId"])
        require(b["endPage"] <= v["pageCount"], "Source range no longer valid")
        geometry = json.loads(self._db.execute("SELECT geometry FROM local_pdf_bytes WHERE versionId=?", (v["id"],)).fetchone()[0])
        return [{"versionId": v["id"], "sourcePage": p, "sourceHash": v["fileHash"],
                 "geometry": geometry[p]} for p in range(b["startPage"] - 1, b["endPage"])]

    def audit_files(self, *, cancel=None):
        with self._transaction(cancel):
            for v in self.rows("score_pdf_versions"):
                row = self._db.execute("SELECT data,geometry FROM local_pdf_bytes WHERE versionId=?", (v["id"],)).fetchone()
                require(row is not None, "Missing original PDF")
                info = inspect_pdf(row[0], cancel)
                require(info["sha256"] == v["fileHash"].lower() and info["bytes"] == v["fileSizeBytes"]
                        and info["pageCount"] == v["pageCount"] and info["pages"] == json.loads(row[1]), "Original PDF integrity mismatch")
        return len(self.rows("score_pdf_versions"))
