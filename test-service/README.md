# 临时安卓联网闭环

这是同一测试运行边界内的合成服务，不是 NAS/商业认证系统。只绑定 `127.0.0.1:8765`，不公开部署；两个账号 `alice`（Violin）和 `bob`（Cello）的分谱由程序各自独立生成，没有母 PDF 或真实资料。服务端为每次请求重新验证会话、设备与实时授权，客户端不能传成员 ID 扩权。

## 启动与手动操作

需要 Python 3、JDK **21**、Gradle 8.13、Android SDK 36、build-tools 35/36 和 adb。

```sh
python3 test-service/server.py --runtime /tmp/score-test-run
# 另一个终端：仅查看本次合成账号的临时登录码；不要提交 runtime 目录。
cat /tmp/score-test-run/login-codes.json
adb reverse tcp:8765 tcp:8765
gradle :app:assembleSyncTest
adb install -r app/build/outputs/apk/syncTest/app-syncTest.apk
adb shell am start -n com.ensemble.scorelibrary.httptest/com.ensemble.scorelibrary.MainActivity
```

1. 在 App 输入测试账号和本次登录码，点击登录。显示清单来自实际 `/login` 和 `/manifest` HTTP 请求。
2. 点击「下载本人分谱」，等待「字节与 PDF 校验通过」。点击曲目后使用既有预览与阅读器。
3. 返回曲库，**在运行服务的终端按 Ctrl-C 关闭服务**，再次进入已下载曲目的阅读器。不要修改防火墙、TLS 或系统联网设置。
4. 服务停止后，新登录应失败；当前进程中已在线登录的有效会话仍可读已入库授权谱。进程退出后需重新在线登录，不是永久离线凭据策略。
5. 重新启动服务生成全新的临时团队、身份与码。没有创建真实账号或长期访问密钥。

测试 APK 的包名为 `com.ensemble.scorelibrary.httptest`，与旧安装并存，独立私有存储且禁止备份。不要卸载或清除用户旧安装；本轮没有验证旧签名兼容性。`debug`/`release` 构建保持原入口，无法启用该临时 HTTP 适配器，也不包含回环明文许可资源。

## 自动验证

```sh
python3 -m unittest discover -s test-service -v
gradle :app:testDebugUnitTest :app:lintSyncTest :app:lintRelease \
  :app:assembleSyncTest :app:assembleDebug \
  -PtestBuildType=syncTest :app:assembleSyncTestAndroidTest
adb install -r app/build/outputs/apk/syncTest/app-syncTest.apk
adb install -r app/build/outputs/apk/androidTest/syncTest/app-syncTest-androidTest.apk
python3 test-service/run_android_acceptance.py --output /tmp/score-http-evidence
```

自动验证仅允许明确标记的模拟器，在 App 输入实际临时登录码，操作两账号、损坏下载、中断/Range 重试、换版、撤权与重新授权。宿主关闭 HTTP 服务后，App 验证服务不可达，再打开原生阅读器并截图。宿主控制通道是 adb 测试标记，不存在任何 HTTP 管理/关服后门；临时登录码只用于此测试进程，不写入报告。

`http-audit.json` 记录合成账号请求和续传字节偏移，不记录登录码或会话令牌。`instrumentation.txt` 为原生测试原始结果；两张 PNG 分别为授权下载完成和服务关闭后的真实原生阅读器。服务代码只依赖 Python 标准库。

## 适配边界

- 复用 PR6 在 `b2ea782` 的纯 Kotlin 核心与测试；核心只协调状态，不充当认证或网络证据。实际网络、校验、Room 提交、接收确认均由本轮适配器执行。
- 下载私有 `.partial` 按团队/账号/设备/内容哈希隔离；Range 重试仍服务端鉴权；SHA-256、长度、原生 PDF 每页检查成功、文件同步/改名后才在 Room 事务切换可用版本。重复提交不会重复建版本，旧文件与原版本批注保留。提交前还会请求当前授权确认。
- 仅接受 `independentSource=true` 且完整零基顺序、identity 页映射的独立原始分谱。原件就是本次独立配送 PDF，因此现有源页/批注版本语义可直接保持；其他裁切/重排衍生映射明确拒绝，未宣称支持母谱拆分。
- 收到新清单先应用撤权，再尝试资源更新；下载失败不能恢复旧权限。账号切换先隐藏旧会话，绑定新账号后才显示库，联网包没有成员下拉认证替代入口。
- 读谱时隐藏同步/切号控制，保留现有离开及批注保护。没有修改冻结演出、HID、节目单或导出实现。没有上传任何个人批注。
- 数据库仍 schema 3，版本仍 0.9.6/code 8；临时演示不引入账户后台、商业离线时限、NAS 接入或多租户生产承诺。
