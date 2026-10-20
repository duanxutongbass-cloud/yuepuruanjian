"""Loopback-only malformed HTTP responses for Android transfer boundary tests.

Synthetic bytes exercise transport validation, not PDF rendering or production auth.
"""
import hashlib
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import re
import sys

root,mode=Path(sys.argv[1]),sys.argv[2]
assert mode in {'full','range','etag','truncated'}
data=bytes(i%251 for i in range(160000))
checksum=hashlib.sha256(data).hexdigest()

class Handler(BaseHTTPRequestHandler):
    protocol_version='HTTP/1.1'
    def log_message(self,*args):pass
    def do_GET(self):
        assert self.client_address[0]=='127.0.0.1'
        assert self.headers.get('Authorization')=='Bearer '+'s'*43
        assert re.fullmatch('[a-f0-9-]{36}',self.headers.get('X-Device-Id',''))
        assert self.headers.get('If-Match')=='"'+checksum+'"'
        value=self.headers.get('Range')
        start=int(re.fullmatch(r'bytes=(\d+)-',value)[1]) if value else 0
        offset=0 if mode=='full' else start
        status=206 if offset else 200
        self.send_response(status)
        self.send_header('Content-Type','application/pdf')
        self.send_header('ETag','"'+('0'*64 if mode=='etag' else checksum)+'"')
        if status==206:self.send_header('Content-Range',f'bytes {0 if mode=="range" else offset}-{len(data)-1}/{len(data)}')
        self.send_header('Content-Length',str(len(data)-offset))
        self.send_header('Connection','close')
        self.end_headers()
        self.wfile.write(data[offset:offset+32000] if mode=='truncated' else data[offset:])
        self.wfile.flush()
        self.close_connection=True

with HTTPServer(('127.0.0.1',0),Handler) as server:
    pending=root/'ready-port.pending'
    pending.write_text(str(server.server_port),encoding='ascii')
    pending.replace(root/'ready-port')
    server.serve_forever()
