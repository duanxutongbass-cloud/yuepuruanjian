"""Writable runtime for an explicitly activated, independently migrated library."""
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import uuid

from score_organizer.storage.database import Database
from score_organizer.storage.number_upgrade import _no_links, _write_json_exclusive
from score_organizer.storage.writer_guard import register_format_one_writer, require_format_one_guards
from score_organizer.services.reset_service import _unique_manifest_fields

MARKER = 'active-runtime.json'


def activation_proof(database):
    database = Path(database).absolute()
    root = database.parent
    for path in (database, root/MARKER, root/'files', root/'sources'):
        _no_links(path)
    marker = root/MARKER
    if (database.name != 'library.sqlite3' or not database.is_file() or database.stat().st_nlink != 1
            or not marker.is_file() or marker.stat().st_nlink != 1 or marker.stat().st_size > 4096):
        raise ValueError('扩位运行库缺失或未明确激活；不会新建或回退旧库')
    proof=json.loads(marker.read_text('utf-8'),object_pairs_hook=_unique_manifest_fields)
    if (not isinstance(proof,dict) or set(proof) != {'format','version','runtimeId','database','output'}
            or proof['format'] != 'score-organizer-active-runtime' or type(proof['version']) is not int
            or proof['version'] != 1 or proof['database'] != str(database)
            or proof['output'] != str(root/'files') or str(uuid.UUID(proof['runtimeId'])) != proof['runtimeId']):
        raise ValueError('扩位激活身份或路径不匹配')
    return proof


class ExpandedDatabase(Database):
    _numbering_version = 2

    def _require_supported(self, connection):
        require_format_one_guards(connection)
        proof=activation_proof(self.path)
        settings=dict(connection.execute('SELECT key,value FROM settings'))
        if settings.get('expanded_runtime_id') != proof['runtimeId'] or settings.get('output_root') != proof['output']:
            raise ValueError('扩位运行库身份或输出路径已变化')

    def connect(self):
        activation_proof(self.path)
        connection=sqlite3.connect(self.path.as_uri()+'?mode=rw',uri=True)
        try:
            connection.row_factory=sqlite3.Row
            register_format_one_writer(connection)
            connection.execute('PRAGMA foreign_keys=ON')
            connection.execute('BEGIN')
            self._require_supported(connection)
            connection.rollback()
            return connection
        except BaseException:
            connection.close();raise

    def initialize(self):
        # An active library is already initialized. Never run legacy DDL.
        with self.session():pass


def activate_candidate(manifest):
    require_old_organizers_closed()
    from score_organizer.services.runtime_candidate import inspect_runtime_candidate
    candidate=inspect_runtime_candidate(manifest)
    proof=dict(format='score-organizer-active-runtime',version=1,runtimeId=str(uuid.uuid4()),
               database=str(candidate.database),output=str(candidate.output))
    with closing(sqlite3.connect(candidate.database.as_uri()+'?mode=rw',uri=True)) as connection:
        register_format_one_writer(connection)
        connection.execute('BEGIN IMMEDIATE')
        require_format_one_guards(connection)
        if connection.execute("SELECT 1 FROM settings WHERE key='expanded_runtime_id'").fetchone():
            raise ValueError('候选已激活，不能重复激活或重置身份')
        connection.execute("INSERT INTO settings VALUES('expanded_runtime_id',?)",(proof['runtimeId'],))
        _write_json_exclusive(candidate.database.parent/MARKER,proof)
        connection.commit()
    database=ExpandedDatabase(candidate.database)
    database.initialize()
    return database


def require_old_organizers_closed():
    if os.name != 'nt':return
    import csv
    import subprocess
    result=subprocess.run(['tasklist.exe','/FO','CSV','/NH'],capture_output=True,text=True,encoding='mbcs',
                          creationflags=subprocess.CREATE_NO_WINDOW,check=True)
    for row in csv.reader(result.stdout.splitlines()):
        if len(row)>1 and (row[0].casefold().startswith('scoreorganizer') or row[0]=='曲谱库重奏乐谱整理工具.exe') and row[0].casefold().endswith('.exe'):
            if row[1] != str(os.getpid()):
                raise ValueError('请先关闭其他版本的乐谱整理器，再激活或打开扩位曲库')


class RuntimeLock:
    def __init__(self, database):
        require_old_organizers_closed()
        path=database.path.parent/'.runtime.lock'
        _no_links(path)
        self.stream=path.open('a+b')
        try:
            self.stream.seek(0);self.stream.write(b'1');self.stream.flush();self.stream.seek(0)
            if os.name == 'nt':
                import msvcrt
                msvcrt.locking(self.stream.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(self.stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        except BaseException:
            self.stream.close();raise ValueError('该扩位曲库已被另一个整理器打开')

    def close(self):
        self.stream.close()
