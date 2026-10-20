"""Whole-PDF uploads include ordinary parts, full scores and combined scores."""
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid

import pytest
from pypdf import PdfWriter
from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QMessageBox

from score_organizer.services import runtime_services
from score_organizer.services.import_service import sha256_file
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.team_publish import TeamClient
from score_organizer.storage.database import Database
from score_organizer.storage.expanded_database import activate_candidate
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_expanded_team_publish import CandidateClient
from test_library_upgrade_snapshot import contents, db_state
from test_team_upload_queue import ProtocolClient


def mixed_library(tmp_path, expanded):
    old = Database(tmp_path / 'library.sqlite3')
    old.initialize()
    root = tmp_path / 'files'
    old.set_setting('output_root', str(root))
    sources = []
    for code in ('01', '02', '03', '08', '09'):
        source = tmp_path / f'{code}01上传合奏.pdf'
        writer = PdfWriter()
        writer.add_blank_page(595, 842)
        writer.add_metadata({'/Title': f'Synthetic score {code}'})
        with source.open('wb') as stream:
            writer.write(stream)
        sources.append(source)
    importer = runtime_services.importer(old)
    batch = importer.import_paths(sources)
    archive = runtime_services.archive(old, root)
    assert archive.commit_plan(batch, archive.build_plan(batch)) == 0
    if expanded:
        db = activate_candidate(prepare_relocated_library(old.path, tmp_path / 'expanded'))
    else:
        db = old
    return old, root, sources, db


def snapshot(old, root, sources, db):
    return (db_state(old.path), contents(root), db_state(db.path),
            contents(Path(db.get_setting('output_root'))), {p: sha256_file(p) for p in sources})


def wait_worker(app, dialog):
    deadline = time.monotonic() + 20
    while dialog.worker.isRunning() and time.monotonic() < deadline:
        app.processEvents()
        time.sleep(.005)
    assert not dialog.worker.isRunning(), 'upload worker did not finish'
    app.processEvents()


@pytest.mark.parametrize('expanded', [False, True])
def test_mixed_whole_pdfs_publish_retry_preserves_numbers_files_and_explicit_recipients(tmp_path, expanded):
    old, root, sources, db = mixed_library(tmp_path, expanded)
    service = runtime_services.publish(db)
    admin = str(uuid.uuid4())
    client = CandidateClient(admin) if expanded else ProtocolClient(admin)
    before = snapshot(old, root, sources, db)
    rows = db.list_archived_scores()
    plan = service.build_plan({r['id']: [admin] for r in rows})
    assert len(plan.parts) == 5
    assert {p.number[:2] for p in plan.parts} == {'01', '02', '03', '08', '09'}
    assert {p.part_name for p in plan.parts if p.number.startswith('09')} == {'09 总谱+分谱'}
    assert {len(p.sources) for p in plan.parts if p.number[:2] in ('08', '09')} == {1}
    publisher = str(uuid.uuid4())
    with pytest.raises(ValueError, match='确认'):
        service.publish(plan, client, publisher)
    assert not client.uploads
    receipt = service.publish(plan, client, publisher, confirmed=True)
    assert service.publish(plan, client, publisher, confirmed=True) == receipt
    assert len(receipt['artifactIds']) == client.content_count == 5
    assert all(a['accountIds'] == [admin] for batch in client.batches for a in batch['assignments'])
    assert snapshot(old, root, sources, db) == before


@pytest.mark.parametrize('expanded', [False, True])
@pytest.mark.parametrize('library_only', [False, True])
def test_actual_async_preview_enables_combined_upload_only_after_confirmation(tmp_path, monkeypatch, expanded, library_only):
    app = QApplication.instance() or QApplication([])
    old, root, sources, db = mixed_library(tmp_path, expanded)
    dialog = TeamPublishDialog(db)
    admin, member = str(uuid.uuid4()), str(uuid.uuid4())
    client = CandidateClient(admin) if expanded else ProtocolClient(admin)
    client.token = 'synthetic'
    client.principal['teamName'] = '合成乐团'
    dialog.connected((client, [dict(accountId=member, username='member', displayName='成员', active=True)]))
    dialog.library_only.setChecked(library_only)
    if not library_only:
        dialog.members.item(0).setCheckState(Qt.Checked)
    dialog.scores.selectAll()
    dialog.assign()
    dialog.confirm.setChecked(True)
    assert not dialog.publish_button.isEnabled()  # A checkbox alone is not a preview.
    dialog.make_preview()
    wait_worker(app, dialog)
    assert dialog.plan is not None, dialog.status.text()
    assert dialog.preview.rowCount() == 5
    assert any(dialog.preview.item(i, 2).text() == '09 总谱+分谱' for i in range(5))
    assert not dialog.confirm.isChecked() and not dialog.publish_button.isEnabled()
    dialog.confirm.setChecked(True)
    assert dialog.publish_button.isEnabled()
    monkeypatch.setattr(QMessageBox, 'question', lambda *args: QMessageBox.No)
    dialog.publish()
    assert not client.uploads
    monkeypatch.setattr(QMessageBox, 'question', lambda *args: QMessageBox.Yes)
    dialog.publish()
    wait_worker(app, dialog)
    assert '上传成功：5 份' in dialog.status.text()
    recipient = admin if library_only else member
    assert all(a['accountIds'] == [recipient] for batch in client.batches for a in batch['assignments'])
    assert dialog.plan is None and not dialog.publish_button.isEnabled()
    dialog.client = None
    dialog.reject()


@pytest.mark.parametrize('expanded', [False, True])
def test_combined_pdf_changes_still_block_before_any_remote_write(tmp_path, expanded):
    old, root, sources, db = mixed_library(tmp_path, expanded)
    service = runtime_services.publish(db)
    admin = str(uuid.uuid4())
    client = CandidateClient(admin) if expanded else ProtocolClient(admin)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores()})
    combined = next(p for p in plan.parts if p.number.startswith('09'))
    combined.sources[0].write_bytes(b'changed after preview')
    with pytest.raises(ValueError, match='副本缺失或变化'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert not client.uploads and client.content_count == 0


@pytest.mark.parametrize('problem', ['not_ready', 'wrong_team', 'missing_endpoint', 'account_changed', 'protocol_changed'])
def test_legacy_library_requires_fresh_v2_readiness_and_stable_identity_before_every_write(tmp_path, problem):
    old, root, sources, db = mixed_library(tmp_path, False)
    service = runtime_services.publish(db)
    client = CandidateClient(str(uuid.uuid4()))
    client.principal['numberingProtocol'] = 2
    plan = service.build_plan({r['id']: [client.principal['accountId']] for r in db.list_archived_scores()})
    if problem == 'not_ready':
        client.reply['expandedPublicationReady'] = False
    elif problem == 'wrong_team':
        client.reply['teamId'] = str(uuid.uuid4())
    elif problem == 'missing_endpoint':
        client.after_read = lambda: (_ for _ in ()).throw(ValueError('HTTP 404'))
    elif problem == 'account_changed':
        client.after_read = lambda: client.principal.update(accountId=str(uuid.uuid4()))
    else:
        client.after_read = lambda: client.principal.update(numberingProtocol=1)
    before = snapshot(old, root, sources, db)
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.calls == [('GET', '/v2/admin/capabilities')]
    assert not client.uploads and snapshot(old, root, sources, db) == before


def test_legacy_library_renegotiates_v2_before_content_and_does_not_fall_back(tmp_path):
    old, root, sources, db = mixed_library(tmp_path, False)
    service = runtime_services.publish(db)
    client = CandidateClient(str(uuid.uuid4()))
    client.principal['numberingProtocol'] = 2
    client.revoke_at = 2
    plan = service.build_plan({r['id']: [client.principal['accountId']] for r in db.list_archived_scores()})
    with pytest.raises(ValueError, match='未继续上传'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.calls == [('GET', '/v2/admin/capabilities'), ('POST', '/v2/admin/uploads'),
                            ('GET', '/v2/admin/capabilities')]
    assert client.content_count == 0 and not client.batches


@pytest.mark.parametrize('protocol', [True, '2', 3, None])
def test_legacy_library_rejects_ambiguous_protocol_without_writing(tmp_path, protocol):
    old, root, sources, db = mixed_library(tmp_path, False)
    service = runtime_services.publish(db)
    client = ProtocolClient(str(uuid.uuid4()))
    client.principal['numberingProtocol'] = protocol
    plan = service.build_plan({r['id']: [client.principal['accountId']] for r in db.list_archived_scores()})
    with pytest.raises(ValueError, match='协议不兼容'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert not client.uploads


@pytest.mark.parametrize('expanded', [False, True])
def test_mixed_pdfs_reach_real_http_service_with_exact_hashes_and_types(tmp_path, expanded):
    fixture = Path(__file__).resolve().parents[2] / 'team-service/expanded_interop_fixture.py'
    remote = tmp_path / 'remote'
    remote.mkdir()
    with (remote / 'error.log').open('w') as error:
        process = subprocess.Popen([sys.executable, str(fixture), str(remote)], stdout=subprocess.DEVNULL,
            stderr=error, creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            ready, deadline = remote / 'ready-port', time.monotonic() + 90
            while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(.05)
            assert ready.exists(), (remote / 'error.log').read_text()
            old, root, sources, db = mixed_library(tmp_path, expanded)
            before = snapshot(old, root, sources, db)
            client = TeamClient('http://127.0.0.1:' + ready.read_text(), str(uuid.uuid4()), allow_test_http=True)
            actor = client.login('director', 'synthetic rehearsal passphrase')
            service = runtime_services.publish(db)
            plan = service.build_plan({r['id']: [actor['accountId']] for r in db.list_archived_scores()})
            publisher = str(uuid.uuid4())
            receipt = service.publish(plan, client, publisher, confirmed=True)
            assert service.publish(plan, client, publisher, confirmed=True) == receipt
            assert len(receipt['artifactIds']) == 5
            catalog = client.request('GET', '/v2/catalog')['artifacts']
            assert {p['partName'] for p in catalog} == {p.part_name for p in plan.parts}
            assert {p['scoreNumber'] for p in catalog} == {p.number for p in plan.parts}
            for part in catalog:
                request = urllib.request.Request(client.address + '/v2/artifacts/' + part['artifactId'], headers={
                    'Authorization': 'Bearer ' + client.token, 'X-Device-ID': client.device_id})
                with urllib.request.urlopen(request, timeout=15) as response:
                    assert hashlib.sha256(response.read()).hexdigest() == part['hash']
            assert snapshot(old, root, sources, db) == before
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
