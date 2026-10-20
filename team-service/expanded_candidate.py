"""Isolated v3 storage prerequisite. No serving entry point or expanded writer.

Only a verified v1 full archive can seed a new candidate directory. The normal
service and its archive reader still refuse database version 3.
"""
from contextlib import contextmanager, closing
import json
import os
from pathlib import Path
import secrets
import sqlite3
import tempfile
import time
import uuid

from backup_archive import fingerprint, plain_path, restore_archive, unique_object
from score_numbers import current_reader, require_current_writer
from security import password_hash
from service import TeamService, UUID, Rejected, require

MARKER = 'expanded-candidate.json'
FORMAT = 'score-team-expanded-candidate-v1'
IDENTITY = 'numberingCandidateId'
ROOT = 'numberingCandidateRoot'
NAMING = 'scoreNamingVersion'


def _inventory(db, reader=current_reader, writer=require_current_writer):
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if 'artifact_metadata' in tables:
        for row in db.execute('SELECT number FROM artifact_metadata'):
            reader(row[0])
    if 'uploads' in tables:
        for row in db.execute('SELECT spec FROM uploads'):
            spec = json.loads(row[0], object_pairs_hook=unique_object)
            require(isinstance(spec, dict) and 'scoreNumber' in spec, 'candidate_invalid_inventory', 409)
            writer(spec['scoreNumber'])
    if 'program_freezes' in tables:
        for row in db.execute('SELECT manifest FROM program_freezes'):
            manifest = json.loads(row[0], object_pairs_hook=unique_object)
            require(isinstance(manifest, dict) and isinstance(manifest.get('artifacts'), list),
                    'candidate_invalid_inventory', 409)
            for artifact in manifest['artifacts']:
                require(isinstance(artifact, dict), 'candidate_invalid_inventory', 409)
                reader(artifact.get('scoreNumber'))
    if 'artifact_tombstones' in tables:
        for row in db.execute('SELECT number FROM artifact_tombstones'):
            reader(row[0])


def _copy_new(source, target):
    with source.open('rb') as src, target.open('xb') as dest:
        while block := src.read(1024 * 1024):
            dest.write(block)
        dest.flush()
        os.fsync(dest.fileno())
    target.chmod(0o600)


def prepare_candidate(archive, destination):
    """Never migrate in place. A failed final copy stays incomplete and closed."""
    archive, destination = plain_path(archive), plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(), 'candidate_requires_new_directory', 409)
    original = fingerprint(archive)
    identity, root = str(uuid.uuid4()), str(destination.resolve())
    with tempfile.TemporaryDirectory(prefix='.expanded-candidate-', dir=destination.parent) as tmp:
        restored = Path(tmp) / 'restored'
        restored_proof = restore_archive(archive, restored)
        with closing(sqlite3.connect(restored / 'team.sqlite3')) as db:
            require(db.execute('PRAGMA user_version').fetchone()[0] == 2, 'candidate_requires_database_v2', 409)
            _inventory(db)
            require(not db.execute('SELECT 1 FROM metadata WHERE key IN (?,?,?)', (IDENTITY, ROOT, NAMING)).fetchone(),
                    'candidate_metadata_conflict', 409)
            db.executemany('INSERT INTO metadata VALUES(?,?)', [(IDENTITY, identity), (ROOT, root), (NAMING, '2')])
            db.execute('PRAGMA user_version=3')
            db.commit()
            require(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok' and
                    not db.execute('PRAGMA foreign_key_check').fetchall(), 'candidate_invalid_database', 409)
        require(fingerprint(archive) == original, 'candidate_source_changed', 409)
        marker = dict(format=FORMAT, databaseVersion=3, namingVersion=2, storageId=identity, directory=root)
        copied = _publish_candidate(restored, destination, marker,
            lambda:require(fingerprint(archive) == original, 'candidate_source_changed', 409))
    return dict(databaseVersion=3, namingVersion=2, expandedPublicationReady=False,
                sourceSha256=original['sha256'], copiedFiles=copied, **restored_proof)


def _publish_candidate(restored, destination, marker, check_source, *, marker_name=MARKER):
    # Publish the guarded database first; a partial copy must never expose v2.
    destination.mkdir(mode=0o700, exist_ok=False)
    copies = {'team.sqlite3':fingerprint(restored / 'team.sqlite3')}
    _copy_new(restored / 'team.sqlite3', destination / 'team.sqlite3')
    for source in sorted(restored.rglob('*')):
        if source.name == 'team.sqlite3':
            continue
        target = destination / source.relative_to(restored)
        if source.is_dir():
            target.mkdir(mode=0o700, exist_ok=False)
        else:
            _copy_new(source, target)
            copies[source.relative_to(restored).as_posix()] = fingerprint(source)
    for name, expected in copies.items():
        copied = plain_path(destination / name)
        require(copied.is_file() and copied.stat().st_nlink == 1 and fingerprint(copied) == expected,
                'candidate_copy_changed', 409)
    check_source()
    pending = destination / '.expanded-candidate.pending'
    fd = os.open(pending, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as output:
        json.dump(marker, output, ensure_ascii=False, sort_keys=True)
        output.flush()
        os.fsync(output.fileno())
    pending.chmod(0o600)
    os.link(pending, destination / marker_name)
    pending.unlink()
    return len(copies)


class ExpandedCandidateService(TeamService):
    """Existing authentication on a separate, identity-bound v3 snapshot only."""
    database_version = 3
    naming_version = 2
    marker_format = FORMAT
    marker_file = MARKER
    def __init__(self, directory, clock=time.time):
        self.directory = plain_path(directory)
        self.path = self.directory / 'team.sqlite3'
        self.clock = clock
        self._identity = None
        marker, _ = self._marker()
        self._identity = marker['storageId']
        self.dummy = password_hash(secrets.token_urlsafe(32))
        with self.connection() as db:
            require(db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok' and
                    not db.execute('PRAGMA foreign_key_check').fetchall(), 'candidate_invalid_database', 503)
            require(not db.execute('''SELECT 1 FROM accounts a LEFT JOIN account_memberships m
                ON m.account=a.id WHERE m.account IS NULL LIMIT 1''').fetchone() and
                db.execute('SELECT count(*) FROM join_code WHERE singleton=1').fetchone()[0] == 1,
                'candidate_invalid_database', 503)

    def _marker(self):
        directory = plain_path(self.directory)
        path, marker = plain_path(self.path), plain_path(directory / self.marker_file)
        require(directory.is_dir() and path.is_file() and marker.is_file() and
                path.stat().st_nlink == marker.stat().st_nlink == 1 and marker.stat().st_size <= 4096,
                'candidate_storage_unavailable', 503)
        raw = marker.read_bytes()
        try:
            result = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
        except (ValueError, UnicodeError, Rejected):
            require(False, 'candidate_storage_unavailable', 503)
        require(isinstance(result, dict) and set(result) ==
                {'format','databaseVersion','namingVersion','storageId','directory'} and
                result['format'] == self.marker_format and type(result['databaseVersion']) is int and result['databaseVersion'] == self.database_version and
                type(result['namingVersion']) is int and result['namingVersion'] == self.naming_version and
                isinstance(result['storageId'], str) and UUID.fullmatch(result['storageId']) and
                result['directory'] == str(directory.resolve()) and
                (self._identity is None or result['storageId'] == self._identity), 'candidate_storage_unavailable', 503)
        return result, raw

    def _database(self, db, marker):
        require(db.execute('PRAGMA user_version').fetchone()[0] == self.database_version and
                dict(db.execute('SELECT key,value FROM metadata WHERE key IN (?,?,?)', (IDENTITY, ROOT, NAMING))) ==
                {IDENTITY:marker['storageId'], ROOT:marker['directory'], NAMING:str(self.naming_version)},
                'candidate_database_unavailable', 503)

    @contextmanager
    def connection(self):
        marker, raw = self._marker()
        inode = self.path.stat()
        with closing(sqlite3.connect(self.path.as_uri() + '?mode=rw', uri=True, timeout=15, isolation_level=None)) as db:
            db.row_factory = sqlite3.Row
            try:
                self._database(db, marker)
                db.execute('PRAGMA foreign_keys=ON')
                db.execute('PRAGMA journal_mode=WAL')
                db.execute('PRAGMA synchronous=FULL')
                db.execute('BEGIN IMMEDIATE')
                self._database(db, marker)
                yield db
                require(self._marker()[1] == raw, 'candidate_storage_changed', 503)
                current = self.path.stat()
                require((inode.st_dev, inode.st_ino) == (current.st_dev, current.st_ino), 'candidate_storage_changed', 503)
                self._database(db, marker)
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def backup(self, destination):
        # Raw/v1 backup remains closed. Use the explicit full v2 archive path.
        require(False, 'candidate_backup_not_ready', 503)
