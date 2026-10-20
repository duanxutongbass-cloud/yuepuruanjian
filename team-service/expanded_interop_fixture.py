"""Disposable real HTTP fixture; never used by production CLI or Docker."""
from contextlib import closing
import io
from pathlib import Path
import sys
from wsgiref.simple_server import make_server, WSGIRequestHandler

from pypdf import PdfWriter
from service import TeamService
from server import Application
from backup_archive import create_archive
from permanent_storage import prepare_permanent_storage,PermanentStorage
from permanent_runtime import activate
from permanent_backup import create_permanent_archive
import expanded_permanent as expanded


def fixture(root):
    root=Path(root)
    service=TeamService(root/'legacy')
    service.bootstrap('Synthetic expanded ensemble','director','Director','synthetic rehearsal passphrase')
    Application(service)
    archive=root/'legacy.zip';create_archive(service,archive)
    v4=root/'v4';prepare_permanent_storage(archive,v4);activate(v4,service.directory,True)
    current=root/'v4.zip';create_permanent_archive(PermanentStorage(v4),current)
    target=root/'expanded';expanded.prepare(current,target);expanded.activate(target,v4,True)
    pdf=PdfWriter();pdf.add_blank_page(595,842)
    with (root/'source.pdf').open('wb') as stream:pdf.write(stream)
    service=expanded.open_active(target)
    return expanded.ExpandedReadyApplication(service,True)


if __name__=='__main__':
    root=Path(sys.argv[1]);app=fixture(root)
    class Quiet(WSGIRequestHandler):
        def log_message(self,*args):pass
    with make_server('127.0.0.1',0,app,handler_class=Quiet) as server:
        pending=root/'ready-port.pending';pending.write_text(str(server.server_port),encoding='ascii')
        pending.replace(root/'ready-port')
        server.serve_forever()
