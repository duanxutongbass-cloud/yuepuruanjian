# PyInstaller spec for Windows packaging.

from pathlib import Path
import os
import PySide6
from PyInstaller.utils.hooks import collect_submodules

root = Path(SPECPATH).parent

a = Analysis(
    [str(root / "src/score_organizer/main.py")],
    pathex=[str(root / "src")],
    binaries=[],
    datas=[],
    hiddenimports=collect_submodules('score_organizer.services'),
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
)
# Qt's runtime is newer than Python's bundled runtime; use one compatible set
# at the loader root so Qt cannot bind to Python's older DLLs first.
qt_directory = Path(PySide6.__file__).parent
runtime_names = ("vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll",
                 "msvcp140_1.dll", "msvcp140_2.dll", "msvcp140_codecvt_ids.dll", "concrt140.dll")
runtimes = [qt_directory / name for name in runtime_names if (qt_directory / name).is_file()]
root_names = {path.name.casefold() for path in runtimes}
a.binaries = [entry for entry in a.binaries
              if not (Path(entry[0]).parent == Path(".") and Path(entry[0]).name.casefold() in root_names)]
a.binaries += [(path.name, str(path), "BINARY") for path in runtimes]
# Windows Qt uses the system ICU API. A same-named Poppler DLL on the build
# machine's PATH exports a different API and must not shadow Windows' DLL.
a.binaries = [entry for entry in a.binaries if Path(entry[0]).name.casefold() != "icuuc.dll"]
pyz = PYZ(a.pure)
exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="曲谱库重奏乐谱整理工具",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=bool(os.environ.get("SCORE_PACK_CONSOLE")),
)
coll = COLLECT(
    exe,
    a.binaries,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="曲谱库重奏乐谱整理工具",
)
