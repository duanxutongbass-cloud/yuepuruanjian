"""Bounded, monotonic deletion notifications; physical cleanup precedes delivery.

Only guarded v4 copies can use this component. No HTTP/startup activation here.
"""
import re

from permanent_journal import plan_value
from permanent_storage import supports_permanent
from service import UUID,fields,require

MAX_CURSOR=2**63-1


def decimal(value):
    require(isinstance(value,str) and re.fullmatch(r'0|[1-9][0-9]{0,18}',value) and int(value)<=MAX_CURSOR,
            'invalid_deletion_cursor')
    return int(value)


class DeletionEvents:
    def __init__(self,service):
        require(supports_permanent(service),
                'permanent_service_required',503)
        self.service=service

    def page(self,body,token,device):
        fields(body,{'protocolVersion','storageId','after','limit'})
        require(type(body['protocolVersion']) is int and body['protocolVersion']==1,'protocol_unsupported')
        after=decimal(body['after']);storage=body['storageId'];limit=body['limit']
        require(storage is None or isinstance(storage,str) and UUID.fullmatch(storage),'invalid_deletion_cursor')
        require(type(limit) is int and 1<=limit<=100,'invalid_deletion_limit')
        with self.service.connection() as db:
            actor=self.service.authenticate(db,token,device)
            marker,_=self.service._marker()
            require(storage==marker['storageId'] or storage is None and after==0,'deletion_storage_changed',409)
            require(not db.execute('''SELECT 1 FROM artifact_tombstones t LEFT JOIN permanent_deletion_jobs j
                ON j.request=t.request GROUP BY t.sequence HAVING count(j.request)!=1 OR min(j.completed) NOT IN (1,2)
                LIMIT 1''').fetchone(),'permanent_event_ledger_invalid',503)
            maximum=db.execute('SELECT coalesce(max(sequence),0) FROM artifact_tombstones').fetchone()[0]
            pending=db.execute('''SELECT min(t.sequence) FROM artifact_tombstones t JOIN permanent_deletion_jobs j
                ON j.request=t.request WHERE j.completed=1''').fetchone()[0]
            # Never skip a lower, unfinished event and acknowledge a later one.
            through=min(maximum,pending-1) if pending is not None else maximum
            require(after<=through,'deletion_cursor_ahead',409)
            rows=list(db.execute('SELECT * FROM artifact_tombstones WHERE sequence>? AND sequence<=? ORDER BY sequence LIMIT ?',
                                 (after,through,limit)))
            jobs={}
            for row in rows:
                require(type(row['deleted']) is int and row['deleted']>=0,'permanent_event_ledger_invalid',503)
                request=row['request']
                if request not in jobs:
                    job=db.execute('SELECT * FROM permanent_deletion_jobs WHERE request=?',(request,)).fetchone()
                    plan=plan_value(None,job,storage=marker)
                    jobs[request]=plan
                plan=jobs[request]
                require(any(f['artifactId']==row['artifact'] and f['number']==row['number'] for f in plan['files']),
                        'permanent_event_ledger_invalid',503)
            next_after=rows[-1]['sequence'] if rows else after
            require(rows or after==through,'permanent_event_ledger_invalid',503)
            return dict(protocolVersion=1,storageId=marker['storageId'],teamId=self.service.principal(db,actor)['teamId'],
                accountId=actor['id'],after=str(after),through=str(through),nextAfter=str(next_after),more=next_after<through,
                deletions=[dict(sequence=str(r['sequence']),artifactId=r['artifact'],requestId=r['request'],deletedAt=r['deleted']) for r in rows],
                jobs=[dict(requestId=request,programIds=plan['programIds'],freezeIds=plan['freezeIds'],
                           pieceReferences=plan['pieceReferences']) for request,plan in jobs.items()])
