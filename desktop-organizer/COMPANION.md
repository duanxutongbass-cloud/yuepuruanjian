# Android 曲谱库的 Windows 整理器

用户于 2026-10-14 指定：本软件是整个 Android 曲谱库项目的电脑端附属软件。原项目源码和功能基线来自 `duanxutongbass-cloud/yuepuruanjianqianzhi`，来源提交见 UPSTREAM.json，SPEC.md 和既有开发规则保留。主项目接入规则见 ../docs/ORGANIZER_COMPANION.md。

保留 Python/PySide6/SQLite 离线桌面、人工校对、全局编号、重复处理、双目录归档及节目单打包。现有数据库和 Android Room 库各自独立，不直接混用 SQLite 或假称已经联网同步。

新增“设置 → 导出 Android 识别字典…”：只读导出启用的分类和乐器映射，不含 PDF/曲目/个人批注，不覆盖已有 JSON。把该文件传到平板，在“导入曲谱 → 加载电脑端字典 JSON”中加载。初始字典与 Android 内置资产完全一致，可自由维护之后再导出。

旧名：`乐器2位 + 分类2位 + 曲名.pdf`；新名：`乐器2位 + 分类2位 + 流水3位 + 曲名.pdf`。例如 `0201007天空之城.pdf` 为第二小提琴 / 久石让 / 007 / 天空之城。分类不是作曲家字段。序号仍遵循本整理器 001–999 的现行协议，超出规则另行设计，不修改既有编号。

附属软件启动器设置 SCORE_ORGANIZER_DATA_DIR，把新库放在交付目录 ScoreOrganizer/data；不设置此变量时源码仍沿用原程序的用户目录。原电脑的用户库和 PDF 没有随源码上传，本次不读取或迁移它们。普通直接双击 EXE 仍沿用旧默认路径，建议使用交付的 ScoreOrganizer.cmd。

CLI 等价出口：`python android-integration/export_rules.py 原数据库.sqlite3 新字典.json`。原数据库只读，输出不得已存在。

现有 local-manager/ 是早期技术原型，保留参考。本 desktop-organizer/ 作为用户指定的电脑端软件基础，不再从零重做另一套整理器。
