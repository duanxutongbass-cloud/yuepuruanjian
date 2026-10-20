from __future__ import annotations

import sys
import os
import json
import sqlite3
from pathlib import Path

from score_organizer.storage.database import Database, UnsupportedDatabaseVersion


def default_db_path() -> Path:
    companion_data = os.environ.get("SCORE_ORGANIZER_DATA_DIR")
    if companion_data:
        return Path(companion_data) / "score_organizer.sqlite3"
    return Path.home() / "曲谱库整理工具" / "score_organizer.sqlite3"


def expanded_profile():
    return Path(os.environ.get('SCORE_ORGANIZER_EXPANDED_PROFILE',str(Path.home()/'曲谱库整理工具扩位')))


def remember_expanded(database):
    from score_organizer.storage.expanded_database import ExpandedDatabase
    from score_organizer.storage.number_upgrade import _no_links
    import uuid
    ExpandedDatabase(database).initialize()
    profile=expanded_profile();_no_links(profile);profile.mkdir(parents=True,exist_ok=True)
    target=profile/'current-library.json';_no_links(target)
    pending=profile/('current-'+str(uuid.uuid4())+'.pending')
    try:
        with pending.open('x',encoding='utf-8') as out:
            json.dump({'format':'score-organizer-current-v2','database':str(database)},out,ensure_ascii=False)
            out.flush();os.fsync(out.fileno())
        os.replace(pending,target)
    finally:pending.unlink(missing_ok=True)


def remember_portable(database):
    from score_organizer.storage.portable_database import PortableDatabase
    from score_organizer.storage.number_upgrade import _no_links
    import uuid
    portable = PortableDatabase(Path(database))
    portable.initialize()
    pointer_format = ('score-organizer-current-portable-v2' if portable._proof()['format'].endswith('-v2')
                      else 'score-organizer-current-portable-v1')
    profile = expanded_profile()
    _no_links(profile)
    profile.mkdir(parents=True, exist_ok=True)
    target = profile / 'current-library.json'
    _no_links(target)
    pending = profile / ('current-' + str(uuid.uuid4()) + '.pending')
    try:
        with pending.open('x', encoding='utf-8') as out:
            json.dump({'format': pointer_format, 'database': str(database)}, out, ensure_ascii=False)
            out.flush()
            os.fsync(out.fileno())
        os.replace(pending, target)
    finally:
        pending.unlink(missing_ok=True)


def startup_database(explicit=None, legacy=False):
    from score_organizer.storage.expanded_database import ExpandedDatabase
    from score_organizer.services.reset_service import _unique_manifest_fields
    from score_organizer.storage.number_upgrade import _no_links
    pointer=expanded_profile()/'current-library.json'
    if explicit is not None:return ExpandedDatabase(Path(explicit).absolute())
    if not legacy and pointer.exists():
        _no_links(pointer)
        if pointer.stat().st_size > 4096:raise ValueError('扩位启动记录过大')
        proof=json.loads(pointer.read_text('utf-8'),object_pairs_hook=_unique_manifest_fields)
        if not isinstance(proof,dict) or set(proof) != {'format','database'} or proof['format'] not in ('score-organizer-current-v2', 'score-organizer-current-portable-v1', 'score-organizer-current-portable-v2'):
            raise ValueError('扩位启动记录无效，不会回退到旧库')
        if proof['format'] in ('score-organizer-current-portable-v1', 'score-organizer-current-portable-v2'):
            from score_organizer.storage.portable_database import PortableDatabase
            return PortableDatabase(Path(proof['database']))
        return ExpandedDatabase(Path(proof['database']))
    return Database(default_db_path())


def main(argv=None) -> int:
    try:
        from PySide6.QtWidgets import QApplication, QMessageBox
    except ImportError as exc:
        raise SystemExit(f"无法加载 PySide6 界面依赖：{exc}。源码运行请检查依赖；EXE请保留完整程序目录。") from exc

    import argparse
    parser=argparse.ArgumentParser(description='曲谱库整理器：显式打开已激活的扩位曲库')
    parser.add_argument('--expanded-library',type=Path)
    parser.add_argument('--legacy',action='store_true',help='明确打开保留的旧曲库')
    args=parser.parse_args([] if argv is None else argv)
    app = QApplication.instance() or QApplication(sys.argv)
    lease=None
    try:
        db = startup_database(args.expanded_library,args.legacy)
        db.initialize()
        if db._numbering_version == 2 or type(db).__name__ == 'PortableDatabase':
            from score_organizer.storage.expanded_database import RuntimeLock
            lease=RuntimeLock(db)
    except (ValueError,OSError,sqlite3.Error) as exc:
        QMessageBox.critical(None, "乐谱库版本不兼容", str(exc))
        return 2

    from score_organizer.ui.main_window import MainWindow

    window = MainWindow(db)
    window.show()
    try:return app.exec()
    finally:
        if lease is not None:lease.close()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

