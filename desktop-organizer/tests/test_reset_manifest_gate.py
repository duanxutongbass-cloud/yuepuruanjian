import json

import pytest

from score_organizer.services.import_service import sha256_file
from score_organizer.services.reset_service import ResetService, MANAGED_FOLDERS
from test_reset_service import filled_library


def prepared(tmp_path):
    db, root, sources = filled_library(tmp_path)
    service = ResetService(db)
    backup = service.clear(service.build_plan())
    return db, root, sources, service, backup


def assert_preserved(db, root, sources, backup, hashes, manifest_bytes):
    assert db.count_songs() == db.count_scores() == 0
    assert all(not (root / name).exists() for name in MANAGED_FOLDERS)
    assert all(sha256_file(path) == digest for path, digest in hashes.items())
    assert (backup / "清空记录.json").read_bytes() == manifest_bytes
    assert not list(backup.glob("恢复前_*.sqlite3"))
    assert all(path.is_file() for path in sources)


@pytest.mark.parametrize("version", [True, 1.0, "1", None, 0, 2, -1])
def test_noncanonical_or_future_manifest_version_refused_without_mutation(tmp_path, version):
    db, root, sources, service, backup = prepared(tmp_path)
    path = backup / "清空记录.json"
    manifest = json.loads(path.read_text("utf-8"))
    manifest["version"] = version
    path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    before = path.read_bytes()
    hashes = {p: sha256_file(p) for p in [*sources, *(backup.rglob("*.pdf")), backup / "清空前.sqlite3"]}
    with pytest.raises(ValueError):
        service.restore(backup)
    assert_preserved(db, root, sources, backup, hashes, before)


@pytest.mark.parametrize("field", ["version", "folders", "database_sha256"])
def test_duplicate_manifest_fields_refused_even_when_identical(tmp_path, field):
    db, root, sources, service, backup = prepared(tmp_path)
    path = backup / "清空记录.json"
    original = path.read_text("utf-8")
    value = json.loads(original)[field]
    path.write_text(original.rstrip()[:-1] + ',' + json.dumps(field) + ':' + json.dumps(value) + '}', encoding="utf-8")
    before = path.read_bytes()
    hashes = {p: sha256_file(p) for p in [*sources, *(backup.rglob("*.pdf")), backup / "清空前.sqlite3"]}
    with pytest.raises(ValueError, match="重复字段"):
        service.restore(backup)
    assert_preserved(db, root, sources, backup, hashes, before)


def test_changed_but_individually_valid_manifest_refused_before_restore(tmp_path, monkeypatch):
    db, root, sources, service, backup = prepared(tmp_path)
    path = backup / "清空记录.json"
    original = service._validate_restore
    calls = 0
    changed = None
    hashes = {p: sha256_file(p) for p in [*sources, *(backup.rglob("*.pdf")), backup / "清空前.sqlite3"]}

    def validate(target):
        nonlocal calls, changed
        calls += 1
        if calls == 2:
            manifest = json.loads(path.read_text("utf-8"))
            manifest["folders"] = manifest["folders"][:-1]
            path.write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
            changed = path.read_bytes()
        return original(target)

    monkeypatch.setattr(service, "_validate_restore", validate)
    with pytest.raises(ValueError, match="清单已经变化"):
        service.restore(backup)
    assert calls == 2
    assert_preserved(db, root, sources, backup, hashes, changed)


def test_unchanged_integer_one_manifest_remains_restorable(tmp_path):
    db, root, sources, service, backup = prepared(tmp_path)
    service.restore(backup)
    assert db.count_songs() == 1 and db.count_scores() == 3
    assert all((root / name).is_dir() for name in MANAGED_FOLDERS)
    assert all(p.is_file() for p in sources)
