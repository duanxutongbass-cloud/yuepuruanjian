"""Synthetic localhost migration fixture; never uses a real account/library."""
from pathlib import Path
import hashlib,io,json,sys,time,uuid,tempfile,threading,traceback
from wsgiref.simple_server import make_server,WSGIRequestHandler
from pypdf import PdfWriter
from test_expanded_permanent import ExpandedPermanentTest
from test_accounts import PASSWORD
from multiteam import MultiTeamDirectory,MultiTeamApplication
import multiteam_storage as storage

root=Path(sys.argv[1]);tempfile.tempdir=str(root)
fixture=ExpandedPermanentTest();fixture.setUp();fixture.service.clock=time.time;fixture.app.allow_loopback_http=True
current=[fixture.app];gate=threading.RLock()

def proxy(env,start):
    with gate:return current[0](env,start)

def migrate():
    try:
        while not (root/'migrate.request').exists():time.sleep(.05)
        with gate:
            destination=root/'multi-runtime';storage.prepare(fixture.target,destination)
            directory=MultiTeamDirectory(destination);bootstrap=str(uuid.uuid4())
            admin=directory.sign_in('/v1/auth/login',dict(username='director',password=PASSWORD,deviceId=bootstrap,
                deviceName='Synthetic fixture bootstrap'),'127.0.0.1')
            actor,_=directory.authenticated(admin['token'],bootstrap)
            second=directory.create(actor,dict(requestId=str(uuid.uuid4()),name='合成团队乙',confirmed=True))['teamId']
            with directory.locked(),directory.delegate(actor,second,admin['token'],bootstrap) as token:
                publisher=directory.application(second).publisher;out=io.BytesIO();pdf=PdfWriter();pdf.add_blank_page(222,333);pdf.write(out);data=out.getvalue()
                spec=dict(requestId=str(uuid.uuid4()),title='多团队合成谱乙',partName='01 Violin',scoreNumber='0101001',category='01 Style',
                    sha256=hashlib.sha256(data).hexdigest(),size=len(data),independentSource=True)
                aid=publisher.prepare(spec,token,bootstrap)['uploadId'];publisher.upload(aid,io.BytesIO(data),len(data),token,bootstrap)
                publisher.publish(dict(requestId=str(uuid.uuid4()),assignments=[dict(artifactId=aid,accountIds=[actor['id']])]),token,bootstrap)
            storage.retire_source(fixture.target,destination,True);current[0]=MultiTeamApplication(directory,True)
            pending=root/'migrated.pending';pending.write_text(json.dumps(dict(first=storage.team_id(fixture.service),second=second)),encoding='utf-8')
            pending.replace(root/'migrated.json')
    except Exception:
        (root/'migration-error.log').write_text(traceback.format_exc(),encoding='utf-8');raise

class Quiet(WSGIRequestHandler):
    def log_message(self,*args):pass
server=make_server('127.0.0.1',0,proxy,handler_class=Quiet)
pending=root/'ready.pending'
pending.write_text(json.dumps(dict(port=server.server_port)),encoding='utf-8');pending.replace(root/'ready.json')
threading.Thread(target=migrate,daemon=True).start()
try:server.serve_forever()
finally:server.server_close();fixture.doCleanups()
