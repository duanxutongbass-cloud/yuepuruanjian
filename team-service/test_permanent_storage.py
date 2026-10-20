from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import unittest
from unittest.mock import patch

from expanded_candidate import ExpandedCandidateService, IDENTITY, ROOT, NAMING
from expanded_backup import create_expanded_archive
from expanded_protocol import ExpandedApplication
from permanent_storage import PermanentStorage, prepare_permanent_storage, MARKER
from service import TeamService, Rejected
from test_accounts import AccountFixture, PASSWORD
import test_expanded_candidate as storage_tests


class PermanentStorageTest(unittest.TestCase):
    rejected=AccountFixture.rejected
    def setUp(self):
        self.seed=storage_tests.ExpandedStorageTest();self.seed.setUp();self.addCleanup(self.seed.tearDown)
        self.target=Path(self.seed.tmp.name)/'permanent'
        self.before=self.seed.database()

    def opened(self):
        proof=prepare_permanent_storage(self.seed.archive,self.target)
        return PermanentStorage(self.target,lambda:self.seed.now[0]),proof

    def test_separate_v4_snapshot_preserves_all_business_rows_and_pdfs_without_public_activation(self):
        service,proof=self.opened()
        after=self.seed.database(service.path)
        for table,rows in self.before.items():
            if table in ('sessions','invitations'):
                self.assertEqual([],after[table])
            elif table=='metadata':
                self.assertEqual(sorted(rows), sorted(r for r in after[table] if r[0] not in
                    {IDENTITY,ROOT,NAMING,'permanentDeletionVersion'}))
            else:self.assertEqual(rows,after[table],table)
        self.assertEqual(4,proof['databaseVersion']);self.assertEqual(1,proof['namingVersion'])
        self.assertFalse(proof['permanentDeletionReady'])
        self.assertEqual([],after['artifact_tombstones']);self.assertEqual([],after['permanent_deletion_jobs'])
        self.assertEqual(self.before,self.seed.database())
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())
        copied=self.target/'artifacts'/(self.seed.artifact+'.pdf')
        self.assertEqual(self.seed.pdf,copied.read_bytes())
        self.assertFalse(os.path.samefile(copied,self.seed.app.catalog.root/copied.name))
        principal=service.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,
            deviceName='Synthetic v4 client'),'127.0.0.1')['principal']
        self.assertEqual(self.seed.admin_id,principal['accountId'])
        self.rejected(503,lambda:TeamService(self.target))
        self.rejected(503,lambda:ExpandedCandidateService(self.target))

    def test_wrong_path_storage_identity_version_and_purpose_do_not_open_or_recreate_database(self):
        service,_=self.opened()
        other=Path(self.seed.tmp.name)/'copied-v4';shutil.copytree(self.target,other)
        self.rejected(503,lambda:PermanentStorage(other))
        path=self.target/MARKER;original=path.read_bytes();marker=json.loads(original)
        marker['namingVersion']=2;path.write_text(json.dumps(marker),'utf-8')
        self.rejected(503,lambda:PermanentStorage(self.target))
        path.write_bytes(original)
        missing=service.path.with_name('saved.sqlite3');service.path.rename(missing)
        self.rejected(503,lambda:PermanentStorage(self.target));self.assertFalse(service.path.exists())
        missing.rename(service.path)

    def test_withdrawn_deletion_schema_rolls_back_transaction_and_old_backup_paths_stay_closed(self):
        service,_=self.opened();before=self.seed.database(service.path)
        def modify():
            with service.connection() as db:
                db.execute("UPDATE artifacts SET title='Must roll back'")
                db.execute("DELETE FROM metadata WHERE key='permanentDeletionVersion'")
        self.rejected(503,modify)
        self.assertEqual(before,self.seed.database(service.path))
        destination=self.target.parent/'unsupported-expanded-backup.zip'
        self.rejected(409,lambda:create_expanded_archive(service,destination))
        self.assertFalse(destination.exists())
        self.rejected(503,lambda:ExpandedApplication(service))
        self.rejected(503,lambda:service.backup(self.target.parent/'raw.sqlite3'))

    def test_copy_failure_stays_incomplete_and_neither_old_service_can_open(self):
        import expanded_candidate as copies
        original=copies._copy_new
        def fail(source,target):
            if source.name.endswith('.pdf'):raise OSError('Synthetic copy failure')
            return original(source,target)
        with patch.object(copies,'_copy_new',side_effect=fail):
            with self.assertRaises(OSError):prepare_permanent_storage(self.seed.archive,self.target)
        self.assertFalse((self.target/MARKER).exists())
        self.rejected(503,lambda:PermanentStorage(self.target));self.rejected(503,lambda:TeamService(self.target))
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())

    def test_unsupported_history_and_existing_target_are_not_rewritten(self):
        self.target.mkdir();sentinel=self.target/'original.txt';sentinel.write_text('Keep','utf-8')
        self.rejected(409,lambda:prepare_permanent_storage(self.seed.archive,self.target))
        self.assertEqual('Keep',sentinel.read_text('utf-8'))
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())
