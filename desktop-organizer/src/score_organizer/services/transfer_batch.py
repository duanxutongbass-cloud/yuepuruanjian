"""Per-song migration classification; incomplete records remain reserved."""
from contextlib import closing
from copy import deepcopy
import json
import re
from pathlib import Path
import sqlite3

from score_organizer.services.library_relocation import _digest, _records
from score_organizer.services.number_upgrade_service import _identity, _regular, _read_hash
from score_organizer.storage.number_upgrade import _no_links, _state, _integrity
from score_organizer.storage.writer_guard import register_format_one_writer

PENDING = 'migration_pending'


def source_references(connection):
    """Invalid per-file metadata is deferred, never guessed or auto-corrected."""
    references = {}
    for table in ('score', 'import_item'):
        for old, digest in connection.execute(f'SELECT source_path,source_sha256 FROM "{table}"'):
            if not isinstance(old, str):
                raise ValueError('曲库来源路径字段类型损坏，无法安全关联原记录')
            valid = (Path(old).is_absolute() and Path(old).suffix.lower() == '.pdf'
                     and isinstance(digest, str) and re.fullmatch('[0-9a-f]{64}', digest))
            value = digest if valid else None
            if old in references and references[old] != value:
                value = None
            references[old] = value
    return references


def copy_available_tree(root, destination, progress):
    """Copy readable files, collect every local read error, never follow links.

    Destination write errors and user cancellation are global and must stop.
    """
    destination.mkdir()
    directories, files, identities, issues = ['.'], {}, {}, []
    if root is None:
        return directories, files, identities, issues
    pending = [root]
    while pending:
        folder = pending.pop()
        try:
            _no_links(folder)
            children = sorted(folder.iterdir())
        except (OSError, ValueError) as exc:
            issues.append(dict(path=str(folder), reason=str(exc), kind='directory'))
            continue
        for path in children:
            relative = path.relative_to(root).as_posix()
            is_directory = False
            try:
                _no_links(path)
                if path.is_dir():
                    is_directory = True
                else:
                    identity = _identity(_regular(path))
                    # A destination write error must not be mislabeled as a bad source.
                    proof = _read_hash(path, identity)
            except (OSError, ValueError) as exc:
                issues.append(dict(path=str(path), reason=str(exc), kind='file'))
                continue
            if is_directory:
                (destination / relative).mkdir()
                directories.append(relative)
                pending.append(path)
                continue
            progress('正在复制可用资料：' + relative)
            copied = destination / relative
            # Source files were just read; the copy still checks identity/hash.
            # A late source change is an inconsistent snapshot, not a static bad
            # song, and remains a global stop.
            if _read_hash(path, identity, copied) != proof:
                raise ValueError('复制期间文件变化，请停止编辑后重试')
            files[relative], identities[relative] = proof, identity
    return sorted(directories), files, identities, issues


def classify(database, naming, files, source_paths, references):
    """Recompute affected whole songs from original DB and hashed ZIP contents."""
    from score_organizer.services.library_transfer import _SnapshotDatabase
    from score_organizer.services.archive_service import ArchiveService
    db = _SnapshotDatabase(database, naming)
    with db.session() as connection:
        songs = {int(row['id']): dict(row) for row in connection.execute('SELECT * FROM song')}
        scores = [dict(row) for row in connection.execute('SELECT * FROM score')]
        items = [dict(row) for row in connection.execute('SELECT * FROM import_item')]
        output = connection.execute("SELECT value FROM settings WHERE key='output_root'").fetchone()
    deferred = {old for old, digest in references.items()
                if not digest or files.get(source_paths[old], {}).get('sha256') != digest}
    pending_songs = {int(row['id']) for row in songs.values() if row['status'] == PENDING}
    pending_songs.update(int(row['song_id']) for row in scores if row['source_path'] in deferred)
    problems = []
    root = Path(output[0]) if output and output[0] else None
    archive = ArchiveService(db, root) if root else None
    if archive:
        archive._numbering_version = naming
    for score in db.list_archived_scores():
        if score['source_path'] in deferred:
            problems.append(dict(kind='song', songId=int(score['song_id']), title=score['display_title'],
                sequence=score['seq'], instrument=score['instrument_name'], path=score['source_path'],
                reason='来源分谱缺失、不可读或哈希与校对记录不符'))
        try:
            if archive is None:
                raise ValueError('缺少原整理目录')
            for path in archive.score_paths(score):
                relative = path.absolute().relative_to(root).as_posix()
                if files.get('files/' + relative, {}).get('sha256') != score['source_sha256']:
                    raise ValueError(f'库内副本缺失、不可读或校验不符：{path}')
        except (ValueError, OSError) as exc:
            pending_songs.add(int(score['song_id']))
            problems.append(dict(kind='song', songId=int(score['song_id']), title=score['display_title'],
                sequence=score['seq'], instrument=score['instrument_name'], path=score['source_path'], reason=str(exc)))
    groups = {}
    pending_groups, pending_items = set(), set()
    for item in items:
        try:
            fields = json.loads(item['parsed_fields'] or '{}')
            key = (fields.get('category_code'), fields.get('title'))
            if not all(isinstance(value, str) and value for value in key):
                key = ('item', str(item['id']))
        except (ValueError, AttributeError):
            key = ('item', str(item['id']))
        groups[int(item['id'])] = key
        if item['source_path'] in deferred or item['status'] == PENDING:
            pending_groups.add(key)
    pending_items = {item_id for item_id, group in groups.items() if group in pending_groups}
    for song_id in sorted(pending_songs):
        song = songs[song_id]
        if not any(problem.get('songId') == song_id for problem in problems):
            problems.append(dict(kind='song', songId=song_id, title=song['display_title'], sequence=song['seq'],
                                 instrument='', path='', reason='来源分谱缺失/校验不符，或此前已列为迁移待处理'))
    for item in items:
        if int(item['id']) in pending_items:
            problems.append(dict(kind='draft', itemId=int(item['id']), title=groups[int(item['id'])][1],
                                 sequence='', instrument='', path=item['source_path'],
                                 reason='同曲至少一份来源文件不可用，整组草稿保留为迁移待处理'))
    active_songs = {int(row['id']) for row in songs.values() if row['status'] == 'archived'}
    usable = active_songs - pending_songs
    return dict(deferredSources=sorted(deferred), pendingSongs=sorted(pending_songs), pendingItems=sorted(pending_items),
                usableSongs=len(usable), skippedSongs=len(pending_songs),
                usableScores=sum(int(row['song_id']) in usable for row in scores),
                skippedDraftGroups=len(pending_groups), problems=problems)


def apply_pending(database, naming, batch):
    """Only change statuses, preserving all rows, IDs, numbers and decisions."""
    with closing(sqlite3.connect(database)) as connection:
        if naming == 2:
            register_format_one_writer(connection)
        connection.execute('PRAGMA foreign_keys=ON')
        connection.execute('BEGIN IMMEDIATE')
        before = _state(connection)
        expected = deepcopy(before)
        for table, ids in [('song', batch['pendingSongs']), ('import_item', batch['pendingItems'])]:
            id_set = set(ids)
            columns, values = _records(connection, table)
            for row in values:
                if row[columns.index('id')] in id_set:
                    row[columns.index('status')] = PENDING
            expected['tables'][table] = _digest(values)
            for identity in ids:
                connection.execute(f'UPDATE "{table}" SET status=? WHERE id=?', (PENDING, identity))
        if _state(connection) != expected:
            raise ValueError('标记待处理曲目时出现其他记录变化，恢复已停止')
        _integrity(connection)
        connection.commit()


def report_text(batch, file_issues, source_issues):
    lines = ['曲库批量迁移报告',
        f'成功迁移可用曲目：{batch["usableSongs"]}首 / {batch["usableScores"]}份正式分谱',
        f'跳过待处理曲目：{batch["skippedSongs"]}首；待处理草稿组：{batch["skippedDraftGroups"]}组',
        '问题曲目未作为可用曲目入库；编号、校对记录及原始数据库快照保留，未回收占号。',
        '问题如下（同曲按整体跳过，不阻断其他完整曲目）：', '']
    problems = [*batch['problems'], *source_issues, *file_issues]
    if not problems:
        lines.append('无跳过或异常。')
    for number, problem in enumerate(problems, 1):
        label = ' '.join(str(problem.get(key, '')) for key in ('sequence', 'title', 'instrument')).strip()
        lines.extend([f'{number}. {label or "附属资料/来源文件"}',
                      '   路径：' + problem.get('path', ''), '   原因：' + problem['reason']])
    return '\n'.join(lines) + '\n'
