import copy
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import threading
import uuid

import pytest

from score_organizer.domain.publication_capabilities import require_expanded_publication
from score_organizer.services.expanded_team_publish import ExpandedPublishService
from score_organizer.services.team_publish import PublishService, TeamClient
from test_expanded_catalog_service import archived
from test_library_upgrade_snapshot import contents, db_state
from test_team_upload_queue import ProtocolClient


def capabilities(team):
    return dict(protocolVersion=2, teamId=team, candidateOnly=False, expandedPublicationReady=True,
                numbering=dict(namingVersions=[1, 2], maximumSequence='9223372036854775807',
                               sequenceEncoding='decimal-string', expandedSeparator='-'))


@pytest.mark.parametrize('field,value', [
    ('protocolVersion', True), ('protocolVersion', '1'), ('protocolVersion', 1),
    ('teamId', 'another-team'), ('expandedPublicationReady', 1), ('expandedPublicationReady', False),
    ('candidateOnly', True), ('candidateOnly', None), ('numbering', None), ('numbering.namingVersions', [True, 2]), ('numbering.namingVersions', [1]),
    ('numbering.namingVersions', [1, 2, 2]), ('numbering.namingVersions', [1, 3]),
    ('numbering.maximumSequence', 9223372036854775807), ('numbering.maximumSequence', '09223372036854775807'),
    ('numbering.maximumSequence', '9223372036854775808'), ('numbering.maximumSequence', '999'),
    ('numbering.maximumSequence', '１０００'), ('numbering.maximumSequence', '1e4'),
    ('numbering.sequenceEncoding', 'number'), ('numbering.expandedSeparator', '_')])
def test_incomplete_ambiguous_or_incompatible_capabilities_are_not_readiness(field, value):
    reply = capabilities('expected-team')
    parent = reply
    keys = field.split('.')
    for key in keys[:-1]:
        parent = parent[key]
    parent[keys[-1]] = value
    with pytest.raises(ValueError, match='未继续上传'):
        require_expanded_publication(reply, 'expected-team', ['0101001', '01-01-1000'])


def test_server_range_is_checked_for_the_entire_selection_using_exact_integers():
    reply = capabilities('team')
    reply['numbering']['maximumSequence'] = '9223372036854775806'
    with pytest.raises(ValueError, match='超过服务'):
        require_expanded_publication(reply, 'team', ['0101001', '01-01-9223372036854775807'])
    reply['numbering']['maximumSequence'] = '9223372036854775807'
    require_expanded_publication(reply, 'team', ['0101001', '01-01-9223372036854775807'])


@pytest.mark.parametrize('team', [None, ''])
def test_missing_team_identity_is_not_a_matching_capability(team):
    with pytest.raises(ValueError, match='未继续上传'):
        require_expanded_publication(capabilities(team), team, ['01-01-1000'])


class CandidateClient(ProtocolClient):
    def __init__(self, admin):
        super().__init__(admin)
        self.calls = []
        self.reply = capabilities(self.principal['teamId'])
        self.after_read = lambda: None
        self.revoke_at = None
        self.reads = 0

    def request(self, method, path, body=None, **kwargs):
        self.calls.append((method, path))
        if method == 'GET':
            assert path == '/v2/admin/capabilities' and body is None and not kwargs
            self.reads += 1
            reply = copy.deepcopy(self.reply)
            if self.reads == self.revoke_at:
                reply['expandedPublicationReady'] = False
            self.after_read()
            return reply
        assert path.startswith('/v2/admin/')
        return super().request(method, path.replace('/v2/', '/v1/', 1), body, **kwargs)


def state(old, old_root, sources, db, root):
    return db_state(old.path), contents(old_root), db_state(db.path), contents(root), {p: p.read_bytes() for p in sources}


@pytest.mark.parametrize('number', ['1000', '10000', '9223372036854775807'])
def test_candidate_preserves_exact_numbers_both_copies_and_sources_on_publish_retry(tmp_path, number, monkeypatch):
    old, old_root, sources, db, root, _, _ = archived(tmp_path, number)
    admin = str(uuid.uuid4())
    client, service = CandidateClient(admin), ExpandedPublishService(db)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores()})
    assert any(p.number == '01-01-' + number for p in plan.parts)
    assert any(p.number == '0101001' for p in plan.parts)
    before = state(old, old_root, sources, db, root)
    monkeypatch.setattr('score_organizer.services.team_publish.MAX_PUBLICATION_ITEMS', 2)
    client.lose_reply = 2
    publisher = str(uuid.uuid4())
    with pytest.raises(ValueError, match='已确认 2/6'):
        service.publish(plan, client, publisher, confirmed=True)
    result = service.publish(plan, client, publisher, confirmed=True)
    assert len(result['artifactIds']) == 6 and len(client.batches) == 3 and client.content_count == 6
    assert {u['spec']['scoreNumber'] for u in client.uploads.values()} == {p.number for p in plan.parts}
    assert all(row['accountIds'] == [admin] for batch in client.batches for row in batch['assignments'])
    assert client.calls[0] == ('GET', '/v2/admin/capabilities')
    assert all(client.calls[i-1] == ('GET', '/v2/admin/capabilities')
               for i, (method, _) in enumerate(client.calls) if method == 'POST')
    assert state(old, old_root, sources, db, root) == before


@pytest.mark.parametrize('point', ['unconfirmed', 'not_admin', 'old_database', 'wrong_root', 'setting', 'changed_pdf', 'range'])
def test_unconfirmed_or_changed_candidate_has_no_remote_writes(tmp_path, point):
    old, old_root, sources, db, root, _, _ = archived(tmp_path, '10000' if point == 'range' else '1000')
    admin = str(uuid.uuid4())
    client, service = CandidateClient(admin), ExpandedPublishService(db)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores()})
    if point == 'not_admin': client.principal['role'] = 'member'
    elif point == 'old_database': service = ExpandedPublishService(old)
    elif point == 'wrong_root': service.root = old_root
    elif point == 'setting': db.set_setting('output_root', str(old_root))
    elif point == 'changed_pdf': plan.parts[0].sources[-1].write_bytes(b'%PDF external change')
    elif point == 'range': client.reply['numbering']['maximumSequence'] = '1000'
    before = state(old, old_root, sources, db, root)
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=point != 'unconfirmed')
    assert not any(method == 'POST' for method, _ in client.calls)
    assert state(old, old_root, sources, db, root) == before


@pytest.mark.parametrize('change', ['account', 'team', 'address', 'role', 'pdf', 'runtime'])
def test_capability_read_cannot_hide_identity_or_local_changes_before_upload(tmp_path, change):
    old, old_root, sources, db, root, _, _ = archived(tmp_path)
    admin = str(uuid.uuid4())
    client, service = CandidateClient(admin), ExpandedPublishService(db)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores()})
    def mutate():
        if change in ('account', 'team'): client.principal[change + 'Id'] = str(uuid.uuid4())
        elif change == 'address': client.address = 'https://different.example.test'
        elif change == 'role': client.principal['role'] = 'member'
        elif change == 'pdf': plan.parts[0].sources[-1].write_bytes(b'%PDF changed during negotiation')
        else: db.set_setting('output_root', str(old_root))
    client.after_read = mutate
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.calls == [('GET', '/v2/admin/capabilities')]


@pytest.mark.parametrize('revoke_at', [2, 3])
def test_capability_revocation_blocks_content_or_publication_without_local_change(tmp_path, revoke_at):
    old, old_root, sources, db, root, _, song = archived(tmp_path)
    admin = str(uuid.uuid4())
    client, service = CandidateClient(admin), ExpandedPublishService(db)
    row = db.list_archived_scores(song)[0]
    plan = service.build_plan({row['id']: [admin]})
    before = state(old, old_root, sources, db, root)
    client.revoke_at = revoke_at
    with pytest.raises(ValueError, match='未继续上传'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.content_count == revoke_at - 2 and not client.receipts
    assert state(old, old_root, sources, db, root) == before


def test_single_total_score_and_default_legacy_service_keep_their_boundaries(tmp_path):
    _, _, _, db, _, _, song = archived(tmp_path, codes=('08',))
    admin = str(uuid.uuid4())
    assignments = {db.list_archived_scores(song)[0]['id']: [admin]}
    plan = ExpandedPublishService(db).build_plan(assignments)
    assert plan.parts[0].number == '08-01-1000' and len(plan.parts[0].sources) == 1
    with pytest.raises(ValueError, match='999'):
        PublishService(db).build_plan(assignments)


@pytest.mark.parametrize('mode', ['ready', 'old404', 'redirect', 'duplicate'])
def test_real_loopback_http_negotiation_before_any_pdf_or_metadata_post(tmp_path, mode):
    old, old_root, sources, db, root, _, song = archived(tmp_path, '9223372036854775807')
    admin, team = str(uuid.uuid4()), str(uuid.uuid4())
    calls, specs, pdfs, receipts = [], {}, {}, {}
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def send_json(self, status, value):
            data = json.dumps(value, ensure_ascii=False).encode('utf-8')
            self.send_response(status)
            if status == 302:
                self.send_header('Location', 'https://never-contact.invalid/')
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        def do_GET(self):
            calls.append(('GET', self.path))
            assert self.path == '/v2/admin/capabilities'
            if mode in ('old404', 'redirect'):
                return self.send_json(404 if mode == 'old404' else 302, {})
            if mode == 'duplicate':
                raw = json.dumps(capabilities(team)).replace('"protocolVersion": 2', '"protocolVersion": 2, "protocolVersion": 1').encode()
                self.send_response(200); self.send_header('Content-Length', str(len(raw))); self.end_headers(); self.wfile.write(raw)
                return
            self.send_json(200, capabilities(team))
        def do_POST(self):
            calls.append(('POST', self.path))
            raw = self.rfile.read(int(self.headers['Content-Length']))
            if self.path == '/v2/admin/uploads':
                spec = json.loads(raw)
                specs[spec['requestId']] = spec
                self.send_json(200, dict(uploadId=spec['requestId'], complete=False))
            elif self.path.endswith('/content'):
                aid = self.path.split('/')[-2]
                assert hashlib.sha256(raw).hexdigest() == specs[aid]['sha256']
                pdfs[aid] = raw
                self.send_json(200, dict(artifactId=aid, hash=specs[aid]['sha256'], size=len(raw)))
            else:
                assert self.path == '/v2/admin/publications'
                request = json.loads(raw)
                assert all(a['artifactId'] in pdfs and a['accountIds'] == [admin] for a in request['assignments'])
                receipt = dict(ok=True, artifactIds=[a['artifactId'] for a in request['assignments']], publicationId=request['requestId'])
                receipts[request['requestId']] = receipt
                self.send_json(200, receipt)
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        client = TeamClient(f'http://127.0.0.1:{server.server_port}', str(uuid.uuid4()), allow_test_http=True)
        client.principal = dict(role='admin', teamId=team, accountId=admin)
        client.token = 'synthetic-test-token'
        service = ExpandedPublishService(db)
        plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores(song)})
        before = state(old, old_root, sources, db, root)
        if mode == 'ready':
            result = service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
            assert len(result['artifactIds']) == 3 and len(receipts) == 1
            assert {s['scoreNumber'] for s in specs.values()} == {f'{code}-01-9223372036854775807' for code in ('01', '02', '03')}
            assert set(pdfs.values()) == {p.sources[0].read_bytes() for p in plan.parts}
            assert all(calls[i-1] == ('GET', '/v2/admin/capabilities') for i,(method,_) in enumerate(calls) if method == 'POST')
        else:
            with pytest.raises(ValueError): service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
            assert calls == [('GET', '/v2/admin/capabilities')] and not specs and not pdfs and not receipts
        assert state(old, old_root, sources, db, root) == before
    finally:
        server.shutdown(); server.server_close(); thread.join(timeout=5)
