"""Immutable, independent part PDFs. Never distribute a whole-library archive."""
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import re
import uuid

from service import Rejected, UUID, fields, label, require
from score_numbers import current_reader, require_current_writer

MAX_PDF = 100 * 1024 * 1024


def validate_pdf(source):
    from pypdf import PdfReader
    with Path(source).open("rb") as content:
        require(content.read(5) == b"%PDF-", "invalid_pdf")
        content.seek(0)
        pdf = PdfReader(content, strict=True)
        require(not pdf.is_encrypted, "encrypted_pdf_not_supported")
        root = pdf.trailer["/Root"]
        names = root.get("/Names", {})
        if hasattr(names, "get_object"):
            names = names.get_object()
        require(not any(key in root for key in ("/OpenAction", "/AA", "/AcroForm")) and
                not any(key in names for key in ("/EmbeddedFiles", "/JavaScript")), "active_or_embedded_pdf_rejected")
        pages = len(pdf.pages)
        require(1 <= pages <= 2000, "invalid_page_count")
        for page in pdf.pages:
            require(float(page.mediabox.width) > 0 and float(page.mediabox.height) > 0, "invalid_page_size")
        return pages


@dataclass
class FileReply:
    stream: object
    size: int
    hash: str
    total: int
    offset: int
    partial: bool
    content_type: str = "application/pdf"

    def __iter__(self):
        remaining = self.size
        try:
            while remaining:
                chunk = self.stream.read(min(65536, remaining))
                if not chunk:
                    raise IOError("immutable_artifact_truncated")
                remaining -= len(chunk)
                yield chunk
        finally:
            self.close()

    def close(self):
        self.stream.close()


class Catalog:
    @staticmethod
    def number_inventory(db, now):
        from number_inventory import inventory
        return inventory(db, now)

    read_number = staticmethod(current_reader)
    write_number = staticmethod(require_current_writer)
    protocol_version = 1

    def __init__(self, service):
        self.service = service
        self.root = service.directory / "artifacts"
        self.root.mkdir(exist_ok=True)
        with service.connection() as db:
            db.executescript("""
                CREATE TABLE IF NOT EXISTS artifacts(
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, part TEXT NOT NULL, hash TEXT NOT NULL,
                    size INTEGER NOT NULL, pages INTEGER NOT NULL, published INTEGER NOT NULL DEFAULT 0);
                CREATE TABLE IF NOT EXISTS artifact_grants(
                    account TEXT NOT NULL REFERENCES accounts(id), artifact TEXT NOT NULL REFERENCES artifacts(id),
                    PRIMARY KEY(account,artifact));
                CREATE TABLE IF NOT EXISTS artifact_metadata(
                    artifact TEXT PRIMARY KEY REFERENCES artifacts(id), number TEXT NOT NULL, category TEXT NOT NULL);
                INSERT OR IGNORE INTO metadata VALUES('catalogRevision','0');
            """)

    def changed(self, db):
        db.execute("UPDATE metadata SET value=CAST(value AS INTEGER)+1 WHERE key='catalogRevision'")

    def piece_key(self, db, row):
        from number_release import piece_key
        return piece_key(db,row,self.read_number)

    def packed_metadata(self, db, row):
        from number_release import generation
        result=self.metadata(row)
        gen=generation(db,row['number'],self.read_number)
        if gen:result['pieceGeneration']=gen
        return result

    def check_retired(self, db, number, artifact=None, title=None, category=None):
        if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='artifact_tombstones'").fetchone():
            return
        require(artifact is None or not db.execute('SELECT 1 FROM artifact_tombstones WHERE artifact=?',(artifact,)).fetchone(),
                'artifact_permanently_deleted',409)
        parsed=self.read_number(number)
        if parsed is None:return
        from number_release import ledger
        released=ledger(db,self.read_number)
        retired=[]
        if released:
            for row in db.execute('SELECT artifact,number FROM artifact_tombstones'):
                previous=self.read_number(row['number'])
                if previous and row['artifact'] not in released.get(previous.sequence,('',set()))[1]:retired.append(previous)
        else:retired=[self.read_number(r[0]) for r in db.execute('SELECT number FROM artifact_tombstones')]
        same=[n for n in retired if n is not None and n.sequence==parsed.sequence]
        if not same:return
        require(not any(n.instrument==parsed.instrument for n in same),'part_permanently_deleted',409)
        survivors=[]
        for row in db.execute('SELECT a.title,m.number,m.category FROM artifacts a JOIN artifact_metadata m ON m.artifact=a.id'):
            previous=self.read_number(row['number'])
            if previous is not None and previous.sequence==parsed.sequence:survivors.append((row,previous))
        require(bool(survivors),'number_retired',409)
        if title is not None:
            require(all((row['title'],row['category'],previous.category)==(title,category,parsed.category)
                for row,previous in survivors),'number_conflict',409)

    def check_publication(self, db, rows):
        # Share the writer-lock conflict check between batch and direct publish;
        # the latter must not bypass global sequence/part uniqueness.
        has_removed = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='removed_artifacts'").fetchone()
        reserved = ' OR f.id IN (SELECT id FROM removed_artifacts)' if has_removed else ''
        existing = list(db.execute('''SELECT f.id,f.title,m.number,m.category FROM artifacts f
            JOIN artifact_metadata m ON m.artifact=f.id
            WHERE f.published=1''' + reserved))
        batch = []
        for row in rows:
            self.check_retired(db,row['number'],row['id'],row['title'],row['category'])
            number = self.read_number(row['number'])
            if number is not None:
                self.write_number(row['number'])
                for other in existing + batch:
                    if other['id'] == row['id']:
                        continue
                    previous = self.read_number(other['number'])
                    if previous is None or previous.sequence != number.sequence:
                        continue
                    require((other['title'], other['category'], previous.category) ==
                            (row['title'], row['category'], number.category), 'number_conflict', 409)
                    require(previous.instrument != number.instrument, 'part_version_conflict', 409)
            batch.append(row)

    def import_part(self, source, title, part, independent_part=False):
        """Operator-only staging. Publication and grants remain separate explicit operations."""
        require(independent_part is True, "independent_part_confirmation_required")
        title, part = label(title), label(part)
        source = Path(source)
        require(source.is_file() and 0 < source.stat().st_size <= MAX_PDF, "invalid_pdf")
        artifact_id = str(uuid.uuid4())
        target = self.root / (artifact_id + ".pdf")
        temporary = self.root / (artifact_id + ".pending")
        committed = False
        try:
            size, checksum = 0, hashlib.sha256()
            with source.open("rb") as src, temporary.open("xb") as dest:
                while chunk := src.read(65536):
                    size += len(chunk)
                    require(size <= MAX_PDF, "invalid_pdf")
                    dest.write(chunk)
                    checksum.update(chunk)
                dest.flush()
                os.fsync(dest.fileno())
            pages = validate_pdf(temporary)
            os.replace(temporary, target)
            with self.service.connection() as db:
                db.execute("INSERT INTO artifacts VALUES(?,?,?,?,?,?,0)",
                           (artifact_id, title, part, checksum.hexdigest(), size, pages))
                self.service.audit(db, None, "operator_import_part", artifact_id)
            committed = True
            return artifact_id
        finally:
            temporary.unlink(missing_ok=True)
            if not committed:
                target.unlink(missing_ok=True)

    @classmethod
    def metadata(cls, row):
        cls.read_number(row['number'] if 'number' in row.keys() else None)
        return dict(artifactId=row["id"], title=row["title"], partName=row["part"], hash=row["hash"],
                    size=row["size"], pageCount=row["pages"], independentSource=True,
                    scoreNumber=(row["number"] or "") if "number" in row.keys() else "",
                    category=(row["category"] or "") if "category" in row.keys() else "")

    def dispatch(self, method, path, body, token, device, range_header=None, if_match=None):
        service = self.service
        with service.connection() as db:
            actor = service.authenticate(db, token, device)
            aid = actor["id"]
            if (method, path) == ("GET", "/v1/catalog"):
                rows = db.execute("""SELECT f.*,m.number,m.category FROM artifacts f JOIN artifact_grants g ON g.artifact=f.id
                                     LEFT JOIN artifact_metadata m ON m.artifact=f.id
                                     WHERE g.account=? AND f.published=1 ORDER BY f.id""", (aid,))
                return dict(principal=service.principal(db, actor),
                            programs=self.programs.manifest(db, actor) if hasattr(self, 'programs') else [],
                            revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0]),
                            artifacts=[self.packed_metadata(db,r) for r in rows])
            if method == "GET" and path.startswith("/v1/artifacts/"):
                artifact_id = path[len("/v1/artifacts/"):]
                require(UUID.fullmatch(artifact_id), "not_found", 404)
                row = db.execute("""SELECT f.*,m.number FROM artifacts f JOIN artifact_grants g ON g.artifact=f.id
                                    LEFT JOIN artifact_metadata m ON m.artifact=f.id
                                    WHERE f.id=? AND g.account=? AND f.published=1""", (artifact_id, aid)).fetchone()
                require(row is not None, "not_found", 404)
                self.read_number(row['number'])
                require(if_match is None or if_match == '"' + row["hash"] + '"', "version_changed", 412)
                start, end = 0, row["size"] - 1
                if range_header:
                    match = re.fullmatch(r"bytes=(\d+)-(\d*)", range_header)
                    require(match is not None, "invalid_range", 416)
                    start = int(match[1])
                    end = min(int(match[2]), end) if match[2] else end
                    require(0 <= start <= end < row["size"], "invalid_range", 416)
                target = self.root / (artifact_id + ".pdf")
                require(target.is_file() and not target.is_symlink() and target.stat().st_size == row["size"], "artifact_unavailable", 503)
                stream = target.open("rb")
                try:
                    stream.seek(start)
                    service.audit(db, aid, "artifact_download", artifact_id)
                    return FileReply(stream, end - start + 1, row["hash"], row["size"], start, bool(range_header))
                except BaseException:
                    stream.close()
                    raise
            require(actor["role"] == "admin", "forbidden", 403)
            if (method, path) == ('GET', '/v1/admin/number-inventory'):
                return self.number_inventory(db, service.clock())
            if (method, path) == ("GET", "/v1/admin/artifacts"):
                return dict(revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0]),
                            artifacts=[dict(self.packed_metadata(db,row), published=bool(row["published"]), removed=bool(db.execute("SELECT 1 FROM removed_artifacts WHERE id=?", (row['id'],)).fetchone()) if hasattr(self, 'programs') else False)
                                       for row in db.execute("SELECT f.*,m.number,m.category FROM artifacts f LEFT JOIN artifact_metadata m ON m.artifact=f.id ORDER BY f.id")])
            if (method, path) == ("POST", "/v1/admin/artifacts/publish"):
                fields(body, {"artifactId", "published"})
                require(type(body["published"]) is bool and isinstance(body["artifactId"], str))
                if body['published']:
                    row = db.execute('''SELECT f.id,f.title,m.number,m.category FROM artifacts f
                        LEFT JOIN artifact_metadata m ON m.artifact=f.id WHERE f.id=?''', (body['artifactId'],)).fetchone()
                    require(row is not None, 'not_found', 404)
                    self.check_publication(db, [row])
                result = db.execute("UPDATE artifacts SET published=? WHERE id=?", (int(body["published"]), body["artifactId"]))
                require(result.rowcount == 1, "not_found", 404)
                self.changed(db)
                service.audit(db, aid, "artifact_published" if body["published"] else "artifact_unpublished", body["artifactId"])
                return dict(ok=True)
            if (method, path) == ("POST", "/v1/admin/grants"):
                fields(body, {"accountId", "artifactId", "allowed"})
                require(type(body["allowed"]) is bool and isinstance(body["accountId"], str) and isinstance(body["artifactId"], str))
                require(db.execute("SELECT 1 FROM accounts WHERE id=?", (body["accountId"],)).fetchone() and
                        db.execute("SELECT 1 FROM artifacts WHERE id=?", (body["artifactId"],)).fetchone(), "not_found", 404)
                if body["allowed"]:
                    row = db.execute('SELECT number FROM artifact_metadata WHERE artifact=?', (body['artifactId'],)).fetchone()
                    self.read_number(row['number'] if row else None)
                    self.check_retired(db,row['number'] if row else None,body['artifactId'])
                    require(service.recipient(db, body["accountId"]), "recipient_unavailable", 409)
                    db.execute("INSERT OR IGNORE INTO artifact_grants VALUES(?,?)", (body["accountId"], body["artifactId"]))
                else:
                    db.execute("DELETE FROM artifact_grants WHERE account=? AND artifact=?", (body["accountId"], body["artifactId"]))
                self.changed(db)
                service.audit(db, aid, "artifact_granted" if body["allowed"] else "artifact_revoked",
                              body["accountId"] + "/" + body["artifactId"])
                return dict(ok=True)
            raise Rejected(404, "not_found")
