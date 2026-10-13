# 本批依赖核对

运行依赖：Python 标准库（SQLite、ZIP、CSV、JSON、hashlib、subprocess）以及 `pypdf==6.20.1`。

2026-10-13 核对官方来源：

- [PyPI pypdf 6.20.1 元数据](https://pypi.org/pypi/pypdf/6.20.1/json)：BSD-3-Clause；wheel 为 `pypdf-6.20.1-py3-none-any.whl`。
- [pypdf 官方安装说明](https://pypdf.readthedocs.io/en/stable/user/installation.html)：使用基础包，不需要 crypto/image extras。
- [pypdf 官方 strict 说明](https://pypdf.readthedocs.io/en/stable/user/robustness.html)：采用 `strict=True`，不启用宽容修复模式。
- [源代码与许可证](https://github.com/py-pdf/pypdf/blob/6.20.1/LICENSE)：再分发时须保留 BSD 声明。当前仓库不分发第三方二进制或 wheel；原生 Windows 打包另行列出许可证清单。

wheel SHA-256：`aa5a55ddcffdc5e5ab291d5decb23f6383f4e56f8e3263dc39af41fff03885ad`。

这不是永久 UI 技术选型，不引入 PDFium/MuPDF、Windows 框架或联网 SDK。仅基础 Python 解析依赖获本批使用；不能据此批准将任意 PDF 当作安全配送文件。测试用 PDF 由同一官方库生成简单几何和合成元数据，另以仓库既有 `Verify-AssetArchive.py` 独立核验导出的 Android 子包。
