"""Export enabled naming dictionaries from a desktop organizer DB without changing it."""
import json
import re
import sqlite3
from pathlib import Path

def export_rules(database: Path, output: Path):
    database = database.resolve(strict=True)
    result = {"format": "score-organizer-naming", "version": 1, "sequenceDigits": 3}
    with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True) as connection:
        connection.execute('PRAGMA query_only=ON')
        for table, field in [('instrument', 'instruments'), ('category', 'categories')]:
            rows = connection.execute(f'SELECT code,name FROM {table} WHERE active=1 ORDER BY code').fetchall()
            if not 1 <= len(rows) <= 100: raise ValueError('字典不能为空或超过100项')
            if any(not re.fullmatch(r'[0-9]{2}', code) or not name.strip() or len(name) > 100 for code, name in rows):
                raise ValueError('字典编号或名称无效')
            result[field] = dict(rows)
            if len(result[field]) != len(rows) or len({name.strip() for _, name in rows}) != len(rows):
                raise ValueError('字典编号或名称重复')
    payload = json.dumps(result, ensure_ascii=False, indent=2).encode('utf-8')
    if len(payload) > 64 * 1024: raise ValueError('字典文件过大')
    with output.open('xb') as destination: destination.write(payload)
    return result

