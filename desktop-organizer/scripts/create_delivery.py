"""Assemble a clean local delivery; never include user databases or score PDFs."""
from __future__ import annotations

import argparse
import hashlib
import importlib.metadata
import json
import shutil
import zipfile
from pathlib import Path


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def assemble(root: Path, build: Path, target: Path):
    if target.exists() or target.with_suffix('.zip').exists():
        raise ValueError('交付目录或ZIP已存在，不覆盖，请选择新地址')
    exe = build / '曲谱库重奏乐谱整理工具.exe'
    if not exe.is_file() or not (build / '_internal').is_dir():
        raise ValueError('缺少完整Windows程序构建')
    target.mkdir(parents=True, exist_ok=False)
    shutil.copytree(build, target / 'Windows程序')
    source = target / '源码'
    source.mkdir()
    for folder in ('src', 'tests', 'scripts', 'packaging'):
        shutil.copytree(root / folder, source / folder,
                        ignore=shutil.ignore_patterns('__pycache__', '*.pyc', '*.egg-info'))
    for name in ('pyproject.toml', 'README.md', 'SPEC.md', 'PROGRESS.md', 'AGENTS.md'):
        shutil.copy2(root / name, source / name)
    packages = ('PySide6', 'PySide6_Addons', 'PySide6_Essentials', 'shiboken6', 'rapidfuzz',
                'pytest', 'PyInstaller', 'pyinstaller-hooks-contrib', 'altgraph', 'pefile',
                'pywin32-ctypes', 'setuptools', 'packaging', 'pypdf', 'cryptography', 'cffi', 'pycparser')
    versions = {package: importlib.metadata.version(package) for package in packages}
    (source / 'requirements-lock.txt').write_text(
        '\n'.join(f'{key}=={value}' for key, value in versions.items()) + '\n', encoding='utf-8')
    for package in packages:
        distribution = importlib.metadata.distribution(package)
        for file in distribution.files or ():
            if Path(file).is_absolute() or '..' in Path(file).parts:
                continue
            if any(word in file.name.casefold() for word in ('license', 'copyright', 'copying', 'notice')):
                original = Path(distribution.locate_file(file))
                if original.is_file():
                    destination = target / '依赖许可' / package / str(file)
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(original, destination)
    (target / '启动整理工具.cmd').write_text(
        '@echo off\r\nstart "" "%~dp0Windows程序\\曲谱库重奏乐谱整理工具.exe"\r\n', encoding='gbk')
    (target / '先读我.txt').write_text(
        '曲谱库重奏乐谱整理工具 - 完整交付\n\n'
        '运行：双击“启动整理工具.cmd”，或Windows程序中的EXE。无需另装Python。\n'
        '请完整解压，不要单独复制EXE或删除_internal目录。\n'
        '源码、真实自动化测试、开发规则与打包方法在“源码”目录；锁定依赖见requirements-lock.txt。\n'
        '数据不在程序目录：默认使用当前Windows用户目录/曲谱库整理工具/score_organizer.sqlite3。\n'
        '旧电脑用同一Windows用户打开新版，核对原曲库后选择“数据管理→导出整库迁移包”（默认桌面）。\n'
        '把完整迁移ZIP复制到新电脑，再选择“数据管理→从整库迁移包恢复”，校验预览后恢复到全新目录。\n'
        '保留原编号和校对结果，无需重新识别；只复制桌面上的PDF或程序文件不能带走数据库。\n'
        '本包不包含用户数据库、草稿、乐谱、缓存、开发虚拟环境或临时测试文件。\n'
        '未经数字签名，不要将测试通过等同于另一台机器或真实PDF整库验收。\n'
        '本机验证详情见“验证结果.txt”；文件完整性见“文件校验.json”。\n', encoding='utf-8')
    records = {p.relative_to(target).as_posix(): {'size': p.stat().st_size, 'sha256': digest(p)}
               for p in sorted(target.rglob('*')) if p.is_file()}
    (target / '文件校验.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')


def finalize(target: Path):
    manifest = target / '文件校验.json'
    records = {p.relative_to(target).as_posix(): {'size': p.stat().st_size, 'sha256': digest(p)}
               for p in sorted(target.rglob('*')) if p.is_file() and p != manifest}
    manifest.write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
    archive = target.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as bundle:
        for file in sorted(target.rglob('*')):
            if file.is_file():
                bundle.write(file, (Path(target.name) / file.relative_to(target)).as_posix())
    with zipfile.ZipFile(archive) as bundle:
        if bundle.testzip() is not None:
            raise ValueError('ZIP完整性检查失败')
        for relative, record in records.items():
            if digest(target / relative) != record['sha256']:
                raise ValueError('文件在打包期间变化：' + relative)
            with bundle.open((Path(target.name) / relative).as_posix()) as stream:
                if hashlib.file_digest(stream, 'sha256').hexdigest() != record['sha256']:
                    raise ValueError('ZIP内容校验失败：' + relative)
    print(json.dumps({'folder': str(target), 'zip': str(archive), 'files': len(records),
                      'zip_bytes': archive.stat().st_size, 'zip_sha256': digest(archive)}, ensure_ascii=False))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('target', type=Path)
    parser.add_argument('--build', type=Path)
    parser.add_argument('--finalize', action='store_true')
    args = parser.parse_args()
    if args.finalize:
        finalize(args.target.resolve())
    else:
        assemble(Path(__file__).resolve().parent.parent, args.build.resolve(), args.target.resolve())
