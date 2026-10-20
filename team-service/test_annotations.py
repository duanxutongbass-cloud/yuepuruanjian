import concurrent.futures
import copy
import io
import json
import uuid
from annotations import Annotations, AnnotationReply
from catalog import Catalog
from server import Application
from test_accounts import AccountFixture


class AnnotationTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.catalog = Catalog(self.service)
        self.api = Annotations(self.service)
        self.listener = self.member()
        self.artifact = str(uuid.uuid4())
        self.hash = 'a' * 64
        with self.service.connection() as db:
            db.execute('INSERT INTO artifacts VALUES(?,?,?,?,?,?,1)', (self.artifact, 'Synthetic', 'Violin', self.hash, 1, 2))
            for login in (self.admin, self.listener):
                db.execute('INSERT INTO artifact_grants VALUES(?,?)', (login['principal']['accountId'], self.artifact))
        self.mark = dict(id=str(uuid.uuid4()), kind='PEN', points=[[.2, .3], [.8, .9]], color=0xffd32f2f, width=.003, symbol='f')

    def route(self, kind='personal', body=None, login=None, page=0, device=None, path=None):
        return self.api.dispatch('POST' if body is not None else 'GET', path or f'/v1/annotations/{self.artifact}/{page}/{kind}',
                                 body, (login or self.listener)['token'], device or self.device)

    def body(self, revision=0, marks=None, **extra):
        return dict(protocolVersion=1, requestId=str(uuid.uuid4()), baseRevision=revision, artifactHash=self.hash,
                    marks=[self.mark] if marks is None else marks, **extra)

    def consent_admin(self):
        self.call('/v1/me/backup-consent', dict(accepted=True))

    def test_personal_cas_idempotence_and_durable_conflict(self):
        first = self.body()
        result = self.route(body=first)
        self.assertEqual(1, result.data['revision'])
        self.assertEqual(result, self.route(body=first))
        self.rejected(409, lambda: self.route(body=dict(first, marks=[])))
        stale = self.body(marks=[])
        conflict = self.route(body=stale)
        self.assertEqual(409, conflict.status)
        self.assertEqual([self.mark], conflict.data['current']['marks'])
        self.assertEqual([], conflict.data['candidate'])
        self.assertEqual(conflict, self.route(body=stale))
        self.assertEqual([self.mark], self.route()['marks'])
        self.assertEqual(2, self.route(body=self.body(1, [] )).data['revision'])
        with self.service.connection() as db:
            self.assertEqual(3, db.execute('SELECT count(*) FROM annotation_history').fetchone()[0])
        self.assertEqual(conflict, self.route(body=stale))

    def test_two_devices_have_one_winner_and_keep_loser(self):
        device2 = str(uuid.uuid4()); second = self.login('violinist', device2)
        bodies = [self.body(), self.body(marks=[])]
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda i: self.route(body=bodies[i], login=second if i else self.listener,
                                                       device=device2 if i else self.device), range(2)))
        self.assertEqual([200, 409], sorted(r.status for r in results))
        with self.service.connection() as db:
            self.assertEqual(2, db.execute('SELECT count(*) FROM annotation_operations').fetchone()[0])

    def test_identity_consent_revocation_and_private_retrieval(self):
        self.rejected(403, lambda: self.route(body=self.body(), login=self.admin))
        body = self.body(); self.route(body=body)
        other = self.member('cellist')
        self.rejected(404, lambda: self.route(login=other))
        self.rejected(403, lambda: self.route(path='/v1/admin/annotation-backups', body=dict(
            accountId=self.admin_id, artifactId=self.artifact, pageIndex=0)))
        with self.service.connection() as db:
            db.execute('DELETE FROM artifact_grants WHERE account=?', (self.listener['principal']['accountId'],))
        self.rejected(404, lambda: self.route(body=body))
        saved = self.route(path=f'/v1/annotation-backups/{self.artifact}/0')
        self.assertEqual([self.mark], saved['marks'])
        self.assertEqual(1, len(self.route(path='/v1/annotation-backups')['pages']))
        self.rejected(404, lambda: self.route(login=other, path=f'/v1/annotation-backups/{self.artifact}/0'))

    def test_shared_draft_publish_and_personal_privacy(self):
        self.rejected(403, lambda: self.route('draft'))
        self.route('draft', self.body(), self.admin)
        self.assertEqual([], self.route('published')['marks'])
        publication = dict(protocolVersion=1, requestId=str(uuid.uuid4()), baseRevision=0, artifactHash=self.hash, draftRevision=1, marks=[self.mark])
        self.rejected(403, lambda: self.route('published', publication))
        result = self.route('published', publication, self.admin)
        self.assertEqual([self.mark], result.data['marks'])
        self.assertEqual(result, self.route('published', publication, self.admin))
        self.route('draft', self.body(1, []), self.admin)
        self.assertEqual([self.mark], self.route('published')['marks'])
        self.route(body=self.body())
        body = dict(accountId=self.listener['principal']['accountId'], artifactId=self.artifact, pageIndex=0)
        self.assertEqual([self.mark], self.route(body=body, login=self.admin, path='/v1/admin/annotation-backups')['marks'])
        with self.service.connection() as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM audit WHERE event='annotation_backup_read'").fetchone()[0])
        self.consent_admin()
        self.assertEqual([], self.route(login=self.admin)['marks'])

    def test_strict_ink_page_hash_and_protocol_validation(self):
        invalid = [dict(self.mark, points=[[float('nan'), .2]]), dict(self.mark, color=True),
                   dict(self.mark, width=3), dict(self.mark, symbol='unknown'), dict(self.mark, points=[[1.1, .3]]),
                   dict(self.mark, injected=True), *[dict(self.mark, symbolScale=value) for value in
                       (True, None, '1', .49, 2.01, float('nan'), float('inf'))]]
        for mark in invalid:
            self.rejected(400, lambda: self.route(body=self.body(marks=[mark])))
        self.rejected(400, lambda: self.route(body=self.body(marks=[self.mark, self.mark])))
        self.rejected(404, lambda: self.route(page=2))
        self.rejected(412, lambda: self.route(body=dict(self.body(), artifactHash='b'*64)))
        self.rejected(400, lambda: self.route(body=dict(self.body(), accountId=self.admin_id)))
        self.rejected(400, lambda: self.route(body=dict(self.body(), protocolVersion=2)))
        self.assertEqual(0, self.route()['revision'])

    def test_pressure_roundtrip_cas_history_and_strict_validation(self):
        for pressures in (None, [], [.2], [.2, True], [.2, '0.3'], [.2, float('nan')], [.2, 1.01], [.2, -.01]):
            self.rejected(400, lambda: self.route(body=self.body(marks=[dict(self.mark, pressures=pressures)])))
        self.rejected(400, lambda: self.route(body=self.body(marks=[dict(self.mark, kind='SYMBOL', pressures=[.2, .9])])))
        mark = dict(self.mark, pressures=[.2, .9])
        body = self.body(marks=[mark])
        saved = self.route(body=body)
        self.assertEqual([mark], saved.data['marks'])
        self.assertEqual([mark], self.route()['marks'])
        self.assertEqual(saved, self.route(body=body))
        conflict = self.route(body=self.body(marks=[]))
        self.assertEqual(409, conflict.status)
        self.assertEqual([mark], conflict.data['current']['marks'])
        with self.service.connection() as db:
            stored = json.loads(db.execute('SELECT payload FROM annotation_history ORDER BY id LIMIT 1').fetchone()[0])
        self.assertEqual([mark], stored)

    def test_old_clients_cannot_strip_pressure_but_explicit_deletion_remains_allowed(self):
        pressure = dict(self.mark, pressures=[.2, .9])
        self.route(body=self.body(marks=[pressure]))
        self.rejected(409, lambda: self.route(body=self.body(1, [self.mark])))
        self.assertEqual([pressure], self.route()['marks'])
        unchanged = self.route(body=self.body(1, [dict(pressure, width=.006)]))
        self.assertEqual([.2, .9], unchanged.data['marks'][0]['pressures'])
        self.assertEqual([], self.route(body=self.body(2, [] )).data['marks'])

    def test_scaled_stamp_roundtrip_and_legacy_payload_unchanged(self):
        stamp = dict(self.mark, kind='SYMBOL', points=[[.2, .3]], symbolScale=.5)
        saved = self.route(body=self.body(marks=[stamp]))
        self.assertEqual([stamp], saved.data['marks'])
        self.assertEqual([stamp], self.route()['marks'])
        self.assertEqual([self.mark], self.route(body=self.body(1)).data['marks'])
        self.assertNotIn('symbolScale', self.route()['marks'][0])

    def test_adjustable_hairpin_endpoints_roundtrip_without_a_protocol_upgrade(self):
        for revision, symbol in enumerate(('<', '>')):
            stamp = dict(self.mark, kind='SYMBOL', symbol=symbol,
                         points=[[.2, .3], [.75, .3]], symbolScale=1.5)
            self.assertEqual([stamp], self.route(body=self.body(revision, marks=[stamp])).data['marks'])
            self.assertEqual([stamp], self.route()['marks'])

    def test_wsgi_commits_conflict_and_rejects_unbounded_json(self):
        app = Application(self.service)
        def request(body):
            raw = json.dumps(body).encode(); status=[]
            env = dict(REMOTE_ADDR='127.0.0.1', REQUEST_METHOD='POST', PATH_INFO=f'/v1/annotations/{self.artifact}/0/personal',
                       CONTENT_TYPE='application/json', CONTENT_LENGTH=str(len(raw)), HTTP_AUTHORIZATION='Bearer '+self.listener['token'],
                       HTTP_X_DEVICE_ID=self.device, **{'wsgi.url_scheme':'https','wsgi.input':io.BytesIO(raw)})
            result = json.loads(b''.join(app(env, lambda s,h:status.append(s))))
            return status[0], result
        self.assertTrue(request(self.body())[0].startswith('200'))
        candidate=self.body(marks=[])
        self.assertTrue(request(candidate)[0].startswith('409'))
        self.assertEqual('annotation_conflict', request(candidate)[1]['error'])
        with self.service.connection() as db:
            self.assertEqual(2, db.execute('SELECT count(*) FROM annotation_operations').fetchone()[0])
        manifest=self.route(path='/v1/annotations')
        self.assertEqual('personal',manifest['pages'][0]['kind'])

    def test_admin_read_requires_target_consent_and_own_pdf_grant(self):
        self.route(body=self.body())
        body = dict(accountId=self.listener['principal']['accountId'], artifactId=self.artifact, pageIndex=0)
        self.assertEqual([self.mark], self.route(body=body, login=self.admin, path='/v1/admin/annotation-backups')['marks'])
        with self.service.connection() as db:
            db.execute('DELETE FROM artifact_grants WHERE account=?', (self.admin_id,))
        self.rejected(404, lambda: self.route(body=body, login=self.admin, path='/v1/admin/annotation-backups'))
        with self.service.connection() as db:
            db.execute('INSERT INTO artifact_grants VALUES(?,?)', (self.admin_id, self.artifact))
            db.execute('UPDATE accounts SET consent=NULL WHERE id=?', (self.listener['principal']['accountId'],))
        self.rejected(404, lambda: self.route(body=body, login=self.admin, path='/v1/admin/annotation-backups'))
        with self.service.connection() as db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM audit WHERE event='annotation_backup_read'").fetchone()[0])

    def test_verified_backup_preserves_heads_conflicts_and_idempotency(self):
        import hashlib
        from pathlib import Path
        from pypdf import PdfWriter
        from backup_archive import create_archive, restore_archive
        with PdfWriter() as writer:
            writer.add_blank_page(595,842); writer.add_blank_page(595,842)
            writer.write(self.catalog.root / (self.artifact+'.pdf'))
        content=(self.catalog.root / (self.artifact+'.pdf')).read_bytes()
        self.hash=hashlib.sha256(content).hexdigest()
        with self.service.connection() as db:
            db.execute('UPDATE artifacts SET hash=?,size=? WHERE id=?',(self.hash,len(content),self.artifact))
        first=self.body();self.route(body=first)
        conflict=self.route(body=self.body(marks=[]))
        archive=Path(self.tmp.name)/'ink-backup.zip'
        create_archive(self.service,archive)
        restored=Path(self.tmp.name)/'restored'
        restore_archive(archive,restored)
        import sqlite3
        from contextlib import closing
        with closing(sqlite3.connect(restored/'team.sqlite3')) as db:
            self.assertEqual(1,db.execute('SELECT revision FROM annotation_heads').fetchone()[0])
            self.assertEqual(2,db.execute('SELECT count(*) FROM annotation_operations').fetchone()[0])
            self.assertEqual(2,db.execute('SELECT count(*) FROM annotation_history').fetchone()[0])
            self.assertEqual(0,db.execute('SELECT count(*) FROM sessions').fetchone()[0])
        self.assertEqual(409,conflict.status)


if __name__ == '__main__':
    import unittest
    unittest.main()
