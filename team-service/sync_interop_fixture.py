"""Disposable synthetic score publication for Android download/Room/PDF integration tests."""
import hashlib
import io
import json
from pathlib import Path
import sys
import uuid
import zipfile
from wsgiref.simple_server import make_server
from pypdf import PdfWriter
from interop_fixture import Quiet
from server import Application
from service import TeamService
from app_release import AppReleases

if __name__ == "__main__":
    directory = Path(sys.argv[1])
    service = TeamService(directory)
    service.bootstrap("Synthetic sync ensemble", "director", "Director", "synthetic rehearsal passphrase")
    device = str(uuid.uuid4())
    login = service.dispatch("POST", "/v1/auth/login", dict(username="director", password="synthetic rehearsal passphrase",
        deviceId=device, deviceName="Synthetic publisher"), "", device, "127.0.0.1")
    output = io.BytesIO()
    writer = PdfWriter()
    for _ in range(1000 if '--large' in sys.argv[2:] else 1):
        writer.add_blank_page(595, 842)
    writer.write(output)
    pdf = output.getvalue()
    app = Application(service, True)
    spec = dict(requestId=str(uuid.uuid4()), title="合成同步练习曲", partName="01 第一小提琴", scoreNumber="0101001",
        category="01 合成分类", sha256=hashlib.sha256(pdf).hexdigest(), size=len(pdf), independentSource=True)
    upload = app.publisher.prepare(spec, login["token"], device)["uploadId"]
    app.publisher.upload(upload, io.BytesIO(pdf), len(pdf), login["token"], device)
    app.publisher.publish(dict(requestId=str(uuid.uuid4()), assignments=[dict(artifactId=upload,
        accountIds=[login["principal"]["accountId"]])]), login["token"], device)
    apk = directory / "synthetic.apk"
    with zipfile.ZipFile(apk, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"transport fixture, not an installable APK")
        archive.writestr("classes.dex", b"synthetic")
    AppReleases(service.directory).publish(apk, 15, "synthetic-update", "Synthetic transport verification")
    def traced(env,start_response):
        if '--trace' in sys.argv[2:] and env['PATH_INFO'].startswith('/v1/artifacts/'):
            with (directory/'ranges.jsonl').open('a',encoding='utf-8') as log:
                log.write(json.dumps(dict(range=env.get('HTTP_RANGE'),ifMatch=env.get('HTTP_IF_MATCH'))) + '\n')
        return app(env,start_response)
    with make_server("127.0.0.1", 0, traced, handler_class=Quiet) as server:
        pending = directory / "ready-port.pending"
        pending.write_text(str(server.server_port), encoding="ascii")
        pending.replace(directory / "ready-port")
        server.serve_forever()
