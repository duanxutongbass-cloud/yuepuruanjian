from contextlib import closing
import hashlib
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import uuid
from zipfile import ZipFile,ZIP_DEFLATED

from backup_archive import validate_archive
from expanded_backup import validate_expanded_archive
from expanded_candidate import ExpandedCandidateService,IDENTITY,ROOT
from number_inventory import inventory
from permanent_storage import PermanentStorage,prepare_permanent_storage,MARKER
import permanent_backup as backups
from service import TeamService
from test_accounts import AccountFixture,PASSWORD
import test_expanded_candidate as storage_tests


class PermanentArchiveTest(unittest.TestCase):
    rejected=AccountFixture.rejected
    def setUp(self):
        self.seed=storage_tests.ExpandedStorageTest();self.seed.setUp();self.addCleanup(self.seed.tearDown)
        self.target=Path(self.seed.tmp.name)/'permanent-archive-source'
        prepare_permanent_storage(self.seed.archive,self.target)
        self.service=PermanentStorage(self.target,lambda:self.seed.now[0])
        self.archive=Path(self.seed.tmp.name)/'permanent-full.zip'

    def create(self,path=None):
        return backups.create_permanent_archive(self.service,path or self.archive)

    def rewrite(self,change,name):
        target=self.archive.with_name(name)
        with ZipFile(self.archive) as source:entries={n:source.read(n) for n in source.namelist()}
        manifest=json.loads(entries['manifest.json']);change(manifest)
        entries['manifest.json']=json.dumps(manifest).encode()
        with ZipFile(target,'x',ZIP_DEFLATED) as bundle:
            for n,data in entries.items():bundle.writestr(n,data)
        return target

    def test_round_trip_preserves_business_rows_and_retired_numbers_but_rebinds_storage_only(self):
        retired=str(uuid.uuid4())
        with self.service.connection() as db:
            db.execute('INSERT INTO artifact_tombstones(artifact,number,deleted,request) VALUES(?,?,?,?)',
                (retired,'0201999',int(self.seed.now[0]),str(uuid.uuid4())))
        before=self.seed.database(self.service.path);self.create();original=self.archive.read_bytes()
        target=self.archive.parent/'restored-permanent'
        proof=backups.restore_permanent_archive(self.archive,target)
        restored=PermanentStorage(target,lambda:self.seed.now[0]);after=self.seed.database(restored.path)
        for table,rows in before.items():
            if table=='metadata':
                self.assertEqual(sorted(r for r in rows if r[0] not in (IDENTITY,ROOT)),
                    sorted(r for r in after[table] if r[0] not in (IDENTITY,ROOT)))
            else:self.assertEqual(rows,after[table],table)
        self.assertNotEqual(proof['storageId'],proof['previousStorageId'])
        self.assertFalse(proof['permanentDeletionReady'])
        self.assertEqual(original,self.archive.read_bytes());self.assertEqual(before,self.seed.database(self.service.path))
        self.assertEqual(self.seed.pdf,(target/'artifacts'/(self.seed.artifact+'.pdf')).read_bytes())
        self.assertFalse(os.path.samefile(target/'artifacts'/(self.seed.artifact+'.pdf'),self.service.directory/'artifacts'/(self.seed.artifact+'.pdf')))
        with restored.connection() as db:self.assertEqual(['001','999'],inventory(db,self.seed.now[0])['occupiedSequences'])
        self.rejected(503,lambda:TeamService(target));self.rejected(503,lambda:ExpandedCandidateService(target))

    def test_committed_wal_is_included_sessions_and_invitations_are_not_restored(self):
        token=self.service.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,
            deviceName='Synthetic live source'),'127.0.0.1')['token']
        self.service.dispatch('POST','/v1/admin/invitations',{},token,self.seed.device,'127.0.0.1')
        reader=sqlite3.connect(self.service.path)
        self.addCleanup(reader.close);reader.execute('BEGIN');reader.execute('SELECT * FROM accounts').fetchall()
        with self.service.connection() as db:
            db.execute("UPDATE accounts SET name='Committed WAL name' WHERE id=?",(self.seed.admin_id,))
        self.create();destination=self.archive.parent/'wal-restored'
        backups.restore_permanent_archive(self.archive,destination)
        restored=PermanentStorage(destination)
        with restored.connection() as db:
            self.assertEqual('Committed WAL name',db.execute('SELECT name FROM accounts WHERE id=?',(self.seed.admin_id,)).fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM sessions').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM invitations').fetchone()[0])
        with self.service.connection() as db:
            self.assertEqual(1,db.execute('SELECT count(*) FROM sessions').fetchone()[0])
            self.assertEqual(1,db.execute('SELECT count(*) FROM invitations').fetchone()[0])

    def test_unfinished_cleanup_or_journal_cannot_publish_a_complete_archive(self):
        with self.service.connection() as db:
            db.execute('INSERT INTO permanent_deletion_jobs VALUES(?,?,?,?,?)',(self.seed.admin_id,str(uuid.uuid4()),'{}','{}',1))
        self.rejected(409,self.create);self.assertFalse(self.archive.exists())
        with self.service.connection() as db:db.execute('DELETE FROM permanent_deletion_jobs')
        journal=self.service.directory/'permanent-journal';journal.mkdir();(journal/'pending.json').write_text('{}','utf-8')
        self.rejected(409,self.create);self.assertFalse(self.archive.exists())

    def test_retired_ink_and_old_receipt_payloads_are_not_hidden_in_full_backup(self):
        retired,request=str(uuid.uuid4()),str(uuid.uuid4())
        with self.service.connection() as db:
            db.execute('INSERT INTO artifact_tombstones(artifact,number,deleted,request) VALUES(?,?,?,?)',
                (retired,'0201999',int(self.seed.now[0]),str(uuid.uuid4())))
            db.execute('INSERT INTO annotation_history(artifact,page,owner,kind,revision,payload,actor,at) VALUES(?,?,?,?,?,?,?,?)',
                (retired,0,self.seed.admin_id,'personal',0,'[]',self.seed.admin_id,int(self.seed.now[0])))
        self.rejected(400,self.create);self.assertFalse(self.archive.exists())
        with self.service.connection() as db:
            db.execute('DELETE FROM annotation_history WHERE artifact=?',(retired,))
            db.execute('INSERT INTO annotation_operations VALUES(?,?,?,?,?)',(self.seed.admin_id,request,'0'*64,'{"marks":[]}',200))
        self.rejected(400,self.create);self.assertFalse(self.archive.exists())
        with self.service.connection() as db:db.execute('UPDATE annotation_operations SET status=410')
        self.rejected(400,self.create);self.assertFalse(self.archive.exists())
        with self.service.connection() as db:
            db.execute('UPDATE annotation_operations SET result=?',(json.dumps(dict(error='annotation_receipt_expired',requestId=request)),))
        self.create();self.assertTrue(self.archive.exists())

    def test_v1_v2_readers_and_wrong_purpose_cannot_restore_v4(self):
        self.create()
        for reader in (validate_archive,validate_expanded_archive):
            with tempfile.TemporaryDirectory(dir=self.archive.parent) as stage:
                self.rejected(400,lambda:reader(self.archive,Path(stage)))
        wrong=self.rewrite(lambda m:m['storage'].update(namingVersion=2),'wrong-purpose.zip')
        target=self.archive.parent/'wrong-restored'
        self.rejected(400,lambda:backups.restore_permanent_archive(wrong,target));self.assertFalse(target.exists())
        self.rejected(409,lambda:self.create(self.archive))

    def test_changed_archive_and_failed_final_copy_never_complete_restoration(self):
        self.create();original=self.archive.read_bytes();validate=backups.validate_permanent_archive
        def changing(archive,stage,**options):
            result=validate(archive,stage,**options)
            with archive.open('ab') as file:file.write(b'changed')
            return result
        target=self.archive.parent/'changed-source'
        with patch.object(backups,'validate_permanent_archive',side_effect=changing):
            self.rejected(409,lambda:backups.restore_permanent_archive(self.archive,target))
        self.assertFalse(target.exists());self.archive.write_bytes(original)
        import expanded_candidate as copies
        copy=copies._copy_new
        def failing(src,dst):
            if src.name.endswith('.pdf'):raise OSError('Synthetic copy failure')
            return copy(src,dst)
        with patch.object(copies,'_copy_new',side_effect=failing):
            with self.assertRaises(OSError):backups.restore_permanent_archive(self.archive,target)
        self.assertFalse((target/MARKER).exists());self.rejected(503,lambda:PermanentStorage(target))
        self.assertEqual(original,self.archive.read_bytes())

    def test_actual_frozen_old_backup_reader_rejects_new_format_and_disguised_database4(self):
        self.create();old=self.archive.parent/'frozen-reader';old.mkdir()
        fixtures=Path(__file__).parent/'fixtures'
        with ZipFile(fixtures/'legacy-account-0.10.17.zip') as bundle:
            for name in ('service.py','security.py'):(old/name).write_bytes(bundle.read(name))
        frozen=(fixtures/'legacy-backup-0.10.17.py.txt').read_bytes()
        self.assertEqual('b7e2cc82d1062331fddf6cd2120423b4028caeb0752400c8dc44329b99c9d5fc',hashlib.sha256(frozen).hexdigest())
        (old/'backup_archive.py').write_bytes(frozen)
        def disguise(m):m['format']='score-team-backup-v1';m.pop('storage')
        disguised=self.rewrite(disguise,'disguised-v1.zip')
        code='''import sys,tempfile
from pathlib import Path
from backup_archive import validate_archive
from service import Rejected
with tempfile.TemporaryDirectory() as stage:
    try:validate_archive(Path(sys.argv[1]),Path(stage))
    except Rejected as error:
        assert (error.status,error.code)==(400,sys.argv[2]),(error.status,error.code)
    else:raise AssertionError('Old reader must reject v4')
'''
        env=os.environ.copy();env['PYTHONDONTWRITEBYTECODE']='1'
        for archive,error in ((self.archive,'backup_invalid_manifest'),(disguised,'backup_invalid_database')):
            result=subprocess.run([sys.executable,'-c',code,str(archive),error],cwd=old,env=env,capture_output=True,text=True,timeout=60)
            self.assertEqual(0,result.returncode,result.stderr)
