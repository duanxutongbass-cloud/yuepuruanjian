import io,json,sqlite3,tempfile,time,unittest,uuid,zipfile
from contextlib import closing
from pathlib import Path

import test_expanded_permanent as fixtures
from test_accounts import PASSWORD
from service import Rejected
from multiteam import MultiTeamDirectory,MultiTeamApplication
import multiteam_storage as storage
import expanded_permanent as expanded


class MultiTeamTest(unittest.TestCase):
    def setUp(self):
        self.seed=fixtures.ExpandedPermanentTest();self.seed.setUp();self.addCleanup(self.seed.doCleanups)
        self.root=self.seed.root/'multi';self.device=self.seed.device
        self.before=self.seed.pdf;self.artifact=self.seed.seed.artifact
        self.proof=storage.prepare(self.seed.target,self.root)
        self.directory=MultiTeamDirectory(self.root,lambda:self.seed.seed.now[0]);self.app=MultiTeamApplication(self.directory,True)
        self.session=self.seed.admin;self.primary=self.session['principal']['teamId']

    def api(self,path,method='GET',body=None,session=None,header=True):
        session=self.session if session is None else session
        raw=json.dumps(body).encode() if body is not None else b'';captured=[]
        env=dict(REQUEST_METHOD=method,PATH_INFO=path,QUERY_STRING='',REMOTE_ADDR='127.0.0.1',
            CONTENT_TYPE='application/json',CONTENT_LENGTH=str(len(raw)))
        env['wsgi.url_scheme']='http';env['wsgi.input']=io.BytesIO(raw)
        env['HTTP_X_DEVICE_ID']=self.device;env['HTTP_X_SCORE_NUMBER_REUSE']='1'
        if session:
            env['HTTP_AUTHORIZATION']='Bearer '+session['token']
            if header:env['HTTP_X_SCORE_TEAM']=session['principal']['teamId']
        if isinstance(header,str):env['HTTP_X_SCORE_TEAM']=header
        result=self.app(env,lambda status,headers:captured.append((int(status.split()[0]),headers)))
        data=b''.join(result)
        if hasattr(result,'close'):result.close()
        try:data=json.loads(data)
        except (ValueError,UnicodeError):pass
        return captured[0][0],data

    def create(self,name='Synthetic second team'):
        status,created=self.api('/v1/me/teams/create','POST',dict(requestId=str(uuid.uuid4()),name=name,confirmed=True))
        self.assertEqual(200,status,created);return self.wait_creation(created)['teamId']

    def wait_creation(self,result):
        deadline=time.monotonic()+120
        while result.get('pending'):
            self.assertLess(time.monotonic(),deadline,'Synthetic team creation did not complete')
            time.sleep(.1);status,result=self.api('/v1/me/team-operations/'+result['requestId']);self.assertEqual(200,status,result)
        return result

    def switch(self,tid,session=None):
        status,result=self.api('/v1/me/teams/switch','POST',dict(teamId=tid),session=session)
        self.assertEqual(200,status,result);return result

    def login(self,username='director',password=PASSWORD):
        status,result=self.api('/v1/auth/login','POST',dict(username=username,password=password,
            deviceId=self.device,deviceName='Synthetic multi pad'),session={})
        self.assertEqual(200,status,result);return result

    def test_migration_preserves_primary_ids_pdf_permissions_and_old_session(self):
        status,me=self.api('/v1/me');self.assertEqual(200,status,me)
        self.assertEqual(self.primary,me['principal']['teamId']);self.assertEqual(1,me['principal']['multiTeamProtocol'])
        self.assertEqual(self.seed.admin_id,me['principal']['accountId'])
        status,catalog=self.api('/v2/catalog');self.assertEqual(200,status,catalog)
        self.assertEqual(self.before,(self.root/'teams'/self.primary/'artifacts'/(self.artifact+'.pdf')).read_bytes())
        self.assertEqual(self.before,(self.seed.target/'artifacts'/(self.artifact+'.pdf')).read_bytes())
        self.assertEqual(200,self.api('/v2/catalog',header=False)[0])
        self.assertEqual(403,self.api('/v2/catalog',header=str(uuid.uuid4()))[0])
        self.assertRaises(Rejected,expanded.open_active,self.root)

    def test_create_is_confirmed_idempotent_and_switch_retires_old_session(self):
        body=dict(requestId=str(uuid.uuid4()),name='Other orchestra',confirmed=True)
        self.assertEqual(400,self.api('/v1/me/teams/create','POST',dict(body,confirmed=False))[0])
        status,new=self.api('/v1/me/teams/create','POST',body);self.assertEqual(200,status,new);new=self.wait_creation(new)
        self.assertEqual((status,new),self.api('/v1/me/teams/create','POST',body))
        self.assertEqual(409,self.api('/v1/me/teams/create','POST',dict(body,name='Changed'))[0])
        old=self.session;self.session=self.switch(new['teamId'])
        self.assertEqual('admin',self.session['principal']['role']);self.assertNotEqual(old['token'],self.session['token'])
        self.assertEqual(401,self.api('/v2/catalog',session=old)[0])
        self.assertEqual(426,self.api('/v2/catalog',header=False)[0])
        status,catalog=self.api('/v2/catalog');self.assertEqual(200,status,catalog);self.assertEqual([],catalog['artifacts'])
        self.assertEqual(404,self.api('/v2/artifacts/'+self.artifact)[0])
        self.session=self.switch(self.primary);self.assertEqual(200,self.api('/v2/catalog')[0])

    def test_same_account_join_review_team_roles_and_global_device_revocation(self):
        tid=self.create();admin_primary=self.session;self.session=self.switch(tid)
        status,code=self.api('/v1/admin/join-code','POST',dict(baseRevision=0));self.assertEqual(200,status,code)
        admin_second=self.session
        status,member=self.api('/v1/auth/signup','POST',dict(username='new-member',password=PASSWORD,displayName='Synthetic member',
            deviceId=self.device,deviceName='Synthetic pad',backupConsent=False),session={});self.assertEqual(200,status,member)
        self.session=member
        request=dict(requestId=str(uuid.uuid4()),code=code['code'].lower())
        status,application=self.api('/v1/me/teams/apply','POST',request);self.assertEqual(200,status,application)
        self.assertEqual((status,application),self.api('/v1/me/teams/apply','POST',request))
        self.assertEqual(403,self.api('/v2/catalog')[0]);self.session=self.switch(tid)
        self.assertEqual('pending',self.session['principal']['membership'])
        self.assertEqual(403,self.api('/v2/catalog')[0])
        pending=self.session;self.session=admin_second
        status,queue=self.api('/v1/admin/join-requests');self.assertEqual(200,status,queue)
        row=queue['requests'][0]
        status,review=self.api('/v1/admin/join-requests/review','POST',dict(requestId=str(uuid.uuid4()),
            baseRevision=row['revision'],accountId=row['accountId'],approved=True));self.assertEqual(200,status,review)
        self.session=pending;status,me=self.api('/v1/me');self.assertEqual(200,status,me);self.assertEqual('member',me['principal']['role'])
        self.assertEqual(403,self.api('/v1/admin/members')[0]);self.assertEqual(200,self.api('/v2/catalog')[0])
        self.assertEqual(200,self.api('/v1/me/devices/revoke','POST',dict(deviceId=self.device))[0])
        self.assertEqual(401,self.api('/v1/me')[0]);self.assertEqual(401,self.api('/v2/catalog')[0])

    def test_multi_backup_independent_restore_and_tamper_rejection(self):
        tid=self.create();backup=self.seed.root/'multi-full.zip';self.directory.backup(backup)
        before=backup.read_bytes();restored=self.seed.root/'multi-restored';storage.restore_archive(backup,restored)
        self.assertRaises(Rejected,MultiTeamDirectory,restored)
        result=storage.activate(restored,self.root);self.assertEqual(2,result['teams']);self.assertEqual(before,backup.read_bytes())
        restored_dir=MultiTeamDirectory(restored,lambda:self.seed.seed.now[0]);self.app=MultiTeamApplication(restored_dir,True)
        self.assertEqual(401,self.api('/v1/me')[0]);self.session=self.login();self.assertEqual(200,self.api('/v2/catalog')[0])
        self.assertEqual(self.before,(restored/'teams'/self.primary/'artifacts'/(self.artifact+'.pdf')).read_bytes())
        self.assertTrue((restored/'teams'/tid/'team.sqlite3').is_file())
        with zipfile.ZipFile(backup) as source:
            payload={n:source.read(n) for n in source.namelist()}
        manifest=json.loads(payload['manifest.json']);manifest['files']['teams/'+tid+'.zip']['sha256']='0'*64
        payload['manifest.json']=json.dumps(manifest).encode();tampered=self.seed.root/'bad-multi.zip'
        with zipfile.ZipFile(tampered,'x') as z:
            for n,b in payload.items():z.writestr(n,b)
        stage=self.seed.root/'bad-stage';stage.mkdir()
        self.assertRaises(Rejected,storage.validate_archive,tampered,stage)
        legacy=self.seed.root/'legacy-reject';legacy.mkdir()
        self.assertRaises(Rejected,expanded.validate_archive,backup,legacy)

    def test_release_backs_up_all_teams_and_retains_other_team(self):
        tid=self.create()
        revision=self.api('/v2/admin/ensemble')[1]['revision']
        status,result=self.api('/v2/admin/catalog-actions','POST',dict(requestId=str(uuid.uuid4()),artifactIds=[self.artifact],
            removed=True,expectedRevision=revision));self.assertEqual(200,status,result)
        status,preview=self.api('/v2/admin/permanent-deletions/preview','POST',dict(artifactIds=[self.artifact],expectedRevision=revision+1))
        self.assertEqual(200,status,preview)
        status,result=self.api('/v2/admin/permanent-deletions','POST',dict(artifactIds=[self.artifact],expectedRevision=preview['revision'],
            scopeDigest=preview['scopeDigest'],requestId=str(uuid.uuid4()),confirmed=True));self.assertEqual(200,status,result)
        status,inventory=self.api('/v2/admin/number-releases');self.assertEqual(200,status,inventory)
        status,preview=self.api('/v2/admin/number-releases/preview','POST',dict(sequences=['001'],expectedRevision=inventory['revision']))
        self.assertEqual(200,status,preview);request=str(uuid.uuid4())
        status,result=self.api('/v2/admin/number-releases/commit','POST',dict(sequences=['001'],expectedRevision=preview['revision'],
            scopeDigest=preview['scopeDigest'],requestId=request,confirmed=True));self.assertEqual(200,status,result)
        backup=self.root/'teams'/self.primary/'backups/number-releases'/request/'before.zip'
        with tempfile.TemporaryDirectory(dir=self.seed.root) as tmp:
            manifest=storage.validate_archive(backup,Path(tmp));self.assertIn('teams/'+tid+'.zip',manifest['files'])
        self.assertEqual(tid,storage.team_id(expanded.open_active(self.root/'teams'/tid)))
        self.seed.app=self.app
        new,_=self.seed.staged('0101001');self.seed.publish([new])
        status,catalog=self.api('/v2/catalog');self.assertEqual(200,status,catalog)
        self.assertTrue(catalog['artifacts'][0]['pieceGeneration'])

    def test_real_http_windows_client_switches_without_changing_primary_library(self):
        import sys,threading
        from wsgiref.simple_server import make_server,WSGIRequestHandler
        desktop=Path(__file__).resolve().parent.parent/'desktop-organizer/src'
        sys.path.insert(0,str(desktop))
        from score_organizer.services.team_publish import TeamClient
        tid=self.create()
        class Quiet(WSGIRequestHandler):
            def log_message(self,*args):pass
        server=make_server('127.0.0.1',0,self.app,handler_class=Quiet)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        try:
            client=TeamClient('http://127.0.0.1:'+str(server.server_port),str(uuid.uuid4()),allow_test_http=True)
            client.login('director',PASSWORD);self.assertEqual(2,len(client.teams()))
            old=client.token;client.switch_team(tid);self.assertNotEqual(old,client.token)
            self.assertEqual([],client.request('GET','/v2/admin/ensemble')['groups'])
            with self.assertRaises(ValueError):client.request('GET','/v2/artifacts/'+self.artifact)
            self.assertEqual(self.before,(self.root/'teams'/self.primary/'artifacts'/(self.artifact+'.pdf')).read_bytes())
            client.logout()
        finally:server.shutdown();server.server_close();thread.join(5);self.assertFalse(thread.is_alive())

    def test_retired_single_team_cannot_restart_or_silently_reopen_old_data(self):
        self.assertEqual(storage.business_snapshot(self.seed.service),storage.business_snapshot(self.directory.application(self.primary).service))
        original=(self.seed.target/'permanent-active.json').read_bytes()
        result=storage.retire_source(self.seed.target,self.root,True);self.assertTrue(result['retired'])
        self.assertRaises(Rejected,expanded.open_active,self.seed.target)
        self.assertEqual(original,(self.root/'source-active-marker.json').read_bytes())
        self.assertTrue(storage.retire_source(self.seed.target,self.root,True)['alreadyRetired'])
        self.assertEqual(self.before,(self.seed.target/'artifacts'/(self.artifact+'.pdf')).read_bytes())
        self.assertEqual(200,self.api('/v2/catalog')[0])

    def test_member_in_primary_admin_in_own_team_and_disable_is_team_local(self):
        admin=self.session
        status,member=self.api('/v1/auth/signup','POST',dict(username='team-owner',password=PASSWORD,displayName='Synthetic owner',
            deviceId=self.device,deviceName='Synthetic member',backupConsent=True),session={});self.assertEqual(200,status,member)
        self.session=member;tid=self.create('Member owned team');self.session=self.switch(tid)
        self.assertEqual('admin',self.session['principal']['role']);own=self.session
        self.session=admin;status,code=self.api('/v1/admin/join-code','POST',dict(baseRevision=0));self.assertEqual(200,status,code)
        self.session=own;status,result=self.api('/v1/me/teams/apply','POST',dict(requestId=str(uuid.uuid4()),code=code['code']))
        self.assertEqual(200,status,result);self.session=admin
        status,queue=self.api('/v1/admin/join-requests');self.assertEqual(200,status,queue);row=queue['requests'][0]
        status,result=self.api('/v1/admin/join-requests/review','POST',dict(requestId=str(uuid.uuid4()),baseRevision=row['revision'],
            accountId=row['accountId'],approved=True));self.assertEqual(200,status,result)
        self.session=own;self.session=self.switch(self.primary);self.assertEqual('member',self.session['principal']['role']);primary_member=self.session
        self.session=admin;status,result=self.api('/v1/admin/members/status','POST',dict(accountId=row['accountId'],active=False))
        self.assertEqual(200,status,result);self.session=primary_member;self.assertEqual(403,self.api('/v2/catalog')[0])
        self.session=self.switch(tid);self.assertEqual('admin',self.session['principal']['role'])
        status,roster=self.api('/v1/admin/members');self.assertEqual(200,status,roster)
        self.assertEqual([row['accountId']],[r['accountId'] for r in roster['members']])
        self.assertEqual(403,self.api('/v1/me/teams/switch','POST',dict(teamId=self.primary))[0])

    def test_restore_cannot_revive_old_grants_but_keeps_current_device_seen_and_team_choice(self):
        tid=self.create();backup=self.seed.root/'authority.zip';self.directory.backup(backup)
        root=self.seed.root/'old-auth';storage.restore_archive(backup,root)
        service=self.directory.application(self.primary).service
        with service.connection() as db:db.execute('DELETE FROM artifact_grants WHERE account=? AND artifact=?',(self.seed.admin_id,self.artifact))
        with self.assertRaisesRegex(Rejected,'multi_team_restore_requires_current_authorization'):storage.activate(root,self.root)
        self.assertRaises(Rejected,MultiTeamDirectory,root)
        with service.connection() as db:db.execute('INSERT INTO artifact_grants VALUES(?,?)',(self.seed.admin_id,self.artifact))
        self.session=self.switch(tid)
        with self.directory.identity.connection() as db:db.execute('UPDATE devices SET seen=seen+5')
        storage.activate(root,self.root)
        self.app=MultiTeamApplication(MultiTeamDirectory(root,lambda:self.seed.seed.now[0]),True)
        self.session=self.login();self.assertEqual(tid,self.session['principal']['teamId'])

    def test_pending_apply_retries_after_child_commit_without_storing_plain_join_code(self):
        tid=self.create();self.session=self.switch(tid)
        status,code=self.api('/v1/admin/join-code','POST',dict(baseRevision=0));self.assertEqual(200,status,code)
        status,member=self.api('/v1/auth/signup','POST',dict(username='retry-player',password=PASSWORD,displayName='Synthetic retry',
            deviceId=self.device,deviceName='Synthetic pad',backupConsent=False),session={});self.assertEqual(200,status,member)
        self.session=member;body=dict(requestId=str(uuid.uuid4()),code=code['code'])
        status,first=self.api('/v1/me/teams/apply','POST',body);self.assertEqual(200,status,first)
        with self.directory.identity.connection() as db:
            db.execute('DELETE FROM mt_operations WHERE actor=? AND request=?',(member['principal']['accountId'],body['requestId']))
            intent=db.execute('SELECT spec,plan FROM mt_intents WHERE actor=? AND request=?',(member['principal']['accountId'],body['requestId'])).fetchone()
            self.assertNotIn(code['code'],str(tuple(intent)))
        self.assertEqual((200,first),self.api('/v1/me/teams/apply','POST',body))
        self.assertEqual(409,self.api('/v1/me/teams/apply','POST',dict(body,code='ChangedCode'))[0])

    def test_failed_root_marker_publication_leaves_source_usable_and_partial_root_closed(self):
        from unittest.mock import patch
        destination=self.seed.root/'interrupted';original=(self.seed.target/'permanent-active.json').read_bytes();link=storage.os.link
        def fail_root_marker(source,target):
            if Path(target).name==storage.MARKER:raise OSError('synthetic marker failure')
            return link(source,target)
        with patch.object(storage.os,'link',side_effect=fail_root_marker):
            self.assertRaises(OSError,storage.prepare,self.seed.target,destination)
        self.assertFalse((destination/storage.MARKER).exists());self.assertRaises(Rejected,MultiTeamDirectory,destination)
        self.assertEqual(original,(self.seed.target/'permanent-active.json').read_bytes());expanded.open_active(self.seed.target)

    def test_creation_poll_stays_available_during_slow_worker_and_hides_other_accounts(self):
        import threading
        from unittest.mock import patch
        entered=threading.Event();release=threading.Event()
        body=dict(requestId=str(uuid.uuid4()),name='Synthetic slow creation',confirmed=True)
        def slow(*args):
            entered.set();release.wait(20);raise OSError('Synthetic storage interruption')
        with patch.object(self.directory,'create',side_effect=slow):
            status,result=self.api('/v1/me/teams/create','POST',body)
            self.assertEqual(200,status,result);self.assertTrue(result['pending'])
            try:
                self.assertTrue(entered.wait(10));started=time.monotonic()
                status,result=self.api('/v1/me/team-operations/'+body['requestId'])
                self.assertEqual(200,status,result);self.assertTrue(result['pending'])
                self.assertLess(time.monotonic()-started,2,'Polling waited on the long team write lock')
            finally:release.set()
            for worker in list(self.directory.jobs.workers.values()):worker.join(20)
        status,result=self.api('/v1/me/team-operations/'+body['requestId'])
        self.assertEqual((503,'team_creation_failed'),(status,result['error']))
        status,other=self.api('/v1/auth/signup','POST',dict(username='job-outsider',password=PASSWORD,displayName='Synthetic outsider',
            deviceId=self.device,deviceName='Synthetic pad',backupConsent=False),session={})
        self.assertEqual(200,status,other)
        self.assertEqual(404,self.api('/v1/me/team-operations/'+body['requestId'],session=other)[0])

    def test_creation_restart_reauth_retry_preserves_partial_storage_and_stable_identity(self):
        from unittest.mock import patch
        from security import digest
        body=dict(requestId=str(uuid.uuid4()),name='Synthetic recovered creation',confirmed=True)
        with patch.object(self.directory.jobs,'start'):
            status,result=self.api('/v1/me/teams/create','POST',body)
        self.assertEqual(200,status,result);self.assertTrue(result['pending'])
        with self.directory.identity.connection() as db:
            job=db.execute('SELECT * FROM mt_jobs WHERE request=?',(body['requestId'],)).fetchone()
            self.assertEqual(digest(self.session['token']),job['session_hash'])
            self.assertNotIn(self.session['token'],json.dumps(dict(job)))
        old=self.session;self.session=self.login()
        self.directory.jobs.perform(self.seed.admin_id,body['requestId'])
        status,result=self.api('/v1/me/team-operations/'+body['requestId'])
        self.assertEqual((409,'team_creation_authorization_expired'),(status,result['error']))
        self.assertEqual(200,self.api('/v1/me')[0]);self.assertEqual(401,self.api('/v1/me',session=old)[0])
        tid=str(uuid.uuid5(uuid.UUID(self.seed.admin_id),'create-team/'+body['requestId']))
        partial=self.root/'teams'/tid;partial.mkdir();(partial/'interrupted.txt').write_text('Preserve interrupted build')
        status,result=self.api('/v1/me/teams/create','POST',body);self.assertEqual(200,status,result)
        result=self.wait_creation(result);self.assertEqual(tid,result['teamId'])
        self.assertEqual((200,result),self.api('/v1/me/teams/create','POST',body))
        preserved=list((self.root/'failed-team-creations').glob(tid+'-*/interrupted.txt'))
        self.assertEqual(1,len(preserved));self.assertEqual('Preserve interrupted build',preserved[0].read_text())
        self.assertEqual(2,len(self.api('/v1/me/teams')[1]['teams']))

    def test_real_operator_cli_backup_restore_activation_and_device_recovery(self):
        import subprocess,sys
        def command(data,*args,success=True):
            result=subprocess.run([sys.executable,str(Path(__file__).with_name('server.py')),'--data',str(data),*args],
                capture_output=True,text=True,timeout=180)
            if success:self.assertEqual(0,result.returncode,result.stderr[-2000:])
            else:self.assertNotEqual(0,result.returncode)
            return result
        archive=self.seed.root/'cli-multi.zip';restored=self.seed.root/'cli-restored'
        command(self.root,'--multi-team','backup-full','--output',str(archive))
        verified=command(self.root,'verify-multi','--archive',str(archive));self.assertTrue(json.loads(verified.stdout)['verified'])
        command(restored,'restore-multi','--archive',str(archive));self.assertRaises(Rejected,MultiTeamDirectory,restored)
        command(restored,'activate-multi','--authority',str(self.root),'--source-stopped','--confirmed')
        reopened=MultiTeamDirectory(restored);self.assertEqual(self.primary,reopened.marker['primary'])
        self.assertEqual(200,self.api('/v1/me/devices/revoke','POST',dict(deviceId=self.device))[0])
        command(self.root,'restore-account-device','--username','director','--device-id',self.device,'--confirmed',success=False)
        command(self.root,'--multi-team','restore-account-device','--username','director','--device-id',self.device,'--confirmed')
        self.assertEqual(401,self.api('/v1/me')[0]);self.session=self.login();self.assertEqual(200,self.api('/v1/me')[0])
