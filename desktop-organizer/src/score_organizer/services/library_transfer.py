"""Whole-library transfer: immutable snapshot, checked ZIP, independent restore.

Nothing is restored over a live library. Only current runtime locators are
rebased; original names, numbering, manual decisions and audit text survive.
"""
from contextlib import closing
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath, PureWindowsPath
import re
import shutil
import sqlite3
import stat
import tempfile
import uuid
import zipfile

from score_organizer.storage.database import Database, require_supported_database
from score_organizer.storage.number_upgrade import _backup, _integrity, _no_links, _state, _write_json_exclusive
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards
from score_organizer.services.library_relocation import _source_references, _rebase_database
from score_organizer.services.number_upgrade_service import (
    _copy_tree, _identity, _inventory, _output_root, _read_hash, _regular, _verify_tree,
)
from score_organizer.services.reset_service import _unique_manifest_fields

FORMAT = 'score-organizer-whole-library'
MAX_MANIFEST = 64 * 1024 * 1024
MAX_FILES = 250000
MAX_TOTAL = 1024 ** 4


def _json(data):
    return json.loads(data, object_pairs_hook=_unique_manifest_fields)


def _check_db(connection, naming):
    own_transaction = not connection.in_transaction
    if own_transaction:
        connection.execute('BEGIN')
    try:
        if naming == 2:
            require_format_one_guards(connection)
        elif naming == 1:
            require_supported_database(connection)
        else:
            raise ValueError('迁移包编号版本不受支持')
        _integrity(connection)
    finally:
        if own_transaction:
            connection.rollback()


def _counts(connection):
    return dict(songs=connection.execute('SELECT count(*) FROM song').fetchone()[0],
                archivedScores=connection.execute('SELECT count(*) FROM score').fetchone()[0],
                draftItems=connection.execute('SELECT count(*) FROM import_item').fetchone()[0])


class _SnapshotDatabase(Database):
    """Read-only snapshot, never initializes a database or needs live markers."""
    def __init__(self, path, naming):
        super().__init__(path)
        self._numbering_version = naming

    def _require_supported(self, connection):
        if self._numbering_version == 2:
            require_format_one_guards(connection)
        else:
            require_supported_database(connection)

    def connect(self):
        connection = sqlite3.connect(self.path.as_uri() + '?mode=ro', uri=True)
        try:
            if self._numbering_version == 2:
                register_format_one_writer(connection)
            connection.row_factory = sqlite3.Row
            connection.execute('PRAGMA query_only=ON')
            connection.execute('BEGIN')
            self._require_supported(connection)
            connection.rollback()
            return connection
        except BaseException:
            connection.close()
            raise


def _verify_parts(database, naming, root, files):
    # Path calculation only: no writes, auto-renumbering, reparsing or merging.
    from score_organizer.services.archive_service import ArchiveService
    snapshot = _SnapshotDatabase(database, naming)
    rows = snapshot.list_archived_scores()
    if not rows:
        return
    if root is None:
        raise ValueError('已有正式分谱但缺少整理目录')
    archive = ArchiveService(snapshot, root)
    archive._numbering_version = naming
    for score in rows:
        if root is None:
            raise ValueError('已有正式分谱但缺少整理目录')
        for path in archive.score_paths(score):
            relative = path.absolute().relative_to(root).as_posix()
            if files.get(relative, {}).get('sha256') != score['source_sha256']:
                raise ValueError(f"正式分谱缺失或变化：{score['output_name']}")


def _new_file(partial, target):
    # Windows rename refuses to overwrite an existing destination, including FAT.
    if os.name == 'nt':
        os.rename(partial, target)
    else:
        os.link(partial, target)
        partial.unlink()


def export_library(db, destination, *, progress=lambda _message: None, skip_errors=False):
    destination = Path(destination).absolute()
    _no_links(destination)
    if destination.exists() or not destination.parent.is_dir():
        raise ValueError('迁移包目标已存在或父目录不存在，请另选文件名')
    naming = db._numbering_version
    source = db.path.absolute()
    _regular(source)
    with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=0)) as live:
        if naming == 2:
            register_format_one_writer(live)
        live.execute('BEGIN')
        db._require_supported(live)
        _check_db(live, naming)
        if not skip_errors and live.execute("SELECT 1 FROM song WHERE status='migration_pending' UNION ALL SELECT 1 FROM import_item WHERE status='migration_pending' LIMIT 1").fetchone():
            raise ValueError('曲库含迁移待处理记录，须使用新版批量迁移入口，不能降为旧格式')
        root = _output_root(live)
        before = _state(live)
        if root and (destination.is_relative_to(root) or source.is_relative_to(root)):
            raise ValueError('迁移包须保存在乐谱库目录以外，例如桌面；数据库也不能位于整理目录内')
        with tempfile.TemporaryDirectory(prefix='.score-transfer-', dir=destination.parent) as scratch:
            stage = Path(scratch)
            database = stage / 'database.sqlite3'
            progress('正在保存识别、校对、编号和配置记录…')
            _backup(live, database)
            with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as saved:
                _check_db(saved, naming)
                if _state(saved) != before:
                    raise ValueError('曲库快照与导出前记录不符')
                counts = _counts(saved)
                if skip_errors:
                    from score_organizer.services.transfer_batch import source_references
                    references = source_references(saved)
                else:
                    references = _source_references(saved)
            # The immutable backup owns the snapshot now. Release the source
            # read transaction while copying; refuse any subsequent DB changes.
            live.rollback()
            file_issues, source_issues = [], []
            if skip_errors:
                from score_organizer.services.transfer_batch import copy_available_tree
                directories, files, inventory, file_issues = copy_available_tree(root, stage / 'files', progress)
            else:
                directories, files, inventory = _copy_tree(root, stage / 'files',
                    lambda message: progress('正在复制乐谱及历史资料：' + message))
                _verify_parts(database, naming, root, files)
            entries = {'files/' + key: value for key, value in files.items()}
            folders = ['files' + ('/' + value if value != '.' else '') for value in directories]
            folders.append('sources')
            (stage / 'sources').mkdir()
            sources, recovered, identities = {}, [], {}
            by_digest = {}
            for name, proof in files.items():
                if Path(name).suffix.lower() == '.pdf':
                    by_digest.setdefault(proof['sha256'], stage / 'files' / name)
            for number, (old, digest) in enumerate(sorted(references.items()), 1):
                original = Path(old)
                relative = f'sources/{number:08d}/' + ('source.pdf' if skip_errors else original.name)
                target = stage / relative
                target.parent.mkdir()
                folders.append(target.parent.relative_to(stage).as_posix())
                sources[old] = relative
                # Missing archived originals may be recovered from an identical,
                # already hashed managed copy. Changed originals still refuse.
                try:
                    if digest is None:
                        raise ValueError('来源路径或哈希记录无效、缺失，或同一路径记录互相冲突')
                    if original.exists() or original.is_symlink():
                        identity = _identity(_regular(original))
                        actual = original
                        proof = _read_hash(actual, identity)
                        if proof['sha256'] != digest:
                            raise ValueError('来源PDF与原校对记录的哈希不符')
                        identities[old] = identity
                    elif digest in by_digest:
                        actual = by_digest[digest]
                        identity = _identity(_regular(actual))
                        recovered.append(old)
                    else:
                        raise ValueError(f'缺少源PDF和可核验副本：{old}')
                except (ValueError, OSError) as exc:
                    if not skip_errors:
                        raise
                    source_issues.append(dict(kind='source', path=old, reason=str(exc)))
                    progress(f'已记录问题来源，继续其他曲目：{number}/{len(references)}')
                    continue
                proof = _read_hash(actual, identity, target)
                if proof['sha256'] != digest:
                    raise ValueError(f'源PDF内容与校对记录不符：{old}')
                entries[relative] = proof
                progress(f'正在打包来源PDF：{number}/{len(references)}')
            entries['database.sqlite3'] = _read_hash(database, _identity(_regular(database)))
            manifest = dict(format=FORMAT, version=1, namingVersion=naming, counts=counts,
                createdAt=datetime.now().astimezone().isoformat(), files=entries,
                directories=sorted(folders), sourcePaths=sources, recoveredSources=recovered)
            batch = None
            if skip_errors:
                from score_organizer.services.transfer_batch import classify, report_text
                batch = classify(database, naming, entries, sources, references)
                report = report_text(batch, file_issues, source_issues)
                report_path = stage / 'migration-report.txt'
                report_path.write_text(report, encoding='utf-8')
                entries['migration-report.txt'] = _read_hash(report_path, _identity(_regular(report_path)))
                manifest.update(version=2, batch=batch, fileIssues=file_issues, sourceIssues=source_issues)
            _validate_manifest(manifest)
            partial = stage / 'package.partial'
            progress('正在压缩整库迁移包，请保持窗口打开…')
            with zipfile.ZipFile(partial, 'x', zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
                archive.writestr('manifest.json', json.dumps(manifest, ensure_ascii=False))
                for folder in manifest['directories']:
                    archive.writestr(folder + '/', b'')
                for name, proof in entries.items():
                    path = stage / name
                    if _read_hash(path, _identity(_regular(path))) != proof:
                        raise ValueError('压缩前的副本已变化')
                    archive.write(path, name)
            progress('正在回读迁移包并逐份校验…')
            inspect_package(partial, progress=progress)
            if root:
                if skip_errors:
                    for relative, identity in inventory.items():
                        if _read_hash(root / relative, identity) != files[relative]:
                            raise ValueError('导出期间已核验的资料发生变化')
                else:
                    _verify_tree(root, directories, files, inventory=inventory)
            for old, identity in identities.items():
                if _read_hash(Path(old), identity)['sha256'] != references[old]:
                    raise ValueError('导出期间源PDF已变化')
            # A second transaction detects changes made after the snapshot began.
            progress('最终校验完成，正在保存完整迁移包…')
            with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=0)) as current:
                current.execute('BEGIN')
                if _state(current) != before:
                    raise ValueError('导出期间曲库记录已变化，请停止整理后重试')
                _new_file(partial, destination)
    result = dict(path=str(destination), **counts, recoveredSources=len(recovered), files=len(entries))
    if batch is not None:
        result.update(batch=batch, report=report)
    return result


def _safe_name(name):
    if not isinstance(name, str) or not name or '\\' in name or '\x00' in name:
        raise ValueError('迁移包路径无效')
    path = PurePosixPath(name)
    windows = PureWindowsPath(name)
    if (path.is_absolute() or windows.drive or windows.root or name != path.as_posix()
            or any(part in ('', '.', '..') or part.endswith((' ', '.'))
                   or any(char in part for char in ':<>"|?*') or any(ord(char) < 32 for char in part) or windows_part_reserved(part)
                   for part in name.split('/'))):
        raise ValueError('迁移包含越界或不兼容路径')
    return name


def windows_part_reserved(part):
    return part.split('.')[0].upper() in {'CON', 'PRN', 'AUX', 'NUL',
        *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))}


def _validate_manifest(value):
    keys = {'format', 'version', 'namingVersion', 'counts', 'createdAt', 'files', 'directories', 'sourcePaths', 'recoveredSources'}
    batch_mode = isinstance(value, dict) and value.get('version') == 2
    if batch_mode:
        keys |= {'batch', 'fileIssues', 'sourceIssues'}
    if (not isinstance(value, dict) or set(value) != keys or value['format'] != FORMAT
            or type(value['version']) is not int or value['version'] not in (1, 2)
            or type(value['namingVersion']) is not int or value['namingVersion'] not in (1, 2)
            or not isinstance(value['files'], dict) or not isinstance(value['directories'], list)
            or len(value['files']) + len(value['directories']) > MAX_FILES
            or not isinstance(value['sourcePaths'], dict) or not isinstance(value['recoveredSources'], list)
            or not isinstance(value['counts'], dict) or set(value['counts']) != {'songs', 'archivedScores', 'draftItems'}
            or any(type(n) is not int or n < 0 for n in value['counts'].values())):
        raise ValueError('不是受支持的完整曲库迁移包')
    names, total = set(), 0
    for name in [*value['files'], *value['directories']]:
        _safe_name(name)
        if name.casefold() in names:
            raise ValueError('迁移包含重复文件或目录')
        names.add(name.casefold())
    for name, proof in value['files'].items():
        if (not isinstance(proof, dict) or set(proof) != {'bytes', 'sha256'}
                or type(proof['bytes']) is not int or proof['bytes'] < 0
                or not isinstance(proof['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', proof['sha256'])):
            raise ValueError('迁移包缺少有效大小或哈希')
        total += proof['bytes']
        if name not in (('database.sqlite3', 'migration-report.txt') if batch_mode else ('database.sqlite3',)) and not name.startswith(('files/', 'sources/')):
            raise ValueError('迁移包文件超出资料范围')
    if (total > MAX_TOTAL or 'database.sqlite3' not in value['files']
            or not {'files', 'sources'}.issubset(value['directories'])):
        raise ValueError('迁移包不完整或过大')
    deferred = set()
    if batch_mode:
        batch_keys = {'deferredSources', 'pendingSongs', 'pendingItems', 'usableSongs', 'skippedSongs', 'usableScores', 'skippedDraftGroups', 'problems'}
        batch = value['batch']
        if (not isinstance(batch, dict) or set(batch) != batch_keys
                or any(not isinstance(value[key], list) for key in ('fileIssues', 'sourceIssues'))
                or not isinstance(batch['deferredSources'], list)
                or any(not isinstance(item, str) for item in batch['deferredSources'])
                or not isinstance(batch['problems'], list)
                or any(type(batch[key]) is not int or batch[key] < 0 for key in ('usableSongs', 'skippedSongs', 'usableScores', 'skippedDraftGroups'))
                or any(not isinstance(batch[key], list) or any(type(item) is not int or item < 1 for item in batch[key])
                       or len(set(batch[key])) != len(batch[key]) for key in ('pendingSongs', 'pendingItems'))
                or 'migration-report.txt' not in value['files']):
            raise ValueError('批量迁移报告或待处理清单无效')
        deferred = set(batch['deferredSources'])
        if not deferred.issubset(value['sourcePaths']):
            raise ValueError('待处理来源不在原始索引中')
    for old, target in value['sourcePaths'].items():
        if (not isinstance(old, str) or (not Path(old).is_absolute() and old not in deferred)
                or not isinstance(target, str) or not target.startswith('sources/')
                or (target not in value['files'] and old not in deferred)):
            raise ValueError('迁移包来源索引无效')
        _safe_name(target)
    if len(set(value['sourcePaths'].values())) != len(value['sourcePaths']):
        raise ValueError('迁移包把不同来源合并为同一路径')
    file_names = {key.casefold() for key in value['files']}
    for name in names:
        if any(parent.as_posix().casefold() in file_names
               for parent in PurePosixPath(name).parents if parent.as_posix() != '.'):
            raise ValueError('迁移包文件与目录冲突')
    return total


def _open_package(path):
    _regular(path)
    archive = zipfile.ZipFile(path)
    try:
        listing = archive.infolist()
        if len(listing) > MAX_FILES + 1:
            raise ValueError('迁移包文件数量过大')
        names = set()
        for entry in listing:
            name = entry.filename[:-1] if entry.is_dir() else entry.filename
            _safe_name(name)
            mode = entry.external_attr >> 16
            if (name.casefold() in names or entry.flag_bits & 1
                    or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise ValueError('迁移包含重复、加密或链接文件')
            names.add(name.casefold())
        info = archive.getinfo('manifest.json')
        if info.file_size > MAX_MANIFEST:
            raise ValueError('迁移清单过大')
        value = _json(archive.read(info).decode('utf-8'))
        _validate_manifest(value)
        expected = {'manifest.json', *value['files'], *(name + '/' for name in value['directories'])}
        if set(archive.namelist()) != expected:
            raise ValueError('迁移包有缺失或未登记文件')
        for name, proof in value['files'].items():
            if archive.getinfo(name).file_size != proof['bytes']:
                raise ValueError('迁移包文件大小与清单不符')
        return archive, value
    except BaseException:
        archive.close()
        raise


def _read_entry(archive, name, proof, target=None):
    digest, size = hashlib.sha256(), 0
    with archive.open(name) as source:
        out = target.open('xb') if target else None
        try:
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                size += len(chunk)
                if size > proof['bytes']:
                    raise ValueError('迁移包解压大小超过清单')
                digest.update(chunk)
                if out:
                    out.write(chunk)
            if out:
                out.flush()
                os.fsync(out.fileno())
        finally:
            if out:
                out.close()
    if size != proof['bytes'] or digest.hexdigest() != proof['sha256']:
        raise ValueError(f'迁移包文件校验失败：{name}')


def _verify_snapshot(database, manifest):
    with closing(sqlite3.connect(database.as_uri() + '?mode=ro', uri=True)) as saved:
        _check_db(saved, manifest['namingVersion'])
        if _counts(saved) != manifest['counts']:
            raise ValueError('迁移包曲目和分谱数量不符')
        if manifest['version'] == 1 and saved.execute("SELECT 1 FROM song WHERE status='migration_pending' UNION ALL SELECT 1 FROM import_item WHERE status='migration_pending' LIMIT 1").fetchone():
            raise ValueError('旧格式包含迁移待处理状态，不能以旧版身份恢复')
        if manifest['version'] == 2:
            from score_organizer.services.transfer_batch import source_references
            refs = source_references(saved)
        else:
            refs = _source_references(saved)
        if set(refs) != set(manifest['sourcePaths']):
            raise ValueError('迁移包未覆盖全部来源PDF')
        for old, digest in refs.items():
            deferred = manifest.get('batch', {}).get('deferredSources', [])
            if old not in deferred and manifest['files'][manifest['sourcePaths'][old]]['sha256'] != digest:
                raise ValueError('来源PDF与原识别记录不一致')
    if manifest['version'] == 2:
        from score_organizer.services.transfer_batch import classify
        actual = classify(database, manifest['namingVersion'], manifest['files'], manifest['sourcePaths'], refs)
        for key in ('deferredSources', 'pendingSongs', 'pendingItems', 'usableSongs', 'skippedSongs', 'usableScores', 'skippedDraftGroups'):
            if actual[key] != manifest['batch'][key]:
                raise ValueError('待处理清单与原始曲库或文件校验不一致')


def inspect_package(path, *, progress=lambda _message: None):
    path = Path(path).absolute()
    identity = _identity(_regular(path))
    archive, value = _open_package(path)
    with archive, tempfile.TemporaryDirectory(prefix='score-transfer-check-') as temporary:
        database = Path(temporary) / 'database.sqlite3'
        for number, (name, proof) in enumerate(value['files'].items(), 1):
            _read_entry(archive, name, proof, database if name == 'database.sqlite3' else None)
            progress(f'正在校验迁移包：{number}/{len(value["files"])}')
        _verify_snapshot(database, value)
    if _identity(_regular(path)) != identity:
        raise ValueError('核验期间迁移包已变化')
    return value


def restore_library(package, destination, *, progress=lambda _message: None):
    package, destination = Path(package).absolute(), Path(destination).absolute()
    _no_links(destination)
    if destination.exists() or not destination.parent.is_dir() or package.is_relative_to(destination):
        raise ValueError('恢复必须使用全新目录，不能覆盖现有曲库或迁移包')
    from score_organizer.storage.expanded_database import ExpandedDatabase, MARKER
    identity = _identity(_regular(package))
    archive, value = _open_package(package)
    with archive:
        total = _validate_manifest(value)
        if shutil.disk_usage(destination.parent).free < total + value['files']['database.sqlite3']['bytes'] + 16 * 1024 * 1024:
            raise ValueError('目标磁盘剩余空间不足')
        destination.mkdir()
        try:
            for folder in sorted(value['directories'], key=lambda name: len(PurePosixPath(name).parts)):
                (destination / folder).mkdir()
            for number, (name, proof) in enumerate(value['files'].items(), 1):
                target = destination / ('source-snapshot.sqlite3' if name == 'database.sqlite3' else name)
                target.parent.mkdir(parents=True, exist_ok=True)
                _read_entry(archive, name, proof, target)
                progress(f'正在恢复并校验：{number}/{len(value["files"])}')
            if _identity(_regular(package)) != identity:
                raise ValueError('恢复期间迁移包已变化')
            snapshot = destination / 'source-snapshot.sqlite3'
            _verify_snapshot(snapshot, value)
            database = destination / 'library.sqlite3'
            with closing(sqlite3.connect(snapshot.as_uri() + '?mode=ro', uri=True)) as saved:
                _backup(saved, database)
            mapping = {old: dict(path=str(destination / relative), **value['files'].get(relative, {}))
                       for old, relative in value['sourcePaths'].items()}
            _rebase_database(database, mapping, destination / 'files', progress,
                             numbering_version=value['namingVersion'])
            if value['version'] == 2:
                from score_organizer.services.transfer_batch import apply_pending
                apply_pending(database, value['namingVersion'], value['batch'])
            _verify_parts(database, value['namingVersion'], destination / 'files',
                {name[6:]: proof for name, proof in value['files'].items() if name.startswith('files/')})
            progress('正在复核恢复后的全部PDF和独立目录…')
            for name, proof in value['files'].items():
                target = destination / ('source-snapshot.sqlite3' if name == 'database.sqlite3' else name)
                if target.stat().st_nlink != 1 or _read_hash(target, _identity(_regular(target))) != proof:
                    raise ValueError('恢复副本在完成前发生变化')
            progress('完整副本已核验，正在准备切换曲库…')
            runtime_id = str(uuid.uuid4())
            expanded = value['namingVersion'] == 2
            with closing(sqlite3.connect(database)) as candidate:
                if expanded:
                    register_format_one_writer(candidate)
                candidate.execute('BEGIN IMMEDIATE')
                runtime_before = _state(candidate)
                settings_before = dict(candidate.execute('SELECT key,value FROM settings'))
                runtime_key = 'expanded_runtime_id' if expanded else 'portable_runtime_id'
                candidate.execute('INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value',
                    (runtime_key, runtime_id))
                runtime_after = _state(candidate)
                settings_before[runtime_key] = runtime_id
                if (runtime_after['schema'] != runtime_before['schema'] or runtime_after['version'] != runtime_before['version']
                        or {key: value for key, value in runtime_after['tables'].items() if key != 'settings'}
                        != {key: value for key, value in runtime_before['tables'].items() if key != 'settings'}
                        or dict(candidate.execute('SELECT key,value FROM settings')) != settings_before):
                    raise ValueError('运行身份写入引起其他记录变化，不能宣布迁移完成')
                _integrity(candidate)
                candidate.commit()
            if expanded:
                marker = dict(format='score-organizer-active-runtime', version=value['version'], runtimeId=runtime_id,
                              database=str(database), output=str(destination / 'files'))
                _write_json_exclusive(destination / MARKER, marker)
                result = ExpandedDatabase(database)
            else:
                from score_organizer.storage.portable_database import PortableDatabase
                marker = dict(format='score-organizer-portable-runtime-v' + str(value['version']), runtimeId=runtime_id,
                              database=str(database), output=str(destination / 'files'))
                _write_json_exclusive(destination / 'portable-runtime.json', marker)
                result = PortableDatabase(database)
            result.initialize()
            _write_json_exclusive(destination / 'migration-completed.json',
                dict(format=FORMAT, counts=value['counts'], namingVersion=value['namingVersion'],
                     originalNumberingPreserved=True, previousResetShortcutCleared=True,
                     sourcePackage=str(package), database=str(database), batch=value.get('batch')))
            return result
        except BaseException as error:
            # No current-library pointer has been touched. Incomplete directories
            # remain available for diagnosis and can never be opened as finished.
            for name in (MARKER, 'portable-runtime.json'):
                (destination / name).unlink(missing_ok=True)
            _write_json_exclusive(destination / 'migration-failed.json',
                                  dict(errorType=type(error).__name__, message=str(error)))
            raise
