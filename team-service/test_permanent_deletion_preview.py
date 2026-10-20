import hashlib
import json
from pathlib import Path
import unittest
import uuid

from permanent_storage import PermanentStorage,prepare_permanent_storage
from permanent_deletion import PermanentDeletion,piece_reference
from server import Application
from test_accounts import AccountFixture,PASSWORD
import test_expanded_candidate as storage_tests


class PermanentPreviewTest(unittest.TestCase):
    rejected=AccountFixture.rejected
    def setUp(self):
        self.seed=storage_tests.ExpandedStorageTest();self.seed.setUp();self.addCleanup(self.seed.tearDown)
        self.target=Path(self.seed.tmp.name)/'purge-preview'
        prepare_permanent_storage(self.seed.archive,self.target)
        self.service=PermanentStorage(self.target,lambda:self.seed.now[0]);self.app=Application(self.service)
        self.admin=self.service.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,
            deviceName='Synthetic deletion preview'),'127.0.0.1')
        self.operation=PermanentDeletion(self.service,self.app.catalog)

    def remove(self):
        with self.service.connection() as db:
            revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
        self.app.programs.dispatch('POST','/v1/admin/catalog-actions',dict(requestId=str(uuid.uuid4()),
            artifactIds=[self.seed.artifact],removed=True,expectedRevision=revision),self.admin['token'],self.seed.device)

    def body(self):
        with self.service.connection() as db:
            revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
        return dict(artifactIds=[self.seed.artifact],expectedRevision=revision)

    def preview(self,body=None,actor=None):
        return self.operation.preview(body or self.body(),(actor or self.admin)['token'],self.seed.device)

    def state(self):
        with self.service.connection() as db:return tuple(db.iterdump())

    def test_preview_counts_personal_shared_history_freezes_and_folders_without_deleting(self):
        self.service.dispatch('POST','/v1/me/backup-consent',dict(accepted=True),self.admin['token'],self.seed.device,'127.0.0.1')
        marks=[dict(id='synthetic-ink',kind='PEN',points=[[.1,.2],[.2,.3]],color=0xff000000,width=.002,symbol='p')]
        for kind in ('personal','draft','published'):
            body=dict(protocolVersion=1,requestId=str(uuid.uuid4()),baseRevision=0,
                artifactHash=hashlib.sha256(self.seed.pdf).hexdigest(),marks=marks)
            if kind=='published':body['draftRevision']=1
            self.app.annotations.dispatch('POST','/v1/annotations/'+self.seed.artifact+'/0/'+kind,body,self.admin['token'],self.seed.device)
        folder=[dict(id='folder',name='Synthetic folder',pieces=[piece_reference('sequence:001')],
            coverPiece=piece_reference('sequence:001'),image=None)]
        payload=json.dumps(folder)
        with self.service.connection() as db:
            # Simulate receipts written by v1, before exact targets existed.
            db.execute('DELETE FROM annotation_operation_targets')
            db.execute('INSERT INTO personal_folder_heads VALUES(?,?,?,?)',(self.seed.admin_id,1,payload,hashlib.sha256(payload.encode()).hexdigest()))
        self.remove();before=self.state()
        result=self.preview()
        self.assertEqual((1,len(self.seed.pdf),1,2,3,3),(result['fileCount'],result['totalBytes'],
            result['personalPages'],result['sharedPages'],result['historyRecords'],result['legacyReceiptCount']))
        self.assertEqual(1,len(result['freezeIds']));self.assertEqual(1,len(result['programs']))
        self.assertEqual([self.seed.admin_id],result['folderAccounts'])
        self.assertFalse(result['permanentDeletionReady'])
        self.assertEqual(result['scopeDigest'],self.preview()['scopeDigest'])
        self.assertEqual(before,self.state())
        self.assertEqual(self.seed.pdf,(self.app.catalog.root/(self.seed.artifact+'.pdf')).read_bytes())
        self.assertNotIn('synthetic-ink',json.dumps(result))

    def test_current_admin_removed_state_revision_and_uuid_selection_are_required(self):
        self.rejected(409,self.preview)
        self.remove();body=self.body()
        self.rejected(409,lambda:self.preview(dict(body,expectedRevision=body['expectedRevision']-1)))
        self.rejected(400,lambda:self.preview(dict(body,artifactIds=[self.seed.artifact,self.seed.artifact])))
        self.rejected(404,lambda:self.preview(dict(body,artifactIds=[str(uuid.uuid4())])))
        code=self.service.dispatch('POST','/v1/admin/invitations',{},self.admin['token'],self.seed.device,'127.0.0.1')['invitation']
        member=self.service.register(dict(username='syntheticplayer',password=PASSWORD,displayName='Player',invitation=code,
            deviceId=self.seed.device,deviceName='Synthetic player',backupConsent=True),'127.0.0.1')
        self.rejected(403,lambda:self.preview(actor=member))
        self.rejected(503,lambda:PermanentDeletion(self.seed.service,self.seed.app.catalog))

    def test_changed_scope_produces_new_digest_and_cannot_guess_an_unchanged_plan(self):
        self.remove();first=self.preview()
        with self.service.connection() as db:
            db.execute('INSERT INTO annotation_history(artifact,page,owner,kind,revision,payload,actor,at) VALUES(?,?,?,?,?,?,?,?)',
                (self.seed.artifact,0,self.seed.admin_id,'personal',0,'[]',self.seed.admin_id,int(self.seed.now[0])))
        second=self.preview()
        self.assertNotEqual(first['scopeDigest'],second['scopeDigest']);self.assertEqual(1,second['historyRecords'])

    def test_corrupted_or_replaced_pdf_is_never_silently_deleted_by_preview(self):
        self.remove();pdf=self.app.catalog.root/(self.seed.artifact+'.pdf');pdf.write_bytes(b'Unexpected contents')
        before=self.state();self.rejected(409,self.preview)
        self.assertEqual(b'Unexpected contents',pdf.read_bytes());self.assertEqual(before,self.state())
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())
