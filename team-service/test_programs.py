import hashlib
import io
import uuid
from pathlib import Path
from pypdf import PdfWriter
from server import Application
from test_accounts import AccountFixture


class ProgramsTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.app = Application(self.service)
        data = io.BytesIO()
        writer = PdfWriter(); writer.add_blank_page(595, 842); writer.write(data)
        self.pdf = data.getvalue()

    def upload(self, number, part, accounts, title='Synthetic score'):
        body = dict(requestId=str(uuid.uuid4()), title=title, partName=part, scoreNumber=number, category='01 Category',
            sha256=hashlib.sha256(self.pdf).hexdigest(), size=len(self.pdf), independentSource=True)
        publisher = self.app.publisher
        uid = publisher.prepare(body, self.admin['token'], self.device)['uploadId']
        publisher.upload(uid, io.BytesIO(self.pdf), len(self.pdf), self.admin['token'], self.device)
        publisher.publish(dict(requestId=str(uuid.uuid4()), assignments=[dict(artifactId=uid, accountIds=accounts)]), self.admin['token'], self.device)
        return uid

    def call_program(self, path='/v1/admin/ensemble', body=None, login=None):
        return self.app.programs.dispatch('POST' if body is not None else 'GET', path, body, (login or self.admin)['token'], self.device)

    def catalog(self, login):
        return self.app.catalog.dispatch('GET', '/v1/catalog', None, login['token'], self.device)

    def program(self, **changes):
        return dict(dict(id=str(uuid.uuid4()), title='Programme', pieceKeys=['sequence:001'], accountIds=None, archived=False, expectedRevision=0), **changes)

    def test_one_programme_choice_covers_all_parts_and_members_only_receive_their_grants(self):
        violin, cello, outsider = self.member(), self.member('cellist'), self.member('outsider')
        self.upload('0101001', 'Violin', [self.admin_id, violin['principal']['accountId']])
        self.upload('0201001', 'Cello', [self.admin_id, cello['principal']['accountId']])
        groups = self.call_program()['groups']
        self.assertEqual(1, len(groups)); self.assertEqual(2, len(groups[0]['parts']))
        body = self.program()
        self.call_program('/v1/admin/programs', body)
        for login in (violin, cello):
            manifest = self.catalog(login)
            self.assertEqual(1, len(manifest['artifacts'])); self.assertEqual(['sequence:001'], manifest['programs'][0]['pieceKeys'])
        self.assertEqual([], self.catalog(outsider)['programs'])
        # New parts attach by exact global number, never by a repeated title.
        self.upload('0301001', 'Bass', [self.admin_id])
        self.upload('0101002', 'Violin', [self.admin_id], title='Synthetic score')
        groups = self.call_program()['groups']
        self.assertEqual([3, 1], [len(g['parts']) for g in groups])
        self.assertEqual(['sequence:001'], self.catalog(self.admin)['programs'][0]['pieceKeys'])

    def test_remove_and_restore_preserve_pdf_ink_grants_and_idempotence(self):
        uid = self.upload('0101001', 'Violin', [self.admin_id])
        path = Path(self.tmp.name) / 'artifacts' / (uid + '.pdf')
        before = path.read_bytes()
        state = self.call_program()
        body = dict(requestId=str(uuid.uuid4()), artifactIds=[uid], removed=True, expectedRevision=state['revision'])
        self.call_program('/v1/admin/catalog-actions', body)
        self.call_program('/v1/admin/catalog-actions', body)
        self.assertEqual([], self.catalog(self.admin)['artifacts'])
        self.assertTrue(self.call_program()['groups'][0]['removed']); self.assertEqual(before, path.read_bytes())
        restore = dict(body, requestId=str(uuid.uuid4()), removed=False, expectedRevision=self.call_program()['revision'])
        self.call_program('/v1/admin/catalog-actions', restore)
        self.assertEqual(uid, self.catalog(self.admin)['artifacts'][0]['artifactId'])
        self.assertEqual(before, path.read_bytes())
        # An old successful remove retry cannot remove a restored score again.
        self.call_program('/v1/admin/catalog-actions', body)
        self.assertEqual(1, len(self.catalog(self.admin)['artifacts']))

    def test_programme_audience_delete_restore_and_stale_edit(self):
        member, other = self.member(), self.member('other')
        self.upload('0101001', 'Violin', [self.admin_id, member['principal']['accountId'], other['principal']['accountId']])
        body = self.program(accountIds=[member['principal']['accountId']])
        self.call_program('/v1/admin/programs', body)
        self.assertEqual(1, len(self.catalog(member)['programs'])); self.assertEqual([], self.catalog(other)['programs'])
        self.rejected(409, lambda: self.call_program('/v1/admin/programs', dict(body, title='Stale edit')))
        deleted = dict(body, archived=True, expectedRevision=1)
        self.call_program('/v1/admin/programs', deleted); self.call_program('/v1/admin/programs', deleted)
        self.assertEqual([], self.catalog(member)['programs']); self.assertEqual(1, len(self.catalog(member)['artifacts']))
        self.call_program('/v1/admin/programs', dict(body, expectedRevision=2))
        self.assertEqual(1, len(self.catalog(member)['programs']))

    def test_member_denied_and_missing_score_rejected(self):
        member = self.member()
        self.rejected(403, lambda: self.call_program(login=member))
        self.rejected(403, lambda: self.call_program('/v1/admin/programs', self.program(), member))
        self.rejected(409, lambda: self.call_program('/v1/admin/programs', self.program()))
        self.assertEqual([], self.call_program()['programs'])
