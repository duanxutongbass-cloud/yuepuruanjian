"""Durable empty-team creation; bounded HTTP polls can be cancelled by the client."""
from contextlib import closing
import json,logging,sqlite3,threading
from service import Rejected,require,fields,label,UUID
from security import digest


class TeamCreationJobs:
    def __init__(self,directory):self.directory=directory;self.workers={}

    def reply(self,row):
        if row['state']=='complete':return dict(json.loads(row['result']),requestId=row['request'])
        if row['state']=='failed':
            # This describes the old queued authorization, not the fresh caller
            # who has just authenticated to fetch/retry their own operation.
            if row['http_status']==401:raise Rejected(409,'team_creation_authorization_expired')
            raise Rejected(row['http_status'],row['error'])
        return dict(pending=True,requestId=row['request'])

    def enqueue(self,actor,token,device,body):
        fields(body,{'requestId','name','confirmed'});require(body['confirmed'] is True,'team_creation_confirmation_required')
        request=body['requestId'];require(isinstance(request,str) and UUID.fullmatch(request));label(body['name'])
        encoded=json.dumps(body,sort_keys=True,separators=(',',':'));spec=digest('create-job/'+encoded)
        with self.directory.identity.connection() as db:
            row=db.execute('SELECT * FROM mt_jobs WHERE actor=? AND request=?',(actor['id'],request)).fetchone()
            if row:
                require(row['spec']==spec,'request_reused',409)
                if row['state']=='complete':return self.reply(row)
                if row['state']=='failed':
                    self.workers.pop((actor['id'],request),None)
                    db.execute("UPDATE mt_jobs SET state='pending',session_hash=?,device=?,http_status=NULL,error=NULL WHERE actor=? AND request=?",
                        (digest(token),device,actor['id'],request))
                else:db.execute('UPDATE mt_jobs SET session_hash=?,device=? WHERE actor=? AND request=?',
                        (digest(token),device,actor['id'],request))
            else:db.execute("INSERT INTO mt_jobs VALUES(?,?,?,?,?,?,'pending',NULL,NULL,NULL)",
                (actor['id'],request,spec,encoded,digest(token),device))
        self.start(actor['id'],request)
        return dict(pending=True,requestId=request)

    def start(self,actor,request):
        key=(actor,request)
        if key in self.workers and self.workers[key].is_alive():return
        thread=threading.Thread(target=self.perform,args=(actor,request),name='team-creation',daemon=True)
        self.workers[key]=thread;thread.start()

    def resume(self):
        with self.directory.locked():
            with self.directory.identity.connection() as db:rows=db.execute("SELECT actor,request FROM mt_jobs WHERE state='pending'").fetchall()
            for actor,request in rows:self.start(actor,request)

    def perform(self,aid,request):
        try:
            with self.directory.locked():
                with self.directory.identity.connection() as db:
                    job=db.execute('SELECT * FROM mt_jobs WHERE actor=? AND request=?',(aid,request)).fetchone()
                    if job is None or job['state']!='pending':return
                    actor=db.execute('''SELECT a.*,s.expires FROM accounts a JOIN sessions s ON s.account=a.id JOIN mt_sessions ms ON ms.hash=s.hash
                        JOIN devices d ON d.account=s.account AND d.id=s.device WHERE a.id=? AND a.active=1
                        AND s.hash=? AND s.device=? AND s.expires>? AND d.revoked=0''',
                        (aid,job['session_hash'],job['device'],int(self.directory.clock()))).fetchone()
                    require(actor is not None,'session_invalid',401);actor=dict(actor);body=json.loads(job['body'])
                result=self.directory.create(actor,body)
                with self.directory.identity.connection() as db:db.execute("UPDATE mt_jobs SET state='complete',result=?,http_status=NULL,error=NULL WHERE actor=? AND request=?",
                    (json.dumps(result,separators=(',',':')),aid,request))
        except Exception as error:
            status,code=(error.status,error.code) if isinstance(error,Rejected) else (503,'team_creation_failed')
            if not isinstance(error,Rejected):logging.exception('Team creation job failed')
            with self.directory.identity.connection() as db:db.execute("UPDATE mt_jobs SET state='failed',http_status=?,error=? WHERE actor=? AND request=?",
                (status,code,aid,request))
        finally:
            key=(aid,request)
            if self.workers.get(key) is threading.current_thread():self.workers.pop(key,None)

    def get(self,actor,request):
        require(isinstance(request,str) and UUID.fullmatch(request))
        with closing(sqlite3.connect(self.directory.identity.path.as_uri()+'?mode=ro',uri=True)) as db:
            db.row_factory=sqlite3.Row
            row=db.execute('SELECT * FROM mt_jobs WHERE actor=? AND request=?',(actor['id'],request)).fetchone()
            require(row is not None,'not_found',404);return self.reply(row)
