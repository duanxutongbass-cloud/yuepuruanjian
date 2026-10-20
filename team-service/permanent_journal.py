"""Crash recovery for explicitly confirmed deletion on isolated v4 storage.

0: durable plan; rollback any file moves. 1: DB committed; erase only.
2: files/journal removed. Nothing here activates a public service endpoint.
"""
import hashlib
import json
import os
import re

from backup_archive import fingerprint, plain_path, unique_object
from permanent_deletion import piece_reference
from publishing import canonical
from programs import piece_key
from service import UUID, fields, require


def sync_directory(path):
    if os.name!='nt':
        handle=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(handle)
        finally:os.close(handle)


def checked(path, item, links=1):
    path=plain_path(path)
    require(path.is_file() and path.stat().st_nlink==links and
        fingerprint(path)==dict(size=item['size'],sha256=item['sha256']),'permanent_file_changed',409)
    return path


def plan_value(operation, row, storage=None):
    plan=json.loads(row['plan'],object_pairs_hook=unique_object)
    fields(plan,{'storageId','owner','requestId','revision','files','programIds','freezeIds','pieceReferences'})
    from score_numbers import current_reader
    storage=storage or operation.service._marker()[0]
    if operation is not None:
        reader=operation.catalog.read_number
    elif storage.get('namingVersion') == 2:
        from expanded_permanent import FORMAT
        from expanded_protocol import expanded_reader
        require(storage.get('format') == FORMAT and storage.get('databaseVersion') == 4,
                'permanent_plan_invalid',503)
        reader=expanded_reader
    else:
        reader=current_reader
    require(plan['storageId']==storage['storageId'] and
        plan['owner']==row['owner'] and plan['requestId']==row['request'] and
        all(isinstance(plan[k],str) and UUID.fullmatch(plan[k]) for k in ('storageId','owner','requestId')) and
        type(plan['revision']) is int and plan['revision']>0,'permanent_plan_invalid',503)
    files=plan['files']
    require(isinstance(files,list) and 1<=len(files)<=200,'permanent_plan_invalid',503)
    for item in files:
        fields(item,{'artifactId','sha256','size','number'})
        require(isinstance(item['artifactId'],str) and UUID.fullmatch(item['artifactId']) and
            isinstance(item['sha256'],str) and re.fullmatch('[a-f0-9]{64}',item['sha256']) and
            type(item['size']) is int and item['size']>0,'permanent_plan_invalid',503)
        reader(item['number'])
    require(len({f['artifactId'] for f in files})==len(files),'permanent_plan_invalid',503)
    for key in ('programIds','freezeIds','pieceReferences'):
        require(isinstance(plan[key],list) and len(set(plan[key]))==len(plan[key]) and
            all(isinstance(v,str) and UUID.fullmatch(v) for v in plan[key]),'permanent_plan_invalid',503)
    require(type(row['completed']) is int and row['completed'] in (0,1,2),'permanent_plan_invalid',503)
    return plan


def journal(operation, plan, create=False):
    root=plain_path(operation.service.directory/'permanent-journal')
    path=plain_path(root/plan['requestId'])
    if create:
        root.mkdir(mode=0o700,exist_ok=True)
        if not path.exists():
            path.mkdir(mode=0o700)
            sync_directory(root)
        target=plain_path(path/'plan.json')
        if not target.exists():
            with target.open('xb') as stream:
                stream.write(canonical(plan).encode('utf-8'));stream.flush();os.fsync(stream.fileno())
            sync_directory(path)
    if path.exists():
        require(root.is_dir() and path.is_dir(),'permanent_journal_changed',503)
        allowed={'plan.json'}|{f['artifactId']+'.pdf' for f in plan['files']}
        require(all(p.name in allowed for p in path.iterdir()),'permanent_journal_changed',503)
        proof=plain_path(path/'plan.json')
        if not proof.exists():
            require(not any(path.iterdir()),'permanent_journal_changed',503)
            return path  # Crash after mkdir, or after proof unlink before rmdir.
        require(proof.is_file() and proof.stat().st_nlink==1 and proof.stat().st_size<=100000 and
            proof.read_bytes()==canonical(plan).encode('utf-8'),'permanent_journal_changed',503)
    return path


def move_file(source, destination, item):
    # Exclusive link prevents POSIX rename from silently replacing a new file.
    checked(source,item)
    require(not plain_path(destination).exists(),'permanent_file_collision',409)
    os.link(source,destination);sync_directory(destination.parent)
    checked(source,item,2);checked(destination,item,2)
    source.unlink();sync_directory(source.parent)


def clear_journal(path):
    if path.exists():
        (path/'plan.json').unlink(missing_ok=True);sync_directory(path)
        path.rmdir();sync_directory(path.parent)


def restore_files(operation, plan):
    path=journal(operation,plan)
    if not path.exists():return  # A durable, never-started plan may now be stale.
    for item in plan['files']:
        source=plain_path(operation.catalog.root/(item['artifactId']+'.pdf'))
        staged=plain_path(path/source.name)
        if staged.exists():
            if source.exists():
                require(os.path.samefile(source,staged),'permanent_file_collision',409)
                checked(source,item,2);checked(staged,item,2)
                staged.unlink();sync_directory(path)
            else:
                move_file(staged,source,item)
        checked(source,item)
    clear_journal(path)


def erase_files(operation, plan):
    path=journal(operation,plan)
    for item in plan['files']:
        require(not plain_path(operation.catalog.root/(item['artifactId']+'.pdf')).exists(),
                'permanent_file_collision',409)
        staged=plain_path(path/(item['artifactId']+'.pdf'))
        if staged.exists():
            checked(staged,item).unlink();sync_directory(path)
    clear_journal(path)


def finish(operation, owner, request):
    with operation.service.connection() as db:
        row=db.execute('SELECT * FROM permanent_deletion_jobs WHERE owner=? AND request=?',(owner,request)).fetchone()
        require(row is not None,'permanent_plan_missing',503)
        plan=plan_value(operation,row)
        require(row['completed'] in (1,2),'permanent_not_committed',409)
        erase_files(operation,plan)
        db.execute('UPDATE permanent_deletion_jobs SET completed=2 WHERE owner=? AND request=?',(owner,request))
    return dict(ok=True,requestId=request,revision=plan['revision'],
                artifactIds=[f['artifactId'] for f in plan['files']])


def recover(operation):
    with operation.service.connection() as db:
        rows=list(db.execute('SELECT * FROM permanent_deletion_jobs ORDER BY request,owner'))
        require(len({r['request'] for r in rows})==len(rows),'permanent_plan_invalid',503)
        root=plain_path(operation.service.directory/'permanent-journal')
        require(not root.exists() or root.is_dir() and all(p.name in {r['request'] for r in rows}
            for p in root.iterdir()),'permanent_journal_changed',503)
        for row in rows:
            plan=plan_value(operation,row)
            if row['completed']==0:restore_files(operation,plan)
            else:
                erase_files(operation,plan)
                db.execute('UPDATE permanent_deletion_jobs SET completed=2 WHERE owner=? AND request=?',
                           (row['owner'],row['request']))
    return dict(recovered=len(rows))


def erase_rows(operation, db, plan, preview):
    db.execute('PRAGMA secure_delete=ON')
    ids={f['artifactId'] for f in plan['files']}
    selected={operation.catalog.piece_key(db,dict(id=f['artifactId'],number=f['number'])) for f in plan['files']}
    survivors={operation.catalog.piece_key(db,row) for row in db.execute('''SELECT a.id,m.number FROM artifacts a
        LEFT JOIN artifact_metadata m ON m.artifact=a.id''') if row['id'] not in ids}
    disappearing=selected-survivors
    # Legacy payload receipts have no artifact identity. Retire their responses,
    # never unrelated annotation heads/history. Exact-target new receipts survive.
    for row in list(db.execute('''SELECT o.account,o.request FROM annotation_operations o
        LEFT JOIN annotation_operation_targets t ON t.account=o.account AND t.request=o.request
        WHERE t.artifact IS NULL''')):
        db.execute('UPDATE annotation_operations SET status=410,result=? WHERE account=? AND request=?',
            (canonical(dict(error='annotation_receipt_expired',requestId=row['request'])),row['account'],row['request']))
    for item in plan['files']:
        aid=item['artifactId']
        for row in list(db.execute('SELECT account,request FROM annotation_operation_targets WHERE artifact=?',(aid,))):
            db.execute('UPDATE annotation_operations SET status=410,result=? WHERE account=? AND request=?',
                (canonical(dict(error='annotation_receipt_expired',requestId=row['request'])),row['account'],row['request']))
        db.execute('DELETE FROM annotation_heads WHERE artifact=?',(aid,))
        db.execute('DELETE FROM annotation_history WHERE artifact=?',(aid,))
        for table,column in (('artifact_grants','artifact'),('artifact_metadata','artifact'),('removed_artifacts','id')):
            db.execute('DELETE FROM '+table+' WHERE '+column+'=?',(aid,))
        db.execute('DELETE FROM artifacts WHERE id=?',(aid,))
        db.execute('INSERT INTO artifact_tombstones(artifact,number,deleted,request) VALUES(?,?,?,?)',
            (aid,item['number'],int(operation.service.clock()),plan['requestId']))
    for row in list(db.execute('SELECT * FROM uploads')):
        spec=json.loads(row['spec'],object_pairs_hook=unique_object)
        number=operation.catalog.read_number(spec['scoreNumber'])
        if row['id'] in ids or number and operation.catalog.piece_key(db,dict(id=row['id'],number=spec['scoreNumber'])) in disappearing:
            db.execute('INSERT OR IGNORE INTO cancelled_uploads VALUES(?,?)',(row['owner'],row['request']))
            db.execute('DELETE FROM uploads WHERE id=?',(row['id'],))
    for program in preview['programs']:
        pid=program['id']
        db.execute('DELETE FROM program_freezes WHERE program=?',(pid,))
        for row in list(db.execute('SELECT owner,request,spec FROM program_freeze_requests')):
            if json.loads(row['spec'],object_pairs_hook=unique_object)['programId']==pid:
                db.execute('DELETE FROM program_freeze_requests WHERE owner=? AND request=?',(row['owner'],row['request']))
        current=db.execute('SELECT pieces,archived FROM programs WHERE id=?',(pid,)).fetchone()
        pieces=[p for p in json.loads(current['pieces']) if p not in disappearing]
        db.execute('UPDATE programs SET pieces=?,archived=?,revision=revision+1 WHERE id=?',
            (canonical(pieces),int(bool(current['archived']) or not pieces),pid))
    references={piece_reference(key) for key in disappearing}
    for account in preview['folderAccounts']:
        row=db.execute('SELECT payload,revision FROM personal_folder_heads WHERE account=?',(account,)).fetchone()
        from personal_folders import MAX_REVISION
        require(row['revision']<MAX_REVISION,'folder_revision_exhausted',409)
        folders=json.loads(row['payload'],object_pairs_hook=unique_object)
        for folder in folders:
            folder['pieces']=[p for p in folder['pieces'] if p not in references]
            if folder['coverPiece'] in references:folder['coverPiece']=None
        payload=canonical(folders)
        db.execute('UPDATE personal_folder_heads SET payload=?,sha256=?,revision=revision+1 WHERE account=?',
            (payload,hashlib.sha256(payload.encode()).hexdigest(),account))
    operation.catalog.changed(db)
    operation.service.audit(db,plan['owner'],'scores_permanently_deleted',plan['requestId'])


def commit(operation, body, token, device):
    fields(body,{'artifactIds','expectedRevision','scopeDigest','requestId','confirmed'})
    require(body['confirmed'] is True,'permanent_confirmation_required',400)
    require(isinstance(body['requestId'],str) and UUID.fullmatch(body['requestId']) and
        isinstance(body['scopeDigest'],str) and re.fullmatch('[a-f0-9]{64}',body['scopeDigest']))
    spec=canonical(body);request=body['requestId']
    scope=dict(artifactIds=body['artifactIds'],expectedRevision=body['expectedRevision'])
    with operation.service.connection() as db:
        actor=operation.service.authenticate(db,token,device)
        require(actor['role']=='admin','forbidden',403)
        owner=actor['id']
        prior=db.execute('SELECT * FROM permanent_deletion_jobs WHERE request=?',(request,)).fetchone()
        if prior:
            require(prior['owner']==owner and prior['spec']==spec,'request_conflict',409)
            plan=plan_value(operation,prior)
        else:
            preview=operation._preview(db,actor,scope)
            require(preview['scopeDigest']==body['scopeDigest'],'permanent_scope_changed',409)
            plan=dict(storageId=operation.service._marker()[0]['storageId'],owner=owner,requestId=request,
                revision=preview['revision']+1,files=[],programIds=[p['id'] for p in preview['programs']],freezeIds=preview['freezeIds'],
                pieceReferences=preview['pieceReferences'])
            for item in preview['parts']:
                row=db.execute('SELECT hash,size FROM artifacts WHERE id=?',(item['artifactId'],)).fetchone()
                plan['files'].append(dict(artifactId=item['artifactId'],sha256=row['hash'],size=row['size'],number=item['scoreNumber']))
            db.execute('INSERT INTO permanent_deletion_jobs VALUES(?,?,?,?,0)',(owner,request,spec,canonical(plan)))
    try:
        with operation.service.connection() as db:
            actor=operation.service.authenticate(db,token,device)
            require(actor['id']==owner and actor['role']=='admin','forbidden',403)
            row=db.execute('SELECT * FROM permanent_deletion_jobs WHERE owner=? AND request=?',(owner,request)).fetchone()
            plan=plan_value(operation,row)
            if row['completed']==0:
                restore_files(operation,plan)
                preview=operation._preview(db,actor,scope)
                require(preview['scopeDigest']==body['scopeDigest'],'permanent_scope_changed',409)
                path=journal(operation,plan,create=True)
                for item in plan['files']:
                    move_file(operation.catalog.root/(item['artifactId']+'.pdf'),path/(item['artifactId']+'.pdf'),item)
                erase_rows(operation,db,plan,preview)
                db.execute('UPDATE permanent_deletion_jobs SET completed=1 WHERE owner=? AND request=?',(owner,request))
    except Exception:
        # A failed DB transaction is always state 0; restore under the writer lock.
        # A real process crash leaves the same durable plan for startup recovery.
        with operation.service.connection() as db:
            row=db.execute('SELECT * FROM permanent_deletion_jobs WHERE owner=? AND request=?',(owner,request)).fetchone()
            if row is not None and row['completed']==0:restore_files(operation,plan_value(operation,row))
        raise
    return finish(operation,owner,request)
