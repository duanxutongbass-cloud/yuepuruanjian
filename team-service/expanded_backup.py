"""Explicit candidate v3 full archives; the production v1 reader stays closed."""
from contextlib import closing
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sqlite3
import tempfile
import uuid
import zipfile

from backup_archive import _extract_archive, fingerprint, plain_path, MAX_ENTRIES, MAX_TOTAL
import expanded_candidate as candidate
from service import UUID, require

FORMAT = 'score-team-backup-v2'


def _storage(value):
    require(isinstance(value, dict) and set(value) ==
            {'format','databaseVersion','namingVersion','storageId','directory'} and
            value['format'] == candidate.FORMAT and type(value['databaseVersion']) is int and value['databaseVersion'] == 3 and
            type(value['namingVersion']) is int and value['namingVersion'] == 2 and
            isinstance(value['storageId'], str) and UUID.fullmatch(value['storageId']) and
            isinstance(value['directory'], str) and 0 < len(value['directory']) <= 4096 and
            '\x00' not in value['directory'] and
            (PurePosixPath(value['directory']).is_absolute() or PureWindowsPath(value['directory']).is_absolute()),
            'backup_invalid_candidate_storage')


def validate_expanded_archive(archive, stage):
    manifest = _extract_archive(archive, stage, FORMAT, ('storage',))
    storage, files = manifest['storage'], manifest['files']
    _storage(storage)
    with closing(sqlite3.connect(stage/'team.sqlite3')) as db:
        require(db.execute('PRAGMA user_version').fetchone()[0] == 3 and
                db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok' and
                not db.execute('PRAGMA foreign_key_check').fetchall(), 'backup_invalid_database')
        expected = {candidate.IDENTITY:storage['storageId'], candidate.ROOT:storage['directory'], candidate.NAMING:'2'}
        require(dict(db.execute('SELECT key,value FROM metadata WHERE key IN (?,?,?)', tuple(expected))) == expected,
                'backup_candidate_identity_mismatch')
        require(not db.execute('''SELECT 1 FROM accounts a LEFT JOIN account_memberships m
            ON m.account=a.id WHERE m.account IS NULL LIMIT 1''').fetchone() and
            db.execute('SELECT count(*) FROM join_code WHERE singleton=1').fetchone()[0] == 1,
            'backup_invalid_database')
        rows = db.execute('SELECT id,hash,size FROM artifacts').fetchall()
        wanted = {'artifacts/'+r[0]+'.pdf':dict(size=r[2],sha256=r[1]) for r in rows}
        require({k:v for k,v in files.items() if k != 'team.sqlite3'} == wanted, 'backup_catalog_mismatch')
        db.execute('DELETE FROM sessions'); db.execute('DELETE FROM invitations'); db.commit()
        db.execute('PRAGMA wal_checkpoint(TRUNCATE)'); db.execute('PRAGMA journal_mode=DELETE')
    return dict(artifacts=len(rows), bytes=sum(v['size'] for v in files.values()), storage=storage)


def create_expanded_archive(service, destination):
    require(isinstance(service, candidate.ExpandedCandidateService) and service.database_version == 3 and
            service.naming_version == 2 and service.marker_format == candidate.FORMAT, 'candidate_service_required', 409)
    destination = plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(), 'backup_target_exists', 409)
    with tempfile.TemporaryDirectory(prefix='.expanded-backup-', dir=destination.parent) as tmp:
        stage = Path(tmp); archive = stage/'backup.zip'; database = stage/'team.sqlite3'
        with service.connection() as db:
            storage, _ = service._marker()
            rows = list(db.execute('SELECT id,hash,size FROM artifacts ORDER BY id'))
            require(len(rows)+2 <= MAX_ENTRIES, 'backup_too_large')
            # A separate read connection captures committed WAL while this same
            # writer lock prevents publication from changing the snapshot.
            with closing(sqlite3.connect(service.path.as_uri()+'?mode=ro', uri=True)) as source, \
                    closing(sqlite3.connect(database)) as dest:
                source.backup(dest)
                dest.execute('DELETE FROM sessions'); dest.execute('DELETE FROM invitations'); dest.commit()
                dest.execute('PRAGMA wal_checkpoint(TRUNCATE)'); dest.execute('PRAGMA journal_mode=DELETE')
            database.chmod(0o600)
            files = {'team.sqlite3':fingerprint(database)}
            with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, allowZip64=True) as bundle:
                bundle.write(database, 'team.sqlite3')
                for row in rows:
                    require(isinstance(row['id'], str) and UUID.fullmatch(row['id']), 'backup_invalid_catalog')
                    name = 'artifacts/'+row['id']+'.pdf'; source = plain_path(service.directory/name)
                    expected = dict(size=row['size'],sha256=row['hash'])
                    require(source.is_file() and fingerprint(source) == expected, 'backup_changed_pdf', 409)
                    files[name] = expected
                    require(sum(f['size'] for f in files.values()) <= MAX_TOTAL, 'backup_too_large')
                    bundle.write(source, name)
                bundle.writestr('manifest.json', json.dumps(dict(format=FORMAT,storage=storage,files=files)))
        with tempfile.TemporaryDirectory(prefix='.verify-', dir=stage) as checked:
            validate_expanded_archive(archive, Path(checked))
        # A withdrawn/rebound candidate cannot publish the completed archive.
        with service.connection():
            pass
        archive.chmod(0o600)
        with archive.open('r+b') as completed:
            os.fsync(completed.fileno())
        os.link(archive, destination)
    return destination


def restore_expanded_archive(archive, destination):
    archive, destination = plain_path(archive), plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(), 'restore_requires_new_directory', 409)
    original = fingerprint(archive)
    with tempfile.TemporaryDirectory(prefix='.expanded-restore-', dir=destination.parent) as tmp:
        stage = Path(tmp)
        proof = validate_expanded_archive(archive, stage)
        previous = proof.pop('storage')
        marker = dict(format=candidate.FORMAT, databaseVersion=3, namingVersion=2,
                      storageId=str(uuid.uuid4()), directory=str(destination.resolve()))
        require(marker['storageId'] != previous['storageId'], 'candidate_identity_reuse', 409)
        with closing(sqlite3.connect(stage/'team.sqlite3')) as db:
            db.executemany('UPDATE metadata SET value=? WHERE key=?', [
                (marker['storageId'],candidate.IDENTITY), (marker['directory'],candidate.ROOT)])
            db.commit()
        require(fingerprint(archive) == original, 'candidate_source_changed', 409)
        copied = candidate._publish_candidate(stage, destination, marker,
            lambda:require(fingerprint(archive) == original, 'candidate_source_changed', 409))
    return dict(**proof,databaseVersion=3,namingVersion=2,expandedPublicationReady=False,copiedFiles=copied,
                sourceSha256=original['sha256'],previousStorageId=previous['storageId'],storageId=marker['storageId'])
