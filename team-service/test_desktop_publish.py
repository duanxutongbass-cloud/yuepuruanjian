"""Run with desktop-organizer/src and its runtime on PYTHONPATH."""
import threading
import uuid
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'desktop-organizer' / 'src'))

from waitress import create_server
from pypdf import PdfWriter
from server import Application
from test_accounts import AccountFixture, PASSWORD
from score_organizer.services.team_publish import TeamClient, PublishService
from score_organizer.storage.database import Database
from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import ImportService, sha256_file


class DesktopPublishingTest(AccountFixture):
    def test_real_http_desktop_upload_publish_retry_download_and_backup(self):
        from backup_archive import create_archive, restore_archive
        db = Database(Path(self.tmp.name) / 'desktop.sqlite3')
        db.initialize()
        root = Path(self.tmp.name) / 'library'
        db.set_setting('output_root', str(root))
        sources = []
        for code in ('01','02','03'):
            source = Path(self.tmp.name) / f'{code}01合成测试.pdf'
            pdf = PdfWriter()
            pdf.add_blank_page(595, 842)
            pdf.add_metadata({'/Title':code})
            pdf.write(source)
            sources.append(source)
        batch = ImportService(db).import_paths(sources)
        archive = ArchiveService(db, root)
        archive.commit_plan(batch, archive.build_plan(batch))
        before = {p:sha256_file(p) for p in [*sources, *root.rglob('*.pdf')]}
        member = self.member()
        service = PublishService(db)
        plan = service.build_plan({r['id']:[member['principal']['accountId']] for r in db.list_archived_scores()})
        app = Application(self.service, allow_loopback_http=True)
        server = create_server(app, host='127.0.0.1', port=0, threads=2)
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        client = TeamClient(f'http://127.0.0.1:{server.effective_port}', str(uuid.uuid4()), allow_test_http=True)
        try:
            client.login('director', PASSWORD)
            publisher = str(uuid.uuid4())
            receipt = service.publish(plan, client, publisher, confirmed=True)
            self.assertEqual(receipt, service.publish(plan, client, publisher, confirmed=True))
            self.assertEqual(3, len(receipt['artifactIds']))
            catalog = app.catalog.dispatch('GET','/v1/catalog',None,member['token'],self.device)
            self.assertEqual(3, len(catalog['artifacts']))
            for part in catalog['artifacts']:
                reply = app.catalog.dispatch('GET','/v1/artifacts/'+part['artifactId'],None,member['token'],self.device)
                self.assertGreater(len(b''.join(reply)), 100)
            full = Path(self.tmp.name) / 'publication.zip'
            create_archive(self.service, full)
            self.assertEqual(3, restore_archive(full, Path(self.tmp.name)/'restored')['artifacts'])
            self.assertEqual(before, {p:sha256_file(p) for p in before})
            client.logout()
            self.assertFalse(client.token)
        finally:
            server.task_dispatcher.shutdown()
            server.close()
            thread.join(5)
