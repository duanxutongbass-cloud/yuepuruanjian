import hashlib
import io
import json
from pathlib import Path
import threading
import uuid

import pytest
from pypdf import PdfWriter
from waitress import create_server

from score_numbers import capabilities, current_reader, parse_number, require_current_writer
from server import Application
from service import Rejected
from test_accounts import AccountFixture, PASSWORD
import test_publishing as publication_tests


@pytest.mark.parametrize('value,sequence,version', [
    ('0101001', '001', 1), ('9901999', '999', 1), ('0000001', '001', 1),
    ('01-01-1000', '1000', 2), ('99-99-10000', '10000', 2),
    ('01-02-9223372036854775807', '9223372036854775807', 2)])
def test_canonical_numbers_match_desktop_contract_without_truncation(value, sequence, version):
    from score_organizer.domain.score_naming import parse_score_number
    number = parse_number(value)
    desktop = parse_score_number(value, naming_version=2)
    assert (number.instrument, number.category, number.sequence) == (desktop.instrument_code, desktop.category_code, desktop.seq)
    assert number.sequence == sequence and number.version == version
    if version == 1:
        assert require_current_writer(value) == current_reader(value) == number
    else:
        with pytest.raises(Rejected) as caught: require_current_writer(value)
        assert caught.value.status == 426


@pytest.mark.parametrize('value', ['0101000', '01011000', '01-01-001', '01-01-01000', '01_01_1000',
    '01-01_1000', '01-01-9223372036854775808', '01-01-99999999999999999999',
    '０１０１００１', '01-01-１０００', True, None, 101001, 101001.0, '', ' 0101001', '0101001\n',
    '01-01-1e3', '01-01-1000-title.pdf'])
def test_ambiguous_noncanonical_or_overflowed_numbers_are_not_reinterpreted(value):
    with pytest.raises(Rejected) as caught: parse_number(value)
    assert caught.value.status == 400


@pytest.mark.parametrize('value', [None, ''])
def test_old_operator_pdf_without_number_remains_readable(value):
    assert current_reader(value) is None


class NumberingCapabilitiesTest(AccountFixture):
    prepare = publication_tests.PublishingTest.prepare
    upload = publication_tests.PublishingTest.upload
    publication = publication_tests.PublishingTest.publication
    publish = publication_tests.PublishingTest.publish
    catalog = publication_tests.PublishingTest.catalog

    def setUp(self):
        super().setUp()
        self.app = Application(self.service)
        self.publisher = self.app.publisher
        output = io.BytesIO()
        pdf = PdfWriter(); pdf.add_blank_page(595, 842); pdf.write(output)
        self.pdf = output.getvalue()
        self.spec = dict(requestId=str(uuid.uuid4()), title='Synthetic score', partName='01 Violin',
            scoreNumber='0101001', category='01 Style', sha256=hashlib.sha256(self.pdf).hexdigest(),
            size=len(self.pdf), independentSource=True)

    def api(self, path, method='GET', body=None, login='admin', **extra):
        raw = json.dumps(body).encode() if body is not None else b''
        actor = self.admin if login == 'admin' else login
        env = dict(REQUEST_METHOD=method, PATH_INFO=path, CONTENT_TYPE='application/json', CONTENT_LENGTH=str(len(raw)),
                   HTTP_AUTHORIZATION='Bearer '+actor['token'] if actor else '', HTTP_X_DEVICE_ID=self.device)
        env.update({'wsgi.url_scheme':'https', 'wsgi.input':io.BytesIO(raw)})
        env.update(extra)
        status, headers = [], []
        response = b''.join(self.app(env, lambda s,h:(status.append(s), headers.extend(h))))
        return int(status[0].split()[0]), json.loads(response), dict(headers)

    def state(self):
        with self.service.connection() as db:
            return tuple(db.iterdump())

    def mutate_upload(self, uid, number):
        with self.service.connection() as db:
            spec = json.loads(db.execute('SELECT spec FROM uploads WHERE id=?', (uid,)).fetchone()[0])
            spec['scoreNumber'] = number
            db.execute('UPDATE uploads SET spec=? WHERE id=?', (json.dumps(spec), uid))

    def mutate_number(self, uid, number):
        with self.service.connection() as db:
            db.execute('UPDATE artifact_metadata SET number=? WHERE artifact=?', (number, uid))

    def test_capabilities_are_admin_only_read_only_truthful_and_keep_tls_rules(self):
        player = self.member()
        before = self.state()
        for _ in range(2):
            status, result, headers = self.api('/v1/admin/capabilities')
            self.assertEqual(200, status)
            self.assertEqual(self.admin['principal']['teamId'], result['teamId'])
            self.assertIs(False, result['expandedPublicationReady'])
            self.assertEqual([1], result['numbering']['namingVersions'])
            self.assertEqual('999', result['numbering']['maximumSequence'])
            self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual(before, self.state())
        self.assertEqual(401, self.api('/v1/admin/capabilities', login=None)[0])
        self.assertEqual(403, self.api('/v1/admin/capabilities', login=player)[0])
        self.assertEqual(405, self.api('/v1/admin/capabilities', 'POST', {})[0])
        self.assertEqual(403, self.api('/v1/admin/capabilities', **{'wsgi.url_scheme':'http'})[0])
        self.assertEqual(403, self.api('/v1/admin/capabilities', HTTP_X_ROLE='admin')[0])
        self.assertEqual(403, self.api('/v1/admin/capabilities', HTTP_ORIGIN='https://foreign.example')[0])
        self.now[0] = self.admin['expiresAt']
        self.assertEqual(401, self.api('/v1/admin/capabilities')[0])

    def test_invalid_and_expanded_new_uploads_make_no_rows_or_catalog_changes(self):
        before = self.state()
        for value, status in [('0101000',400), ('01011000',400), ('01_01_1000',400),
                              ('01-01-01000',400), ('01-01-9223372036854775808',400),
                              ('01-01-1000',426), ('01-01-9223372036854775807',426)]:
            with self.subTest(value=value):
                self.rejected(status, lambda:self.prepare(requestId=str(uuid.uuid4()), scoreNumber=value))
                self.assertEqual(before, self.state())
        self.assertEqual([], list(self.app.catalog.root.iterdir()))
        status, _, _ = self.api('/v1/admin/uploads', 'POST', dict(self.spec, scoreNumber='01-01-1000'),
                                HTTP_X_SCORE_NAMING_VERSION='2')
        self.assertEqual(426, status)
        self.assertEqual(before, self.state())

    def test_historical_queued_expanded_number_is_rejected_before_reading_pdf(self):
        uid = self.prepare()['uploadId']
        self.mutate_upload(uid, '01-01-1000')
        before = self.state()
        class Unreadable:
            def read(self, size): raise AssertionError('must not read unsupported upload')
        self.rejected(426, lambda:self.publisher.upload(uid, Unreadable(), len(self.pdf), self.admin['token'], self.device))
        self.assertEqual(before, self.state())
        self.assertEqual([], list(self.app.catalog.root.iterdir()))

    def test_number_changed_during_pdf_transfer_cannot_commit_or_leave_artifact(self):
        uid = self.prepare()['uploadId']
        test = self
        class Changing(io.BytesIO):
            def read(stream, size=-1):
                test.mutate_upload(uid, '01-01-1000')
                return super().read(size)
        self.rejected(426, lambda:self.publisher.upload(uid, Changing(self.pdf), len(self.pdf), self.admin['token'], self.device))
        with self.service.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM artifacts').fetchone()[0])
        self.assertEqual([], list(self.app.catalog.root.iterdir()))

    def test_old_staged_expanded_metadata_cannot_be_published_or_granted_by_other_routes(self):
        first = self.prepare()['uploadId']; self.upload(first)
        second = self.prepare(requestId=str(uuid.uuid4()), scoreNumber='0201001', partName='02 Viola')['uploadId']
        self.upload(second); self.mutate_number(second, '02-01-1000')
        before = self.state()
        self.rejected(426, lambda:self.publish(self.publication([first,second], [self.admin_id])))
        self.assertEqual(before, self.state())
        self.assertEqual(426, self.api('/v1/admin/artifacts/publish', 'POST', dict(artifactId=second,published=True))[0])
        self.assertEqual(426, self.api('/v1/admin/grants', 'POST', dict(accountId=self.admin_id,artifactId=second,allowed=True))[0])
        with self.service.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM artifacts WHERE published=1').fetchone()[0])
            self.assertEqual(0, db.execute('SELECT count(*) FROM artifact_grants').fetchone()[0])
        self.assertEqual(self.pdf, (self.app.catalog.root/(second+'.pdf')).read_bytes())

    def test_legacy_readers_receive_upgrade_error_without_metadata_truncation_or_pdf_bytes(self):
        player, outsider = self.member(), self.member('outsider')
        uid = self.prepare()['uploadId']; self.upload(uid)
        self.publish(self.publication([uid], [self.admin_id,player['principal']['accountId']]))
        self.mutate_number(uid, '01-01-1000')
        before = self.state()
        for path in ('/v1/catalog','/v1/admin/ensemble','/v1/admin/artifacts','/v1/artifacts/'+uid):
            status, body, _ = self.api(path)
            self.assertEqual(426, status)
            self.assertEqual({'error':'reader_numbering_upgrade_required'}, body)
        self.assertEqual(426, self.api('/v1/catalog', login=player)[0])
        self.assertEqual([], self.api('/v1/catalog', login=outsider)[1]['artifacts'])
        self.assertEqual(before, self.state())
        self.assertEqual(self.pdf, (self.app.catalog.root/(uid+'.pdf')).read_bytes())
        from backup_archive import create_archive, restore_archive
        from service import TeamService
        archive = Path(self.tmp.name)/'private-numbering-backup.zip'
        create_archive(self.service, archive)
        restored_path = Path(self.tmp.name)/'independent-restored'
        self.assertEqual(1, restore_archive(archive, restored_path)['artifacts'])
        restored = TeamService(restored_path)
        with restored.connection() as db:
            self.assertEqual('01-01-1000', db.execute('SELECT number FROM artifact_metadata WHERE artifact=?',(uid,)).fetchone()[0])
        self.assertEqual(self.pdf, (restored_path/'artifacts'/(uid+'.pdf')).read_bytes())
        self.assertEqual(200, self.api('/v1/admin/grants','POST',dict(accountId=player['principal']['accountId'],artifactId=uid,allowed=False))[0])
        self.assertEqual(200, self.api('/v1/admin/artifacts/publish','POST',dict(artifactId=uid,published=False))[0])
        with self.service.connection() as db:
            self.assertEqual('01-01-1000', db.execute('SELECT number FROM artifact_metadata WHERE artifact=?',(uid,)).fetchone()[0])

    def test_old_publication_receipt_retry_never_regrants_after_metadata_changes(self):
        player = self.member()
        uid = self.prepare()['uploadId']; self.upload(uid)
        body = self.publication([uid], [player['principal']['accountId']])
        receipt = self.publish(body)
        self.mutate_number(uid, '01-01-1000')
        self.api('/v1/admin/grants','POST',dict(accountId=player['principal']['accountId'],artifactId=uid,allowed=False))
        self.assertEqual(receipt, self.publish(body))
        self.assertEqual([], self.catalog(player))

    def test_old_freeze_projection_rejects_unsupported_number_even_with_valid_checksum(self):
        uid = self.prepare()['uploadId']; self.upload(uid)
        self.publish(self.publication([uid], [self.admin_id]))
        program = dict(id=str(uuid.uuid4()), title='Synthetic concert', pieceKeys=['sequence:001'],
                       accountIds=None, archived=False, expectedRevision=0)
        self.app.programs.dispatch('POST','/v1/admin/programs',program,self.admin['token'],self.device)
        frozen = self.app.program_freezes.dispatch('POST','/v1/admin/program-freezes',
            dict(programId=program['id'],requestId=str(uuid.uuid4()),expectedRevision=1),self.admin['token'],self.device)
        with self.service.connection() as db:
            row = db.execute('SELECT * FROM program_freezes WHERE program=?',(program['id'],)).fetchone()
            manifest, pages = json.loads(row['manifest']), json.loads(row['pages'])
            manifest['artifacts'][0]['scoreNumber'] = '01-01-1000'
            manifest['artifacts'][0]['pieceKey'] = 'sequence:1000'
            manifest['pieceKeys'] = ['sequence:1000']
            from publishing import canonical
            checksum = hashlib.sha256(canonical(dict(**manifest,pages=pages)).encode()).hexdigest()
            db.execute('UPDATE program_freezes SET manifest=?,sha256=? WHERE program=?',
                       (canonical(manifest),checksum,program['id']))
        before = self.state()
        for path in ('/v1/catalog',f"/v1/program-freezes/{program['id']}/{frozen['freezeId']}"):
            status, body, _ = self.api(path)
            self.assertEqual(426, status)
            self.assertEqual({'error':'reader_numbering_upgrade_required'}, body)
        self.assertEqual(before, self.state())
        self.assertEqual(self.pdf, (self.app.catalog.root/(uid+'.pdf')).read_bytes())

    def test_real_http_expanded_desktop_candidate_stops_at_truthful_v1_capabilities(self):
        from score_organizer.services.expanded_team_publish import ExpandedPublishService
        from score_organizer.services.team_publish import TeamClient
        from test_expanded_catalog_service import archived
        root = Path(self.tmp.name)/'candidate-test'; root.mkdir()
        _, _, _, db, _, _, _ = archived(root, '10000')
        service = ExpandedPublishService(db)
        plan = service.build_plan({row['id']:[self.admin_id] for row in db.list_archived_scores()})
        posts = []
        app = Application(self.service, allow_loopback_http=True)
        def observed(env, start):
            if env['REQUEST_METHOD'] == 'POST' and env['PATH_INFO'].startswith(('/v1/admin/', '/v2/admin/')):
                posts.append(env['PATH_INFO'])
            return app(env, start)
        server = create_server(observed, host='127.0.0.1', port=0, threads=2)
        thread = threading.Thread(target=server.run, daemon=True); thread.start()
        client = TeamClient(f'http://127.0.0.1:{server.effective_port}', str(uuid.uuid4()), allow_test_http=True)
        try:
            client.login('director', PASSWORD)
            before = self.state()
            with self.assertRaisesRegex(ValueError, 'HTTP 404'):
                service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
            self.assertEqual([], posts)
            self.assertEqual(before, self.state())
        finally:
            server.task_dispatcher.shutdown(); server.close(); thread.join(5)


def test_deployment_copy_list_includes_the_numbering_gate_module():
    dockerfile = (Path(__file__).parent/'Dockerfile').read_text('utf-8')
    assert 'score_numbers.py' in dockerfile.split('USER 10001:10001')[0]
