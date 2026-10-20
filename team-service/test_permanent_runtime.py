import json
from pathlib import Path
import unittest
from unittest.mock import patch
import uuid
from zipfile import ZipFile
from app_release import AppReleases

from permanent_runtime import activate,open_active,ACTIVATION
from permanent_storage import prepare_permanent_storage,PermanentStorage
from permanent_backup import create_permanent_archive,restore_permanent_archive
from server import Application
from service import Rejected
from test_accounts import PASSWORD
import test_expanded_candidate as fixtures


class PermanentRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.seed=fixtures.ExpandedStorageTest();self.seed.setUp();self.addCleanup(self.seed.tearDown)
        self.target=Path(self.seed.tmp.name)/'active-v4'
        prepare_permanent_storage(self.seed.archive,self.target)

    def test_explicit_activation_and_stale_legacy_copy_rejected(self):
        with self.assertRaises(Rejected):open_active(self.target)
        with self.assertRaises(Rejected):activate(self.target,self.seed.service.directory)
        apk=Path(self.seed.tmp.name)/'synthetic-update.apk'
        with ZipFile(apk,'w') as bundle:
            bundle.writestr('AndroidManifest.xml',b'Synthetic non-installable fixture');bundle.writestr('classes.dex',b'Synthetic')
        release=AppReleases(self.seed.service.directory).publish(apk,30,'0.10.18','Synthetic update preservation')
        before=self.seed.database()
        proof=activate(self.target,self.seed.service.directory,True)
        self.assertTrue(proof['permanentDeletionReady']);open_active(self.target)
        self.assertEqual(release,AppReleases(self.target).current())
        self.assertEqual(before,self.seed.database())
        stale=Path(self.seed.tmp.name)/'stale-v4'
        prepare_permanent_storage(self.seed.archive,stale)
        with self.seed.service.connection() as db:db.execute("UPDATE accounts SET name='Changed after backup' WHERE id=?",(self.seed.admin_id,))
        with self.assertRaises(Rejected) as error:activate(stale,self.seed.service.directory,True)
        self.assertEqual('permanent_migration_source_changed',error.exception.code)
        self.assertFalse((stale/ACTIVATION).exists())

    def test_old_backup_merges_current_retirements_before_activation(self):
        activate(self.target,self.seed.service.directory,True)
        service=PermanentStorage(self.target,lambda:self.seed.now[0]);app=Application(service)
        old=Path(self.seed.tmp.name)/'before-delete.zip';create_permanent_archive(service,old)
        actor=service.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,deviceName='Deletion authority'),'127.0.0.1')
        with service.connection() as db:revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
        app.programs.dispatch('POST','/v1/admin/catalog-actions',dict(requestId=str(uuid.uuid4()),artifactIds=[self.seed.artifact],removed=True,
            expectedRevision=revision),actor['token'],self.seed.device)
        p=app.permanent.preview(dict(artifactIds=[self.seed.artifact],expectedRevision=revision+1),actor['token'],self.seed.device)
        app.permanent.commit(dict(artifactIds=[self.seed.artifact],expectedRevision=p['revision'],scopeDigest=p['scopeDigest'],
            requestId=str(uuid.uuid4()),confirmed=True),actor['token'],self.seed.device)
        source_before=self.seed.database(service.path)
        restored=Path(self.seed.tmp.name)/'restored-old-v4';restore_permanent_archive(old,restored)
        self.assertTrue((restored/'artifacts'/(self.seed.artifact+'.pdf')).exists())
        with self.assertRaises(Rejected):open_active(restored)
        activate(restored,self.target,True)
        active=open_active(restored)
        self.assertFalse((restored/'artifacts'/(self.seed.artifact+'.pdf')).exists())
        with active.connection() as db:
            self.assertEqual([self.seed.artifact],[r[0] for r in db.execute('SELECT artifact FROM artifact_tombstones')])
            self.assertEqual(0,db.execute('SELECT count(*) FROM program_freezes').fetchone()[0])
            self.assertEqual([],db.execute('PRAGMA foreign_key_check').fetchall())
        self.assertEqual(source_before,self.seed.database(service.path))
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())

    def test_incomplete_activation_never_serves(self):
        with patch('permanent_runtime.os.link',side_effect=OSError('Synthetic marker fault')):
            with self.assertRaises(OSError):activate(self.target,self.seed.service.directory,True)
        self.assertFalse((self.target/ACTIVATION).exists())
        with self.assertRaises(Rejected):open_active(self.target)
        activate(self.target,self.seed.service.directory,True)
