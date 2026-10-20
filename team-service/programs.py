"""Single-team programmes and reversible catalogue removal; PDF files and ink are retained."""
import json
import re

from service import UUID, fields, label, require
from publishing import canonical
from score_numbers import current_reader


def piece_key(row, reader=current_reader):
    number = reader(row['number'])
    return 'sequence:' + number.sequence if number else 'artifact:' + row['id']


class Programs:
    def __init__(self, service, catalog):
        self.service, self.catalog = service, catalog
        with service.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS programs(
                    id TEXT PRIMARY KEY, title TEXT NOT NULL, pieces TEXT NOT NULL, audience TEXT,
                    archived INTEGER NOT NULL DEFAULT 0, revision INTEGER NOT NULL);
                CREATE TABLE IF NOT EXISTS removed_artifacts(
                    id TEXT PRIMARY KEY REFERENCES artifacts(id));
                CREATE TABLE IF NOT EXISTS catalog_actions(
                    owner TEXT NOT NULL, request TEXT NOT NULL, spec TEXT NOT NULL, receipt TEXT NOT NULL,
                    PRIMARY KEY(owner,request));
            ''')

    def piece_key(self, row, db=None):
        return self.catalog.piece_key(db,row) if db is not None else piece_key(row, self.catalog.read_number)

    def groups(self, db):
        groups = {}
        for row in db.execute('''SELECT f.*,m.number,m.category,r.id AS removed FROM artifacts f
                LEFT JOIN artifact_metadata m ON m.artifact=f.id LEFT JOIN removed_artifacts r ON r.id=f.id
                WHERE f.published=1 OR r.id IS NOT NULL ORDER BY f.id'''):
            removed = row['removed'] is not None
            number = self.catalog.read_number(row['number'])
            key = (self.piece_key(row,db), removed)
            group = groups.setdefault(key, dict(key=key[0], title=row['title'], number=number.sequence if number else '',
                category=row['category'] or '', removed=removed, conflict=False, parts=[]))
            if (group['title'], group['category']) != (row['title'], row['category'] or ''):
                group['conflict'] = True
            if any(p['scoreNumber'] == (row['number'] or '') and row['number'] for p in group['parts']):
                group['conflict'] = True
            group['parts'].append(dict(artifactId=row['id'], partName=row['part'], scoreNumber=row['number'] or ''))
        return sorted(groups.values(), key=lambda g: (g['removed'], int(g['number']) if g['number'] else -1, g['title'], g['key']))

    def manifest(self, db, actor):
        granted = list(db.execute('''SELECT f.id,m.number FROM artifacts f
            JOIN artifact_grants g ON g.artifact=f.id LEFT JOIN artifact_metadata m ON m.artifact=f.id
            WHERE f.published=1 AND g.account=?''', (actor['id'],)))
        keys, allowed = {self.piece_key(r,db) for r in granted}, {r['id'] for r in granted}
        result = []
        for row in db.execute('SELECT * FROM programs WHERE archived=0 ORDER BY id'):
            audience = json.loads(row['audience']) if row['audience'] is not None else None
            if actor['role'] != 'admin' and audience is not None and actor['id'] not in audience:
                continue
            if hasattr(self, 'freezes'):
                frozen = self.freezes.project_manifest(db, row, allowed)
                if frozen is not None:
                    if frozen['pieceKeys']:
                        result.append(frozen)
                    continue
            # A member receives only the numbers they are authorized to perform, in programme order.
            pieces = [key for key in json.loads(row['pieces']) if key in keys]
            if pieces:
                result.append(dict(id=row['id'], title=row['title'], pieceKeys=pieces))
        return result

    def dispatch(self, method, path, body, token, device):
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            require(actor['role'] == 'admin', 'forbidden', 403)
            if (method, path) == ('GET', '/v1/admin/ensemble'):
                rows = db.execute('SELECT * FROM programs ORDER BY id')
                return dict(revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0]),
                    groups=self.groups(db), programs=[dict(id=r['id'], title=r['title'], pieceKeys=json.loads(r['pieces']),
                        accountIds=json.loads(r['audience']) if r['audience'] is not None else None,
                        archived=bool(r['archived']), revision=r['revision'],
                        freeze=self.freezes.descriptor(db,r['id']) if hasattr(self,'freezes') else None) for r in rows])
            if (method, path) == ('POST', '/v1/admin/programs'):
                fields(body, {'id', 'title', 'pieceKeys', 'accountIds', 'archived', 'expectedRevision'})
                require(isinstance(body['id'], str) and UUID.fullmatch(body['id']))
                title = label(body['title'])
                pieces, audience = body['pieceKeys'], body['accountIds']
                require(type(body['archived']) is bool and type(body['expectedRevision']) is int and body['expectedRevision'] >= 0)
                require(isinstance(pieces, list) and 1 <= len(pieces) <= 200 and all(isinstance(p, str) for p in pieces)
                        and len(set(pieces)) == len(pieces))
                require(audience is None or isinstance(audience, list) and len(audience) <= 200
                    and all(isinstance(a, str) and UUID.fullmatch(a) for a in audience) and len(set(audience)) == len(audience))
                if audience is not None:
                    require(all(self.service.recipient(db, a) for a in audience), 'recipient_unavailable', 409)
                old = db.execute('SELECT * FROM programs WHERE id=?', (body['id'],)).fetchone()
                revision = old['revision'] if old else 0
                encoded_pieces, encoded_audience = canonical(pieces), canonical(audience) if audience is not None else None
                # An identical retry after a lost reply is safe, including deletion/restoration.
                if old and (old['title'], old['pieces'], old['audience'], bool(old['archived'])) == (title, encoded_pieces, encoded_audience, body['archived']):
                    return dict(ok=True, revision=revision)
                require(body['expectedRevision'] == revision, 'program_changed', 409)
                if old and hasattr(self, 'freezes') and self.freezes.descriptor(db, old['id']) is not None:
                    require((old['title'],old['pieces'],old['audience']) == (title,encoded_pieces,encoded_audience),
                            'program_frozen', 409)
                if not body['archived']:
                    available = {g['key'] for g in self.groups(db) if not g['removed'] and not g['conflict']}
                    require(set(pieces) <= available, 'program_score_unavailable', 409)
                elif old:
                    require(old['pieces'] == encoded_pieces and old['title'] == title and old['audience'] == encoded_audience,
                            'program_changed', 409)
                else:
                    require(False, 'not_found', 404)
                if old:
                    db.execute('UPDATE programs SET title=?,pieces=?,audience=?,archived=?,revision=? WHERE id=?',
                               (title,encoded_pieces,encoded_audience,int(body['archived']),revision+1,body['id']))
                else:
                    db.execute('INSERT INTO programs VALUES(?,?,?,?,?,?)',
                               (body['id'],title,encoded_pieces,encoded_audience,int(body['archived']),revision+1))
                self.catalog.changed(db)
                self.service.audit(db, actor['id'], 'program_removed' if body['archived'] else 'program_saved', body['id'])
                return dict(ok=True, revision=revision + 1)
            if (method, path) == ('POST', '/v1/admin/catalog-actions'):
                fields(body, {'requestId', 'artifactIds', 'removed', 'expectedRevision'})
                require(isinstance(body['requestId'], str) and UUID.fullmatch(body['requestId']))
                ids = body['artifactIds']
                require(isinstance(ids, list) and 1 <= len(ids) <= 200 and all(isinstance(i, str) and UUID.fullmatch(i) for i in ids) and len(set(ids)) == len(ids))
                require(type(body['removed']) is bool and type(body['expectedRevision']) is int)
                spec = canonical(body)
                saved = db.execute('SELECT * FROM catalog_actions WHERE owner=? AND request=?', (actor['id'], body['requestId'])).fetchone()
                if saved:
                    require(saved['spec'] == spec, 'request_conflict', 409)
                    return json.loads(saved['receipt'])
                require(body['expectedRevision'] == int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0]), 'catalog_changed', 409)
                for artifact in ids:
                    row = db.execute('SELECT published FROM artifacts WHERE id=?', (artifact,)).fetchone()
                    require(row is not None, 'not_found', 404)
                    require(bool(row['published']) if body['removed'] else bool(db.execute('SELECT 1 FROM removed_artifacts WHERE id=?', (artifact,)).fetchone()), 'catalog_changed', 409)
                for artifact in ids:
                    db.execute('UPDATE artifacts SET published=? WHERE id=?', (0 if body['removed'] else 1, artifact))
                    if body['removed']:
                        db.execute('INSERT OR IGNORE INTO removed_artifacts VALUES(?)', (artifact,))
                    else:
                        db.execute('DELETE FROM removed_artifacts WHERE id=?', (artifact,))
                self.catalog.changed(db)
                self.service.audit(db, actor['id'], 'scores_removed' if body['removed'] else 'scores_restored', body['requestId'])
                receipt = dict(ok=True)
                db.execute('INSERT INTO catalog_actions VALUES(?,?,?,?)', (actor['id'], body['requestId'], spec, canonical(receipt)))
                return receipt
            require(False, 'not_found', 404)
