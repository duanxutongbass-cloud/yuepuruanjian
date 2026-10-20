"""Unified accounts, session-bound teams, independently authorized score databases."""
from contextlib import contextmanager,closing
from http import HTTPStatus
from pathlib import Path
import json,logging,os,secrets,sqlite3,threading,time,uuid

from service import TeamService,Rejected,require,fields,label,UUID,SESSION_SECONDS,CONSENT_VERSION
from security import digest
from backup_archive import plain_path,unique_object
from app_release import AppReleases
import expanded_permanent as expanded
import multiteam_storage as storage

GLOBAL={('/v1/me','GET'),('/v1/me/devices','GET'),('/v1/me/devices/revoke','POST'),
        ('/v1/auth/logout','POST'),('/v1/auth/password','POST'),('/v1/me/backup-consent','POST')}
PUBLIC={('/v1/auth/login','POST'),('/v1/auth/signup','POST'),('/v1/auth/register','POST')}


class MultiTeamDirectory:
    def __init__(self,directory,clock=time.time):
        self.root,self.marker=storage.checked_root(directory);self.clock=clock
        self.identity=TeamService(self.root/'identity',clock);self.mutex=threading.RLock();self.local=threading.local();self.apps={}
        self.releases=AppReleases(self.root)
        from team_jobs import TeamCreationJobs
        self.jobs=TeamCreationJobs(self)

    @contextmanager
    def locked(self):
        # Serialize both registry and team writes across threads/processes. A backup
        # captures one state; credential revocation is ordered against long uploads.
        with self.mutex:
            if getattr(self.local,'depth',0):
                self.local.depth+=1
                try:yield
                finally:self.local.depth-=1
                return
            path=plain_path(self.root/'multi-team.lock')
            with path.open('a+b') as f:
                if f.tell()==0:f.write(b'0');f.flush()
                require(path.stat().st_nlink==1,'multi_team_lock_invalid',503)
                deadline=time.monotonic()+15
                while True:
                    try:
                        if os.name=='nt':
                            import msvcrt
                            f.seek(0);msvcrt.locking(f.fileno(),msvcrt.LK_NBLCK,1)
                        else:
                            import fcntl
                            fcntl.flock(f.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
                        break
                    except (BlockingIOError,OSError):
                        require(time.monotonic()<deadline,'multi_team_busy',503);time.sleep(.02)
                self.local.depth=1
                try:yield
                finally:
                    self.local.depth=0
                    if os.name=='nt':
                        f.seek(0);msvcrt.locking(f.fileno(),msvcrt.LK_UNLCK,1)
                    else:fcntl.flock(f.fileno(),fcntl.LOCK_UN)

    def application(self,tid):
        require(isinstance(tid,str) and UUID.fullmatch(tid),'team_unavailable',404)
        with self.identity.connection() as db:registered=db.execute('SELECT name FROM mt_teams WHERE id=?',(tid,)).fetchone()
        require(registered is not None,'team_unavailable',404)
        if tid not in self.apps:
            service=expanded.open_active(self.root/'teams'/tid);service.clock=self.clock
            require(storage.team_id(service)==tid,'team_storage_mismatch',503)
            app=expanded.ExpandedReadyApplication(service);app.releases=self.releases
            service.complete_archive=(self.archive_for_team,self.validate_backup)
            self.apps[tid]=app
        return self.apps[tid]

    def backup(self,path):
        with self.locked():return storage.create_archive(self.root,path)

    def archive_for_team(self,service,path):
        require(storage.team_id(service) in self.apps,'team_storage_mismatch',503)
        return self.backup(path)

    @staticmethod
    def validate_backup(archive,stage):return storage.validate_archive(archive,stage)

    def authenticated(self,token,device):
        with closing(sqlite3.connect(self.identity.path.as_uri()+'?mode=ro',uri=True)) as db:
            db.row_factory=sqlite3.Row;db.execute('BEGIN')
            actor=dict(self.identity.authenticate(db,token,device,joined=False))
            row=db.execute('SELECT team FROM mt_sessions WHERE hash=?',(digest(token),)).fetchone()
            require(row is not None,'team_session_required',401)
            return actor,row[0]

    def principal(self,actor,tid):
        app=self.application(tid)
        with app.service.connection() as db:
            row=db.execute('SELECT * FROM accounts WHERE id=?',(actor['id'],)).fetchone()
            meta=dict(db.execute('SELECT key,value FROM metadata'))
            membership=app.service.membership(db,actor['id'])['state'] if row else 'unjoined'
            enabled=row is not None and bool(row['active'])
            return dict(accountId=actor['id'],memberId=actor['id'],username=actor['username'],displayName=actor['name'],
                role=row['role'] if enabled and membership=='joined' else 'member',teamId=tid,teamName=meta['teamName'],
                backupConsent=actor['consent']==CONSENT_VERSION,membership=membership if enabled else 'unjoined',
                numberingProtocol=2,multiTeamProtocol=1)

    def teams(self,actor,current):
        with self.identity.connection() as db:registered=[dict(r) for r in db.execute('SELECT * FROM mt_teams ORDER BY created,id')]
        result=[]
        for team in registered:
            with self.application(team['id']).service.connection() as db:
                row=db.execute('SELECT * FROM accounts WHERE id=?',(actor['id'],)).fetchone()
                if row is None:continue
                member=self.application(team['id']).service.membership(db,actor['id'])
                result.append(dict(teamId=team['id'],teamName=team['name'],role=row['role'],membership=member['state'],
                    active=bool(row['active']),revision=member['revision']))
        require(len(result)<=1000,'too_many_teams',409)
        return dict(protocolVersion=1,accountId=actor['id'],currentTeamId=current,teams=result)

    def shadow(self,service,actor,*,joined=False):
        with service.connection() as db:
            row=db.execute('SELECT * FROM accounts WHERE id=?',(actor['id'],)).fetchone()
            if row is None:
                require(db.execute('SELECT count(*) FROM accounts').fetchone()[0]<10000,'team_member_capacity',409)
                db.execute('INSERT INTO accounts VALUES(?,?,?,?,?,?,?,?)',(actor['id'],actor['username'],actor['name'],
                    '!global-identity-only','member',1,actor['consent'],int(self.clock())))
                db.execute('INSERT INTO account_memberships(account,state,revision) VALUES(?,?,0)',(actor['id'],'joined' if joined else 'unjoined'))
            else:
                require(row['username']==actor['username'],'team_account_binding_invalid',503)
                db.execute('UPDATE accounts SET name=?,consent=? WHERE id=?',(actor['name'],actor['consent'],actor['id']))

    def scoped_session(self,result,tid,device):
        with self.identity.connection() as db:
            actor=dict(db.execute('SELECT * FROM accounts WHERE id=?',(result['principal']['accountId'],)).fetchone())
            db.execute('INSERT INTO mt_sessions VALUES(?,?)',(digest(result['token']),tid))
            db.execute('INSERT INTO mt_preferences VALUES(?,?,?) ON CONFLICT(account,device) DO UPDATE SET team=excluded.team',
                       (actor['id'],device,tid))
        self.shadow(self.application(tid).service,actor)
        result['principal']=self.principal(actor,tid);return result

    def sign_in(self,path,body,remote):
        tid=self.marker['primary']
        if path.endswith('/login'):
            # Remembered team is not a credential; it is used only after password verification.
            result=self.identity.login(body,remote)
            with self.identity.connection() as db:
                pref=db.execute('SELECT team FROM mt_preferences WHERE account=? AND device=?',
                    (result['principal']['accountId'],body['deviceId'])).fetchone()
            if pref:tid=pref[0]
        elif path.endswith('/signup'):result=self.identity.signup(body,remote)
        else:
            fields(body,{'username','password','displayName','invitation','deviceId','deviceName','backupConsent'})
            invitation=body['invitation'];require(isinstance(invitation,str) and len(invitation)<=128)
            supplied=self.identity.invitation_value(invitation)
            with self.identity.connection() as db:
                code=db.execute("SELECT team FROM mt_codes WHERE hash=? AND active=1 AND kind='invitation'",(digest(supplied),)).fetchone()
            require(code is not None,'invitation_invalid',403);tid=code[0]
            with self.application(tid).service.connection() as db:
                invite=db.execute('SELECT * FROM invitations WHERE hash=?',(digest(supplied),)).fetchone()
                require(invite and not invite['used'] and invite['expires']>int(self.clock()),'invitation_invalid',403)
            result=self.identity.register(body,remote)
            with self.identity.connection() as db:actor=dict(db.execute('SELECT * FROM accounts WHERE id=?',(result['principal']['accountId'],)).fetchone())
            self.shadow(self.application(tid).service,actor,joined=True)
            with self.application(tid).service.connection() as db:
                invite=db.execute('SELECT * FROM invitations WHERE hash=?',(digest(supplied),)).fetchone()
                require(invite and not invite['used'] and invite['expires']>int(self.clock()),'invitation_invalid',403)
                db.execute('UPDATE invitations SET used=1 WHERE hash=?',(digest(supplied),))
            with self.identity.connection() as db:db.execute('UPDATE mt_codes SET active=0 WHERE hash=?',(digest(supplied),))
        return self.scoped_session(result,tid,body['deviceId'])

    def switch(self,actor,token,device,body):
        fields(body,{'teamId'});tid=body['teamId'];require(isinstance(tid,str) and UUID.fullmatch(tid))
        with self.application(tid).service.connection() as db:
            row=db.execute('SELECT * FROM accounts WHERE id=?',(actor['id'],)).fetchone()
            require(row is not None and row['active'],'team_membership_unavailable',403)
            self.application(tid).service.membership(db,actor['id'])
        with self.identity.connection() as db:
            fresh=self.identity.authenticate(db,token,device,joined=False)
            name=db.execute('SELECT name FROM devices WHERE account=? AND id=?',(actor['id'],device)).fetchone()[0]
            result=self.identity.issue(db,fresh,dict(deviceId=device,deviceName=name))
        return self.scoped_session(result,tid,device)

    def operation(self,actor,request,kind,spec,perform):
        require(isinstance(request,str) and UUID.fullmatch(request))
        spec=digest(kind+'/'+json.dumps(spec,sort_keys=True,separators=(',',':')))
        with self.identity.connection() as db:old=db.execute('SELECT * FROM mt_operations WHERE actor=? AND request=?',(actor['id'],request)).fetchone()
        if old:
            require(old['kind']==kind and old['spec']==spec,'request_reused',409);return json.loads(old['result'])
        result=perform()
        with self.identity.connection() as db:db.execute('INSERT INTO mt_operations VALUES(?,?,?,?,?)',
            (actor['id'],request,kind,spec,json.dumps(result,separators=(',',':'))))
        return result

    def create(self,actor,body):
        fields(body,{'requestId','name','confirmed'});require(body['confirmed'] is True,'team_creation_confirmation_required')
        name=label(body['name']);request=body['requestId']
        def perform():
            tid=str(uuid.uuid5(uuid.UUID(actor['id']),'create-team/'+request))
            with self.identity.connection() as db:
                existing=db.execute('SELECT * FROM mt_teams WHERE id=?',(tid,)).fetchone()
                if existing:require(existing['name']==name and existing['creator']==actor['id'],'request_reused',409)
                else:
                    require(db.execute('SELECT count(*) FROM mt_teams WHERE creator=?',(actor['id'],)).fetchone()[0]<20,
                            'team_creation_capacity',409)
                    require(db.execute('SELECT count(*) FROM mt_teams').fetchone()[0]<1000,'team_creation_capacity',409)
            destination=plain_path(self.root/'teams'/tid)
            intent=plain_path(self.root/'teams'/('.create-'+tid+'.json'))
            scope=dict(actor=actor['id'],request=request,teamId=tid,name=name)
            if intent.exists():require(json.loads(intent.read_text('utf-8'))==scope,'team_creation_intent_changed',409)
            else:storage.save_new(intent,scope)
            if destination.exists() and not existing:
                try:expanded.open_active(destination)
                except (Rejected,OSError,ValueError):
                    # An unregistered interrupted empty-team build was never
                    # accessible. Preserve it in quarantine; never erase it.
                    failed=plain_path(self.root/'failed-team-creations');failed.mkdir(exist_ok=True)
                    target=plain_path(failed/(tid+'-'+str(uuid.uuid4())))
                    require(destination.parent==plain_path(self.root/'teams') and target.parent==failed and not target.exists(),
                            'team_creation_recovery_path_invalid',503)
                    destination.rename(target)
            storage.create_team_storage(destination,actor,name,request,tid,self.clock)
            with self.identity.connection() as db:
                db.execute('INSERT OR IGNORE INTO mt_teams VALUES(?,?,?,?)',(tid,name,actor['id'],int(self.clock())))
                if not existing:self.identity.audit(db,actor['id'],'team_created',tid)
            return dict(teamId=tid,teamName=name,created=True)
        return self.operation(actor,request,'create',body,perform)

    def apply(self,actor,body):
        fields(body,{'requestId','code'});require(isinstance(body['code'],str) and 1<=len(body['code'])<=128)
        supplied=body['code'].strip()
        if len(supplied)==6:supplied=supplied.upper()
        def perform():
            spec=digest('apply/'+json.dumps(body,sort_keys=True,separators=(',',':')))
            with self.identity.connection() as db:
                intent=db.execute('SELECT * FROM mt_intents WHERE actor=? AND request=?',(actor['id'],body['requestId'])).fetchone()
                if intent:
                    require(intent['kind']=='apply' and intent['spec']==spec,'request_reused',409)
                    plan=json.loads(intent['plan']);tid=plan['teamId']
                else:
                    row=db.execute("SELECT team FROM mt_codes WHERE hash=? AND kind='join' AND active=1",(digest(supplied),)).fetchone()
                    require(row is not None,'join_code_invalid',403);tid=row[0]
            service=self.application(tid).service
            self.shadow(service,actor)
            if not intent:
                with service.connection() as db:member=service.membership(db,actor['id'])
                plan=dict(teamId=tid,baseRevision=member['revision'])
                with self.identity.connection() as db:db.execute('INSERT INTO mt_intents VALUES(?,?,?,?,?)',
                    (actor['id'],body['requestId'],'apply',spec,json.dumps(plan,separators=(',',':'))))
            with service.connection() as db:
                state=service.apply_to_team(db,actor['id'],dict(requestId=body['requestId'],baseRevision=plan['baseRevision'],code=supplied))
            return dict(teamId=tid,membership=state)
        return self.operation(actor,body['requestId'],'apply',body,perform)

    @contextmanager
    def delegate(self,actor,tid,token,device):
        # This token never leaves the server and is removed when the request
        # returns. Keep the real account deadline: a slow PDF upload must not
        # acquire an artificial two-minute expiry. Global changes are serialized.
        service=self.application(tid).service;self.shadow(service,actor)
        local=secrets.token_urlsafe(32);now=int(self.clock())
        with service.connection() as db:
            member=db.execute('SELECT active FROM accounts WHERE id=?',(actor['id'],)).fetchone()
            require(member is not None and member['active'],'team_membership_unavailable',403)
            existing=db.execute('SELECT revoked FROM devices WHERE account=? AND id=?',(actor['id'],device)).fetchone()
            require(existing is None or not existing[0],'device_revoked',403)
            db.execute('INSERT INTO devices VALUES(?,?,?,0,?,?) ON CONFLICT(account,id) DO UPDATE SET seen=excluded.seen',
                (actor['id'],device,'Bound account device',now,now))
            db.execute('DELETE FROM sessions WHERE expires<=?',(now,))
            db.execute('INSERT INTO sessions VALUES(?,?,?,?)',(digest(local),actor['id'],device,actor['expires']))
        try:yield local
        finally:
            with service.connection() as db:db.execute('DELETE FROM sessions WHERE hash=?',(digest(local),))

    def remember_code(self,tid,path,result,actor):
        if 'code' not in result and 'invitation' not in result:return
        kind='join' if path=='/v1/admin/join-code' else 'invitation';value=result['code' if kind=='join' else 'invitation'];hashed=digest(value)
        with self.identity.connection() as db:
            old=db.execute('SELECT team,kind FROM mt_codes WHERE hash=?',(hashed,)).fetchone()
            require(old is None,'team_code_collision_retry',409)
            if kind=='join':db.execute("UPDATE mt_codes SET active=0 WHERE team=? AND kind='join'",(tid,))
            db.execute('INSERT INTO mt_codes VALUES(?,?,?,1)',(hashed,tid,kind))
            if kind=='invitation':db.execute('INSERT INTO invitations VALUES(?,?,?,0,?)',(hashed,'member',result['expiresAt'],actor['id']))


class MultiTeamApplication:
    def __init__(self,directory,allow_loopback_http=False):
        self.directory=directory if isinstance(directory,MultiTeamDirectory) else MultiTeamDirectory(directory)
        self.allow_loopback_http=allow_loopback_http
        self.directory.jobs.resume()

    @staticmethod
    def json_response(start,status,result):
        raw=json.dumps(result,separators=(',',':'),ensure_ascii=False).encode()
        start(str(status)+' '+HTTPStatus(status).phrase,[('Content-Type','application/json; charset=utf-8'),
            ('Content-Length',str(len(raw))),('Cache-Control','no-store'),('X-Content-Type-Options','nosniff')]);return [raw]

    def __call__(self,env,start):
        try:
            require(env.get('wsgi.url_scheme')=='https' or self.allow_loopback_http and env.get('REMOTE_ADDR') in ('127.0.0.1','::1'),'https_required',403)
            require(not env.get('QUERY_STRING') and not any(env.get(k) for k in ('HTTP_X_MEMBER_ID','HTTP_X_ACCOUNT_ID','HTTP_X_ROLE','HTTP_ORIGIN')),
                    'identity_override_rejected',403)
            method,path=env['REQUEST_METHOD'],env['PATH_INFO'];require(method in ('GET','POST'),'method_not_allowed',405)
            auth=env.get('HTTP_AUTHORIZATION','');token=auth[7:] if auth.startswith('Bearer ') else '';device=env.get('HTTP_X_DEVICE_ID','')
            require(len(token)<=128,'session_invalid',401)
            if path.startswith('/v1/me/team-operations/'):
                require(method=='GET','method_not_allowed',405)
                actor,tid=self.directory.authenticated(token,device)
                require(env.get('HTTP_X_SCORE_TEAM')==tid,'team_session_mismatch',403)
                return self.json_response(start,200,self.directory.jobs.get(actor,path[len('/v1/me/team-operations/'):]))
            direct=(path,method) in GLOBAL|PUBLIC or path.startswith('/v1/me/teams')
            with self.directory.locked():
                if path in ('/v1/app-release','/v1/app-release/apk'):
                    app=self.directory.application(self.directory.marker['primary']);app.allow_loopback_http=self.allow_loopback_http
                    return app(env,start)
                actor=tid=None
                if (path,method) not in PUBLIC:
                    actor,tid=self.directory.authenticated(token,device)
                    header=env.get('HTTP_X_SCORE_TEAM')
                    require(header is None or header==tid,'team_session_mismatch',403)
                    require(tid==self.directory.marker['primary'] or header==tid,'team_client_upgrade_required',426)
                if direct:
                    body=None
                    if method=='POST':
                        require(env.get('CONTENT_TYPE','').split(';')[0]=='application/json','json_required',415)
                        length=int(env.get('CONTENT_LENGTH') or 0);require(0<length<=16384,'body_too_large',413)
                        raw=env['wsgi.input'].read(length);require(len(raw)==length)
                        body=json.loads(raw.decode('utf-8'),object_pairs_hook=unique_object)
                    if (path,method) in PUBLIC:result=self.directory.sign_in(path,body,env.get('REMOTE_ADDR',''))
                    elif path=='/v1/me/teams' and method=='GET':result=self.directory.teams(actor,tid)
                    elif path=='/v1/me/teams/create' and method=='POST':result=self.directory.jobs.enqueue(actor,token,device,body)
                    elif path=='/v1/me/teams/apply' and method=='POST':
                        self.directory.identity.throttle(env.get('REMOTE_ADDR',''),actor['id']+'/teams-apply')
                        result=self.directory.apply(actor,body)
                    elif path=='/v1/me/teams/switch' and method=='POST':result=self.directory.switch(actor,token,device,body)
                    elif (path,method) in GLOBAL:
                        result=self.directory.identity.dispatch(method,path,body,token,device,env.get('REMOTE_ADDR',''))
                        if path=='/v1/me':result['principal']=self.directory.principal(actor,tid)
                    else:raise Rejected(404,'not_found')
                    return self.json_response(start,200,result)
                app=self.directory.application(tid);app.allow_loopback_http=self.allow_loopback_http
                with self.directory.delegate(actor,tid,token,device) as local:
                    request=dict(env);request['HTTP_AUTHORIZATION']='Bearer '+local
                    if method=='POST' and path in ('/v1/admin/join-code','/v1/admin/invitations'):
                        # Capture the existing bounded response, then register its
                        # unique code before exposing it to any client.
                        captured=[]
                        def capture(status,headers,exc_info=None):captured.append((status,headers))
                        result=app(request,capture);raw=b''.join(result)
                        if hasattr(result,'close'):result.close()
                        if captured[0][0].startswith('200'):
                            self.directory.remember_code(tid,path,json.loads(raw),actor)
                        start(*captured[0]);return [raw]
                    return app(request,start)
        except Rejected as error:return self.json_response(start,error.status,dict(error=error.code))
        except (ValueError,TypeError,KeyError,UnicodeError):return self.json_response(start,400,dict(error='invalid_request'))
        except Exception:
            logging.exception('Multi-team request failed')
            return self.json_response(start,500,dict(error='service_unavailable'))
