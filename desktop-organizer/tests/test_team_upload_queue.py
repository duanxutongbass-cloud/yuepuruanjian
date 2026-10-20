import json
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from PySide6.QtWidgets import QApplication

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.team_publish import PublishPlan, PublishService, publication_batches
from score_organizer.storage.database import Database
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_catalog_filter import catalog_fixture
from test_library_upgrade_snapshot import contents, db_state


def library(tmp_path, count):
    db = Database(tmp_path / 'library.sqlite3')
    db.initialize()
    root = tmp_path / 'files'
    db.set_setting('output_root', str(root))
    archive = ArchiveService(db, root)
    for n in range(1, count + 1):
        seq, title = f'{n:03}', f'曲目{n}'
        song = db.create_song('01', seq, title, status='archived')
        name = f'0101{seq}{title}.pdf'
        original = tmp_path / name
        original.write_bytes(f'%PDF synthetic part {n}'.encode())
        for target in (root / '按乐器分类' / archive._instrument_folder('01') / name,
                       root / '按曲目分类' / f'01{seq}{title}（01）' / name):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original.read_bytes())
        db.add_score(song, '01', name, str(original), sha256_file(original), name)
    return db, root


class ProtocolClient:
    address = 'https://scores.example.test'

    def __init__(self, admin):
        self.principal = dict(role='admin', teamId=str(uuid.uuid4()), accountId=admin)
        self.uploads, self.receipts, self.batches = {}, {}, []
        self.content_count = 0
        self.lose_reply = None
        self.bad_receipt = False

    def request(self, method, path, body=None, **kwargs):
        if body is not None:
            assert len(json.dumps(body, ensure_ascii=False).encode('utf-8')) <= 16384
        if path.endswith('/uploads'):
            uid = body['requestId']
            prior = self.uploads.setdefault(uid, dict(spec=body.copy(), complete=False))
            assert prior['spec'] == body
            return dict(uploadId=uid, complete=prior['complete'])
        if path.endswith('/content'):
            uid = path.split('/')[-2]
            spec = self.uploads[uid]['spec']
            assert kwargs['size'] == Path(kwargs['file']).stat().st_size == spec['size']
            assert sha256_file(kwargs['file']) == spec['sha256']
            self.uploads[uid]['complete'] = True
            self.content_count += 1
            return dict(artifactId=uid, hash=spec['sha256'], size=spec['size'])
        assert path == '/v1/admin/publications'
        assert 1 <= len(body['assignments']) <= 50
        assert all(self.uploads[row['artifactId']]['complete'] for row in body['assignments'])
        request = body['requestId']
        if request not in self.receipts:
            self.batches.append(body)
            self.receipts[request] = dict(ok=True, publicationId=request, artifactIds=[r['artifactId'] for r in body['assignments']])
            if self.lose_reply == len(self.batches):
                raise ValueError('合成网络中断，服务器已提交但回执未送达')
        receipt = self.receipts[request].copy()
        if self.bad_receipt:
            receipt['artifactIds'] = []
        return receipt


@pytest.mark.parametrize('count', [1, 50, 51, 101])
def test_total_selection_has_no_fifty_limit_and_requests_are_bounded(tmp_path, count):
    db, root = library(tmp_path, count)
    admin = str(uuid.uuid4())
    service, client = PublishService(db), ProtocolClient(admin)
    plan = service.build_plan({row['id']: [admin] for row in db.list_archived_scores()})
    before, data = contents(root), db_state(db.path)
    progress = []
    receipt = service.publish(plan, client, str(uuid.uuid4()), confirmed=True, progress=progress.append)
    assert len(plan.parts) == len(receipt['artifactIds']) == count
    assert [len(b['assignments']) for b in client.batches] == [min(50, count - n) for n in range(0, count, 50)]
    assert all(row['accountIds'] == [admin] for b in client.batches for row in b['assignments'])
    assert f'已确认 {count}/{count}' in progress[-1]
    assert contents(root) == before and db_state(db.path) == data


def test_recipient_heavy_queue_splits_by_encoded_size_before_count_limit(tmp_path):
    db, root = library(tmp_path, 12)
    recipients = [str(uuid.uuid4()) for _ in range(100)]
    service, client = PublishService(db), ProtocolClient(recipients[0])
    plan = service.build_plan({row['id']: recipients for row in db.list_archived_scores()})
    batches = publication_batches(plan.parts)
    assert len(batches) > 1 and max(map(len, batches)) < 50
    result = service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert len(result['artifactIds']) == 12
    assert all(set(row['accountIds']) == set(recipients) for batch in client.batches for row in batch['assignments'])


def test_lost_second_batch_receipt_retains_partial_status_and_retry_is_idempotent(tmp_path):
    db, root = library(tmp_path, 51)
    admin = str(uuid.uuid4())
    service, client = PublishService(db), ProtocolClient(admin)
    plan = service.build_plan({row['id']: [admin] for row in db.list_archived_scores()})
    publisher = str(uuid.uuid4())
    client.lose_reply = 2
    with pytest.raises(ValueError, match='已确认 50/51'):
        service.publish(plan, client, publisher, confirmed=True)
    assert len(client.receipts) == 2 and client.content_count == 51
    receipt = service.publish(plan, client, publisher, confirmed=True)
    assert len(receipt['artifactIds']) == 51 and len(client.batches) == 2 and client.content_count == 51


def test_mismatched_receipt_never_reports_complete(tmp_path):
    db, root = library(tmp_path, 1)
    admin = str(uuid.uuid4())
    service, client = PublishService(db), ProtocolClient(admin)
    client.bad_receipt = True
    plan = service.build_plan({row['id']: [admin] for row in db.list_archived_scores()})
    with pytest.raises(ValueError, match='回执不匹配'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)


def test_default_total_library_preview_requires_no_member_selection(tmp_path, monkeypatch):
    app = QApplication.instance() or QApplication([])
    db, root, sources = catalog_fixture(tmp_path, valid_pdf=True)
    dialog = TeamPublishDialog(db)
    admin, member = str(uuid.uuid4()), str(uuid.uuid4())
    client = SimpleNamespace(address='https://scores.example.test', token='synthetic',
                             principal=dict(accountId=admin, teamId=str(uuid.uuid4()), teamName='合成乐团', role='admin'))
    dialog.connected((client, [dict(accountId=member, username='member', displayName='普通成员', active=True)]))
    assert dialog.library_only.isChecked() and not dialog.members.isEnabled()
    dialog.scores.selectRow(0)
    monkeypatch.setattr(dialog, 'run', lambda callback, success: success(callback(lambda _: None)))
    dialog.make_preview()
    assert dialog.plan and dialog.plan.parts[0].accounts == (admin,)
    assert not dialog.publish_button.isEnabled()
    dialog.confirm.setChecked(True)
    assert dialog.publish_button.isEnabled()
    assert '管理员总库' in dialog.preview.item(0, 5).text()
    dialog.library_only.setChecked(False)
    assert dialog.plan is None and not dialog.assignments and not dialog.publish_button.isEnabled()
    assert dialog.members.isEnabled()
    dialog.client = None
    dialog.reject()
