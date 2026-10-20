# Phase 9 审查

## 用户暂时验收

2026-10-09，用户表示“暂时通过”。记录为对当前非真机验证批次的暂时接受，不表示新增实测证据，也不将整个 Phase 9 改为完整通过。真机、实际笔/脚踏、真实复杂原谱和长期 UI/排练测试等仍待补；既有交互风险仍按 UX_ITERATION_BACKLOG 跟踪。没有自动启动 Phase 10 或正式采纳新增 Phase 9.5 的开发安排。

状态：2026-10-09 进行中，未通过。用户最新决定暂时不做真机，继续自动测试与模拟器检查；停止 USB/驱动排查，不向物理平板运行任何测试。已知设备为用户报告 MatePad Pro 2021 / HarmonyOS 4.2.0.213、Windows WGR-W09，尚无授权 ADB 真机验收。Phase 8 的 130 个测试和 APK 0.8.0 仍是当时已通过基线。

本轮已补真机验收要求/记录矩阵、只读设备采样工具和 100 次小 PDF 重复原生渲染测试。以下结果仅为模拟器与验收准备证据，不能替代真机阶段结论。

禁止对已有资料的真机执行现有完整 connected 测试套件，避免测试数据库清理/UTP 重装影响用户资料。真机先读设备和包信息，不清数据、不改系统密度。

尚缺：普通 Pad 实测，大 Pad/笔/实际脚踏，真实复杂谱的完整 UI/真实排练持续运行及更完整的数据规模/界面性能；用户 39 页扫描谱已补原生引擎证据，范围见专项记录。不提前宣告 Phase 9 通过或进入 NAS 阶段。sources/ 只读。

## 本轮修复

1. 实际执行发现只读采样工具在设备未安装应用时将 pm path 的空输出/退出码 1 当成失败；修复为记录未安装并停止应用采样。只对该已知分支放行，授权/连接/其他命令失败仍拒绝。已在模拟器分别验证已安装和未安装路径，不改设备设置或用户数据。
2. 新资源测试初次入口推断了非 Unit 返回类型，Android JUnit 无法初始化；改为显式 runBlocking<Unit>。随后重新完整运行设备测试，不忽略失败、不跳过新增测试。
3. 审查既有设备测试会清理测试数据且 UTP 在测试后卸载目标包，明确禁止对有用户资料的 Pad 直接执行该套件。只读工具和真机手测分离，未安装/卸载/清除物理设备数据。

## 第一批实际软件证据

- 完整 Gradle `testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest` 最终 BUILD SUCCESSFUL；Android 14/API 34 隔离模拟器的 26 个设备测试，0 失败/错误/跳过。
- JVM 105 个现有测试，0 失败/错误/跳过；新增资源测试为设备测试，JVM 用例未变。本轮另外执行 `testDebugUnitTest --rerun-tasks`，37 个任务实际重新执行，BUILD SUCCESSFUL。本轮软件回归合计 131 个测试，不代表 Phase 9 整阶段通过。
- APK 仍为 0.8.0 / versionCode 6，数据库版本 3；本轮只补验证与采样工具，没有冒称新版生产功能。
- Lint 0 errors、11 warnings、1 hint。构建出现 SDK XML 工具版本兼容提示，不影响本次成功，但不隐瞒为无工具警告。
- 原生引擎对 53,066 字节四页真实 PDF 连续渲染 100 次：80 次 800×1100、20 次 1280×1700；PAGE/WIDTH 与不同滚动位置交替。检查准确可见窗口位图分配，PAGE 采样为非空内容；测试用例在每帧 finally 回收位图。
- 预热后文件句柄初始/最终/采样最大均 109，未随这 100 次增长。模拟器引擎耗时中位数 25.501 ms、P95 42.299 ms、最大 246.527 ms，包含 hash/打开/渲染；不是 UI 翻页响应或真实 Pad 延迟。
- 测试进程 PSS 每 10 次采样 203,902–249,114 KB，包含测试框架与此前用例，不能当作独立 PDF 引擎内存；未以此宣称无泄漏或长期内存通过。主动回收测试位图也不证明 Compose 页面生命周期。

原始结果在 `app/build/reports/device-acceptance/emulator-render-metrics.json`；采样工具烟测在同目录 `emulator-tool-check-*`、`emulator-final-tool-check-*`。目录中的名称只是场景标签，是否为真机、是否已安装应用须读 device.json，不能按名称推断。测试/JVM/Lint 报告继续使用标准 app/build/reports 路径，后续运行会更新。

## 继续非真机检查

2026-10-09，用户明确暂不做真机后，新增以下三个测试。应用功能、数据库结构与版本号未修改，现有 UX 待办未提前实施。

1. ComplexPdfAcceptanceTest：用 Android PdfDocument 生成并由 Native PdfRenderer 打开的 24 页混合栅格/密集矢量 PDF，含 600×900 和 600×1400 两种源尺寸；文件 4,042,013 字节。绑定只允许第 2–23 页，1280×1700 窗口重复渲染 66 次（44 次 PAGE、22 次 WIDTH/不同滚动）。PAGE 帧做非空像素和页面坐标往返检查，全部帧核验窗口位图分配为 8,704,000 字节。第 1/24 页明确拒绝，不能越出绑定。
2. CatalogScaleAcceptanceTest：真实 Room 内存数据库，7 名成员、250 首曲目、500 个曲目声部和 500 个绑定，管理目录生成 1,750 个成员×曲目 Domain 投影；管理员未自动获谱，单曲 II 授权不扩散到别曲，撤权保留实际分配并明确 UNAUTHORIZED，不回退默认 I。PDF/版本为 1 组元数据夹具，不打开该文件、不声称测过 250 份实际 PDF；没有节目单和缓存规模负载。
3. ReaderCancellationAcceptanceTest：真实 NativePdfEngine + PdfReaderViewModel，连续 30 次交替 Fit/窗口尺寸请求（间隔 3 ms），等待最终 PAGE/1280×1700 的第 2 页；静置 200 ms 后旧渲染不能覆盖最终 frame，再翻第 3 页和 detach。检查文件句柄增长上限；不是完整 Compose/长期内存验收。

最终重新运行 `testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest`，BUILD SUCCESSFUL：29 个 Android 14/API 34 模拟器测试实际运行，0 失败/错误/跳过；既有 105 个 JVM 用例未变，本轮 Gradle 复用上批已强制重跑通过的结果（UP-TO-DATE）。当前有效软件套件共 134 个测试，不声称 105 个 JVM 本轮重新执行。

Lint 0 errors、11 warnings、1 hint。SDK XML 工具兼容提示仍存在。APK 0.8.0 / versionCode 6，数据库版本 3，既有 schema/迁移未改变，禁止 destructive migration。本轮首轮新增 2 项通过后，加入取消测试并再次跑全套，最终 29 项全部通过；未发现需要改写生产实现的新增失败。

最终一轮观察值（与第一轮/第一批不混用）：

| 项目 | 结果 | 范围限制 |
| --- | --- | --- |
| 混合 PDF 渲染 | 中位 132.860 ms，P95 203.375 ms，最大 296.397 ms | 包含 hash/打开/渲染，非 UI 翻页耗时，不设虚假 SLA |
| 混合 PDF 文件句柄 | 初始 109 → 最终 109 | 短时测试观察，不证明所有生命周期无泄漏 |
| 目录计算 5 次 | 332.397、246.974、260.179、230.484、223.189 ms | Room 内存库/后台元数据计算，非磁盘、网络、NAS 或 UI 帧时 |
| 快速切换取消 | 最终 frame/源页正确，旧结果不覆盖，随后翻页/detach 正常 | 30 次请求和短时观察，不证明长时间排练与物理设备表现 |

原始结果：`app/build/reports/device-acceptance/emulator-mixed-pdf.json`、`emulator-catalog-scale.json`。测试详情在标准 Android 报告和 XML 的三项用例。PDF 是合成真实格式文件，不是用户的复杂扫描原谱；测试位图由用例主动回收，仍不能推断 Compose 显示生命周期内存。目录结果说明有限规模业务正确性，尚未给全表快照/成员×曲目投影性能上限盖章，批量授权和更多版本/节目项需追加评估。

## 用户真实扫描谱专项

2026-10-09 用户提供私有扫描谱，原文件 39 页、9,181,191 字节、A4、未加密；SHA-256 为 `bef7856b8eaa0a11e2b0b5bfc87b9e314d6055f89eb2e559d6c1add258ec70c9`。只用于本地隔离模拟器，不加入 APK/assets、sources/ 或公开内容。

新增 ExternalPdfAcceptanceTest 使用可选 instrumentation 参数从固定临时路径读取外部样本；普通全套回归默认使用既有四页夹具，不依赖用户私人文件、不跳过用例。针对扫描谱独立执行 `privatePdf=true / privatePdfPages=39`，实际结果 OK (1 test)，耗时 52.549 秒。原生引擎渲染绑定第 2–38 页，共 148 帧（37 页 × 2 种窗口 800×1100/1280×1700 × PAGE/WIDTH），核验位图尺寸、可见非空内容、PAGE 坐标往返，绑定外第 1/39 页均拒绝。第 1/39 页未作为授权内容渲染，不声称检查全部 39 页的显示质量。

指标在 `app/build/reports/device-acceptance/private-scan-engine.json`。这是引擎专项，不是 Compose 全流程、真实声部分配或生产分发验收。测试页段用于边界验证，不表示原谱第 2–38 页属于同一声部；完整多声部母 PDF 不能据此宣称适合将来直接下发成员。原图扫描清晰度仍受源分辨率限制。另用桌面渲染抽查第 20 页，内容可见；不作为 Android 截图证据，图片不进入报告。

## 磁盘、导入提交与持续 UI 检查

本批新增 ImporterCommitAcceptanceTest 两项：真实 content URI 导入后，Room 外键提交失败时事务回滚且暂存文件删除；进入不可取消提交区后取消调用方，已提交的 activeVersion 仍引用存在且长度正确的文件。不覆盖复制过程中取消、进程强杀、磁盘耗尽或断电；孤儿文件清理风险仍保留。

DiskCatalogAcceptanceTest 使用独立磁盘 Room 数据库：7 人、150 曲目、300 版本、300 乐章、450 绑定、120 节目项、150 缓存元数据。关闭/重开后核验 activeVersion、乐章/节目顺序、个人批注和撤权；撤权后的批注读取明确拒绝。实际文件路径是元数据夹具，不打开或下载 150 份谱。最终一次目录计算观察 145.3651 ms，不是冷启动磁盘性能 SLA；数据库重开不等于系统杀进程恢复。指标见 `emulator-disk-catalog.json`。

PedalConcertScreenTest 新增真实 MainActivity/Compose/Room/native PDF 的断网持续阅读：五首小测试谱、反复跨曲往返、保存个人笔画、普通/大窗口切换和 Activity 重建。最终运行 180,444 ms、530 次翻页、17 次重建；回到原页的个人批注不变，演出中无普通底部导航、常亮保留，退出恢复导航。FD 初始/最终均 143，26 次 PSS 采样 198,582–246,171 KB，含测试框架/既有用例；不能宣称所有内存生命周期无泄漏。不是 3 分钟扫描谱 UI 压测，也不是等长真实排练或进程死亡测试。指标/截图见 `emulator-ui-soak.json/png`，截图实际检查非空、谱面与页码一致、无内容覆盖。

本批实跑发现并修复测试同步缺陷：第一次点击落在渲染/批注短暂禁用期间；后续等待只检查曲目未核验源页，可能读取未完成加载的批注；节目单详情异步加载可能还没有 item 节点；演出布局变化触发新渲染时立即截图捕获加载指示器。修复为等待按钮可用、当前源页批注非空、详情节点出现和实际谱面出现；截图依然有 30 秒期限及非空断言，原有失败用例没有忽略、删除或降低内容阈值。前三次全套分别出现 1、2、1 个失败，修复后完整重跑 34 项，0 失败/错误/跳过。生产应用逻辑未改，不能以这些测试同步修复宣称已解决 UX 待办。

## 阶段结论

最终验证：`connectedDebugAndroidTest lintDebug` BUILD SUCCESSFUL，34 个 API 34 设备用例 0 失败/错误/跳过；`testDebugUnitTest --rerun-tasks` BUILD SUCCESSFUL，37 个任务实际执行，105 个 JVM 用例 0 失败/错误/跳过；最后 `assembleDebug lintDebug` BUILD SUCCESSFUL。当前独立套件共 139 项，外部扫描谱使用同一用例的额外专项参数运行，不重复加算为第 140 项。Lint 0 errors、11 warnings、1 hint，既有 SDK XML 兼容提示、两个冗余注解目标和一个 Input 图标弃用编译警告未隐藏。APK 0.8.0 / versionCode 6、数据库版本 3、schema/迁移及生产代码未改变。私人样本原文件的 SHA-256 在测试后再次核验一致。

当前新增真实扫描谱引擎、磁盘重开、导入提交和 3 分钟 UI 检查，本批非真机用例通过，Phase 9 本身仍未完整通过。按用户最新要求，只使用显式指定的隔离 emulator-5580，不触碰物理 Pad。真实扫描谱完整 UI 长时/进程回收、物理笔/脚踏/大 Pad 和真实排练仍待验；不能因暂不做真机而删掉通过条件。既有 UX 待办、孤儿清理、规模查询优化和生产认证边界仍保留，没有自动进入 NAS 阶段。
