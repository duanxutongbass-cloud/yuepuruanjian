import concurrent.futures
import hashlib
import io
import json
import threading
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from unittest.mock import patch
from wsgiref.simple_server import WSGIRequestHandler, make_server

from pypdf import PdfWriter
from backup_archive import create_archive, restore_archive
from server import Application
from service import TeamService, Rejected
from test_accounts import AccountFixture, PASSWORD


class FreezeTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.app=Application(self.service)
        self.player=self.member()
        data=io.BytesIO();writer=PdfWriter();writer.add_blank_page(595,842);writer.add_blank_page(595,842);writer.write(data)
        self.pdf=data.getvalue()
        self.artifact=self.upload('0101001',[self.admin_id,self.player['principal']['accountId']])
        self.program=dict(id=str(uuid.uuid4()),title='Frozen rehearsal',pieceKeys=['sequence:001'],
                          accountIds=None,archived=False,expectedRevision=0)
        self.save(self.program)
        self.request=dict(programId=self.program['id'],requestId=str(uuid.uuid4()),expectedRevision=1)
        self.mark=dict(id=str(uuid.uuid4()),kind='SYMBOL',points=[[.2,.3]],color=0xff222222,width=.003,symbol='f',symbolScale=.75)

    def upload(self,number,accounts):
        body=dict(requestId=str(uuid.uuid4()),title='Synthetic score',partName=number[:2]+' Instrument',scoreNumber=number,
                  category='01 Category',sha256=hashlib.sha256(self.pdf).hexdigest(),size=len(self.pdf),independentSource=True)
        uid=self.app.publisher.prepare(body,self.admin['token'],self.device)['uploadId']
        self.app.publisher.upload(uid,io.BytesIO(self.pdf),len(self.pdf),self.admin['token'],self.device)
        self.app.publisher.publish(dict(requestId=str(uuid.uuid4()),assignments=[dict(artifactId=uid,accountIds=accounts)]),self.admin['token'],self.device)
        return uid

    def save(self,body):
        return self.app.programs.dispatch('POST','/v1/admin/programs',body,self.admin['token'],self.device)

    def freeze(self,body=None,login=None):
        return self.app.program_freezes.dispatch('POST','/v1/admin/program-freezes',body or self.request,
                                                (login or self.admin)['token'],self.device)

    def read(self,receipt,login=None,app=None):
        return (app or self.app).program_freezes.dispatch('GET',f"/v1/program-freezes/{receipt['programId']}/{receipt['freezeId']}",
                                                        None,(login or self.player)['token'],self.device)

    def catalog(self,login):
        return self.app.catalog.dispatch('GET','/v1/catalog',None,login['token'],self.device)

    def test_immutable_published_ink_only_and_current_grant_projection(self):
        cello=self.upload('0201001',[self.admin_id])
        private=dict(self.mark,id='private-marker')
        with self.service.connection() as db:
            for artifact,owner,kind,mark in [(self.artifact,'shared','published',self.mark),
                (self.artifact,'shared','draft',private),(self.artifact,self.player['principal']['accountId'],'personal',private),
                (cello,'shared','published',dict(self.mark,id='cello-marker'))]:
                db.execute('INSERT INTO annotation_heads VALUES(?,?,?,?,?,?)',(artifact,0,owner,kind,1,json.dumps([mark])))
        before=hashlib.sha256((Path(self.tmp.name)/'artifacts'/(self.artifact+'.pdf')).read_bytes()).hexdigest()
        receipt=self.freeze();self.assertEqual(2,receipt['artifactCount']);self.assertEqual(4,receipt['pageCount'])
        view=self.read(receipt);payload=json.loads(view['payload'])
        self.assertEqual(hashlib.sha256(view['payload'].encode()).hexdigest(),view['sha256'])
        self.assertEqual([self.artifact],[a['artifactId'] for a in payload['artifacts']])
        self.assertEqual([self.mark],payload['pages'][0]['marks'])
        self.assertNotIn('private-marker',view['payload']);self.assertNotIn('cello-marker',view['payload'])
        with self.service.connection() as db:
            db.execute("UPDATE annotation_heads SET payload='[]',revision=revision+1 WHERE kind='published'")
        self.assertEqual(view,self.read(receipt,app=Application(self.service)))
        self.assertEqual(2,len(json.loads(self.read(receipt,self.admin)['payload'])['artifacts']))
        extra=self.upload('0301001',[self.admin_id,self.player['principal']['accountId']])
        catalog=self.catalog(self.player)
        self.assertEqual(2,len(catalog['artifacts']))
        self.assertEqual([self.artifact],catalog['programs'][0]['frozenArtifactIds'])
        self.assertNotIn(extra,view['payload'])
        self.assertEqual(view,self.read(receipt))
        self.assertEqual(before,hashlib.sha256((Path(self.tmp.name)/'artifacts'/(self.artifact+'.pdf')).read_bytes()).hexdigest())

    def test_durable_idempotence_and_frozen_program_edits_require_new_program(self):
        receipt=self.freeze();self.assertEqual(receipt,self.freeze())
        self.app=Application(self.service);self.assertEqual(receipt,self.freeze())
        self.rejected(409,lambda:self.freeze(dict(self.request,expectedRevision=2)))
        self.rejected(409,lambda:self.freeze(dict(self.request,requestId=str(uuid.uuid4()))))
        for changes in (dict(title='Changed'),dict(accountIds=[]),dict(pieceKeys=['artifact:'+self.artifact])):
            self.rejected(409,lambda:self.save(dict(self.program,expectedRevision=1,**changes)))
        self.save(dict(self.program,expectedRevision=1,archived=True))
        self.rejected(404,lambda:self.read(receipt))
        self.save(dict(self.program,expectedRevision=2))
        self.assertEqual(receipt['freezeId'],self.catalog(self.player)['programs'][0]['freezeId'])
        self.assertEqual(receipt['freezeId'],self.read(receipt)['freezeId'])

    def test_concurrent_freezes_have_exactly_one_immutable_winner(self):
        requests=[dict(self.request,requestId=str(uuid.uuid4())) for _ in range(2)]
        def submit(body):
            try:return self.freeze(body)
            except Rejected as error:return error.status
        with concurrent.futures.ThreadPoolExecutor(2) as pool:results=list(pool.map(submit,requests))
        self.assertEqual(1,results.count(409))
        winner=next(i for i,r in enumerate(results) if isinstance(r,dict))
        self.assertEqual(results[winner],self.freeze(requests[winner]))
        with self.service.connection() as db:self.assertEqual(1,db.execute('SELECT count(*) FROM program_freezes').fetchone()[0])

    def test_authority_audience_archive_and_device_checks_are_live(self):
        self.rejected(403,lambda:self.freeze(login=self.player))
        self.rejected(409,lambda:self.freeze(dict(self.request,expectedRevision=2)))
        self.rejected(400,lambda:self.freeze(dict(self.request,accountId=self.admin_id)))
        receipt=self.freeze()
        excluded=self.member('excluded')
        self.rejected(404,lambda:self.read(receipt,excluded))
        # Knowing both UUIDs is insufficient after revocation. Catalogue grants also disappear.
        with self.service.connection() as db:
            db.execute('DELETE FROM artifact_grants WHERE account=?',(self.player['principal']['accountId'],))
        self.rejected(404,lambda:self.read(receipt))
        self.assertEqual([],self.catalog(self.player)['programs'])
        with self.service.connection() as db:
            db.execute('UPDATE devices SET revoked=1 WHERE account=? AND id=?',(self.admin_id,self.device))
        self.rejected(401,lambda:self.read(receipt,self.admin))

    def test_snapshot_caps_rollback_and_storage_tamper_fail_closed(self):
        with patch('program_freezes.MAX_FREEZE_PAGES',1):self.rejected(413,self.freeze)
        with patch('program_freezes.MAX_FREEZE_BYTES',128):self.rejected(413,self.freeze)
        with self.service.connection() as db:
            self.assertEqual(0,db.execute('SELECT count(*) FROM program_freezes').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM program_freeze_requests').fetchone()[0])
        receipt=self.freeze()
        with self.service.connection() as db:db.execute("UPDATE program_freezes SET pages='[{}]'")
        self.rejected(503,lambda:self.read(receipt))
        with self.service.connection() as db:
            db.execute("UPDATE program_freezes SET pages='[]'")
            db.execute("UPDATE artifacts SET hash=? WHERE id=?",('b'*64,self.artifact))
        self.rejected(503,lambda:self.read(receipt))

    def test_programme_audience_is_checked_even_with_a_valid_pdf_grant(self):
        self.save(dict(self.program,expectedRevision=1,accountIds=[self.admin_id]))
        receipt=self.freeze(dict(self.request,expectedRevision=2))
        self.rejected(404,lambda:self.read(receipt))
        self.assertEqual([],self.catalog(self.player)['programs'])
        self.assertEqual(self.artifact,self.catalog(self.player)['artifacts'][0]['artifactId'])
        self.assertEqual(receipt['freezeId'],self.read(receipt,self.admin)['freezeId'])

    def test_real_http_freeze_and_download_remain_authenticated(self):
        class Quiet(WSGIRequestHandler):
            def log_message(self,*args):pass
        server=make_server('127.0.0.1',0,Application(self.service,allow_loopback_http=True),handler_class=Quiet)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            base=f'http://127.0.0.1:{server.server_port}'
            def request(path,body=None,login=None):
                headers={'X-Device-Id':self.device,'Content-Type':'application/json'}
                if login:headers['Authorization']='Bearer '+login['token']
                req=urllib.request.Request(base+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
                with urllib.request.urlopen(req,timeout=10) as response:return json.load(response)
            with self.assertRaises(urllib.error.HTTPError) as rejected:request('/v1/admin/program-freezes',self.request)
            self.assertEqual(401,rejected.exception.code)
            receipt=request('/v1/admin/program-freezes',self.request,self.admin)
            result=request(f"/v1/program-freezes/{receipt['programId']}/{receipt['freezeId']}",login=self.player)
            self.assertEqual(receipt['freezeId'],result['freezeId'])
            self.assertEqual([self.artifact],[a['artifactId'] for a in json.loads(result['payload'])['artifacts']])
            with self.assertRaises(urllib.error.HTTPError) as rejected:request('/v1/admin/program-freezes',self.request,self.player)
            self.assertEqual(403,rejected.exception.code)
        finally:server.shutdown();thread.join(5);server.server_close()

    def test_complete_backup_preserves_frozen_versions_and_request_receipts(self):
        receipt=self.freeze();original=self.read(receipt,self.admin)
        archive=Path(self.tmp.name)/'snapshot.zip'
        create_archive(self.service,archive)
        restored=Path(self.tmp.name)/'restored'
        restore_archive(archive,restored)
        service=TeamService(restored,self.service.clock);app=Application(service)
        login=service.login(dict(username='director',password=PASSWORD,deviceId=self.device,deviceName='Restored test'),'127.0.0.1')
        path=f"/v1/program-freezes/{receipt['programId']}/{receipt['freezeId']}"
        result=app.program_freezes.dispatch('GET',path,None,login['token'],self.device)
        self.assertEqual(original,result)
        self.assertEqual(receipt,app.program_freezes.dispatch('POST','/v1/admin/program-freezes',self.request,login['token'],self.device))
        self.assertEqual(hashlib.sha256(self.pdf).hexdigest(),hashlib.sha256((restored/'artifacts'/(self.artifact+'.pdf')).read_bytes()).hexdigest())
