import hashlib
from concurrent.futures import ThreadPoolExecutor
import io
import json
from pathlib import Path
import threading
import unittest
import urllib.error
import urllib.request
import uuid

from waitress import create_server

from expanded_protocol import ExpandedApplication, ExpandedCatalog
from expanded_backup import create_expanded_archive, restore_expanded_archive
from expanded_candidate import ExpandedCandidateService, MARKER
from server import Application
from service import Rejected
from test_accounts import PASSWORD
import test_expanded_candidate as storage_tests


class ExpandedProtocolTest(unittest.TestCase):
    def setUp(self):
        fixture = storage_tests.ExpandedStorageTest()
        fixture.setUp()
        self.addCleanup(fixture.tearDown)
        self.fixture = fixture
        self.original = fixture.database()
        self.service = fixture.opened()
        self.device, self.admin_id, self.pdf = fixture.device, fixture.admin_id, fixture.pdf
        self.admin = self.service.login(dict(username='director', password=PASSWORD, deviceId=self.device,
            deviceName='Synthetic v2 client'), '127.0.0.1')
        self.app = ExpandedApplication(self.service)

    def api(self, path, method='GET', body=None, actor='admin', **extra):
        raw = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else b''
        login = self.admin if actor == 'admin' else actor
        env = dict(REQUEST_METHOD=method, PATH_INFO=path, CONTENT_TYPE='application/pdf' if isinstance(body, bytes)
            else 'application/json', CONTENT_LENGTH=str(len(raw)), HTTP_X_DEVICE_ID=self.device,
            HTTP_AUTHORIZATION='Bearer '+login['token'] if login else '')
        env.update({'wsgi.url_scheme':'https', 'wsgi.input':io.BytesIO(raw)})
        env.update(extra)
        statuses, headers = [], []
        data = b''.join(self.app(env, lambda s,h:(statuses.append(s), headers.extend(h))))
        status, headers = int(statuses[0].split()[0]), dict(headers)
        return status, data if headers['Content-Type'] == 'application/pdf' else json.loads(data), headers

    def member(self):
        code = self.service.dispatch('POST', '/v1/admin/invitations', {}, self.admin['token'], self.device,
            '127.0.0.1')['invitation']
        return self.service.register(dict(username='syntheticplayer', password=PASSWORD, displayName='Player',
            invitation=code, deviceId=self.device, deviceName='Synthetic v2 player', backupConsent=True), '127.0.0.1')

    def prepare(self, number, title='Synthetic expanded work', part='01 Violin'):
        body = dict(requestId=str(uuid.uuid4()), title=title, partName=part, scoreNumber=number,
            category='01 Style', sha256=hashlib.sha256(self.pdf).hexdigest(), size=len(self.pdf), independentSource=True)
        status, result, _ = self.api('/v2/admin/uploads', 'POST', body)
        self.assertEqual(200, status, result)
        return result['uploadId'], body

    def staged(self, number, **kwargs):
        uid, body = self.prepare(number, **kwargs)
        status, result, _ = self.api(f'/v2/admin/uploads/{uid}/content', 'POST', self.pdf)
        self.assertEqual(200, status, result)
        return uid, body

    def publication(self, ids, accounts=None):
        return dict(requestId=str(uuid.uuid4()), assignments=[dict(artifactId=i,
            accountIds=accounts if accounts is not None else [self.admin_id]) for i in ids])

    def publish(self, ids, accounts=None):
        body = self.publication(ids, accounts)
        status, result, _ = self.api('/v2/admin/publications', 'POST', body)
        self.assertEqual(200, status, result)
        return body, result

    def state(self):
        with self.service.connection() as db:
            return tuple(db.iterdump())

    def test_inventory_reserves_expanded_pending_and_retired_numbers_without_range_scan(self):
        self.prepare('01-01-1000')
        self.prepare('01-01-9223372036854775807')
        with self.service.connection() as db:
            db.execute('CREATE TABLE artifact_tombstones(number TEXT NOT NULL)')
            db.execute('INSERT INTO artifact_tombstones VALUES(?)', ('0201002',))
        before = self.state()
        status, result, _ = self.api('/v2/admin/number-inventory')
        self.assertEqual(200, status, result)
        self.assertEqual(2, result['protocolVersion'])
        self.assertEqual('9223372036854775807', result['maximumSequence'])
        self.assertEqual(['001', '002', '1000', '9223372036854775807'], result['occupiedSequences'])
        self.assertEqual('003', result['firstFreeSequence'])
        self.assertEqual('9223372036854775803', result['availableCount'])
        self.assertEqual(2, result['pendingUploads'])
        self.assertEqual(before, self.state())
        self.assertEqual(426, self.api('/v1/admin/number-inventory')[0])

    def test_actual_upload_publish_read_full_sequence_numeric_order_and_private_recipients(self):
        player = self.member()
        numbers = ['0101999', '01-01-1000', '01-01-9223372036854775807']
        ids = [self.staged(n, title='Work '+n)[0] for n in numbers]
        self.publish(ids, [player['principal']['accountId']])
        status, catalog, _ = self.api('/v2/catalog', actor=player)
        self.assertEqual(200, status)
        self.assertEqual(2, catalog['protocolVersion'])
        actual = {a['scoreNumber']:a for a in catalog['artifacts']}
        self.assertEqual(set(numbers), set(actual))
        for n, sequence in zip(numbers, ['999','1000','9223372036854775807']):
            self.assertEqual(sequence, actual[n]['sequence'])
            self.assertEqual(1 if len(n) == 7 else 2, actual[n]['namingVersion'])
        groups = self.api('/v2/admin/ensemble')[1]['groups']
        self.assertEqual(['001','999','1000','9223372036854775807'], [g['number'] for g in groups])
        self.assertEqual(['sequence:'+g['number'] for g in groups], [g['key'] for g in groups])
        self.assertEqual([self.fixture.artifact], [a['artifactId'] for a in self.api('/v2/catalog')[1]['artifacts']])
        for uid in ids:
            self.assertEqual(404, self.api('/v2/artifacts/'+uid)[0])
            status, pdf, headers = self.api('/v2/artifacts/'+uid, actor=player)
            self.assertEqual((200,self.pdf), (status,pdf))
            self.assertEqual('"'+hashlib.sha256(self.pdf).hexdigest()+'"', headers['ETag'])
        self.assertEqual(self.original, self.fixture.database())
        self.assertEqual(self.fixture.original, self.fixture.archive.read_bytes())

    def test_v1_routes_never_expose_numbers_or_read_old_request_bodies_even_after_v2_call(self):
        uid, _ = self.staged('01-01-1000')
        self.publish([uid])
        before = self.state()
        class Unreadable:
            def read(self, *args): raise AssertionError('legacy body read')
        paths = ['/v1/catalog','/v1/artifacts/'+uid,'/v1/admin/artifacts','/v1/admin/ensemble',
                 '/v1/program-freezes/'+str(uuid.uuid4())+'/'+str(uuid.uuid4())]
        for path in paths:
            self.assertEqual(426, self.api(path)[0], path)
        for path in ['/v1/admin/uploads','/v1/admin/uploads/'+uid+'/content','/v1/admin/publications',
                     '/v1/admin/grants','/v1/admin/artifacts/publish','/v1/admin/programs',
                     '/v1/admin/catalog-actions','/v1/admin/program-freezes','/v1/admin/publication-cancellations']:
            status, result, _ = self.api(path, 'POST', {}, **{'wsgi.input':Unreadable()})
            self.assertEqual((426,'reader_numbering_upgrade_required'), (status,result['error']), path)
        self.assertEqual(before, self.state())
        self.assertEqual(401, self.api('/v1/catalog', actor=None)[0])
        self.assertEqual(401, self.api('/v2/catalog', actor=None)[0])
        self.assertEqual(200, self.api('/v1/me')[0])

    def test_candidate_capabilities_truthful_readonly_auth_and_normal_app_stays_closed(self):
        before = self.state()
        old = self.api('/v1/admin/capabilities')[1]
        new = self.api('/v2/admin/capabilities')[1]
        self.assertEqual(([1], '999', False), (old['numbering']['namingVersions'],
            old['numbering']['maximumSequence'], old['expandedPublicationReady']))
        self.assertEqual((2, [1,2], '9223372036854775807', False, True), (new['protocolVersion'],
            new['numbering']['namingVersions'],new['numbering']['maximumSequence'],
            new['expandedPublicationReady'],new['candidateOnly']))
        self.assertEqual(self.admin['principal']['teamId'], new['teamId'])
        self.assertEqual(before, self.state())
        self.assertEqual(401, self.api('/v2/admin/capabilities', actor=None)[0])
        player = self.member()
        self.assertEqual(403, self.api('/v2/admin/capabilities', actor=player)[0])
        self.assertEqual(405, self.api('/v2/admin/capabilities','POST',{})[0])
        self.assertEqual(403, self.api('/v2/catalog', **{'wsgi.url_scheme':'http'})[0])
        self.assertEqual(403, self.api('/v2/catalog', HTTP_X_ROLE='admin')[0])
        self.assertEqual(403, self.api('/v2/catalog', HTTP_ORIGIN='https://foreign.example')[0])
        self.assertEqual(403, self.api('/v2/catalog', QUERY_STRING='version=2')[0])
        with self.assertRaises(Rejected): ExpandedCatalog(self.fixture.service)
        with self.assertRaises(Rejected): ExpandedApplication(self.fixture.service)
        legacy = self.app
        self.app = Application(self.service)
        try:
            self.assertEqual(404, self.api('/v2/catalog')[0])
            self.assertEqual(False, self.api('/v1/admin/capabilities')[1]['expandedPublicationReady'])
        finally:
            self.app = legacy

    def test_same_work_new_instrument_supplements_full_sequence_and_duplicates_need_new_version(self):
        first, _ = self.staged('01-01-1000')
        self.publish([first])
        second, _ = self.staged('02-01-1000', part='02 Viola')
        self.publish([second])
        group = next(g for g in self.api('/v2/admin/ensemble')[1]['groups'] if g['number']=='1000')
        self.assertEqual({first,second}, {p['artifactId'] for p in group['parts']})
        self.assertFalse(group['conflict'])
        for number,title in [('01-01-1000','Synthetic expanded work'), ('03-01-1000','Different work'),
                             ('03-02-1000','Synthetic expanded work')]:
            uid, _ = self.staged(number,title=title)
            self.assertEqual(409, self.api('/v2/admin/publications','POST',self.publication([uid]))[0])
            self.assertEqual(409, self.api('/v2/admin/artifacts/publish','POST',dict(artifactId=uid,published=True))[0])
        version, _ = self.staged('01-01-1001', title='Synthetic expanded work（第二版）')
        self.publish([version])
        self.assertEqual('Synthetic expanded work', group['title'])
        self.assertEqual(2, len(group['parts']))

    def test_conflicting_batch_rolls_back_every_part_and_removed_numbers_stay_reserved(self):
        first, _ = self.staged('01-01-1000')
        second, _ = self.staged('01-01-1000')
        before = self.state()
        self.assertEqual(409, self.api('/v2/admin/publications','POST',self.publication([first,second]))[0])
        self.assertEqual(before, self.state())
        self.publish([first])
        revision = self.api('/v2/admin/ensemble')[1]['revision']
        self.assertEqual(200,self.api('/v2/admin/catalog-actions','POST', dict(requestId=str(uuid.uuid4()),
            artifactIds=[first],removed=True,expectedRevision=revision))[0])
        self.assertEqual(409, self.api('/v2/admin/publications','POST',self.publication([second]))[0])
        group = next(g for g in self.api('/v2/admin/ensemble')[1]['groups'] if g['number']=='1000')
        self.assertTrue(group['removed'])
        self.assertEqual(self.pdf, (self.service.directory/'artifacts'/(first+'.pdf')).read_bytes())

    def test_invalid_numbers_rejected_without_upload_or_catalog_rows(self):
        _, body = self.prepare('01-01-1000')
        before = self.state()
        for value in ['01011000','01-01-001','01-01-01000','01-01-9223372036854775808',
                      '01-01-１０００','01-01-1e3',True,1000,None,'0101000','01-01-1000-title.pdf',
                      ' 01-01-1000','01-01-1000 ', ' 0101001']:
            status, result, _ = self.api('/v2/admin/uploads','POST',dict(body,requestId=str(uuid.uuid4()),scoreNumber=value))
            self.assertEqual(400,status,(value,result))
        self.assertEqual(before, self.state())

    def test_number_change_and_unauthorized_pdf_stream_do_not_commit_or_read(self):
        uid, _ = self.prepare('01-01-1000')
        outer = self
        class Changed(io.BytesIO):
            def read(stream, size=-1):
                with outer.service.connection() as db:
                    spec = json.loads(db.execute('SELECT spec FROM uploads WHERE id=?',(uid,)).fetchone()[0])
                    spec['scoreNumber']='01-01-1001'
                    db.execute('UPDATE uploads SET spec=? WHERE id=?',(json.dumps(spec),uid))
                return super().read(size)
        self.assertEqual(409, self.api('/v2/admin/uploads/'+uid+'/content','POST',self.pdf,
            **{'wsgi.input':Changed(self.pdf)})[0])
        self.assertFalse((self.service.directory/'artifacts'/(uid+'.pdf')).exists())
        player = self.member()
        class Unreadable:
            def read(self,*args): raise AssertionError('unauthorized PDF stream read')
        self.assertEqual(403, self.api('/v2/admin/uploads/'+uid+'/content','POST',self.pdf,actor=player,
            **{'wsgi.input':Unreadable()})[0])

    def test_expired_session_after_stream_cannot_commit_expanded_pdf(self):
        uid,_ = self.prepare('01-01-1000')
        outer = self
        class Expiring(io.BytesIO):
            def read(stream,size=-1):
                outer.fixture.now[0] = outer.admin['expiresAt']
                return super().read(size)
        self.assertEqual(401, self.api('/v2/admin/uploads/'+uid+'/content','POST',self.pdf,
            **{'wsgi.input':Expiring(self.pdf)})[0])
        self.assertFalse((self.service.directory/'artifacts'/(uid+'.pdf')).exists())
        with self.service.connection() as db:
            self.assertIsNone(db.execute('SELECT 1 FROM artifacts WHERE id=?',(uid,)).fetchone())

    def test_concurrent_duplicate_publish_is_serialized_and_keeps_both_immutable_pdfs(self):
        first,_=self.staged('01-01-1000')
        second,_=self.staged('01-01-1000')
        bodies=[self.publication([first]),self.publication([second])]
        with ThreadPoolExecutor(max_workers=2) as pool:
            replies=list(pool.map(lambda body:self.api('/v2/admin/publications','POST',body),bodies))
        self.assertEqual([200,409],sorted(reply[0] for reply in replies))
        rows=[a for a in self.api('/v2/admin/artifacts')[1]['artifacts'] if a['artifactId'] in (first,second)]
        self.assertEqual(1,sum(row['published'] for row in rows))
        for uid in (first,second):
            self.assertEqual(self.pdf,(self.service.directory/'artifacts'/(uid+'.pdf')).read_bytes())

    def test_cancel_retry_revoke_and_receipt_do_not_regrant_or_overwrite_pdf(self):
        uid, body = self.staged('01-01-1000')
        self.assertEqual(uid, self.api('/v2/admin/uploads','POST',body)[1]['uploadId'])
        publication, receipt = self.publish([uid])
        self.assertEqual(receipt, self.api('/v2/admin/publications','POST',publication)[1])
        self.assertEqual(200, self.api('/v2/admin/grants','POST',dict(accountId=self.admin_id,artifactId=uid,allowed=False))[0])
        self.assertEqual(receipt, self.api('/v2/admin/publications','POST',publication)[1])
        self.assertEqual(404, self.api('/v2/artifacts/'+uid)[0])
        self.assertEqual(self.pdf, (self.service.directory/'artifacts'/(uid+'.pdf')).read_bytes())
        pending, prepared = self.prepare('01-01-1001')
        self.assertEqual(200, self.api('/v2/admin/publication-cancellations','POST',dict(
            publicationRequest=str(uuid.uuid4()),uploadRequests=[prepared['requestId']]))[0])
        self.assertEqual(409, self.api('/v2/admin/uploads/'+pending+'/content','POST',self.pdf)[0])

    def test_expanded_program_freeze_preserves_keys_order_numbers_and_per_pdf_revocation(self):
        player = self.member()
        a, _ = self.staged('01-01-1000')
        b, _ = self.staged('02-01-1000',part='02 Viola')
        c, _ = self.staged('01-01-9223372036854775807',title='Another work')
        self.publish([a,b,c], [self.admin_id,player['principal']['accountId']])
        program = dict(id=str(uuid.uuid4()),title='Synthetic concert',pieceKeys=['sequence:9223372036854775807','sequence:1000'],
            accountIds=[player['principal']['accountId']],archived=False,expectedRevision=0)
        self.assertEqual(200,self.api('/v2/admin/programs','POST',program)[0])
        status, freeze, _ = self.api('/v2/admin/program-freezes','POST',dict(programId=program['id'],
            requestId=str(uuid.uuid4()),expectedRevision=1))
        self.assertEqual(200,status,freeze)
        path='/v2/program-freezes/'+program['id']+'/'+freeze['freezeId']
        status, reply, _ = self.api(path,actor=player)
        self.assertEqual(200,status,reply)
        payload=json.loads(reply['payload'])
        self.assertEqual(2,payload['protocolVersion'])
        self.assertEqual(program['pieceKeys'],payload['pieceKeys'])
        self.assertEqual([c], [x['artifactId'] for x in payload['artifacts'][:1]])
        self.assertEqual(hashlib.sha256(reply['payload'].encode()).hexdigest(),reply['sha256'])
        self.assertEqual(200,self.api('/v2/admin/grants','POST',dict(accountId=player['principal']['accountId'],
            artifactId=b,allowed=False))[0])
        selected=json.loads(self.api(path,actor=player)[1]['payload'])
        self.assertEqual({a,c},{x['artifactId'] for x in selected['artifacts']})
        self.assertEqual(426,self.api(path.replace('/v2/','/v1/'),actor=player)[0])
        # Existing v1 freezes are retained byte-for-byte and still project safely.
        with self.service.connection() as db:
            row=db.execute('SELECT id FROM program_freezes WHERE program=?',(self.fixture.program_id,)).fetchone()
        legacy=self.api('/v2/program-freezes/'+self.fixture.program_id+'/'+row['id'])[1]
        self.assertEqual(1,json.loads(legacy['payload'])['protocolVersion'])
        self.assertEqual(self.original['program_freezes'][0][5], self.fixture.database(self.service.path)['program_freezes'][0][5])

    def test_expanded_number_and_pdf_survive_new_full_backup_and_independent_restore(self):
        uid, _ = self.staged('01-01-9223372036854775807')
        self.publish([uid])
        archive=Path(self.fixture.tmp.name)/'expanded-roundtrip.zip'
        target=Path(self.fixture.tmp.name)/'restored-expanded'
        create_expanded_archive(self.service,archive)
        proof=restore_expanded_archive(archive,target)
        self.assertFalse(proof['expandedPublicationReady'])
        restored=ExpandedCandidateService(target,lambda:self.fixture.now[0])
        self.service=restored
        self.app=ExpandedApplication(restored)
        self.admin=restored.login(dict(username='director',password=PASSWORD,deviceId=self.device,
            deviceName='Restored synthetic client'),'127.0.0.1')
        status, catalog, _=self.api('/v2/catalog')
        self.assertEqual(200,status)
        self.assertEqual('9223372036854775807',next(a for a in catalog['artifacts'] if a['artifactId']==uid)['sequence'])
        self.assertEqual(self.pdf,self.api('/v2/artifacts/'+uid)[1])
        self.assertEqual(self.original,self.fixture.database())

    def test_withdrawn_storage_blocks_all_numbered_routes_before_body_read(self):
        uid,_=self.prepare('01-01-1000')
        (self.service.directory/MARKER).unlink()
        class Unreadable:
            def read(self,*args): raise AssertionError('withdrawn body read')
        self.assertEqual(503,self.api('/v2/admin/uploads/'+uid+'/content','POST',self.pdf,
            **{'wsgi.input':Unreadable()})[0])
        self.assertEqual(503,self.api('/v2/catalog')[0])
        self.assertEqual(503,self.api('/v2/admin/capabilities')[0])

    def test_real_local_http_v2_upload_range_download_legacy_isolation_and_tls_default(self):
        self.app=ExpandedApplication(self.service,allow_loopback_http=True)
        server=create_server(self.app,host='127.0.0.1',port=0,threads=2)
        thread=threading.Thread(target=server.run,daemon=True)
        thread.start()
        try:
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            base='http://127.0.0.1:'+str(server.effective_port)
            def request(path,body=None,**headers):
                raw=body if isinstance(body,bytes) else json.dumps(body).encode() if body is not None else None
                hdr={'Authorization':'Bearer '+self.admin['token'],'X-Device-ID':self.device,
                     'Content-Type':'application/pdf' if isinstance(body,bytes) else 'application/json'}
                hdr.update(headers)
                req=urllib.request.Request(base+path,data=raw,headers=hdr)
                try:
                    with opener.open(req,timeout=10) as r:return r.status,r.read(),dict(r.headers)
                except urllib.error.HTTPError as e:
                    with e:return e.code,e.read(),dict(e.headers)
            spec=dict(requestId=str(uuid.uuid4()),title='HTTP work',partName='01 Violin',scoreNumber='01-01-1000',
                category='01 Style',sha256=hashlib.sha256(self.pdf).hexdigest(),size=len(self.pdf),independentSource=True)
            status,data,_=request('/v2/admin/uploads',spec)
            self.assertEqual(200,status,data)
            uid=json.loads(data)['uploadId']
            self.assertEqual(200,request('/v2/admin/uploads/'+uid+'/content',self.pdf)[0])
            self.assertEqual(200,request('/v2/admin/publications',self.publication([uid]))[0])
            status,data,headers=request('/v2/artifacts/'+uid,**{'Range':'bytes=7-19'})
            self.assertEqual((206,self.pdf[7:20]),(status,data))
            self.assertEqual('bytes 7-19/'+str(len(self.pdf)),headers['Content-Range'])
            self.assertEqual(412,request('/v2/artifacts/'+uid,**{'If-Match':'"wrong"'})[0])
            self.assertEqual(426,request('/v1/catalog')[0])
            self.assertEqual(False,json.loads(request('/v1/admin/capabilities')[1])['expandedPublicationReady'])
            self.assertEqual('01-01-1000',next(a for a in json.loads(request('/v2/catalog')[1])['artifacts']
                if a['artifactId']==uid)['scoreNumber'])
            with ThreadPoolExecutor(max_workers=2) as pool:
                replies=list(pool.map(request, ['/v2/catalog','/v1/catalog']*3))
            self.assertEqual([200,426]*3,[reply[0] for reply in replies])
        finally:
            server.task_dispatcher.shutdown()
            server.close()
            thread.join(5)
        self.assertFalse(thread.is_alive())
