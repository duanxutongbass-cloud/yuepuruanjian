"""Admin-only staged upload and atomic, idempotent publication of independent PDFs."""
import hashlib
import json
import os
import re
import tempfile
import uuid
from pathlib import Path

from catalog import MAX_PDF, validate_pdf
from service import UUID, fields, label, require


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


class Publisher:
    def __init__(self, catalog):
        self.catalog, self.service = catalog, catalog.service
        with self.service.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS uploads(
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    spec TEXT NOT NULL, expires INTEGER NOT NULL, complete INTEGER NOT NULL DEFAULT 0,
                    UNIQUE(owner,request));
                CREATE TABLE IF NOT EXISTS publications(
                    owner TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL, spec TEXT NOT NULL,
                    receipt TEXT NOT NULL, PRIMARY KEY(owner,request));
                CREATE TABLE IF NOT EXISTS removed_artifacts(id TEXT PRIMARY KEY REFERENCES artifacts(id));
                CREATE TABLE IF NOT EXISTS cancelled_uploads(owner TEXT NOT NULL, request TEXT NOT NULL, PRIMARY KEY(owner,request));
            """)

    def admin(self, db, token, device):
        actor = self.service.authenticate(db, token, device)
        require(actor["role"] == "admin", "forbidden", 403)
        return actor["id"]

    def prepare(self, body, token, device):
        fields(body, {"requestId", "title", "partName", "scoreNumber", "category", "sha256", "size", "independentSource"})
        require(isinstance(body["requestId"], str) and UUID.fullmatch(body["requestId"]))
        require(body["independentSource"] is True, "independent_part_confirmation_required")
        spec = {k: label(body[k]) for k in ("title", "partName", "scoreNumber", "category")}
        require(isinstance(body["sha256"], str) and re.fullmatch("[a-f0-9]{64}", body["sha256"]))
        require(type(body["size"]) is int and 0 < body["size"] <= MAX_PDF, "invalid_pdf")
        spec.update(sha256=body["sha256"], size=body["size"])
        encoded = canonical(spec)
        with self.service.connection() as db:
            owner = self.admin(db, token, device)
            # Validate the original number; label normalization must not silently
            # turn padded/otherwise noncanonical input into a different value.
            self.catalog.write_number(body['scoreNumber'])
            self.catalog.check_retired(db,body['scoreNumber'],title=spec['title'],category=spec['category'])
            require(not db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?', (owner, body['requestId'])).fetchone(), 'upload_cancelled', 409)
            row = db.execute("SELECT * FROM uploads WHERE owner=? AND request=?", (owner, body["requestId"])).fetchone()
            if row:
                self.catalog.check_retired(db,body['scoreNumber'],row['id'],spec['title'],spec['category'])
                require(row["spec"] == encoded, "request_conflict", 409)
                db.execute("UPDATE uploads SET expires=? WHERE id=?", (int(self.service.clock()) + 86400, row["id"]))
                return dict(uploadId=row["id"], complete=bool(row["complete"]))
            # Expired incomplete requests contain no committed PDF and can be retried anew.
            db.execute("DELETE FROM uploads WHERE complete=0 AND expires<?", (int(self.service.clock()),))
            require(db.execute("SELECT count(*) FROM uploads WHERE complete=0").fetchone()[0] < 200, "too_many_pending_uploads", 409)
            upload_id = str(uuid.uuid4())
            db.execute("INSERT INTO uploads VALUES(?,?,?,?,?,0)",
                       (upload_id, owner, body["requestId"], encoded, int(self.service.clock()) + 86400))
            return dict(uploadId=upload_id, complete=False)

    def upload(self, upload_id, stream, length, token, device):
        require(UUID.fullmatch(upload_id), "not_found", 404)
        with self.service.connection() as db:
            owner = self.admin(db, token, device)
            row = db.execute("SELECT * FROM uploads WHERE id=? AND owner=?", (upload_id, owner)).fetchone()
            require(row is not None, "not_found", 404)
            require(not db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?', (owner, row['request'])).fetchone(), 'upload_cancelled', 409)
            spec = json.loads(row["spec"])
            self.catalog.write_number(spec['scoreNumber'])
            self.catalog.check_retired(db,spec['scoreNumber'],upload_id,spec['title'],spec['category'])
            require(row["expires"] >= self.service.clock(), "upload_expired", 409)
            require(length == spec["size"], "size_mismatch", 400)
        # Read only after authorization. Each concurrent request owns its temporary file.
        with tempfile.TemporaryDirectory(prefix=".upload-", dir=self.catalog.root) as temp:
            pending = Path(temp) / "part.pdf"
            checksum, remaining = hashlib.sha256(), length
            with pending.open("xb") as dest:
                while remaining:
                    chunk = stream.read(min(65536, remaining))
                    require(bool(chunk), "upload_incomplete")
                    remaining -= len(chunk)
                    checksum.update(chunk)
                    dest.write(chunk)
                dest.flush()
                os.fsync(dest.fileno())
            require(checksum.hexdigest() == spec["sha256"], "hash_mismatch", 409)
            pages = validate_pdf(pending)
            with self.service.connection() as db:
                self.admin(db, token, device)  # Recheck revocation/expiry after a slow upload.
                row = db.execute("SELECT * FROM uploads WHERE id=? AND owner=?", (upload_id, owner)).fetchone()
                require(row is not None and row["expires"] >= self.service.clock(), "upload_expired", 409)
                current_spec = json.loads(row['spec'])
                self.catalog.write_number(current_spec['scoreNumber'])
                self.catalog.check_retired(db,current_spec['scoreNumber'],upload_id,current_spec['title'],current_spec['category'])
                require(current_spec == spec, 'upload_changed', 409)
                require(not db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?', (owner, row['request'])).fetchone(), 'upload_cancelled', 409)
                if not row["complete"]:
                    target = self.catalog.root / (upload_id + ".pdf")
                    try:
                        os.link(pending, target)  # Exclusive, never overwrite an existing artifact.
                    except FileExistsError:
                        # Recover only this upload's immutable orphan after a process interruption.
                        require(not target.is_symlink() and target.is_file() and target.stat().st_size == length,
                                "artifact_conflict", 409)
                        with target.open("rb") as content:
                            require(hashlib.file_digest(content, "sha256").hexdigest() == spec["sha256"], "artifact_conflict", 409)
                    db.execute("INSERT INTO artifacts VALUES(?,?,?,?,?,?,0)",
                               (upload_id, spec["title"], spec["partName"], spec["sha256"], length, pages))
                    db.execute("INSERT INTO artifact_metadata VALUES(?,?,?)", (upload_id, spec["scoreNumber"], spec["category"]))
                    db.execute("UPDATE uploads SET complete=1 WHERE id=?", (upload_id,))
                    self.service.audit(db, owner, "part_uploaded", upload_id)
                return dict(artifactId=upload_id, hash=spec["sha256"], size=length)

    def publish(self, body, token, device):
        fields(body, {"requestId", "assignments"})
        require(isinstance(body["requestId"], str) and UUID.fullmatch(body["requestId"]))
        items = body["assignments"]
        require(isinstance(items, list) and 1 <= len(items) <= 50)
        for item in items:
            fields(item, {"artifactId", "accountIds"})
            require(isinstance(item["artifactId"], str) and UUID.fullmatch(item["artifactId"]))
            ids = item["accountIds"]
            require(isinstance(ids, list) and 1 <= len(ids) <= 100 and
                    all(isinstance(a, str) and UUID.fullmatch(a) for a in ids) and len(set(ids)) == len(ids))
        require(len({i["artifactId"] for i in items}) == len(items))
        encoded = canonical(sorted((dict(artifactId=i["artifactId"], accountIds=sorted(i["accountIds"])) for i in items), key=lambda i: i["artifactId"]))
        with self.service.connection() as db:
            owner = self.admin(db, token, device)
            prior = db.execute("SELECT * FROM publications WHERE owner=? AND request=?", (owner, body["requestId"])).fetchone()
            if prior:
                require(prior["spec"] == encoded, "request_conflict", 409)
                return json.loads(prior["receipt"])  # Never re-grant later revoked access on retry.
            batch = []
            for item in items:
                row = db.execute("SELECT f.id,f.title,m.number,m.category FROM artifacts f JOIN artifact_metadata m ON m.artifact=f.id WHERE f.id=?", (item['artifactId'],)).fetchone()
                require(row is not None, 'artifact_not_staged', 409)
                self.catalog.write_number(row['number'])
                batch.append(row)
            self.catalog.check_publication(db, batch)
            for item in items:
                row = db.execute("SELECT f.* FROM artifacts f JOIN uploads u ON u.id=f.id WHERE f.id=? AND u.owner=? AND u.complete=1",
                                 (item["artifactId"], owner)).fetchone()
                require(row is not None and not row["published"], "artifact_not_staged", 409)
                request = db.execute('SELECT request FROM uploads WHERE id=? AND owner=?', (item['artifactId'], owner)).fetchone()
                require(not db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?', (owner, request['request'])).fetchone(), 'upload_cancelled', 409)
                for account in item["accountIds"]:
                    require(self.service.recipient(db, account), "recipient_unavailable", 409)
                target = self.catalog.root / (row["id"] + ".pdf")
                require(target.is_file() and not target.is_symlink() and target.stat().st_size == row["size"], "artifact_unavailable", 409)
                with target.open("rb") as content:
                    require(hashlib.file_digest(content, "sha256").hexdigest() == row["hash"], "artifact_unavailable", 409)
            for item in items:
                db.execute("DELETE FROM artifact_grants WHERE artifact=?", (item["artifactId"],))
                db.executemany("INSERT INTO artifact_grants VALUES(?,?)", ((account, item["artifactId"]) for account in item["accountIds"]))
                db.execute("UPDATE artifacts SET published=1 WHERE id=?", (item["artifactId"],))
            self.catalog.changed(db)
            receipt = dict(ok=True, publicationId=body["requestId"], artifactIds=[i["artifactId"] for i in items])
            db.execute("INSERT INTO publications VALUES(?,?,?,?)", (owner, body["requestId"], encoded, canonical(receipt)))
            self.service.audit(db, owner, "parts_published", body["requestId"])
            return receipt

    def cancel(self, body, token, device):
        fields(body, {'publicationRequest', 'uploadRequests'})
        require(isinstance(body['publicationRequest'], str) and UUID.fullmatch(body['publicationRequest']))
        requests = body['uploadRequests']
        require(isinstance(requests, list) and 1 <= len(requests) <= 50 and all(isinstance(r, str) and UUID.fullmatch(r) for r in requests))
        with self.service.connection() as db:
            owner = self.admin(db, token, device)
            if db.execute('SELECT 1 FROM publications WHERE owner=? AND request=?', (owner, body['publicationRequest'])).fetchone():
                return dict(cancelled=False, published=True)
            db.executemany('INSERT OR IGNORE INTO cancelled_uploads VALUES(?,?)', ((owner, request) for request in requests))
            self.service.audit(db, owner, 'publication_cancelled', body['publicationRequest'])
            return dict(cancelled=True, published=False)
