"""Explicit release of fully retired score numbers; artifact retirements never disappear."""
from contextlib import closing
import hashlib,json,os,re,sqlite3,uuid
from pathlib import Path

from backup_archive import plain_path,unique_object,fingerprint
from publishing import canonical
from service import UUID,fields,require

MAX_BATCH=200
TABLES={'number_releases','number_release_jobs'}

def sequence(value):
    require(isinstance(value,str) and re.fullmatch(r'[0-9]{3}|[1-9][0-9]{3,18}',value) and
            1<=int(value)<=2**63-1 and value==f'{int(value):03d}','invalid_number_release')
    return value

def tables(db):
    present={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}&TABLES
    require(not present or present==TABLES,'number_release_ledger_invalid',503)
    return bool(present)

def ledger(db,reader):
    if not tables(db):return {}
    result={}
    retired=dict(db.execute('SELECT artifact,number FROM artifact_tombstones'))
    cursor=db.execute('SELECT * FROM number_releases ORDER BY length(sequence),sequence')
    columns=[c[0] for c in cursor.description]
    for values in cursor.fetchall():
        row=dict(zip(columns,values))
        seq=sequence(row['sequence']);generation=row['generation']
        require(isinstance(generation,str) and UUID.fullmatch(generation) and generation==row['request'] and
                type(row['released']) is int and row['released']>=0,'number_release_ledger_invalid',503)
        ids=json.loads(row['artifacts'],object_pairs_hook=unique_object)
        require(isinstance(ids,list) and 1<=len(ids)<=100000 and ids==sorted(set(ids)) and
                all(isinstance(i,str) and UUID.fullmatch(i) and i in retired and
                    reader(retired[i]).sequence==seq for i in ids),'number_release_ledger_invalid',503)
        job=db.execute('SELECT result FROM number_release_jobs WHERE owner=? AND request=?',
                       (row['owner'],row['request'])).fetchone()
        require(job is not None,'number_release_ledger_invalid',503)
        receipt=json.loads(job[0],object_pairs_hook=unique_object)
        require(receipt.get('requestId')==generation and receipt.get('ok') is True and
                seq in receipt.get('sequences',[]),'number_release_ledger_invalid',503)
        result[seq]=(generation,set(ids))
    return result

def generation(db,number,reader):
    parsed=reader(number)
    if parsed is None or not tables(db):return None
    row=db.execute('SELECT generation FROM number_releases WHERE sequence=?',(parsed.sequence,)).fetchone()
    if row is None:return None
    require(isinstance(row[0],str) and UUID.fullmatch(row[0]),'number_release_ledger_invalid',503)
    return row[0]

def piece_key(db,row,reader):
    parsed=reader(row['number'])
    key='sequence:'+parsed.sequence if parsed else 'artifact:'+row['id']
    gen=generation(db,row['number'],reader)
    return key+':released:'+gen if gen else key

def ensure_tables(db):
    db.execute('''CREATE TABLE IF NOT EXISTS number_releases(sequence TEXT PRIMARY KEY,generation TEXT NOT NULL,
        artifacts TEXT NOT NULL,released INTEGER NOT NULL,owner TEXT NOT NULL REFERENCES accounts(id),request TEXT NOT NULL)''')
    db.execute('''CREATE TABLE IF NOT EXISTS number_release_jobs(owner TEXT NOT NULL REFERENCES accounts(id),
        request TEXT NOT NULL,spec TEXT NOT NULL,result TEXT NOT NULL,PRIMARY KEY(owner,request))''')

class NumberRelease:
    def __init__(self,service,catalog):self.service,self.catalog=service,catalog

    def admin(self,db,token,device):
        actor=self.service.authenticate(db,token,device)
        require(actor['role']=='admin','forbidden',403)
        require(self.service.database_version==4 and self.service.naming_version==2,'number_release_unsupported',426)
        return actor

    def eligible(self,db):
        reader=self.catalog.read_number;released=ledger(db,reader);groups={}
        live={reader(r[0]).sequence for r in db.execute('SELECT number FROM artifact_metadata') if reader(r[0])}
        pending=set()
        for row in db.execute('SELECT owner,request,spec FROM uploads WHERE complete=0 AND expires>=?',(int(self.service.clock()),)):
            if db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?',(row['owner'],row['request'])).fetchone():continue
            parsed=reader(json.loads(row['spec'],object_pairs_hook=unique_object)['scoreNumber'])
            if parsed:pending.add(parsed.sequence)
        for row in db.execute('''SELECT t.*,j.completed FROM artifact_tombstones t LEFT JOIN permanent_deletion_jobs j
                ON j.request=t.request ORDER BY t.sequence'''):
            parsed=reader(row['number'])
            if not parsed:continue
            seq=parsed.sequence
            group=groups.setdefault(seq,dict(sequence=seq,artifacts=[],deletedParts=0,latestDeletedAt=0,complete=True))
            group['artifacts'].append(row['artifact']);group['deletedParts']+=1
            group['latestDeletedAt']=max(group['latestDeletedAt'],row['deleted'])
            group['complete']=group['complete'] and row['completed']==2
        return {seq:g for seq,g in groups.items() if seq not in live|pending and g['complete'] and
                not set(g['artifacts'])<=released.get(seq,('',set()))[1]}

    def view(self,token,device):
        with self.service.connection() as db:
            actor=self.admin(db,token,device);marker,_=self.service._marker()
            available=self.eligible(db)
            return dict(protocolVersion=1,numberReleaseReady=True,storageId=marker['storageId'],
                teamId=self.service.principal(db,actor)['teamId'],accountId=actor['id'],
                revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0]),
                eligible=[{k:v for k,v in available[s].items() if k in ('sequence','deletedParts','latestDeletedAt')}
                          for s in sorted(available,key=int)])

    def _preview(self,db,actor,body):
        fields(body,{'sequences','expectedRevision'})
        seqs=body['sequences']
        require(isinstance(seqs,list) and 1<=len(seqs)<=MAX_BATCH and len(set(seqs))==len(seqs),'invalid_number_release')
        for s in seqs:sequence(s)
        require(type(body['expectedRevision']) is int and body['expectedRevision']>=0)
        revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
        require(revision==body['expectedRevision'],'catalog_changed',409)
        eligible=self.eligible(db)
        require(all(s in eligible for s in seqs),'number_not_releasable',409)
        marker,_=self.service._marker()
        selected=[eligible[s] for s in sorted(seqs,key=int)]
        scope=dict(storageId=marker['storageId'],accountId=actor['id'],revision=revision,selected=selected)
        return dict(protocolVersion=1,storageId=marker['storageId'],accountId=actor['id'],revision=revision,
                    sequences=sorted(seqs,key=int),deletedParts=sum(g['deletedParts'] for g in selected),
                    scopeDigest=hashlib.sha256(canonical(scope).encode()).hexdigest())

    def preview(self,body,token,device):
        with self.service.connection() as db:return self._preview(db,self.admin(db,token,device),body)

    def backup(self,request,owner,spec):
        from expanded_permanent import create_archive,validate_archive
        create_archive,validate_archive=getattr(self.service,'complete_archive',(create_archive,validate_archive))
        import tempfile
        base=plain_path(self.service.directory/'backups');base.mkdir(exist_ok=True)
        parent=plain_path(base/'number-releases');parent.mkdir(exist_ok=True)
        folder=plain_path(parent/request);folder.mkdir(exist_ok=True)
        intent=dict(owner=owner,spec=spec,storageId=self.service._marker()[0]['storageId'])
        path=plain_path(folder/'intent.json')
        if path.exists():require(json.loads(path.read_text('utf-8'))==intent,'request_conflict',409)
        else:
            with path.open('x',encoding='utf-8') as f:json.dump(intent,f);f.flush();os.fsync(f.fileno())
        archive=plain_path(folder/'before.zip')
        if not archive.exists():create_archive(self.service,archive)
        with tempfile.TemporaryDirectory(prefix='.number-release-check-',dir=folder) as stage:
            validate_archive(archive,Path(stage))
        return fingerprint(archive)

    def commit(self,body,token,device):
        fields(body,{'sequences','expectedRevision','scopeDigest','requestId','confirmed'})
        require(body['confirmed'] is True,'number_release_confirmation_required')
        request=body['requestId'];require(isinstance(request,str) and UUID.fullmatch(request))
        require(isinstance(body['scopeDigest'],str) and re.fullmatch('[a-f0-9]{64}',body['scopeDigest']))
        spec=canonical({k:body[k] for k in ('sequences','expectedRevision','scopeDigest')})
        preview_body={k:body[k] for k in ('sequences','expectedRevision')}
        with self.service.connection() as db:
            actor=self.admin(db,token,device);owner=actor['id']
            if tables(db):
                old=db.execute('SELECT spec,result FROM number_release_jobs WHERE owner=? AND request=?',(owner,request)).fetchone()
                if old:
                    require(old['spec']==spec,'request_conflict',409);return json.loads(old['result'])
            preview=self._preview(db,actor,preview_body)
            require(preview['scopeDigest']==body['scopeDigest'],'number_release_scope_changed',409)
        backup=self.backup(request,owner,spec)
        with self.service.connection() as db:
            actor=self.admin(db,token,device)
            if tables(db):
                old=db.execute('SELECT spec,result FROM number_release_jobs WHERE owner=? AND request=?',(owner,request)).fetchone()
                if old:
                    require(old['spec']==spec,'request_conflict',409);return json.loads(old['result'])
            preview=self._preview(db,actor,preview_body)
            require(preview['scopeDigest']==body['scopeDigest'],'number_release_scope_changed',409)
            eligible=self.eligible(db);ensure_tables(db)
            wanted=set(preview['sequences'])
            # Cancel every prior upload intent for these numbers, including expired requests.
            for row in db.execute('SELECT owner,request,spec FROM uploads').fetchall():
                parsed=self.catalog.read_number(json.loads(row['spec'])['scoreNumber'])
                if parsed and parsed.sequence in wanted:
                    db.execute('INSERT OR IGNORE INTO cancelled_uploads VALUES(?,?)',(row['owner'],row['request']))
            for seq in preview['sequences']:
                db.execute('INSERT OR REPLACE INTO number_releases VALUES(?,?,?,?,?,?)',
                    (seq,request,canonical(sorted(eligible[seq]['artifacts'])),int(self.service.clock()),owner,request))
            self.catalog.changed(db)
            result=dict(ok=True,requestId=request,sequences=preview['sequences'],releasedCount=len(wanted),
                        backup=backup,revision=preview['revision']+1)
            db.execute('INSERT INTO number_release_jobs VALUES(?,?,?,?)',(owner,request,spec,canonical(result)))
            ledger(db,self.catalog.read_number)
            self.service.audit(db,owner,'numbers_released',request)
            return result
