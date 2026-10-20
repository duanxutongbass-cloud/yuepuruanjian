import copy,json,uuid,sqlite3,hashlib,threading,urllib.request,urllib.error
from contextlib import closing
from pathlib import Path
import unittest
from unittest.mock import patch

import test_expanded_permanent as v4fixture
import test_expanded_protocol as v2fixture
import expanded_permanent as expanded
from permanent_deletion import piece_reference
from service import Rejected
from number_release import ledger

class NumberReleaseTest(unittest.TestCase):
    def setUp(self):
        self.f=v4fixture.ExpandedPermanentTest();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.root=self.f.target
    def api(self,*args,**kw):return self.f.api(*args,**kw)
    def view(self):
        status,data,_=self.api('/v2/admin/number-releases');self.assertEqual(200,status,data);return data
    def release_body(self,seqs):
        revision=self.view()['revision']
        status,data,_=self.api('/v2/admin/number-releases/preview','POST',dict(sequences=seqs,expectedRevision=revision))
        self.assertEqual(200,status,data)
        return dict(sequences=seqs,expectedRevision=revision,scopeDigest=data['scopeDigest'],requestId=str(uuid.uuid4()),confirmed=True)
    def release(self,seqs):
        body=self.release_body(seqs);status,data,_=self.api('/v2/admin/number-releases/commit','POST',body)
        self.assertEqual(200,status,data);return body,data
    def keep_three(self):
        aid,_=self.f.staged('0101003',title='Keep original three');self.f.publish([aid]);return aid
    def test_release_only_fully_deleted_number_retains_other_pdf_and_retirements(self):
        keep=self.keep_three();original=(self.root/'artifacts'/(keep+'.pdf')).read_bytes()
        self.f.delete(self.f.seed.artifact)
        self.assertEqual(['001'],[r['sequence'] for r in self.view()['eligible']])
        body,result=self.release(['001'])
        inventory=self.api('/v2/admin/number-inventory')[1]
        self.assertEqual(['003'],inventory['occupiedSequences']);self.assertEqual('001',inventory['firstFreeSequence'])
        self.assertEqual(original,(self.root/'artifacts'/(keep+'.pdf')).read_bytes())
        with self.f.service.connection() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
            self.assertEqual(1,db.execute('SELECT count(*) FROM permanent_deletion_jobs WHERE completed=2').fetchone()[0])
            self.assertEqual(body['requestId'],ledger(db,self.f.app.catalog.read_number)['001'][0])
            self.assertEqual(keep,db.execute('SELECT artifact FROM artifact_metadata WHERE number=?',('0101003',)).fetchone()[0])
        backup=self.root/'backups/number-releases'/body['requestId']/'before.zip'
        self.assertTrue(backup.is_file());self.assertGreater(result['backup']['size'],0)
    def test_live_recycled_partial_or_pending_numbers_cannot_be_released(self):
        self.assertEqual([],self.view()['eligible'])
        with self.f.service.connection() as db:title=db.execute('SELECT title FROM artifacts WHERE id=?',(self.f.seed.artifact,)).fetchone()[0]
        second,_=self.f.staged('0201001',title=title,part='02 Cello');self.f.publish([second])
        self.f.delete(self.f.seed.artifact)
        self.assertEqual([],self.view()['eligible'])
        rev=self.view()['revision']
        self.assertEqual(409,self.api('/v2/admin/number-releases/preview','POST',dict(sequences=['001'],expectedRevision=rev))[0])
        self.f.delete(second)
        with self.f.service.connection() as db:
            spec=dict(title=title,partName='01 Violin',category='01 Style',scoreNumber='0101001',
                      sha256=hashlib.sha256(self.f.pdf).hexdigest(),size=len(self.f.pdf))
            db.execute('INSERT INTO uploads VALUES(?,?,?,?,?,0)',(str(uuid.uuid4()),self.f.admin_id,str(uuid.uuid4()),json.dumps(spec),int(self.f.service.clock())+100))
        self.assertEqual([],self.view()['eligible'])
    def test_confirmation_scope_revision_auth_and_protocol_gates(self):
        self.f.delete(self.f.seed.artifact);body=self.release_body(['001'])
        for changes,status in ((dict(confirmed=False),400),(dict(scopeDigest='0'*64),409),(dict(expectedRevision=body['expectedRevision']+1),409)):
            code,_,_=self.api('/v2/admin/number-releases/commit','POST',dict(body,**changes));self.assertEqual(status,code)
        for seqs in ([],['001','001'],['1'],['000'],['9223372036854775808']):
            self.assertEqual(400,self.api('/v2/admin/number-releases/preview','POST',dict(sequences=seqs,expectedRevision=body['expectedRevision']))[0])
        member=v2fixture.ExpandedProtocolTest.member(self.f)
        self.assertEqual(403,self.api('/v2/admin/number-releases',actor=member)[0])
        self.assertEqual(401,self.api('/v2/admin/number-releases',actor=None)[0])
        self.assertEqual(426,self.api('/v1/admin/number-releases')[0])
        self.assertEqual(['001'],self.api('/v2/admin/number-inventory')[1]['occupiedSequences'])
    def test_replay_returns_same_receipt_without_releasing_new_incarnation(self):
        self.f.delete(self.f.seed.artifact);body,result=self.release(['001'])
        new,_=self.f.staged('0101001',title='New incarnation');self.f.publish([new])
        self.assertEqual((200,result),self.api('/v2/admin/number-releases/commit','POST',body)[:2])
        self.assertEqual(['001'],self.api('/v2/admin/number-inventory')[1]['occupiedSequences'])
        self.assertEqual(409,self.api('/v2/admin/number-releases/commit','POST',dict(body,scopeDigest='0'*64))[0])
        self.assertTrue((self.root/'artifacts'/(new+'.pdf')).is_file())
    def test_reused_piece_has_new_identity_old_requests_and_readers_stay_blocked(self):
        old=self.f.seed.artifact
        with self.f.service.connection() as db:title=db.execute('SELECT title FROM artifacts WHERE id=?',(old,)).fetchone()[0]
        _,old_intent=self.f.prepare('0201001',title=title,part='02 Cello')
        self.f.delete(old);body,_=self.release(['001'])
        new,_=self.f.staged('0101001',title='New incarnation');self.f.publish([new])
        self.assertEqual(426,self.api('/v2/catalog')[0])
        status,catalog,_=self.api('/v2/catalog',HTTP_X_SCORE_NUMBER_REUSE='1');self.assertEqual(200,status,catalog)
        row=next(a for a in catalog['artifacts'] if a['artifactId']==new)
        self.assertEqual(body['requestId'],row['pieceGeneration'])
        group=self.api('/v2/admin/ensemble')[1]['groups'][0]
        self.assertEqual('sequence:001:released:'+body['requestId'],group['key'])
        self.assertNotEqual(piece_reference('sequence:001'),piece_reference(group['key']))
        self.assertEqual(404,self.api('/v2/artifacts/'+old)[0])
        self.assertEqual(409,self.api('/v2/admin/uploads','POST',old_intent)[0])
        self.assertEqual(self.f.pdf,(self.root/'artifacts'/(new+'.pdf')).read_bytes())
    def test_reused_program_freeze_and_folder_reference_do_not_resurrect_old_piece(self):
        self.f.delete(self.f.seed.artifact);body,_=self.release(['001'])
        aid,_=self.f.staged('0101001',title='New incarnation');self.f.publish([aid])
        key='sequence:001:released:'+body['requestId'];program=str(uuid.uuid4())
        self.assertEqual(200,self.api('/v2/admin/programs','POST',dict(id=program,title='New scene',pieceKeys=[key],
            accountIds=None,archived=False,expectedRevision=0))[0])
        code,frozen,_=self.api('/v2/admin/program-freezes','POST',dict(programId=program,expectedRevision=1,requestId=str(uuid.uuid4())))
        self.assertEqual(200,code,frozen)
        code,snapshot,_=self.api('/v2/program-freezes/'+program+'/'+frozen['freezeId'])
        self.assertEqual(200,code,snapshot)
        encoded=json.dumps(snapshot);self.assertIn(key,encoded);self.assertIn(aid,encoded)
        folder=dict(id=str(uuid.uuid4()),name='New folder',pieces=[piece_reference('sequence:001')],coverPiece=None,image=None)
        payload=dict(protocolVersion=1,requestId=str(uuid.uuid4()),baseRevision=0,folders=[folder])
        self.assertEqual(409,self.api('/v1/personal-folders','POST',payload)[0])
        folder['pieces']=[piece_reference(key)];payload['requestId']=str(uuid.uuid4())
        code,result,_=self.api('/v1/personal-folders','POST',payload);self.assertEqual(200,code,result)
    def test_actual_http_release_gate_backup_and_reused_reader_header(self):
        from waitress import create_server
        self.f.delete(self.f.seed.artifact)
        app=expanded.ExpandedPermanentApplication(self.f.service,allow_loopback_http=True)
        server=create_server(app,host='127.0.0.1',port=0);thread=threading.Thread(target=server.run,daemon=True);thread.start()
        self.addCleanup(server.close)
        origin='http://127.0.0.1:'+str(server.effective_port)
        def call(path,body=None,authenticated=True,reuse=False):
            headers={'X-Device-Id':self.f.device}
            if authenticated:headers['Authorization']='Bearer '+self.f.admin['token']
            if reuse:headers['X-Score-Number-Reuse']='1'
            if body is not None:headers['Content-Type']='application/json'
            request=urllib.request.Request(origin+path,data=json.dumps(body).encode() if body is not None else None,headers=headers)
            try:response=urllib.request.urlopen(request,timeout=10)
            except urllib.error.HTTPError as error:response=error
            with response:return response.status,json.load(response)
        self.assertEqual(401,call('/v2/admin/number-releases',authenticated=False)[0])
        code,view=call('/v2/admin/number-releases');self.assertEqual(200,code)
        code,preview=call('/v2/admin/number-releases/preview',dict(sequences=['001'],expectedRevision=view['revision']));self.assertEqual(200,code)
        body=dict(sequences=['001'],expectedRevision=view['revision'],scopeDigest=preview['scopeDigest'],requestId=str(uuid.uuid4()),confirmed=True)
        code,result=call('/v2/admin/number-releases/commit',body);self.assertEqual(200,code,result)
        aid,_=self.f.staged('0101001',title='HTTP released new work');self.f.publish([aid])
        self.assertEqual(426,call('/v2/catalog')[0]);self.assertEqual(200,call('/v2/catalog',reuse=True)[0])
    def test_delete_reused_incarnation_reserves_again_until_explicit_release(self):
        self.f.delete(self.f.seed.artifact);first,_=self.release(['001'])
        new,_=self.f.staged('0101001',title='New incarnation');self.f.publish([new]);self.f.delete(new)
        self.assertEqual(['001'],self.api('/v2/admin/number-inventory')[1]['occupiedSequences'])
        with self.f.service.connection() as db:
            plans=[json.loads(r[0]) for r in db.execute('SELECT plan FROM permanent_deletion_jobs')]
        self.assertTrue(any(piece_reference('sequence:001:released:'+first['requestId']) in p['pieceReferences'] for p in plans))
        second,_=self.release(['001']);self.assertNotEqual(first['requestId'],second['requestId'])
        self.assertEqual([],self.api('/v2/admin/number-inventory')[1]['occupiedSequences'])
    def test_current_backup_roundtrip_keeps_reused_pdf_old_backup_activation_is_rejected(self):
        old=self.root.parent/'before-release.zip';expanded.create_archive(self.f.service,old)
        self.f.delete(self.f.seed.artifact);self.release(['001'])
        new,_=self.f.staged('0101001',title='New incarnation');self.f.publish([new])
        stale=self.root.parent/'stale-number-restore';expanded.restore_archive(old,stale)
        with self.assertRaises(Rejected) as raised:expanded.activate(stale,self.root,True)
        self.assertEqual('number_release_restore_requires_current_backup',raised.exception.code)
        self.assertFalse((stale/'permanent-active.json').exists())
        current=self.root.parent/'current-number-release.zip';expanded.create_archive(self.f.service,current)
        restored=self.root.parent/'current-number-restore';expanded.restore_archive(current,restored)
        expanded.activate(restored,self.root,True)
        self.assertEqual(self.f.pdf,(restored/'artifacts'/(new+'.pdf')).read_bytes())
        with expanded.open_active(restored).connection() as db:
            self.assertEqual(1,len(ledger(db,self.f.app.catalog.read_number)))
            self.assertEqual(1,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
    def test_commit_failure_rolls_back_release_and_retry_succeeds_with_backup_retained(self):
        self.f.delete(self.f.seed.artifact);body=self.release_body(['001'])
        with patch.object(self.f.app.catalog,'changed',side_effect=RuntimeError('Synthetic commit failure')):
            self.assertEqual(503,self.api('/v2/admin/number-releases/commit','POST',body)[0])
        self.assertEqual(['001'],self.api('/v2/admin/number-inventory')[1]['occupiedSequences'])
        self.assertTrue((self.root/'backups/number-releases'/body['requestId']/'before.zip').is_file())
        self.assertEqual(200,self.api('/v2/admin/number-releases/commit','POST',body)[0])
