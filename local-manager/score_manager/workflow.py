"""Replaceable local workflow: isolated drafts, read-only review, atomic receipts.

Drafts are owned staging databases, never an Android/live catalog. Confirmation
is append-only (except local numbering); no permissions editor is exposed.
"""
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import sqlite3
import tempfile
import uuid
import zipfile

from .archive import restore_archive
from .errors import checkpoint, require, Invalid
from .pdf import read_pdf
from .store import Catalog, LOCAL_TABLES
from .validation import TABLES, insert_rows, json_bytes, parse_json, sha
from .visibility import Visibility

ASSET_TABLES = TABLES + LOCAL_TABLES
RECEIPTS = "local_workflow_receipts"
FORMAT_NOTICE = {
    "completeRecovery": "Keep the COMPLETE local bundle: library.zip + catalog.json + README.txt",
    "androidV1Only": "library.zip alone does NOT restore numbering, categories or reserved-number history",
    "distribution": "Administrator backup only; not member PDF delivery or a permission-safe split file",
}
ACTIONS = {"add_member", "add_category", "add_instrument", "import_dictionary_csv",
           "add_piece", "assign_number", "add_part", "add_movement", "bind",
           "import_pdf", "import_pdfs", "restore_archive"}


def digest(catalog):
    h = hashlib.sha256()
    for table in ASSET_TABLES:
        h.update(json_bytes([table, catalog.rows(table)]))
    for row in catalog._db.execute("SELECT versionId,data,geometry FROM local_pdf_bytes ORDER BY versionId"):
        h.update(json_bytes([row[0], sha(row[1]), row[2]]))
    return h.hexdigest()


def receipt(catalog, draft_id):
    exists = catalog._db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (RECEIPTS,)).fetchone()
    if not exists:
        return None
    row = catalog._db.execute("SELECT result FROM local_workflow_receipts WHERE draftId=?", (draft_id,)).fetchone()
    return parse_json(row[0]) if row else None


class DraftCatalog(Catalog):
    @contextmanager
    def _transaction(self, cancel=None):
        # Compose existing, tested Catalog operations within the draft's atomic
        # operation+request-receipt transaction without changing the core API.
        if not self._db.in_transaction:
            with super()._transaction(cancel):
                yield
            return
        name = "draft_" + uuid.uuid4().hex
        self._db.execute("SAVEPOINT " + name)
        try:
            checkpoint(cancel)
            yield
            self._validate()
            checkpoint(cancel)
            self._db.execute("RELEASE " + name)
        except BaseException:
            self._db.execute("ROLLBACK TO " + name)
            self._db.execute("RELEASE " + name)
            raise


class Workflow:
    def __init__(self, catalog_path, drafts_path=None):
        self.path = Path(catalog_path).resolve()
        self.drafts = Path(drafts_path).resolve() if drafts_path else self.path.parent / (self.path.name + ".drafts")
        with Catalog(self.path) as live:
            require(live._db.execute("SELECT 1 FROM sqlite_master WHERE name='local_draft'").fetchone() is None,
                    "A draft cannot be used as a committed workbench")

    def draft_path(self, draft_id):
        require(str(uuid.UUID(draft_id)) == draft_id, "Invalid draft ID")
        return self.drafts / (draft_id + ".sqlite")

    def start(self, *, cancel=None):
        self.drafts.mkdir(mode=0o700, parents=True, exist_ok=True)
        draft_id = str(uuid.uuid4())
        destination = self.draft_path(draft_id)
        fd, temp = tempfile.mkstemp(prefix=".preparing-", dir=self.drafts)
        os.close(fd)
        try:
            with Catalog(self.path) as live:
                live._db.execute("BEGIN")
                try:
                    live._validate()
                    base = digest(live)
                    target = sqlite3.connect(temp)
                    try:
                        live._db.backup(target, pages=128, progress=lambda *_: checkpoint(cancel))
                        # Receipts are live-only. Drafts never replay workflow history.
                        target.execute("DROP TABLE IF EXISTS local_workflow_receipts")
                        target.execute("CREATE TABLE local_draft (id TEXT PRIMARY KEY, target TEXT, base TEXT, state TEXT, operations TEXT)")
                        target.execute("INSERT INTO local_draft VALUES (?,?,?,?,?)", (draft_id, str(self.path), base, "DRAFT", "[]"))
                        target.commit()
                    finally:
                        target.close()
                finally:
                    live._db.rollback()
            checkpoint(cancel)
            os.link(temp, destination)
            return {"draftId": draft_id, "state": "DRAFT", "committed": False, "formatNotice": FORMAT_NOTICE}
        finally:
            Path(temp).unlink(missing_ok=True)

    def _meta(self, draft, draft_id):
        row = draft._db.execute("SELECT * FROM local_draft WHERE id=?", (draft_id,)).fetchone()
        require(row is not None and row["target"] == str(self.path), "Draft belongs to another workbench")
        return dict(row)

    def status(self, draft_id):
        with Catalog(self.path) as live:
            done = receipt(live, draft_id)
            if done:
                return done
        with DraftCatalog(self.draft_path(draft_id)) as draft:
            meta = self._meta(draft, draft_id)
            return {"draftId": draft_id, "state": meta["state"], "committed": False,
                    "operations": len(parse_json(meta["operations"])), "formatNotice": FORMAT_NOTICE}

    def edit(self, draft_id, action, arguments, *, request_id, cancel=None):
        require(action in ACTIONS and isinstance(arguments, dict), "Unsupported draft operation")
        require(isinstance(request_id, str) and 1 <= len(request_id) <= 200, "An operation request ID is required")
        request_hash = sha(json_bytes([action, arguments]))
        with DraftCatalog(self.draft_path(draft_id)) as draft:
            with draft._transaction(cancel):
                meta = self._meta(draft, draft_id)
                with Catalog(self.path) as live:
                    require(receipt(live, draft_id) is None, "Draft is already committed")
                require(meta["state"] == "DRAFT", "Draft is cancelled")
                operations = parse_json(meta["operations"])
                for old in operations:
                    if old["requestId"] == request_id:
                        require(old["requestHash"] == request_hash, "Request ID reused for different input")
                        return {"state": "DRAFT", "committed": False, "result": old["result"], "replayed": True, "formatNotice": FORMAT_NOTICE}
                require("cancel" not in arguments, "Cancellation is not a serialized draft argument")
                args = dict(arguments)
                sources = []
                if action in {"import_pdf", "import_pdfs"}:
                    paths = [args["path"]] if action == "import_pdf" else args["paths"]
                    for path in paths:
                        resolved = str(Path(path).resolve())
                        sources.append({"path": resolved, "sha256": sha(read_pdf(resolved, cancel)), "kind": "pdf"})
                    if action == "import_pdf":
                        args["path"] = sources[0]["path"]
                    else:
                        args["paths"] = [s["path"] for s in sources]
                    args["cancel"] = cancel
                    result = getattr(draft, action)(**args)
                    imported = [result] if action == "import_pdf" else result
                    require([s["sha256"] for s in sources] == [x["sha256"] for x in imported], "PDF changed during staging")
                elif action == "restore_archive":
                    from .archive import _read_input
                    path = str(Path(args["source"]).resolve())
                    archive_data = _read_input(path, cancel)
                    data_hash = sha(archive_data)
                    require(data_hash == args["expected_sha256"], "Archive changed before staging")
                    result = restore_archive(draft, path, expected_sha256=data_hash, cancel=cancel)
                    with zipfile.ZipFile(io.BytesIO(archive_data)) as archive:
                        result["recoveryScope"] = "COMPLETE_LOCAL_BUNDLE" if "catalog.json" in archive.namelist() else "ANDROID_V1_ONLY_NO_NUMBERING_CATEGORIES_HISTORY"
                    sources = [{"path": path, "sha256": data_hash, "kind": "archive"}]
                else:
                    result = getattr(draft, action)(**args)
                operations.append({"requestId": request_id, "requestHash": request_hash, "action": action,
                                   "arguments": arguments, "result": result, "sources": sources})
                draft._db.execute("UPDATE local_draft SET operations=? WHERE id=?", (json_bytes(operations).decode(), draft_id))
                return {"state": "DRAFT", "committed": False, "result": result, "replayed": False, "formatNotice": FORMAT_NOTICE}

    def _review(self, live, draft, meta, member_id, cancel):
        require(meta["state"] == "DRAFT", "Draft is cancelled")
        require(digest(live) == meta["base"], "STALE_DRAFT: live files or data changed; start a new draft")
        operations = parse_json(meta["operations"])
        require(operations, "Nothing to confirm")
        from .archive import _read_input
        for operation in operations:
            for source in operation["sources"]:
                data = read_pdf(source["path"], cancel) if source["kind"] == "pdf" else _read_input(source["path"], cancel)
                require(sha(data) == source["sha256"], "Source file changed; stage a new draft")
        draft.audit_files(cancel=cancel)
        token = sha(json_bytes([meta["id"], meta["base"], digest(draft), operations, member_id]))
        preview = Visibility({t: draft.rows(t) for t in TABLES}).member_preview(member_id)
        numbers = {x["pieceId"]: x for x in draft.rows("local_numbers")}
        categories = {x["id"]: x for x in draft.rows("local_categories")}
        for piece in preview["pieces"]:
            number = numbers.get(piece["pieceId"])
            piece["number"] = number["number"] if number else None
            category = categories.get(number["categoryId"]) if number else None
            piece["category"] = {k: category[k] for k in ("id", "code", "name")} if category else None
        return {"draftId": meta["id"], "state": "DRAFT", "committed": False,
                "confirmationHash": token, "memberPreview": preview,
                "operationCount": len(operations), "formatNotice": FORMAT_NOTICE}

    def review(self, draft_id, member_id, *, cancel=None):
        # Both catalogs are held at read snapshots. No confirmation token or
        # preview is persisted; reviewing never writes either database.
        with DraftCatalog(self.draft_path(draft_id)) as draft, Catalog(self.path) as live:
            draft._db.execute("BEGIN")
            live._db.execute("BEGIN")
            try:
                require(receipt(live, draft_id) is None, "Draft is already committed")
                return self._review(live, draft, self._meta(draft, draft_id), member_id, cancel)
            finally:
                live._db.rollback()
                draft._db.rollback()

    def _apply(self, live, draft, operations):
        # Validate a closed, append-only workflow delta. Never bulk overwrite
        # existing Room rows, permissions, private ink or frozen payloads.
        old = {t: live.rows(t) for t in ASSET_TABLES}
        new = {t: draft.rows(t) for t in ASSET_TABLES}
        restore = any(x["action"] == "restore_archive" for x in operations)
        if restore:
            require(not any(old.values()), "Archive workflow requires an empty live workbench")
        permitted = {t: set() for t in ASSET_TABLES}
        blobs = set()
        targets = {"add_member": "members", "add_category": "local_categories", "add_instrument": "part_definitions",
                   "add_piece": "pieces", "add_part": "piece_parts", "add_movement": "movements", "bind": "part_score_bindings"}
        for op in operations:
            action, args, result = op["action"], op["arguments"], op["result"]
            require(action in ACTIONS, "Unknown recorded operation")
            if action in targets:
                permitted[targets[action]].add(result)
            if action == "import_dictionary_csv":
                permitted["local_categories" if args["kind"] == "category" else "part_definitions"].update(result)
            if action in {"add_piece", "assign_number"}:
                pid = result if action == "add_piece" else args["piece_id"]
                permitted["local_numbers"].add(pid)
                permitted["local_number_history"].update(x["number"] for x in new["local_number_history"] if x["pieceId"] == pid)
            if action in {"add_part", "add_movement"}:
                live._guard_piece(args["piece_id"])
            if action == "bind":
                live._guard_piece(draft._get("piece_parts", args["part_id"])["pieceId"])
            if action in {"import_pdf", "import_pdfs"}:
                for result_item in ([result] if action == "import_pdf" else result):
                    permitted["score_pdfs"].add(result_item["scorePdfId"])
                    permitted["score_pdf_versions"].add(result_item["versionId"])
                    blobs.add(result_item["versionId"])
                if action == "import_pdf" and args.get("pdf_id") is not None:
                    live._guard_pdf(args["pdf_id"])
        for table in TABLES + ("local_categories", "local_number_history", "local_numbers"):
            key = "pieceId" if table == "local_numbers" else "number" if table == "local_number_history" else "id"
            before = {x[key]: x for x in old[table]}
            after = {x[key]: x for x in new[table]}
            require(set(before) <= set(after), "Workflow cannot delete existing assets")
            for row_id, row in after.items():
                if row_id in before:
                    if before[row_id] != row:
                        require(table == "local_numbers" and row_id in permitted[table], "Workflow cannot overwrite existing assets")
                        live._db.execute("UPDATE local_numbers SET number=?,categoryId=? WHERE pieceId=?", (row["number"], row["categoryId"], row_id))
                else:
                    require(restore or row_id in permitted[table], "Unrecorded draft change")
                    if table in TABLES:
                        insert_rows(live._db, table, [row])
                    else:
                        columns = list(row)
                        live._db.execute(f'INSERT INTO {table} ({",".join(columns)}) VALUES ({",".join("?" for _ in columns)})', list(row.values()))
        for row in draft._db.execute("SELECT versionId,data,geometry FROM local_pdf_bytes"):
            existing = live._db.execute("SELECT data,geometry FROM local_pdf_bytes WHERE versionId=?", (row[0],)).fetchone()
            if existing:
                require(tuple(existing) == tuple(row)[1:], "Workflow cannot replace original PDF bytes")
            else:
                require(restore or row[0] in blobs, "Unrecorded PDF")
                live._db.execute("INSERT INTO local_pdf_bytes VALUES (?,?,?)", tuple(row))

    def confirm(self, draft_id, member_id, expected_hash, *, cancel=None):
        require(isinstance(expected_hash, str) and len(expected_hash) == 64, "Explicit review hash is required")
        with DraftCatalog(self.draft_path(draft_id)) as draft, Catalog(self.path) as live:
            # Lock ordering is always draft then live. The live transaction owns
            # both the assets and idempotency receipt: crash/retry cannot duplicate.
            draft._db.execute("BEGIN IMMEDIATE")
            try:
                with live._transaction(cancel):
                    done = receipt(live, draft_id)
                    if done:
                        require(done["confirmationHash"] == expected_hash and done["reviewedMemberId"] == member_id, "Confirmation does not match committed receipt")
                        return done
                    meta = self._meta(draft, draft_id)
                    reviewed = self._review(live, draft, meta, member_id, cancel)
                    require(reviewed["confirmationHash"] == expected_hash, "STALE_PREVIEW: draft changed; review again")
                    self._apply(live, draft, parse_json(meta["operations"]))
                    result = {"draftId": draft_id, "state": "COMMITTED", "committed": True,
                              "confirmationHash": expected_hash, "reviewedMemberId": member_id,
                              "operationCount": reviewed["operationCount"], "formatNotice": FORMAT_NOTICE}
                    live._db.execute("CREATE TABLE IF NOT EXISTS local_workflow_receipts (draftId TEXT PRIMARY KEY, result TEXT NOT NULL)")
                    live._db.execute("INSERT INTO local_workflow_receipts VALUES (?,?)", (draft_id, json_bytes(result).decode()))
                return result
            finally:
                draft._db.rollback()  # No draft status write can fail after live commit.

    def cancel(self, draft_id):
        with DraftCatalog(self.draft_path(draft_id)) as draft, Catalog(self.path) as live:
            draft._db.execute("BEGIN IMMEDIATE")
            live._db.execute("BEGIN IMMEDIATE")
            try:
                require(receipt(live, draft_id) is None, "Committed work cannot be cancelled")
                self._meta(draft, draft_id)
                draft._db.execute("DELETE FROM local_pdf_bytes")
                draft._db.execute("UPDATE local_draft SET state='CANCELLED' WHERE id=?", (draft_id,))
                draft._db.commit()
            finally:
                live._db.rollback()
                draft._db.rollback()
        return {"draftId": draft_id, "state": "CANCELLED", "committed": False}


def export_committed_bundle(catalog_path, destination, owner, *, cancel=None):
    from .archive import export_bundle
    Workflow(catalog_path)  # Reject using a pending/cancelled draft as the live DB.
    with Catalog(catalog_path) as live:
        result = export_bundle(live, destination, owner, cancel=cancel)
    return {"state": "EXPORTED", "bundleKind": "COMPLETE_LOCAL_BUNDLE",
            **result, "formatNotice": FORMAT_NOTICE}
