# Android 纸面工作区切片验收（2026-10-14）

## 当前结论：第三版视觉未完成、未通过

用户视觉审核已退回首个浅色工作区/白色卡片切片。第三版实际目标是**整体低饱和深色工作台、窄侧导航、左侧曲目列表及选中态、右侧大型白色真实授权谱页、顶部搜索及当前成员**。白纸指 PDF 乐谱纸面，不是曲目卡。当前 APK 仍包含被退回的初始 UI，只能作为中间验证包，不能称为第三版交付。

本轮已完成可构建的 Android 结构和真实 PDF 预览数据层准备。按用户“先在本环境实际读图，再实现”的要求，视觉集成及“列表选择→原生 PDF 预览→打开阅读器”的 UI 闭环仍等待原图本地可读；预览 ViewModel 尚未接入首页。没有拿网页原型代替 Android，也没有把收到的文字审核当作自己已看过像素。

## 基线与边界

分支 `feat/android-paper-library-v3` 从审核后的 `6e39372e185e4238ae243726d16d4f56fc55ba81` 派生；main 核验 `32b702e6019b4a8f4471634163d3844b28f03d65`。草稿 [PR #5](https://github.com/duanxutongbass-cloud/yuepuruanjian/pull/5) 以批注 R2 分支为 base，隔离 UI diff。不使用整理工具 PR2/3/4，不修改 local-manager，不合并/发布/部署。

版本 0.9.6 / code 8 / Room schema 3 不变，无迁移。批注存储、串行提交、失败重试、重新鉴权前内存草稿隐藏、编辑锁/演出冻结语义未改。没有增加联网依赖、生产登录或 NAS 同步声明。

仓库与 checkout `.agents/skills` 没有发现 AGENTS.md 或相关本地技能；已读取 DEVELOPMENT_RULES、ARCHITECTURE、PRODUCT_DESIGN、INTERACTION_REVIEW、INTERACTION_FIX_REQUIREMENTS 和现有实现。

## 参考文件读取阻塞（源文件未丢失）

- PNG：`libfile_3f9535d6b6508191b43943af019336bd`，direction-03.png，175900 字节。
- ZIP：`libfile_5488e080ed1c8191a985d70c4d5b1d04`，yuepu-three-homepages.zip，598349 字节。
- 审核方给出的 PNG 预期 SHA-256：`126c936213fca75ba796e277339c368a9c51b3e74868de80512f37f3702d3567`。本环境没有成功下载，因此**未在本地复核此 hash 或像素**。
- 使用原 ID、原始文件名/版本身份、明确自身 `/workspace/design-reference` 与 `/workspace/design-reference-v3` 目的地，按支持的 materialize/helper 路线有限重试。结果是网络代理 `Tunnel connection failed: 403 Forbidden`。未使用另一环境路径或绕过代理拒绝。
- image_file 读取只提供资产指针与不可用 OCR，没有可检查的像素。已请求另一个可读取的原图链接。只有这个读取前置条件满足后，才能按要求继续视觉实现。

## 已完成的软件准备

- 初始自适应导航、曲名/作曲检索、清除、曲名正序/倒序、列表/网格切换及成员隔离；这些初始布局**不符合第三版视觉**，后续需替换。
- 节目单检索、打开演出谱入口；阅读器横滚工具与固定演出入口，普通导航在阅读/全屏时隐藏。
- 新增 `ScreenRequest.Preview`，复用 `ScoreResolver.resolvePiece` 决定当前个人声部，不在 UI 重写默认/分配/权限，也不在失败时猜测另一个声部。
- `ScorePreviewViewModel` 观察 Room/Domain 变更，调用真实 PdfEngine 校验文件/hash/页段并渲染首个授权源页；单张位图上限 1200×1800，不创建虚构缩略图、页数或可用标记。
- 成员/曲目切换立即清空旧帧；撤权/版本等投影更新先隐藏旧帧，再等待旧渲染取消；generation 与 target 双重检查拒绝迟到的异步结果。缺文件、权限/配置错误和一般失败分别提供真实状态与重试。
- 目录仍沿用基线的未归档标题可见规则；声部、PDF 和批注需要 Domain/仓储授权，搜索不是安全边界。

## 本轮验证（独立计数）

| 验证 | 结果 |
| --- | --- |
| Debug APK / 测试 APK | 构建成功 |
| 完整 JVM / Robolectric | 163 项；0 失败 / 0 错误 / 0 跳过 |
| Lint | 0 错误 / 10 警告 / 1 提示 |
| JVM 界面截图 | 800×1200、1280×1800；已查看；记录的是被退回的初始 UI |
| Android 14 软件模拟器 UI | 两项计划用例中一项已开始、取得普通平板曲库截图；System UI ANR 干扰后中止，无完成通过的 UI 用例 |
| 原生 PDF 预览边界测试 | 1 项通过，0 失败；Android 14 AOSP 软件模拟器，86.744 秒 |
| 物理 Pad / 笔 / HID | 没有连接，未执行 |

163 是最终完整 JVM 批次，不与旧 130+40、批注 152 或中途 158/定向 11 项相加。新增测试覆盖查询恢复、成员隔离、排序、旧路由结果、重复请求、窄屏导航，以及实际个人声部解析、未授权拒绝、首个源页、位图边界、撤权隐藏、缺文件和不可取消旧渲染返回。原生 `ScorePreviewNativeTest` 使用真实 Room/Domain/PdfRenderer 与合成 PDF，单独核验文件缺失/重试/撤权；已实际完成并取回真实渲染像素，不用 JVM 假图冒充它。

工具环境：Linux、Gradle 8.13、完整 Temurin JDK 21、compile SDK 36 / Build Tools 35.0.0。无 `/dev/kvm`；模拟器只能 TCG 软件执行。Google APIs 镜像首次启动优化耗时后，改用 Android 14 AOSP 镜像；出现 System UI ANR，点击等待后仍未得到完整 UI 结果，故中止并保留证据。不是物理设备验证，也不是断言 App 崩溃。

已诊断并解决 Java 17 无法运行 SDK36 Robolectric、JVM home 下载锁只读及 Compose 作用域编译问题。环境代理/缓存与安装工具均在工作区，不进入仓库或应用。

## 证据与复现

- [JVM 普通平板](evidence/android-paper-ui/jvm-library-800.png)、[JVM 大屏竖屏](evidence/android-paper-ui/jvm-library-1280.png)：初始 UI，视觉未验收。
- [Android 原生授权第 2 页渲染](evidence/android-paper-ui/native-authorized-page.png)、[原生边界测试记录](evidence/android-paper-ui/native-preview-test.txt)：对应当前代码的真实 Room/Domain/PdfRenderer，属于预览数据层证据，不是首页视觉闭环截图。
- APK：`app/build/outputs/apk/debug/app-debug.apk`；测试 APK：`app/build/outputs/apk/androidTest/debug/`。
- JVM：`app/build/reports/tests/testDebugUnitTest/`；Lint：`app/build/reports/lint-results-debug.html`。
- 构建/回归：`gradle assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`，SDK36 Robolectric 需要 JDK21。本环境另为测试 JVM 定向了可写缓存与代理，普通可写环境不需要复制这些环境路径。
- 中间交付包包含最终提交身份、APK 校验值、软件报告、模拟器原始记录与截图，不包含私谱、秘密、签名私钥或构建缓存。

## 安装与剩余验收

**禁止通过卸载已有 App 绕过 Debug 签名冲突。** 本环境生成的 Debug 证书未证明匹配旧 APK，已有本地曲谱和批注必须保留；需先确认签名并验证导出备份。仅在本轮隔离模拟器使用合成数据安装测试。

剩余：可读原图及本地 hash/像素核验；第三版原生整体深色列表/真实谱页预览 UI 集成与返回/取消/重复点击/旧结果场景；节目单/阅读器视觉复核；完整模拟器回归；物理 Pad、笔/手掌、无线 HID、OEM/长期排练。当前结论不能写“第三版已完成”或“全部验收通过”。
