import importlib.util
import json
import zipfile
from pathlib import Path

import pytest

spec = importlib.util.spec_from_file_location('delivery', Path(__file__).parents[1] / 'scripts/create_delivery.py')
delivery = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delivery)


def fixture(tmp_path, monkeypatch):
    root = tmp_path / 'repo'
    root.mkdir()
    for directory in ('src', 'tests', 'scripts', 'packaging'):
        (root / directory).mkdir()
        (root / directory / 'content.py').write_text('# source\n', encoding='utf-8')
    for filename in ('pyproject.toml', 'README.md', 'SPEC.md', 'PROGRESS.md', 'AGENTS.md'):
        (root / filename).write_text(filename, encoding='utf-8')
    (root / 'work').mkdir()
    (root / 'work/user.sqlite3').write_bytes(b'private database')
    (root / 'private.pdf').write_bytes(b'private score')
    (root / 'src/__pycache__').mkdir()
    (root / 'src/__pycache__/private.pyc').write_bytes(b'cache')
    build = tmp_path / 'build'
    build.mkdir()
    (build / '曲谱库重奏乐谱整理工具.exe').write_bytes(b'exe-fixture')
    (build / '_internal').mkdir()
    (build / '_internal/runtime.dll').write_bytes(b'dll-fixture')
    monkeypatch.setattr(delivery.importlib.metadata, 'version', lambda _: '1.0')
    class Distribution:
        files = []
    monkeypatch.setattr(delivery.importlib.metadata, 'distribution', lambda _: Distribution())
    return root, build, tmp_path / '交付'


def test_clean_source_runtime_archive_and_hashes(tmp_path, monkeypatch):
    root, build, target = fixture(tmp_path, monkeypatch)
    delivery.assemble(root, build, target)
    delivery.finalize(target)
    records = json.loads((target / '文件校验.json').read_text(encoding='utf-8'))
    assert (target / 'Windows程序/_internal/runtime.dll').read_bytes() == b'dll-fixture'
    assert '源码/pyproject.toml' in records
    assert not list(target.rglob('*.sqlite3')) and not list(target.rglob('*.pdf'))
    assert not list(target.rglob('*.pyc'))
    for relative, record in records.items():
        assert delivery.digest(target / relative) == record['sha256']
    with zipfile.ZipFile(target.with_suffix('.zip')) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == len(records) + 1


@pytest.mark.parametrize('kind', ['directory', 'zip', 'missing_runtime'])
def test_existing_or_incomplete_package_rejected(tmp_path, monkeypatch, kind):
    root, build, target = fixture(tmp_path, monkeypatch)
    if kind == 'directory':
        target.mkdir()
        (target / 'keep.txt').write_text('keep')
    elif kind == 'zip':
        target.with_suffix('.zip').write_bytes(b'keep')
    else:
        (build / '_internal/runtime.dll').unlink()
        (build / '_internal').rmdir()
    with pytest.raises(ValueError):
        delivery.assemble(root, build, target)
    if kind == 'directory':
        assert (target / 'keep.txt').read_text() == 'keep'
    elif kind == 'zip':
        assert target.with_suffix('.zip').read_bytes() == b'keep'


def test_main_reports_dll_loading_failure_without_opening_database(monkeypatch):
    import builtins
    from score_organizer.main import main
    original = builtins.__import__
    def importing(name, *args, **kwargs):
        if name == 'PySide6.QtWidgets':
            raise ImportError('DLL test failure')
        return original(name, *args, **kwargs)
    monkeypatch.setattr(builtins, '__import__', importing)
    with pytest.raises(SystemExit, match='DLL test failure'):
        main()
