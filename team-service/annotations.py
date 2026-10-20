"""Version-bound ink, authenticated ownership, CAS and durable idempotent candidates."""
from dataclasses import dataclass
import hashlib
import json
import math
import re
from service import CONSENT_VERSION, UUID, fields, require

MAX_INK = 8 * 1024 * 1024
SYMBOLS = set('ppp|pp|p|mp|mf|f|ff|fff|sfz|fp|cresc.|dim.|<|>|♯|♭|♮|downbow|upbow|fermata|accent|staccato|tenuto|legato|marcato|pizz.|arco|trem.|div.|unis.|con sord.|senza sord.|rit.|rall.|accel.|a tempo|rubato|Andante|Allegro|Adagio|Largo|Presto|D.C.|D.S.|Fine|Coda|To Coda|simile|solo|tutti'.split('|'))


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def ink(marks):
    require(isinstance(marks, list) and len(marks) <= 2000, 'invalid_ink')
    ids, count = set(), 0
    for mark in marks:
        fields(mark, {'id', 'kind', 'points', 'color', 'width', 'symbol'}, {'symbolScale', 'pressures'})
        ident = mark['id']
        require(isinstance(ident, str) and 0 < len(ident) <= 100 and
                not any(ord(c) < 32 for c in ident) and ident not in ids, 'invalid_ink')
        ids.add(ident)
        require(mark['kind'] in ('PEN', 'HIGHLIGHT', 'SYMBOL') and mark['symbol'] in SYMBOLS, 'invalid_ink')
        require(type(mark['color']) is int and 0 <= mark['color'] <= 0xffffffff, 'invalid_ink')
        require(type(mark['width']) in (int, float) and math.isfinite(mark['width']) and
                .00049999 <= mark['width'] <= .05000001, 'invalid_ink')
        scale = mark.get('symbolScale', 1)
        require(type(scale) in (int, float) and math.isfinite(scale) and .5 <= scale <= 2, 'invalid_ink')
        require(isinstance(mark['points'], list) and 1 <= len(mark['points']) <= 20000, 'invalid_ink')
        count += len(mark['points'])
        require(count <= 100000, 'invalid_ink')
        if 'pressures' in mark:
            pressures = mark['pressures']
            require(mark['kind'] == 'PEN' and isinstance(pressures, list) and len(pressures) == len(mark['points']) and all(
                type(n) in (int, float) and math.isfinite(n) and 0 <= n <= 1 for n in pressures), 'invalid_ink')
        for point in mark['points']:
            require(isinstance(point, list) and len(point) == 2 and all(
                type(n) in (int, float) and math.isfinite(n) and 0 <= n <= 1 for n in point), 'invalid_ink')
    payload = canonical(marks)
    require(len(payload.encode('utf-8')) <= MAX_INK, 'ink_too_large', 413)
    return payload


@dataclass
class AnnotationReply:
    data: dict
    status: int = 200


class Annotations:
    def __init__(self, service):
        self.service = service
        with service.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS annotation_heads(
                    artifact TEXT NOT NULL REFERENCES artifacts(id), page INTEGER NOT NULL,
                    owner TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT NOT NULL,
                    PRIMARY KEY(artifact,page,owner,kind));
                CREATE TABLE IF NOT EXISTS annotation_operations(
                    account TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, result TEXT NOT NULL, status INTEGER NOT NULL,
                    PRIMARY KEY(account,request));
                CREATE TABLE IF NOT EXISTS annotation_history(
                    id INTEGER PRIMARY KEY, artifact TEXT NOT NULL, page INTEGER NOT NULL,
                    owner TEXT NOT NULL, kind TEXT NOT NULL, revision INTEGER NOT NULL,
                    payload TEXT NOT NULL, actor TEXT NOT NULL, at INTEGER NOT NULL);
            ''')

    @staticmethod
    def head(db, artifact, page, owner, kind):
        row = db.execute('SELECT revision,payload FROM annotation_heads WHERE artifact=? AND page=? AND owner=? AND kind=?',
                         (artifact, page, owner, kind)).fetchone()
        return dict(revision=row['revision'], marks=json.loads(row['payload'])) if row else dict(revision=0, marks=[])

    def authorized(self, db, actor, artifact, page):
        require(isinstance(artifact, str) and UUID.fullmatch(artifact) and type(page) is int, 'not_found', 404)
        row = db.execute('''SELECT f.* FROM artifacts f JOIN artifact_grants g ON g.artifact=f.id
                            WHERE f.id=? AND g.account=? AND f.published=1''', (artifact, actor['id'])).fetchone()
        require(row is not None and 0 <= page < row['pages'], 'not_found', 404)
        return row

    def dispatch(self, method, path, body, token, device):
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            aid = actor['id']
            if (method, path) == ('GET', '/v1/annotations'):
                rows = db.execute('''SELECT h.artifact,h.page,h.kind,h.revision,f.hash FROM annotation_heads h
                    JOIN artifacts f ON f.id=h.artifact JOIN artifact_grants g ON g.artifact=f.id
                    WHERE g.account=? AND f.published=1 AND
                    ((h.kind='personal' AND h.owner=? AND ?=1) OR h.kind='published' OR (h.kind='draft' AND ?='admin'))
                    ORDER BY h.artifact,h.page,h.kind LIMIT 10001''',
                    (aid, aid, int(actor['consent'] == CONSENT_VERSION), actor['role'])).fetchall()
                require(len(rows) <= 10000, 'annotation_manifest_too_large', 413)
                return dict(protocolVersion=1, accountId=aid, teamId=self.service.principal(db, actor)['teamId'],
                            pages=[dict(artifactId=r['artifact'], artifactHash=r['hash'], pageIndex=r['page'],
                                        kind=r['kind'], revision=r['revision']) for r in rows])
            # Private ink remains retrievable by its authenticated owner after PDF revocation.
            if (method, path) == ('GET', '/v1/annotation-backups'):
                rows = db.execute("SELECT h.artifact,h.page,h.revision,f.hash,f.title,f.part FROM annotation_heads h JOIN artifacts f ON f.id=h.artifact WHERE h.owner=? AND h.kind='personal' ORDER BY h.artifact,h.page LIMIT 10001", (aid,)).fetchall()
                require(len(rows) <= 10000, 'annotation_manifest_too_large', 413)
                return dict(pages=[dict(artifactId=r['artifact'], artifactHash=r['hash'], pageIndex=r['page'],
                                        revision=r['revision'], title=r['title'], partName=r['part']) for r in rows])
            if path.startswith('/v1/annotation-backups/') and method == 'GET':
                match = re.fullmatch(r'/v1/annotation-backups/([a-f0-9-]{36})/(\d{1,5})', path)
                require(match is not None, 'not_found', 404)
                row = db.execute("SELECT 1 FROM annotation_heads WHERE artifact=? AND page=? AND owner=? AND kind='personal'", (match[1], int(match[2]), aid)).fetchone()
                require(row is not None, 'not_found', 404)
                return self.head(db, match[1], int(match[2]), aid, 'personal')
            if (method, path) == ('POST', '/v1/admin/annotation-backups'):
                require(actor['role'] == 'admin', 'forbidden', 403)
                fields(body, {'accountId', 'artifactId', 'pageIndex'})
                require(isinstance(body['accountId'], str) and UUID.fullmatch(body['accountId']), 'not_found', 404)
                target = db.execute('SELECT * FROM accounts WHERE id=?', (body['accountId'],)).fetchone()
                require(target is not None and target['consent'] == CONSENT_VERSION, 'not_found', 404)
                source = self.authorized(db, actor, body['artifactId'], body['pageIndex'])
                self.service.audit(db, aid, 'annotation_backup_read', body['accountId'] + '/' + body['artifactId'] + '/' + str(body['pageIndex']))
                return dict(self.head(db, body['artifactId'], body['pageIndex'], body['accountId'], 'personal'),
                            accountId=body['accountId'], artifactId=body['artifactId'], artifactHash=source['hash'], pageIndex=body['pageIndex'])
            match = re.fullmatch(r'/v1/annotations/([a-f0-9-]{36})/(\d{1,5})/(personal|draft|published)', path)
            require(match is not None, 'not_found', 404)
            artifact, page, kind = match[1], int(match[2]), match[3]
            source = self.authorized(db, actor, artifact, page)
            if kind == 'personal':
                require(actor['consent'] == CONSENT_VERSION, 'consent_required', 403)
            if kind == 'draft' or method == 'POST' and kind == 'published':
                require(actor['role'] == 'admin', 'forbidden', 403)
            owner = aid if kind == 'personal' else 'shared'
            current = self.head(db, artifact, page, owner, kind)
            if method == 'GET':
                return dict(current, artifactId=artifact, artifactHash=source['hash'], pageIndex=page, kind=kind, accountId=aid)
            required = {'protocolVersion', 'requestId', 'baseRevision', 'artifactHash'}
            fields(body, required | {'marks'} | ({'draftRevision'} if kind == 'published' else set()))
            require(type(body['protocolVersion']) is int and body['protocolVersion'] == 1, 'protocol_unsupported')
            require(isinstance(body['requestId'], str) and UUID.fullmatch(body['requestId']) and
                    type(body['baseRevision']) is int and 0 <= body['baseRevision'] < 2**53-1, 'invalid_revision')
            require(body['artifactHash'] == source['hash'], 'version_changed', 412)
            if kind == 'published':
                require(type(body['draftRevision']) is int and 0 <= body['draftRevision'] < 2**53-1, 'invalid_revision')
            payload = ink(body['marks'])
            fingerprint = hashlib.sha256(canonical([path, body]).encode('utf-8')).hexdigest()
            previous = db.execute('SELECT * FROM annotation_operations WHERE account=? AND request=?', (aid, body['requestId'])).fetchone()
            if previous:
                require(previous['fingerprint'] == fingerprint, 'request_id_reused', 409)
                return AnnotationReply(json.loads(previous['result']), previous['status'])
            if kind == 'published':
                draft = self.head(db, artifact, page, owner, 'draft')
                draft_changed = body['draftRevision'] != draft['revision'] or json.loads(payload) != draft['marks']
            else:
                draft_changed = False
            used = db.execute('SELECT COALESCE(SUM(length(CAST(result AS BLOB))),0) FROM annotation_operations WHERE account=?', (aid,)).fetchone()[0]
            require(used + len(payload.encode('utf-8')) * 3 < 512 * 1024 * 1024, 'annotation_quota', 413)
            if body['baseRevision'] != current['revision'] or draft_changed:
                # Commit candidate and stable response; do not raise inside the transaction.
                result = dict(error='annotation_conflict', requestId=body['requestId'], current=current,
                              candidate=json.loads(payload), revision=current['revision'])
                status = 409
                event = 'annotation_conflict'
            else:
                # Older clients can display a flat fallback but must not silently strip pressure
                # from an existing mark when saving an otherwise current page.
                by_id = {mark['id']: mark for mark in current['marks']}
                require(not any('pressures' in by_id.get(mark['id'], {}) and 'pressures' not in mark
                                for mark in body['marks']), 'pressure_data_required', 409)
                revision = current['revision'] + 1
                db.execute('''INSERT INTO annotation_heads VALUES(?,?,?,?,?,?) ON CONFLICT(artifact,page,owner,kind)
                    DO UPDATE SET revision=excluded.revision,payload=excluded.payload''', (artifact, page, owner, kind, revision, payload))
                result = dict(requestId=body['requestId'], revision=revision, marks=json.loads(payload))
                status = 200
                event = 'annotation_publish' if kind == 'published' else 'annotation_save'
            db.execute('INSERT INTO annotation_history(artifact,page,owner,kind,revision,payload,actor,at) VALUES(?,?,?,?,?,?,?,?)',
                       (artifact, page, owner, kind, body['baseRevision'], payload, aid, int(self.service.clock())))
            db.execute('INSERT INTO annotation_operations VALUES(?,?,?,?,?)',
                       (aid, body['requestId'], fingerprint, canonical(result), status))
            if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='annotation_operation_targets'").fetchone():
                db.execute('INSERT INTO annotation_operation_targets VALUES(?,?,?)',(aid,body['requestId'],artifact))
            self.service.audit(db, aid, event, artifact + '/' + str(page) + '/' + kind)
            return AnnotationReply(result, status)
