# Phase 10.1 无网络本地管理核心（候选）

本目录是可独立测试的管理领域核心，不是 Windows 原生 UI、安装包或联网服务。沿用仓库 `tools/Verify-AssetArchive.py` 的 Python 工具惯例；Python 只是本批可执行实现，不冻结 Windows 最终技术栈。产品规则仍以 `docs/PRODUCT_ROADMAP.md`、`PHASE10_0_REQUIREMENTS.md`、`TEAM_SYNC_CONTRACT.md` 为准。

PM 复审发现初版冻结/归档保护缺口后已修订，并限定放行 `4a00ba8` 的离线核心（74 项测试）。逐项写操作与既有 Android Domain 对照见 [MANAGEMENT_INVARIANTS.md](MANAGEMENT_INVARIANTS.md)。新增独立最小工作流批次仍待复审，CLI、草稿/确认语义及成员只读预览边界见 [WORKFLOW.md](WORKFLOW.md)。初版 49 项测试保留为历史证据。

## 运行与复审

PR #3 的 CLI 最小流程已经 PM 限定复审通过；其上的本地可视复核候选见 [LOCAL_REVIEW.md](LOCAL_REVIEW.md)：完整变更摘要、成员模拟及获准源页 PNG。仅本机管理员原型，未发布，不是成员服务。

需要 Python 3.10+。在仓库根目录运行（Linux/macOS）：

```sh
python3 -m venv /tmp/score-manager-venv
/tmp/score-manager-venv/bin/python -m pip install --require-hashes --only-binary=:all: -r local-manager/requirements.txt
PYTHONPATH=local-manager /tmp/score-manager-venv/bin/python -m unittest discover -s local-manager/tests -v
PYTHONPATH=local-manager /tmp/score-manager-venv/bin/python local-manager/tests/run_validation.py /tmp/phase101-evidence-new
```

Windows 的开发入口（此批没有实测 Windows）：

```powershell
py -3 -m venv .venv-local
.\.venv-local\Scripts\python -m pip install --require-hashes --only-binary=:all: -r local-manager/requirements.txt
$env:PYTHONPATH = 'local-manager'
.\.venv-local\Scripts\python -m unittest discover -s local-manager/tests -v
```

`run_validation.py` 拒绝已存在的输出目录，生成测试日志/JSON、独立合成旧 v1 包、往返后的包与源页映射比较。生成的 SQLite 仅是测试工作库，**不是开放备份格式**。不要提交虚拟环境、数据库、测试输出或 SDK。

唯一外部运行依赖是官方 PyPI 的 `pypdf 6.20.1`，BSD-3-Clause，锁定 wheel SHA-256；不安装 crypto/image extras。依赖安装需要包源或预先下载的可信 wheel，核心运行、测试和导入/导出不访问网络。来源、许可证与限制见 [DEPENDENCIES.md](DEPENDENCIES.md)。

## 使用示例

```python
from score_manager import Catalog
from score_manager.archive import export_bundle, restore_archive
from score_manager.validation import sha

with Catalog("new-workbench.sqlite", create=True) as c:
    admin = c.add_member("用户输入的管理员名称", administrator=True)
    category = c.add_category("37", "用户定义的分类")
    instrument = c.add_instrument("用户定义的乐器/标准声部")
    piece = c.add_piece("曲目名称", category, number=998)
    part = c.add_part(piece, instrument)
    source = c.import_pdf("selected-source.pdf")
    binding = c.bind(part, source["scorePdfId"], 1, 2)
    print(c.source_pages(binding))  # 原 versionId，零基源页，原 hash/几何
    export_bundle(c, "new-local-bundle.zip", admin)
```

该 API 只用于受信任的本机管理员整理工作流；成员角色检查不构成生产登录或操作系统权限隔离，不能把它接到成员网络请求。没有发布/下载/分谱接口，不会因管理员能整理母库就向其授予线上阅读权限。

## 已实现的业务边界

- 所有分类共享正整数曲目号，显示至少三位；998、999、1000、1001 自动递增，按数值排序。相同曲目的所有分谱通过 `pieceId` 共号，不从文件名猜同曲。
- UUID 用于新建记录，旧包 String ID 原样保留；名称、分类号、曲目号调整不换身份。曲目归档和重新编号均保留历史号占用，其他曲目不能复用。整数上限为 SQLite 有符号 64 位容量；溢出明确拒绝，不回绕。
- 分类号是用户定义的两位 ASCII 数字，不解释为作曲家；标准声部/乐器复用 schema 3 `part_definitions`。不内置七人、乐器或分类。没有已冻结的完整文件名编码协议，因此不发明第三段乐器号或自动文件名解析策略。
- 动态字典 CSV 为 UTF-8：分类 `id,code,name,isArchived`；乐器 `id,name,family,sortOrder,isArchived`。空 `id` 才新建 UUID；已有 ID 原样导入。归档 0/1。整批追加，重复 ID/分类号/乐器名拒绝并回滚；更新走显式 `edit_dictionary`，不按同名合并。CSV 是机器交换格式，未将其作为安全的电子表格预览格式。
- `import_pdfs` 整批原子提交；缺失、重复字节、损坏、加密、空 PDF、取消或写入失败全部回滚。相同 PDF 要供多个声部使用时，复用已有 source 并创建绑定，不重新导入。
- 每个版本保留原字节/ID/hash/页数/几何。后续版本先作为非当前版本入库；显式 `activate_version` 可切换/回滚，但越界页段、错误归属或已冻结演出映射改变会拒绝。
- 绑定沿用 Android 的一基闭区间；`source_pages` 输出零基源页，不改变裁切/旋转、不制造配送文件。绑定组合、跨曲乐章、重复全曲绑定、全曲与乐章混用均拒绝。
- 曲目仍被启用节目项引用时禁止归档；乐器仍被活跃声部或成员默认声部使用时禁止归档。冻结曲目的声部、乐章、绑定和所用乐器编辑、所用 PDF 的版本追加/激活均显式拒绝，不只检查 manifest 是否改变。旧包或现存库含这些非法归档引用时拒绝恢复/导出，不改写冻结快照掩盖错误。
- PDF 字节和 schema 3 元数据在同一 SQLite 事务提交，无独立已提交资产文件可遗留。取消在解析期间和提交前生效，成功提交后到达的取消不撤销已完成动作。SQLite 意外进程退出回滚另有测试。

## 开放格式兼容

本地 bundle 是一个标准 ZIP，含 `library.zip`、`catalog.json`、`README.txt`。

- `library.zip` **仍为原格式 v1 / databaseVersion 3**，表/字段及条目布局不加扩展。保留完整历史版本、唯一 `activeVersionId`、原页段、共享批注 payload、发布 ID 与冻结 manifest。管理员母库排除所有个人层，包括管理员本人个人层。
- `catalog.json` 是本地工作台侧车 v1，含分类字典、曲目号和历史号占用；使用原 piece ID 关联，以 `librarySha256` 绑定子包。侧车不是 Android schema 变更，不是联网协议。完整本地往返必须保留整个 bundle。
- 旧 Android v1 只能直接读取提取后的 `library.zip`，**不会恢复编号/分类侧车**。不把外层 bundle 宣称为 Android 可恢复包，也不静默删侧车后声称完整恢复。未来原生 UI/Android 格式演进需另行冻结和验收。
- 旧 v1 母库可导入空工作库；没有编号的曲目保持“未编号”，需显式 `assign_number`，不按名称重建身份。个人包拒绝进入本模块，继续既有本人备份恢复流程，不实现汇总/合并私人批注。
- 恢复要求空工作库和调用方选定的整包 SHA-256；未知版本/字段、重复条目、路径异常、类型错误、失配 hash、引用/页段/冻结快照异常都拒绝。摘要用于完整性，不是签名或身份认证。
- 导出只创建新文件，利用同目录暂存 + 无覆盖原子硬链接发布；不支持硬链接的文件系统安全失败，不回退到覆盖。未验 Windows/FAT/exFAT/网络盘兼容，留待打包验收。

## 未验与不在本批范围

PDF 检查为严格解析、逐页几何及内容流解析，**不是原生渲染验收、恶意 PDF 清洗或文件级授权分谱**。解析进程有 30 秒超时、输入/页数限制；Linux 测试环境有 768 MiB 地址空间上限，Windows job-object 内存限额待原生集成。未全面验证图像/字体解码、超大谱库性能与恶意资源耗尽。本地包使用内存缓冲，上限是拒绝边界，不是规模通过证据。

没有改 Android 资产、Room schema、版本号、身份或批注备份语义；没有运行 Android APK/Gradle/Lint/模拟器/真机测试，本环境缺少 Gradle 与 JDK 编译器。没有 Windows 原生 UI/打包、真实成员资料、认证/上传/同步/发布、分谱分发、NAS、凭据、私人曲谱或部署。NAS 未知参数不阻本批核心，仍是后续部署门禁。
