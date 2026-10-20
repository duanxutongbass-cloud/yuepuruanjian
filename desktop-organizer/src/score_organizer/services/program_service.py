from __future__ import annotations

import json
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path

from score_organizer.services.archive_service import ArchiveService
from score_organizer.services.import_service import sha256_file
from score_organizer.domain.numbering import normalize_seq


def safe_name(value: str) -> str:
    value = re.sub(r'[<>:"/\\|?*\x00-\x1f]', '_', value).strip().rstrip('. ')
    if not value or value.split('.')[0].upper() in {
        'CON', 'PRN', 'AUX', 'NUL', *(f'COM{i}' for i in range(1, 10)), *(f'LPT{i}' for i in range(1, 10))
    }:
        value = '_' + value
    while len(value.encode('utf-16-le')) // 2 > 140:
        value = value[:-1]
    return value


def reject_links(path: Path):
    for part in (path, *path.parents):
        if part.is_symlink() or part.is_junction():
            raise ValueError(f'路径含链接，不能打包：{part}')


@dataclass(frozen=True)
class ProgramFile:
    sources: tuple[Path, ...]
    relative: str
    digest: str


@dataclass(frozen=True)
class ProgramPlan:
    entries: tuple[tuple[int, int], ...]
    files: tuple[ProgramFile, ...]
    rows: tuple[tuple[int, int, str, str, str, tuple[str, ...]], ...]
    missing: tuple[str, ...]


class ProgramService:
    _numbering_version = 1
    _manifest_format = 'score-program-v1'

    def __init__(self, db, library_root):
        self.db = db
        self.root = Path(library_root).absolute()

    def _require_runtime(self):
        pass

    def _require_export_destination(self, parent):
        pass

    def _archive(self):
        return ArchiveService(self.db, self.root)

    def _library_number(self, category, sequence):
        sequence = normalize_seq(sequence, naming_version=self._numbering_version)
        return f'{category}{sequence}' if len(sequence) == 3 else f'{category}-{sequence}'

    def catalog(self):
        self._require_runtime()
        categories = {r['code']: r['name'] for r in self.db.list_categories()}
        songs = {}
        for score in self.db.list_archived_scores():
            item = songs.setdefault(int(score['song_id']), {
                'id': int(score['song_id']), 'title': score['display_title'],
                'category': score['category_code'], 'category_name': categories.get(score['category_code'], ''),
                'seq': score['seq'], 'instruments': set(),
            })
            item['instruments'].add(score['instrument_code'])
        return sorted(songs.values(), key=lambda r: int(r['seq']))

    def build_plan(self, entries) -> ProgramPlan:
        self._require_runtime()
        entries = tuple(sorted((int(order), int(song)) for order, song in entries))
        if not entries:
            raise ValueError('请先添加节目曲目')
        orders = [order for order, _ in entries]
        if len(set(orders)) != len(orders) or any(not 1 <= n <= 9999 for n in orders):
            raise ValueError('节目序号须为1～9999且不能重复')
        instruments = {r['code']: f"{r['code']} {r['name']}" for r in self.db.list_instruments()}
        archive = self._archive()
        files, rows, paths = [], [], set()
        for order, song_id in entries:
            song = self.db.get_archived_song(song_id)
            scores = self.db.list_archived_scores(song_id)
            if not song or not scores:
                raise ValueError(f'节目{order}的曲目已不存在，请重新选择')
            normalize_seq(song['seq'], naming_version=self._numbering_version)
            codes = [s['instrument_code'] for s in scores]
            if len(set(codes)) != len(codes):
                raise ValueError(f"{song['display_title']}有同乐器多份分谱，请先在库内确认保留哪一份")
            rows.append((order, song_id, song['category_code'], song['seq'], song['display_title'], tuple(sorted(codes))))
            for score in scores:
                if Path(score['output_name']).name != score['output_name']:
                    raise ValueError('库内文件名异常，请先检查目录')
                sources = archive.score_paths(score)
                for source in sources:
                    reject_links(source)
                    if not source.resolve().is_relative_to(self.root.resolve()):
                        raise ValueError('库内PDF越界，不能打包')
                    if not source.is_file() or sha256_file(source) != score['source_sha256']:
                        raise ValueError(f'库内PDF缺失或变化：{source}')
                relative = (Path(safe_name(instruments[score['instrument_code']])) /
                            f"{order:03d} {safe_name(song['display_title'])}.pdf").as_posix()
                if relative.casefold() in paths:
                    raise ValueError('打包文件重名，请调整节目序号')
                paths.add(relative.casefold())
                files.append(ProgramFile(sources, relative, score['source_sha256']))
        all_codes = sorted({code for row in rows for code in row[5]})
        missing = tuple(f"{order:03d} {title}：缺 {instruments[code]}"
                        for order, _, _, _, title, codes in rows for code in all_codes if code not in codes)
        return ProgramPlan(entries, tuple(files), tuple(rows), missing)

    def export(self, plan: ProgramPlan, destination, name: str, make_zip=True) -> Path:
        self._require_runtime()
        if not name.strip():
            raise ValueError('请输入节目单名称')
        parent = Path(destination).absolute()
        reject_links(parent)
        if not parent.is_dir():
            raise ValueError('请选择存在的打包输出文件夹')
        # Exports must never be confused with or placed inside the managed library.
        if parent.resolve().is_relative_to(self.root.resolve()):
            raise ValueError('请在乐谱库目录之外选择打包位置')
        self._require_export_destination(parent)
        target = parent / safe_name(name)
        if target.exists():
            raise ValueError(f'打包目录已存在，请更换名称，不会覆盖：{target}')
        if self.build_plan(plan.entries) != plan:
            raise ValueError('库内记录已变化，请重新预览')
        with tempfile.TemporaryDirectory(prefix='.program-', dir=parent) as temp:
            stage = Path(temp)
            for file in plan.files:
                output = stage / file.relative
                output.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(file.sources[0], output)
                if sha256_file(output) != file.digest:
                    raise ValueError(f'打包副本校验失败：{file.relative}')
            manifest = {'format': self._manifest_format, 'name': name.strip(),
                        'entries': [{'order': r[0], 'song_id': r[1], 'category': r[2], 'library_seq': r[3],
                                     'title': r[4], 'instruments': list(r[5])} for r in plan.rows],
                        'files': [{'path': f.relative, 'sha256': f.digest} for f in plan.files],
                        'missing': list(plan.missing)}
            if self._numbering_version != 1:
                manifest['namingVersion'] = self._numbering_version
            (stage / '节目单.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
            lines = [name.strip(), *[f'{r[0]:03d} {r[4]}（库编号{self._library_number(r[2], r[3])}）' for r in plan.rows],
                     '', '缺谱清单（按本节目出现的乐器比较）', *(plan.missing or ('无',))]
            (stage / '节目单.txt').write_text('\n'.join(lines), encoding='utf-8')
            if make_zip:
                with zipfile.ZipFile(stage / '节目乐谱.zip', 'x', zipfile.ZIP_DEFLATED) as bundle:
                    for file in sorted(stage.rglob('*')):
                        if file.is_file() and file.suffix != '.zip':
                            bundle.write(file, file.relative_to(stage).as_posix())
                with zipfile.ZipFile(stage / '节目乐谱.zip') as bundle:
                    if bundle.testzip() is not None:
                        raise ValueError('ZIP校验失败')
            if self.build_plan(plan.entries) != plan:
                raise ValueError('复制期间库内记录或文件变化，请重新预览')
            reject_links(parent)
            self._require_export_destination(parent)
            target.mkdir(exist_ok=False)
            published = []
            try:
                for child in stage.iterdir():
                    child.rename(target / child.name)
                    published.append(target / child.name)
            except Exception as exc:
                try:
                    for child in reversed(published):
                        child.rename(stage / child.name)
                    target.rmdir()
                except OSError as rollback_error:
                    raise RuntimeError(f'打包失败且回退受阻，请检查：{target}；{rollback_error}') from exc
                raise
        return target
