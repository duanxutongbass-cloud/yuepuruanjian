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
from zipfile import ZipFile

import backup_archive as legacy
import expanded_backup as backups
import expanded_candidate as candidate
from service import Rejected, TeamService
from test_accounts import AccountFixture, PASSWORD
import test_expanded_candidate as storage_tests


class ExpandedArchiveTest(unittest.TestCase):
    rejected = AccountFixture.rejected

    def setUp(self):
        self.seed = storage_tests.ExpandedStorageTest()
        self.seed.setUp()
        self.addCleanup(self.seed.tearDown)
        self.service = self.seed.opened()
        self.root = self.seed.service.directory
        self.archive = self.root/'expanded-full.zip'

    def create(self):
        backups.create_expanded_archive(self.service,self.archive)
        return self.archive

    def rows(self):
        return self.seed.database(self.service.path)

    def rewrite(self,change,name='changed.zip'):
        target=self.root/name
        with ZipFile(self.archive) as source:
            entries={n:source.read(n) for n in source.namelist()}
        manifest=json.loads(entries['manifest.json'])
        change(entries,manifest)
        entries['manifest.json']=json.dumps(manifest).encode('utf-8')
        with ZipFile(target,'x') as output:
            for entry,data in entries.items(): output.writestr(entry,data)
        return target

    def rejected_restore(self,archive):
        target=self.root/('restore-'+str(uuid.uuid4()))
        original=archive.read_bytes()
        with self.assertRaises(Rejected): backups.restore_expanded_archive(archive,target)
        self.assertFalse(target.exists())
        self.assertEqual(original,archive.read_bytes())

    def test_round_trip_preserves_rows_frozen_programmes_pdf_and_rebinds_only_storage(self):
        before=self.rows(); self.create(); original=self.archive.read_bytes()
        target=self.root/'restored-v3'
        proof=backups.restore_expanded_archive(self.archive,target)
        restored=candidate.ExpandedCandidateService(target,lambda:self.seed.now[0])
        after=self.seed.database(restored.path)
        for name,rows in before.items():
            if name in ('sessions','invitations'):
                self.assertEqual([],after[name])
            elif name=='metadata':
                ignored={candidate.IDENTITY,candidate.ROOT}
                self.assertEqual(sorted(r for r in rows if r[0] not in ignored),
                                 sorted(r for r in after[name] if r[0] not in ignored))
            else: self.assertEqual(rows,after[name],name)
        self.assertNotEqual(proof['previousStorageId'],proof['storageId'])
        self.assertIs(False,proof['expandedPublicationReady'])
        self.assertEqual(3,proof['databaseVersion'])
        self.assertEqual(before,self.rows()); self.assertEqual(original,self.archive.read_bytes())
        pdf=target/'artifacts'/(self.seed.artifact+'.pdf')
        self.assertEqual(self.seed.pdf,pdf.read_bytes())
        self.assertFalse(os.path.samefile(pdf,self.service.directory/'artifacts'/pdf.name))
        login=restored.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,
                                 deviceName='Synthetic restored v3'), '127.0.0.1')
        self.assertEqual(self.seed.admin_id,login['principal']['accountId'])
        self.assertEqual(self.seed.admin['principal']['teamId'],login['principal']['teamId'])
        self.rejected(503,lambda:TeamService(target))

    def test_committed_wal_is_captured_and_live_tokens_invitations_are_not_restored(self):
        current=self.service
        login=current.login(dict(username='director',password=PASSWORD,deviceId=self.seed.device,
                                 deviceName='Synthetic backup login'), '127.0.0.1')
        current.dispatch('POST','/v1/admin/invitations',{},login['token'],self.seed.device,'127.0.0.1')
        with closing(sqlite3.connect(current.path)) as reader:
            reader.execute('BEGIN'); reader.execute('SELECT count(*) FROM accounts').fetchone()
            with current.connection() as db:
                db.execute("INSERT INTO audit(at,event) VALUES(?,?)",(self.seed.now[0],'synthetic committed WAL'))
            self.assertTrue(current.path.with_name('team.sqlite3-wal').exists())
            before=self.rows(); self.create()
            self.assertEqual(before,self.rows())
        target=self.root/'wal-restored'; backups.restore_expanded_archive(self.archive,target)
        restored=candidate.ExpandedCandidateService(target,lambda:self.seed.now[0])
        with restored.connection() as db:
            self.assertEqual(1,db.execute("SELECT count(*) FROM audit WHERE event='synthetic committed WAL'").fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM sessions').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM invitations').fetchone()[0])
        self.rejected(401,lambda:restored.dispatch('GET','/v1/me',None,login['token'],self.seed.device,'127.0.0.1'))

    def test_archive_preserves_full_expanded_and_unusual_raw_numbers_without_normalizing(self):
        values=('0101001','01-01-1000','01-01-9223372036854775807','unresolved-original-label')
        for index,value in enumerate(values):
            with self.subTest(value=value):
                with self.service.connection() as db:
                    db.execute('UPDATE artifact_metadata SET number=?',(value,))
                archive=self.root/('number-'+str(index)+'.zip')
                backups.create_expanded_archive(self.service,archive)
                target=self.root/('number-restore-'+str(index)); backups.restore_expanded_archive(archive,target)
                with closing(sqlite3.connect(target/'team.sqlite3')) as db:
                    self.assertEqual(value,db.execute('SELECT number FROM artifact_metadata').fetchone()[0])

    def test_v1_writer_reader_and_existing_destinations_remain_closed(self):
        self.rejected(409,lambda:backups.create_expanded_archive(self.seed.service,self.archive))
        self.rejected(503,lambda:legacy.create_archive(self.service,self.archive))
        self.assertFalse(self.archive.exists())
        self.create(); original=self.archive.read_bytes()
        self.rejected(409,lambda:backups.create_expanded_archive(self.service,self.archive))
        self.rejected(409,lambda:backups.restore_expanded_archive(self.archive,self.service.directory))
        target=self.root/'old-restore'
        with self.assertRaises(Rejected): legacy.restore_archive(self.archive,target)
        self.assertFalse(target.exists()); self.assertEqual(original,self.archive.read_bytes())
        self.rejected_restore(self.seed.archive)

    def test_frozen_old_backup_reader_rejects_new_format_and_disguised_v3_database(self):
        self.create()
        def disguise(entries,manifest):
            manifest['format']='score-team-backup-v1'; del manifest['storage']
        disguised=self.rewrite(disguise,'disguised-v1.zip')
        folder=self.root/'old-backup-reader'; folder.mkdir()
        fixtures=Path(__file__).resolve().parent/'fixtures'
        with ZipFile(fixtures/'legacy-account-0.10.17.zip') as old:
            for name in ('service.py','security.py'): (folder/name).write_bytes(old.read(name))
        text=(fixtures/'legacy-backup-0.10.17.py.txt').read_bytes()
        self.assertEqual('b7e2cc82d1062331fddf6cd2120423b4028caeb0752400c8dc44329b99c9d5fc',hashlib.sha256(text).hexdigest())
        (folder/'backup_archive.py').write_bytes(text)
        hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.iterdir()}
        code='''import sys
from backup_archive import restore_archive
from service import Rejected
try:
    restore_archive(sys.argv[1],sys.argv[2])
except Rejected as error:
    assert (error.status,error.code)==(400,sys.argv[3])
    print('legacy restore refused')
else:
    raise AssertionError('old reader must reject')
'''
        for index,archive in enumerate((self.archive,disguised)):
            target=self.root/('frozen-restore-'+str(index)); original=archive.read_bytes()
            env=os.environ.copy(); env['PYTHONDONTWRITEBYTECODE']='1'
            expected='backup_invalid_manifest' if index==0 else 'backup_invalid_database'
            result=subprocess.run([sys.executable,'-c',code,str(archive),str(target),expected],cwd=folder,
                                  env=env,capture_output=True,text=True,timeout=60)
            self.assertEqual(0,result.returncode,result.stderr)
            self.assertEqual('legacy restore refused',result.stdout.strip())
            self.assertFalse(target.exists()); self.assertEqual(original,archive.read_bytes())
        self.assertEqual(hashes,{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in folder.iterdir()})

    def test_corrupt_or_missing_pdf_blocks_new_archive_without_touching_original(self):
        pdf=self.service.directory/'artifacts'/(self.seed.artifact+'.pdf')
        for content in (b'wrong',None):
            with self.subTest(content=content):
                if content is None: pdf.unlink()
                else: pdf.write_bytes(content)
                before=self.rows()
                self.rejected(409,lambda:self.create())
                self.assertFalse(self.archive.exists()); self.assertEqual(before,self.rows())
        self.assertEqual(self.seed.original,self.seed.archive.read_bytes())

    def test_malformed_storage_descriptors_and_identity_mismatch_cannot_create_destination(self):
        self.create()
        with ZipFile(self.archive) as source: marker=json.loads(source.read('manifest.json'))['storage']
        variants=[None,{},dict(marker,extra=True),dict(marker,databaseVersion=True),dict(marker,namingVersion=1),
                  dict(marker,storageId=str(uuid.uuid4())),dict(marker,directory='relative-root'),
                  dict(marker,directory='/other/original/root')]
        for index,value in enumerate(variants):
            def change(entries,manifest): manifest['storage']=value
            self.rejected_restore(self.rewrite(change,'storage-'+str(index)+'.zip'))

    def test_corrupt_missing_extra_or_duplicate_entries_are_rejected(self):
        self.create()
        def corruption(entries,manifest):
            name=next(n for n in entries if n.endswith('.pdf')); entries[name]=b'x'*len(entries[name])
        def missing(entries,manifest): del entries[next(n for n in entries if n.endswith('.pdf'))]
        def traversal(entries,manifest):
            entries['../outside.pdf']=b'x'; manifest['files']['../outside.pdf']=dict(size=1,sha256=hashlib.sha256(b'x').hexdigest())
        def catalog(entries,manifest):
            name='artifacts/'+str(uuid.uuid4())+'.pdf'; entries[name]=b'x'
            manifest['files'][name]=dict(size=1,sha256=hashlib.sha256(b'x').hexdigest())
        for index,fn in enumerate((corruption,missing,traversal,catalog)):
            self.rejected_restore(self.rewrite(fn,'bad-'+str(index)+'.zip'))
        duplicate=self.root/'duplicate-json.zip'
        with ZipFile(self.archive) as source,ZipFile(duplicate,'x') as dest:
            for name in source.namelist():
                data=source.read(name)
                if name=='manifest.json': data=b'{"format":"a",'+data[1:]
                dest.writestr(name,data)
        self.rejected_restore(duplicate)
        repeated=self.root/'duplicate-entry.zip'
        with ZipFile(self.archive) as source,ZipFile(repeated,'x') as dest:
            for name in source.namelist(): dest.writestr(name,source.read(name))
            with self.assertWarns(UserWarning): dest.writestr('manifest.json',source.read('manifest.json'))
        self.rejected_restore(repeated)
        self.assertFalse((self.root/'outside.pdf').exists())

    def test_foreign_platform_source_path_is_evidence_not_an_accessed_path(self):
        self.create()
        def foreign(entries,manifest):
            database=self.root/'foreign-db.sqlite3'; database.write_bytes(entries['team.sqlite3'])
            with closing(sqlite3.connect(database)) as db:
                db.execute('UPDATE metadata SET value=? WHERE key=?',('/volume1/private/offline-candidate',candidate.ROOT));db.commit()
            entries['team.sqlite3']=database.read_bytes()
            manifest['files']['team.sqlite3']=dict(size=len(entries['team.sqlite3']),sha256=hashlib.sha256(entries['team.sqlite3']).hexdigest())
            manifest['storage']['directory']='/volume1/private/offline-candidate'
        archive=self.rewrite(foreign,'foreign.zip'); target=self.root/'foreign-restored'
        backups.restore_expanded_archive(archive,target)
        restored=candidate.ExpandedCandidateService(target)
        with restored.connection() as db:
            self.assertEqual(str(target.resolve()),db.execute('SELECT value FROM metadata WHERE key=?',(candidate.ROOT,)).fetchone()[0])

    def test_archive_changed_during_validation_cannot_publish_restored_candidate(self):
        self.create(); original=self.archive.read_bytes(); validate=backups.validate_expanded_archive
        def changed(archive,stage):
            result=validate(archive,stage)
            with archive.open('ab') as output: output.write(b'synthetic change')
            return result
        target=self.root/'changed-source'
        with patch.object(backups,'validate_expanded_archive',changed):
            self.rejected(409,lambda:backups.restore_expanded_archive(self.archive,target))
        self.assertFalse(target.exists()); self.archive.write_bytes(original)

    def test_final_restore_copy_failure_or_corruption_keeps_target_closed(self):
        self.create(); original=candidate._copy_new
        for phase in ('failure','corruption'):
            target=self.root/('partial-'+phase)
            def broken(source,destination):
                if source.suffix=='.pdf' and phase=='failure': raise OSError('synthetic restore failure')
                original(source,destination)
                if source.suffix=='.pdf' and phase=='corruption': destination.write_bytes(b'bad copy')
            with patch.object(candidate,'_copy_new',broken):
                with self.assertRaises((OSError,Rejected)): backups.restore_expanded_archive(self.archive,target)
            self.assertFalse((target/candidate.MARKER).exists())
            self.rejected(503,lambda:candidate.ExpandedCandidateService(target))
            self.rejected(503,lambda:TeamService(target))

    def test_withdrawn_candidate_after_verification_cannot_publish_archive(self):
        marker=self.service.directory/candidate.MARKER; original=marker.read_bytes()
        validate=backups.validate_expanded_archive
        def withdrawn(archive,stage):
            result=validate(archive,stage); marker.write_text('{}'); return result
        try:
            with patch.object(backups,'validate_expanded_archive',withdrawn): self.rejected(503,lambda:self.create())
        finally: marker.write_bytes(original)
        self.assertFalse(self.archive.exists())

    def test_restoration_cannot_reuse_previous_storage_identity(self):
        self.create()
        with ZipFile(self.archive) as source: identity=json.loads(source.read('manifest.json'))['storage']['storageId']
        target=self.root/'identity-reuse'
        with patch.object(backups.uuid,'uuid4',return_value=uuid.UUID(identity)):
            self.rejected(409,lambda:backups.restore_expanded_archive(self.archive,target))
        self.assertFalse(target.exists())
