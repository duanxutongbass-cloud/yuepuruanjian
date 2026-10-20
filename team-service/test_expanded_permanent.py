import json
from pathlib import Path
import unittest
import uuid

import expanded_permanent as expanded
import test_expanded_candidate as storage_tests
import test_expanded_protocol as protocol_tests
from permanent_storage import prepare_permanent_storage, PermanentStorage
from permanent_backup import create_permanent_archive, restore_permanent_archive
from permanent_runtime import activate, open_active
from expanded_candidate import ExpandedCandidateService
from server import Application
from service import Rejected
from test_accounts import PASSWORD


class ExpandedPermanentTest(unittest.TestCase):
    api = protocol_tests.ExpandedProtocolTest.api
    prepare_upload = protocol_tests.ExpandedProtocolTest.prepare
    prepare = prepare_upload
    staged = protocol_tests.ExpandedProtocolTest.staged
    publication = protocol_tests.ExpandedProtocolTest.publication
    publish = protocol_tests.ExpandedProtocolTest.publish

    def setUp(self):
        self.seed = storage_tests.ExpandedStorageTest()
        self.seed.setUp(); self.addCleanup(self.seed.tearDown)
        self.root = Path(self.seed.tmp.name)
        self.v4 = self.root/'v4'
        prepare_permanent_storage(self.seed.archive, self.v4)
        activate(self.v4, self.seed.service.directory, True)
        self.archive = self.root/'v4.zip'
        create_permanent_archive(PermanentStorage(self.v4), self.archive)
        self.target = self.root/'expanded-v4'
        expanded.prepare(self.archive, self.target)
        expanded.activate(self.target, self.v4, True)
        self.service = expanded.ExpandedPermanentStorage(self.target, lambda:self.seed.now[0])
        self.app = expanded.ExpandedPermanentApplication(self.service)
        self.device, self.admin_id, self.pdf = self.seed.device, self.seed.admin_id, self.seed.pdf
        self.admin = self.service.login(dict(username='director',password=PASSWORD,deviceId=self.device,
            deviceName='Synthetic expanded v4'), '127.0.0.1')

    def delete(self, aid):
        revision = self.api('/v2/admin/ensemble')[1]['revision']
        status, body, _ = self.api('/v2/admin/catalog-actions', 'POST', dict(requestId=str(uuid.uuid4()),
            artifactIds=[aid], removed=True, expectedRevision=revision))
        self.assertEqual(200, status, body)
        status, preview, _ = self.api('/v2/admin/permanent-deletions/preview', 'POST',
            dict(artifactIds=[aid], expectedRevision=revision+1))
        self.assertEqual(200, status, preview)
        status, result, _ = self.api('/v2/admin/permanent-deletions', 'POST', dict(artifactIds=[aid],
            expectedRevision=preview['revision'], scopeDigest=preview['scopeDigest'],
            requestId=str(uuid.uuid4()), confirmed=True))
        self.assertEqual(200, status, result)

    def test_source_preserved_and_old_storage_and_archive_readers_refuse(self):
        self.assertEqual(4, self.service.database_version)
        before = self.archive.read_bytes()
        for reader in (PermanentStorage, ExpandedCandidateService, open_active):
            with self.assertRaises(Rejected):reader(self.target)
        with self.assertRaises(Rejected):create_permanent_archive(self.service,self.root/'wrong.zip')
        self.assertEqual(426,self.api('/v1/catalog')[0])
        self.assertEqual(426,self.api('/v1/admin/permanent-deletions/preview','POST',{})[0])
        self.assertFalse(self.api('/v2/admin/capabilities')[1]['expandedPublicationReady'])
        self.assertEqual(before,self.archive.read_bytes())
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())

    def test_pressure_survives_current_v4_full_backup_and_independent_restore(self):
        mark = dict(id=str(uuid.uuid4()), kind='PEN', points=[[.2,.3],[.8,.7]],
                    pressures=[.1,.9], color=0xff000000, width=.003, symbol='f')
        with self.service.connection() as db:
            db.execute('INSERT INTO annotation_heads VALUES(?,?,?,?,?,?)',
                       (self.seed.artifact,0,self.admin_id,'personal',1,json.dumps([mark])))
        archive=self.root/'pressure-full.zip';expanded.create_archive(self.service,archive)
        original=archive.read_bytes();restored=self.root/'pressure-restored'
        expanded.restore_archive(archive,restored);expanded.activate(restored,self.target,True)
        opened=expanded.open_active(restored)
        with opened.connection() as db:
            stored=json.loads(db.execute('SELECT payload FROM annotation_heads WHERE artifact=? AND owner=?',
                                         (self.seed.artifact,self.admin_id)).fetchone()[0])
        self.assertEqual([mark],stored);self.assertEqual(original,archive.read_bytes())
        self.assertEqual(self.pdf,(restored/'artifacts'/(self.seed.artifact+'.pdf')).read_bytes())

    def test_expanded_deletion_retirement_backup_restore_and_old_copy_activation(self):
        aid,_ = self.staged('01-01-1000');self.publish([aid])
        old = self.root/'before-delete.zip';expanded.create_archive(self.service,old)
        self.delete(aid)
        self.assertFalse((self.target/'artifacts'/(aid+'.pdf')).exists())
        inventory=self.api('/v2/admin/number-inventory')[1]
        self.assertEqual(['001','1000'],inventory['occupiedSequences'])
        status,result,_=self.api('/v1/deletions','POST',dict(protocolVersion=1,storageId=None,after='0',limit=100))
        self.assertEqual(200,status,result)
        status,_,_=self.api('/v2/admin/uploads','POST',dict(requestId=str(uuid.uuid4()), title='Reuse',partName='02 Cello',
            scoreNumber='02-01-1000',category='01 Style',sha256=__import__('hashlib').sha256(self.pdf).hexdigest(),
            size=len(self.pdf),independentSource=True))
        self.assertEqual(409,status)
        restored=self.root/'old-restored';expanded.restore_archive(old,restored)
        self.assertTrue((restored/'artifacts'/(aid+'.pdf')).exists())
        with self.assertRaises(Rejected):expanded.open_active(restored)
        expanded.activate(restored,self.target,True)
        active=expanded.open_active(restored)
        self.assertFalse((restored/'artifacts'/(aid+'.pdf')).exists())
        with active.connection() as db:
            self.assertEqual(['01-01-1000'],[r[0] for r in db.execute('SELECT number FROM artifact_tombstones')])
            self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())
        current=self.root/'current.zip';expanded.create_archive(active,current)
        fresh=self.root/'fresh';expanded.restore_archive(current,fresh)
        self.assertFalse((fresh/'artifacts'/(aid+'.pdf')).exists())
        with self.assertRaises(Rejected):restore_permanent_archive(current,self.root/'legacy-restore')

    def test_full_64bit_pdf_survives_new_archive_and_activation(self):
        aid,_=self.staged('01-01-9223372036854775807');self.publish([aid])
        saved=self.root/'max.zip';expanded.create_archive(self.service,saved)
        restored=self.root/'max-restored';expanded.restore_archive(saved,restored)
        expanded.activate(restored,self.target,True)
        self.service=expanded.open_active(restored)
        self.app=expanded.ExpandedPermanentApplication(self.service)
        self.admin=self.service.login(dict(username='director',password=PASSWORD,deviceId=self.device,
            deviceName='Restored max'), '127.0.0.1')
        status,pdf,_=self.api('/v2/artifacts/'+aid)
        self.assertEqual((200,self.pdf),(status,pdf))
        row=next(a for a in self.api('/v2/catalog')[1]['artifacts'] if a['artifactId']==aid)
        self.assertEqual('9223372036854775807',row['sequence'])
