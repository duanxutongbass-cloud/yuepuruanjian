from collections import Counter
import math
import uuid

import pytest

from score_organizer.domain.score_naming import format_score_filename, format_song_folder
from score_organizer.services.expanded_team_publish import ExpandedPublishService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.team_publish import PublishService
from test_expanded_archive_service import prepared
from test_expanded_catalog_service import archived
from test_expanded_team_publish import CandidateClient, state
from test_team_upload_queue import ProtocolClient, library


def large_candidate(tmp_path, count):
    old, old_root, originals, db, root, archive = prepared(tmp_path)
    sources, selected = list(originals), []
    for offset in range(count):
        seq, title = str(1000 + offset), f'合成曲目{offset}'
        song = db.create_song('01', seq, title, status='archived')
        name = format_score_filename('01', '01', seq, title, naming_version=2)
        source = tmp_path / name
        data = f'%PDF synthetic expanded part {offset}'.encode()
        source.write_bytes(data)
        sources.append(source)
        folder = format_song_folder('01', seq, title, 1, naming_version=2)
        for target in (root / '按乐器分类' / archive._instrument_folder('01') / name,
                       root / '按曲目分类' / folder / name):
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        selected.append(db.add_score(song, '01', name, str(source), sha256_file(source), name))
    return old, old_root, sources, db, root, selected


@pytest.mark.parametrize('count', [6, 51, 101])
def test_pdf_hash_reads_are_four_passes_per_copy_not_whole_plan_per_request(tmp_path, count, monkeypatch):
    old, old_root, sources, db, root, selected = large_candidate(tmp_path, count)
    admin = str(uuid.uuid4())
    service, client = ExpandedPublishService(db), CandidateClient(admin)
    plan = service.build_plan({score_id: [admin] for score_id in selected})
    before = state(old, old_root, sources, db, root)
    reads, number_reads = Counter(), []
    def read(path):
        reads[path] += 1
        return sha256_file(path)
    from score_organizer.services import expanded_team_publish
    parse = expanded_team_publish.parse_score_number
    def number(value, **kwargs):
        number_reads.append(value)
        return parse(value, **kwargs)
    monkeypatch.setattr('score_organizer.services.team_publish.sha256_file', read)
    monkeypatch.setattr(expanded_team_publish, 'parse_score_number', number)
    receipt = service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert len(receipt['artifactIds']) == count and client.content_count == count
    assert [len(b['assignments']) for b in client.batches] == [min(50, count - n) for n in range(0, count, 50)]
    assert client.reads == count * 2 + math.ceil(count / 50)
    assert number_reads == [part.number for part in plan.parts]
    assert set(reads) == {path for part in plan.parts for path in part.sources}
    assert set(reads.values()) == {4} and sum(reads.values()) == 8 * count
    assert state(old, old_root, sources, db, root) == before


@pytest.mark.parametrize('expanded', [True, False])
def test_file_change_in_final_progress_callback_blocks_publication(tmp_path, expanded):
    admin = str(uuid.uuid4())
    if expanded:
        _, _, _, db, _, _, song = archived(tmp_path)
        rows = db.list_archived_scores(song)
        service, client = ExpandedPublishService(db), CandidateClient(admin)
    else:
        db, _ = library(tmp_path, 3)
        rows = db.list_archived_scores()
        service, client = PublishService(db), ProtocolClient(admin)
    plan = service.build_plan({r['id']: [admin] for r in rows})
    def progress(message):
        if message.startswith('核对并提交'):
            plan.parts[0].sources[-1].write_bytes(b'%PDF external change before commit')
    with pytest.raises(ValueError, match='副本缺失或变化'):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True, progress=progress)
    assert client.content_count == 3 and not client.receipts


@pytest.mark.parametrize('change', ['pdf', 'metadata'])
def test_entire_current_batch_is_rechecked_after_final_capability_read(tmp_path, change):
    old, old_root, sources, db, root, _, song = archived(tmp_path)
    admin = str(uuid.uuid4())
    service, client = ExpandedPublishService(db), CandidateClient(admin)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores(song)})
    def mutate():
        if client.reads == 7:
            if change == 'pdf': plan.parts[0].sources[-1].write_bytes(b'%PDF externally modified after upload')
            else:
                with db.session() as connection:
                    connection.execute('UPDATE song SET display_title=? WHERE id=?', ('外部改名', song))
    client.after_read = mutate
    originals = {p: p.read_bytes() for p in sources}
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.reads == 7 and client.content_count == 3 and not client.receipts
    assert client.calls[-1] == ('GET', '/v2/admin/capabilities')
    assert {p: p.read_bytes() for p in sources} == originals


def test_changed_later_pdf_is_rejected_before_its_upload_and_no_batch_is_granted(tmp_path):
    _, _, sources, db, _, _, song = archived(tmp_path)
    admin = str(uuid.uuid4())
    service, client = ExpandedPublishService(db), CandidateClient(admin)
    plan = service.build_plan({r['id']: [admin] for r in db.list_archived_scores(song)})
    originals = {p: p.read_bytes() for p in sources}
    def mutate():
        if client.reads == 1:
            plan.parts[-1].sources[-1].write_bytes(b'%PDF external change to a future part')
    client.after_read = mutate
    with pytest.raises(ValueError):
        service.publish(plan, client, str(uuid.uuid4()), confirmed=True)
    assert client.content_count == 2 and not client.receipts
    assert plan.parts[-1].number not in {upload['spec']['scoreNumber'] for upload in client.uploads.values()}
    assert {p: p.read_bytes() for p in sources} == originals


def test_maximum_requirement_is_numeric_and_local_to_each_nested_or_retried_call(tmp_path):
    old, old_root, sources, db, root, _, _ = archived(tmp_path, '10000')
    admin = str(uuid.uuid4())
    service = ExpandedPublishService(db)
    rows = db.list_archived_scores()
    high = service.build_plan({r['id']: [admin] for r in rows})
    low = service.build_plan({next(r for r in rows if r['seq'] == '001')['id']: [admin]})
    outer, inner = CandidateClient(admin), CandidateClient(admin)
    outer.reply['numbering']['maximumSequence'] = '1000'
    inner.reply['numbering']['maximumSequence'] = '1000'
    before = state(old, old_root, sources, db, root)
    def nested():
        if outer.reads == 1:
            result = service.publish(low, inner, str(uuid.uuid4()), confirmed=True)
            assert len(result['artifactIds']) == 1
    outer.after_read = nested
    with pytest.raises(ValueError, match='超过服务'):
        service.publish(high, outer, str(uuid.uuid4()), confirmed=True)
    assert outer.calls == [('GET', '/v2/admin/capabilities')] and len(inner.receipts) == 1
    outer.after_read = lambda: None
    assert len(service.publish(low, outer, str(uuid.uuid4()), confirmed=True)['artifactIds']) == 1
    before_calls = len(outer.calls)
    with pytest.raises(ValueError, match='超过服务'):
        service.publish(high, outer, str(uuid.uuid4()), confirmed=True)
    assert outer.calls[before_calls:] == [('GET', '/v2/admin/capabilities')]
    assert state(old, old_root, sources, db, root) == before
