import base64
import concurrent.futures
import copy
import hashlib
import io
import json
import random
from pathlib import Path
import struct
import uuid
from unittest.mock import patch
import zlib

from backup_archive import create_archive, restore_archive
from personal_folders import PersonalFolders, canonical, MAX_FOLDER_BODY
from server import Application
from service import TeamService, SESSION_SECONDS
from test_accounts import AccountFixture, PASSWORD


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


def png(width=2, height=2, raster=None, compressed=None):
    raw = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    pixels = raster if raster is not None else (b'\x00' + b'\xff\x00\x00\xff' * width) * height
    return raw + chunk(b'IDAT', compressed if compressed is not None else zlib.compress(pixels)) + chunk(b'IEND', b'')


class PersonalFolderTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.api = PersonalFolders(self.service)
        self.listener = self.member()
        self.folder = dict(id=str(uuid.uuid4()), name='个人排练', pieces=['opaque-piece-id'],
                           coverPiece=None, image=base64.b64encode(png()).decode())

    def route(self, body=None, login=None, device=None):
        return self.api.dispatch('POST' if body is not None else 'GET', '/v1/personal-folders', body,
                                 (login or self.listener)['token'], device or self.device)

    def body(self, revision=0, folders=None):
        return dict(protocolVersion=1, requestId=str(uuid.uuid4()), baseRevision=revision,
                    folders=[copy.deepcopy(self.folder)] if folders is None else folders)

    def test_roundtrip_is_private_and_does_not_grant_pdf_access(self):
        other = self.member('cellist')
        initial = self.route()
        self.assertEqual((0, '[]'), (initial['revision'], initial['payload']))
        self.assertEqual(hashlib.sha256(b'[]').hexdigest(), initial['sha256'])
        self.route(self.body())
        head = self.route()
        self.assertEqual([self.folder], json.loads(head['payload']))
        self.assertEqual(canonical([self.folder]), head['payload'])
        self.assertEqual(hashlib.sha256(head['payload'].encode()).hexdigest(), head['sha256'])
        self.assertEqual(self.listener['principal']['accountId'], head['accountId'])
        self.assertEqual('[]', self.route(login=other)['payload'])
        self.assertEqual('[]', self.route(login=self.admin)['payload'])
        with self.service.connection() as db:
            # This fixture has no catalog/PDF grant tables at all; folder refs are opaque.
            self.assertEqual(1, db.execute('SELECT count(*) FROM personal_folder_heads').fetchone()[0])
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name='artifact_grants'").fetchone())

    def test_idempotent_success_and_conflict_survive_advancing_head_and_restart(self):
        body = self.body()
        receipt = self.route(body)
        self.assertEqual(1, receipt.data['revision'])
        self.assertEqual(receipt, self.route(body))
        self.rejected(409, lambda: self.route(dict(body, folders=[])))
        stale = self.body(folders=[])
        conflict = self.route(stale)
        self.assertEqual(409, conflict.status)
        self.assertEqual('folder_conflict', conflict.data['error'])
        self.assertEqual(receipt.data['sha256'], conflict.data['currentSha256'])
        self.assertNotIn('payload', conflict.data)
        self.assertEqual(2, self.route(self.body(1, [])).data['revision'])
        self.api = PersonalFolders(TeamService(self.tmp.name, lambda: self.now[0]))
        self.assertEqual(receipt, self.route(body))
        self.assertEqual(conflict, self.route(stale))
        self.assertEqual('[]', self.route()['payload'])
        with self.service.connection() as db:
            receipts = db.execute('SELECT result FROM personal_folder_operations').fetchall()
            self.assertEqual(3, len(receipts))
            self.assertTrue(all(len(row[0].encode()) < 1024 and self.folder['image'] not in row[0] for row in receipts))

    def test_two_devices_race_exactly_one_winner(self):
        device2 = str(uuid.uuid4())
        second = self.login('violinist', device2)
        bodies = [self.body(), self.body(folders=[])]
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            results = list(pool.map(lambda i: self.route(bodies[i], second if i else self.listener,
                                                        device2 if i else self.device), range(2)))
        self.assertEqual([200, 409], sorted(reply.status for reply in results))
        self.assertEqual(1, self.route()['revision'])
        self.assertEqual([self.folder] if results[0].status == 200 else [], json.loads(self.route()['payload']))

    def test_no_client_owner_override_or_unknown_folder_fields(self):
        for extra in ({'accountId': self.admin_id}, {'teamId': 'other'}, {'owner': self.admin_id}):
            self.rejected(400, lambda: self.route(dict(self.body(), **extra)))
        for extra in ({'sequence': '001'}, {'grants': ['all']}, {'path': '/etc/passwd'}):
            self.rejected(400, lambda: self.route(self.body(folders=[dict(self.folder, **extra)])))
        self.rejected(404, lambda: self.api.dispatch('GET', '/v1/personal-folders/' + self.admin_id,
                                                    None, self.listener['token'], self.device))
        self.assertEqual(0, self.route()['revision'])

    def test_invalid_metadata_leaves_current_snapshot_and_receipts_unchanged(self):
        self.route(self.body())
        before = self.route()
        invalid = [None, {}, [self.folder] * 2, [self.folder] * 101,
                   [dict(self.folder, id='')], [dict(self.folder, id='x\x7f')],
                   [dict(self.folder, id='a' * 201)], [dict(self.folder, name=' ')],
                   [dict(self.folder, name='x' * 101)], [dict(self.folder, name='🎻' * 51)],
                   [dict(self.folder, pieces=[1])], [dict(self.folder, pieces=['p', 'p'])],
                   [dict(self.folder, pieces=['p'] * 10001)], [dict(self.folder, coverPiece='p')]]
        for folders in invalid:
            body = self.body(1);body['folders'] = folders
            self.rejected(400, lambda: self.route(body))
        for value in (True, -1, 1.0, 2**53-1, '1'):
            self.rejected(400, lambda: self.route(dict(self.body(1), baseRevision=value)))
        self.rejected(400, lambda: self.route(dict(self.body(1), protocolVersion=True)))
        self.rejected(400, lambda: self.route(dict(self.body(1), requestId='bad')))
        self.assertEqual(before, self.route())
        with self.service.connection() as db:
            self.assertEqual(1, db.execute('SELECT count(*) FROM personal_folder_operations').fetchone()[0])

    def test_corrupt_png_dimensions_filters_compression_and_trailing_bytes_are_rejected(self):
        valid = png()
        corrupt = bytearray(valid);corrupt[-1] ^= 1
        images = [b'not png', bytes(corrupt), valid + b'trailing', valid[:-12], png(width=513),
                  png(raster=b'\x05' + b'\x00' * 17), png(raster=b'\x00' * 1000),
                  png(compressed=zlib.compress(b'\x00' * 10_000_000)),
                  png(compressed=zlib.compress(b'\x00' * 18) + b'trailing'),
                  png(compressed=b'bad zlib'), png(compressed=zlib.compress(b'\x00' * 18)[:-2])]
        for raw in images:
            self.rejected(400, lambda: self.route(self.body(folders=[dict(self.folder, image=base64.b64encode(raw).decode())])))
        for encoded in ('!', self.folder['image'] + '\n', 'a' * 700001, True):
            self.rejected(400, lambda: self.route(self.body(folders=[dict(self.folder, image=encoded)])))
        self.assertEqual(0, self.route()['revision'])

    def test_size_and_durable_receipt_quota_never_apply_or_erase_retry(self):
        body = self.body();receipt = self.route(body)
        with patch('personal_folders.MAX_OPERATIONS', 1):
            self.assertEqual(receipt, self.route(body))
            self.rejected(413, lambda: self.route(self.body(1, [])))
        with patch('personal_folders.MAX_FOLDERS', 10):
            self.rejected(413, lambda: self.route(self.body(1)))
        with patch('personal_folders.MAX_TOTAL_HEADS', 1):
            self.rejected(413, lambda: self.route(self.body(1)))
        self.assertEqual(1, self.route()['revision'])
        self.assertEqual(receipt, self.route(body))

    def test_revoked_expired_or_wrong_device_cannot_read_or_retry(self):
        body = self.body();self.route(body)
        self.rejected(401, lambda: self.route(device=str(uuid.uuid4())))
        self.now[0] += SESSION_SECONDS + 1
        self.rejected(401, lambda: self.route())
        self.rejected(401, lambda: self.route(body))

    def test_disabled_account_and_revoked_device_deny_existing_receipts(self):
        body = self.body();self.route(body)
        self.call('/v1/admin/members/status', dict(accountId=self.listener['principal']['accountId'], active=False))
        self.rejected(401, lambda: self.route())
        self.rejected(401, lambda: self.route(body))
        self.call('/v1/admin/members/status', dict(accountId=self.listener['principal']['accountId'], active=True))
        self.listener = self.login('violinist')
        self.assertEqual(1, self.route()['revision'])
        self.call('/v1/me/devices/revoke', dict(deviceId=self.device), login=self.listener)
        self.rejected(401, lambda: self.route())
        self.rejected(401, lambda: self.route(body))

    def test_real_size_limit_rejects_combined_covers_without_partial_write(self):
        raster = b''.join(b'\x00' + random.Random(row).randbytes(500 * 4) for row in range(250))
        image = base64.b64encode(png(500, 250, raster)).decode()
        self.assertLess(len(base64.b64decode(image)), 512*1024)
        rows = [dict(self.folder, id=str(uuid.uuid4()), image=image) for _ in range(13)]
        self.rejected(413, lambda: self.route(self.body(folders=rows)))
        self.assertEqual(0, self.route()['revision'])

    def test_png_unknown_critical_chunks_animation_and_order_are_rejected(self):
        raw = png()
        prefix, data, end = raw[:33], raw[33:-12], raw[-12:]
        invalid = [prefix + chunk(b'ABCD', b'') + data + end,
                   prefix + chunk(b'acTL', struct.pack('>II', 1, 0)) + data + end,
                   data + prefix + end, prefix + data + chunk(b'tEXt', b'hello') + data + end,
                   prefix + raw[8:33] + data + end]
        for value in invalid:
            self.rejected(400, lambda: self.route(self.body(folders=[dict(self.folder, image=base64.b64encode(value).decode())])))

    def wsgi(self, raw=b'{}', login=None, path='/v1/personal-folders', stream=None, **extra):
        env = dict(REQUEST_METHOD='POST', PATH_INFO=path, REMOTE_ADDR='127.0.0.1',
                   CONTENT_TYPE='application/json', CONTENT_LENGTH=str(len(raw)))
        env.update({'wsgi.url_scheme': 'https', 'wsgi.input': stream if stream is not None else io.BytesIO(raw)})
        if login:
            env.update(HTTP_AUTHORIZATION='Bearer ' + login['token'], HTTP_X_DEVICE_ID=self.device)
        env.update(extra)
        response = []
        data = b''.join(Application(self.service)(env, lambda status, headers: response.append((status, dict(headers)))))
        return int(response[0][0].split()[0]), json.loads(data), response[0][1]

    def test_wsgi_authenticates_before_large_body_and_rechecks_after_read(self):
        class Unreadable:
            def read(inner, size):
                raise AssertionError('Unauthenticated body must not be read')
        self.assertEqual(401, self.wsgi(stream=Unreadable(), CONTENT_LENGTH=str(MAX_FOLDER_BODY))[0])
        self.assertEqual(401, self.wsgi(stream=Unreadable(), login=self.listener, HTTP_X_DEVICE_ID=str(uuid.uuid4()))[0])
        self.assertEqual(413, self.wsgi(stream=Unreadable(), login=self.listener, CONTENT_LENGTH=str(MAX_FOLDER_BODY+1))[0])
        raw = json.dumps(self.body()).encode()
        class ExpiresDuringRead(io.BytesIO):
            def read(inner, size):
                self.now[0] += SESSION_SECONDS + 1
                return super().read(size)
        self.assertEqual(401, self.wsgi(raw, self.listener, stream=ExpiresDuringRead(raw))[0])
        with self.service.connection() as db:
            self.assertEqual(0, db.execute('SELECT count(*) FROM personal_folder_heads').fetchone()[0])

    def test_wsgi_large_snapshot_duplicate_keys_identity_override_and_safe_errors(self):
        body = self.body(folders=[dict(self.folder, image=None, pieces=['piece-' + str(i) for i in range(3000)])])
        raw = json.dumps(body).encode()
        self.assertGreater(len(raw), 16*1024)
        status, result, headers = self.wsgi(raw, self.listener)
        self.assertEqual(200, status)
        self.assertEqual(1, result['revision'])
        self.assertEqual('no-store', headers['Cache-Control'])
        self.assertEqual(413, self.wsgi(raw, self.listener, path='/v1/me/backup-consent')[0])
        self.assertEqual(400, self.wsgi(raw.replace(b'"protocolVersion": 1', b'"protocolVersion": 1, "protocolVersion": 1'), self.listener)[0])
        self.assertEqual(403, self.wsgi(raw, self.listener, HTTP_X_ACCOUNT_ID=self.admin_id)[0])
        self.assertEqual(400, self.wsgi(b'[' * 2000, self.listener)[0])
        self.assertEqual(400, self.wsgi(b'{"protocolVersion":1}', self.listener)[0])
        self.assertEqual(1, self.route()['revision'])

    def test_full_backup_restore_preserves_head_and_both_receipts_with_fresh_login(self):
        body = self.body();receipt = self.route(body)
        stale = self.body(folders=[]);conflict = self.route(stale)
        target = Path(self.tmp.name) / 'folders.zip'
        create_archive(self.service, target)
        root = Path(self.tmp.name) / 'restored'
        restore_archive(target, root)
        restored = TeamService(root, lambda: self.now[0])
        api = PersonalFolders(restored)
        self.rejected(401, lambda: api.dispatch('GET', '/v1/personal-folders', None, self.listener['token'], self.device))
        login = restored.login(dict(username='violinist', password=PASSWORD, deviceId=self.device, deviceName='Restored'), '127.0.0.1')
        self.assertEqual(self.route(), api.dispatch('GET', '/v1/personal-folders', None, login['token'], self.device))
        for request, expected in ((body, receipt), (stale, conflict)):
            self.assertEqual(expected, api.dispatch('POST', '/v1/personal-folders', request, login['token'], self.device))
