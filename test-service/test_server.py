import json
import threading
import unittest
import urllib.error
import urllib.request
from server import make_server


class HttpSecurityTest(unittest.TestCase):
    def setUp(self):
        self.server = make_server()
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = 'http://127.0.0.1:' + str(self.server.server_port)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def req(self, path, body=None, token=None, device='device-123', extra=None):
        headers = {'X-Device-Id': device, **(extra or {})}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        req = urllib.request.Request(self.base + path, json.dumps(body).encode() if body is not None else None, headers)
        try:
            with urllib.request.urlopen(req) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def login(self, account):
        status, data, _ = self.req('/login', dict(account=account, loginCode=self.server.state.codes[account], deviceId='device-123'))
        self.assertEqual(200, status)
        return json.loads(data)['token']

    def manifest(self, token):
        status, data, _ = self.req('/manifest', token=token)
        self.assertEqual(200, status)
        return json.loads(data)

    def test_real_login_and_distinct_server_projections(self):
        a, b = self.login('alice'), self.login('bob')
        ma, mb = self.manifest(a), self.manifest(b)
        self.assertEqual(['Violin'], [x['partName'] for x in ma['artifacts']])
        self.assertEqual(['Cello'], [x['partName'] for x in mb['artifacts']])
        self.assertNotIn('Cello', json.dumps(ma))
        self.assertNotEqual(ma['principal']['memberId'], mb['principal']['memberId'])
        self.assertEqual(401, self.req('/login', dict(account='alice', loginCode='wrong', deviceId='device-123'))[0])
        self.assertEqual(401, self.req('/manifest')[0])

    def test_guessed_resource_and_member_override_rejected(self):
        a, b = self.login('alice'), self.login('bob')
        other = self.manifest(b)['artifacts'][0]['artifactId']
        self.assertEqual(403, self.req('/artifacts/' + other, token=a)[0])
        self.assertEqual(403, self.req('/artifacts/' + other, token=a, extra={'Range': 'bytes=30-'})[0])
        self.assertEqual(403, self.req('/manifest?memberId=bob', token=a)[0])
        self.assertEqual(403, self.req('/manifest', token=a, extra={'X-Member-Id': 'bob'})[0])
        self.assertEqual(403, self.req('/login', dict(account='alice', loginCode=self.server.state.codes['alice'], deviceId='device-123', memberId='bob'))[0])

    def test_revocation_denies_old_manifest_download_range_and_commit(self):
        a = self.login('alice')
        m = self.manifest(a)
        resource = m['artifacts'][0]['artifactId']
        self.server.state.settings = dict(revoked=['alice'], revision=2)
        self.assertEqual([], self.manifest(a)['artifacts'])
        self.assertEqual(403, self.req('/artifacts/' + resource, token=a)[0])
        self.assertEqual(403, self.req('/artifacts/' + resource, token=a, extra={'Range': 'bytes=20-'})[0])
        self.assertEqual(403, self.req('/authorize-commit', {k: m[k] for k in ('revision', 'artifacts')}, token=a)[0])

    def test_device_binding_revocation_and_run_boundary(self):
        a = self.login('alice')
        self.assertEqual(401, self.req('/manifest', token=a, device='other-device')[0])
        self.server.state.settings = dict(revokedDevices=['device-123'])
        self.assertEqual(401, self.req('/manifest', token=a)[0])
        self.server.state.settings = {}
        self.server.state.sessions.clear()
        self.assertEqual(401, self.req('/manifest', token=a)[0])

    def test_pdf_hash_and_range_are_real_bytes(self):
        import hashlib
        a = self.login('alice')
        artifact = self.manifest(a)['artifacts'][0]
        path = '/artifacts/' + artifact['artifactId']
        status, full, _ = self.req(path, token=a)
        self.assertEqual(200, status)
        self.assertTrue(full.startswith(b'%PDF-'))
        self.assertNotIn(b'CELLO', full)
        self.assertEqual(artifact['size'], len(full))
        self.assertEqual(artifact['hash'], hashlib.sha256(full).hexdigest())
        status, tail, headers = self.req(path, token=a, extra={'Range': 'bytes=100-', 'If-Match': artifact['hash']})
        self.assertEqual(206, status)
        self.assertEqual(full[100:], tail)
        self.assertEqual(f'bytes 100-{len(full)-1}/{len(full)}', headers['Content-Range'])
        self.assertEqual(412, self.req(path, token=a, extra={'If-Match': 'old'})[0])
        self.assertEqual(416, self.req(path, token=a, extra={'Range': 'bytes=999999-'})[0])

    def test_new_version_is_independent_and_old_download_denied(self):
        a = self.login('alice')
        old = self.manifest(a)['artifacts'][0]
        self.server.state.settings = dict(version=2)
        new = self.manifest(a)['artifacts'][0]
        self.assertNotEqual(old['hash'], new['hash'])
        self.assertEqual(old['scorePdfId'], new['scorePdfId'])
        self.assertNotEqual(old['sourceVersionId'], new['sourceVersionId'])
        self.assertEqual(403, self.req('/artifacts/' + old['artifactId'], token=a)[0])
        self.assertEqual(200, self.req('/artifacts/' + new['artifactId'], token=a)[0])


if __name__ == '__main__':
    unittest.main(verbosity=2)
