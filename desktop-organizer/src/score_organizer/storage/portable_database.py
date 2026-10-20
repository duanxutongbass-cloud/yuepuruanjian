"""An existing format-0 library restored to its own independent runtime."""
import json
import sqlite3
import uuid

from score_organizer.storage.database import Database
from score_organizer.storage.number_upgrade import _no_links
from score_organizer.services.reset_service import _unique_manifest_fields


class PortableDatabase(Database):
    def _proof(self):
        path = self.path
        marker = path.parent / 'portable-runtime.json'
        for entry in (path, marker, path.parent / 'files', path.parent / 'sources'):
            _no_links(entry)
        if (not path.is_absolute() or path.name != 'library.sqlite3' or not path.is_file()
                or path.stat().st_nlink != 1 or not marker.is_file() or marker.stat().st_nlink != 1 or marker.stat().st_size > 4096):
            raise ValueError('迁移库缺失或位置已变化；不会新建空库')
        proof = json.loads(marker.read_text('utf-8'), object_pairs_hook=_unique_manifest_fields)
        if (not isinstance(proof, dict) or set(proof) != {'format', 'runtimeId', 'database', 'output'}
                or proof['format'] not in ('score-organizer-portable-runtime-v1', 'score-organizer-portable-runtime-v2')
                or proof['database'] != str(path) or proof['output'] != str(path.parent / 'files')
                or str(uuid.UUID(proof['runtimeId'])) != proof['runtimeId']):
            raise ValueError('迁移库身份或路径不匹配')
        if not (path.parent / 'files').is_dir() or not (path.parent / 'sources').is_dir():
            raise ValueError('迁移库的PDF目录缺失')
        return proof

    def _require_supported(self, connection):
        super()._require_supported(connection)
        proof = self._proof()
        settings = dict(connection.execute('SELECT key,value FROM settings'))
        if (settings.get('portable_runtime_id') != proof['runtimeId']
                or settings.get('output_root') != proof['output']):
            raise ValueError('迁移库目录或身份已变化')

    def connect(self):
        self._proof()
        connection = sqlite3.connect(self.path.as_uri() + '?mode=rw', uri=True)
        try:
            connection.row_factory = sqlite3.Row
            connection.execute('PRAGMA foreign_keys=ON')
            self._require_supported(connection)
            return connection
        except BaseException:
            connection.close()
            raise

    def initialize(self):
        with self.session():
            pass
