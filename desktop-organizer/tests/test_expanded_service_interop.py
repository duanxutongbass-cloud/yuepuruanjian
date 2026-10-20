"""Real desktop archive -> v2 HTTP -> private PDF on activated v4 storage."""
import json
import io
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import uuid
from pypdf import PdfWriter

from score_organizer.services import runtime_services
from score_organizer.storage.expanded_database import activate_candidate
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.import_service import sha256_file
from score_organizer.services.team_publish import TeamClient
from test_library_upgrade_snapshot import make_library,contents,db_state


def test_actual_v2_desktop_to_service_preserves_full_numbers_pdf_and_old_library(tmp_path):
    fixture=Path(__file__).resolve().parents[2]/'team-service/expanded_interop_fixture.py'
    remote=tmp_path/'remote';remote.mkdir()
    process=subprocess.Popen([sys.executable,str(fixture),str(remote)],stdout=subprocess.DEVNULL,
        stderr=(remote/'error.log').open('w'),creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    try:
        ready=remote/'ready-port';deadline=time.monotonic()+90
        while not ready.exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.05)
        assert ready.exists(),(remote/'error.log').read_text()
        old,root,sources=make_library(tmp_path)
        before,files=db_state(old.path),contents(root)
        manifest=prepare_relocated_library(old.path,tmp_path/'candidate')
        db=activate_candidate(manifest)
        pdf=(remote/'source.pdf').read_bytes()
        paths=[]
        for sequence in ('1000','9223372036854775807'):
            for code in ('01','02','03'):
                path=tmp_path/f'{code}-01-{sequence}-Work{sequence}.pdf'
                writer=PdfWriter();writer.append(io.BytesIO(pdf));writer.add_metadata({'/Title':f'Synthetic {sequence} {code}'})
                with path.open('wb') as output:writer.write(output)
                paths.append(path)
        original_hashes={path:sha256_file(path) for path in paths}
        batch=runtime_services.importer(db).import_paths(paths)
        archive=runtime_services.archive(db,db.get_setting('output_root'))
        archive.commit_plan(batch,archive.build_plan(batch))
        client=TeamClient('http://127.0.0.1:'+ready.read_text(),str(uuid.uuid4()),allow_test_http=True)
        actor=client.login('director','synthetic rehearsal passphrase')
        assert actor['numberingProtocol']==2
        publisher=runtime_services.publish(db)
        rows=[r for r in db.list_archived_scores() if len(r['seq'])>3]
        plan=publisher.build_plan({r['id']:[actor['accountId']] for r in rows})
        published=publisher.publish(plan,client,str(uuid.uuid4()),confirmed=True)
        assert len(published['artifactIds'])==6
        catalog=client.request('GET','/v2/catalog')
        assert catalog['protocolVersion']==2
        assert {r['sequence'] for r in catalog['artifacts']}=={'1000','9223372036854775807'}
        for part in catalog['artifacts']:
            request=urllib.request.Request(client.address+'/v2/artifacts/'+part['artifactId'],headers={
                'Authorization':'Bearer '+client.token,'X-Device-ID':client.device_id})
            with urllib.request.urlopen(request,timeout=15) as response:
                assert hashlib.sha256(response.read()).hexdigest()==part['hash']
        assert db_state(old.path)==before and contents(root)==files
        assert all(sha256_file(path)==original_hashes[path] for path in paths)
        for row in rows:
            assert all(sha256_file(path)==row['source_sha256'] for path in archive.score_paths(row))
    finally:
        process.terminate()
        try:process.wait(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
