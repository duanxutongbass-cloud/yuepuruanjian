"""Loopback-only synthetic score service. No real accounts, passwords or score files."""
import argparse
import hashlib
import hmac
import json
import os
import re
import secrets
import signal
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


def uid(value):
    return str(uuid.uuid5(uuid.NAMESPACE_URL, 'synthetic-score-test/' + value))


def pdf(part, version):
    # Independently authored PDF per part; no source library, attachments or hidden parts.
    stream = f'BT /F1 22 Tf 50 760 Td (SYNTHETIC TEST - {part.upper()}) Tj 0 -35 Td (Edition {version} - Offline rehearsal) Tj ET\n'
    for staff in range(5):
        y = 640 - staff * 105
        for line in range(5):
            stream += f'50 {y + line * 10} m 540 {y + line * 10} l S\n'
        for note in range(8):
            x = 90 + note * 55
            ny = y + (note % 5) * 10
            stream += f'{x} {ny} 8 5 re f {x + 8} {ny} m {x + 8} {ny + 35} l S\n'
    objs = ['<< /Type /Catalog /Pages 2 0 R >>', '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
            '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
            '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
            f'<< /Length {len(stream)} >>\nstream\n{stream}endstream']
    data = b'%PDF-1.4\n'
    offsets = [0]
    for index, obj in enumerate(objs, 1):
        offsets.append(len(data))
        data += f'{index} 0 obj\n{obj}\nendobj\n'.encode()
    xref = len(data)
    data += b'xref\n0 6\n0000000000 65535 f \n'
    data += ''.join(f'{n:010d} 00000 n \n' for n in offsets[1:]).encode()
    return data + f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode()


class State:
    def __init__(self, control=None):
        self.run = str(uuid.uuid4())
        self.codes = {a: secrets.token_urlsafe(12) for a in ('alice', 'bob')}
        self.sessions = {}
        self.control = control
        self.lock = threading.RLock()
        self.settings = {}
        self.audit = []

    def config(self):
        if self.control and self.control.exists():
            self.settings = json.loads(self.control.read_text())
        return self.settings

    def principal(self, account):
        part = {'alice': 'Violin', 'bob': 'Cello'}[account]
        return dict(teamId=uid(self.run), accountId=uid(self.run + account), memberId=uid(self.run + account),
                    displayName='测试成员 ' + account, part=part)

    def artifact(self, account):
        p = self.principal(account)
        v = self.config().get('version', 1)
        data = pdf(p['part'], v)
        a = dict(artifactId=uid(self.run + account + str(v)), sourceVersionId=uid(self.run + account + 'source' + str(v)),
                 scorePdfId=uid(self.run + account + 'pdf'), pieceId=uid(self.run + account + 'piece'),
                 partId=uid(self.run + account + 'part'), definitionId=uid(self.run + account + 'definition'),
                 title='合成练习曲 · ' + p['part'], partName=p['part'], versionNumber=v,
                 hash=hashlib.sha256(data).hexdigest(), size=len(data), pageMap=[dict(sourcePage=0, transform='identity')],
                 independentSource=True)
        return a, data

    def manifest(self, account):
        cfg = self.config()
        arts = [] if account in cfg.get('revoked', []) else [self.artifact(account)[0]]
        revision = cfg.get('revision', cfg.get('version', 1))
        return dict(protocolVersion=1, revision=revision, operationId=self.run + '/' + str(revision),
                    principal=self.principal(account), artifacts=arts)


class Handler(BaseHTTPRequestHandler):
    server_version = 'SyntheticLoopback/1'

    def log_message(self, fmt, *args):
        pass  # Never log login codes, bearer tokens or user-supplied bodies.

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authenticated(self):
        st = self.server.state
        token = self.headers.get('Authorization', '').removeprefix('Bearer ')
        pair = st.sessions.get(token)
        if not pair or pair[1] != self.headers.get('X-Device-Id') or pair[1] in st.config().get('revokedDevices', []):
            self.reply(401, dict(error='session_or_device_rejected'))
            return None
        # No client-supplied member identity, including query/header overrides.
        if urlsplit(self.path).query or self.headers.get('X-Member-Id'):
            self.reply(403, dict(error='identity_override_rejected'))
            return None
        return pair[0]

    def body(self):
        n = int(self.headers.get('Content-Length', '0'))
        if not 0 < n < 16384:
            raise ValueError('body')
        return json.loads(self.rfile.read(n))

    def do_POST(self):
        st = self.server.state
        try:
            body = self.body()
            with st.lock:
                if self.path == '/login':
                    if set(body) != {'account', 'loginCode', 'deviceId'}:
                        return self.reply(403, dict(error='unexpected_identity_fields'))
                    account, device = body['account'], body['deviceId']
                    if account not in st.codes or not hmac.compare_digest(str(body['loginCode']), st.codes[account]) or not re.fullmatch(r'[a-zA-Z0-9-]{8,80}', device):
                        return self.reply(401, dict(error='login_rejected'))
                    if device in st.config().get('revokedDevices', []):
                        return self.reply(401, dict(error='device_rejected'))
                    token = secrets.token_urlsafe(32)
                    st.sessions[token] = account, device
                    st.audit.append(('login', account, device))
                    return self.reply(200, dict(token=token, principal=st.principal(account)))
                account = self.authenticated()
                if account is None:
                    return
                if self.path in ('/authorize-commit', '/receipt'):
                    expected = st.manifest(account)
                    if body != {k: expected[k] for k in ('revision', 'artifacts')}:
                        return self.reply(403, dict(error='authorization_changed'))
                    st.audit.append((self.path[1:], account))
                    return self.reply(200, dict(authorized=True))
                self.reply(404, dict(error='not_found'))
        except (ValueError, KeyError, TypeError):
            self.reply(400, dict(error='invalid_request'))

    def do_GET(self):
        st = self.server.state
        with st.lock:
            account = self.authenticated()
            if account is None:
                return
            if self.path == '/manifest':
                st.audit.append(('manifest', account))
                return self.reply(200, st.manifest(account))
            a, data = st.artifact(account)
            if self.path != '/artifacts/' + a['artifactId'] or account in st.config().get('revoked', []):
                return self.reply(403, dict(error='resource_forbidden'))
            offset = 0
            requested = self.headers.get('Range')
            if requested:
                match = re.fullmatch(r'bytes=(\d+)-', requested)
                if not match or int(match[1]) >= len(data):
                    return self.reply(416, dict(error='range'))
                offset = int(match[1])
            if self.headers.get('If-Match', a['hash']) != a['hash']:
                return self.reply(412, dict(error='version_changed'))
            st.audit.append(('download', account, offset))
            self.send_response(206 if requested else 200)
            self.send_header('Content-Type', 'application/pdf')
            self.send_header('ETag', a['hash'])
            self.send_header('Content-Length', str(len(data) - offset))
            if requested:
                self.send_header('Content-Range', f'bytes {offset}-{len(data)-1}/{len(data)}')
            self.end_headers()
            mode = st.config().get('fault')
            payload = data[offset:]
            if mode == 'truncate':
                payload = payload[:max(1, len(payload) // 2)]
            if mode == 'corrupt':
                payload = b'X' + payload[1:]
            try:
                self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass
            self.close_connection = True


def make_server(port=0, control=None):
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.state = State(control)
    return server


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--runtime', type=Path, required=True, help='Temporary local run directory, never commit')
    args = parser.parse_args()
    args.runtime.mkdir(mode=0o700, parents=True, exist_ok=True)
    server = make_server(args.port, args.runtime / 'control.json')
    credentials = args.runtime / 'login-codes.json'
    fd = os.open(credentials, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(dict(codes=server.state.codes, port=server.server_port), f)
    print(f'Loopback test service on 127.0.0.1:{server.server_port}; temporary synthetic login codes: {credentials}', flush=True)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        credentials.unlink(missing_ok=True)
        server.server_close()
