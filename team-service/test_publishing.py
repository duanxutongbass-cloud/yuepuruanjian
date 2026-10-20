import hashlib
import io
import json
import uuid
from pypdf import PdfWriter
from server import Application
from test_accounts import AccountFixture


class PublishingTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.app = Application(self.service)
        self.publisher = self.app.publisher
        data = io.BytesIO()
        writer = PdfWriter()
        writer.add_blank_page(595, 842)
        writer.write(data)
        self.pdf = data.getvalue()
        self.spec = dict(requestId=str(uuid.uuid4()), title="天空之城", partName="01 第一小提琴",
                         scoreNumber="0101001", category="01 久石让", sha256=hashlib.sha256(self.pdf).hexdigest(),
                         size=len(self.pdf), independentSource=True)

    def prepare(self, **changes):
        return self.publisher.prepare(dict(self.spec, **changes), self.admin['token'], self.device)

    def upload(self, upload_id, content=None, login=None):
        return self.publisher.upload(upload_id, io.BytesIO(self.pdf if content is None else content), len(self.pdf),
                                     (login or self.admin)['token'], self.device)

    def publication(self, artifacts, accounts):
        return dict(requestId=str(uuid.uuid4()), assignments=[dict(artifactId=a, accountIds=accounts) for a in artifacts])

    def publish(self, body):
        return self.publisher.publish(body, self.admin['token'], self.device)

    def catalog(self, login):
        return self.app.catalog.dispatch('GET', '/v1/catalog', None, login['token'], self.device)['artifacts']

    def test_cancelled_batch_cannot_publish_after_slow_upload_or_retry(self):
        uid = self.prepare()['uploadId']
        publication = self.publication([uid], [self.admin_id])
        cancellation = dict(publicationRequest=publication['requestId'], uploadRequests=[self.spec['requestId']])
        publisher, token, device = self.publisher, self.admin['token'], self.device
        class SlowUpload(io.BytesIO):
            def read(stream, size=-1):
                publisher.cancel(cancellation, token, device)
                return super().read(size)
        self.rejected(409, lambda: publisher.upload(uid, SlowUpload(self.pdf), len(self.pdf), token, device))
        self.rejected(409, lambda: self.prepare())
        self.assertEqual([], self.catalog(self.admin))

    def test_cancel_after_publication_never_removes_published_score(self):
        uid = self.prepare()['uploadId']; self.upload(uid)
        body = self.publication([uid], [self.admin_id]); self.publish(body)
        response = self.publisher.cancel(dict(publicationRequest=body['requestId'], uploadRequests=[self.spec['requestId']]), self.admin['token'], self.device)
        self.assertFalse(response['cancelled']); self.assertTrue(response['published'])
        self.assertEqual(uid, self.catalog(self.admin)[0]['artifactId'])

    def test_atomic_number_guard_rejects_different_title_or_duplicate_voice(self):
        uid = self.prepare()['uploadId']; self.upload(uid)
        self.publish(self.publication([uid], [self.admin_id]))
        for title in ('Different title', self.spec['title']):
            second = self.prepare(requestId=str(uuid.uuid4()), title=title)['uploadId']
            self.upload(second)
            self.rejected(409, lambda: self.publish(self.publication([second], [self.admin_id])))
        self.assertEqual(1, len(self.catalog(self.admin)))

    def test_upload_repeat_and_atomic_publication_preserve_number_and_recipient_isolation(self):
        member = self.member()
        uid = self.prepare()['uploadId']
        self.assertEqual(uid, self.prepare()['uploadId'])
        self.upload(uid)
        self.upload(uid)
        self.assertEqual([], self.catalog(member))
        body = self.publication([uid], [member['principal']['accountId']])
        receipt = self.publish(body)
        self.assertEqual(receipt, self.publish(body))
        self.assertEqual('0101001', self.catalog(member)[0]['scoreNumber'])
        self.assertEqual('01 久石让', self.catalog(member)[0]['category'])
        self.assertEqual([], self.catalog(self.admin))
        self.app.catalog.dispatch('POST', '/v1/admin/grants', dict(accountId=member['principal']['accountId'], artifactId=uid, allowed=False), self.admin['token'], self.device)
        self.publish(body)
        self.assertEqual([], self.catalog(member))
        with self.service.connection() as db:
            self.assertEqual(1, db.execute('SELECT count(*) FROM artifacts').fetchone()[0])

    def test_bad_recipient_rolls_back_whole_publication_and_retry_conflicts_are_rejected(self):
        uid = self.prepare()['uploadId']
        self.upload(uid)
        self.rejected(409, lambda: self.prepare(title='changed'))
        body = self.publication([uid], [self.admin_id, str(uuid.uuid4())])
        self.rejected(409, lambda: self.publish(body))
        self.assertEqual([], self.catalog(self.admin))
        body['assignments'][0]['accountIds'] = [self.admin_id]
        self.publish(body)
        body['assignments'][0]['accountIds'] = [str(uuid.uuid4())]
        self.rejected(409, lambda: self.publish(body))

    def test_member_denied_before_stream_read_and_bad_pdf_never_enters_catalog(self):
        member = self.member()
        uid = self.prepare()['uploadId']
        class Unreadable:
            def read(self, size):
                raise AssertionError('unauthorized stream read')
        self.rejected(403, lambda: self.publisher.upload(uid, Unreadable(), len(self.pdf), member['token'], self.device))
        self.rejected(409, lambda: self.upload(uid, b'x' * len(self.pdf)))
        self.rejected(400, lambda: self.upload(uid, self.pdf[:-10]))
        with self.service.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM artifacts').fetchone()[0])
        self.assertEqual([], list(self.app.catalog.root.iterdir()))

    def test_expired_session_during_upload_cannot_commit(self):
        uid = self.prepare()['uploadId']
        test = self
        class Expiring(io.BytesIO):
            def read(self, size):
                test.now[0] = test.admin['expiresAt']
                return super().read(size)
        self.rejected(401, lambda: self.publisher.upload(uid, Expiring(self.pdf), len(self.pdf), self.admin['token'], self.device))
        self.assertEqual([], list(self.app.catalog.root.iterdir()))

    def test_wsgi_pdf_path_does_not_relax_json_limits_or_tls(self):
        uid = self.prepare()['uploadId']
        env = {'REQUEST_METHOD':'POST', 'PATH_INFO':'/v1/admin/uploads/'+uid+'/content',
               'wsgi.url_scheme':'https', 'CONTENT_TYPE':'application/pdf', 'CONTENT_LENGTH':str(len(self.pdf)),
               'wsgi.input':io.BytesIO(self.pdf), 'HTTP_AUTHORIZATION':'Bearer '+self.admin['token'], 'HTTP_X_DEVICE_ID':self.device}
        status = []
        result = json.loads(b''.join(self.app(env, lambda s,h:status.append(s))))
        self.assertEqual('200 OK', status[-1])
        self.assertEqual(uid, result['artifactId'])
        env.update(PATH_INFO='/v1/admin/uploads', CONTENT_TYPE='application/json', CONTENT_LENGTH='20000', **{'wsgi.input':io.BytesIO(b'{}')})
        b''.join(self.app(env, lambda s,h:status.append(s)))
        self.assertTrue(status[-1].startswith('413'))
