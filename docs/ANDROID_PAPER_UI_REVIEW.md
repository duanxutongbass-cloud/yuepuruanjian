# Android 纸面工作区切片验收（2026-10-14）

## 结论与范围

原生曲谱库结构切片已实现，APK / 测试 APK 可构建，157 项 JVM 全部通过。精确第三版视觉与模拟器验收尚未完成，不能宣称整轮通过。只推进 Android；不修改 local-manager，不依赖 Windows/NAS，不发布/部署/合并。

本轮从远端 `feat/annotation-reliability-phase10-r2` 的 `6e39372e185e4238ae243726d16d4f56fc55ba81` 派生 `feat/android-paper-library-v3`；远端 main 核验 `32b702e6019b4a8f4471634163d3844b28f03d65`。不是整理工具 PR2/3/4 的衍生。0.9.6 / versionCode 8 / Room schema 3 不变，无迁移。

## 参考读取边界

Library 解析到 `libfile_3f9535d6b6508191b43943af019336bd`（direction-03.png）与 `libfile_5488e080ed1c8191a985d70c4d5b1d04`（yuepu-three-homepages.zip）。按已解析 ID 走 materialize，当前 helper 下载失败，明确工作区目的地重试仍失败。image_file 读取只返回图片指针和不可用的 OCR，没有可见像素。没有成功物化 PNG/ZIP，未声称查看参考或读取源码。

因此这里只落实用户文字明确的深色侧导航、浅色工作区和白色卡片，以及不依赖附件的原生结构/交互；精确版式、字体、比例、谱面预览等视觉匹配待附件解阻。白色卡片没有伪造 PDF 缩略图。不是网页原型。

仓库及 checkout `.agents/skills` 未发现 AGENTS.md 或相关本地技能；已读取 DEVELOPMENT_RULES、ARCHITECTURE、PRODUCT_DESIGN、INTERACTION_REVIEW、INTERACTION_FIX_REQUIREMENTS 与批注 R2 实现。

## 实际变更

- `ScoreTheme` 提供本地视觉令牌；不染色真实 PDF 像素或批注。
- 窗口 <600dp 底部导航、600–1199dp 紧凑侧导航、>=1200dp 宽侧导航；阅读与全屏均移除普通导航。
- 曲谱库原生卡片/列表、曲名及作曲检索、清除、默认/曲名排序；查询与布局按成员隔离并支持状态恢复。
- 搜索仅消费既有 Domain 目录。基线允许显示未归档曲目/节目单标题，不能把这称作新的权限过滤；声部、PDF、批注访问仍由 Domain/仓储重新验证。
- 节目单原生检索及卡片，详情提供“打开演出谱”；真正入场仍走原有准备与冻结检查。
- 阅读器深色标题栏；可横滚的适配/缩放工具与固定可见的演出/专注入口。PDF 不受普通内容最大宽度限制，全屏最大化保留。
- 未修改 AnnotationViewModel/仓储与 Domain；保留串行保存、失败重试、重新鉴权前内存草稿隐藏、编辑锁与演出冻结区别。没有新增云服务、生产登录或 NAS 同步声明。

## 本轮验证（独立计数）

| 验证 | 结果 |
| --- | --- |
| Debug APK / AndroidTest APK | 构建成功 |
| JVM / Robolectric | 157 项；0 失败 / 0 错误 / 0 跳过 |
| Lint | 0 错误 / 10 警告 / 1 提示 |
| JVM 原生截图 | 800×1200、1280×1800；已查看，中文清晰、导航和卡片未重叠 |
| Android 模拟器 | 执行中，尚未计为通过 |
| 物理 Pad / 笔 / HID | 未执行；没有连接物理设备 |

使用 Linux、Gradle 8.13、完整 Temurin JDK 21、Android compile SDK 36、Build Tools 35.0.0。Java 17 的首轮 Robolectric 因 SDK36 要求 JDK21 失败；只读 home 引发下载锁失败也已修复，最终 157 项不包含这些失败启动计数。工具链代理/缓存配置仅在工作区，不进入应用或仓库。不要累加旧 130+40 或批注 152。

新增覆盖搜索/清除、网格切换、查询恢复、成员切换移除旧行、路由变化后的旧异步结果、重复请求、窄屏导航。既有完整 JVM 套件覆盖批注串行与失败恢复、权限、HID 门禁、Room、节目单及备份恢复；JVM 通过不替代物理笔/无线输入验收。原生模拟器新增 `PaperWorkspaceAcceptanceTest` 使用合成投影与真实 `PdfRenderer`，覆盖两种竖屏、搜索→声部→PDF、全屏退出、返回、节目单重复点击与入口。

## 证据

- [JVM 普通平板截图](evidence/android-paper-ui/jvm-library-800.png)
- [JVM 大屏竖屏截图](evidence/android-paper-ui/jvm-library-1280.png)
- 构建产物：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK 在 `app/build/outputs/apk/androidTest/debug/`。
- JVM：`app/build/reports/tests/testDebugUnitTest/`；Lint：`app/build/reports/lint-results-debug.html`。
- 交付包将包含最终提交身份、APK SHA-256、截图与测试证据；没有私谱、秘密、签名私钥或构建缓存。

## 尚未完成

第三版 PNG/ZIP 本地读取与像素对照；模拟器运行结果；实际物理 Pad、触控笔/手掌、无线 HID、OEM/长期排练。Debug 签名为本环境生成，未验证与已有装机签名相同；不要通过卸载有用户数据的旧包来绕过签名不匹配，应先验证并导出资产。
