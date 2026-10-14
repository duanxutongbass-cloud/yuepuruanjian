# Android 授权预览与阅读器闭环验收（2026-10-14）

## 当前范围

从 PR #5 的 `3198524149c4f0a0139c1487e18e1477556474d3` 继续，已将 `ScorePreviewViewModel` 接入真实 MainActivity：选择左侧曲目，由 Domain 解析当前成员的实际声部，校验本地 PDF/hash/页段并渲染首个授权源页；可直接打开同一曲目/声部阅读器，返回保留搜索与选中项。整体使用低饱和深色工作区、窄导航、列表选中态及右侧真实白色谱页；节目单和阅读器沿用原生导航及演出入口。

这是用户随后明确授权的不依赖原图的功能集成。**原图仍不可读，第三版精确视觉匹配和独立产品验收尚未通过。** 先前被退回的浅色网格已替换，不再是本轮 APK 的首页；没有把文字目标当作自己已看过参考像素，也没有用网页或 JVM 假 PDF 冒充 Android 实现。

## 基线与边界

分支 `feat/android-paper-library-v3` 从审核后的 `6e39372e185e4238ae243726d16d4f56fc55ba81` 派生；本轮远端 main 再核验为 `32b702e6019b4a8f4471634163d3844b28f03d65`。继续同一草稿 [PR #5](https://github.com/duanxutongbass-cloud/yuepuruanjian/pull/5)，base 是批注 R2 分支。没有使用整理工具 PR2/3/4、没有改 local-manager、没有合并/发布/部署。

版本 0.9.6 / code 8 / Room schema 3 不变，无迁移。批注存储、串行保存、失败重试、重新鉴权前隐藏内存草稿、编辑锁/演出冻结语义未改。没有新增云服务强依赖、生产登录或 NAS 同步声明。测试只用合成成员、PDF 和批注。

已读取仓库架构、产品、交互及开发要求；checkout 内未发现 AGENTS.md 或适用的本地技能。

## 实现与资源生命周期

- 首页搜索/选择状态提升到导航与临时 Loading 状态之外，使用 `rememberSaveable` 恢复；成员变化重置，失效/过滤掉的曲目不继续预览。
- `ScreenRequest.Preview` 复用 `ScoreResolver.resolvePiece`，UI 不重写分配与权限规则。阅读器仍重新走 Domain 鉴权。标题目录可见规则沿用基线，搜索不是授权边界。
- 预览只渲染首个授权源页，位图最多 1200×1800；不虚构封面、页数或离线可用标记。缺文件、权限/配置错误均清屏并提供真实错误；支持重试。
- 成员/曲目/视口变化、离开曲库、暂停 Activity、撤权/版本投影更新，均隐藏旧预览；bindingId、generation、target 和取消检查拒绝迟到结果。点击阅读器时再检查是否仍是当前 Ready 实例。
- `PreviewImage` 管理 ViewModel 所有权与 Android ImageView 持有的租约。旧图片停止接受新租约；Drawable 脱离后释放最后引用再回收。未发布的迟到/取消帧也释放。没有把仍在视图中的 Bitmap 提前 recycle。
- 实际 API30 窗口测试发现并修复搜索键盘遮谱，以及暂停/恢复事件在停止重组期间合并导致未重新绑定的问题。恢复计数确保每次重新鉴权；生命周期收集缓存中的退役图片不能再显示。新增同一次 UI 调度内暂停/恢复的 JVM 回归。
- `AcceptanceDependencies` 仅位于 debug source set，供明确启用 `isolatedTestDevice=true` 的合成模拟器测试取得真实 Hilt Room 单例；不进入 release，不增加用户入口或权限绕过。

## 本轮验证

| 验证 | 本轮结果 |
| --- | --- |
| Debug APK / 测试 APK | 构建成功 |
| 完整 JVM / Robolectric | 168 项；0 失败 / 0 错误 / 0 跳过 |
| Lint | 0 错误 / 10 警告 / 1 提示 |
| 实际 MainActivity UI | **1 项通过，0 失败/跳过**；Android 11/API30，183.694 秒 |
| 受影响 Android 回归 | **6 项通过，0 失败/跳过**；API30 原生预览 1、PDF 引擎 3、导出恢复 2，77.464 秒 |
| JVM 自适应截图 | 800×1200、1280×1800；属于布局证据，不是原生 PDF 渲染证据 |
| 物理 Pad / 笔 / HID | 没有连接，未执行 |

168 是本轮最终完整 JVM 批次，包含批注、权限、阅读器、HID、导入导出等既有回归，以及预览释放、迟到渲染、导航返回/恢复的新断言；不与旧批次相加。实际 App UI 完整用例覆盖：列表选择→授权原 PDF 第 2 页→同声部阅读器→全屏/恢复→返回保留查询与选择→Activity 暂停清屏/恢复重新鉴权→真实 Room 撤权清屏/恢复授权→缺文件/重试→节目单/重复导航→无权限成员拒绝。最终批次未在运行中进行手工输入干预。测试启动 MainActivity，使用真实 Hilt/Room/Domain/NativePdfEngine，截图由 UIAutomation 取得整个 App 窗口，区别于前一批无 Activity 的 PDF 引擎边界测试。

工具：Linux、Gradle 8.13、JDK21、compile SDK36。无 `/dev/kvm`，Android 14 AOSP x86_64 AVD 使用 TCG 软件执行。前一批 UI 测试在冷启动/运行中改尺寸及重建窗口后遇到 System UI ANR，曾中止，不能算通过。本轮固定已启动的 800×1200 / 160dpi，避免把冷启动和尺寸切换混进首次 UI 路径；虽然 `dumpsys activity lastanr` 未返回记录，后续完整截图发现残留的 Quickstep 未响应对话框，事件日志确认 01:08:22 为 `com.android.launcher3` 的 `TouchInteractionService` 执行超时（早于本次 App 测试）。通过可见“Wait”按钮处理并保留截图/事件日志，未把它计作 App 崩溃。首轮测试另因模拟器指纹大小写判断被跳过，已修正为实际 Android/sdk + ranchu/goldfish 匹配，跳过记录不计通过。随后仍被 System UI 对话框阻挡，首个真实 App 批次在成员选择处因 Compose 无法 idle 失败；原始失败保留，不计通过。模拟器日志持续出现 `Failed to find ColorBuffer` / `invalid color buffer`，据本地 `emulator -help-gpu` 将旧 `swiftshader_indirect` 改为受支持的 `swiftshader`，保留 AVD 数据重启后继续验证。图形调整未解决 API34 多个启动服务超时；后一次尝试另发现合成名称的唯一约束冲突，改用每轮唯一名称，不删除旧数据。转用经官方 SHA-1 校验的 Android 11/API30 AOSP 镜像；待系统启动完成、通过可见 Wait 按钮处理启动 ANR 后，真实 MainActivity 成功渲染了预览/阅读器/返回页。该次明确在暂停后恢复等待预览处超时，是 App 恢复绑定缺陷，已修复并新增 JVM 回归；键盘遮挡同时修复。失败与修复前截图保留于 evidence/android-paper-ui/observations-before-fix.md。以上不宣称修复 Android 系统问题，没有修改安全设置。

## 参考文件状态

- 原 PNG：`libfile_3f9535d6b6508191b43943af019336bd`，direction-03.png，175900 字节。
- 三版 ZIP：`libfile_5488e080ed1c8191a985d70c4d5b1d04`，yuepu-three-homepages.zip，598349 字节。
- 审核方提供预期 PNG SHA-256 `126c936213fca75ba796e277339c368a9c51b3e74868de80512f37f3702d3567`，本环境未下载成功，未本地核验 hash/像素。
- 支持的 Library materialize/helper 路线返回网络代理 `Tunnel connection failed: 403 Forbidden`。没有绕过代理；image_file 仅提供资产指针、没有可检查像素。
- 可读原图仍是精确视觉匹配的前置条件；用户已明确它不阻塞本轮原生功能闭环。

## 证据与复现

[首页](evidence/android-paper-ui/app-home-preview.png)、[阅读器](evidence/android-paper-ui/app-reader.png)、[全屏](evidence/android-paper-ui/app-reader-fullscreen.png)、[返回选择](evidence/android-paper-ui/app-return-selection.png)、[撤权](evidence/android-paper-ui/app-revoked.png)、[节目单](evidence/android-paper-ui/app-concerts.png)、[节目单阅读器](evidence/android-paper-ui/app-concert-reader.png)、[未授权成员](evidence/android-paper-ui/app-member-without-grant.png)均为最终真实 App 窗口截图，已查看。完整 [App UI 原始结果](evidence/android-paper-ui/actual-app-ui-final.txt)与 [6 项原生回归](evidence/android-paper-ui/native-affected-final.txt)单列。

最终 App 截图、测试原始输出、JVM/Lint 报告和 APK 及 SHA-256 一起放入本轮交付包；仓库只保留精简截图与验收记录，不提交 APK、SDK、缓存或签名私钥。

构建：`gradle assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest`。SDK36 Robolectric 需要 JDK21；本环境另配置可写 JVM 缓存和代理，不是 App 网络依赖。

隔离模拟器 App 验收：`adb shell am instrument -w -r -e class com.ensemble.scorelibrary.ui.PaperWorkspaceAcceptanceTest -e isolatedTestDevice true com.ensemble.scorelibrary.test/androidx.test.runner.AndroidJUnitRunner`。不得在私人安装上启用该合成数据测试。

前批 `31985241` 的 163 JVM、1 个无 Activity 原生边界用例及 UI 中止记录是历史证据，不能替代本轮完整 App UI 结果。旧 `jvm-library-*.png` 为被退回的浅色布局，`native-authorized-page.png` 仅为引擎渲染证据。

## 安装与剩余验收

禁止通过卸载已有 App 绕过 Debug 签名冲突；既有曲谱和批注必须保留。本环境 Debug 证书未证明匹配旧安装，本轮只在隔离合成模拟器 `install -r`。

剩余：API34 软件 AVD 完整 UI 验收（系统 ANR 未解决）、原图实际读取和精确视觉核验、独立产品审核、物理 Pad/笔/掌触/无线 HID、OEM 与长期排练；不将本轮软件通过说成全部设备验收通过。
