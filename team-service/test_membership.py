"""Real SQLite/WSGI/HTTP checks for one existing orchestra, without tenant routing."""
import concurrent.futures
import hashlib
import io
import json
from pathlib import Path
import sqlite3
import threading
import urllib.error
import urllib.request
import uuid
from unittest.mock import patch
from wsgiref.simple_server import make_server, WSGIRequestHandler

from pypdf import PdfWriter
from backup_archive import create_archive, restore_archive
from security import digest
from server import Application
from service import Rejected, TeamService, SESSION_SECONDS, JOIN_CODE_ALPHABET, new_join_code
from test_accounts import AccountFixture, PASSWORD


class MembershipTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.app = Application(self.service, True)

    def signup_body(self, name='new-player'):
        return dict(username=name, password=PASSWORD, displayName='新成员', deviceId=self.device,
                    deviceName='Synthetic tablet', backupConsent=False)

    def signup(self, name='new-player'):
        return self.call('/v1/auth/signup', self.signup_body(name))

    def code(self, revision=0):
        return self.call('/v1/admin/join-code', dict(baseRevision=revision))['code']

    def test_short_code_is_readable_case_insensitive_and_only_creates_pending_request(self):
        code = self.code()
        self.assertEqual(6, len(code))
        self.assertTrue(all(c in JOIN_CODE_ALPHABET for c in code))
        self.assertTrue(any(c.isdigit() for c in code))
        self.assertTrue(any(c.isalpha() for c in code))
        player = self.signup()
        result = self.apply(player, self.application(' ' + code.lower() + ' '))
        self.assertEqual('pending', result['state'])
        with self.service.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM artifact_grants').fetchone()[0])

    def test_existing_long_code_remains_case_sensitive_until_rotated(self):
        legacy = 'LegacyCaseSensitive_Code_For_Test_ABC'
        with self.service.connection() as db:
            db.execute('UPDATE join_code SET hash=?,revision=1 WHERE singleton=1', (digest(legacy),))
        player = self.signup()
        self.rejected(403, lambda: self.apply(player, self.application(legacy.lower())))
        self.assertEqual('pending', self.apply(player, self.application(legacy))['state'])
        new = self.code(1)
        second = self.signup('another-player')
        self.rejected(403, lambda: self.apply(second, self.application(legacy)))
        self.assertEqual('pending', self.apply(second, self.application(new.lower()))['state'])

    def test_short_code_generation_retries_collision_and_excludes_ambiguous_characters(self):
        with patch('service.secrets.choice', side_effect=list('AB2CD3EF4GH5')):
            self.assertEqual('EF4GH5', new_join_code(digest('AB2CD3')))
        self.assertFalse(set('01ILO').intersection(JOIN_CODE_ALPHABET))

    def test_failed_short_code_generation_does_not_change_current_code_or_revision(self):
        first = self.code()
        with patch('service.secrets.choice', return_value='A'):
            self.rejected(503, lambda: self.code(1))
        self.assertEqual(dict(configured=True, revision=1), self.call('/v1/admin/join-code'))
        self.assertEqual('pending', self.apply(self.signup(), self.application(first))['state'])

    def application(self, code, revision=0):
        return dict(requestId=str(uuid.uuid4()), baseRevision=revision, code=code)

    def apply(self, player, body):
        return self.call('/v1/me/membership', body, player)

    def review(self, player, revision=1, approved=True):
        return dict(requestId=str(uuid.uuid4()), baseRevision=revision,
                    accountId=player['principal']['accountId'], approved=approved)

    def wsgi(self, path, login, body=None, method=None, raw=None):
        payload = raw if raw is not None else json.dumps(body).encode() if body is not None else b''
        env = dict(REQUEST_METHOD=method or ('POST' if body is not None else 'GET'), PATH_INFO=path,
                   REMOTE_ADDR='127.0.0.1', CONTENT_TYPE='application/json', CONTENT_LENGTH=str(len(payload)),
                   HTTP_AUTHORIZATION='Bearer '+login['token'], HTTP_X_DEVICE_ID=self.device)
        env.update({'wsgi.url_scheme': 'https', 'wsgi.input': io.BytesIO(payload)})
        status = []
        result = b''.join(self.app(env, lambda s, h: status.append(int(s[:3]))))
        return status[0], json.loads(result)

    def private_routes(self):
        aid = str(uuid.uuid4())
        return [('/v1/catalog', None), ('/v1/artifacts/'+aid, None), ('/v1/annotations', None),
                ('/v1/annotations/'+aid+'/0/personal', {}), ('/v1/annotation-backups', None),
                ('/v1/annotation-backups/'+aid+'/0', None), ('/v1/admin/annotation-backups', {}),
                ('/v1/personal-folders', None), ('/v1/personal-folders', {}),
                ('/v1/program-freezes/'+aid+'/'+aid, None), ('/v1/admin/program-freezes', {}),
                ('/v1/test-operations', {}), ('/v1/admin/test-operations/query', {}),
                ('/v1/admin/ensemble', None), ('/v1/admin/programs', {}),
                ('/v1/admin/catalog-actions', {}), ('/v1/admin/artifacts', None),
                ('/v1/admin/uploads', {}), ('/v1/admin/publications', {}),
                ('/v1/admin/publication-cancellations', {}), ('/v1/admin/members', None),
                ('/v1/admin/join-code', None), ('/v1/admin/join-requests', None)]

    def test_public_signup_does_not_join_and_legacy_invite_still_joins(self):
        player = self.signup()
        self.assertEqual(('member', 'unjoined', False), tuple(player['principal'][k] for k in ('role','membership','backupConsent')))
        self.assertEqual('unjoined', self.call('/v1/me', login=player)['principal']['membership'])
        self.assertEqual(1, len(self.call('/v1/me/devices', login=player)['devices']))
        self.assertEqual([self.admin_id], [m['accountId'] for m in self.call('/v1/admin/members')['members']])
        invited = self.member()
        self.assertEqual('joined', invited['principal']['membership'])
        self.assertEqual('joined', self.admin['principal']['membership'])
        self.rejected(400, lambda: self.call('/v1/auth/register', self.signup_body('no-invite')))

    def test_registration_strict_fields_device_failure_rolls_back_and_duplicate_is_safe(self):
        for field in ('role','memberId','accountId','teamId','membership','invitation'):
            self.rejected(400, lambda: self.call('/v1/auth/signup', dict(self.signup_body(), **{field:'admin'})))
        self.rejected(400, lambda: self.call('/v1/auth/signup', dict(self.signup_body(), deviceId='bad-device')))
        with self.service.connection() as db:
            for table in ('accounts','devices','sessions','account_memberships'):
                self.assertEqual(1, db.execute('SELECT count(*) FROM '+table).fetchone()[0])
        original = self.signup()
        self.rejected(409, lambda: self.signup())
        self.assertEqual(original['principal']['accountId'], self.login('new-player')['principal']['accountId'])

    def test_all_private_routes_deny_unjoined_pending_and_rejected_even_with_forged_grants(self):
        player = self.signup()
        source = Path(self.tmp.name)/'original.pdf'
        writer = PdfWriter(); writer.add_blank_page(595,842); writer.write(source)
        original = hashlib.sha256(source.read_bytes()).hexdigest()
        artifact = self.app.catalog.import_part(source, '001原谱', '小提琴', True)
        with self.service.connection() as db:
            db.execute('UPDATE artifacts SET published=1 WHERE id=?',(artifact,))
            db.execute('INSERT INTO artifact_grants VALUES(?,?)',(player['principal']['accountId'], artifact))
        code = self.code()
        for state in ('unjoined','pending','rejected'):
            self.assertEqual(state, self.call('/v1/me/membership', login=player)['state'])
            for path, body in self.private_routes()+[('/v1/artifacts/'+artifact,None)]:
                self.assertEqual((403, {'error':'membership_required'}), self.wsgi(path,player,body), (state,path))
            # An older APK ignores the new principal field, but the server gate still denies it.
            self.assertEqual(player['principal']['memberId'], self.call('/v1/me',login=player)['principal']['memberId'])
            if state == 'unjoined':
                self.apply(player,self.application(code))
            elif state == 'pending':
                self.call('/v1/admin/join-requests/review',self.review(player,approved=False))
        self.assertEqual(original, hashlib.sha256(source.read_bytes()).hexdigest())

    def test_code_hash_rotation_old_code_invalid_new_code_applies_without_grant(self):
        player = self.signup()
        self.assertEqual(dict(configured=False,revision=0),self.call('/v1/admin/join-code'))
        first = self.code(); second = self.code(1)
        self.rejected(409,lambda:self.code(1))
        self.assertEqual(dict(configured=True,revision=2),self.call('/v1/admin/join-code'))
        self.rejected(403,lambda:self.apply(player,self.application(first)))
        result = self.apply(player,self.application(' '+second+' '))
        self.assertEqual(('pending',1),(result['state'],result['revision']))
        with self.service.connection() as db:
            self.assertEqual(digest(second),db.execute('SELECT hash FROM join_code').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM artifact_grants').fetchone()[0])
        for path in Path(self.tmp.name).glob('team.sqlite3*'):
            for secret in (first,second,player['token'],PASSWORD):
                self.assertNotIn(secret.encode(),path.read_bytes())

    def test_application_and_review_lost_ack_retry_conflict_and_audit_are_durable(self):
        player = self.signup(); application = self.application(self.code())
        pending = self.apply(player,application)
        self.assertEqual(pending,self.apply(player,application))
        self.rejected(409,lambda:self.apply(player,dict(application,code='changed')))
        review = self.review(player)
        accepted = self.call('/v1/admin/join-requests/review',review)
        self.service = TeamService(self.tmp.name,lambda:self.now[0]); self.app=Application(self.service,True)
        self.assertEqual(accepted,self.call('/v1/admin/join-requests/review',review))
        self.rejected(409,lambda:self.call('/v1/admin/join-requests/review',dict(review,approved=False)))
        self.assertEqual(pending,self.apply(player,application))
        self.assertEqual('joined',self.call('/v1/me/membership',login=player)['state'])
        events = self.call('/v1/admin/audit')['events']
        self.assertEqual(1,sum(e['event']=='join_requested' for e in events))
        self.assertEqual(1,sum(e['event']=='join_approved' for e in events))

    def test_pending_review_is_admin_only_and_two_decisions_have_one_winner(self):
        player = self.signup(); invited = self.member()
        self.apply(player,self.application(self.code()))
        self.rejected(403,lambda:self.call('/v1/admin/join-requests/review',self.review(player),invited))
        self.rejected(403,lambda:self.call('/v1/admin/join-code',{},invited))
        request = self.call('/v1/admin/join-requests')
        self.assertEqual(1,request['total']); self.assertEqual(player['principal']['accountId'],request['requests'][0]['accountId'])
        def decide(approved):
            try:
                return self.call('/v1/admin/join-requests/review',self.review(player,approved=approved))['state']
            except Rejected as error:
                return error.status
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results=list(pool.map(decide,[True,False]))
        self.assertEqual(1,results.count(409))
        self.assertEqual(0,self.call('/v1/admin/join-requests')['total'])
        self.rejected(409,lambda:self.call('/v1/admin/join-requests/review',self.review(player)))

    def test_rejected_can_reapply_with_new_revision_without_stale_approval(self):
        player=self.signup(); code=self.code(); self.apply(player,self.application(code))
        self.call('/v1/admin/join-requests/review',self.review(player,approved=False))
        self.rejected(409,lambda:self.apply(player,self.application(code)))
        self.assertEqual(3,self.apply(player,self.application(code,2))['revision'])
        self.rejected(409,lambda:self.call('/v1/admin/join-requests/review',self.review(player)))
        self.assertEqual('joined',self.call('/v1/admin/join-requests/review',self.review(player,3))['state'])

    def test_approval_still_requires_explicit_per_pdf_grants_and_original_is_unchanged(self):
        player=self.signup(); aid=player['principal']['accountId']
        source=Path(self.tmp.name)/'source.pdf'; writer=PdfWriter(); writer.add_blank_page(595,842); writer.write(source)
        before=hashlib.sha256(source.read_bytes()).hexdigest()
        artifact=self.app.catalog.import_part(source,'001曲目','小提琴',True)
        def grant():
            return self.app.catalog.dispatch('POST','/v1/admin/grants',dict(accountId=aid,artifactId=artifact,allowed=True),self.admin['token'],self.device)
        self.rejected(409,grant)
        self.apply(player,self.application(self.code())); self.call('/v1/admin/join-requests/review',self.review(player))
        self.assertEqual('joined',self.call('/v1/me',login=player)['principal']['membership'])
        self.assertEqual([],self.wsgi('/v1/catalog',player)[1]['artifacts'])
        grant()
        self.app.catalog.dispatch('POST','/v1/admin/artifacts/publish',dict(artifactId=artifact,published=True),self.admin['token'],self.device)
        self.assertEqual([artifact],[a['artifactId'] for a in self.wsgi('/v1/catalog',player)[1]['artifacts']])
        self.assertEqual(before,hashlib.sha256(source.read_bytes()).hexdigest())

    def test_pending_device_expiry_password_logout_and_disablement_still_apply(self):
        player=self.signup(); aid=player['principal']['accountId']
        self.call('/v1/me/backup-consent',dict(accepted=True),player)
        self.assertTrue(self.call('/v1/me',login=player)['principal']['backupConsent'])
        self.call('/v1/me/devices/revoke',dict(deviceId=self.device),player)
        self.rejected(401,lambda:self.call('/v1/me/membership',login=player))
        self.rejected(403,lambda:self.login('new-player'))
        self.call('/v1/admin/devices/restore',dict(accountId=aid,deviceId=self.device))
        player=self.login('new-player')
        self.call('/v1/auth/password',dict(currentPassword=PASSWORD,newPassword=PASSWORD+' changed'),player)
        self.rejected(401,lambda:self.call('/v1/me',login=player))
        player=self.login('new-player',password=PASSWORD+' changed')
        self.call('/v1/admin/members/status',dict(accountId=aid,active=False))
        self.rejected(401,lambda:self.call('/v1/me',login=player))
        self.call('/v1/admin/members/status',dict(accountId=aid,active=True))
        player=self.login('new-player',password=PASSWORD+' changed')
        self.call('/v1/auth/logout',{},player)
        self.rejected(401,lambda:self.call('/v1/me',login=player))
        player=self.login('new-player',password=PASSWORD+' changed')
        self.now[0]+=SESSION_SECONDS
        self.rejected(401,lambda:self.call('/v1/me/membership',login=player))

    def test_signup_and_wrong_code_throttles_survive_restart(self):
        player=self.signup(); self.code()
        for _ in range(10):
            self.rejected(403,lambda:self.apply(player,self.application('incorrect')))
        self.service=TeamService(self.tmp.name,lambda:self.now[0])
        self.rejected(429,lambda:self.apply(player,self.application('incorrect')))
        self.now[0]+=901
        self.rejected(403,lambda:self.apply(player,self.application('incorrect')))
        # Registration and login share the existing persistent per-name limit.
        for _ in range(10):
            self.rejected(409,lambda:self.signup())
        self.rejected(429,lambda:self.signup())

    def test_legacy_database_migration_preserves_accounts_devices_grants_and_files(self):
        invited=self.member()
        original=Path(self.tmp.name)/'untouched-original.pdf'
        writer=PdfWriter();writer.add_blank_page(595,842);writer.write(original)
        artifact=self.app.catalog.import_part(original,'001 原谱','小提琴',True)
        self.app.catalog.dispatch('POST','/v1/admin/grants',dict(accountId=invited['principal']['accountId'],artifactId=artifact,allowed=True),self.admin['token'],self.device)
        sha=hashlib.sha256(original.read_bytes()).hexdigest()
        with self.service.connection() as db:
            before={t:[tuple(r) for r in db.execute('SELECT * FROM '+t)] for t in
                    ('accounts','devices','sessions','metadata','artifacts','artifact_grants','artifact_metadata')}
            db.execute('DROP TABLE membership_operations'); db.execute('DROP TABLE join_code'); db.execute('DROP TABLE account_memberships')
            db.execute('PRAGMA user_version=1')
        self.service=TeamService(self.tmp.name,lambda:self.now[0])
        with self.service.connection() as db:
            for table,rows in before.items():
                self.assertEqual(rows,[tuple(r) for r in db.execute('SELECT * FROM '+table)])
            self.assertEqual(2,db.execute('PRAGMA user_version').fetchone()[0])
        self.assertEqual('joined',self.call('/v1/me',login=invited)['principal']['membership'])
        self.assertEqual(sha,hashlib.sha256(original.read_bytes()).hexdigest())
        self.assertEqual(sha,hashlib.sha256((self.app.catalog.root/(artifact+'.pdf')).read_bytes()).hexdigest())
        # A missing version-2 membership never silently converts an applicant to a member.
        with self.service.connection() as db:
            db.execute('DELETE FROM account_memberships WHERE account=?',(invited['principal']['accountId'],))
        self.rejected(503,lambda:TeamService(self.tmp.name,lambda:self.now[0]))

    def test_two_startups_migrate_the_same_legacy_database_once(self):
        with self.service.connection() as db:
            db.execute('DROP TABLE membership_operations');db.execute('DROP TABLE join_code');db.execute('DROP TABLE account_memberships')
            db.execute('PRAGMA user_version=1')
        barrier=threading.Barrier(2)
        real_connect=sqlite3.connect
        class Coordinated(sqlite3.Connection):
            def executescript(connection, script):
                result=super().executescript(script)
                if 'CREATE TABLE IF NOT EXISTS metadata' in script:
                    barrier.wait(15)
                return result
        def connect(*args,**kwargs):
            return real_connect(*args,**kwargs,factory=Coordinated)
        with patch('service.sqlite3.connect',side_effect=connect):
            with concurrent.futures.ThreadPoolExecutor(2) as pool:
                services=list(pool.map(lambda _:TeamService(self.tmp.name,lambda:self.now[0]),range(2)))
        for service in services:
            self.assertEqual('joined',service.dispatch('GET','/v1/me',None,self.admin['token'],self.device,'127.0.0.1')['principal']['membership'])

    def test_pending_cannot_be_a_publication_or_programme_recipient(self):
        player=self.signup();aid=player['principal']['accountId']
        content=io.BytesIO();writer=PdfWriter();writer.add_blank_page(595,842);writer.write(content);pdf=content.getvalue()
        spec=dict(requestId=str(uuid.uuid4()),title='001 原谱',partName='小提琴',scoreNumber='0101001',category='电影',
                  sha256=hashlib.sha256(pdf).hexdigest(),size=len(pdf),independentSource=True)
        artifact=self.app.publisher.prepare(spec,self.admin['token'],self.device)['uploadId']
        self.app.publisher.upload(artifact,io.BytesIO(pdf),len(pdf),self.admin['token'],self.device)
        publication=dict(requestId=str(uuid.uuid4()),assignments=[dict(artifactId=artifact,accountIds=[self.admin_id,aid])])
        program=dict(id=str(uuid.uuid4()),title='合成排练',pieceKeys=['sequence:001'],accountIds=[aid],archived=False,expectedRevision=0)
        self.rejected(409,lambda:self.app.publisher.publish(publication,self.admin['token'],self.device))
        self.rejected(409,lambda:self.app.programs.dispatch('POST','/v1/admin/programs',program,self.admin['token'],self.device))
        with self.service.connection() as db:
            self.assertEqual(0,db.execute('SELECT published FROM artifacts WHERE id=?',(artifact,)).fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM artifact_grants').fetchone()[0])
        self.apply(player,self.application(self.code()));self.call('/v1/admin/join-requests/review',self.review(player))
        self.app.publisher.publish(publication,self.admin['token'],self.device)
        self.app.programs.dispatch('POST','/v1/admin/programs',program,self.admin['token'],self.device)
        catalog=self.wsgi('/v1/catalog',player)[1]
        self.assertEqual([artifact],[a['artifactId'] for a in catalog['artifacts']])
        self.assertEqual([program['id']],[p['id'] for p in catalog['programs']])

    def test_full_backup_independent_restore_preserves_pending_and_rejected_not_sessions(self):
        pending=self.signup(); rejected=self.signup('rejected-player'); code=self.code()
        self.apply(pending,self.application(code)); self.apply(rejected,self.application(code))
        self.call('/v1/admin/join-requests/review',self.review(rejected,approved=False))
        archive=Path(self.tmp.name)/'membership.zip'; create_archive(self.service,archive)
        destination=Path(self.tmp.name)/'restored'; restore_archive(archive,destination)
        restored=TeamService(destination,lambda:self.now[0])
        self.rejected(401,lambda:restored.dispatch('GET','/v1/me',None,pending['token'],self.device,'127.0.0.1'))
        for name,state in [('new-player','pending'),('rejected-player','rejected'),('director','joined')]:
            login=restored.login(dict(username=name,password=PASSWORD,deviceId=self.device,deviceName='restored'),'127.0.0.1')
            self.assertEqual(state,login['principal']['membership'])
        with restored.connection() as db:
            self.assertEqual(0,db.execute('SELECT count(*) FROM artifact_grants').fetchone()[0])

    def test_actual_http_signup_apply_review_then_private_catalog(self):
        class Quiet(WSGIRequestHandler):
            def log_message(self,*args): pass
        server=make_server('127.0.0.1',0,self.app,handler_class=Quiet)
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        def request(path,body=None,login=None):
            headers={'Content-Type':'application/json','X-Device-Id':self.device}
            if login: headers['Authorization']='Bearer '+login['token']
            query=urllib.request.Request('http://127.0.0.1:'+str(server.server_port)+path,
                json.dumps(body).encode() if body is not None else None,headers)
            try:
                with urllib.request.urlopen(query,timeout=20) as reply: return reply.status,json.load(reply)
            except urllib.error.HTTPError as error: return error.code,json.load(error)
        try:
            status,player=request('/v1/auth/signup',self.signup_body()); self.assertEqual(200,status)
            self.assertEqual(403,request('/v1/catalog',login=player)[0])
            self.assertEqual(200,request('/v1/me/membership',self.application(self.code()),player)[0])
            self.assertEqual('pending',request('/v1/me/membership',login=player)[1]['state'])
            self.assertEqual(200,request('/v1/admin/join-requests/review',self.review(player),self.admin)[0])
            self.assertEqual('joined',request('/v1/me',login=player)[1]['principal']['membership'])
            self.assertEqual([],request('/v1/catalog',login=player)[1]['artifacts'])
        finally:
            server.shutdown(); server.server_close(); thread.join(5)
