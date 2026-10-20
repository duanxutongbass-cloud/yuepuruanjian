"""Temporary test-build diagnostics, with a fixed vocabulary and no free-form content."""
import json

from service import UUID, fields, require

EVENTS = set('app_start app_background stamp_open stamp_close stamp_choose stamp_size '
             'stamp_resize pdf_render upload_select upload_read upload_preflight upload_send upload_publish upload_cancel'.split())
RESULTS = {'start', 'ok', 'error', 'cancelled', 'blocked'}
CODES = set('none network io metadata_invalid session_invalid forbidden number_conflict part_version_conflict '
            'request_conflict service_unavailable offline file_invalid storage_unknown unknown'.split())
RETAIN_SECONDS = 30 * 86400
MAX_ACCOUNT = 2000
MAX_TOTAL = 50000


def validate_event(row):
    fields(row, {'id', 'operation', 'at', 'version', 'event', 'result', 'code', 'count', 'elapsed', 'width', 'height'})
    require(isinstance(row['id'], str) and UUID.fullmatch(row['id']) and
            isinstance(row['operation'], str) and UUID.fullmatch(row['operation']))
    require(all(isinstance(row[key], str) for key in ('event','result','code')) and
            row['event'] in EVENTS and row['result'] in RESULTS and row['code'] in CODES)
    for key, maximum in (('at', 4102444800000), ('version', 1000000), ('count', 10000),
                         ('elapsed', 86400000), ('width', 10000), ('height', 10000)):
        require(type(row[key]) is int and 0 <= row[key] <= maximum)
    return json.dumps(row, sort_keys=True, separators=(',', ':'), allow_nan=False)


class TestOperations:
    def __init__(self, service, enabled=True):
        self.service, self.enabled = service, enabled
        with service.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS test_operations(
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL REFERENCES accounts(id),
                    device TEXT NOT NULL, id TEXT NOT NULL, received INTEGER NOT NULL, payload TEXT NOT NULL,
                    UNIQUE(owner,device,id));
                CREATE INDEX IF NOT EXISTS test_operations_owner ON test_operations(owner,sequence);
                CREATE INDEX IF NOT EXISTS test_operations_received ON test_operations(received);
            ''')

    def dispatch(self, method, path, body, token, device):
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            require(self.enabled, 'diagnostics_disabled', 403)
            now = int(self.service.clock())
            db.execute('DELETE FROM test_operations WHERE received<?', (now - RETAIN_SECONDS,))
            if (method, path) == ('POST', '/v1/test-operations'):
                fields(body, {'events'})
                require(isinstance(body['events'], list) and 1 <= len(body['events']) <= 20)
                encoded = [(row, validate_event(row)) for row in body['events']]
                require(len({row['id'] for row, _ in encoded}) == len(encoded))
                for row, payload in encoded:
                    prior = db.execute('SELECT payload FROM test_operations WHERE owner=? AND device=? AND id=?',
                                       (actor['id'], device, row['id'])).fetchone()
                    if prior:
                        require(prior['payload'] == payload, 'request_conflict', 409)
                    else:
                        db.execute('INSERT INTO test_operations(owner,device,id,received,payload) VALUES(?,?,?,?,?)',
                                   (actor['id'], device, row['id'], now, payload))
                db.execute('''DELETE FROM test_operations WHERE owner=? AND sequence NOT IN
                    (SELECT sequence FROM test_operations WHERE owner=? ORDER BY sequence DESC LIMIT ?)''',
                           (actor['id'], actor['id'], MAX_ACCOUNT))
                db.execute('''DELETE FROM test_operations WHERE sequence NOT IN
                    (SELECT sequence FROM test_operations ORDER BY sequence DESC LIMIT ?)''', (MAX_TOTAL,))
                return dict(accepted=[row['id'] for row, _ in encoded], receivedAt=now)
            require(actor['role'] == 'admin', 'forbidden', 403)
            require((method, path) == ('POST', '/v1/admin/test-operations/query'), 'not_found', 404)
            fields(body, {'after', 'limit'}, {'accountId'})
            require(type(body['after']) is int and 0 <= body['after'] <= 2**63-1 and
                    type(body['limit']) is int and 1 <= body['limit'] <= 200)
            owner = body.get('accountId')
            require(owner is None or isinstance(owner, str) and UUID.fullmatch(owner))
            where, args = 'sequence>?', [body['after']]
            if owner is not None:
                where += ' AND owner=?'; args.append(owner)
            rows = db.execute('SELECT * FROM test_operations WHERE ' + where + ' ORDER BY sequence LIMIT ?',
                              (*args, body['limit'])).fetchall()
            self.service.audit(db, actor['id'], 'test_operations_read', owner)
            return dict(events=[dict(sequence=r['sequence'], accountId=r['owner'], deviceId=r['device'],
                                     receivedAt=r['received'], **json.loads(r['payload'])) for r in rows],
                        retentionDays=30)
