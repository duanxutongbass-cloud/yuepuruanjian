import json

import pytest

from score_organizer.services.expanded_reset_service import ExpandedResetService
from score_organizer.services.import_service import sha256_file
from score_organizer.services.reset_service import MANAGED_FOLDERS, ResetService
from score_organizer.storage.database import Database, UnsupportedDatabaseVersion
from score_organizer.storage.number_upgrade import prepare_number_upgrade
from score_organizer.storage.writer_guard import require_format_one_guards
from test_historical_manifest_relocation import CandidateDatabase
from test_reset_service import filled_library


def candidate(tmp_path):
    old, root, sources = filled_library(tmp_path)
    prepare_number_upgrade(old.path, tmp_path / "upgrade")
    db = CandidateDatabase(tmp_path / "upgrade/candidate.sqlite3")
    song = db.create_song('01', None, 'Expanded draft', status='draft')
    # Only the isolated migrated schema is populated through its guarded SQL
    # connection; the current product create_song keeps its 999 limit.
    with db.transaction() as connection:
        connection.execute("UPDATE song SET seq='1000' WHERE id=?", (song,))
    return db, root, sources, ExpandedResetService(db)


def snapshot(folder):
    return {p.relative_to(folder).as_posix(): sha256_file(p) for p in folder.rglob('*') if p.is_file()}


def test_expanded_clear_restore_preserves_full_sequence_pdfs_and_guards(tmp_path):
    db, root, sources, service = candidate(tmp_path)
    source_hashes = {p: sha256_file(p) for p in sources}
    expected = {name: snapshot(root / name) for name in MANAGED_FOLDERS}
    backup = service.clear(service.build_plan())
    manifest = json.loads((backup / '清空记录.json').read_text('utf-8'))
    assert manifest['version'] == 2 and manifest['database_format'] == 1
    assert db.count_songs() == db.count_scores() == 0
    before = sha256_file(backup / '清空前.sqlite3')
    service.restore(backup)
    assert db.count_songs() == 2 and db.count_scores() == 3
    with db.session() as connection:
        assert connection.execute("SELECT seq FROM song WHERE display_title='Expanded draft'").fetchone()[0] == '1000'
        require_format_one_guards(connection)
        assert not connection.execute('PRAGMA foreign_key_check').fetchall()
    assert {name: snapshot(root / name) for name in MANAGED_FOLDERS} == expected
    assert {p: sha256_file(p) for p in sources} == source_hashes
    assert sha256_file(backup / '清空前.sqlite3') == before
    with pytest.raises(ValueError):
        service.restore(backup)
    with pytest.raises(UnsupportedDatabaseVersion):
        Database(db.path).connect()


@pytest.mark.parametrize('change', ['pdf', 'extra', 'missing', 'empty_directory', 'proof', 'format', 'version'])
def test_changed_expanded_backup_refused_without_live_mutation(tmp_path, change):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    path = backup / '清空记录.json'
    manifest = json.loads(path.read_text('utf-8'))
    pdf = next(backup.rglob('*.pdf'))
    if change == 'pdf':
        pdf.write_bytes(b'%PDF changed')
    elif change == 'extra':
        (backup / MANAGED_FOLDERS[0] / 'extra.txt').write_text('extra')
    elif change == 'missing':
        pdf.rename(pdf.with_suffix('.retained'))
    elif change == 'empty_directory':
        (backup / MANAGED_FOLDERS[0] / 'new-empty').mkdir()
    else:
        if change == 'proof':
            manifest['folder_proofs'].pop(MANAGED_FOLDERS[0])
        elif change == 'format':
            manifest['database_format'] = True
        else:
            manifest['version'] = 1
        path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    preserved = snapshot(backup)
    originals = {p: sha256_file(p) for p in sources}
    with pytest.raises(ValueError):
        service.restore(backup)
    assert db.count_songs() == db.count_scores() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
    assert snapshot(backup) == preserved
    assert {p: sha256_file(p) for p in sources} == originals


def test_current_reset_entry_rejects_version_two_before_moving_files(tmp_path):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    before = snapshot(backup)
    # The real v1 service reads settings through the guarded test adapter; its
    # manifest gate must reject v2 independently of the production DB v1 gate.
    with pytest.raises(ValueError):
        ResetService(db).restore(backup)
    assert snapshot(backup) == before
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)


def test_attached_snapshot_guard_is_checked_in_its_own_schema(tmp_path):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    with db.transaction() as connection:
        connection.execute('ATTACH DATABASE ? AS other', (str(backup / '清空前.sqlite3'),))
        require_format_one_guards(connection, schema='other')
        trigger = connection.execute("SELECT name FROM other.sqlite_schema WHERE type='trigger' AND name LIKE '_score_format1_guard_%' LIMIT 1").fetchone()[0]
        connection.execute('DROP TRIGGER other."' + trigger + '"')
        require_format_one_guards(connection)
        with pytest.raises(ValueError):
            require_format_one_guards(connection, schema='other')


def test_failed_restore_after_directory_move_rolls_back_expanded_state(tmp_path, monkeypatch):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    before = snapshot(backup)
    original = service._rename_folder
    calls = 0

    def rename(source, target):
        nonlocal calls
        calls += 1
        if calls == 2:
            raise ValueError('injected directory move failure')
        original(source, target)

    monkeypatch.setattr(service, '_rename_folder', rename)
    with pytest.raises(ValueError):
        service.restore(backup)
    assert db.count_songs() == db.count_scores() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
    after = snapshot(backup)
    assert {p: digest for p, digest in after.items() if not p.startswith('恢复前_')} == before
    service.restore(backup)
    assert db.count_songs() == 2


def test_expanded_reset_refuses_format_zero_before_moving_files(tmp_path):
    db, root, sources = filled_library(tmp_path)
    expected = snapshot(root)
    service = ExpandedResetService(db)
    with pytest.raises(ValueError):
        service.clear(service.build_plan())
    assert snapshot(root) == expected
    assert db.count_songs() == 1


def test_missing_snapshot_guard_rejected_even_with_updated_database_hash(tmp_path):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    import sqlite3
    from contextlib import closing
    snapshot_path = backup / '清空前.sqlite3'
    with closing(sqlite3.connect(snapshot_path)) as connection:
        trigger = connection.execute("SELECT name FROM sqlite_schema WHERE type='trigger' AND name LIKE '_score_format1_guard_%' LIMIT 1").fetchone()[0]
        connection.execute('DROP TRIGGER "' + trigger + '"')
        connection.commit()
    manifest_path = backup / '清空记录.json'
    manifest = json.loads(manifest_path.read_text('utf-8'))
    manifest['database_sha256'] = sha256_file(snapshot_path)
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    before = snapshot(backup)
    with pytest.raises(ValueError):
        service.restore(backup)
    assert snapshot(backup) == before
    assert db.count_songs() == 0


def test_late_restored_pdf_change_rejects_commit_and_returns_folders(tmp_path, monkeypatch):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    originals = {p: sha256_file(p) for p in sources}
    original = service._rename_folder
    changed = False

    def rename(source, target):
        nonlocal changed
        original(source, target)
        if not changed and target.parent == root:
            pdfs = list(target.rglob('*.pdf'))
            if pdfs:
                pdfs[0].write_bytes(b'%PDF externally changed after move')
                changed = True

    monkeypatch.setattr(service, '_rename_folder', rename)
    with pytest.raises(ValueError):
        service.restore(backup)
    assert changed and db.count_songs() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
    assert all((backup / name).is_dir() for name in MANAGED_FOLDERS)
    assert {p: sha256_file(p) for p in sources} == originals
    with pytest.raises(ValueError):
        service.restore(backup)


def test_manifest_write_failure_rolls_back_and_allows_retry(tmp_path, monkeypatch):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    original = service._write_manifest

    def write(target, manifest):
        if manifest['state'] == 'restored':
            raise OSError('injected manifest write failure')
        original(target, manifest)

    monkeypatch.setattr(service, '_write_manifest', write)
    with pytest.raises(ValueError):
        service.restore(backup)
    assert db.count_songs() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
    monkeypatch.setattr(service, '_write_manifest', original)
    service.restore(backup)
    assert db.count_songs() == 2


def test_omitting_folder_and_its_proof_together_cannot_restore_partial_library(tmp_path):
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    path = backup / '清空记录.json'
    manifest = json.loads(path.read_text('utf-8'))
    removed = manifest['folders'].pop(0)
    manifest['folder_proofs'].pop(removed)
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding='utf-8')
    before = snapshot(backup)
    with pytest.raises(ValueError, match='实际受管目录'):
        service.restore(backup)
    assert snapshot(backup) == before
    assert db.count_songs() == db.count_scores() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)


def test_shared_file_entity_in_expanded_backup_cannot_restore(tmp_path):
    import os
    db, root, sources, service = candidate(tmp_path)
    backup = service.clear(service.build_plan())
    pdf = next(backup.rglob('*.pdf'))
    external = tmp_path / 'external-hardlink.pdf'
    os.link(pdf, external)
    before = snapshot(backup)
    with pytest.raises(ValueError, match='共享实体'):
        service.restore(backup)
    assert snapshot(backup) == before
    assert db.count_songs() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
