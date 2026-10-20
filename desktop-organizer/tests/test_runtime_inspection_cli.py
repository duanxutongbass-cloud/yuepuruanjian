"""Exercise the real read-only entry point in a separate Python process."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

from test_library_upgrade_snapshot import contents, db_state
from test_runtime_candidate import prepared


def run_cli(*args, default_root):
    env = dict(os.environ, SCORE_ORGANIZER_DATA_DIR=str(default_root),
               PYTHONIOENCODING='utf-8', PYTHONDONTWRITEBYTECODE='1')
    # Use product code and the installed runtime dependencies, not pytest's test
    # directory or its in-process connection adapters.
    env['PYTHONPATH'] = os.pathsep.join(filter(None, [
        str(Path(__file__).resolve().parents[1] / 'src'), env.get('PYTHONPATH', '')]))
    return subprocess.run([sys.executable, '-m',
        'score_organizer.services.runtime_candidate', *map(str, args)],
        capture_output=True, text=True, encoding='utf-8', env=env, timeout=30)


def test_real_entry_verifies_unicode_candidate_without_default_library_or_writes(tmp_path):
    data = tmp_path / '中文 路径'
    data.mkdir()
    db, old_output, originals, manifest = prepared(data)
    before, state = contents(manifest.parent), db_state(db.path)
    default_root = tmp_path / '绝不创建默认库'
    result = run_cli(manifest, default_root=default_root)
    assert result.returncode == 0, result.stderr
    assert result.stderr == ''
    report = json.loads(result.stdout)
    assert report['format'] == 'score-organizer-runtime-inspection'
    assert report['verified'] is True and report['activationAllowed'] is False
    assert report['database'] == str(manifest.parent / 'runtime/library.sqlite3')
    assert report['output'] == str(manifest.parent / 'runtime/files')
    assert report['sourceCount'] == len(originals)
    assert '1000' in report['limitations'] and '激活租约' in report['limitations']
    assert not default_root.exists()
    assert contents(manifest.parent) == before and db_state(db.path) == state


@pytest.mark.parametrize('damage', ['missing_manifest', 'invalid_json', 'missing_database',
                                   'database_hash', 'extra_file', 'activation_flag'])
def test_failed_entry_does_not_start_gui_or_repair_or_fall_back(tmp_path, damage):
    db, old_output, originals, manifest = prepared(tmp_path)
    if damage == 'missing_manifest':
        manifest.unlink()
    elif damage == 'invalid_json':
        manifest.write_text('{', encoding='utf-8')
    elif damage == 'missing_database':
        (manifest.parent / 'runtime/library.sqlite3').unlink()
    elif damage == 'database_hash':
        with (manifest.parent / 'runtime/library.sqlite3').open('ab') as stream:
            stream.write(b'changed')
    elif damage == 'extra_file':
        (manifest.parent / 'runtime/files/unregistered.pdf').write_bytes(b'%PDF new')
    else:
        data = json.loads(manifest.read_text('utf-8'))
        data['readyForActivation'] = True
        manifest.write_text(json.dumps(data), encoding='utf-8')
    before, old_files, state = contents(manifest.parent), contents(old_output), db_state(db.path)
    default_root = tmp_path / 'default'
    result = run_cli(manifest, default_root=default_root)
    assert result.returncode == 3
    assert result.stdout == '' and '候选核验失败，未启用' in result.stderr
    assert 'Traceback' not in result.stderr
    assert not default_root.exists()
    assert contents(manifest.parent) == before
    assert contents(old_output) == old_files and db_state(db.path) == state


@pytest.mark.parametrize('args,expected', [([], 2), (['--activate'], 2),
    (['missing.json', '--activate'], 2), (['--help'], 0)])
def test_cli_requires_explicit_manifest_and_has_no_activation_option(tmp_path, args, expected):
    default_root = tmp_path / 'default'
    result = run_cli(*args, default_root=default_root)
    assert result.returncode == expected
    assert not default_root.exists() and list(tmp_path.iterdir()) == []
    if expected == 0:
        assert '只读核验' in result.stdout
    else:
        assert result.stdout == '' and 'usage:' in result.stderr
