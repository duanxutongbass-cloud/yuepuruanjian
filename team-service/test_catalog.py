import hashlib
import io
import json
import unittest
import uuid
from pathlib import Path
from pypdf import PdfWriter

from catalog import Catalog
from server import Application
from test_accounts import AccountFixture


class CatalogTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.catalog = Catalog(self.service)
        self.source = Path(self.tmp.name) / "synthetic-part.pdf"
        with PdfWriter() as writer:
            writer.add_blank_page(595, 842)
            writer.add_blank_page(595, 842)
            writer.write(self.source)
        self.original = self.source.read_bytes()
        self.artifact = self.catalog.import_part(self.source, "Synthetic rehearsal", "Violin", True)
        self.listener = self.member()

    def route(self, path, body=None, login=None, range_header=None, if_match=None):
        return self.catalog.dispatch("GET" if body is None else "POST", path, body,
                                     (login or self.admin)["token"], self.device, range_header, if_match)

    def grant(self, login=None, allowed=True):
        self.route("/v1/admin/grants", dict(accountId=(login or self.listener)["principal"]["accountId"],
                                            artifactId=self.artifact, allowed=allowed))

    def publish(self, value=True):
        self.route("/v1/admin/artifacts/publish", dict(artifactId=self.artifact, published=value))

    def download(self, login=None, **args):
        return self.route("/v1/artifacts/" + self.artifact, login=login or self.listener, **args)

    def test_staging_preserves_original_and_neither_grant_nor_publish_alone_releases_file(self):
        self.assertEqual(self.original, self.source.read_bytes())
        self.grant()
        self.rejected(404, self.download)
        self.assertEqual([], self.route("/v1/catalog", login=self.listener)["artifacts"])
        self.publish()
        self.assertEqual(self.original, b"".join(self.download()))
        self.grant(allowed=False)
        self.rejected(404, self.download)

    def test_admin_role_does_not_bypass_file_grants(self):
        self.publish()
        self.assertEqual([], self.route("/v1/catalog")["artifacts"])
        self.rejected(404, lambda: self.download(self.admin))
        self.grant(self.admin)
        self.assertEqual(self.original, b"".join(self.download(self.admin)))

    def test_other_member_cannot_guess_file_or_change_grants(self):
        self.grant()
        self.publish()
        other = self.member("cellist")
        self.rejected(404, lambda: self.download(other))
        self.rejected(403, lambda: self.route("/v1/admin/grants", dict(accountId=other["principal"]["accountId"],
                                                                     artifactId=self.artifact, allowed=True), other))
        self.rejected(404, lambda: self.route("/v1/artifacts/" + str(uuid.uuid4()), login=other))
        self.rejected(404, lambda: self.route("/v1/artifacts/../../team.sqlite3", login=other))

    def test_revocation_rechecks_even_for_range_retry_and_old_manifest(self):
        self.grant()
        self.publish()
        manifest = self.route("/v1/catalog", login=self.listener)
        checksum = hashlib.sha256(self.original).hexdigest()
        reply = self.download(range_header="bytes=10-99", if_match='"' + checksum + '"')
        self.assertEqual(self.original[10:100], b"".join(reply))
        self.rejected(412, lambda: self.download(if_match='"' + "0" * 64 + '"'))
        self.rejected(416, lambda: self.download(range_header="bytes=999999-"))
        self.grant(allowed=False)
        self.rejected(404, lambda: self.download(range_header="bytes=100-", if_match='"' + checksum + '"'))
        current = self.route("/v1/catalog", login=self.listener)
        self.assertGreater(current["revision"], manifest["revision"])
        self.assertEqual([], current["artifacts"])

    def test_unpublish_and_account_disable_stop_new_downloads(self):
        self.grant()
        self.publish()
        self.publish(False)
        self.rejected(404, self.download)
        self.publish()
        self.call("/v1/admin/members/status", {"accountId": self.listener["principal"]["accountId"], "active": False})
        self.rejected(401, self.download)

    def test_missing_or_truncated_artifact_fails_without_sending_pdf(self):
        self.grant()
        self.publish()
        target = self.catalog.root / (self.artifact + ".pdf")
        target.write_bytes(b"%PDF-broken")
        self.rejected(503, self.download)

    def test_pdf_import_requires_explicit_independent_part_and_rejects_attachments(self):
        self.rejected(400, lambda: self.catalog.import_part(self.source, "Title", "Part"))
        attached = Path(self.tmp.name) / "attachment.pdf"
        with PdfWriter() as writer:
            writer.add_blank_page(595, 842)
            writer.add_attachment("another-part.pdf", b"private second part")
            writer.write(attached)
        self.rejected(400, lambda: self.catalog.import_part(attached, "Title", "Part", True))
        self.assertEqual(1, len(list(self.catalog.root.glob("*.pdf"))))
        self.assertEqual([], list(self.catalog.root.glob("*.pending")))

    def test_http_file_response_returns_only_authorized_bytes_and_consistent_range_headers(self):
        app = Application(self.service)
        self.grant()
        self.publish()
        headers, status = {}, []
        def start(s, h):
            status.append(s)
            headers.update(h)
        response = app({"REQUEST_METHOD": "GET", "PATH_INFO": "/v1/artifacts/" + self.artifact,
                        "wsgi.url_scheme": "https", "REMOTE_ADDR": "127.0.0.1",
                        "HTTP_AUTHORIZATION": "Bearer " + self.listener["token"], "HTTP_X_DEVICE_ID": self.device,
                        "HTTP_RANGE": "bytes=20-", "wsgi.input": io.BytesIO()}, start)
        self.assertEqual(self.original[20:], b"".join(response))
        self.assertEqual(["206 Partial Content"], status)
        self.assertEqual(str(len(self.original) - 20), headers["Content-Length"])
        self.assertEqual(f"bytes 20-{len(self.original)-1}/{len(self.original)}", headers["Content-Range"])
        self.assertEqual("no-store", headers["Cache-Control"])


if __name__ == "__main__":
    unittest.main()
