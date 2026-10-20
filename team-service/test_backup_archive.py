from contextlib import closing
import json
from pathlib import Path
import sqlite3
import zipfile

from backup_archive import create_archive, restore_archive
from catalog import Catalog
from service import TeamService, Rejected
from test_accounts import AccountFixture, PASSWORD
from pypdf import PdfWriter


class BackupArchiveTest(AccountFixture):
    def setUp(self):
        super().setUp()
        self.catalog = Catalog(self.service)
        source = Path(self.tmp.name) / "source.pdf"
        with PdfWriter() as pdf:
            pdf.add_blank_page(595, 842)
            pdf.write(source)
        self.original = source.read_bytes()
        self.artifact = self.catalog.import_part(source, "曲谱", "小提琴", True)
        self.target = Path(self.tmp.name) / "full.zip"

    def test_restore_contains_matching_pdf_permissions_and_requires_login_again(self):
        self.catalog.dispatch("POST", "/v1/admin/grants", dict(accountId=self.admin_id, artifactId=self.artifact, allowed=True), self.admin["token"], self.device)
        self.catalog.dispatch("POST", "/v1/admin/artifacts/publish", dict(artifactId=self.artifact, published=True), self.admin["token"], self.device)
        self.call("/v1/admin/invitations", {})
        create_archive(self.service, self.target)
        destination = Path(self.tmp.name) / "restored"
        self.assertEqual(1, restore_archive(self.target, destination)["artifacts"])
        restored = TeamService(destination, lambda: self.now[0])
        self.rejected(401, lambda: restored.dispatch("GET", "/v1/me", None, self.admin["token"], self.device, "127.0.0.1"))
        login = restored.login(dict(username="director", password=PASSWORD, deviceId=self.device, deviceName="restored"), "127.0.0.1")
        reply = Catalog(restored).dispatch("GET", "/v1/artifacts/" + self.artifact, None, login["token"], self.device)
        self.assertEqual(self.original, b"".join(reply))
        with restored.connection() as db:
            self.assertEqual(0, db.execute("SELECT count(*) FROM invitations").fetchone()[0])

    def test_existing_backup_and_restore_destination_are_preserved(self):
        create_archive(self.service, self.target)
        before = self.target.read_bytes()
        self.rejected(409, lambda: create_archive(self.service, self.target))
        self.assertEqual(before, self.target.read_bytes())
        self.rejected(409, lambda: restore_archive(self.target, self.service.directory))
        self.assertEqual(self.admin_id, self.login("director")["principal"]["accountId"])

    def test_corrupt_or_missing_pdf_prevents_backup_without_publishing_partial_file(self):
        path = self.catalog.root / (self.artifact + ".pdf")
        path.write_bytes(b"bad")
        self.rejected(409, lambda: create_archive(self.service, self.target))
        self.assertFalse(self.target.exists())
        path.unlink()
        self.rejected(409, lambda: create_archive(self.service, self.target))

    def test_corrupt_archive_and_extra_path_cannot_create_restored_directory(self):
        create_archive(self.service, self.target)
        for kind in ("corrupt", "traversal", "missing", "catalog"):
            changed = Path(self.tmp.name) / (kind + ".zip")
            with zipfile.ZipFile(self.target) as src, zipfile.ZipFile(changed, "w") as dest:
                for info in src.infolist():
                    if kind == "missing" and info.filename.endswith(".pdf"):
                        continue
                    data = src.read(info.filename)
                    if kind == "corrupt" and info.filename.endswith(".pdf"):
                        data = b"x" * len(data)
                    if kind == "catalog" and info.filename == "manifest.json":
                        value = json.loads(data)
                        value["files"]["artifacts/00000000-0000-0000-0000-000000000000.pdf"] = dict(size=1, sha256="0" * 64)
                        data = json.dumps(value).encode()
                    dest.writestr(info, data)
                if kind == "traversal":
                    dest.writestr("../outside.txt", "bad")
            destination = Path(self.tmp.name) / ("restored-" + kind)
            with self.assertRaises(Rejected):
                restore_archive(changed, destination)
            self.assertFalse(destination.exists())
        self.assertFalse((Path(self.tmp.name) / "outside.txt").exists())
