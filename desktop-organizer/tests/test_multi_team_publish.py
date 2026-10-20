import json,threading,uuid
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from types import SimpleNamespace
import pytest
from PySide6.QtWidgets import QApplication,QMessageBox
from score_organizer.services.team_publish import TeamClient
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_catalog_filter import catalog_fixture


@pytest.fixture
def wire():
    a,b,account=[str(uuid.uuid4()) for _ in range(3)]
    state=dict(current=a,token='s'*43,invalid=False,headers=[])
    def principal(team):return dict(teamId=team,teamName='团队甲' if team==a else '团队乙',accountId=account,
        memberId=account,role='member' if team==a else 'admin',multiTeamProtocol=1,numberingProtocol=2)
    class Handler(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_POST(self):self.handle_request()
        def do_GET(self):self.handle_request()
        def handle_request(self):
            body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0')))) if self.command=='POST' else None
            if self.path=='/v1/auth/login':result=dict(token=state['token'],expiresAt=2**31,principal=principal(a))
            else:
                state['headers'].append((self.path,self.headers.get('X-Score-Team')))
                assert self.headers.get('X-Score-Team')==state['current']
                assert self.headers.get('Authorization')=='Bearer '+state['token']
                if self.path=='/v1/me/teams':result=dict(protocolVersion=1,accountId=account,currentTeamId=state['current'],teams=[
                    dict(principal(t),active=True,membership='joined',revision=0) for t in (a,b)])
                elif self.path=='/v1/me/teams/switch':
                    state['current']=body['teamId'];state['token']='n'*43;p=principal(state['current'])
                    if state['invalid']:p['accountId']=str(uuid.uuid4())
                    result=dict(token=state['token'],expiresAt=2**31,principal=p)
                else:result=dict(ok=True)
            raw=json.dumps(result).encode();self.send_response(200);self.send_header('Content-Length',str(len(raw)))
            self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
    server=ThreadingHTTPServer(('127.0.0.1',0),Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    try:yield f'http://127.0.0.1:{server.server_port}',a,b,state
    finally:server.shutdown();server.server_close();thread.join(5);assert not thread.is_alive()


def test_real_http_member_can_select_admin_team_and_every_request_is_scoped(wire):
    address,a,b,state=wire;client=TeamClient(address,str(uuid.uuid4()),allow_test_http=True)
    assert client.login('synthetic','synthetic password')['role']=='member'
    assert len(client.teams())==2
    assert client.switch_team(b)['role']=='admin';client.request('GET','/v2/admin/ensemble')
    assert state['headers']==[('/v1/me/teams',a),('/v1/me/teams/switch',a),('/v2/admin/ensemble',b)]


def test_foreign_switch_receipt_clears_credentials_instead_of_reusing_old_scope(wire):
    address,a,b,state=wire;client=TeamClient(address,str(uuid.uuid4()),allow_test_http=True);client.login('synthetic','synthetic password')
    state['invalid']=True
    with pytest.raises(ValueError,match='身份|回执'):client.switch_team(b)
    assert client.principal is None and client.token==''


def test_switch_dialog_clears_old_plan_recipients_and_assignments(tmp_path,monkeypatch):
    app=QApplication.instance() or QApplication([]);db,_,_=catalog_fixture(tmp_path)
    a,b,aid,member=[str(uuid.uuid4()) for _ in range(4)]
    class Client:
        address='https://synthetic.example';token='s'*43
        principal=dict(teamId=a,teamName='团队甲',accountId=aid,role='admin',multiTeamProtocol=1)
        def teams(self):return [dict(teamId=t,teamName='团队甲' if t==a else '团队乙',role='admin',active=True,membership='joined') for t in (a,b)]
        def switch_team(self,target):self.principal=dict(self.principal,teamId=target,teamName='团队乙');self.token='n'*43
        def request(self,*args):return dict(members=[dict(accountId=member,displayName='新团队成员',username='new-member',active=True)])
    client=Client();dialog=TeamPublishDialog(db);dialog.connected((client,[],client.teams()))
    dialog.scores.selectRow(0);dialog.assign();dialog.show_preview(dialog.service.build_plan(dialog.assignments));dialog.confirm.setChecked(True)
    assert dialog.plan is not None and dialog.publish_button.isEnabled()
    dialog.teams.setCurrentIndex(dialog.teams.findData(b))
    monkeypatch.setattr(QMessageBox,'question',lambda *args:QMessageBox.No)
    dialog.switch_team();assert dialog.plan is not None and client.principal['teamId']==a
    monkeypatch.setattr(QMessageBox,'question',lambda *args:QMessageBox.Yes)
    monkeypatch.setattr(dialog,'run',lambda callback,success:success(callback(lambda message:None)))
    dialog.switch_team()
    assert dialog.plan is None and dialog.assignments=={} and not dialog.confirm.isChecked() and not dialog.publish_button.isEnabled()
    assert client.principal['teamId']==b and dialog.teams.currentData()==b and dialog.members.count()==1
    dialog.client=None;dialog.close()
