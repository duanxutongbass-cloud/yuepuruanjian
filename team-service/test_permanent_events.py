import json
import unittest
from unittest.mock import patch
import uuid

from permanent_events import DeletionEvents
import permanent_journal as journal
import test_permanent_deletion_preview as fixtures


class DeletionEventTest(unittest.TestCase):
    rejected=fixtures.PermanentPreviewTest.rejected
    def setUp(self):
        self.f=fixtures.PermanentPreviewTest();self.f.setUp();self.addCleanup(self.f.doCleanups)
        self.service,self.admin,self.device=self.f.service,self.f.admin,self.f.seed.device
        self.events=DeletionEvents(self.service)

    def query(self,after='0',storage=None,limit=100,token=None):
        return self.events.page(dict(protocolVersion=1,after=after,storageId=storage,limit=limit),
                                token or self.admin['token'],self.device)

    def delete(self):
        self.f.remove();p=self.f.preview()
        body=dict(artifactIds=[self.f.seed.artifact],expectedRevision=p['revision'],scopeDigest=p['scopeDigest'],
                  requestId=str(uuid.uuid4()),confirmed=True)
        return self.f.operation.commit(body,self.admin['token'],self.device)

    def test_empty_then_completed_page_has_identity_and_no_pdf_titles_or_ink(self):
        empty=self.query();self.assertEqual(('0','0',False,[]),(empty['through'],empty['nextAfter'],empty['more'],empty['deletions']))
        self.delete();first=self.query(storage=empty['storageId'])
        self.assertEqual(('1','1',False),(first['through'],first['nextAfter'],first['more']))
        self.assertEqual([self.f.seed.artifact],[r['artifactId'] for r in first['deletions']])
        self.assertEqual([self.f.seed.program_id],first['jobs'][0]['programIds'])
        self.assertEqual(self.f.seed.admin_id,first['accountId'])
        self.assertNotIn('Synthetic score',json.dumps(first));self.assertNotIn('marks',json.dumps(first))
        self.assertEqual([],self.query('1',first['storageId'])['deletions'])
        self.assertEqual(first,self.query(storage=empty['storageId']))

    def test_pending_physical_cleanup_holds_watermark_and_does_not_skip_later_completed_events(self):
        with patch.object(journal,'finish',side_effect=SystemExit()):
            with self.assertRaises(SystemExit):self.delete()
        self.assertEqual('0',self.query()['through'])
        self.assertEqual([],self.query()['deletions'])
        # A later job can finish while an earlier disk cleanup is blocked.
        with self.service.connection() as db:
            row=db.execute('SELECT * FROM permanent_deletion_jobs').fetchone()
            plan=json.loads(row['plan']);plan['requestId']=str(uuid.uuid4());plan['files'][0]['artifactId']=str(uuid.uuid4())
            db.execute('INSERT INTO permanent_deletion_jobs VALUES(?,?,?,?,2)',
                (row['owner'],plan['requestId'],'{}',json.dumps(plan)))
            db.execute('INSERT INTO artifact_tombstones(artifact,number,deleted,request) VALUES(?,?,?,?)',
                (plan['files'][0]['artifactId'],plan['files'][0]['number'],int(self.f.seed.now[0]),plan['requestId']))
        self.assertEqual('0',self.query()['through'])
        self.f.operation.recover()
        first=self.query(limit=1);self.assertTrue(first['more']);self.assertEqual(('2','1'),(first['through'],first['nextAfter']))
        second=self.query(first['nextAfter'],first['storageId'],1)
        self.assertEqual('2',second['nextAfter']);self.assertFalse(second['more'])
        self.assertNotEqual(first['deletions'][0]['artifactId'],second['deletions'][0]['artifactId'])

    def test_wrong_identity_bad_cursor_wrong_storage_and_unjoined_accounts_cannot_query(self):
        for after in ('01','-1','1.0','9223372036854775808',1,True):
            self.rejected(400,lambda:self.query(after))
        self.rejected(409,lambda:self.query(storage=str(uuid.uuid4())))
        self.rejected(409,lambda:self.query('1'))
        self.rejected(400,lambda:self.query(limit=101));self.rejected(400,lambda:self.query(limit=True))
        self.rejected(401,lambda:self.query(token='x'*32))
        with self.service.connection() as db:db.execute("UPDATE account_memberships SET state='pending' WHERE account=?",(self.f.seed.admin_id,))
        self.rejected(403,self.query)

    def test_corrupt_or_orphan_event_is_never_acknowledged_as_an_empty_page(self):
        self.delete()
        with self.service.connection() as db:db.execute('UPDATE artifact_tombstones SET artifact=?',(str(uuid.uuid4()),))
        self.rejected(503,self.query)
        with self.service.connection() as db:db.execute('DELETE FROM permanent_deletion_jobs')
        self.rejected(503,self.query)
