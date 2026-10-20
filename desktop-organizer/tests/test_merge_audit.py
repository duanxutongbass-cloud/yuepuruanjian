import copy
import json
import os
from pathlib import Path

import pytest

from score_organizer.services.catalog_merge_service import CatalogMergeService
from score_organizer.services.duplicate_layout import DuplicateLayoutStore
from score_organizer.services.historical_manifests import verify_pending_manifests
from score_organizer.services.import_service import sha256_file
from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.merge_audit import read_current_merge_audits
from score_organizer.services.reset_service import ResetService
from test_catalog_merge_service import merge_fixture, selected_ids
from test_historical_manifest_relocation import CandidateDatabase
from test_library_upgrade_snapshot import contents, db_state


def merged(tmp_path):
    db, root, ids, sources = merge_fixture(tmp_path)
    service = CatalogMergeService(db, root)
    plan = service.build_plan(*ids, selected_ids(db, ids), '萤火虫之墓《可爱的家》')
    service.commit_plan(plan)
    record = plan.duplicate_root / f'合并对比记录_{plan.event_id}.json'
    value = json.loads(record.read_text('utf-8'))
    return db, root, record, value, sources


def save(record, value):
    record.write_text(json.dumps(value, ensure_ascii=False), encoding='utf-8')


def duplicate(value):
    return next(item for item in value['files'] if item['score_id'] not in value['selected_score_ids'])


def test_current_audit_is_read_only_and_keeps_both_duplicate_copies(tmp_path):
    db, root, record, value, sources = merged(tmp_path)
    before, data = contents(root), db_state(db.path)
    proof = DuplicateLayoutStore(db, root).current_merge_audits()
    assert proof['records'] == [str(record)] and len(proof['entries']) == 2
    assert len(proof['checkedFiles']) == 3
    assert all(Path(item['copy_source']).is_file() for item in proof['entries'])
    assert {item['source_sha256'] for item in proof['entries']} == {duplicate(value)['sha256']}
    verified = verify_pending_manifests(root, db.path)
    assert verified['mergeAuditItems'] == 2 and verified['mergeAuditRecords'] == 1
    assert contents(root) == before and db_state(db.path) == data
    assert all(source.exists() for source in sources)


def test_real_relocation_then_old_clear_resolves_only_new_local_copies(tmp_path):
    db, root, record, value, sources = merged(tmp_path)
    before = contents(root)
    record_bytes = record.read_bytes()
    target = tmp_path / '独立候选'
    proof = json.loads(prepare_relocated_library(db.path, target).read_text('utf-8'))
    assert proof['historicalPendingManifestProof']['mergeAuditItems'] == 2
    assert not proof['readyForActivation']
    for source in set(sources):
        source.rename(source.with_suffix('.retained'))
    reset = ResetService(db)
    reset.clear(reset.build_plan())
    future = CandidateDatabase(target / 'runtime/library.sqlite3')
    future_root = target / 'runtime/files'
    audit = DuplicateLayoutStore(future, future_root).current_merge_audits()
    assert len(audit['entries']) == 2
    assert all(Path(item['copy_source']).is_relative_to(future_root) for item in audit['entries'])
    assert all(not Path(item['source_path']).exists() for item in audit['entries'])
    assert Path(audit['records'][0]).read_bytes() == record_bytes
    assert contents(future_root) == contents(target / 'snapshot/files') == before
    assert all(sha256_file(Path(item['copy_source'])) == item['source_sha256'] for item in audit['entries'])


def test_current_merge_coexists_with_global_duplicate_manifest(tmp_path):
    db, root, record, value, sources = merged(tmp_path)
    source = tmp_path / '其他重复.pdf'
    source.write_bytes(b'%PDF other confirmed duplicate')
    store = DuplicateLayoutStore(db, root)
    store.store([dict(source_path=str(source), source_sha256=sha256_file(source), display_title=value['title'],
        parsed_fields={'instrument_code': '03', 'category_code': '01'}, reasons=['人工确认'], details=[])],
        lambda: None, lambda: None)
    before = contents(root)
    proof = verify_pending_manifests(root, db.path)
    assert proof['mergeAuditItems'] == 2 and proof['duplicateLayoutItems'] == 1
    assert contents(root) == before


@pytest.mark.parametrize('case', [
    'missing-field', 'boolean-song-id', 'same-song', 'invalid-seq', 'same-seq', 'invalid-category',
    'invalid-version', 'event-mismatch', 'wrong-root', 'list-type', 'duplicate-original',
    'wrong-owner', 'invalid-layout', 'duplicate-file', 'missing-original', 'invalid-instrument',
    'hash-mismatch', 'selected-duplicate', 'selected-unknown', 'selected-empty', 'selected-all',
    'escape-path', 'nested-path', 'same-copy', 'invalid-hash', 'empty-provenance',
])
def test_invalid_audit_refuses_without_modifying_library(tmp_path, case):
    db, root, record, value, sources = merged(tmp_path)
    item = duplicate(value)
    if case == 'missing-field': del value['original_records']
    elif case == 'boolean-song-id': value['song_id'] = True
    elif case == 'same-song': value['source_song_id'] = value['song_id']
    elif case == 'invalid-seq': value['seq'] = '0007'
    elif case == 'same-seq': value['source_seq'] = value['seq']
    elif case == 'invalid-category': value['category'] = '一二'
    elif case == 'invalid-version': value['version_no'] = 0
    elif case == 'event-mismatch': value['event_id'] = '0' * 32
    elif case == 'wrong-root': value['duplicate_root'] = str(record.parent / '错误位置')
    elif case == 'list-type': value['files'] = {}
    elif case == 'duplicate-original': value['original_records'].append(copy.deepcopy(value['original_records'][0]))
    elif case == 'wrong-owner': value['original_records'][0]['song_id'] = 999
    elif case == 'invalid-layout': value['original_records'][0]['storage_layout'] = 'unknown'
    elif case == 'duplicate-file': value['files'].append(copy.deepcopy(item))
    elif case == 'missing-original': value['original_records'].pop()
    elif case == 'invalid-instrument': item['instrument_code'] = '1'
    elif case == 'hash-mismatch': item['sha256'] = '0' * 64
    elif case == 'selected-duplicate': value['selected_score_ids'].append(value['selected_score_ids'][0])
    elif case == 'selected-unknown': value['selected_score_ids'][0] = 9999
    elif case == 'selected-empty': value['selected_score_ids'] = []
    elif case == 'selected-all': value['selected_score_ids'] = [item['score_id'] for item in value['files']]
    elif case == 'escape-path': item['new_instrument'] = str(record.parent.parent / '越界.pdf')
    elif case == 'nested-path': item['new_instrument'] = str(record.parent / '内部/不允许.pdf')
    elif case == 'same-copy': item['new_song'] = item['new_instrument']
    elif case == 'invalid-hash': value['original_records'][0]['source_sha256'] = 'not-a-digest'
    elif case == 'empty-provenance': value['original_records'][0]['source_path'] = ''
    save(record, value)
    before, data = contents(root), db_state(db.path)
    with pytest.raises(ValueError):
        verify_pending_manifests(root, db.path)
    assert contents(root) == before and db_state(db.path) == data


@pytest.mark.parametrize('case', ['missing-pdf', 'changed-pdf', 'orphan-pdf', 'duplicate-json-key', 'bad-name', 'nested-record'])
def test_ambiguous_or_changed_audit_artifacts_block_migration(tmp_path, case):
    db, root, record, value, sources = merged(tmp_path)
    item = duplicate(value)
    pdf = Path(item['new_instrument'])
    if case == 'missing-pdf': pdf.unlink()
    elif case == 'changed-pdf': pdf.write_bytes(b'%PDF modified after merge')
    elif case == 'orphan-pdf': (record.parent / '未登记.pdf').write_bytes(b'%PDF orphan')
    elif case == 'duplicate-json-key': record.write_text(record.read_text('utf-8').replace('{', '{"song_id": 123,', 1), encoding='utf-8')
    elif case == 'bad-name': record.rename(record.with_name('合并对比记录_bad.json'))
    elif case == 'nested-record':
        nested = record.parent / '多余层'
        nested.mkdir()
        record.rename(nested / record.name)
    before, data = contents(root), db_state(db.path)
    with pytest.raises(ValueError):
        prepare_relocated_library(db.path, tmp_path / 'refused-candidate')
    assert contents(root) == before and db_state(db.path) == data


def test_live_hardlink_is_refused_but_snapshot_copy_is_independent(tmp_path):
    db, root, record, value, sources = merged(tmp_path)
    pdf = Path(duplicate(value)['new_instrument'])
    outside = tmp_path / 'outside-link.pdf'
    os.link(pdf, outside)
    before = contents(root)
    with pytest.raises(ValueError):
        verify_pending_manifests(root, db.path)
    target = tmp_path / 'independent-candidate'
    prepare_relocated_library(db.path, target)
    copied = target / 'runtime/files' / pdf.relative_to(root)
    assert copied.stat().st_nlink == 1 and not os.path.samefile(copied, pdf)
    assert copied.read_bytes() == outside.read_bytes() == pdf.read_bytes()
    assert contents(root) == contents(target / 'runtime/files') == before


def test_empty_duplicate_directory_is_not_created_by_consumer(tmp_path):
    root = tmp_path / 'missing'
    assert read_current_merge_audits(root) == dict(entries=[], records=[], checkedFiles={})
    assert not root.exists()
