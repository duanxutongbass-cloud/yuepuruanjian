"""Open export/restore: unchanged Android v1 ZIP plus a hash-bound sidecar.

The outer local workbench bundle is not an Android archive. Extract library.zip
for Android; its numbering/categories are intentionally only in catalog.json.
PERSONAL archives are rejected here, not merged into the administrator library.
"""
import io
from pathlib import Path
import os
import tempfile
import zipfile

from .errors import checkpoint, require
from .pdf import inspect_pdf, PDF_LIMIT
from .store import LOCAL_TABLES
from .validation import (TABLES, JSON_LIMIT, TOTAL_LIMIT, entry, identity,
                         integer, insert_rows, json_bytes, parse_json, sha, validate_library)

ROOT_FIELDS = {"format", "formatVersion", "databaseVersion", "kind", "owner", "createdAtEpochMillis", "tables", "files", "versionReferences"}
SIDECAR_FIELDS = {"format", "formatVersion", "librarySha256", "categories", "numbers", "numberHistory"}
SIDECAR_TABLES = {"categories": "local_categories", "numbers": "local_numbers", "numberHistory": "local_number_history"}
SIDECAR_COLUMNS = {"categories": {"id", "code", "name", "isArchived"}, "numbers": {"pieceId", "number", "categoryId"},
                   "numberHistory": {"number", "pieceId"}}
README = b"Local workbench bundle v1. library.zip is an unchanged Android schema-3 archive.\nKeep catalog.json with it to retain numbering/category IDs and number reservations.\nRestore only into an empty local workbench. Never send this administrator mother library to members.\nThis bundle contains no personal annotations, authentication secrets or device/cache state.\nPDFs are original bytes; no distribution, sanitization or cropping has been performed.\n"


def _read_member(z, name, limit, cancel):
    require(z.getinfo(name).file_size <= limit, "Archive entry exceeds limit")
    output = bytearray()
    with z.open(name) as stream:
        while True:
            checkpoint(cancel)
            block = stream.read(65536)
            if not block:
                break
            require(len(output) + len(block) <= limit, "Archive entry exceeds limit")
            output.extend(block)
    return bytes(output)  # zipfile verifies CRC on complete reads.


def _zip_entries(z, limit):
    info = z.infolist()
    names = [x.filename for x in info]
    require(len(info) <= limit and len(set(names)) == len(names), "Duplicate/too many ZIP entries")
    require(all(not x.is_dir() and not x.flag_bits & 1 for x in info), "Directories/encrypted ZIP entries unsupported")
    require(sum(x.file_size for x in info) <= TOTAL_LIMIT + 2 * JSON_LIMIT + 65536, "Expanded ZIP too large")
    return set(names)


def _read_input(path, cancel):
    require(Path(path).stat().st_size <= TOTAL_LIMIT, "Archive too large")
    output = bytearray()
    with Path(path).open("rb") as stream:
        while True:
            checkpoint(cancel)
            block = stream.read(65536)
            if not block:
                break
            require(len(output) + len(block) <= TOTAL_LIMIT, "Archive grew beyond limit")
            output.extend(block)
    return bytes(output)


def _write_zip(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as z:
        for name, data in entries:
            # Fixed timestamps make identical snapshots independently comparable.
            z.writestr(zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0)), data)
    require(output.tell() <= TOTAL_LIMIT, "Archive exceeds limit")
    return output.getvalue()


def _library_bytes(catalog, owner, cancel):
    tables = validate_library(catalog._db, require_admin=True)
    actor = catalog._get("members", owner, active=True)
    require(actor["role"] == "ADMIN", "Mother library export needs a local administrator")
    tables["annotation_pages"] = [x for x in tables["annotation_pages"] if x["ownerKey"] == "shared"]
    shared_ids = {x["id"] for x in tables["annotation_pages"]}
    tables["annotation_revisions"] = [x for x in tables["annotation_revisions"] if x["documentId"] in shared_ids]
    # Row ordering is deterministic and agrees with Android's ID ordering.
    tables = {table: sorted(rows, key=lambda x: x["id"]) for table, rows in tables.items()}
    records, entries = [], []
    require(len(tables["score_pdf_versions"]) <= 5000, "Too many PDFs")
    total = 0
    for v in tables["score_pdf_versions"]:
        checkpoint(cancel)
        stored = catalog._db.execute("SELECT data,geometry FROM local_pdf_bytes WHERE versionId=?", (v["id"],)).fetchone()
        require(stored is not None, "Missing original PDF")
        data = stored[0]
        info = inspect_pdf(data, cancel)
        require(info["sha256"] == v["fileHash"].lower() and len(data) == v["fileSizeBytes"]
                and info["pageCount"] == v["pageCount"] and info["pages"] == parse_json(stored[1]), "Original PDF integrity mismatch")
        total += len(data)
        require(total <= TOTAL_LIMIT, "Total PDFs exceed limit")
        name = entry(v["id"])
        v["managedFilePath"] = name
        records.append({"versionId": v["id"], "entry": name, "bytes": len(data), "sha256": info["sha256"]})
        entries.append((name, data))
    from .store import now
    root = {"format": "ensemble-score-library", "formatVersion": 1, "databaseVersion": 3,
            "kind": "LIBRARY", "owner": identity(owner), "createdAtEpochMillis": now(),
            "tables": tables, "files": records, "versionReferences": []}
    entries.extend([("manifest.json", json_bytes(root)), ("README.txt", README)])
    return _write_zip(entries)


def export_bundle(catalog, destination, owner, *, cancel=None):
    """Create a new file only. One complete bundle becomes visible at commit."""
    destination = Path(destination)
    require(not destination.exists(), "Export target already exists")
    with catalog._transaction(cancel):
        library = _library_bytes(catalog, owner, cancel)
        sidecar = {"format": "ensemble-score-local-catalog", "formatVersion": 1, "librarySha256": sha(library),
                   **{key: catalog.rows(table) for key, table in SIDECAR_TABLES.items()}}
        bundle = _write_zip([("library.zip", library), ("catalog.json", json_bytes(sidecar)), ("README.txt", README)])
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix=".score-export-", delete=False) as out:
            temporary = Path(out.name)
            for offset in range(0, len(bundle), 65536):
                checkpoint(cancel)
                out.write(bundle[offset:offset + 65536])
            out.flush()
            os.fsync(out.fileno())
        checkpoint(cancel)
        # Same-filesystem link provides atomic no-replace publication. If the
        # filesystem lacks hard links, fail safely; never fall back to overwrite.
        os.link(temporary, destination)
        return {"sha256": sha(bundle), "bytes": len(bundle)}
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def _restore_library(catalog, data, cancel):
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names = _zip_entries(z, 5002)
        require({"manifest.json", "README.txt"} <= names, "Missing archive manifest/readme")
        root = parse_json(_read_member(z, "manifest.json", JSON_LIMIT, cancel))
        require(isinstance(root, dict) and set(root) == ROOT_FIELDS, "Unknown archive fields")
        require(root["format"] == "ensemble-score-library" and type(root["formatVersion"]) is int
                and root["formatVersion"] == 1 and type(root["databaseVersion"]) is int
                and root["databaseVersion"] == 3, "Unsupported archive version")
        require(root["kind"] == "LIBRARY", "Personal archives stay in their existing owner-only workflow")
        require(identity(root["owner"]) != "shared", "Reserved archive owner")
        integer(root["createdAtEpochMillis"])
        require(root["versionReferences"] == [], "Mother library must not contain personal references")
        t = root["tables"]
        require(isinstance(t, dict) and set(t) == set(TABLES), "Unknown/missing tables")
        require(isinstance(t["annotation_pages"], list) and all(isinstance(x, dict) and x.get("ownerKey") == "shared" for x in t["annotation_pages"]), "Mother library contains personal ink")
        records = root["files"]
        require(isinstance(records, list) and len(records) <= 5000, "Invalid file records")
        files = {}
        for record in records:
            require(isinstance(record, dict) and set(record) == {"versionId", "entry", "bytes", "sha256"}, "Invalid file record")
            version_id = identity(record["versionId"])
            require(version_id not in files and record["entry"] == entry(version_id), "Duplicate version or unsafe entry")
            integer(record["bytes"], 1, PDF_LIMIT)
            require(isinstance(record["sha256"], str), "Invalid hash")
            files[version_id] = record
        require(names == {"manifest.json", "README.txt"} | {x["entry"] for x in files.values()}, "Extra/missing ZIP entries")
        # Original deferred active-version FK is retained. Other FK insert order
        # matches ArchiveFormat.tables; failures roll back the entire import.
        for table in TABLES:
            checkpoint(cancel)
            insert_rows(catalog._db, table, t[table])
        validate_library(catalog._db, require_admin=True)
        versions = {x["id"]: x for x in t["score_pdf_versions"]}
        require(set(files) == set(versions), "Missing/extra version file")
        total = 0
        for version_id, record in files.items():
            data = _read_member(z, record["entry"], PDF_LIMIT, cancel)
            total += len(data)
            require(total <= TOTAL_LIMIT, "Too many PDF bytes")
            v = versions[version_id]
            require(len(data) == record["bytes"] == v["fileSizeBytes"]
                    and sha(data) == record["sha256"].lower() == v["fileHash"].lower(), "PDF checksum/size mismatch")
            info = inspect_pdf(data, cancel)
            require(info["pageCount"] == v["pageCount"], "PDF page count mismatch")
            catalog._db.execute("INSERT INTO local_pdf_bytes VALUES (?,?,?)", (version_id, data, json_bytes(info["pages"]).decode()))
        _read_member(z, "README.txt", 65536, cancel)


def _restore_sidecar(catalog, data, library):
    root = parse_json(data)
    require(isinstance(root, dict) and set(root) == SIDECAR_FIELDS, "Unknown/missing sidecar fields")
    require(root["format"] == "ensemble-score-local-catalog" and type(root["formatVersion"]) is int
            and root["formatVersion"] == 1 and root["librarySha256"] == sha(library), "Wrong sidecar version or library association")
    # History must precede current-number foreign keys.
    for key in ("categories", "numberHistory", "numbers"):
        rows = root[key]
        require(isinstance(rows, list) and len(rows) <= 100_000, "Invalid sidecar row list")
        columns = sorted(SIDECAR_COLUMNS[key])
        for row in rows:
            require(isinstance(row, dict) and set(row) == set(columns), "Unknown/missing sidecar row fields")
            for field, value in row.items():
                if field in {"id", "pieceId"} or field == "categoryId" and value is not None:
                    identity(value)
                elif field in {"number", "isArchived"}:
                    integer(value, 1 if field == "number" else 0, MAX_ARCHIVE_INT if field == "number" else 1)
                elif field != "categoryId":
                    require(isinstance(value, str), "Invalid sidecar text")
            catalog._db.execute(f'INSERT INTO {SIDECAR_TABLES[key]} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})',
                                [row[x] for x in columns])


MAX_ARCHIVE_INT = 2**63 - 1


def restore_archive(catalog, source, *, expected_sha256, cancel=None):
    """Restore a legacy library.zip or a complete local bundle into EMPTY storage.

    Caller supplies the hash from explicit selection/preview. A different source
    cannot be silently substituted. Legacy imports keep numbering unassigned.
    """
    data = _read_input(source, cancel)
    require(sha(data) == expected_sha256, "Archive changed since selection")
    with catalog._transaction(cancel):
        require(all(not catalog.rows(table) for table in TABLES + LOCAL_TABLES)
                and catalog._db.execute("SELECT count(*) FROM local_pdf_bytes").fetchone()[0] == 0, "Restore requires an empty workbench; overwrite is forbidden")
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            names = _zip_entries(z, 5002)
            if "library.zip" in names:
                require(names == {"library.zip", "catalog.json", "README.txt"}, "Unknown bundle entries")
                library = _read_member(z, "library.zip", TOTAL_LIMIT, cancel)
                sidecar = _read_member(z, "catalog.json", JSON_LIMIT, cancel)
                _read_member(z, "README.txt", 65536, cancel)
                _restore_library(catalog, library, cancel)
                _restore_sidecar(catalog, sidecar, library)
            else:
                _restore_library(catalog, data, cancel)
        checkpoint(cancel)
    return {"pieces": len(catalog.rows("pieces")), "versions": len(catalog.rows("score_pdf_versions")),
            "numberedPieces": len(catalog.rows("local_numbers"))}
