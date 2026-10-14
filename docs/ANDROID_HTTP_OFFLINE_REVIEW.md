# Android 临时 HTTP 与离线闭环验收

状态：未通过本轮端到端验收；代码与构建交付完成，真实 App 闭环被模拟器系统故障阻塞。2026-10-14。

## 基线与改动

独立分支 `integration/android-http-offline`，从 PR #5 `1bfa0d1c5db40ddbdbdb063cf2c429ad0d327b6e` 派生，包含既有批注补丁；明确复用 PR #6 `b2ea782d94f2876f474e1fd8f14c0663b6cd7c08` 的 `SyncModels.kt`、`SyncStateCore.kt` 与状态测试，未修改内核原件。没有使用 PR2/3/4 或修改 local-manager，没有合并 main。

实现提交：`442a7cf67771db9e65cd0529da9167853ee06d66`。草稿 PR：[#7](https://github.com/duanxutongbass-cloud/yuepuruanjian/pull/7)。

本轮实际代码：回环合成服务、随机运行期登录码/会话、逐请求设备及授权检查、本人清单、独立分谱实际 HTTP 下载及 Range 重试、SHA-256/长度/原生 PDF 校验、文件同步/改名与 Room 原子提交、当前账号 UI 绑定以及既有库/阅读器入口。临时会话仅保留在进程内，冷启动必须在线认证。仅支持独立原始 PDF 的完整 identity 页映射，拒绝其他衍生映射，不用换 managedFilePath 冒充母谱拆分。

版本 0.9.6 / code 8 / Room schema 3 不变，无迁移。隔离测试 APK `com.ensemble.scorelibrary.httptest` 与原安装并存，禁止备份。正式 debug/release 不启用临时登录，不含测试网络许可；明文仅 syncTest 的 `127.0.0.1`。不触碰用户密码、NAS、真实曲谱、系统网络安全设置；不做批注上传。

## 已完成实测

- 200 JVM：0 失败、0 错误、0 跳过，含原基线 168 项、PR6 状态内核 29 项和清单安全解析 3 项。使用 JDK21；环境最初缺 SDK/Gradle、只有 JRE，已在工作区补齐。早期 JDK17/Robolectric 及只读下载目录失败已修正后完整重跑。
- 6 服务端测试：使用真实回环 HTTP，而非内存调用替代请求；覆盖不同账号清单、错码/未认证、猜资源/改 memberId、设备绑定/撤销、撤权后的旧清单/Range/提交拒绝、实际 PDF hash/size、版本切换与 Range 字节。
- `assembleSyncTest`、`assembleSyncTestAndroidTest`、`assembleDebug` 成功。
- Lint：syncTest 0 错误、12 警告、1 提示；release 0 错误、11 警告、1 提示。没有通过屏蔽 Lint 绕过错误。测试域名配置缺少 `includeSubdomains` 的错误已修复重跑。
- [构建/JVM/Lint 摘要](evidence/http-offline/build-jvm-lint.json)、[服务端原始测试输出](evidence/http-offline/service-tests.txt)。状态核心测试和 JVM 画面均不作为安卓联网证据。

## 原生验收状态

本轮原生测试 **0 项执行，不能记为通过**。没有 KVM；API30 Google APIs x86_64 软件模拟器首次启动后系统死亡，安装报 `Can't find service: package`。单核/1200×1600 限定重启仍出现系统 `DeviceStorageMonitorService` 空指针并丢失 package 服务。最后改用 API30 AOSP x86_64，系统及 settings 提供者曾就绪，主 APK 确实安装成功，但安装测试 APK 时系统再次死亡（`DeadSystemException` / `Failure calling service package: Broken pipe (32)`），一次当前设备上的安装重试仍报 package 服务不存在。随后停止模拟器，不继续换镜像或无限重试。

原始证据：[首次 Google APIs 崩溃](evidence/http-offline/google-api30-first-crash.txt)、[限定重启存储服务异常](evidence/http-offline/google-api30-retry-crash.txt)、[最后 AOSP 系统死亡](evidence/http-offline/aosp-api30-crash.txt)、[原生尝试结果](evidence/http-offline/native-attempt.txt)。这些是环境失败证据，不是 App 登录/下载/离线通过证据。

以下均 **未跑**：真实 App 登录→本人清单→下载→关闭服务→离线阅读；安卓端账号隔离、失败/Range 重试、旧版本与笔迹保留、重复入库及撤权；适用 API30 的既有 NativePdfEngine、ScorePreviewNative、ArchiveRecovery 原生回归。`AnnotationScreenTest` 本身要求 API31+，不应在 API30 假报通过。服务端/JVM 中相应已通过检查不替代这些原生项。

自动脚本 `test-service/run_android_acceptance.py` 与 `HttpEndToEndTest` 已编译，设计为从真实 MainActivity 完成上述流程；宿主关闭 HTTP 服务后才允许 App 打开现有阅读器，额外验证 Activity 恢复及服务停机后的新登录拒绝。尚未取得实际执行结果。关键两张原生截图没有产生，未上传任何替代截图到 Library。

继续验收所需的是一个稳定可运行的 Android 模拟器/设备，不需要用户的新账号、NAS 权限或持久凭据。本轮“实际安卓闭环”的唯一目标因此仍未完成。
API34、物理设备、旧安装 debug 签名兼容性未验证；不要求用户卸载或清数据。此临时适配不是生产认证、商业离线时限、NAS 联网或完整多租户系统。

## 交付

启动与自动验收命令见 [test-service/README.md](../test-service/README.md)。APK SHA-256：`3c15c8e6b8676e81a25e120f71ed0eebdc2ba1fa7e042705174497db9dd6a4ac`。

Library 已确认保存 `score-http-test-442a7cf.apk`：`libfile_d1f913dc235c8191801328f162a7e977`；底层文件 ID `file_00000000c84881fda875ab37c41d70b0`。Library 当前版本 1。原生两张关键截图未产生，不虚构路径或 ID。
