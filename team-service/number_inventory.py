"""Read-only v1 inventory. Existing and retained numbers are never reassigned."""
import json
from backup_archive import unique_object

from score_numbers import require_current_writer
from service import require


def inventory(db, now, *, parse=require_current_writer, maximum=999, protocol=1):
    occupied=set()
    tables={r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    def reserve(value):
        if value is None or value == '':
            return
        occupied.add(int(parse(value).sequence))
    for row in db.execute('SELECT number FROM artifact_metadata'):
        reserve(row[0])
    pending=0
    if 'uploads' in tables:
        for row in db.execute('SELECT owner,request,spec FROM uploads WHERE complete=0 AND expires>=?',(int(now),)):
            if 'cancelled_uploads' in tables and db.execute('SELECT 1 FROM cancelled_uploads WHERE owner=? AND request=?',
                                                          (row['owner'],row['request'])).fetchone():
                continue
            spec=json.loads(row['spec'],object_pairs_hook=unique_object)
            require(isinstance(spec,dict) and 'scoreNumber' in spec,'number_inventory_unavailable',409)
            reserve(spec['scoreNumber']);pending+=1
    if 'artifact_tombstones' in tables:
        if 'number_releases' in tables:
            from number_release import ledger
            released=ledger(db,parse)
            for row in db.execute('SELECT artifact,number FROM artifact_tombstones'):
                if row['number'] and row['artifact'] in released.get(parse(row['number']).sequence,('',set()))[1]:continue
                reserve(row['number'])
        else:
            for row in db.execute('SELECT number FROM artifact_tombstones'):reserve(row[0])
    # Walk only occupied numbers, never the potentially 64-bit capacity range.
    first=1
    for number in sorted(occupied):
        if number == first:first+=1
        elif number > first:break
    revision=db.execute("SELECT value FROM metadata WHERE key='catalogRevision'").fetchone()
    require(revision is not None,'number_inventory_unavailable',503)
    available=maximum-len(occupied)
    return dict(protocolVersion=protocol,revision=int(revision[0]),maximumSequence=str(maximum),
                occupiedSequences=[f'{n:03d}' for n in sorted(occupied)],
                availableCount=str(available) if protocol == 2 else available,
                firstFreeSequence=f'{first:03d}' if first <= maximum else None,pendingUploads=pending)
