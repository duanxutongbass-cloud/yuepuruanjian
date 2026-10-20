"""Deletion scope and guarded local commit. No public activation yet."""
import hashlib
import json
import uuid

from backup_archive import fingerprint, plain_path, unique_object
from permanent_storage import supports_permanent
from programs import piece_key
from publishing import canonical
from service import UUID, fields, require


def piece_reference(key):
    raw=hashlib.md5(('team-library-v1/piece/'+key).encode('utf-8')).digest()
    return str(uuid.UUID(bytes=raw,version=3))


class PermanentDeletion:
    def __init__(self, service, catalog):
        require(supports_permanent(service),
                'permanent_service_required',503)
        require(catalog.service is service,'permanent_service_required',503)
        self.service,self.catalog=service,catalog

    def preview(self, body, token, device):
        with self.service.connection() as db:
            actor=self.service.authenticate(db,token,device)
            require(actor['role']=='admin','forbidden',403)
            return self._preview(db,actor,body)

    def _preview(self, db, actor, body):
        fields(body,{'artifactIds','expectedRevision'})
        ids=body['artifactIds']
        require(isinstance(ids,list) and 1<=len(ids)<=200 and all(isinstance(i,str) and UUID.fullmatch(i) for i in ids)
                and len(set(ids))==len(ids))
        require(type(body['expectedRevision']) is int and body['expectedRevision']>=0)
        revision=int(db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()[0])
        require(revision==body['expectedRevision'],'catalog_changed',409)
        markers,_=self.service._marker()
        scope=dict(storage=markers['storageId'],account=actor['id'],revision=revision,artifacts=[],heads=[],history=[],
                   operations=[],programs=[],freezes=[],folders=[],survivors=[],uploads=[])
        details=[];keys=set();total=0;personal=shared=history_count=0
        for aid in sorted(ids):
            row=db.execute('''SELECT f.*,m.number,m.category,r.id AS removed FROM artifacts f
                LEFT JOIN artifact_metadata m ON m.artifact=f.id LEFT JOIN removed_artifacts r ON r.id=f.id
                WHERE f.id=?''',(aid,)).fetchone()
            require(row is not None,'not_found',404)
            require(row['removed'] is not None and not row['published'],'artifact_not_in_recycle',409)
            key=self.catalog.piece_key(db,row);keys.add(key)
            path=plain_path(self.catalog.root/(aid+'.pdf'))
            require(path.is_file() and path.stat().st_nlink==1,'artifact_unavailable',409)
            require(fingerprint(path)==dict(size=row['size'],sha256=row['hash']),'artifact_unavailable',409)
            scope['artifacts'].append(dict(row));total+=row['size']
            details.append(dict(artifactId=aid,title=row['title'],partName=row['part'],scoreNumber=row['number'] or ''))
            heads=[tuple(r) for r in db.execute('SELECT * FROM annotation_heads WHERE artifact=? ORDER BY page,owner,kind',(aid,))]
            history=[tuple(r) for r in db.execute('SELECT * FROM annotation_history WHERE artifact=? ORDER BY id',(aid,))]
            personal+=sum(r[3]=='personal' for r in heads);shared+=sum(r[3]!='personal' for r in heads)
            history_count+=len(history);scope['heads']+=heads;scope['history']+=history
        surviving_keys=set()
        for row in db.execute('''SELECT f.id,m.number FROM artifacts f LEFT JOIN artifact_metadata m ON m.artifact=f.id ORDER BY f.id'''):
            key=self.catalog.piece_key(db,row)
            if row['id'] not in ids and key in keys:
                surviving_keys.add(key);scope['survivors'].append(tuple(row))
        disappearing=keys-surviving_keys
        for row in db.execute('SELECT * FROM uploads ORDER BY id'):
            spec=json.loads(row['spec'],object_pairs_hook=unique_object)
            number=self.catalog.read_number(spec['scoreNumber'])
            if row['id'] in ids or number and self.catalog.piece_key(db,dict(id=row['id'],number=spec['scoreNumber'])) in disappearing:
                scope['uploads'].append(tuple(row))
        affected_programs=[];freeze_ids=[]
        for row in db.execute('SELECT * FROM programs ORDER BY id'):
            pieces=json.loads(row['pieces'],object_pairs_hook=unique_object)
            require(isinstance(pieces,list),'permanent_preview_unavailable',409)
            frozen=db.execute('SELECT * FROM program_freezes WHERE program=?',(row['id'],)).fetchone()
            affected=bool(set(pieces)&disappearing)
            if frozen:
                manifest=json.loads(frozen['manifest'],object_pairs_hook=unique_object)
                require(isinstance(manifest,dict) and isinstance(manifest.get('artifacts'),list)
                        and all(isinstance(a,dict) for a in manifest['artifacts']),'permanent_preview_unavailable',409)
                if any(a.get('artifactId') in ids for a in manifest['artifacts']):
                    freeze_ids.append(frozen['id']);scope['freezes'].append(tuple(frozen));affected=True
            if affected:
                affected_programs.append(dict(id=row['id'],title=row['title'],frozen=frozen is not None))
                scope['programs'].append(tuple(row))
        references={piece_reference(key) for key in disappearing};folder_accounts=[]
        for row in db.execute('SELECT * FROM personal_folder_heads ORDER BY account'):
            folders=json.loads(row['payload'],object_pairs_hook=unique_object)
            require(isinstance(folders,list),'permanent_preview_unavailable',409)
            if any(set(f['pieces'])&references or f['coverPiece'] in references for f in folders):
                folder_accounts.append(row['account']);scope['folders'].append(tuple(row))
        # Legacy receipts did not store an artifact identity and may contain
        # marks. Their payloads must be retired without deleting unrelated
        # annotation heads/history; future v4 writes get an exact target map.
        receipts=list(db.execute('''SELECT o.* FROM annotation_operations o LEFT JOIN annotation_operation_targets t
            ON t.account=o.account AND t.request=o.request WHERE t.artifact IS NULL
            ORDER BY o.account,o.request'''))
        scope['operations']=[tuple(r) for r in receipts]
        for aid in sorted(ids):
            scope['operations'] += [tuple(r) for r in db.execute('''SELECT o.* FROM annotation_operations o
                JOIN annotation_operation_targets t ON t.account=o.account AND t.request=o.request
                WHERE t.artifact=? ORDER BY o.account,o.request''',(aid,))]
        digest=hashlib.sha256(canonical(scope).encode('utf-8')).hexdigest()
        return dict(permanentDeletionReady=False,revision=revision,scopeDigest=digest,parts=details,
            fileCount=len(ids),totalBytes=total,personalPages=personal,sharedPages=shared,
            historyRecords=history_count,programs=affected_programs,freezeIds=freeze_ids,
            folderAccounts=folder_accounts,legacyReceiptCount=len(receipts),pieceReferences=sorted(references))

    def commit(self, body, token, device):
        from permanent_journal import commit
        return commit(self,body,token,device)

    def recover(self):
        from permanent_journal import recover
        return recover(self)
