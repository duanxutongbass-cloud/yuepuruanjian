# Phase 10.1 无网络本地核心候选审查记录

2026-10-13。状态：PM 复审确认初版测试证据可信，但发现管理写操作的归档/冻结保护缺口，**初版未放行**。修订候选与证据见本文末尾；仍等待 PM 独立复审，不宣布完整 Phase 10.1 或 Windows 原生交付通过。

## 授权与基线

用户已批准 0.9.6 后续并委托 PM 决定阶段；PM 明确放行可独立实施的编号、动态字典、PDF 导入校验和开放格式兼容最小批次，使用合成数据，与 Android 可靠性 R2 独立。用户随后授权将本项目代码/文档/测试保存到独立 feature 分支及 draft PR；不授权直接推 main、合并、release 或部署。

开工及提交前核对远端 main 与本地源代码，基线为 `32b702e6019b4a8f4471634163d3844b28f03d65`，Android `0.9.6 / versionCode 8 / schema 3`。工作区开工时无改动。枚举 `/workspace` 与仓库，未发现适用的 `AGENTS.md` 或 `.agents/skills`；仓库树也无此文件。读取 PRODUCT_ROADMAP、PHASE10_0_REQUIREMENTS、TEAM_SYNC_CONTRACT、DEVELOPMENT_RULES、PRODUCT_DESIGN、ASSET_ARCHIVE_FORMAT、schema 3 及 ArchiveFormat/ArchiveValidation/AssetArchiveService、实体与 InkCodec/冻结 manifest 实现。仓库没有独立 Windows 工具或另一本编号规范，采用上述当前有效确认，不按旧总结固定三位或固定作曲家号。

## 实现

全部核心位于 `local-manager/`，复用 Python 工具惯例及只读的既有 Room schema 3 定义；新建独立本地工作库，不打开或迁移 Android 数据库。

| 模块 | 本批结果 |
| --- | --- |
| `score_manager/store.py` | 全局序号与数值排序、历史号占用、同曲各声部共号、动态字典 CSV、稳定 ID、新旧版本、声部/乐章页段绑定、完整性审计 |
| `score_manager/pdf.py`、`pdf_worker.py` | 原始 PDF 严格解析、逐页几何/内容流检查，独立进程超时/取消；重复、缺失、损坏、加密和空文件拒绝 |
| `score_manager/archive.py` | 原 Android v1 子包与 hash 绑定侧车的本地开放往返；空工作库恢复、原字节/身份/版本/源页保留、无覆盖导出 |
| `score_manager/validation.py` | schema 3 字段/类型、外键、页段/绑定组合、共享批注/发布引用/冻结快照契约检查 |
| `tests/` | 纯合成 PDF、独立构造旧 v1 档案、边界/并发/取消/写入失败/进程退出/兼容与拒绝矩阵，以及证据生成入口 |

编号 998→999→1000→1001 不回绕、不按分类重置；归档及改号不释放历史编号；名字和分类号编辑不重建 UUID。分类为用户定义的两位数字，不固定解释为作曲家。动态乐器/标准声部用原 `part_definitions`，测试动态生成超过七种条目，不植入成员名单或正式字典。

PDF 原字节作为 SQLite BLOB，与元数据共用事务。校验或提交前取消/失败不遗留伪成功资产；新版本先不激活，显式换版会检查现有页段和冻结演出，不移动旧批注。原一基绑定转换为零基 sourcePage，版本/hash/旋转/裁切几何保持原义，没有生成配送分谱或新身份。

## 兼容边界

完整本地包是普通 ZIP：`library.zip` + `catalog.json` + `README.txt`。子包沿用 `ensemble-score-library`、formatVersion 1、databaseVersion 3，**未给旧 manifest 增加字段**。分类/编号/历史占号位于明确版本化侧车，关联原 piece ID，绑定子包 SHA-256。

旧 Android 只能消费提取后的 `library.zip`，不能恢复侧车编号/分类；完整本地往返必须保存整个 bundle。未来原生工具格式融合仍需单独审查，不宣称旧 Android 自动识别新元数据。旧 v1 导入不编造编号、不重建身份。所有共享 payload、revision、冻结 manifest 字符串原样保留，母库排除所有个人层；PERSONAL 包明确拒绝进入本模块，既有本人备份/恢复流程未修改。

## 初版实际验证（82bee7c；PM 未放行）

执行环境：Linux x86_64，Python 3.12.14，pypdf 6.20.1（官方 wheel，BSD-3-Clause，哈希锁定）。

```sh
PYTHONPATH=local-manager /tmp/phase101-venv/bin/python local-manager/tests/run_validation.py /workspace/phase101-final-evidence
/tmp/phase101-venv/bin/python tools/Verify-AssetArchive.py /workspace/phase101-final-evidence/android-v1-library.zip
python3 -m compileall -q local-manager
git diff --check
git diff --exit-code 32b702e6019b4a8f4471634163d3844b28f03d65 -- app build.gradle.kts settings.gradle.kts gradle.properties gradle tools test-tools
```

- **49 项测试，0 失败、0 错误、0 跳过**，最终完整套件用时 118.830 秒；首轮 45 项也通过，最终计数只报 49，不累计成 94。
- 编号：边界生成/导入导出、数值排序、跨分类重复拒绝、同曲 12 个声部共号、归档/改号保留占号、并发 12 次分配无重号、重开工作库继续流水。
- 字典：空初始状态、稳定用户 ID/新 UUID、改名/改分类号不换身份、CSV 往返、重复项/错误字段整批回滚、归档保留引用。
- PDF/绑定：同字节改名重复、批内重复、损坏、加密、零页、缺失、解析前/中取消、元数据已暂存后取消、SQLite 写入拒绝、校验失败及进程直接退出恢复；跨曲乐章、非法页段、重复/混合绑定、显式换版与回滚。
- 兼容：独立构造的旧 v1 母库含历史版本、当前版本不是最大版本号、权限/分配、共享发布和冻结快照；逐表完全相等往返，源页映射/几何相等；母库排除管理员与成员个人层。
- 拒绝矩阵：非空目标、源 hash 变化、错误侧车 hash、未知格式/字段、重复 ZIP/JSON 字段、异常路径、错误类型、坏 ID/引用/页数/冻结快照、个人包、掺入个人批注、缺 PDF/校验失败。
- 另运行仓库原有 `tools/Verify-AssetArchive.py`：导出的 Android 子包 `valid=true`，2 个 PDF 版本、1 个共享批注页。这个校验器本来就不替代 Room/Domain 全量验证。
- Python 编译检查与 `git diff --check` 成功；Android、Gradle 配置、原 tools/test-tools 相对基线 diff 为空。

仓库内精简证据：[test-result.json](../local-manager/evidence/2026-10-13/test-result.json)、[tests.log](../local-manager/evidence/2026-10-13/tests.log)、[round-trip.json](../local-manager/evidence/2026-10-13/round-trip.json)。完整交接包另含精确 patch、基线、源文件哈希、合成开放包和执行日志；生成包/数据库不上传 GitHub。

## 修复与保留限制

首个开发 smoke check 前纠正成员插入参数数目；首轮完整测试无失败。自查后清理未用导入，追加真正的 SQLite 写入拒绝、子进程直接退出回滚、解析中取消及显式换版/回滚用例，再运行上述最终完整套件。没有套用另一任务 R2 补丁，没有更改其 app 文件。

PDF 结果是结构解析，不是原生渲染/图像字体解码全覆盖，不是清洗或越权分发安全审查。没有分谱生成、页面裁切或配送身份。Windows 原生 UI、安装包、文件系统/硬链接兼容、进程内存预算和超大库性能待后续验收；Linux parser 有内存/时间预算，不能据此宣称 Windows 已验。

Android Gradle APK、Lint、JVM/模拟器/真机未重跑：本批无 Android 修改，环境没有 Gradle/JDK 编译器；不沿用历史 170 项冒充本次测试。没有读取私人曲谱/真实成员资料，没有凭据、SDK 或缓存交付，没有认证、联网同步/上传/分发、NAS 部署、生产权限、破坏性迁移或 main 合并。NAS 参数未知不阻本批，但后续部署仍需核验。

初版结论已由下述 PM 复审更新：49 项测试通过不能代表本批业务不变量完整，修复并重测后再交复审。完整 10.1、Windows 原生交付及 Phase 10 整体尚未通过，不据此放行联网或部署。

## PM 复审修订：归档与冻结结构保护

PM 发现 `archive_piece` 可归档启用节目项引用的曲目，且 manifest 没有曲目归档字段，原校验未拒绝。随后扩展检查发现乐器定义编辑/归档、未绑定声部/乐章新增也不能仅依赖 manifest 相等。已对照 `LibraryManagement.kt` 的 `lockedPieces`、`guardPiece`、`guardPdf` 及 Piece/Definition/Part/Movement/Binding 分支，集中收紧本地核心，不重写 Android Domain。

- 写前保护：启用节目项引用时禁止归档曲目；活跃声部或成员默认声部使用的乐器禁止归档；冻结曲目的声部、乐章、绑定和使用中乐器编辑拒绝，所用 PDF 的版本追加和激活拒绝（包括激活同一当前版本）。保护检查与写入在同一 SQLite 写事务。
- 状态校验：恢复/导出/事务提交拒绝启用节目项指向已归档曲目、活跃声部/成员默认指向已归档定义。不重新生成冻结 manifest/payload 来绕过保护。
- 相关写操作收紧：绑定检查原所属曲目/定义未归档，已归档的同范围绑定也不能另建身份；版本激活重验原始 PDF 字节；新增文本/排序输入沿用 Android 管理端长度、裁剪和 Int 边界。
- 完整对照矩阵见 [MANAGEMENT_INVARIANTS.md](../local-manager/MANAGEMENT_INVARIANTS.md)。编号/分类仅写本地侧车，不修改 Android 曲目或冻结结构；未开放的成员编辑、授权、节目项或解除冻结操作没有新入口。
- 修复前先运行新回归：最初 8 项得到 7 条失败记录；扩大为 20 项得到 18 条失败记录（unittest 统计包含子测试）。随后修复，首轮 23 项保护回归通过，再加 2 项绑定/历史引用边界并跑完整套件。失败路径比较全库 `iterdump()`，包括 PDF BLOB、所有元数据和编号占用；另验证事务中其他写入一起回滚。
- 原证据生成器曾给已冻结曲目追加未绑定声部；现在将 12 个动态声部样本放到独立可编辑的 999 号曲目，保留原冻结曲目及源页证据，不放宽保护迁就样本。

本修订仍只是 PR #2 的追加候选，不 force、不改 main、不合并、不 release/deploy；Android、schema、版本、批注格式和 R2 文件不变。最终本地测试、补丁重放和远端 CI 状态分别记录，不将本地通过当作 GitHub CI 通过。

修订最终本地验证：`run_validation.py /workspace/phase101-pm1-final-evidence` 实际执行 **74 项，0 失败/错误/跳过，用时 101.336 秒**；独立合成旧 v1 往返及原 `Verify-AssetArchive.py` 再次通过（2 个 PDF 版本、1 个共享批注页）。compileall、diff --check 成功，Android/构建配置/原工具相对 main 基线 diff 仍为空。证据：[最终结果](../local-manager/evidence/2026-10-13-pm1/test-result.json)、[全集日志](../local-manager/evidence/2026-10-13-pm1/tests.log)、[修复前失败日志](../local-manager/evidence/2026-10-13-pm1/guards-before.log)、[往返](../local-manager/evidence/2026-10-13-pm1/round-trip.json)。远端新提交的 CI 查询回执随本次 Library 交接包提供，PM 仍须复审业务，不将本地 74 项自动计为放行。
