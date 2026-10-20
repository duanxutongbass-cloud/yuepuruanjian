"""Isolated, disposable Python API fixture for the Android client contract test."""
import sys
from pathlib import Path
from wsgiref.simple_server import make_server, WSGIRequestHandler
from server import Application
from service import TeamService


class Quiet(WSGIRequestHandler):
    def log_message(self, *args):
        pass


if __name__ == "__main__":
    directory = Path(sys.argv[1])
    service = TeamService(directory)
    service.bootstrap("Synthetic interoperability ensemble", "director", "Director", "synthetic rehearsal passphrase")
    with make_server("127.0.0.1", 0, Application(service, True), handler_class=Quiet) as server:
        pending = directory / "ready-port.pending"
        pending.write_text(str(server.server_port), encoding="ascii")
        pending.replace(directory / "ready-port")
        server.serve_forever()
