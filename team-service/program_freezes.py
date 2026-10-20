"""Immutable programme snapshots; downloading never bypasses current per-PDF grants."""
import hashlib
import json
import re
import uuid

from annotations import ink
from service import UUID, fields, require
from publishing import canonical

MAX_FREEZE_BYTES = 32 * 1024 * 1024
MAX_FREEZE_PAGES = 10000


class ProgramFreezes:
    def __init__(self, service, programs):
        self.service, self.programs = service, programs
        with service.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS program_freezes(
                    program TEXT PRIMARY KEY REFERENCES programs(id), id TEXT NOT NULL UNIQUE,
                    revision INTEGER NOT NULL, created INTEGER NOT NULL, creator TEXT NOT NULL REFERENCES accounts(id),
                    manifest TEXT NOT NULL, pages TEXT NOT NULL, sha256 TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS program_freeze_requests(
                    owner TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    spec TEXT NOT NULL, receipt TEXT NOT NULL, PRIMARY KEY(owner,request));
            ''')

    @staticmethod
    def descriptor(db, program):
        row = db.execute('SELECT id,revision,created FROM program_freezes WHERE program=?', (program,)).fetchone()
        return dict(id=row['id'], programRevision=row['revision'], createdAt=row['created']) if row else None

    def project_manifest(self, db, program, allowed):
        frozen = db.execute('SELECT id,manifest FROM program_freezes WHERE program=?', (program['id'],)).fetchone()
        if frozen is None:
            return None
        manifest = json.loads(frozen['manifest'])
        artifacts = [a for a in manifest['artifacts'] if a['artifactId'] in allowed]
        for artifact in artifacts:
            self.programs.catalog.read_number(artifact.get('scoreNumber'))
        keys = {a['pieceKey'] for a in artifacts}
        return dict(id=program['id'], title=manifest['title'],
                    pieceKeys=[k for k in manifest['pieceKeys'] if k in keys],
                    freezeId=frozen['id'], frozenArtifactIds=[a['artifactId'] for a in artifacts])

    def freeze(self, db, actor, body):
        fields(body, {'programId', 'requestId', 'expectedRevision'})
        require(all(isinstance(body[k], str) and UUID.fullmatch(body[k]) for k in ('programId','requestId')))
        require(type(body['expectedRevision']) is int and body['expectedRevision'] > 0)
        spec = canonical(body)
        previous = db.execute('SELECT spec,receipt FROM program_freeze_requests WHERE owner=? AND request=?',
                              (actor['id'], body['requestId'])).fetchone()
        if previous:
            require(previous['spec'] == spec, 'request_conflict', 409)
            return json.loads(previous['receipt'])
        program = db.execute('SELECT * FROM programs WHERE id=?', (body['programId'],)).fetchone()
        require(program is not None and not program['archived'], 'not_found', 404)
        require(program['revision'] == body['expectedRevision'], 'program_changed', 409)
        require(self.descriptor(db, program['id']) is None, 'program_frozen', 409)
        keys = json.loads(program['pieces'])
        groups = {g['key']:g for g in self.programs.groups(db) if not g['removed'] and not g['conflict']}
        require(set(keys) <= set(groups), 'program_score_unavailable', 409)
        candidates = db.execute('''SELECT f.*,m.number,m.category FROM artifacts f
            LEFT JOIN artifact_metadata m ON m.artifact=f.id WHERE f.published=1 ORDER BY f.id''')
        artifacts = []
        for row in candidates:
            key = self.programs.piece_key(row,db)
            if key in keys:
                artifacts.append(dict(artifactId=row['id'], hash=row['hash'], size=row['size'], pageCount=row['pages'],
                    title=row['title'], partName=row['part'], scoreNumber=row['number'] or '',
                    category=row['category'] or '', pieceKey=key))
        artifacts.sort(key=lambda a:(keys.index(a['pieceKey']), a['scoreNumber'], a['artifactId']))
        require(artifacts and {a['pieceKey'] for a in artifacts} == set(keys), 'program_score_unavailable', 409)
        require(sum(a['pageCount'] for a in artifacts) <= MAX_FREEZE_PAGES, 'program_freeze_too_large', 413)
        pages, ink_bytes = [], 0
        for artifact in artifacts:
            for row in db.execute("SELECT page,revision,payload FROM annotation_heads WHERE artifact=? AND owner='shared' AND kind='published' ORDER BY page",
                                  (artifact['artifactId'],)):
                require(0 <= row['page'] < artifact['pageCount'] and row['revision'] > 0, 'invalid_ink')
                ink_bytes += len(row['payload'].encode('utf-8'))
                require(ink_bytes <= MAX_FREEZE_BYTES, 'program_freeze_too_large', 413)
                marks = json.loads(row['payload'])
                ink(marks)
                pages.append(dict(artifactId=artifact['artifactId'], pageIndex=row['page'],
                                  revision=row['revision'], marks=marks))
        freeze_id = str(uuid.uuid4())
        manifest = dict(protocolVersion=self.programs.catalog.protocol_version, programId=program['id'], freezeId=freeze_id, programRevision=program['revision'],
                        title=program['title'], pieceKeys=keys, artifacts=artifacts)
        payload = canonical(dict(**manifest, pages=pages))
        require(len(payload.encode('utf-8')) <= MAX_FREEZE_BYTES, 'program_freeze_too_large', 413)
        checksum = hashlib.sha256(payload.encode('utf-8')).hexdigest()
        now = int(self.service.clock())
        db.execute('INSERT INTO program_freezes VALUES(?,?,?,?,?,?,?,?)',
                   (program['id'], freeze_id, program['revision'], now, actor['id'], canonical(manifest), canonical(pages), checksum))
        receipt = dict(ok=True, freezeId=freeze_id, programId=program['id'], programRevision=program['revision'],
                       createdAt=now, artifactCount=len(artifacts), pageCount=sum(a['pageCount'] for a in artifacts))
        db.execute('INSERT INTO program_freeze_requests VALUES(?,?,?,?)', (actor['id'], body['requestId'], spec, canonical(receipt)))
        self.programs.catalog.changed(db)
        self.service.audit(db, actor['id'], 'program_frozen', program['id'])
        return receipt

    def dispatch(self, method, path, body, token, device):
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            if (method, path) == ('POST', '/v1/admin/program-freezes'):
                require(actor['role'] == 'admin', 'forbidden', 403)
                return self.freeze(db, actor, body)
            match = re.fullmatch(r'/v1/program-freezes/([a-f0-9-]{36})/([a-f0-9-]{36})', path)
            require(method == 'GET' and match is not None, 'not_found', 404)
            require(UUID.fullmatch(match[1]) and UUID.fullmatch(match[2]), 'not_found', 404)
            program = db.execute('SELECT * FROM programs WHERE id=? AND archived=0', (match[1],)).fetchone()
            require(program is not None, 'not_found', 404)
            audience = json.loads(program['audience']) if program['audience'] is not None else None
            require(actor['role'] == 'admin' or audience is None or actor['id'] in audience, 'not_found', 404)
            frozen = db.execute('SELECT * FROM program_freezes WHERE program=? AND id=?', (match[1], match[2])).fetchone()
            require(frozen is not None, 'not_found', 404)
            manifest, pages = json.loads(frozen['manifest']), json.loads(frozen['pages'])
            require(hashlib.sha256(canonical(dict(**manifest,pages=pages)).encode('utf-8')).hexdigest() == frozen['sha256'],
                    'program_freeze_unavailable', 503)
            current = {r['id']:r for r in db.execute('''SELECT f.* FROM artifacts f JOIN artifact_grants g ON g.artifact=f.id
                WHERE g.account=? AND f.published=1''', (actor['id'],))}
            selected = [a for a in manifest['artifacts'] if a['artifactId'] in current]
            require(selected, 'not_found', 404)
            for artifact in selected:
                self.programs.catalog.read_number(artifact.get('scoreNumber'))
            require(all((a['hash'],a['size'],a['pageCount']) ==
                        (current[a['artifactId']]['hash'],current[a['artifactId']]['size'],current[a['artifactId']]['pages'])
                        for a in selected), 'program_freeze_unavailable', 503)
            ids, keys = {a['artifactId'] for a in selected}, {a['pieceKey'] for a in selected}
            projected = dict(**manifest, pages=[p for p in pages if p['artifactId'] in ids])
            projected['artifacts'] = selected
            projected['pieceKeys'] = [key for key in manifest['pieceKeys'] if key in keys]
            payload = canonical(projected)
            self.service.audit(db, actor['id'], 'program_freeze_read', program['id'])
            return dict(protocolVersion=manifest['protocolVersion'], programId=program['id'], freezeId=frozen['id'],
                        sha256=hashlib.sha256(payload.encode('utf-8')).hexdigest(), payload=payload)
