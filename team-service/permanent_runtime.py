"""Explicit activation: restored copies cannot serve before current retirements are merged."""
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import uuid

from backup_archive import plain_path, fingerprint, unique_object
from permanent_storage import PermanentStorage, MARKER
from permanent_deletion import PermanentDeletion, piece_reference
from permanent_journal import plan_value, journal, move_file, erase_rows, finish, sync_directory
from expanded_candidate import IDENTITY, ROOT, NAMING
from publishing import canonical
from programs import piece_key
from service import require

ACTIVATION='permanent-active.json'


def open_active(directory, *, _storage_type=PermanentStorage):
    service=_storage_type(directory)
    marker,_=service._marker()
    path=plain_path(service.directory/ACTIVATION)
    require(path.is_file() and path.stat().st_nlink==1 and path.stat().st_size<=4096,'permanent_activation_required',503)
    proof=json.loads(path.read_text('utf-8'),object_pairs_hook=unique_object)
    with service.connection() as db:team=db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()[0]
    require(proof==dict(format='score-team-permanent-active-v1',storageId=marker['storageId'],teamId=team),
            'permanent_activation_invalid',503)
    return service


def _rows(db):
    ignored={'sessions','invitations','artifact_tombstones','permanent_deletion_jobs','annotation_operation_targets'}
    result={}
    for table, in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
        require(table.replace('_','').isalnum(),'activation_invalid_table')
        if table not in ignored:
            rows=[tuple(r) for r in db.execute('SELECT * FROM "'+table+'" ORDER BY rowid')]
            if table=='metadata':rows=[r for r in rows if r[0] not in {IDENTITY,ROOT,NAMING,'permanentDeletionVersion'}]
            result[table]=rows
    return result


def activate(directory, authority, confirmed=False, *, _storage_type=PermanentStorage, _application_type=None):
    require(confirmed is True,'permanent_activation_confirmation_required',400)
    directory,authority=plain_path(directory),plain_path(authority)
    require(directory!=authority and (authority/'team.sqlite3').is_file(),'permanent_authority_required',409)
    service=_storage_type(directory)
    marker,_=service._marker()
    require(not (directory/ACTIVATION).exists(),'permanent_already_active',409)
    from server import Application
    app=(_application_type or Application)(service)
    op=app.permanent
    # A source read transaction captures a consistent authority. Deployment must
    # stop the prior writer first; the same snapshot is checked again before activation.
    with closing(sqlite3.connect((authority/'team.sqlite3').as_uri()+'?mode=ro',uri=True)) as source:
        source.row_factory=sqlite3.Row;source.execute('BEGIN')
        version=source.execute('PRAGMA user_version').fetchone()[0]
        require(version in (2,4),'permanent_authority_unsupported',409)
        authority_rows=_rows(source)
        source_team=source.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()[0]
        with service.connection() as db:
            require(db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()[0]==source_team,
                    'permanent_authority_team_mismatch',409)
            if version==2:
                require(_rows(db)==_rows(source),'permanent_migration_source_changed',409)
        if version==4:
            from expanded_permanent import ExpandedPermanentStorage
            expanded=(authority/ExpandedPermanentStorage.marker_file).exists()
            require(not expanded or _storage_type is ExpandedPermanentStorage,'permanent_authority_unsupported',409)
            current=open_active(authority,_storage_type=ExpandedPermanentStorage if expanded else PermanentStorage)
            current_marker,_=current._marker()
            from number_release import ledger
            current_releases=ledger(source,app.catalog.read_number)
            current_reused={}
            with service.connection() as db:
                restored_releases=ledger(db,app.catalog.read_number)
                require(restored_releases==current_releases,'number_release_restore_requires_current_backup',409)
                if current_releases:
                    def reused_rows(connection):
                        return {r['id']:tuple(r) for r in connection.execute('''SELECT a.id,a.title,a.part,a.hash,a.size,a.pages,a.published,m.number,m.category
                            FROM artifacts a JOIN artifact_metadata m ON m.artifact=a.id''')
                            if (parsed:=app.catalog.read_number(r['number'])) is not None and parsed.sequence in current_releases}
                    current_reused=reused_rows(source)
                    require(reused_rows(db)==current_reused,'number_release_restore_requires_current_backup',409)
            plans={}
            for row in source.execute('SELECT * FROM permanent_deletion_jobs'):
                require(row['completed']==2,'permanent_authority_cleanup_pending',409)
                plans[row['request']]=plan_value(None,row,storage=current_marker)
            retired=list(source.execute('SELECT * FROM artifact_tombstones ORDER BY sequence'))
            files={};pieces=set();programs=set();freezes=set()
            for row in retired:
                require(row['request'] in plans,'permanent_authority_ledger_invalid',503)
                plan=plans[row['request']]
                matches=[f for f in plan['files'] if f['artifactId']==row['artifact'] and f['number']==row['number']]
                require(len(matches)==1,'permanent_authority_ledger_invalid',503)
                files[row['artifact']]=matches[0]
                pieces.update(plan['pieceReferences']);programs.update(plan['programIds']);freezes.update(plan['freezeIds'])
            with service.connection() as db:
                done={r[0] for r in db.execute('SELECT artifact FROM artifact_tombstones')}
                numbers={r['number'] for r in retired if r['number']}
                existing={r['id']:dict(r) for r in db.execute('SELECT a.*,m.number FROM artifacts a LEFT JOIN artifact_metadata m ON m.artifact=a.id')}
                for aid,row in existing.items():
                    if aid in files:
                        require(files[aid]['sha256']==row['hash'] and files[aid]['size']==row['size'] and
                                files[aid]['number']==(row['number'] or ''),'permanent_restored_artifact_changed',409)
                    elif aid not in current_reused and (row['number'] in numbers or piece_reference(app.catalog.piece_key(db,row)) in pieces):
                        files[aid]=dict(artifactId=aid,sha256=row['hash'],size=row['size'],number=row['number'] or '')
                actor=db.execute("SELECT id FROM accounts WHERE role='admin' AND active=1 ORDER BY id LIMIT 1").fetchone()
                require(actor is not None,'permanent_restore_admin_required',409)
                owner=actor[0]
            pending=[f for aid,f in files.items() if aid not in done]
            # Existing PDFs need the ordinary durable crash journal. Already absent
            # artifacts can enter the ledger in one transaction without file movement.
            for present in (True,False):
                selected=[f for f in pending if (f['artifactId'] in existing)==present]
                for start in range(0,len(selected),200):
                    group=selected[start:start+200];request=str(uuid.uuid4())
                    with service.connection() as db:
                        revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
                        for f in group:
                            if present:
                                db.execute('UPDATE artifacts SET published=0 WHERE id=?',(f['artifactId'],))
                                db.execute('INSERT OR IGNORE INTO removed_artifacts VALUES(?)',(f['artifactId'],))
                        preview=op._preview(db,{'id':owner},dict(artifactIds=[f['artifactId'] for f in group],expectedRevision=revision)) if present else dict(programs=[],folderAccounts=[])
                        actual={p['id'] for p in preview['programs']}
                        preview['programs'] += [dict(id=p[0]) for p in db.execute('SELECT id FROM programs') if p[0] in programs-actual]
                        plan=dict(storageId=marker['storageId'],owner=owner,requestId=request,revision=revision+1,files=group,
                                  programIds=sorted(programs|{p['id'] for p in preview['programs']}),freezeIds=sorted(freezes),pieceReferences=sorted(pieces))
                        db.execute('INSERT INTO permanent_deletion_jobs VALUES(?,?,?,?,0)',(owner,request,canonical(dict(restoreAuthority=current_marker['storageId'])),canonical(plan)))
                        if not present:
                            erase_rows(op,db,plan,preview)
                            db.execute('UPDATE permanent_deletion_jobs SET completed=2 WHERE request=?',(request,))
                    if present:
                        try:
                            with service.connection() as db:
                                path=journal(op,plan,True)
                                for f in group:move_file(app.catalog.root/(f['artifactId']+'.pdf'),path/(f['artifactId']+'.pdf'),f)
                                erase_rows(op,db,plan,preview)
                                db.execute('UPDATE permanent_deletion_jobs SET completed=1 WHERE request=?',(request,))
                        except BaseException:
                            op.recover();raise
                        finish(op,owner,request)
            # Even an old head with no remaining local artifact must not retain a
            # globally retired reference; preserve standalone covers and other pieces.
            with service.connection() as db:
                for row in db.execute('SELECT * FROM personal_folder_heads').fetchall():
                    value=json.loads(row['payload'],object_pairs_hook=unique_object)
                    for f in value:
                        f['pieces']=[p for p in f['pieces'] if p not in pieces]
                        if f['coverPiece'] in pieces:f['coverPiece']=None
                    payload=canonical(value)
                    if payload!=row['payload']:
                        import hashlib
                        from personal_folders import MAX_REVISION
                        require(row['revision']<MAX_REVISION,'folder_revision_exhausted',409)
                        db.execute('UPDATE personal_folder_heads SET payload=?,sha256=?,revision=revision+1 WHERE account=?',
                                   (payload,hashlib.sha256(payload.encode()).hexdigest(),row['account']))
                require(not files.keys()-{r[0] for r in db.execute('SELECT artifact FROM artifact_tombstones')},'permanent_restore_ledger_incomplete',503)
        source.commit()
        require(_rows(source)==authority_rows,'permanent_authority_changed',409)
        if version==4:
            require([tuple(r) for r in source.execute('SELECT * FROM artifact_tombstones ORDER BY sequence')]==[tuple(r) for r in retired],
                    'permanent_authority_changed',409)
    with service.connection() as db:
        from permanent_backup import _database
        _database(db,marker,_storage_type)
    # Full account/PDF archives deliberately exclude the public update channel.
    # Preserve its current pointer and immutable APK when changing the data root.
    from app_release import AppReleases
    releases=AppReleases(authority);release=releases.current()
    if release is not None:
        apk=plain_path(releases.root/(release['sha256']+'.apk'))
        require(fingerprint(apk)==dict(size=release['size'],sha256=release['sha256']),'activation_release_changed',409)
        copied=AppReleases(directory).publish(apk,release['versionCode'],release['versionName'],release['notes'])
        require(copied==release and releases.current()==release,'activation_release_changed',409)
    proof=dict(format='score-team-permanent-active-v1',storageId=marker['storageId'],teamId=source_team)
    temporary=plain_path(directory/('.activation-'+str(uuid.uuid4())+'.pending'))
    try:
        with temporary.open('xb') as out:out.write(canonical(proof).encode());out.flush();os.fsync(out.fileno())
        os.link(temporary,directory/ACTIVATION);sync_directory(directory)
    finally:temporary.unlink(missing_ok=True)
    open_active(directory,_storage_type=_storage_type)
    return dict(ok=True,storageId=marker['storageId'],permanentDeletionReady=True)
