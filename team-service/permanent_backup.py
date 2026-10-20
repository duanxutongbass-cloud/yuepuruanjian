"""Explicit v4 full archives; legacy and expanded v3 readers stay closed."""
from contextlib import closing
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import sqlite3
import tempfile
import uuid
import zipfile

from backup_archive import _extract_archive, fingerprint, plain_path, MAX_ENTRIES, MAX_TOTAL
from expanded_candidate import IDENTITY,ROOT,NAMING,_publish_candidate
from permanent_storage import PermanentStorage,FORMAT as STORAGE_FORMAT,MARKER
from score_numbers import current_reader
from service import UUID,require

FORMAT='score-team-backup-v3'


def _storage(value, storage_type=PermanentStorage):
    require(isinstance(value,dict) and set(value)=={'format','databaseVersion','namingVersion','storageId','directory'} and
        value['format']==storage_type.marker_format and type(value['databaseVersion']) is int and value['databaseVersion']==4 and
        type(value['namingVersion']) is int and value['namingVersion']==storage_type.naming_version and
        isinstance(value['storageId'],str) and UUID.fullmatch(value['storageId']) and
        isinstance(value['directory'],str) and 0<len(value['directory'])<=4096 and '\x00' not in value['directory'] and
        (PurePosixPath(value['directory']).is_absolute() or PureWindowsPath(value['directory']).is_absolute()),
        'backup_invalid_permanent_storage')


def _database(db, storage, storage_type=PermanentStorage):
    _storage(storage, storage_type)
    from expanded_candidate import _inventory
    if storage_type.naming_version == 2:
        from expanded_protocol import expanded_reader
        from score_numbers import parse_number
        _inventory(db,expanded_reader,parse_number)
    else:_inventory(db)
    require(db.execute('PRAGMA user_version').fetchone()[0]==4 and
        db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall(),
        'backup_invalid_database')
    expected={IDENTITY:storage['storageId'],ROOT:storage['directory'],NAMING:str(storage_type.naming_version),'permanentDeletionVersion':'1'}
    require(dict(db.execute('SELECT key,value FROM metadata WHERE key IN (?,?,?,?)',tuple(expected)))==expected,
            'backup_candidate_identity_mismatch')
    require(not db.execute('''SELECT 1 FROM accounts a LEFT JOIN account_memberships m ON m.account=a.id
        WHERE m.account IS NULL LIMIT 1''').fetchone() and
        db.execute('SELECT count(*) FROM join_code WHERE singleton=1').fetchone()[0]==1,'backup_invalid_database')
    require(not db.execute('SELECT 1 FROM permanent_deletion_jobs WHERE completed=1 LIMIT 1').fetchone(),
            'permanent_cleanup_pending',409)
    from permanent_journal import plan_value
    for values in db.execute('SELECT owner,request,spec,plan,completed FROM permanent_deletion_jobs'):
        plan_value(None,dict(zip(('owner','request','spec','plan','completed'),values)),storage=storage)
    tombstones=list(db.execute('SELECT sequence,artifact,number,deleted,request FROM artifact_tombstones ORDER BY sequence'))
    for sequence,artifact,number,deleted,request in tombstones:
        require(type(sequence) is int and sequence>0 and isinstance(artifact,str) and UUID.fullmatch(artifact) and
            type(deleted) is int and deleted>=0 and isinstance(request,str) and UUID.fullmatch(request),'backup_invalid_tombstone')
        if storage_type.naming_version == 2:
            from expanded_protocol import expanded_reader
            expanded_reader(number)
        else:current_reader(number)
    require(not db.execute('SELECT 1 FROM artifact_tombstones t JOIN artifacts a ON a.id=t.artifact LIMIT 1').fetchone(),
            'backup_retired_artifact_present')
    require(not db.execute('SELECT 1 FROM artifact_tombstones t JOIN annotation_history h ON h.artifact=t.artifact LIMIT 1').fetchone(),
            'backup_retired_ink_present')
    from number_release import ledger
    from score_numbers import parse_number
    ledger(db,parse_number if storage_type.naming_version==2 else current_reader)
    if tombstones:
        require(not db.execute('''SELECT 1 FROM annotation_operations o LEFT JOIN annotation_operation_targets m
            ON m.account=o.account AND m.request=o.request WHERE m.artifact IS NULL AND o.status!=410 LIMIT 1''').fetchone(),
            'backup_legacy_ink_receipts_present')
        require(not db.execute('''SELECT 1 FROM annotation_operations o JOIN annotation_operation_targets m
            ON m.account=o.account AND m.request=o.request JOIN artifact_tombstones t ON t.artifact=m.artifact
            WHERE o.status!=410 LIMIT 1''').fetchone(),'backup_retired_ink_receipts_present')
        for request,result in db.execute('''SELECT o.request,o.result FROM annotation_operations o
            LEFT JOIN annotation_operation_targets m ON m.account=o.account AND m.request=o.request
            LEFT JOIN artifact_tombstones t ON t.artifact=m.artifact WHERE m.artifact IS NULL OR t.artifact IS NOT NULL'''):
            payload=json.loads(result)
            require(isinstance(payload,dict) and set(payload)=={'error','requestId'} and
                payload['error']=='annotation_receipt_expired' and payload['requestId']==request,'backup_retired_receipt_payload')
        retired={t[1] for t in tombstones}
        for row in db.execute('SELECT manifest,pages FROM program_freezes'):
            manifest=json.loads(row[0])
            pages=json.loads(row[1])
            require(not any(a['artifactId'] in retired for a in manifest['artifacts']) and
                not any(a['artifactId'] in retired for a in pages),'backup_retired_freeze_present')


def validate_permanent_archive(archive, stage, *, _format=FORMAT, _storage_type=PermanentStorage):
    manifest=_extract_archive(archive,stage,_format,('storage',));storage,files=manifest['storage'],manifest['files']
    _storage(storage,_storage_type)
    with closing(sqlite3.connect(stage/'team.sqlite3')) as db:
        _database(db,storage,_storage_type)
        rows=db.execute('SELECT id,hash,size FROM artifacts').fetchall()
        wanted={'artifacts/'+r[0]+'.pdf':dict(size=r[2],sha256=r[1]) for r in rows}
        require({k:v for k,v in files.items() if k!='team.sqlite3'}==wanted,'backup_catalog_mismatch')
        db.execute('DELETE FROM sessions');db.execute('DELETE FROM invitations');db.commit()
        db.execute('PRAGMA wal_checkpoint(TRUNCATE)');db.execute('PRAGMA journal_mode=DELETE')
    return dict(artifacts=len(rows),bytes=sum(v['size'] for v in files.values()),storage=storage)


def create_permanent_archive(service, destination, *, _format=FORMAT, _storage_type=PermanentStorage):
    require(isinstance(service,_storage_type) and service.database_version==4 and service.naming_version==_storage_type.naming_version,
            'permanent_service_required',409)
    destination=plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(),'backup_target_exists',409)
    with tempfile.TemporaryDirectory(prefix='.permanent-backup-',dir=destination.parent) as tmp:
        stage=Path(tmp);archive=stage/'backup.zip';database=stage/'team.sqlite3'
        with service.connection() as db:
            storage,_=service._marker();_database(db,storage,_storage_type)
            journal=plain_path(service.directory/'permanent-journal')
            require(not journal.exists() or journal.is_dir() and not any(journal.iterdir()),'permanent_cleanup_pending',409)
            rows=list(db.execute('SELECT id,hash,size FROM artifacts ORDER BY id'))
            require(len(rows)+2<=MAX_ENTRIES,'backup_too_large')
            with closing(sqlite3.connect(service.path.as_uri()+'?mode=ro',uri=True)) as source, \
                    closing(sqlite3.connect(database)) as dest:
                source.backup(dest)
                dest.execute('DELETE FROM sessions');dest.execute('DELETE FROM invitations');dest.commit()
                dest.execute('PRAGMA wal_checkpoint(TRUNCATE)');dest.execute('PRAGMA journal_mode=DELETE')
            database.chmod(0o600);files={'team.sqlite3':fingerprint(database)}
            with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,allowZip64=True) as bundle:
                bundle.write(database,'team.sqlite3')
                for row in rows:
                    require(UUID.fullmatch(row['id']),'backup_invalid_catalog')
                    name='artifacts/'+row['id']+'.pdf';source=plain_path(service.directory/name)
                    expected=dict(size=row['size'],sha256=row['hash'])
                    require(source.is_file() and source.stat().st_nlink==1 and fingerprint(source)==expected,'backup_changed_pdf',409)
                    files[name]=expected
                    require(sum(f['size'] for f in files.values())<=MAX_TOTAL,'backup_too_large')
                    bundle.write(source,name)
                bundle.writestr('manifest.json',json.dumps(dict(format=_format,storage=storage,files=files)))
        with tempfile.TemporaryDirectory(prefix='.verify-',dir=stage) as checked:
            validate_permanent_archive(archive,Path(checked),_format=_format,_storage_type=_storage_type)
        with service.connection():pass
        archive.chmod(0o600)
        with archive.open('r+b') as completed:os.fsync(completed.fileno())
        os.link(archive,destination)
    return destination


def restore_permanent_archive(archive,destination, *, _format=FORMAT, _storage_type=PermanentStorage):
    archive,destination=plain_path(archive),plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(),'restore_requires_new_directory',409)
    original=fingerprint(archive)
    with tempfile.TemporaryDirectory(prefix='.permanent-restore-',dir=destination.parent) as tmp:
        stage=Path(tmp);proof=validate_permanent_archive(archive,stage,_format=_format,_storage_type=_storage_type);previous=proof.pop('storage')
        marker=dict(format=_storage_type.marker_format,databaseVersion=4,namingVersion=_storage_type.naming_version,storageId=str(uuid.uuid4()),directory=str(destination.resolve()))
        require(marker['storageId']!=previous['storageId'],'candidate_identity_reuse',409)
        with closing(sqlite3.connect(stage/'team.sqlite3')) as db:
            db.executemany('UPDATE metadata SET value=? WHERE key=?',[(marker['storageId'],IDENTITY),(marker['directory'],ROOT)])
            # Archive validation proved every job belongs to the old storage.
            # Only its storage binding changes; business IDs/receipts do not.
            for owner,request,encoded in db.execute('SELECT owner,request,plan FROM permanent_deletion_jobs').fetchall():
                plan=json.loads(encoded);plan['storageId']=marker['storageId']
                db.execute('UPDATE permanent_deletion_jobs SET plan=? WHERE owner=? AND request=?',
                    (json.dumps(plan,ensure_ascii=False,sort_keys=True,separators=(',',':')),owner,request))
            db.commit()
        check=lambda:require(fingerprint(archive)==original,'candidate_source_changed',409)
        check();copied=_publish_candidate(stage,destination,marker,check,marker_name=_storage_type.marker_file)
    return dict(**proof,databaseVersion=4,namingVersion=_storage_type.naming_version,permanentDeletionReady=False,copiedFiles=copied,
                sourceSha256=original['sha256'],previousStorageId=previous['storageId'],storageId=marker['storageId'])
