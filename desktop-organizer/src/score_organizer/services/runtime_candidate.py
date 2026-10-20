"""Read-only inspection of an isolated runtime candidate; never activate it."""
from contextlib import closing
from dataclasses import dataclass
import json
from pathlib import Path
import sqlite3

from score_organizer.services.library_relocation import _source_references, _standalone
from score_organizer.services.number_upgrade_service import _identity, _inventory, _read_hash, _regular, _verify_tree
from score_organizer.services.reset_service import _unique_manifest_fields
from score_organizer.storage.number_upgrade import _integrity, _no_links
from score_organizer.storage.writer_guard import require_format_one_guards


@dataclass(frozen=True)
class RuntimeCandidate:
    database: Path
    output: Path
    source_count: int
    activation_allowed: bool = False


def inspect_runtime_candidate(manifest_path: str | Path) -> RuntimeCandidate:
    manifest_path = Path(manifest_path).absolute()
    _standalone(manifest_path)
    manifest_identity = _identity(_regular(manifest_path))
    original_manifest = _read_hash(manifest_path, manifest_identity)
    manifest = json.loads(manifest_path.read_text('utf-8'), object_pairs_hook=_unique_manifest_fields)
    if not isinstance(manifest, dict) or manifest.get('format') != 'score-organizer-library-relocation' or \
            type(manifest.get('version')) is not int or manifest['version'] != 1 or \
            manifest.get('readyForActivation') is not False:
        raise ValueError('不支持的独立运行候选清单，不能打开或回退到旧库')
    if manifest.get('runtimeDatabase') != 'runtime/library.sqlite3' or manifest.get('runtimeOutput') != 'runtime/files':
        raise ValueError('运行候选路径不符合隔离布局')
    if not isinstance(manifest.get('directories'), list) or not isinstance(manifest.get('files'), dict):
        raise ValueError('候选输出目录证明缺失或无效')
    root = manifest_path.parent
    database, output, sources = root / 'runtime/library.sqlite3', root / 'runtime/files', root / 'runtime/sources'
    for path in (database, output, sources):
        _no_links(path)
    _standalone(database)
    database_identity = _identity(_regular(database))
    if _read_hash(database, database_identity) != manifest.get('runtimeDatabaseProof'):
        raise ValueError('候选数据库与准备记录不一致')
    _verify_tree(output, manifest['directories'], manifest['files'], independent=True)
    initial_sources = _inventory(sources)
    references = manifest.get('sourcePaths')
    if not isinstance(references, dict):
        raise ValueError('候选源文件映射缺失')
    expected_files, expected_directories, mapped = {}, {'.'}, {}
    for old, proof in references.items():
        if not isinstance(old, str) or not isinstance(proof, dict) or not isinstance(proof.get('path'), str):
            raise ValueError('候选源文件映射无效')
        if not {'bytes', 'sha256'}.issubset(proof):
            raise ValueError('候选源文件哈希证明缺失')
        target = Path(proof['path'])
        if not target.is_absolute() or target.parent.parent != sources or target.name != Path(old).name or \
                len(target.parent.name) != 8 or not target.parent.name.isascii() or not target.parent.name.isdecimal():
            raise ValueError('候选源文件未隔离到独立目录')
        relative = target.relative_to(sources).as_posix()
        if relative in expected_files:
            raise ValueError('候选源文件映射重复')
        expected_files[relative] = {key: proof[key] for key in ('bytes', 'sha256')}
        expected_directories.add(target.parent.name)
        mapped[str(target)] = proof['sha256']
    _verify_tree(sources, sorted(expected_directories), expected_files, inventory=initial_sources, independent=True)
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as connection:
        connection.execute('BEGIN')
        require_format_one_guards(connection)
        _integrity(connection)
        if connection.execute('PRAGMA journal_mode').fetchone()[0] != 'delete':
            raise ValueError('候选数据库含未封存的运行日志')
        settings = dict(connection.execute('SELECT key,value FROM settings'))
        if settings.get('output_root') != str(output) or settings.get('last_reset_backup', ''):
            raise ValueError('候选设置仍指向旧输出或旧清空备份')
        if _source_references(connection) != mapped:
            raise ValueError('候选数据库源文件引用与独立副本不一致')
    _standalone(database)
    if _read_hash(database, database_identity) != manifest['runtimeDatabaseProof'] or \
            _read_hash(manifest_path, manifest_identity) != original_manifest:
        raise ValueError('候选在核验期间发生变化')
    _verify_tree(output, manifest['directories'], manifest['files'], independent=True)
    _verify_tree(sources, sorted(expected_directories), expected_files, inventory=initial_sources, independent=True)
    return RuntimeCandidate(database, output, len(mapped))


def main(argv: list[str] | None = None) -> int:
    """Inspect an explicitly selected candidate without starting the writable GUI."""
    import argparse
    import sys

    parser = argparse.ArgumentParser(
        description='只读核验独立运行候选；不启用、不打开整理界面、不回退到旧库。')
    parser.add_argument('manifest', type=Path, help='独立迁移候选的完成清单路径')
    args = parser.parse_args(argv)
    try:
        candidate = inspect_runtime_candidate(args.manifest)
    except (OSError, ValueError, sqlite3.Error) as exc:
        print(f'候选核验失败，未启用：{exc}', file=sys.stderr)
        return 3
    print(json.dumps({
        'format': 'score-organizer-runtime-inspection',
        'version': 1,
        'verified': True,
        'activationAllowed': candidate.activation_allowed,
        'database': str(candidate.database),
        'output': str(candidate.output),
        'sourceCount': candidate.source_count,
        'limitations': '仅为此次只读核验；不提供激活租约，未证明旧程序隔离，不开放1000号。',
    }, ensure_ascii=False))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
