"""Isolated v4 deletion-storage prerequisite. No public activation or purge API.

Only a complete verified v1 backup can seed a new v4 directory. The ordinary
v1/v2 service and the unrelated v3 expanded candidate reject this format.
"""
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import uuid

from backup_archive import fingerprint, plain_path, restore_archive
from expanded_candidate import ExpandedCandidateService, IDENTITY, ROOT, NAMING, _inventory, _publish_candidate
from service import require

FORMAT='score-team-permanent-storage-v1'
MARKER='permanent-storage.json'


def supports_permanent(service):
    if not isinstance(service, PermanentStorage) or service.database_version != 4:
        return False
    if service.naming_version == 1:
        return service.marker_format == FORMAT and service.marker_file == MARKER
    from expanded_permanent import ExpandedPermanentStorage
    return type(service) is ExpandedPermanentStorage


def prepare_permanent_storage(archive, destination):
    archive,destination=plain_path(archive),plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(),'candidate_requires_new_directory',409)
    original=fingerprint(archive)
    identity,root=str(uuid.uuid4()),str(destination.resolve())
    with tempfile.TemporaryDirectory(prefix='.permanent-storage-',dir=destination.parent) as tmp:
        restored=Path(tmp)/'restored'
        proof=restore_archive(archive,restored)
        with closing(sqlite3.connect(restored/'team.sqlite3')) as db:
            require(db.execute('PRAGMA user_version').fetchone()[0]==2,'candidate_requires_database_v2',409)
            _inventory(db)
            require(not db.execute('SELECT 1 FROM metadata WHERE key IN (?,?,?,?)',
                (IDENTITY,ROOT,NAMING,'permanentDeletionVersion')).fetchone(),'candidate_metadata_conflict',409)
            db.executescript('''
                CREATE TABLE artifact_tombstones(
                    sequence INTEGER PRIMARY KEY AUTOINCREMENT, artifact TEXT NOT NULL UNIQUE,
                    number TEXT NOT NULL, deleted INTEGER NOT NULL, request TEXT NOT NULL);
                CREATE TABLE permanent_deletion_jobs(
                    owner TEXT NOT NULL REFERENCES accounts(id), request TEXT NOT NULL,
                    spec TEXT NOT NULL, plan TEXT NOT NULL, completed INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(owner,request));
                CREATE TABLE annotation_operation_targets(
                    account TEXT NOT NULL, request TEXT NOT NULL, artifact TEXT NOT NULL,
                    PRIMARY KEY(account,request),
                    FOREIGN KEY(account,request) REFERENCES annotation_operations(account,request));
            ''')
            db.executemany('INSERT INTO metadata VALUES(?,?)',[
                (IDENTITY,identity),(ROOT,root),(NAMING,'1'),('permanentDeletionVersion','1')])
            db.execute('PRAGMA user_version=4');db.commit()
            require(db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and
                not db.execute('PRAGMA foreign_key_check').fetchall(),'candidate_invalid_database',409)
        marker=dict(format=FORMAT,databaseVersion=4,namingVersion=1,storageId=identity,directory=root)
        check=lambda:require(fingerprint(archive)==original,'candidate_source_changed',409)
        check()
        copied=_publish_candidate(restored,destination,marker,check,marker_name=MARKER)
    return dict(**proof,databaseVersion=4,namingVersion=1,permanentDeletionReady=False,
                sourceSha256=original['sha256'],copiedFiles=copied)


class PermanentStorage(ExpandedCandidateService):
    database_version=4
    naming_version=1
    marker_format=FORMAT
    marker_file=MARKER

    def _database(self, db, marker):
        super()._database(db,marker)
        setting=db.execute("SELECT value FROM metadata WHERE key='permanentDeletionVersion'").fetchone()
        require(setting is not None and setting[0]=='1','permanent_storage_unavailable',503)
        for table in ('artifact_tombstones','permanent_deletion_jobs','annotation_operation_targets'):
            require(db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",(table,)).fetchone(),
                    'permanent_storage_unavailable',503)
