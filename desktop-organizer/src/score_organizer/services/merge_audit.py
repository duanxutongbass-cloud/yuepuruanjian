"""Read-only consumer for current per-event merge audit files.

Absolute historical paths are provenance. Active duplicate copies are located
only by their verified flat name below the current package directory.
"""
import json
from pathlib import Path
import re


def _unique(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError('合并记录含重复JSON字段')
        value[key] = item
    return value


def _positive_id(value):
    if type(value) is not int or value < 1:
        raise ValueError('合并记录ID无效')
    return value


def _text(value):
    if not isinstance(value, str) or not value.strip() or '\0' in value:
        raise ValueError('合并记录文字或路径无效')
    return value


def _digest(value):
    if not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None:
        raise ValueError('合并记录PDF哈希无效')
    return value


def read_current_merge_audits(root: Path) -> dict:
    # Local imports avoid a module initialization cycle with the archive code.
    from score_organizer.domain.numbering import normalize_seq
    from score_organizer.services.number_upgrade_service import _inventory, _read_hash, _regular
    from score_organizer.storage.number_upgrade import _no_links

    root = Path(root).absolute()
    _no_links(root)
    if not root.exists():
        return dict(entries=[], records=[], checkedFiles={})
    initial = _inventory(root)
    entries, records, checked, used = [], [], {}, set()

    def check(path):
        relative = path.relative_to(root).as_posix()
        item = initial.get(relative)
        if item is None or item[0] != 'file' or _regular(path).st_nlink != 1:
            raise ValueError('合并记录或PDF缺失，或不是独立文件')
        proof = _read_hash(path, item[1])
        if relative in checked and checked[relative] != proof:
            raise ValueError('读取期间合并记录或PDF已变化')
        checked[relative] = proof
        return proof

    for path in sorted(root.rglob('*.json')):
        if not path.name.startswith('合并对比记录_'):
            continue
        name = re.fullmatch(r'合并对比记录_([0-9a-f]{32})\.json', path.name)
        if name is None or len(path.relative_to(root).parts) != 2 or path.parent.name.startswith('.'):
            raise ValueError('合并记录文件名或目录层级无效')
        proof = check(path)
        value = json.loads(path.read_text('utf-8'), object_pairs_hook=_unique)
        if check(path) != proof or not isinstance(value, dict):
            raise ValueError('合并记录读取失败或结构无效')
        required = {'song_id', 'source_song_id', 'seq', 'source_seq', 'category', 'title',
                    'version_no', 'event_id', 'duplicate_root', 'files', 'selected_score_ids', 'original_records'}
        if not required.issubset(value) or value['event_id'] != name[1]:
            raise ValueError('合并记录字段缺失或事件ID不一致')
        target_id, source_id = _positive_id(value['song_id']), _positive_id(value['source_song_id'])
        if target_id == source_id:
            raise ValueError('合并记录的两个曲目ID不能相同')
        _positive_id(value['version_no'])
        for key in ('seq', 'source_seq'):
            if not isinstance(value[key], str) or normalize_seq(value[key], naming_version=2) != value[key]:
                raise ValueError('合并记录的原序号无效')
        if value['seq'] == value['source_seq'] or re.fullmatch('[0-9]{2}', _text(value['category'])) is None:
            raise ValueError('合并记录编号或分类无效')
        title = _text(value['title'])
        historical_root = Path(_text(value['duplicate_root']))
        if not historical_root.is_absolute() or '..' in historical_root.parts or historical_root.name != path.parent.name:
            raise ValueError('合并记录重复目录坐标无效')
        if any(not isinstance(value[key], list) for key in ('files', 'original_records', 'selected_score_ids')):
            raise ValueError('合并记录分谱清单无效')
        originals = {}
        for original in value['original_records']:
            if not isinstance(original, dict) or not {'id', 'song_id', 'source_path', 'source_sha256', 'storage_layout'}.issubset(original):
                raise ValueError('合并记录原始分谱不完整')
            score_id = _positive_id(original['id'])
            if score_id in originals or _positive_id(original['song_id']) not in (target_id, source_id):
                raise ValueError('合并记录原始分谱重复或归属无效')
            _text(original['source_path'])  # Provenance only: never open this path.
            _digest(original['source_sha256'])
            if original['storage_layout'] not in ('dual', 'instrument_only'):
                raise ValueError('合并记录原始分谱布局无效')
            originals[score_id] = original
        files = {}
        for file in value['files']:
            required_file = {'score_id', 'instrument_code', 'sha256', 'old_instrument', 'new_instrument', 'old_song', 'new_song'}
            if not isinstance(file, dict) or not required_file.issubset(file):
                raise ValueError('合并记录文件清单不完整')
            score_id = _positive_id(file['score_id'])
            if score_id in files or score_id not in originals:
                raise ValueError('合并记录文件ID重复或缺少原始记录')
            if re.fullmatch('[0-9]{2}', _text(file['instrument_code'])) is None or \
                    _digest(file['sha256']) != originals[score_id]['source_sha256']:
                raise ValueError('合并记录乐器或哈希与原始记录不一致')
            for key in ('old_instrument', 'new_instrument'):
                _text(file[key])
            for key in ('old_song', 'new_song'):
                if originals[score_id]['storage_layout'] == 'dual':
                    _text(file[key])
                elif file[key] is not None:
                    raise ValueError('单份分谱不能含第二处副本')
            files[score_id] = file
        selected = [_positive_id(item) for item in value['selected_score_ids']]
        if set(files) != set(originals) or len(selected) != len(set(selected)) or \
                not selected or not set(selected) < set(files):
            raise ValueError('合并记录的选择或原始文件集合无效')
        codes = {file['instrument_code'] for file in files.values()}
        if any(sum(files[item]['instrument_code'] == code for item in selected) != 1 for code in codes):
            raise ValueError('合并记录未明确每种乐器保留哪一份')
        for score_id, file in files.items():
            if score_id in selected:
                continue
            for key in ('new_instrument', 'new_song'):
                if file[key] is None:
                    continue
                historical = Path(file[key])
                if not historical.is_absolute() or '..' in historical.parts:
                    raise ValueError('合并记录活动PDF路径无效')
                try:
                    relative = historical.relative_to(historical_root)
                except ValueError as exc:
                    raise ValueError('合并记录活动PDF路径越界') from exc
                if len(relative.parts) != 1 or relative.suffix.lower() != '.pdf':
                    raise ValueError('合并记录活动PDF必须是曲目目录内的单层文件')
                active = path.parent / relative
                token = active.relative_to(root).as_posix().casefold()
                if token in used:
                    raise ValueError('合并记录重复引用同一活动PDF')
                used.add(token)
                if check(active)['sha256'] != file['sha256']:
                    raise ValueError('合并记录PDF内容已变化')
                entries.append(dict(copy_source=str(active), source_path=originals[score_id]['source_path'],
                    source_sha256=file['sha256'], display_title=title, display_instrument_code=file['instrument_code'],
                    audit_record=str(path), parsed_fields=dict(title=title, instrument_code=file['instrument_code'], category_code=value['category'])))
        records.append(str(path))
    for relative, expected in list(checked.items()):
        if check(root / relative) != expected:
            raise ValueError('复核时合并记录或PDF已变化')
    if _inventory(root) != initial:
        raise ValueError('核验期间合并记录目录已变化')
    return dict(entries=entries, records=records, checkedFiles=checked)
