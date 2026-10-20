import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import uuid
from contextlib import closing
from zipfile import ZipFile

import expanded_candidate as candidate
from backup_archive import create_archive
from pypdf import PdfWriter
from server import Application
from service import Rejected, TeamService
from test_accounts import AccountFixture, PASSWORD


class ExpandedStorageTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.app = Application(self.service)
        output = io.BytesIO()
        pdf = PdfWriter(); pdf.add_blank_page(595, 842); pdf.write(output)
        self.pdf = output.getvalue()
        self.number = '0101001'
        spec = dict(requestId=str(uuid.uuid4()), title='Synthetic score', partName='01 Violin',
                    scoreNumber=self.number, category='01 Style', sha256=hashlib.sha256(self.pdf).hexdigest(),
                    size=len(self.pdf), independentSource=True)
        pub = self.app.publisher
        self.artifact = pub.prepare(spec, self.admin['token'], self.device)['uploadId']
        pub.upload(self.artifact, io.BytesIO(self.pdf), len(self.pdf), self.admin['token'], self.device)
        pub.publish(dict(requestId=str(uuid.uuid4()), assignments=[dict(artifactId=self.artifact,
                    accountIds=[self.admin_id])]), self.admin['token'], self.device)
        self.program_id = str(uuid.uuid4())
        self.app.programs.dispatch('POST', '/v1/admin/programs', dict(id=self.program_id, title='Synthetic programme',
            pieceKeys=['sequence:001'], accountIds=[self.admin_id], archived=False, expectedRevision=0),
            self.admin['token'], self.device)
        self.app.program_freezes.dispatch('POST', '/v1/admin/program-freezes', dict(programId=self.program_id,
            requestId=str(uuid.uuid4()), expectedRevision=1), self.admin['token'], self.device)
        self.archive = Path(self.tmp.name) / 'original-full.zip'
        create_archive(self.service, self.archive)
        self.original = self.archive.read_bytes()
        self.target = Path(self.tmp.name) / 'expanded'

    def database(self, path=None):
        db = sqlite3.connect(path or self.service.path)
        try:
            return {r[0]:db.execute('SELECT * FROM "'+r[0]+'" ORDER BY rowid').fetchall()
                    for r in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")}
        finally:
            db.close()

    def opened(self):
        candidate.prepare_candidate(self.archive, self.target)
        return candidate.ExpandedCandidateService(self.target, lambda:self.now[0])

    def test_complete_snapshot_keeps_identity_rows_programme_pdf_and_source_archive(self):
        before = self.database()
        proof = candidate.prepare_candidate(self.archive, self.target)
        current = candidate.ExpandedCandidateService(self.target, lambda:self.now[0])
        after = self.database(current.path)
        for table, rows in before.items():
            if table in ('sessions', 'invitations'):
                self.assertEqual([], after[table])
            elif table == 'metadata':
                extra = {candidate.IDENTITY, candidate.ROOT, candidate.NAMING}
                self.assertEqual(sorted(rows), sorted(r for r in after[table] if r[0] not in extra))
            else:
                self.assertEqual(rows, after[table], table)
        self.assertEqual(before, self.database())
        self.assertEqual(self.original, self.archive.read_bytes())
        copied = self.target / 'artifacts' / (self.artifact+'.pdf')
        source = self.service.directory / 'artifacts' / (self.artifact+'.pdf')
        self.assertEqual(self.pdf, copied.read_bytes())
        self.assertFalse(os.path.samefile(source, copied))
        self.assertEqual((3,2,False,1), (proof['databaseVersion'], proof['namingVersion'],
                                      proof['expandedPublicationReady'], proof['artifacts']))
        self.assertEqual(hashlib.sha256(self.original).hexdigest(), proof['sourceSha256'])
        with current.connection() as db:
            self.assertEqual(3, db.execute('PRAGMA user_version').fetchone()[0])
            self.assertEqual([], list(db.execute('PRAGMA foreign_key_check')))
        login = current.login(dict(username='director', password=PASSWORD, deviceId=self.device,
                                   deviceName='Synthetic restored device'), '127.0.0.1')
        self.assertEqual(self.admin_id, login['principal']['accountId'])
        self.assertEqual(self.admin['principal']['teamId'], login['principal']['teamId'])

    def test_normal_service_rejects_candidate_v3_without_changing_business_rows(self):
        self.opened()
        before = self.database(self.target/'team.sqlite3')
        self.rejected(503, lambda:TeamService(self.target))
        self.assertEqual(before, self.database(self.target/'team.sqlite3'))

    def test_frozen_0118_account_service_in_separate_process_rejects_v3(self):
        self.opened()
        frozen = Path(__file__).resolve().parent/'fixtures/legacy-account-0.10.17.zip'
        self.assertEqual('42290f23f939f3009e9d8080a83ba19c56bb86d054aa7b39ced83abe28820af1',
                         hashlib.sha256(frozen.read_bytes()).hexdigest())
        old = Path(self.tmp.name)/'old-service'
        old.mkdir()
        with ZipFile(frozen) as bundle:
            for name in ('service.py','security.py'):
                (old/name).write_bytes(bundle.read(name))
        snapshots = {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in old.iterdir()}
        before = self.database(self.target/'team.sqlite3')
        code = '''import sys
from service import TeamService, Rejected
try:
    TeamService(sys.argv[1])
except Rejected as error:
    assert (error.status,error.code)==(503,'unsupported_database')
    print('frozen service rejected v3')
else:
    raise AssertionError('legacy service must refuse candidate')
'''
        env = os.environ.copy(); env['PYTHONDONTWRITEBYTECODE']='1'
        result = subprocess.run([sys.executable,'-c',code,str(self.target)], cwd=old, env=env,
                                capture_output=True, text=True, timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('frozen service rejected v3', result.stdout.strip())
        self.assertEqual(snapshots, {p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in old.iterdir()})
        self.assertEqual(before, self.database(self.target/'team.sqlite3'))

    def test_only_new_destination_and_v2_full_archive_are_accepted(self):
        self.target.mkdir(); sentinel=self.target/'keep.txt'; sentinel.write_text('keep')
        self.rejected(409, lambda:candidate.prepare_candidate(self.archive,self.target))
        self.assertEqual('keep',sentinel.read_text())
        fresh = self.target/'new'
        with closing(sqlite3.connect(self.service.path)) as db: db.execute('PRAGMA user_version=1')
        older = self.service.directory/'older.zip'; create_archive(self.service,older)
        original = older.read_bytes()
        self.rejected(409, lambda:candidate.prepare_candidate(older,fresh))
        self.assertFalse(fresh.exists())
        self.assertEqual(original,older.read_bytes())
        self.assertEqual(self.original,self.archive.read_bytes())

    def test_inventory_rejects_unsupported_metadata_queue_and_frozen_numbers_without_rewrite(self):
        for target in ('metadata','queue','frozen'):
            with self.subTest(target=target):
                with self.service.connection() as db:
                    if target == 'metadata':
                        db.execute('UPDATE artifact_metadata SET number=?', ('01-01-1000',))
                    elif target == 'queue':
                        db.execute('UPDATE artifact_metadata SET number=?', (self.number,))
                        spec=json.loads(db.execute('SELECT spec FROM uploads').fetchone()[0])
                        spec['scoreNumber']='01_01_1000'
                        db.execute('UPDATE uploads SET spec=?', (json.dumps(spec),))
                    else:
                        spec=json.loads(db.execute('SELECT spec FROM uploads').fetchone()[0])
                        spec['scoreNumber']=self.number
                        db.execute('UPDATE uploads SET spec=?', (json.dumps(spec),))
                        manifest=json.loads(db.execute('SELECT manifest FROM program_freezes').fetchone()[0])
                        manifest['artifacts'][0]['scoreNumber']='01011000'
                        db.execute('UPDATE program_freezes SET manifest=?', (json.dumps(manifest),))
                archive=self.service.directory/(target+'.zip'); create_archive(self.service,archive)
                original=archive.read_bytes(); before=self.database()
                with self.assertRaises(Rejected): candidate.prepare_candidate(archive,self.target)
                self.assertFalse(self.target.exists())
                self.assertEqual(original,archive.read_bytes())
                self.assertEqual(before,self.database())
                self.assertEqual(self.pdf,(self.service.directory/'artifacts'/(self.artifact+'.pdf')).read_bytes())

    def test_reserved_candidate_metadata_does_not_overwrite_old_value(self):
        with self.service.connection() as db:
            db.execute('INSERT INTO metadata VALUES(?,?)',(candidate.IDENTITY,'retain-existing-value'))
        archive=self.service.directory/'reserved.zip'; create_archive(self.service,archive)
        before=self.database(); original=archive.read_bytes()
        self.rejected(409,lambda:candidate.prepare_candidate(archive,self.target))
        self.assertEqual(before,self.database()); self.assertEqual(original,archive.read_bytes())
        self.assertFalse(self.target.exists())

    def test_invalid_or_rebound_completion_markers_cannot_open_or_write(self):
        current=self.opened(); marker=self.target/candidate.MARKER
        original=marker.read_bytes(); parsed=json.loads(original)
        before=self.database(current.path)
        variants=[dict(parsed,databaseVersion=True),dict(parsed,namingVersion=True),dict(parsed,extra=True),
                  dict(parsed,storageId=str(uuid.uuid4())),dict(parsed,directory=str(self.target/'other'))]
        for invalid in variants:
            with self.subTest(invalid=invalid):
                marker.write_text(json.dumps(invalid),encoding='utf-8')
                with self.assertRaises(Rejected):
                    with current.connection() as db: db.execute("UPDATE accounts SET name='wrong'")
                with self.assertRaises(Rejected): candidate.ExpandedCandidateService(self.target)
                self.assertEqual(before,self.database(current.path))
        for raw in (b'{',b'{"format":"a","format":"b"}',b'x'*4097):
            marker.write_bytes(raw)
            with self.assertRaises(Rejected): candidate.ExpandedCandidateService(self.target)
        marker.write_bytes(original)
        with current.connection() as db: self.assertEqual(self.admin_id,db.execute('SELECT id FROM accounts').fetchone()[0])

    def test_hardlinked_database_or_marker_and_missing_database_are_closed(self):
        current=self.opened()
        for source in (current.path,self.target/candidate.MARKER):
            alias=self.service.directory/('alias-'+source.name)
            os.link(source,alias)
            try:
                with self.assertRaises(Rejected):
                    with current.connection(): self.fail('must not enter')
            finally: alias.unlink()
        moved=current.path.with_suffix('.held'); current.path.rename(moved)
        try:
            with self.assertRaises(Rejected):
                with current.connection(): self.fail('must not create missing database')
            self.assertFalse(current.path.exists())
        finally: moved.rename(current.path)

    def test_guard_rechecked_at_commit_rolls_back_business_and_version_changes(self):
        current=self.opened(); marker=self.target/candidate.MARKER
        original=marker.read_bytes(); before=self.database(current.path)
        try:
            self.rejected(503,lambda:self.changed_marker_transaction(current,marker))
        finally: marker.write_bytes(original)
        self.assertEqual(before,self.database(current.path))
        for sql in ('PRAGMA user_version=2', "UPDATE metadata SET value='wrong' WHERE key='numberingCandidateId'"):
            with self.subTest(sql=sql):
                with self.assertRaises(Rejected):
                    with current.connection() as db:
                        db.execute("UPDATE accounts SET name='wrong'"); db.execute(sql)
                self.assertEqual(before,self.database(current.path))

    def changed_marker_transaction(self,current,marker):
        with current.connection() as db:
            db.execute("UPDATE accounts SET name='wrong'")
            marker.write_text('{}')

    def test_copying_directory_elsewhere_cannot_rebind_candidate_identity(self):
        self.opened()
        other=self.service.directory/'copied'; shutil.copytree(self.target,other)
        before=self.database(other/'team.sqlite3')
        self.rejected(503,lambda:candidate.ExpandedCandidateService(other))
        marker=other/candidate.MARKER
        value=json.loads(marker.read_bytes()); value['directory']=str(other.resolve())
        marker.write_text(json.dumps(value),encoding='utf-8')
        self.rejected(503,lambda:candidate.ExpandedCandidateService(other))
        self.assertEqual(before,self.database(other/'team.sqlite3'))

    def test_partial_copy_cannot_complete_or_be_opened_by_old_service(self):
        original=candidate._copy_new
        def fail_pdf(source,target):
            if source.suffix=='.pdf': raise OSError('synthetic copy failure')
            return original(source,target)
        candidate._copy_new=fail_pdf
        try:
            with self.assertRaises(OSError): candidate.prepare_candidate(self.archive,self.target)
        finally: candidate._copy_new=original
        self.assertTrue((self.target/'team.sqlite3').is_file())
        self.assertFalse((self.target/candidate.MARKER).exists())
        self.rejected(503,lambda:candidate.ExpandedCandidateService(self.target))
        self.rejected(503,lambda:TeamService(self.target))
        self.assertEqual(self.original,self.archive.read_bytes())

    def test_source_archive_changed_during_restore_never_publishes_candidate(self):
        original=candidate.restore_archive
        def changed(archive,destination):
            result=original(archive,destination)
            with archive.open('ab') as content: content.write(b'synthetic change')
            return result
        candidate.restore_archive=changed
        try:
            self.rejected(409,lambda:candidate.prepare_candidate(self.archive,self.target))
        finally:
            candidate.restore_archive=original
            self.archive.write_bytes(self.original)
        self.assertFalse(self.target.exists())

    def test_corrupted_final_pdf_copy_is_detected_before_completion(self):
        original=candidate._copy_new
        def corrupted(source,target):
            original(source,target)
            if source.suffix=='.pdf':
                with target.open('r+b') as output:
                    output.seek(-1,os.SEEK_END); output.write(b'x')
        candidate._copy_new=corrupted
        try:
            self.rejected(409,lambda:candidate.prepare_candidate(self.archive,self.target))
        finally: candidate._copy_new=original
        self.assertFalse((self.target/candidate.MARKER).exists())
        self.rejected(503,lambda:candidate.ExpandedCandidateService(self.target))
        self.assertEqual(self.original,self.archive.read_bytes())

    def test_completion_link_or_unlink_failure_leaves_candidate_closed(self):
        link,unlink=candidate.os.link,Path.unlink
        for phase in ('link','unlink'):
            with self.subTest(phase=phase):
                target=self.service.directory/('marker-'+phase)
                def fail_link(source,destination,*args,**kwargs):
                    if Path(destination).name==candidate.MARKER: raise OSError('synthetic marker link failure')
                    return link(source,destination,*args,**kwargs)
                def fail_unlink(path,*args,**kwargs):
                    if path.name=='.expanded-candidate.pending': raise OSError('synthetic marker unlink failure')
                    return unlink(path,*args,**kwargs)
                try:
                    if phase=='link': candidate.os.link=fail_link
                    else: Path.unlink=fail_unlink
                    with self.assertRaises(OSError): candidate.prepare_candidate(self.archive,target)
                finally:
                    candidate.os.link=link; Path.unlink=unlink
                self.rejected(503,lambda:candidate.ExpandedCandidateService(target))
                self.rejected(503,lambda:TeamService(target))
                self.assertEqual(self.original,self.archive.read_bytes())

    def test_old_archive_writer_is_explicitly_blocked_and_v1_api_still_closed(self):
        current=self.opened(); target=self.service.directory/'must-not-exist.zip'
        self.rejected(503,lambda:create_archive(current,target))
        self.assertFalse(target.exists())
        app=Application(current)
        login=current.login(dict(username='director',password=PASSWORD,deviceId=self.device,
                                 deviceName='Synthetic candidate'), '127.0.0.1')
        from score_numbers import capabilities
        reply=capabilities(current,login['token'],self.device)
        self.assertEqual([1],reply['numbering']['namingVersions'])
        self.assertIs(False,reply['expandedPublicationReady'])
        spec=dict(requestId=str(uuid.uuid4()),title='Another score',partName='01 Violin',scoreNumber='01-01-1000',
                  category='01 Style',sha256=hashlib.sha256(self.pdf).hexdigest(),size=len(self.pdf),independentSource=True)
        self.rejected(426,lambda:app.publisher.prepare(spec,login['token'],self.device))
