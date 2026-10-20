import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from app_release import AppReleases
from server import Application
from service import TeamService, Rejected


class AppReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="apk-test-")
        self.root = Path(self.tmp.name)
        self.apk = self.root / "synthetic.apk"
        with zipfile.ZipFile(self.apk, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"synthetic fixture, not installable")
            archive.writestr("classes.dex", b"synthetic")
        self.releases = AppReleases(self.root / "private")

    def tearDown(self):
        self.tmp.cleanup()

    def test_atomic_publish_and_retries(self):
        self.assertEqual(dict(available=False), self.releases.reply("/v1/app-release"))
        first = self.releases.publish(self.apk, 14, "0.10.2", "合成测试")
        self.assertEqual(first, self.releases.publish(self.apk, 14, "0.10.2", "合成测试"))
        self.assertEqual(14, self.releases.reply("/v1/app-release")["versionCode"])
        with self.assertRaises(Rejected):
            self.releases.publish(self.apk, 13, "old")
        self.assertEqual(first, self.releases.current())

    def test_download_requires_exact_current_hash(self):
        metadata = self.releases.publish(self.apk, 14, "0.10.2")
        with self.assertRaises(Rejected):
            self.releases.reply("/v1/app-release/apk")
        with self.assertRaises(Rejected):
            self.releases.reply("/v1/app-release/apk", '"wrong"')
        reply = self.releases.reply("/v1/app-release/apk", '"' + metadata["sha256"] + '"')
        self.assertEqual("application/vnd.android.package-archive", reply.content_type)
        self.assertEqual(self.apk.read_bytes(), b"".join(reply))
        self.assertTrue(reply.stream.closed)

    def test_corrupt_storage_cannot_serve_an_apk(self):
        metadata = self.releases.publish(self.apk, 14, "0.10.2")
        target = self.releases.root / (metadata["sha256"] + ".apk")
        target.write_bytes(b"damaged")
        with self.assertRaises(Rejected):
            self.releases.reply("/v1/app-release/apk", '"' + metadata["sha256"] + '"')
        with self.assertRaises(Rejected):
            self.releases.publish(self.apk, 15, "0.10.3")

    def test_only_update_channel_is_public(self):
        app = Application(TeamService(self.root / "api"))
        app.releases.publish(self.apk, 14, "0.10.2")
        def request(path, scheme="https", extra=None):
            env = dict(REMOTE_ADDR="127.0.0.1", REQUEST_METHOD="GET", PATH_INFO=path,
                       QUERY_STRING="", **{"wsgi.url_scheme": scheme, "wsgi.input": io.BytesIO()})
            env.update(extra or {})
            status = []
            body = b"".join(app(env, lambda code, headers: status.append((code, headers))))
            return status[0], body
        status, body = request("/v1/app-release")
        self.assertTrue(status[0].startswith("200"))
        self.assertEqual(14, json.loads(body)["versionCode"])
        metadata = app.releases.current()
        status, body = request("/v1/app-release/apk", extra={"HTTP_IF_MATCH": '"' + metadata["sha256"] + '"'})
        self.assertTrue(status[0].startswith("200"))
        self.assertIn(("Content-Type", "application/vnd.android.package-archive"), status[1])
        self.assertEqual(self.apk.read_bytes(), body)
        self.assertTrue(request("/v1/catalog")[0][0].startswith("401"))
        self.assertTrue(request("/v1/app-release", "http")[0][0].startswith("403"))
        self.assertTrue(request("/v1/app-release", extra={"HTTP_ORIGIN": "https://other.example"})[0][0].startswith("403"))

    def test_invalid_metadata_cannot_replace_release(self):
        first = self.releases.publish(self.apk, 14, "0.10.2")
        for code, name in ((True, "bad"), (0, "bad"), (15, "x" * 41)):
            with self.assertRaises(Rejected):
                self.releases.publish(self.apk, code, name)
        self.assertEqual(first, self.releases.current())


if __name__ == "__main__":
    unittest.main()
