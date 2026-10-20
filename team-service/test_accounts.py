import concurrent.futures
import io
import json
import sqlite3
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from wsgiref.simple_server import make_server, WSGIRequestHandler

from security import password_hash, password_matches
from server import Application
from service import TeamService, Rejected, SESSION_SECONDS

PASSWORD = "synthetic rehearsal passphrase"


class AccountFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.now = [1_800_000_000]
        self.service = TeamService(self.tmp.name, lambda: self.now[0])
        self.admin_id = self.service.bootstrap("Synthetic ensemble", "director", "Director", PASSWORD)
        self.device = str(uuid.uuid4())
        self.admin = self.login("director", self.device)

    def tearDown(self):
        self.tmp.cleanup()

    def login(self, name, device=None, password=PASSWORD):
        return self.service.login(dict(username=name, password=password, deviceId=device or self.device,
                                       deviceName="Synthetic tablet"), "127.0.0.1")

    def call(self, path, body=None, login=None, device=None, method=None):
        return self.service.dispatch(method or ("GET" if body is None else "POST"), path, body,
                                     (login or self.admin)["token"], device or self.device, "127.0.0.1")

    def registration(self, invite=None, name="violinist", device=None):
        invite = invite or self.call("/v1/admin/invitations", {})["invitation"]
        return dict(username=name, password=PASSWORD, displayName="Violin player", invitation=invite,
                    deviceId=device or self.device, deviceName="Shared tablet", backupConsent=True)

    def member(self, name="violinist", device=None):
        return self.service.register(self.registration(name=name, device=device), "127.0.0.1")

    def rejected(self, status, function):
        with self.assertRaises(Rejected) as caught:
            function()
        self.assertEqual(status, caught.exception.status)


class AccountsTest(AccountFixture):
    def test_real_password_hash_and_no_plaintext_credentials_on_disk(self):
        self.assertTrue(password_matches(PASSWORD, password_hash(PASSWORD)))
        self.assertNotEqual(password_hash(PASSWORD), password_hash(PASSWORD))
        invitation = self.call("/v1/admin/invitations", {})["invitation"]
        for file in Path(self.tmp.name).glob("team.sqlite3*"):
            data = file.read_bytes()
            for secret in (PASSWORD, self.admin["token"], invitation):
                self.assertNotIn(secret.encode(), data)

    def test_bootstrap_is_one_time_and_does_not_overwrite_accounts(self):
        self.rejected(409, lambda: self.service.bootstrap("Other", "director", "X", PASSWORD))
        self.assertEqual(self.admin_id, self.login("director")["principal"]["accountId"])

    def test_registration_is_invited_once_and_cannot_choose_admin_role(self):
        body = self.registration()
        self.rejected(400, lambda: self.service.register(dict(body, role="admin"), "127.0.0.1"))
        member = self.service.register(body, "127.0.0.1")
        self.assertEqual("member", member["principal"]["role"])
        self.assertTrue(member["principal"]["backupConsent"])
        self.rejected(403, lambda: self.service.register(dict(body, username="cellist"), "127.0.0.1"))

    def test_registration_requires_explicit_backup_notice_confirmation(self):
        body = self.registration()
        self.rejected(400, lambda: self.service.register(dict(body, backupConsent=False), "127.0.0.1"))
        self.rejected(400, lambda: self.service.register(dict(body, backupConsent="true"), "127.0.0.1"))
        self.assertTrue(self.service.register(body, "127.0.0.1")["principal"]["backupConsent"])

    def test_bad_device_rolls_back_registration_and_invitation_consumption(self):
        body = self.registration()
        self.rejected(400, lambda: self.service.register(dict(body, deviceId="../device"), "127.0.0.1"))
        self.assertEqual("violinist", self.service.register(body, "127.0.0.1")["principal"]["username"])

    def test_expired_invitation_cannot_register(self):
        body = self.registration()
        self.now[0] += 48 * 3600
        self.rejected(403, lambda: self.service.register(body, "127.0.0.1"))

    def test_session_persists_restart_but_requires_its_bound_device(self):
        replacement = TeamService(self.tmp.name, lambda: self.now[0])
        self.assertEqual(self.admin_id, replacement.dispatch("GET", "/v1/me", None, self.admin["token"],
                                                            self.device, "127.0.0.1")["principal"]["accountId"])
        self.rejected(401, lambda: self.call("/v1/me", device=str(uuid.uuid4())))

    def test_session_expiry_and_logout_are_enforced_on_server(self):
        self.now[0] += SESSION_SECONDS
        self.rejected(401, lambda: self.call("/v1/me"))
        self.admin = self.login("director")
        self.call("/v1/auth/logout", {})
        self.rejected(401, lambda: self.call("/v1/me"))

    def test_only_verified_identity_check_renews_seven_day_session_and_survives_restart(self):
        self.assertEqual(7 * 24 * 3600, SESSION_SECONDS)
        original_expiry = self.admin["expiresAt"]
        self.now[0] += SESSION_SECONDS - 1
        self.call("/v1/me/devices")
        with self.service.connection() as db:
            self.assertEqual(original_expiry, db.execute("SELECT expires FROM sessions").fetchone()[0])
        verified = self.call("/v1/me")
        self.assertEqual(self.now[0] + SESSION_SECONDS, verified["expiresAt"])
        self.service = TeamService(self.tmp.name, lambda: self.now[0])
        self.now[0] = original_expiry + 1
        self.assertEqual(self.admin_id, self.call("/v1/me")["principal"]["accountId"])
        renewed = self.now[0] + SESSION_SECONDS
        self.now[0] = renewed
        self.rejected(401, lambda: self.call("/v1/me"))

    def test_revocation_after_renewal_cannot_renew_or_restore_old_session(self):
        member = self.member()
        self.now[0] += 3600
        self.assertGreater(self.call("/v1/me", login=member)["expiresAt"], member["expiresAt"])
        target = member["principal"]["accountId"]
        self.call("/v1/admin/members/status", {"accountId": target, "active": False})
        self.rejected(401, lambda: self.call("/v1/me", login=member))
        self.call("/v1/admin/members/status", {"accountId": target, "active": True})
        self.rejected(401, lambda: self.call("/v1/me", login=member))

    def test_member_cannot_access_admin_or_impersonate_identity(self):
        member = self.member()
        self.rejected(403, lambda: self.call("/v1/admin/members", login=member))
        self.rejected(403, lambda: self.call("/v1/admin/invitations", {}, member))
        self.rejected(400, lambda: self.call("/v1/auth/logout", {"memberId": self.admin_id}, member))
        self.assertEqual(member["principal"]["accountId"], self.call("/v1/me", login=member)["principal"]["accountId"])

    def test_same_device_allows_multiple_accounts_without_identity_crossover(self):
        member = self.member()
        self.assertEqual(self.admin_id, self.call("/v1/me")["principal"]["accountId"])
        self.call("/v1/auth/logout", {}, member)
        self.assertEqual(self.admin_id, self.call("/v1/me")["principal"]["accountId"])
        self.rejected(401, lambda: self.call("/v1/me", login=member))

    def test_revoked_device_cannot_log_back_in_until_admin_restores_it(self):
        other = str(uuid.uuid4())
        member = self.member(device=other)
        member2 = self.login("violinist")
        self.call("/v1/me/devices/revoke", {"deviceId": other}, member2)
        self.rejected(401, lambda: self.call("/v1/me", login=member, device=other))
        self.rejected(403, lambda: self.login("violinist", other))
        self.call("/v1/admin/devices/restore", {"accountId": member["principal"]["accountId"], "deviceId": other})
        self.assertEqual("violinist", self.login("violinist", other)["principal"]["username"])

    def test_device_revocation_does_not_revoke_other_account_on_shared_device(self):
        member = self.member()
        self.call("/v1/me/devices/revoke", {"deviceId": self.device}, member)
        self.assertEqual(self.admin_id, self.call("/v1/me")["principal"]["accountId"])

    def test_password_change_invalidates_all_devices(self):
        member = self.member()
        other = str(uuid.uuid4())
        another = self.login("violinist", other)
        new = "new synthetic rehearsal passphrase"
        self.call("/v1/auth/password", {"currentPassword": PASSWORD, "newPassword": new}, member)
        self.rejected(401, lambda: self.call("/v1/me", login=member))
        self.rejected(401, lambda: self.call("/v1/me", login=another, device=other))
        self.rejected(401, lambda: self.login("violinist"))
        self.assertEqual("violinist", self.login("violinist", password=new)["principal"]["username"])

    def test_disabled_member_and_old_session_do_not_revive_after_enable(self):
        member = self.member()
        target = member["principal"]["accountId"]
        self.call("/v1/admin/members/status", {"accountId": target, "active": False})
        self.rejected(401, lambda: self.call("/v1/me", login=member))
        self.rejected(401, lambda: self.login("violinist"))
        self.call("/v1/admin/members/status", {"accountId": target, "active": True})
        self.rejected(401, lambda: self.call("/v1/me", login=member))
        self.assertEqual(target, self.login("violinist")["principal"]["accountId"])

    def test_password_reset_recovery_invalidates_sessions(self):
        self.service.reset_password("director", "new operator recovery password")
        self.rejected(401, lambda: self.call("/v1/me"))
        self.assertEqual(self.admin_id, self.login("director", password="new operator recovery password")["principal"]["accountId"])

    def test_password_guess_limit_survives_failed_transactions_and_restart(self):
        for _ in range(10):
            self.rejected(401, lambda: self.login("missing", password="wrong password value"))
        self.service = TeamService(self.tmp.name, lambda: self.now[0])
        self.rejected(429, lambda: self.login("missing"))
        self.now[0] += 901
        self.rejected(401, lambda: self.login("missing"))

    def test_invite_concurrency_cannot_create_two_accounts(self):
        body = self.registration()
        def attempt(name):
            try:
                self.service.register(dict(body, username=name), "127.0.0.1")
                return True
            except Rejected:
                return False
        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(1, sum(pool.map(attempt, ["first", "second"])))

    def test_backup_can_restore_into_independent_directory(self):
        self.member()
        restored = Path(self.tmp.name) / "restored"
        restored.mkdir()
        self.service.backup(restored / "team.sqlite3")
        recovered = TeamService(restored, lambda: self.now[0])
        self.rejected(401, lambda: recovered.dispatch("GET", "/v1/me", None, self.admin["token"], self.device, "127.0.0.1"))
        result = recovered.login(dict(username="violinist", password=PASSWORD, deviceId=self.device,
                                      deviceName="Recovered"), "127.0.0.1")
        self.assertEqual("violinist", result["principal"]["username"])
        self.rejected(409, lambda: self.service.backup(restored / "team.sqlite3"))

    def test_transport_rejects_cleartext_identity_overrides_and_oversized_bodies(self):
        app = Application(self.service)
        def request(**overrides):
            env = {"REQUEST_METHOD": "GET", "PATH_INFO": "/v1/me", "wsgi.url_scheme": "https",
                   "REMOTE_ADDR": "127.0.0.1", "HTTP_AUTHORIZATION": "Bearer " + self.admin["token"],
                   "HTTP_X_DEVICE_ID": self.device, "wsgi.input": io.BytesIO(b"{}"), **overrides}
            status = []
            result = b"".join(app(env, lambda s, h: status.append(int(s[:3]))))
            return status[0], json.loads(result)
        self.assertEqual(200, request()[0])
        self.assertEqual(403, request(**{"wsgi.url_scheme": "http"})[0])
        self.assertEqual(403, request(QUERY_STRING="memberId=" + self.admin_id)[0])
        self.assertEqual(403, request(HTTP_X_MEMBER_ID=self.admin_id)[0])
        self.assertEqual(403, request(HTTP_ORIGIN="https://untrusted.invalid")[0])
        self.assertEqual(413, request(REQUEST_METHOD="POST", CONTENT_TYPE="application/json", CONTENT_LENGTH="99999")[0])
        self.assertEqual(400, request(REQUEST_METHOD="POST", CONTENT_TYPE="application/json", CONTENT_LENGTH="27",
                                     **{"wsgi.input": io.BytesIO(b'{"username":1,"username":2}')})[0])

    def test_real_http_login_me_and_logout(self):
        class Quiet(WSGIRequestHandler):
            def log_message(self, *args):
                pass
        server = make_server("127.0.0.1", 0, Application(self.service, True), handler_class=Quiet)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        base = f"http://127.0.0.1:{server.server_port}"
        try:
            data = json.dumps(dict(username="director", password=PASSWORD, deviceId=self.device,
                                   deviceName="HTTP client")).encode()
            req = urllib.request.Request(base + "/v1/auth/login", data, {"Content-Type": "application/json"})
            with urllib.request.urlopen(req) as response:
                self.assertEqual("no-store", response.headers["Cache-Control"])
                login = json.load(response)
            headers = {"Authorization": "Bearer " + login["token"], "X-Device-Id": self.device}
            with urllib.request.urlopen(urllib.request.Request(base + "/v1/me", headers=headers)) as response:
                self.assertEqual(self.admin_id, json.load(response)["principal"]["accountId"])
            req = urllib.request.Request(base + "/v1/auth/logout", b"{}", dict(headers, **{"Content-Type": "application/json"}))
            urllib.request.urlopen(req).close()
            with self.assertRaises(urllib.error.HTTPError) as error:
                urllib.request.urlopen(urllib.request.Request(base + "/v1/me", headers=headers))
            self.assertEqual(401, error.exception.code)
        finally:
            server.shutdown()
            thread.join(2)
            server.server_close()


if __name__ == "__main__":
    unittest.main()
