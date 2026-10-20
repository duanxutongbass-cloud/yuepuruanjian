import json
import hashlib
import sqlite3
from contextlib import closing

import pytest

from score_organizer.services.library_relocation import prepare_relocated_library
from score_organizer.services.runtime_candidate import inspect_runtime_candidate
from score_organizer.services.reset_service import ResetService
from score_organizer.storage.writer_guard import register_format_one_writer
from test_library_upgrade_snapshot import make_library, contents, db_state


def prepared(tmp_path):
    db, old_output, originals = make_library(tmp_path)
    path = prepare_relocated_library(db.path, tmp_path / 'candidate')
    return db, old_output, originals, path


def test_inspection_returns_only_isolated_paths_without_activation_or_writes(tmp_path):
    db, root, originals, manifest = prepared(tmp_path)
    before, state = contents(tmp_path / 'candidate'), db_state(db.path)
    result = inspect_runtime_candidate(manifest)
    assert result.database == manifest.parent / 'runtime/library.sqlite3'
    assert result.output == manifest.parent / 'runtime/files'
    assert result.source_count == len(originals)
    assert not result.activation_allowed
    assert contents(tmp_path / 'candidate') == before
    assert db_state(db.path) == state


def test_candidate_selection_survives_actual_old_library_clear(tmp_path):
    db, root, originals, manifest = prepared(tmp_path)
    expected = contents(tmp_path / 'candidate')
    service = ResetService(db)
    service.clear(service.build_plan())
    assert db.count_scores() == 0
    result = inspect_runtime_candidate(manifest)
    assert result.database.is_file() and not result.activation_allowed
    assert contents(tmp_path / 'candidate') == expected


@pytest.mark.parametrize('field,value', [
    ('version', True), ('version', 2), ('format', 'unknown'),
    ('readyForActivation', True), ('runtimeDatabase', '../old.sqlite3'),
    ('runtimeOutput', 'runtime/../files'), ('sourcePaths', None),
    ('directories', None), ('files', None),
])
def test_invalid_manifest_never_falls_back_to_old_default_library(tmp_path, field, value):
    db, root, originals, path = prepared(tmp_path)
    manifest = json.loads(path.read_text('utf-8'))
    manifest[field] = value
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    old_state, old_files = db_state(db.path), contents(root)
    with pytest.raises(ValueError):
        inspect_runtime_candidate(path)
    assert db_state(db.path) == old_state and contents(root) == old_files


def test_missing_candidate_database_is_not_silently_created(tmp_path):
    db, root, originals, path = prepared(tmp_path)
    database = path.parent / 'runtime/library.sqlite3'
    database.rename(database.with_suffix('.retained'))
    with pytest.raises((ValueError, FileNotFoundError)):
        inspect_runtime_candidate(path)
    assert not database.exists()


@pytest.mark.parametrize('area', ['files', 'sources'])
def test_changed_independent_file_tree_blocks_selection(tmp_path, area):
    db, root, originals, path = prepared(tmp_path)
    folder = path.parent / 'runtime' / area
    (folder / 'unregistered.pdf').write_bytes(b'%PDF unregistered')
    before = contents(path.parent)
    with pytest.raises(ValueError):
        inspect_runtime_candidate(path)
    assert contents(path.parent) == before


@pytest.mark.parametrize('change', ['output', 'source'])
def test_database_old_path_rejected_even_if_recorded_database_hash_is_updated(tmp_path, change):
    db, root, originals, path = prepared(tmp_path)
    manifest = json.loads(path.read_text('utf-8'))
    database = path.parent / 'runtime/library.sqlite3'
    with closing(sqlite3.connect(database)) as connection:
        register_format_one_writer(connection)
        if change == 'output':
            connection.execute("UPDATE settings SET value=? WHERE key='output_root'", (str(root),))
        else:
            connection.execute('UPDATE score SET source_path=? WHERE id=(SELECT MIN(id) FROM score)', (str(originals[0]),))
        connection.commit()
    manifest['runtimeDatabaseProof'] = {'bytes': database.stat().st_size,
        'sha256': hashlib.sha256(database.read_bytes()).hexdigest()}
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    old_state, old_files = db_state(db.path), contents(root)
    with pytest.raises(ValueError):
        inspect_runtime_candidate(path)
    assert db_state(db.path) == old_state and contents(root) == old_files


def test_missing_source_hash_proof_is_rejected_explicitly(tmp_path):
    db, root, originals, path = prepared(tmp_path)
    manifest = json.loads(path.read_text('utf-8'))
    next(iter(manifest['sourcePaths'].values())).pop('sha256')
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    with pytest.raises(ValueError, match='哈希证明缺失'):
        inspect_runtime_candidate(path)
