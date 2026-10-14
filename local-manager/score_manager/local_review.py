"""Replaceable loopback-only administrator review adapter; no member service."""
import argparse
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
from threading import Lock

from .errors import Invalid, require
from .store import Catalog
from .validation import parse_json, sha
from .workflow import ASSET_TABLES, DraftCatalog, Workflow, receipt


class Review:
    def __init__(self, workflow, draft_id, renderer):
        self.workflow, self.draft_id = workflow, draft_id
        self.renderer = Path(renderer).resolve()
        require(self.renderer.is_file(), "Explicit local pdftocairo executable required")

    def _snapshot(self, member, callback):
        with DraftCatalog(self.workflow.draft_path(self.draft_id)) as draft, Catalog(self.workflow.path) as live:
            draft._db.execute("BEGIN")
            live._db.execute("BEGIN")
            try:
                require(receipt(live, self.draft_id) is None, "Draft is already committed")
                meta = self.workflow._meta(draft, self.draft_id)
                reviewed = self.workflow._review(live, draft, meta, member, None)
                return callback(live, draft, meta, reviewed)
            finally:
                live._db.rollback()
                draft._db.rollback()

    def members(self):
        # Administrator setup metadata, deliberately separate from member projection.
        status = self.workflow.status(self.draft_id)
        with DraftCatalog(self.workflow.draft_path(self.draft_id)) as draft:
            members = [{"id": x["id"], "name": x["displayName"]} for x in draft.rows("members") if not x["isArchived"]]
        return {"status": status, "members": members}

    def inspect(self, member):
        def project(live, draft, meta, reviewed):
            delta = []
            for table in ASSET_TABLES:
                key = "pieceId" if table == "local_numbers" else "number" if table == "local_number_history" else "id"
                before = {x[key]: x for x in live.rows(table)}
                after = {x[key]: x for x in draft.rows(table)}
                for identity in sorted(before.keys() | after.keys(), key=str):
                    a, b = before.get(identity), after.get(identity)
                    if a != b:
                        delta.append({"table": table, "identity": identity, "before": a, "after": b})
            files = []
            old = {r[0]: (sha(r[1]), r[2]) for r in live._db.execute("SELECT versionId,data,geometry FROM local_pdf_bytes")}
            new = {r[0]: (sha(r[1]), r[2]) for r in draft._db.execute("SELECT versionId,data,geometry FROM local_pdf_bytes")}
            for identity in sorted(old.keys() | new.keys()):
                if old.get(identity) != new.get(identity):
                    files.append({"versionId": identity, "before": old.get(identity), "after": new.get(identity)})
            # Complete administrator summary, including archive restores and numbering
            # reservations, uses the SAME snapshot and hash as member review.
            return {**reviewed, "administratorSummary": {
                "operations": parse_json(meta["operations"]), "assetChanges": delta, "originalFileChanges": files,
                "notice": "Administrator-only full change summary; never a member-facing response"}}
        return self._snapshot(member, project)

    def page(self, member, token, part, binding, page):
        require(type(page) is int and page >= 0, "Invalid zero-based page")
        def render(live, draft, meta, reviewed):
            require(token == reviewed["confirmationHash"], "STALE_PREVIEW: review again")
            segments = [s for p in reviewed["memberPreview"]["pieces"] for v in p["visibleParts"]
                        if v["partId"] == part for s in v.get("segments", [])
                        if s["bindingId"] == binding and page in s["sourcePages"]]
            require(len(segments) == 1, "UNAUTHORIZED_PAGE")
            version = segments[0]["sourceVersionId"]
            data = draft._db.execute("SELECT data FROM local_pdf_bytes WHERE versionId=?", (version,)).fetchone()[0]
            with tempfile.TemporaryDirectory(prefix="score-review-") as folder:
                source, output = Path(folder) / "private.pdf", Path(folder) / "page"
                source.write_bytes(data)
                try:
                    result = subprocess.run([str(self.renderer), "-png", "-singlefile", "-f", str(page + 1),
                                             "-l", str(page + 1), "-scale-to", "1200", "-cropbox", str(source), str(output)],
                                            capture_output=True, timeout=15)
                except subprocess.TimeoutExpired as error:
                    raise Invalid("Page rendering timed out") from error
                require(result.returncode == 0, "Local page rendering failed")
                image = output.with_suffix(".png")
                require(image.stat().st_size <= 8 * 1024 * 1024, "Preview image too large")
                content = image.read_bytes()
                require(content.startswith(b"\x89PNG\r\n\x1a\n"), "Invalid renderer output")
            # Recheck selected sources even when the external renderer took time.
            require(self.workflow._review(live, draft, meta, member, None)["confirmationHash"] == token, "STALE_PREVIEW")
            return content
        return self._snapshot(member, render)


class LocalServer(ThreadingHTTPServer):
    def __init__(self, review, port=0):
        self.review = review
        self.token = secrets.token_urlsafe(32)
        self.operation_lock = Lock()
        super().__init__(("127.0.0.1", port), Handler)
        self.origin = f"http://127.0.0.1:{self.server_port}"

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(5)
        return connection, address


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Never log request bodies or the ephemeral local launch token.

    def send(self, status, data, kind="application/json; charset=utf-8"):
        self.send_response(status)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; img-src blob:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
        try:
            self.end_headers()
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Navigation/disconnect cannot undo an authoritative commit receipt.

    def valid_origin(self):
        require(self.headers.get("Host") == self.server.origin.removeprefix("http://"), "Invalid local Host")
        require(self.headers.get("Origin", self.server.origin) == self.server.origin, "Cross-origin request rejected")
        require(self.headers.get("Sec-Fetch-Site", "same-origin") in {"same-origin", "none"}, "Cross-site request rejected")

    def do_GET(self):
        try:
            self.valid_origin()
            assets = {"/": ("index.html", "text/html; charset=utf-8"), "/ui.js": ("ui.js", "text/javascript; charset=utf-8"), "/style.css": ("style.css", "text/css; charset=utf-8")}
            require(self.path in assets, "No file/PDF download route")
            file, kind = assets[self.path]
            self.send(200, (Path(__file__).parent / "review_ui" / file).read_bytes(), kind)
        except (Invalid, OSError) as error:
            self.send(403, json.dumps({"error": str(error)}).encode())

    def do_POST(self):
        # Browsers pre-open idle connections; they must not block the accept loop.
        # Domain operations remain serialized, with the original SQLite guards.
        with self.server.operation_lock:
            self.post_operation()

    def post_operation(self):
        try:
            self.valid_origin()
            require(hmac.compare_digest(self.headers.get("X-Local-Review", ""), self.server.token), "Local launch token required")
            require(self.headers.get("Content-Type") == "application/json", "JSON required")
            length = int(self.headers.get("Content-Length", "0"))
            require(0 < length <= 8192, "Request size invalid")
            args = parse_json(self.rfile.read(length))
            routes = {"/api/status": set(), "/api/review": {"member"}, "/api/page": {"member", "hash", "part", "binding", "page"},
                      "/api/confirm": {"member", "hash", "confirm"}, "/api/cancel": {"confirm"}}
            require(self.path in routes and isinstance(args, dict) and set(args) == routes[self.path], "Unsupported request")
            r = self.server.review
            if self.path == "/api/status":
                result = r.members()
            elif self.path == "/api/review":
                result = r.inspect(args["member"])
            elif self.path == "/api/page":
                data = r.page(args["member"], args["hash"], args["part"], args["binding"], args["page"])
                self.send(200, data, "image/png")
                return
            elif self.path == "/api/confirm":
                require(args["confirm"] is True, "Explicit confirmation required")
                result = r.workflow.confirm(r.draft_id, args["member"], args["hash"])
            else:
                require(args["confirm"] is True, "Explicit cancellation required")
                result = r.workflow.cancel(r.draft_id)
            self.send(200, json.dumps(result, ensure_ascii=False).encode())
        except Exception as error:
            # Never include native tracebacks, source bytes or renderer stderr.
            message = str(error) if isinstance(error, (Invalid, ValueError, TypeError)) else "Local operation failed; assets were not confirmed by this response"
            self.send(409, json.dumps({"error": message}).encode())


def main():
    parser = argparse.ArgumentParser(description="Administrator-only local draft review; no login, LAN service or deployment")
    parser.add_argument("--db", required=True, type=Path)
    parser.add_argument("--draft", required=True)
    parser.add_argument("--renderer", required=True, type=Path, help="explicit trusted local pdftocairo executable; not bundled")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()
    require(args.renderer.is_absolute(), "Renderer path must be absolute")
    with LocalServer(Review(Workflow(args.db), args.draft, args.renderer), args.port) as server:
        print("Local administrator tool; do not share this session or expose the port.", flush=True)
        print(server.origin + "/#" + server.token, flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == "__main__":
    main()
