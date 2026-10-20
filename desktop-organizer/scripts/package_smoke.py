"""Launch a frozen build against an isolated profile until its window is closed."""
from __future__ import annotations

import argparse
import os
import subprocess
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('exe', type=Path)
    parser.add_argument('profile', type=Path)
    args = parser.parse_args()
    profile = args.profile.resolve()
    profile.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env['USERPROFILE'] = str(profile)
    env.pop('QT_QPA_PLATFORM', None)
    env.pop('PYTHONPATH', None)
    with (profile / 'startup.log').open('w', encoding='utf-8') as log:
        process = subprocess.Popen([str(args.exe.resolve())], cwd=args.exe.resolve().parent,
                                   env=env, stdout=log, stderr=log)
        try:
            for _ in range(150):
                if process.poll() is not None:
                    raise RuntimeError(f'EXE exited early ({process.returncode}); check {profile / "startup.log"}')
                if (profile / '曲谱库整理工具/score_organizer.sqlite3').exists():
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('EXE did not create its isolated test database')
            print(f'PACKAGED_EXE_READY pid={process.pid} profile={profile}', flush=True)
            code = process.wait(timeout=240)
            if code != 0:
                raise RuntimeError(f'EXE exit code: {code}')
            print('PACKAGED_EXE_CLOSED_OK', flush=True)
        finally:
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)


if __name__ == '__main__':
    main()
