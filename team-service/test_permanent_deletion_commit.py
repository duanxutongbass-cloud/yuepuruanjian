import hashlib
from concurrent.futures import ThreadPoolExecutor
import io
import json
import os
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid

import permanent_journal as journal
import test_permanent_deletion_preview as fixtures
from backup_archive import fingerprint
from number_inventory import inventory
from permanent_backup import create_permanent_archive,restore_permanent_archive
from permanent_storage import PermanentStorage
from permanent_deletion import PermanentDeletion,piece_reference
from publishing import canonical
from server import Application
from service import Rejected


class PermanentCommitTest(unittest.TestCase):
    rejected=fixtures.PermanentPreviewTest.rejected

    def setUp(self):
        self.fixture=fixtures.PermanentPreviewTest();self.fixture.setUp();self.addCleanup(self.fixture.doCleanups)
        f=self.fixture
        self.service,self.app,self.operation=f.service,f.app,f.operation
        self.seed,self.admin=f.seed,f.admin
        self.token,self.device=self.admin['token'],self.seed.device
        self.artifact=self.seed.artifact
        self.pdf=self.app.catalog.root/(self.artifact+'.pdf')

    def call(self,body):return self.operation.commit(body,self.token,self.device)

    def body(self):
        self.fixture.remove()
        preview=self.fixture.preview()
        return dict(artifactIds=[self.artifact],expectedRevision=preview['revision'],scopeDigest=preview['scopeDigest'],
                    requestId=str(uuid.uuid4()),confirmed=True)

    def state(self):
        with self.service.connection() as db:
            row=db.execute('SELECT completed FROM permanent_deletion_jobs').fetchone()
            return row[0] if row else None

    def folder(self):
        folders=[dict(id='saved',name='Saved',pieces=[piece_reference('sequence:001')],
                      coverPiece=piece_reference('sequence:001'),image=None)]
        self.app.personal_folders.dispatch('POST','/v1/personal-folders',dict(protocolVersion=1,
            requestId=str(uuid.uuid4()),baseRevision=0,folders=folders),self.token,self.device)
        return folders

    def spec(self,number='0101001'):
        return dict(requestId=str(uuid.uuid4()),title='Synthetic score',partName=number[:2]+' Instrument',
            scoreNumber=number,category='01 Style',sha256=hashlib.sha256(self.seed.pdf).hexdigest(),
            size=len(self.seed.pdf),independentSource=True)

    def upload(self,number='0201001'):
        spec=self.spec(number)
        aid=self.app.publisher.prepare(spec,self.token,self.device)['uploadId']
        self.app.publisher.upload(aid,io.BytesIO(self.seed.pdf),len(self.seed.pdf),self.token,self.device)
        self.app.publisher.publish(dict(requestId=str(uuid.uuid4()),assignments=[dict(artifactId=aid,
            accountIds=[self.seed.admin_id])]),self.token,self.device)
        return aid

    def ink(self,artifact,kind='personal'):
        body=dict(protocolVersion=1,requestId=str(uuid.uuid4()),baseRevision=0,
            artifactHash=hashlib.sha256(self.seed.pdf).hexdigest(),marks=[dict(id='private-ink',kind='PEN',
            points=[[.1,.2],[.2,.3]],color=0xff000000,width=.002,symbol='p')])
        if kind=='published':body['draftRevision']=1
        path='/v1/annotations/'+artifact+'/0/'+kind
        self.app.annotations.dispatch('POST',path,body,self.token,self.device)
        return path,body

    def test_confirmed_delete_erases_selected_pdf_and_ink_and_clears_references(self):
        self.service.dispatch('POST','/v1/me/backup-consent',dict(accepted=True),self.token,self.device,'127.0.0.1')
        for kind in ('personal','draft','published'):self.ink(self.artifact,kind)
        self.folder();body=self.body();result=self.call(body)
        from permanent_events import DeletionEvents
        notice=DeletionEvents(self.service).page(dict(protocolVersion=1,storageId=None,after='0',limit=100),self.token,self.device)
        self.assertEqual([piece_reference('sequence:001')],notice['jobs'][0]['pieceReferences'])
        self.assertEqual((True,[self.artifact]),(result['ok'],result['artifactIds']))
        self.assertEqual(2,self.state());self.assertFalse(self.pdf.exists())
        self.assertEqual([],list((self.service.directory/'permanent-journal').iterdir()))
        with self.service.connection() as db:
            for table in ('artifacts','artifact_grants','artifact_metadata','removed_artifacts','annotation_heads',
                          'annotation_history','program_freezes','program_freeze_requests','uploads'):
                self.assertEqual(0,db.execute('SELECT count(*) FROM '+table).fetchone()[0],table)
            receipts=list(db.execute('SELECT result,status FROM annotation_operations'))
            self.assertEqual([410]*3,[r['status'] for r in receipts])
            self.assertNotIn('private-ink',canonical([tuple(r) for r in receipts]))
            program=db.execute('SELECT * FROM programs').fetchone()
            self.assertEqual(('[]',1,2),(program['pieces'],program['archived'],program['revision']))
            folder=db.execute('SELECT * FROM personal_folder_heads').fetchone()
            self.assertEqual(2,folder['revision']);content=json.loads(folder['payload'])[0]
            self.assertEqual(([],None,None),(content['pieces'],content['coverPiece'],content['image']))
            self.assertEqual(['001'],inventory(db,self.seed.now[0])['occupiedSequences'])
            self.assertEqual([],list(db.execute('PRAGMA foreign_key_check')))
            self.assertEqual(self.artifact,db.execute('SELECT artifact FROM artifact_tombstones').fetchone()[0])
        # Unuploaded original and original full snapshot are never touched.
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())
        self.assertEqual(self.seed.pdf,(self.seed.app.catalog.root/(self.artifact+'.pdf')).read_bytes())
        self.assertNotIn(b'private-ink',self.service.path.read_bytes())

    def test_identical_retry_does_not_repeat_events_and_conflicting_or_unconfirmed_requests_fail(self):
        body=self.body();self.rejected(400,lambda:self.call(dict(body,confirmed=False)))
        self.assertIsNone(self.state());first=self.call(body)
        self.assertEqual(first,self.call(body))
        self.rejected(409,lambda:self.call(dict(body,expectedRevision=body['expectedRevision']+1)))
        with self.service.connection() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
            db.execute("UPDATE accounts SET role='member' WHERE id=?",(self.seed.admin_id,))
        self.rejected(403,lambda:self.call(body))

    def test_changed_ink_scope_or_catalogue_is_rejected_before_files_move(self):
        body=self.body()
        with self.service.connection() as db:
            db.execute('INSERT INTO annotation_history(artifact,page,owner,kind,revision,payload,actor,at) VALUES(?,?,?,?,?,?,?,?)',
                (self.artifact,0,self.seed.admin_id,'personal',0,'[]',self.seed.admin_id,int(self.seed.now[0])))
        self.rejected(409,lambda:self.call(body));self.assertIsNone(self.state());self.assertTrue(self.pdf.exists())
        body['scopeDigest']=self.fixture.preview()['scopeDigest']
        with self.service.connection() as db:self.app.catalog.changed(db)
        self.rejected(409,lambda:self.call(body));self.assertTrue(self.pdf.exists())

    def test_sql_failure_restores_files_and_same_plan_can_retry(self):
        body=self.body()
        with patch.object(journal,'erase_rows',side_effect=RuntimeError('Injected DB failure')):
            with self.assertRaisesRegex(RuntimeError,'Injected DB'):self.call(body)
        self.assertEqual(0,self.state());self.assertEqual(self.seed.pdf,self.pdf.read_bytes())
        with self.service.connection() as db:self.assertEqual(0,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
        self.assertTrue(self.call(body)['ok'])

    def test_restart_before_db_commit_restores_moved_file_and_keeps_source_hash(self):
        body=self.body();move=journal.move_file
        def crash(*args):move(*args);raise SystemExit('Process interrupted before DB commit')
        with patch.object(journal,'move_file',side_effect=crash):
            with self.assertRaises(SystemExit):self.call(body)
        self.assertFalse(self.pdf.exists());self.assertEqual(0,self.state())
        reopened=PermanentStorage(self.service.directory,lambda:self.seed.now[0]);app=Application(reopened)
        PermanentDeletion(reopened,app.catalog).recover()
        self.assertEqual(self.seed.pdf,self.pdf.read_bytes());self.assertTrue(self.call(body)['ok'])

    def test_restart_between_link_and_unlink_removes_only_same_inode_duplicate(self):
        body=self.body()
        def crash(source,destination,item):os.link(source,destination);raise SystemExit('Between link and unlink')
        with patch.object(journal,'move_file',side_effect=crash):
            with self.assertRaises(SystemExit):self.call(body)
        self.assertEqual(2,self.pdf.stat().st_nlink)
        self.operation.recover();self.assertEqual(1,self.pdf.stat().st_nlink)
        self.assertEqual(self.seed.pdf,self.pdf.read_bytes());self.assertTrue(self.call(body)['ok'])

    def test_restart_after_db_commit_continues_erasing_and_never_restores(self):
        body=self.body()
        with patch.object(journal,'finish',side_effect=SystemExit('After commit')):
            with self.assertRaises(SystemExit):self.call(body)
        self.assertEqual(1,self.state());self.assertFalse(self.pdf.exists())
        staged=self.service.directory/'permanent-journal'/body['requestId']/(self.artifact+'.pdf')
        self.assertTrue(staged.exists())
        archive=self.service.directory.parent/'unfinished.zip'
        self.rejected(409,lambda:create_permanent_archive(self.service,archive));self.assertFalse(archive.exists())
        self.operation.recover();self.assertEqual(2,self.state());self.assertFalse(staged.exists());self.assertFalse(self.pdf.exists())
        self.assertTrue(self.call(body)['ok'])

    def test_cleanup_failure_is_not_success_and_retry_finishes(self):
        body=self.body()
        with patch.object(journal,'erase_files',side_effect=OSError('Injected disk cleanup fault')):
            with self.assertRaisesRegex(OSError,'cleanup fault'):self.call(body)
        self.assertEqual(1,self.state());self.assertFalse(self.pdf.exists())
        self.assertTrue(self.call(body)['ok']);self.assertEqual(2,self.state())

    def test_restart_preserves_unexpected_original_collision_and_staged_pdf(self):
        body=self.body();move=journal.move_file
        def crash(*args):move(*args);raise SystemExit()
        with patch.object(journal,'move_file',side_effect=crash):
            with self.assertRaises(SystemExit):self.call(body)
        self.pdf.write_bytes(b'Unexpected replacement; never overwrite')
        staged=self.service.directory/'permanent-journal'/body['requestId']/self.pdf.name
        self.rejected(409,self.operation.recover)
        self.assertEqual(b'Unexpected replacement; never overwrite',self.pdf.read_bytes())
        self.assertEqual(self.seed.pdf,staged.read_bytes());self.assertEqual(0,self.state())
        self.pdf.unlink();self.operation.recover();self.assertEqual(self.seed.pdf,self.pdf.read_bytes())

    def test_changed_staged_file_after_commit_blocks_cleanup_without_hiding_fault(self):
        body=self.body()
        with patch.object(journal,'finish',side_effect=SystemExit()):
            with self.assertRaises(SystemExit):self.call(body)
        staged=self.service.directory/'permanent-journal'/body['requestId']/self.pdf.name
        staged.write_bytes(b'Unexpected staged file')
        self.rejected(409,self.operation.recover);self.assertEqual(1,self.state())
        self.assertEqual(b'Unexpected staged file',staged.read_bytes())

    def test_same_piece_other_parts_and_their_ink_remain_but_deleted_part_cannot_return(self):
        self.service.dispatch('POST','/v1/me/backup-consent',dict(accepted=True),self.token,self.device,'127.0.0.1')
        other=self.upload();path,body=self.ink(other);self.ink(self.artifact)
        self.folder();request=self.body();self.call(request)
        from permanent_events import DeletionEvents
        notice=DeletionEvents(self.service).page(dict(protocolVersion=1,storageId=None,after='0',limit=100),self.token,self.device)
        self.assertEqual([],notice['jobs'][0]['pieceReferences'])
        head=self.app.annotations.dispatch('GET',path,None,self.token,self.device)
        self.assertEqual('private-ink',head['marks'][0]['id'])
        with self.service.connection() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM annotation_heads').fetchone()[0])
            self.assertEqual(1,db.execute('SELECT count(*) FROM annotation_history').fetchone()[0])
            self.assertEqual([200,410],sorted(r[0] for r in db.execute('SELECT status FROM annotation_operations')))
            self.assertEqual([piece_reference('sequence:001')],json.loads(db.execute('SELECT payload FROM personal_folder_heads').fetchone()[0])[0]['pieces'])
            p=db.execute('SELECT * FROM programs').fetchone();self.assertEqual(('001',0), (json.loads(p['pieces'])[0][-3:],p['archived']))
        self.rejected(409,lambda:self.app.publisher.prepare(self.spec(),self.token,self.device))
        self.assertTrue(self.upload('0301001'))  # Legitimate new instrument on the surviving piece.
        self.rejected(409,lambda:self.app.publisher.prepare(dict(self.spec('0401001'),title='Different work'),self.token,self.device))
        self.assertEqual(self.seed.pdf,(self.app.catalog.root/(other+'.pdf')).read_bytes())

    def test_old_uploads_and_whole_retired_number_cannot_resurrect_deleted_piece(self):
        pending=self.spec('0201001');upload=self.app.publisher.prepare(pending,self.token,self.device)['uploadId']
        self.call(self.body())
        self.rejected(409,lambda:self.app.publisher.prepare(pending,self.token,self.device))
        self.rejected(409,lambda:self.app.publisher.prepare(self.spec('0301001'),self.token,self.device))
        class NeverRead:
            def read(self,*args):raise AssertionError('Cancelled upload must stop before reading')
        self.rejected(404,lambda:self.app.publisher.upload(upload,NeverRead(),len(self.seed.pdf),self.token,self.device))
        with self.service.connection() as db:
            self.assertTrue(db.execute('SELECT 1 FROM cancelled_uploads WHERE request=?',(pending['requestId'],)).fetchone())
            self.rejected(409,lambda:self.app.catalog.check_publication(db,[dict(id=str(uuid.uuid4()),number='0401001',title='Synthetic score',category='01 Style')]))

    def test_legacy_receipts_expire_without_deleting_unrelated_actual_ink_or_reviving_folder(self):
        self.service.dispatch('POST','/v1/me/backup-consent',dict(accepted=True),self.token,self.device,'127.0.0.1')
        unrelated=self.upload('0201002');path,ink=self.ink(unrelated)
        with self.service.connection() as db:db.execute('DELETE FROM annotation_operation_targets')
        folders=self.folder();self.call(self.body())
        reply=self.app.annotations.dispatch('POST',path,ink,self.token,self.device)
        self.assertEqual(410,reply.status);self.assertNotIn('private-ink',canonical(reply.data))
        head=self.app.annotations.dispatch('GET',path,None,self.token,self.device)
        self.assertEqual('private-ink',head['marks'][0]['id'])
        body=dict(protocolVersion=1,requestId=str(uuid.uuid4()),baseRevision=2,folders=folders)
        self.rejected(409,lambda:self.app.personal_folders.dispatch('POST','/v1/personal-folders',body,self.token,self.device))
        with self.service.connection() as db:self.assertEqual(2,db.execute('SELECT revision FROM personal_folder_heads').fetchone()[0])

    def test_full_backup_after_delete_keeps_only_retirement_evidence_and_rebinds_job_identity(self):
        body=self.body();self.call(body)
        archive=self.service.directory.parent/'after-delete.zip'
        create_permanent_archive(self.service,archive);original=fingerprint(archive)
        target=self.service.directory.parent/'restored-delete'
        restore_permanent_archive(archive,target);restored=PermanentStorage(target,lambda:self.seed.now[0])
        app=Application(restored);PermanentDeletion(restored,app.catalog).recover()
        self.assertEqual(original,fingerprint(archive))
        with restored.connection() as db:
            self.assertEqual(0,db.execute('SELECT count(*) FROM artifacts').fetchone()[0])
            self.assertEqual([self.artifact],[r[0] for r in db.execute('SELECT artifact FROM artifact_tombstones')])
            self.assertEqual(['001'],inventory(db,self.seed.now[0])['occupiedSequences'])
        self.assertEqual([],list((target/'artifacts').iterdir()))

    def test_partial_multi_file_move_rolls_back_entire_selection(self):
        other=self.upload('0201001');body=self.body()
        with self.service.connection() as db:db.execute('INSERT INTO removed_artifacts VALUES(?)',(other,));db.execute('UPDATE artifacts SET published=0 WHERE id=?',(other,))
        scope=dict(artifactIds=[self.artifact,other],expectedRevision=body['expectedRevision'])
        preview=self.operation.preview(scope,self.token,self.device)
        body.update(scope,scopeDigest=preview['scopeDigest'])
        original=journal.move_file;calls=[]
        def fail_second(*args):
            calls.append(args)
            if len(calls)==2:raise OSError('Second file move fault')
            return original(*args)
        with patch.object(journal,'move_file',side_effect=fail_second):
            with self.assertRaisesRegex(OSError,'Second file'):self.call(body)
        self.assertEqual(0,self.state())
        for aid in (self.artifact,other):self.assertEqual(self.seed.pdf,(self.app.catalog.root/(aid+'.pdf')).read_bytes())
        with self.service.connection() as db:self.assertEqual(0,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
        self.assertEqual(2,len(self.call(body)['artifactIds']))

    def test_concurrent_confirmations_have_one_winner_and_stale_plan_recovers_safely(self):
        body=self.body()
        def attempt(request):
            try:return self.call(dict(body,requestId=request))
            except Rejected as error:return error.status
        with ThreadPoolExecutor(max_workers=2) as pool:
            results=list(pool.map(attempt,[str(uuid.uuid4()),str(uuid.uuid4())]))
        self.assertEqual(1,sum(isinstance(r,dict) and r['ok'] for r in results))
        self.assertTrue(any(r in (404,409) for r in results if isinstance(r,int)))
        self.operation.recover()
        with self.service.connection() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM artifact_tombstones').fetchone()[0])
        self.assertFalse(self.pdf.exists())

    def test_empty_journal_after_proof_unlink_and_unknown_journal_entries_fail_safely(self):
        body=self.body()
        with patch.object(journal,'finish',side_effect=SystemExit()):
            with self.assertRaises(SystemExit):self.call(body)
        path=self.service.directory/'permanent-journal'/body['requestId']
        (path/'unexpected.txt').write_text('Preserve unknown file',encoding='utf-8')
        self.rejected(503,self.operation.recover)
        self.assertTrue((path/self.pdf.name).exists())
        (path/'unexpected.txt').unlink();(path/self.pdf.name).unlink();(path/'plan.json').unlink()
        self.operation.recover();self.assertEqual(2,self.state());self.assertFalse(path.exists())
