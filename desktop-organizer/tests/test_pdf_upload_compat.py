from dataclasses import replace
import hashlib
from pathlib import Path
import uuid

import pytest
from pypdf import PdfReader, PdfWriter
from pypdf.generic import ArrayObject, DictionaryObject, NameObject, NumberObject, RectangleObject, DecodedStreamObject, TextStringObject
from PySide6.QtWidgets import QApplication, QMessageBox

from score_organizer.services import runtime_services
from score_organizer.services.import_service import sha256_file
from score_organizer.services.pdf_upload_compat import CopyStorageError, PdfInputError, UploadPdfWorkspace, page_signatures, upload_report
from score_organizer.services.team_publish import TeamRequestError
from score_organizer.services.team_publish import TeamClient
from score_organizer.ui.team_publish_dialog import TeamPublishDialog
from test_combined_pdf_upload import mixed_library, snapshot, wait_worker
from test_expanded_team_publish import CandidateClient
from test_team_upload_queue import ProtocolClient


def write_pdf(path, *, box=(0, 0, 595, 842), encrypted=False, active=False, prefix=b''):
    writer = PdfWriter()
    page = writer.add_blank_page(595, 842)
    stream = DecodedStreamObject()
    stream.set_data(b'q 0 0 0 rg 10 10 30 30 re f Q')
    page[NameObject('/Contents')] = writer._add_object(stream)
    page[NameObject('/MediaBox')] = RectangleObject(box)
    page[NameObject('/Rotate')] = NumberObject(90)
    writer.add_metadata({'/Title': 'Synthetic PDF compatibility'})
    if active:
        writer.root_object[NameObject('/OpenAction')] = DictionaryObject()
    if encrypted:
        writer.encrypt('synthetic private passphrase')
    with path.open('wb') as output:
        writer.write(output)
    if prefix:
        path.write_bytes(prefix + path.read_bytes())
    return path


@pytest.mark.parametrize('box', [(0,842,595,0), (595,0,0,842), (595,842,0,0), (-100,792,495,-50)])
def test_reversed_page_boxes_get_separate_deterministic_copies_with_content_and_rotation_preserved(tmp_path, box):
    source = write_pdf(tmp_path/'original.pdf', box=box)
    original = source.read_bytes()
    digest = sha256_file(source)
    original_pages = page_signatures(PdfReader(source, strict=True))
    first, second = UploadPdfWorkspace(), UploadPdfWorkspace()
    try:
        one, two = first.prepare(source, digest), second.prepare(source, digest)
        assert one.path != source and two.path != one.path
        assert one.digest == two.digest != digest and one.explanation
        reader = PdfReader(one.path, strict=True)
        assert reader.pages[0].mediabox.width > 0 and reader.pages[0].mediabox.height > 0
        assert page_signatures(reader) == original_pages
        assert first.prepare(source, digest) == one
        assert source.read_bytes() == original
        assert one.path.read_bytes() == two.path.read_bytes()
    finally:
        first.close()
        second.close()
    assert source.read_bytes() == original and not one.path.exists()


def test_normal_pdf_keeps_original_bytes_and_leading_header_gets_traceable_repack(tmp_path):
    workspace = UploadPdfWorkspace()
    ordinary = write_pdf(tmp_path/'ordinary.pdf')
    prefixed = write_pdf(tmp_path/'prefixed.pdf', prefix=b'\xef\xbb\xbf\n')
    try:
        normal = workspace.prepare(ordinary, sha256_file(ordinary))
        rebuilt = workspace.prepare(prefixed, sha256_file(prefixed))
        assert normal.path == ordinary and normal.digest == sha256_file(ordinary) and not normal.explanation
        assert rebuilt.path != prefixed and rebuilt.path.read_bytes().startswith(b'%PDF-')
        assert rebuilt.explanation and prefixed.read_bytes().startswith(b'\xef\xbb\xbf')
    finally:
        workspace.close()


def test_nonempty_stream_and_indirect_annotation_content_survive_repack(tmp_path):
    source = write_pdf(tmp_path/'annotated.pdf', box=(0,842,595,0))
    reader = PdfReader(source);writer = PdfWriter();writer.clone_document_from_reader(reader)
    annotation = DictionaryObject({NameObject('/Type'):NameObject('/Annot'),NameObject('/Subtype'):NameObject('/Text'),
        NameObject('/Rect'):RectangleObject((10,20,30,40)),NameObject('/Contents'):TextStringObject('保持批注文字')})
    writer.pages[0][NameObject('/Annots')] = writer._add_object(ArrayObject([writer._add_object(annotation)]))
    with source.open('wb') as stream:writer.write(stream)
    original = source.read_bytes();workspace = UploadPdfWorkspace()
    try:
        before = PdfReader(source);known = b'q 0 0 0 rg 10 10 30 30 re f Q'
        assert before.pages[0].get_contents().get_data() == known
        signature = page_signatures(before)[0]
        assert signature[0] == hashlib.sha256(known).hexdigest() != hashlib.sha256(b'').hexdigest()
        assert signature[-1] == 1
        copy = workspace.prepare(source, sha256_file(source));after = PdfReader(copy.path)
        assert after.pages[0].get_contents().get_data() == known
        assert after.pages[0]['/Annots'][0].get_object()['/Contents'] == '保持批注文字'
        assert tuple(after.pages[0]['/Annots'][0].get_object()['/Rect']) == (10,20,30,40)
        assert source.read_bytes() == original
    finally:workspace.close()


def test_partial_disk_failure_cleans_copy_and_same_workspace_can_retry(tmp_path, monkeypatch):
    source = write_pdf(tmp_path/'reverse.pdf', box=(0,842,595,0));original = source.read_bytes()
    workspace = UploadPdfWorkspace();digest = sha256_file(source)
    def interrupted(self, output):output.write(b'partial');raise OSError('Synthetic disk full')
    try:
        with monkeypatch.context() as patch:
            patch.setattr(PdfWriter,'write',interrupted)
            with pytest.raises(CopyStorageError):workspace.prepare(source,digest)
        assert list(Path(workspace._temporary.name).iterdir()) == []
        assert workspace.prepare(source,digest).path.is_file()
        assert source.read_bytes() == original
    finally:workspace.close()


def test_preflight_reads_batch_metadata_once_and_blocks_integrity_or_recipient_errors(tmp_path, monkeypatch):
    old,root,sources,db = mixed_library(tmp_path,False);service = runtime_services.publish(db)
    rows = db.list_archived_scores();assignments = {r['id']:[str(uuid.uuid4())] for r in rows}
    calls = [];read = db.list_archived_scores
    monkeypatch.setattr(db,'list_archived_scores',lambda *args,**kwargs: (calls.append(1),read(*args,**kwargs))[1])
    plan = service.prepare_plan(assignments);assert len(plan.parts)==5 and len(calls)==1
    with pytest.raises(ValueError,match='接收'):service.prepare_plan({rows[0]['id']:[]})
    plan.parts[0].sources[0].write_bytes(b'Synthetic changed archived copy')
    with pytest.raises(ValueError,match='库内副本'):service.prepare_plan(assignments)
    service.close()


def test_failed_copy_cleanup_keeps_dialog_open_for_retry(tmp_path,monkeypatch):
    app=QApplication.instance() or QApplication([])
    old,root,sources,db=mixed_library(tmp_path,False);dialog=TeamPublishDialog(db);dialog.show();app.processEvents()
    close=dialog.service.close
    monkeypatch.setattr(dialog.service,'close',lambda: (_ for _ in ()).throw(OSError('Synthetic permission denied')))
    dialog.reject();assert dialog.isVisible() and '清理失败' in dialog.status.text()
    monkeypatch.setattr(dialog.service,'close',close);dialog.reject();assert not dialog.isVisible()


@pytest.mark.parametrize('code,visible',[('invalid_page_size',True),('synthetic-secret-do-not-echo',False)])
def test_real_http_errors_only_display_whitelisted_codes(code,visible):
    from http.server import BaseHTTPRequestHandler,HTTPServer
    import json,threading
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            raw=json.dumps(dict(error=code,message='synthetic-password-do-not-echo')).encode()
            self.send_response(400);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def log_message(self,*args):pass
    server=HTTPServer(('127.0.0.1',0),Handler);worker=threading.Thread(target=server.serve_forever,daemon=True);worker.start()
    try:
        client=TeamClient(f'http://127.0.0.1:{server.server_port}',str(uuid.uuid4()),allow_test_http=True)
        with pytest.raises(TeamRequestError) as caught:client.request('GET','/v1/me')
        message=str(caught.value);assert (code in message)==visible
        assert 'synthetic-password-do-not-echo' not in message
    finally:server.shutdown();server.server_close();worker.join(5)


def test_report_saves_utf8_without_modifying_the_library(tmp_path,monkeypatch):
    from PySide6.QtWidgets import QDialog,QFileDialog,QPushButton
    app=QApplication.instance() or QApplication([])
    old,root,sources,db=mixed_library(tmp_path,False)
    dialog=TeamPublishDialog(db);before=snapshot(old,root,sources,db)
    dialog.report_text='原件哈希与兼容副本哈希分别记录\n跳过：加密分谱'
    target=tmp_path/'报告.txt'
    monkeypatch.setattr(QFileDialog,'getSaveFileName',lambda *a,**k:(str(target),'txt'))
    def save(report):
        next(button for button in report.findChildren(QPushButton) if button.text()=='保存报告…').click()
        return 0
    monkeypatch.setattr(QDialog,'exec',save);dialog.show_report()
    assert target.read_text('utf-8')==dialog.report_text
    assert snapshot(old,root,sources,db)==before
    dialog.reject()


@pytest.mark.parametrize('kind', ['encrypted', 'active', 'zero_width', 'zero_height', 'not_pdf', 'broken'])
def test_unsupported_or_unreliable_pdfs_are_reported_without_mutating_originals(tmp_path, kind):
    source = tmp_path/'problem.pdf'
    if kind in ('not_pdf', 'broken'):
        source.write_bytes(b'not a pdf' if kind == 'not_pdf' else b'%PDF-1.4\ntruncated')
    else:
        box = (0,0,0,842) if kind == 'zero_width' else (0,0,595,0) if kind == 'zero_height' else (0,0,595,842)
        write_pdf(source, box=box, encrypted=kind=='encrypted', active=kind=='active')
    before = source.read_bytes()
    workspace = UploadPdfWorkspace()
    try:
        with pytest.raises(PdfInputError):
            workspace.prepare(source, sha256_file(source))
        assert source.read_bytes() == before
    finally:
        workspace.close()


def test_changed_cache_and_disk_failure_do_not_silently_skip_compatibility_copy(tmp_path, monkeypatch):
    source = write_pdf(tmp_path/'reverse.pdf', box=(0,842,595,0))
    workspace = UploadPdfWorkspace()
    digest = sha256_file(source)
    try:
        copied = workspace.prepare(source, digest)
        copied.path.write_bytes(b'tampered')
        with pytest.raises(CopyStorageError, match='已变化'):
            workspace.prepare(source, digest)
    finally:
        workspace.close()
    workspace = UploadPdfWorkspace()
    monkeypatch.setattr(PdfWriter, 'write', lambda *a, **k: (_ for _ in ()).throw(OSError('synthetic disk full')))
    try:
        with pytest.raises(CopyStorageError, match='写入失败'):
            workspace.prepare(source, digest)
    finally:
        workspace.close()


def rewrite_archived_pdf(db, source, transform):
    """Create a synthetic already-archived variant with consistent library hashes."""
    row = next(row for row in db.list_archived_scores() if row['instrument_code'] == '09')
    archive = runtime_services.archive(db, db.get_setting('output_root'))
    path = archive.score_paths(row)[0]
    transform(path)
    with db.session() as connection:
        connection.execute('UPDATE score SET source_sha256=? WHERE id=?', (sha256_file(path), row['id']))
    return row['id']


@pytest.mark.parametrize('expanded', [False, True])
def test_preflight_skips_bad_pdf_keeps_others_and_never_changes_library(tmp_path, expanded):
    old, root, sources, db = mixed_library(tmp_path, expanded)
    bad = rewrite_archived_pdf(db, sources[-1], lambda path: path.write_bytes(b'not a pdf'))
    service = runtime_services.publish(db)
    before = snapshot(old, root, sources, db)
    admin = str(uuid.uuid4())
    plan = service.prepare_plan({r['id']: [admin] for r in db.list_archived_scores()})
    assert len(plan.parts) == 4 and len(plan.skipped) == 1 and plan.skipped[0].score_id == bad
    assert '不是可识别' in plan.skipped[0].reason
    client = CandidateClient(admin) if expanded else ProtocolClient(admin)
    receipt = service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert len(receipt['artifactIds']) == 4 and receipt['skipped'] == plan.skipped
    assert snapshot(old, root, sources, db) == before
    assert '服务器已确认 4' in upload_report(plan, receipt) and '跳过' in upload_report(plan, receipt)
    service.close()


@pytest.mark.parametrize('expanded', [False, True])
def test_prepared_upload_sends_compatible_hash_and_rechecks_original_and_copy(tmp_path, expanded):
    old, root, sources, db = mixed_library(tmp_path, expanded)
    row_id = rewrite_archived_pdf(db, sources[-1], lambda path: write_pdf(path, box=(0,842,595,0)))
    service = runtime_services.publish(db)
    before = snapshot(old, root, sources, db)
    admin = str(uuid.uuid4())
    plan = service.prepare_plan({r['id']: [admin] for r in db.list_archived_scores()})
    assert len(plan.parts) == 5 and not plan.skipped
    part = next(part for part in plan.parts if part.score_id == row_id)
    assert part.effective_digest != part.digest and part.upload.explanation
    client = CandidateClient(admin) if expanded else ProtocolClient(admin)
    publisher = str(uuid.uuid4())
    receipt = service.publish(plan, client, publisher, confirmed=True)
    assert service.publish(plan, client, publisher, confirmed=True) == receipt
    assert len(receipt['artifactIds']) == 5
    assert any(row['spec']['sha256'] == part.effective_digest for row in client.uploads.values())
    assert snapshot(old, root, sources, db) == before
    part.effective_path.write_bytes(b'changed upload copy')
    previous = client.content_count
    with pytest.raises(ValueError, match='副本已变化'):
        service.publish(plan, client, publisher, confirmed=True)
    assert client.content_count == previous
    service.close()


@pytest.mark.parametrize('status', [400, 413, 415, 401, 403, 409, 500])
def test_only_file_specific_server_rejections_continue_without_expanding_recipients(tmp_path, status):
    old, root, sources, db = mixed_library(tmp_path, False)
    service = runtime_services.publish(db)
    admin = str(uuid.uuid4())
    plan = service.prepare_plan({r['id']: [admin] for r in db.list_archived_scores()})
    class Client(ProtocolClient):
        def request(self, method, path, body=None, **kwargs):
            if path.endswith('/content') and self.content_count == 1 and not getattr(self, 'rejected', False):
                self.rejected = True
                raise TeamRequestError(status, f'synthetic HTTP {status}')
            return super().request(method, path, body, **kwargs)
    client = Client(admin)
    if status in (400,413,415):
        receipt = service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
        assert len(receipt['artifactIds']) == 4 and len(receipt['skipped']) == 1
        assert all(a['accountIds'] == [admin] for b in client.batches for a in b['assignments'])
    else:
        with pytest.raises(TeamRequestError):
            service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
        assert not client.batches
    service.close()


def test_all_bad_preview_cannot_be_confirmed_but_has_report(tmp_path):
    app = QApplication.instance() or QApplication([])
    old, root, sources, db = mixed_library(tmp_path, False)
    archive = runtime_services.archive(db, db.get_setting('output_root'))
    with db.session() as connection:
        for row in db.list_archived_scores():
            for path in archive.score_paths(row):
                path.write_bytes(b'bad pdf')
            connection.execute('UPDATE score SET source_sha256=? WHERE id=?', (hashlib.sha256(b'bad pdf').hexdigest(), row['id']))
    dialog = TeamPublishDialog(db)
    admin = str(uuid.uuid4())
    client = ProtocolClient(admin)
    client.token = 'synthetic'
    client.principal['teamName'] = '合成乐团'
    dialog.connected((client, []))
    dialog.scores.selectAll()
    dialog.make_preview()
    wait_worker(app, dialog)
    assert not dialog.plan.parts and len(dialog.plan.skipped) == 5
    assert dialog.report_button.isEnabled() and not dialog.confirm.isEnabled()
    dialog.confirm.setChecked(True)
    assert not dialog.publish_button.isEnabled()
    dialog.client = None
    dialog.reject()
