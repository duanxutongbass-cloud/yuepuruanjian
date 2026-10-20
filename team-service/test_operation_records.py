import uuid
import io
import json
from server import Application
from test_accounts import AccountFixture
from operation_records import TestOperations, validate_event, MAX_ACCOUNT, RETAIN_SECONDS


class OperationRecordsTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.api = TestOperations(self.service)
        self.listener = self.member()
        self.event = dict(id=str(uuid.uuid4()), operation=str(uuid.uuid4()), at=self.now[0]*1000,
                          version=18, event='stamp_open', result='ok', code='none', count=0, elapsed=0, width=800, height=1200)

    def post(self, events=None, login=None, device=None):
        return self.api.dispatch('POST', '/v1/test-operations', dict(events=events or [self.event]),
                                 (login or self.listener)['token'], device or self.device)

    def query(self, body=None, login=None):
        return self.api.dispatch('POST', '/v1/admin/test-operations/query', body or dict(after=0, limit=200),
                                 (login or self.admin)['token'], self.device)

    def test_ownership_idempotence_admin_only_and_revoked_device(self):
        receipt = self.post()
        self.assertEqual([self.event['id']], receipt['accepted'])
        self.assertEqual(receipt, self.post())
        rows = self.query()['events']
        self.assertEqual(1, len(rows))
        self.assertEqual(self.listener['principal']['accountId'], rows[0]['accountId'])
        self.rejected(403, lambda: self.query(login=self.listener))
        self.rejected(401, lambda: self.api.dispatch('POST', '/v1/test-operations', dict(events=[self.event]), '', self.device))
        self.rejected(409, lambda: self.post([dict(self.event, count=1)]))
        with self.service.connection() as db:
            db.execute('UPDATE devices SET revoked=1 WHERE account=? AND id=?',
                       (self.listener['principal']['accountId'], self.device))
        self.rejected(401, self.post)

    def test_no_free_text_unknown_fields_or_identity_override_and_atomic_batch(self):
        for mutation in (dict(password='secret'), dict(event='my score title'), dict(code='a bearer token'),
                         dict(accountId=self.admin_id), dict(count=True), dict(elapsed=-1), dict(width=10001)):
            candidate = dict(self.event, **mutation)
            self.rejected(400, lambda: self.post([dict(self.event, id=str(uuid.uuid4())), candidate]))
        self.assertEqual([], self.query()['events'])
        self.rejected(400, lambda: self.post([self.event]*21))
        disabled = TestOperations(self.service, False)
        self.rejected(403, lambda: disabled.dispatch('POST', '/v1/test-operations', dict(events=[self.event]),
                                                   self.listener['token'], self.device))

    def test_retention_bounded_storage_and_forward_cursor(self):
        owner = self.listener['principal']['accountId']
        with self.service.connection() as db:
            for index in range(MAX_ACCOUNT + 3):
                event = dict(self.event, id=str(uuid.uuid4()))
                db.execute('INSERT INTO test_operations(owner,device,id,received,payload) VALUES(?,?,?,?,?)',
                           (owner, self.device, event['id'], self.now[0], validate_event(event)))
        self.post()
        with self.service.connection() as db:
            self.assertEqual(MAX_ACCOUNT, db.execute('SELECT count(*) FROM test_operations').fetchone()[0])
        first = self.query(dict(after=0, limit=2))['events']
        after = self.query(dict(after=first[-1]['sequence'], limit=2, accountId=owner))['events']
        self.assertGreater(after[0]['sequence'], first[-1]['sequence'])
        self.now[0] += RETAIN_SECONDS + 1
        # Re-authenticate after expiry. Stored timestamps come from the server, not the client.
        self.admin = self.login('director')
        self.assertEqual([], self.query()['events'])

    def test_wsgi_routes_reject_public_access_and_unbounded_or_secret_payloads(self):
        app=Application(self.service)
        def send(path, body, login=None):
            raw=json.dumps(body).encode();status=[]
            env={'REMOTE_ADDR':'127.0.0.1','wsgi.url_scheme':'https','REQUEST_METHOD':'POST',
                 'PATH_INFO':path,'CONTENT_TYPE':'application/json','CONTENT_LENGTH':str(len(raw)),
                 'wsgi.input':io.BytesIO(raw),'HTTP_X_DEVICE_ID':self.device}
            if login: env['HTTP_AUTHORIZATION']='Bearer '+login['token']
            response=b''.join(app(env,lambda text,headers:status.append(int(text.split()[0]))))
            return status[0],json.loads(response)
        self.assertEqual(401,send('/v1/test-operations',dict(events=[self.event]))[0])
        self.assertEqual(200,send('/v1/test-operations',dict(events=[self.event]),self.listener)[0])
        self.assertEqual(403,send('/v1/admin/test-operations/query',dict(after=0,limit=20),self.listener)[0])
        status,reply=send('/v1/admin/test-operations/query',dict(after=0,limit=20),self.admin)
        self.assertEqual(200,status);self.assertEqual(1,len(reply['events']))
        self.assertEqual(400,send('/v1/test-operations',dict(events=[dict(self.event,password='private secret')]),self.listener)[0])
        self.assertEqual(413,send('/v1/test-operations',dict(events=[],password='private secret'*2000),self.listener)[0])
