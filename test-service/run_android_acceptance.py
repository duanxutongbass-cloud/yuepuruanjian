"""Run the real APK against a temporary loopback service, then close that service.
Requires already-booted disposable emulator and the syncTest application/test APKs.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import threading
import time
from server import make_server

parser = argparse.ArgumentParser()
parser.add_argument('--adb', default='adb')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)


def adb(*argv, **kwargs):
    return subprocess.run([args.adb, *argv], check=True, text=True, capture_output=True, timeout=60, **kwargs).stdout


assert adb('shell', 'getprop', 'ro.hardware').strip() in ('ranchu', 'goldfish'), 'Only disposable emulator is supported'
adb('shell', 'rm', '-f', '/data/local/tmp/http-step', '/data/local/tmp/http-ack')
adb('reverse', 'tcp:8765', 'tcp:8765')
server = make_server(8765)
worker = threading.Thread(target=server.serve_forever, daemon=True)
worker.start()
log = open(args.output / 'instrumentation.txt', 'w')
command = [args.adb, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
           'com.ensemble.scorelibrary.sync.HttpEndToEndTest', '-e', 'isolatedTestDevice', 'true',
           '-e', 'aliceCode', server.state.codes['alice'], '-e', 'bobCode', server.state.codes['bob'],
           'com.ensemble.scorelibrary.httptest.test/androidx.test.runner.AndroidJUnitRunner']
process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
settings = {
    'corrupt-v2': dict(version=2, fault='corrupt'),
    'truncate-v2': dict(version=2, fault='truncate'),
    'resume-v2': dict(version=2),
    'revoke': dict(version=2, revision=3, revoked=['alice']),
    'regrant': dict(version=2, revision=4),
}
stopped = False
seen = set()
try:
    deadline = time.monotonic() + 1800
    while process.poll() is None and time.monotonic() < deadline:
        step = adb('shell', 'cat', '/data/local/tmp/http-step').strip() if 'http-step' in adb('shell', 'ls', '/data/local/tmp') else ''
        if step and step not in seen:
            if step in settings:
                with server.state.lock:
                    server.state.settings = settings[step]
            elif step == 'stop-service':
                server.shutdown()
                server.server_close()
                worker.join()
                stopped = True
                (args.output / 'service-stopped.txt').write_text('Loopback HTTP server was shut down and closed before offline reader opened. No network or security settings changed.\n')
            elif step != 'finished':
                raise RuntimeError('Unknown device step: ' + step)
            seen.add(step)
            adb('shell', f'echo {step} > /data/local/tmp/http-ack')
            print('Device/host step:', step, flush=True)
        time.sleep(1)
    if process.poll() is None:
        process.terminate()
        raise TimeoutError('Native test timeout')
finally:
    if not stopped:
        server.shutdown()
        server.server_close()
        worker.join()
    log.close()
    (args.output / 'http-audit.json').write_text(json.dumps(server.state.audit, indent=2))
    for name in ('http-authorized-download', 'http-service-stopped-offline-reader'):
        try:
            adb('pull', f'/data/local/tmp/{name}.png', str(args.output / (name + '.png')))
        except subprocess.CalledProcessError:
            pass
    adb('reverse', '--remove', 'tcp:8765')
result = (args.output / 'instrumentation.txt').read_text()
print(result)
assert 'OK (1 test)' in result and 'FAILURES' not in result and 'finished' in seen, 'Native acceptance did not pass'
assert any(row[0] == 'download' and row[2] > 0 for row in server.state.audit), 'No actual resumed HTTP range request'
print('PASS: real app, account isolation, corruption, interruption/range retry, old bytes and ink preserved, revocation, service stopped, native offline reader.')
