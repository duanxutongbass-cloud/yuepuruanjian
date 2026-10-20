"""Private folder snapshots; session ownership, CAS and compact durable receipts.

Piece IDs are opaque references, never PDF grants or a name/number matching rule.
Only the head stores image data. Retry receipts never duplicate an 8 MiB snapshot.
"""
import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import re
import struct
import zlib

from service import UUID, fields, require
from backup_archive import unique_object

MAX_FOLDERS = 8 * 1024 * 1024
MAX_FOLDER_BODY = MAX_FOLDERS + 16 * 1024
MAX_OPERATIONS = 50_000
MAX_TOTAL_OPERATIONS = 1_000_000
MAX_TOTAL_HEADS = 512 * 1024 * 1024
MAX_REVISION = 2**53 - 1


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def identity(value):
    require(isinstance(value, str) and value.strip() and len(value.encode('utf-16-le')) <= 400 and
            not any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in value), 'invalid_folder')


def cover_png(encoded):
    """Bound Android's static RGB/RGBA PNG output before storing it, without a decoder.

    CRC/chunk ordering, raster dimensions and the exact bounded zlib stream are
    checked. App consumers also decode the bitmap. See W3C PNG sections 5, 10, 11.
    """
    require(isinstance(encoded, str) and len(encoded) <= 700_000, 'invalid_cover')
    try:
        raw = base64.b64decode(encoded, validate=True)
    except (ValueError, binascii.Error):
        require(False, 'invalid_cover')
    require(8 <= len(raw) <= 512 * 1024 and base64.b64encode(raw).decode('ascii') == encoded and
            raw[:8] == b'\x89PNG\r\n\x1a\n', 'invalid_cover')
    pos, header, ended, pixels, closed = 8, None, False, bytearray(), False
    while pos < len(raw):
        require(pos + 12 <= len(raw), 'invalid_cover')
        length = int.from_bytes(raw[pos:pos+4], 'big')
        kind = raw[pos+4:pos+8]
        end = pos + 12 + length
        require(end <= len(raw) and re.fullmatch(b'[A-Za-z]{4}', kind) and kind[2] & 32 == 0, 'invalid_cover')
        data = raw[pos+8:pos+8+length]
        require(zlib.crc32(kind + data) == int.from_bytes(raw[end-4:end], 'big'), 'invalid_cover')
        require(header is not None or kind == b'IHDR', 'invalid_cover')
        if kind == b'IHDR':
            require(header is None and length == 13, 'invalid_cover')
            width, height, depth, color, compression, filtering, interlace = struct.unpack('>IIBBBBB', data)
            require(1 <= width <= 512 and 1 <= height <= 512 and depth == 8 and color in (2, 6) and
                    compression == filtering == interlace == 0, 'invalid_cover')
            header = (height, width * (3 if color == 2 else 4) + 1)
        elif kind == b'IDAT':
            require(not closed, 'invalid_cover')
            pixels.extend(data)
        elif kind == b'IEND':
            require(length == 0 and end == len(raw) and pixels, 'invalid_cover')
            ended = True
        else:
            # Reject unknown critical chunks and animation; metadata stays opaque.
            require(kind[0] & 32 and kind not in (b'acTL', b'fcTL', b'fdAT'), 'invalid_cover')
            if pixels:
                closed = True
        pos = end
    require(ended and header is not None, 'invalid_cover')
    height, stride = header
    expected = height * stride
    try:
        decoder = zlib.decompressobj()
        raster = decoder.decompress(bytes(pixels), expected + 1)
        require(len(raster) == expected and decoder.eof and not decoder.unused_data and
                not decoder.unconsumed_tail and all(raster[row * stride] <= 4 for row in range(height)), 'invalid_cover')
    except zlib.error:
        require(False, 'invalid_cover')


def folder_payload(folders):
    require(isinstance(folders, list) and len(folders) <= 100, 'invalid_folder')
    ids = set()
    for folder in folders:
        fields(folder, {'id', 'name', 'pieces', 'coverPiece', 'image'})
        identity(folder['id'])
        require(folder['id'] not in ids, 'invalid_folder')
        ids.add(folder['id'])
        name = folder['name']
        require(isinstance(name, str) and name.strip() and len(name.encode('utf-16-le')) <= 200, 'invalid_folder')
        pieces = folder['pieces']
        require(isinstance(pieces, list) and len(pieces) <= 10000, 'invalid_folder')
        for piece in pieces:
            identity(piece)
        require(len(set(pieces)) == len(pieces), 'invalid_folder')
        if folder['coverPiece'] is not None:
            identity(folder['coverPiece'])
            require(folder['image'] is None, 'invalid_cover')
        if folder['image'] is not None:
            cover_png(folder['image'])
    payload = canonical(folders)
    require(len(payload.encode('utf-8')) <= MAX_FOLDERS, 'folders_too_large', 413)
    return payload


@dataclass
class FolderReply:
    data: dict
    status: int = 200


class PersonalFolders:
    def __init__(self, service):
        self.service = service
        with service.connection() as db:
            db.executescript('''
                CREATE TABLE IF NOT EXISTS personal_folder_heads(
                    account TEXT PRIMARY KEY REFERENCES accounts(id), revision INTEGER NOT NULL,
                    payload TEXT NOT NULL, sha256 TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS personal_folder_operations(
                    account TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, result TEXT NOT NULL, status INTEGER NOT NULL,
                    PRIMARY KEY(account,request));
            ''')

    def dispatch(self, method, path, body, token, device):
        with self.service.connection() as db:
            actor = self.service.authenticate(db, token, device)
            require(path == '/v1/personal-folders' and method in ('GET', 'POST'), 'not_found', 404)
            aid = actor['id']
            owner = dict(protocolVersion=1, accountId=aid, teamId=self.service.principal(db, actor)['teamId'])
            current = db.execute('SELECT * FROM personal_folder_heads WHERE account=?', (aid,)).fetchone()
            payload = current['payload'] if current else '[]'
            revision = current['revision'] if current else 0
            digest = current['sha256'] if current else hashlib.sha256(b'[]').hexdigest()
            if method == 'GET':
                return dict(owner, revision=revision, payload=payload, sha256=digest)
            fields(body, {'protocolVersion', 'requestId', 'baseRevision', 'folders'})
            require(type(body['protocolVersion']) is int and body['protocolVersion'] == 1, 'protocol_unsupported')
            request = body['requestId']
            base = body['baseRevision']
            require(isinstance(request, str) and UUID.fullmatch(request) and type(base) is int and
                    0 <= base < MAX_REVISION, 'invalid_revision')
            proposed = folder_payload(body['folders'])
            fingerprint = hashlib.sha256(canonical(body).encode('utf-8')).hexdigest()
            previous = db.execute('SELECT * FROM personal_folder_operations WHERE account=? AND request=?', (aid, request)).fetchone()
            if previous:
                require(previous['fingerprint'] == fingerprint, 'request_id_reused', 409)
                return FolderReply(json.loads(previous['result']), previous['status'])
            # Never discard receipts then treat an old retry as a fresh write.
            require(db.execute('SELECT count(*) FROM personal_folder_operations WHERE account=?', (aid,)).fetchone()[0] < MAX_OPERATIONS and
                    db.execute('SELECT count(*) FROM personal_folder_operations').fetchone()[0] < MAX_TOTAL_OPERATIONS,
                    'folder_operation_quota', 413)
            if base != revision:
                result = dict(owner, error='folder_conflict', requestId=request,
                              currentRevision=revision, currentSha256=digest)
                status = 409
            else:
                require(revision < MAX_REVISION, 'invalid_revision')
                if db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='artifact_tombstones'").fetchone():
                    # Persisted deletion plans name the retired incarnation. Reused
                    # numbers must not retire the new piece's folders as well.
                    retired={ref for row in db.execute('SELECT plan FROM permanent_deletion_jobs WHERE completed IN (1,2)')
                             for ref in json.loads(row[0],object_pairs_hook=unique_object)['pieceReferences']}
                    require(not any(set(f['pieces'])&retired or f['coverPiece'] in retired for f in json.loads(proposed)),
                            'folder_piece_permanently_deleted',409)
                used = db.execute('SELECT coalesce(sum(length(cast(payload AS BLOB))),0) FROM personal_folder_heads').fetchone()[0]
                require(used - (len(payload.encode('utf-8')) if current else 0) + len(proposed.encode('utf-8')) <= MAX_TOTAL_HEADS, 'folder_storage_quota', 413)
                revision += 1
                digest = hashlib.sha256(proposed.encode('utf-8')).hexdigest()
                db.execute('''INSERT INTO personal_folder_heads VALUES(?,?,?,?) ON CONFLICT(account)
                    DO UPDATE SET revision=excluded.revision,payload=excluded.payload,sha256=excluded.sha256''',
                           (aid, revision, proposed, digest))
                result = dict(owner, requestId=request, revision=revision, sha256=digest)
                status = 200
            db.execute('INSERT INTO personal_folder_operations VALUES(?,?,?,?,?)', (aid, request, fingerprint, canonical(result), status))
            self.service.audit(db, aid, 'folder_save' if status == 200 else 'folder_conflict')
            return FolderReply(result, status)
