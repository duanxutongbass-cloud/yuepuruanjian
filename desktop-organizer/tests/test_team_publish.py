import uuid
from types import SimpleNamespace
import pytest
from PySide6.QtWidgets import QApplication, QMessageBox
from PySide6.QtCore import Qt

from score_organizer.services.team_publish import PublishService, TeamClient
from score_organizer.services.import_service import sha256_file
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_catalog_filter import catalog_fixture


@pytest.mark.parametrize('url', ['http://example.com', 'https://user:pass@example.com', 'https://example.com/path',
                                     'https://example.com?token=a', 'https://example.com#secret', 'file:///tmp/a'])
def test_only_clean_https_origins_are_accepted(url):
    with pytest.raises(ValueError):
        TeamClient(url, str(uuid.uuid4()))


def test_preview_and_publish_verify_all_copies_preserve_local_records_and_originals(tmp_path):
    db, root, sources = catalog_fixture(tmp_path)
    records = [dict(r) for r in db.list_archived_scores()]
    before = {p:sha256_file(p) for p in [*sources, *root.rglob('*.pdf')]}
    service = PublishService(db)
    recipient, publisher = str(uuid.uuid4()), str(uuid.uuid4())
    plan = service.build_plan({records[0]['id']:[recipient]})
    assert plan.parts[0].number == '0101001'
    assert len(plan.parts[0].sources) == 2
    class Client:
        address = 'https://scores.example.test'
        principal = {'teamId':'team', 'accountId':'admin', 'role':'admin'}
        def __init__(self):
            self.calls = []
        def request(self, method, path, body=None, **kwargs):
            self.calls.append((path, body))
            if path.endswith('/uploads'):
                return dict(uploadId=body['requestId'], complete=False)
            if path.endswith('/content'):
                return dict(artifactId=path.split('/')[-2], hash=sha256_file(kwargs['file']), size=kwargs['size'])
            return dict(ok=True, artifactIds=[i['artifactId'] for i in body['assignments']], publicationId=body['requestId'])
    client = Client()
    with pytest.raises(ValueError, match='确认'):
        service.publish(plan, client, publisher)
    assert client.calls == []
    first = service.publish(plan, client, publisher, confirmed=True)
    second = service.publish(plan, client, publisher, confirmed=True)
    assert first == second
    assert {p:sha256_file(p) for p in before} == before
    assert [dict(r) for r in db.list_archived_scores()] == records
    plan.parts[0].sources[-1].write_bytes(b'changed')
    previous = len(client.calls)
    with pytest.raises(ValueError, match='副本缺失或变化'):
        service.publish(plan, client, publisher, confirmed=True)
    assert len(client.calls) == previous


def test_file_change_during_upload_prevents_publication(tmp_path):
    db, root, _ = catalog_fixture(tmp_path)
    service = PublishService(db)
    plan = service.build_plan({db.list_archived_scores()[0]['id']:[str(uuid.uuid4())]})
    calls = []
    def request(method, path, body=None, **kwargs):
        calls.append(path)
        if path.endswith('/uploads'):
            return dict(uploadId=body['requestId'], complete=False)
        if path.endswith('/content'):
            plan.parts[0].sources[-1].write_bytes(b'changed')
            return dict(artifactId=path.split('/')[-2], hash=plan.parts[0].digest, size=plan.parts[0].size)
        pytest.fail('publication reached after file changed')
    client = SimpleNamespace(address='https://test.example', principal=dict(teamId='team',accountId='admin',role='admin'),request=request)
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert len(calls) == 2


def test_dialog_explicit_recipient_preview_cancel_and_invalidation(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, _, _ = catalog_fixture(tmp_path)
    dialog = TeamPublishDialog(db)
    dialog.library_only.setChecked(False)  # Explicit direct-to-members workflow remains available.
    member = str(uuid.uuid4())
    client = SimpleNamespace(address='https://test.example', token='synthetic', principal={'teamName':'合成乐团',
        'teamId':str(uuid.uuid4()),'accountId':str(uuid.uuid4()),'role':'admin'})
    dialog.connected((client, [dict(accountId=member, username='violin',displayName='小提琴成员',active=True)]))
    assert dialog.members.item(0).checkState() == Qt.Unchecked
    assert not dialog.publish_button.isEnabled()
    dialog.scores.selectRow(0)
    dialog.assign()
    assert not dialog.assignments
    dialog.members.item(0).setCheckState(Qt.Checked)
    dialog.assign()
    assert len(dialog.assignments) == 1
    dialog.show_preview(dialog.service.build_plan(dialog.assignments))
    assert not dialog.publish_button.isEnabled()
    dialog.confirm.setChecked(True)
    assert dialog.publish_button.isEnabled()
    monkeypatch.setattr(QMessageBox, 'question', lambda *a:QMessageBox.No)
    monkeypatch.setattr(dialog, 'run', lambda *a:pytest.fail('cancel must not upload'))
    dialog.publish()
    dialog.search.setText('不存在的谱')
    assert dialog.selected() == []
    # Filtering keeps the explicit plan, whose preview continues to show all recipients.
    dialog.search.clear()
    dialog.scores.selectRow(0)
    dialog.remove()
    assert dialog.plan is None and not dialog.publish_button.isEnabled()
    dialog.client = None
    dialog.reject()
