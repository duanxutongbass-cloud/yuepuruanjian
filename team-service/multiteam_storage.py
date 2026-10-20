"""Explicit multi-team roots; each score library retains the existing v4 safeguards."""
from contextlib import closing
from pathlib import Path
import hashlib,json,os,secrets,shutil,sqlite3,tempfile,time,uuid,zipfile

from backup_archive import plain_path,fingerprint,unique_object,_extract_archive,MAX_TOTAL,MAX_ENTRIES
from service import TeamService,UUID,require
import expanded_permanent as expanded

FORMAT='score-team-multi-root-v1'
ARCHIVE='score-team-multi-backup-v1'
MARKER='multi-team.json'


def save_new(path,value):
    with path.open('x',encoding='utf-8') as out:
        json.dump(value,out,sort_keys=True,separators=(',',':'));out.flush();os.fsync(out.fileno())


def publish_marker(root,marker):
    pending=root/('.multi-team-'+str(uuid.uuid4())+'.pending')
    try:
        save_new(pending,marker);os.link(pending,root/MARKER)
        from permanent_journal import sync_directory
        sync_directory(root)
    finally:
        if pending.exists():pending.unlink()


def registry(db):
    db.executescript('''
      CREATE TABLE IF NOT EXISTS mt_teams(id TEXT PRIMARY KEY,name TEXT NOT NULL,creator TEXT NOT NULL,created INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS mt_sessions(hash TEXT PRIMARY KEY REFERENCES sessions(hash) ON DELETE CASCADE,team TEXT NOT NULL REFERENCES mt_teams(id));
      CREATE TABLE IF NOT EXISTS mt_preferences(account TEXT NOT NULL REFERENCES accounts(id),device TEXT NOT NULL,team TEXT NOT NULL REFERENCES mt_teams(id),PRIMARY KEY(account,device));
      CREATE TABLE IF NOT EXISTS mt_codes(hash TEXT PRIMARY KEY,team TEXT NOT NULL REFERENCES mt_teams(id),kind TEXT NOT NULL CHECK(kind IN ('join','invitation')),active INTEGER NOT NULL CHECK(active IN (0,1)));
      CREATE TABLE IF NOT EXISTS mt_operations(actor TEXT NOT NULL REFERENCES accounts(id),request TEXT NOT NULL,kind TEXT NOT NULL,spec TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(actor,request));
      CREATE TABLE IF NOT EXISTS mt_intents(actor TEXT NOT NULL REFERENCES accounts(id),request TEXT NOT NULL,kind TEXT NOT NULL,spec TEXT NOT NULL,plan TEXT NOT NULL,PRIMARY KEY(actor,request));
      CREATE TABLE IF NOT EXISTS mt_jobs(actor TEXT NOT NULL REFERENCES accounts(id),request TEXT NOT NULL,spec TEXT NOT NULL,body TEXT NOT NULL,session_hash TEXT NOT NULL,device TEXT NOT NULL,state TEXT NOT NULL CHECK(state IN ('pending','complete','failed')),result TEXT,http_status INTEGER,error TEXT,PRIMARY KEY(actor,request));
    ''')


def team_id(service):
    with service.connection() as db:return db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()[0]


def checked_root(directory,active=True):
    root=plain_path(directory);path=plain_path(root/MARKER)
    require(path.is_file() and path.stat().st_size<=4096,'multi_team_root_required',503)
    marker=json.loads(path.read_text('utf-8'),object_pairs_hook=unique_object)
    require(set(marker)=={'format','identity','primary','directory','active'} and marker['format']==FORMAT and
            UUID.fullmatch(marker['identity']) and UUID.fullmatch(marker['primary']) and
            marker['directory']==str(root.resolve()) and type(marker['active']) is bool and
            (not active or marker['active']),'multi_team_root_invalid',503)
    require((root/'identity/team.sqlite3').is_file(),'multi_team_identity_missing',503)
    with closing(sqlite3.connect((root/'identity/team.sqlite3').as_uri()+'?mode=ro',uri=True)) as db:
        require(db.execute('PRAGMA user_version').fetchone()[0]==2 and db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
                and not db.execute('PRAGMA foreign_key_check').fetchall(),'multi_team_identity_invalid',503)
        meta=dict(db.execute('SELECT key,value FROM metadata'))
        require(meta.get('multiTeamIdentity')==marker['identity'] and meta.get('multiTeamRoot')==marker['directory'],
                'multi_team_identity_binding',503)
        rows=db.execute('SELECT id FROM mt_teams ORDER BY id').fetchall();require(1<=len(rows)<=1000,'multi_team_registry_invalid',503)
        for (tid,) in rows:
            require(UUID.fullmatch(tid) and team_id(expanded.open_active(root/'teams'/tid))==tid,'multi_team_registry_invalid',503)
        require(marker['primary'] in {r[0] for r in rows},'multi_team_registry_invalid',503)
    return root,marker


def _copy_channel(source,destination):
    folder=source/'app-releases'
    if not folder.exists():return
    target=destination/'app-releases';target.mkdir(exist_ok=False)
    for p in sorted(folder.rglob('*')):
        plain_path(p);q=target/p.relative_to(folder)
        if p.is_dir():q.mkdir(exist_ok=False)
        else:
            require(p.is_file() and p.stat().st_nlink==1,'multi_team_channel_invalid',409)
            shutil.copyfile(p,q);require(fingerprint(q)==fingerprint(p),'multi_team_channel_changed',409)


def prepare(source,destination):
    """Caller stops the old writer first. Source PDFs, rows and active marker remain untouched."""
    source=expanded.open_active(source);destination=plain_path(destination)
    require(destination.parent.is_dir() and not destination.exists(),'multi_team_requires_new_directory',409)
    tid=team_id(source);destination.mkdir(mode=0o700);(destination/'teams').mkdir(mode=0o700)
    backup=destination/'before-single-team.zip';expanded.create_archive(source,backup)
    primary=destination/'teams'/tid;expanded.restore_archive(backup,primary);expanded.activate(primary,source.directory,True)
    identity=TeamService(destination/'identity',source.clock)
    with closing(sqlite3.connect(source.path.as_uri()+'?mode=ro',uri=True)) as src,identity.connection() as db:
        src.execute('BEGIN');meta=dict(src.execute('SELECT key,value FROM metadata'))
        db.executemany('INSERT INTO metadata VALUES(?,?)',[(k,meta[k]) for k in ('teamId','teamName')])
        for table in ('accounts','devices','sessions','invitations','audit','throttle','account_memberships','membership_operations'):
            rows=src.execute('SELECT * FROM '+table).fetchall()
            if rows:db.executemany('INSERT INTO '+table+' VALUES('+','.join('?'*len(rows[0]))+')',rows)
        code=src.execute('SELECT * FROM join_code').fetchone();db.execute('UPDATE join_code SET hash=?,revision=? WHERE singleton=1',(code[1],code[2]))
    with closing(sqlite3.connect(identity.path)) as db:registry(db)
    iid=str(uuid.uuid4())
    with identity.connection() as db:
        db.executemany('INSERT INTO metadata VALUES(?,?)',[('multiTeamIdentity',iid),('multiTeamRoot',str(destination.resolve()))])
        creator=db.execute("SELECT id FROM accounts WHERE role='admin' ORDER BY created,id LIMIT 1").fetchone()[0]
        db.execute('INSERT INTO mt_teams VALUES(?,?,?,?)',(tid,meta['teamName'],creator,int(source.clock())))
        db.execute('INSERT INTO mt_sessions SELECT hash,? FROM sessions',(tid,))
        if code[1]:db.execute("INSERT INTO mt_codes VALUES(?,?,'join',1)",(code[1],tid))
        db.execute("INSERT INTO mt_codes SELECT hash,?,'invitation',CASE WHEN used=0 THEN 1 ELSE 0 END FROM invitations",(tid,))
    _copy_channel(source.directory,destination)
    publish_marker(destination,dict(format=FORMAT,identity=iid,primary=tid,directory=str(destination.resolve()),active=True))
    checked_root(destination)
    return dict(primaryTeam=tid,identity=iid,sourceBackup=fingerprint(backup),databaseVersion=4,namingVersion=2)


def create_team_storage(destination,actor,name,request,tid,clock=time.time):
    destination=plain_path(destination)
    if destination.exists():
        service=expanded.open_active(destination)
        with service.connection() as db:
            meta=dict(db.execute('SELECT key,value FROM metadata'))
            require(meta.get('teamId')==tid and meta.get('teamName')==name and
                    meta.get('multiTeamCreatedBy')==actor['id'] and meta.get('multiTeamCreateRequest')==request,
                    'team_creation_incomplete',503)
        return service
    with tempfile.TemporaryDirectory(prefix='.create-team-',dir=destination.parent) as tmp:
        tmp=Path(tmp);seed=TeamService(tmp/'seed',clock)
        with seed.connection() as db:
            db.executemany('INSERT INTO metadata VALUES(?,?)',[('teamId',tid),('teamName',name),
                ('multiTeamCreatedBy',actor['id']),('multiTeamCreateRequest',request)])
            db.execute('INSERT INTO accounts VALUES(?,?,?,?,?,?,?,?)',(actor['id'],actor['username'],actor['name'],
                '!global-identity-only','admin',1,actor['consent'],int(clock())))
            db.execute("INSERT INTO account_memberships(account,state,revision) VALUES(?,'joined',0)",(actor['id'],))
        from server import Application
        from backup_archive import create_archive
        from permanent_storage import prepare_permanent_storage,PermanentStorage
        from permanent_backup import create_permanent_archive
        from permanent_runtime import activate as activate_permanent
        Application(seed);create_archive(seed,tmp/'seed.zip');prepare_permanent_storage(tmp/'seed.zip',tmp/'v4')
        activate_permanent(tmp/'v4',seed.directory,True)
        create_permanent_archive(PermanentStorage(tmp/'v4',clock),tmp/'v4.zip')
        expanded.prepare(tmp/'v4.zip',destination);expanded.activate(destination,tmp/'v4',True)
    return expanded.open_active(destination)


def create_archive(directory,destination):
    root,marker=checked_root(directory);destination=plain_path(destination);require(not destination.exists(),'backup_target_exists',409)
    with tempfile.TemporaryDirectory(prefix='.multi-backup-',dir=destination.parent) as tmp:
        stage=Path(tmp)
        with closing(sqlite3.connect(root/'identity/team.sqlite3')) as src,closing(sqlite3.connect(stage/'team.sqlite3')) as target:
            src.backup(target);target.execute('DELETE FROM sessions');target.execute('DELETE FROM invitations')
            target.execute('DELETE FROM mt_sessions');target.commit()
            rows=target.execute('SELECT id FROM mt_teams ORDER BY id').fetchall()
        (stage/'teams').mkdir();files={'team.sqlite3':fingerprint(stage/'team.sqlite3')};logical=files['team.sqlite3']['size'];entries=1
        for (tid,) in rows:
            name='teams/'+tid+'.zip';expanded.create_archive(expanded.open_active(root/'teams'/tid),stage/name);files[name]=fingerprint(stage/name)
            with zipfile.ZipFile(stage/name) as inner:info=inner.infolist();logical+=sum(i.file_size for i in info);entries+=len(info)
            require(logical<=MAX_TOTAL and entries<=MAX_ENTRIES,'backup_too_large')
        pending=destination.with_name(destination.name+'.pending');require(not pending.exists(),'backup_target_exists',409)
        try:
            with zipfile.ZipFile(pending,'x',zipfile.ZIP_DEFLATED) as z:
                for n in files:z.write(stage/n,n)
                z.writestr('manifest.json',json.dumps(dict(format=ARCHIVE,files=files,primary=marker['primary'],identity=marker['identity'])))
            with tempfile.TemporaryDirectory(prefix='.multi-verify-',dir=destination.parent) as verify:validate_archive(pending,Path(verify))
            with pending.open('r+b') as stream:os.fsync(stream.fileno())
            os.link(pending,destination)
            from permanent_journal import sync_directory
            sync_directory(destination.parent)
        finally:
            if pending.exists():pending.unlink()
    return dict(teams=len(rows),sha256=fingerprint(destination)['sha256'])


def validate_archive(archive,stage):
    stage=plain_path(stage)
    allowed=lambda n:n=='team.sqlite3' or n.startswith('teams/') and n.endswith('.zip') and bool(UUID.fullmatch(n[6:-4]))
    manifest=_extract_archive(archive,stage,ARCHIVE,extra_fields={'primary','identity'},entry_allowed=allowed)
    require(UUID.fullmatch(manifest['primary']) and UUID.fullmatch(manifest['identity']),'multi_team_archive_invalid')
    with closing(sqlite3.connect((stage/'team.sqlite3').as_uri()+'?mode=ro',uri=True)) as db:
        require(db.execute('PRAGMA integrity_check').fetchone()[0]=='ok' and not db.execute('PRAGMA foreign_key_check').fetchall()
                and db.execute('PRAGMA user_version').fetchone()[0]==2,'multi_team_archive_invalid')
        rows=db.execute('SELECT id FROM mt_teams ORDER BY id').fetchall()
        require(1<=len(rows)<=1000 and manifest['primary'] in {r[0] for r in rows},'multi_team_archive_invalid')
        require(not db.execute('SELECT 1 FROM sessions LIMIT 1').fetchone() and not db.execute('SELECT 1 FROM mt_sessions LIMIT 1').fetchone(),
                'multi_team_archive_live_session')
        require(set(manifest['files'])=={'team.sqlite3'}|{'teams/'+r[0]+'.zip' for r in rows},'multi_team_archive_invalid')
        require(dict(db.execute('SELECT key,value FROM metadata')).get('multiTeamIdentity')==manifest['identity'],'multi_team_archive_invalid')
    logical=manifest['files']['team.sqlite3']['size'];entries=1
    for (tid,) in rows:
        require(UUID.fullmatch(tid),'multi_team_archive_invalid')
        with zipfile.ZipFile(stage/'teams'/(tid+'.zip')) as inner:
            info=inner.infolist();logical+=sum(i.file_size for i in info);entries+=len(info)
        require(logical<=MAX_TOTAL and entries<=MAX_ENTRIES,'backup_too_large')
        target=stage/('validated-'+tid);target.mkdir()
        expanded.validate_archive(stage/'teams'/(tid+'.zip'),target)
        with closing(sqlite3.connect((target/'team.sqlite3').as_uri()+'?mode=ro',uri=True)) as db:
            require(db.execute("SELECT value FROM metadata WHERE key='teamId'").fetchone()[0]==tid,'multi_team_archive_invalid')
    return manifest


def restore_archive(archive,destination):
    destination=plain_path(destination);require(destination.parent.is_dir() and not destination.exists(),'multi_team_requires_new_directory',409)
    with tempfile.TemporaryDirectory(prefix='.multi-restore-',dir=destination.parent) as tmp:
        stage=Path(tmp);manifest=validate_archive(archive,stage)
        destination.mkdir(mode=0o700);(destination/'identity').mkdir();(destination/'teams').mkdir()
        shutil.copyfile(stage/'team.sqlite3',destination/'identity/team.sqlite3')
        with closing(sqlite3.connect(destination/'identity/team.sqlite3')) as db:
            db.execute("UPDATE metadata SET value=? WHERE key='multiTeamRoot'",(str(destination.resolve()),));db.commit()
            rows=db.execute('SELECT id FROM mt_teams ORDER BY id').fetchall()
        for (tid,) in rows:expanded.restore_archive(stage/'teams'/(tid+'.zip'),destination/'teams'/tid)
        publish_marker(destination,dict(format=FORMAT,identity=manifest['identity'],primary=manifest['primary'],directory=str(destination.resolve()),active=False))
    return dict(teams=len(rows),activated=False)


def _identity_business(root):
    ignored={'sessions','mt_sessions','mt_preferences','mt_jobs','throttle','audit'};result={}
    with closing(sqlite3.connect((root/'identity/team.sqlite3').as_uri()+'?mode=ro',uri=True)) as db:
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
            if table in ignored:continue
            require(table.replace('_','').isalnum(),'multi_team_identity_invalid')
            rows=db.execute('SELECT * FROM '+table+' ORDER BY rowid').fetchall()
            if table=='metadata':rows=[r for r in rows if r[0]!='multiTeamRoot']
            if table=='devices':
                columns=[r[1] for r in db.execute('PRAGMA table_info(devices)')]
                rows=[tuple(v for k,v in zip(columns,row) if k!='seen') for row in rows]
            if table=='invitations':continue
            result[table]=rows
    return result


def activate(directory,authority):
    root,marker=checked_root_without_teams(directory);source,_=checked_root(authority)
    require(root!=source and not marker['active'],'multi_team_activation_invalid',409)
    require(_identity_business(root)==_identity_business(source),'multi_team_restore_requires_current_identity',409)
    with closing(sqlite3.connect(root/'identity/team.sqlite3')) as db:rows=db.execute('SELECT id FROM mt_teams ORDER BY id').fetchall()
    for (tid,) in rows:
        require(_team_authorization(expanded.ExpandedPermanentStorage(root/'teams'/tid))==
                _team_authorization(expanded.open_active(source/'teams'/tid)),
                'multi_team_restore_requires_current_authorization',409)
    with closing(sqlite3.connect(source/'identity/team.sqlite3')) as src,closing(sqlite3.connect(root/'identity/team.sqlite3')) as dst:
        dst.execute('DELETE FROM mt_preferences')
        preferences=src.execute('SELECT * FROM mt_preferences').fetchall()
        if preferences:dst.executemany('INSERT INTO mt_preferences VALUES(?,?,?)',preferences)
        for account,device,seen in src.execute('SELECT account,id,seen FROM devices'):
            dst.execute('UPDATE devices SET seen=max(seen,?) WHERE account=? AND id=?',(seen,account,device))
        dst.commit()
    for (tid,) in rows:expanded.activate(root/'teams'/tid,source/'teams'/tid,True)
    _copy_channel(source,root)
    marker['active']=True;pending=root/(MARKER+'.active');save_new(pending,marker);os.replace(pending,root/MARKER)
    from permanent_journal import sync_directory
    sync_directory(root)
    checked_root(root);return dict(teams=len(rows),activated=True)


def _team_authorization(service):
    with service.connection() as db:
        return dict(accounts=[tuple(r) for r in db.execute('SELECT * FROM accounts ORDER BY id')],
            memberships=[tuple(r) for r in db.execute('SELECT * FROM account_memberships ORDER BY account')],
            grants=[tuple(r) for r in db.execute('SELECT * FROM artifact_grants ORDER BY account,artifact')],
            joinCode=[tuple(r) for r in db.execute('SELECT * FROM join_code')],
            revokedDevices=[tuple(r) for r in db.execute('SELECT account,id FROM devices WHERE revoked=1 ORDER BY account,id')])


def checked_root_without_teams(directory):
    root=plain_path(directory);marker=json.loads((root/MARKER).read_text('utf-8'),object_pairs_hook=unique_object)
    require(marker.get('format')==FORMAT and marker.get('directory')==str(root.resolve()) and marker.get('active') is False,
            'multi_team_activation_invalid',409)
    return root,marker


def business_snapshot(service):
    """Compare original business rows/PDFs after only verified storage rebinding."""
    marker,_=service._marker();tables={}
    ignored={'sessions','invitations','audit','throttle','operation_records'}
    with service.connection() as db:
        for (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"):
            if table in ignored:continue
            require(table.replace('_','').isalnum(),'multi_team_invalid_table')
            columns=[r[1] for r in db.execute('PRAGMA table_info('+table+')')]
            values=[]
            for row in db.execute('SELECT * FROM '+table+' ORDER BY rowid'):
                row=dict(zip(columns,row))
                if table=='metadata' and row['key'] in (expanded.ROOT,expanded.NAMING,'numberingCandidateId'):continue
                if table=='permanent_deletion_jobs':
                    plan=json.loads(row['plan'],object_pairs_hook=unique_object)
                    require(plan['storageId']==marker['storageId'],'multi_team_deletion_binding_invalid',503)
                    plan['storageId']='self';row['plan']=plan
                values.append(row)
            tables[table]=values
        pdfs={row['id']:dict(hash=row['hash'],size=row['size']) for row in db.execute('SELECT id,hash,size FROM artifacts')}
    for aid,expected in pdfs.items():
        actual=fingerprint(plain_path(service.directory/'artifacts'/(aid+'.pdf')))
        require(actual==dict(sha256=expected['hash'],size=expected['size']),'multi_team_pdf_changed',409)
    return dict(tables=tables,pdfs=pdfs)


def retire_source(source,destination,confirmed=False):
    require(confirmed is True,'multi_team_source_stop_required')
    root,marker=checked_root(destination);source=plain_path(source)
    active=plain_path(source/'permanent-active.json');raw=active.read_bytes();old=json.loads(raw,object_pairs_hook=unique_object)
    previous=root/'source-active-marker.json'
    if old.get('format')=='score-team-multi-redirect-v1':
        require(previous.is_file() and old==dict(format='score-team-multi-redirect-v1',directory=str(root.resolve()),
            identity=marker['identity'],primaryTeamId=marker['primary'],previousActivationSha256=hashlib.sha256(previous.read_bytes()).hexdigest()),
            'multi_team_source_redirect_changed',409)
        return dict(retired=True,alreadyRetired=True)
    service=expanded.open_active(source)
    require(team_id(service)==marker['primary'],'multi_team_source_team_changed',409)
    require(business_snapshot(service)==business_snapshot(expanded.open_active(root/'teams'/marker['primary'])),
            'multi_team_source_changed',409)
    if previous.exists():require(previous.read_bytes()==raw,'multi_team_source_changed',409)
    else:
        with previous.open('xb') as out:out.write(raw);out.flush();os.fsync(out.fileno())
    from permanent_journal import sync_directory
    sync_directory(root)
    redirect=dict(format='score-team-multi-redirect-v1',directory=str(root.resolve()),identity=marker['identity'],
        primaryTeamId=marker['primary'],previousActivationSha256=hashlib.sha256(raw).hexdigest())
    pending=source/'permanent-active.multi-pending.json'
    if pending.exists():require(json.loads(pending.read_text('utf-8'))==redirect,'multi_team_source_redirect_changed',409)
    else:save_new(pending,redirect)
    require(active.read_bytes()==raw,'multi_team_source_changed',409);os.replace(pending,active)
    sync_directory(source)
    return dict(retired=True,alreadyRetired=False,previousActivationSha256=redirect['previousActivationSha256'])
