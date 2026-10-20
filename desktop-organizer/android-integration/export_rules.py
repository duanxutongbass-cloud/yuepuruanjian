"""Export the desktop naming dictionary for Android; original database is read-only."""
import argparse
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'src'))
from score_organizer.services.android_rules import export_rules
if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    export_rules(args.database, args.output)
    print('字典已导出；在 Android 导入页选择“加载电脑端字典 JSON”。原数据库未修改。')
