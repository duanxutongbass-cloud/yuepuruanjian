import io
import hashlib
import json
import uuid
from pypdf import PdfWriter

from number_inventory import inventory
from test_accounts import AccountFixture
import test_publishing as publication_tests
from server import Application


class NumberInventoryTest(AccountFixture):
    prepare=publication_tests.PublishingTest.prepare
    upload=publication_tests.PublishingTest.upload
    publication=publication_tests.PublishingTest.publication
    publish=publication_tests.PublishingTest.publish
    def setUp(self):
        super().setUp()
        self.app=Application(self.service);self.publisher=self.app.publisher
        output=io.BytesIO();pdf=PdfWriter();pdf.add_blank_page(595,842);pdf.write(output)
        self.pdf=output.getvalue()
        self.spec=dict(requestId=str(uuid.uuid4()),title='Synthetic work',partName='01 Violin',scoreNumber='0101001',
            category='01 Style',sha256=hashlib.sha256(self.pdf).hexdigest(),size=len(self.pdf),independentSource=True)

    def read(self,login=None):
        return self.app.catalog.dispatch('GET','/v1/admin/number-inventory',None,(login or self.admin)['token'],self.device)

    def test_empty_inventory_and_all_metadata_staging_removal_keep_original_number_and_pdf(self):
        self.assertEqual(('001',999), (self.read()['firstFreeSequence'],self.read()['availableCount']))
        uid=self.prepare(scoreNumber='0101003')['uploadId'];self.upload(uid)
        before=self.pdf
        staged=self.read()
        self.assertEqual(['003'],staged['occupiedSequences']);self.assertEqual('001',staged['firstFreeSequence'])
        self.publish(self.publication([uid],[self.admin_id]))
        ensemble=self.app.programs.dispatch('GET','/v1/admin/ensemble',None,self.admin['token'],self.device)
        self.app.programs.dispatch('POST','/v1/admin/catalog-actions',dict(requestId=str(uuid.uuid4()),artifactIds=[uid],
            removed=True,expectedRevision=ensemble['revision']),self.admin['token'],self.device)
        self.assertEqual(['003'],self.read()['occupiedSequences'])
        self.assertEqual(before,(self.app.catalog.root/(uid+'.pdf')).read_bytes())

    def test_unexpired_queued_uploads_reserve_until_expiry_or_explicit_cancellation(self):
        first=self.prepare(scoreNumber='0101001')
        second=self.prepare(requestId=str(uuid.uuid4()),scoreNumber='0201002')
        self.assertEqual(['001','002'],self.read()['occupiedSequences'])
        self.app.publisher.cancel(dict(publicationRequest=str(uuid.uuid4()),uploadRequests=[self.spec['requestId']]),
            self.admin['token'],self.device)
        self.assertEqual(['002'],self.read()['occupiedSequences'])
        self.now[0]+=86401
        with self.service.connection() as db:
            result=inventory(db,self.service.clock())
        self.assertEqual([],result['occupiedSequences']);self.assertEqual('001',result['firstFreeSequence'])

    def test_inventory_is_admin_only_readonly_and_rejects_uninterpretable_or_expanded_values(self):
        member=self.member()
        self.rejected(403,lambda:self.read(member))
        uid=self.prepare()['uploadId'];self.upload(uid)
        with self.service.connection() as db:before=tuple(db.iterdump())
        self.read();self.read()
        with self.service.connection() as db:
            self.assertEqual(before,tuple(db.iterdump()))
            db.execute('UPDATE artifact_metadata SET number=? WHERE artifact=?',('01-01-1000',uid))
        self.rejected(426,self.read)
        with self.service.connection() as db:
            db.execute('UPDATE artifact_metadata SET number=? WHERE artifact=?',('unresolved',uid))
        self.rejected(400,self.read)

    def test_http_inventory_keeps_tls_and_authenticated_admin_boundaries(self):
        def call(login=None,secure=True):
            actor=self.admin if login is None else login
            env={'REQUEST_METHOD':'GET','PATH_INFO':'/v1/admin/number-inventory','wsgi.url_scheme':'https' if secure else 'http',
                 'wsgi.input':io.BytesIO(b''),'HTTP_AUTHORIZATION':'Bearer '+actor.get('token',''),'HTTP_X_DEVICE_ID':self.device}
            status=[]
            body=json.loads(b''.join(self.app(env,lambda s,h:status.append(s))))
            return int(status[0].split()[0]),body
        self.assertEqual((200,'001'),(call()[0],call()[1]['firstFreeSequence']))
        self.assertEqual(401,call({})[0]);self.assertEqual(403,call(self.member())[0])
        self.assertEqual(403,call(secure=False)[0])

    def test_ambiguous_queued_json_cannot_free_or_reassign_a_number(self):
        uid=self.prepare()['uploadId']
        with self.service.connection() as db:
            db.execute('UPDATE uploads SET spec=? WHERE id=?',('{"scoreNumber":"0101001","scoreNumber":"0101002"}',uid))
            before=tuple(db.iterdump())
        self.rejected(400,self.read)
        with self.service.connection() as db:self.assertEqual(before,tuple(db.iterdump()))
