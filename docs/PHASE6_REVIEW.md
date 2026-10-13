# Phase 6 审查

日期：2026-10-09。结论：Phase 6 的 HID 输入软件与连续演出流范围通过；不是物理蓝牙脚踏/无线协议验收。按 PHASE6_REQUIREMENTS 实现、修复、再测试和复核，未进入 Phase 7。

## 实际交付

- 既有工程继续开发，APK 0.6.0 / versionCode 4，minSdk 26。数据库仍为版本 3，未修改既有表、迁移或 sources/。
- Android Activity 输入入口处理系统已经配对的 HID KeyEvent；不实现应用内蓝牙扫描、GATT 或未确认的厂商协议，不新增第三方蓝牙依赖。
- “我的”增加脚踏开关、设备配置、下一页/上一页/演出流切换的按键学习、冲突检查、测试模式、防抖滑杆、清除/恢复默认及系统蓝牙设置入口。
- 默认下一页为 Page Down、右/下箭头、Space；上一页为 Page Up、左/上箭头；Enter 切换演出流。自定义学习替换该动作的旧映射，不覆盖其他动作已有键。系统返回、电源、音量等键不可映射。
- 映射按设备 descriptor 的 SHA-256 身份保存；设备没有专属配置时使用默认配置。配置通过后台 IO 同步确认写入 SharedPreferences，重新创建 store/Activity 后仍可读取。保存失败保留旧映射，读取失败不擅自使用默认动作。
- 输入门禁处理按下/释放、repeatCount、重复 DOWN、取消事件及同设备同键 50–600 ms 防抖。离开前台清空按键状态，输入设备移除时清空该设备状态，断开消息不改变 PDF 阅读状态。
- 页面展示和 Activity 输入均只消费 Domain 的当前成员及完整 ResolvedConcert。ConcertCursor 不重排节目单、不选声部、不访问原始 Room 权限表。
- 当前曲最后一页进入下一曲第一页，当前曲第一页进入上一曲最后一页；跨曲不沿用目标曲旧页书签。全场两端弱提示，不循环、无跨曲确认弹窗。
- 演出流隐藏批注编辑栏，禁止笔画编辑并显示已发布/锁定共享层，管理员未发布草稿不渲染。普通底部导航保持隐藏；曲名、实际声部、曲目序号、源 PDF 页码及共享锁定状态可见。
- 曲目/页/适配模式/演出流状态使用 SavedStateHandle 恢复，按成员和准确节目清单隔离。新成员不会恢复前一成员的演出位置；不宣称是永久阅读历史。

## 已修复问题

1. 首轮 IntArray 设备列表转换与缺少图标导入引起的编译失败已修复。
2. Android 8/9 没有公开 InputDevice.isExternal；添加 API 判断。API 26–28 保守接受有 vendor/product 身份的键盘 HID，API 29+ 使用系统 external 标志；虚拟键盘用于自动化输入。旧系统零身份设备不保证识别，须实机确认。
3. Activity 公共 dispatchKeyEvent 覆盖遇到 AndroidX Core 继承桥的 RestrictedApi 提示；仅对此公共覆盖入口做定点注解并解释原因，没有关闭全项目检查或新增 baseline。
4. 演出清单、声部、页段、当前版本/hash 或文件位置变化立即清空旧 binding 并停读，必须显式重新打开。封住窗口变化、Fit 切换和脚踏可能重新渲染旧清单的入口。
5. 原生旧文件检查取消后不得把新会话标记为已检查；检查结果发布前验证取消及 generation。
6. 批注笔画 try/finally 维护 gestureActive；按键在笔画/保存期间消耗但不切页，也不排队稍后突然切页。测试配置捕获不执行阅读动作，普通页面和文本输入不抢翻页。
7. 学习指定设备时收到其他设备输入明确拒绝，不能误覆盖所选设备配置。输入数量及 JSON 大小有界。
8. 页面拖动随演出流状态重新绑定，避免模式切换使用旧手势闭包；读谱弱提示行保持单行高度，避免原生视口反复变化。
9. 设备测试初次全屏被 Android 首次使用提示占用焦点。测试环境确认系统引导后再发送按键；没有删除 App 的焦点保护、放宽断言或把直接调用 ViewModel 当作实际按键验收。

## 实际验证

运行 `testDebugUnitTest assembleDebug lintDebug connectedDebugAndroidTest`，最终完整执行成功：

- 76 个 JVM/Room 测试全部通过，失败 0；包含原 60 个回归及新增 16 个 Phase 6 测试。
- Android 14/API 34 AOSP 模拟器 18 个测试全部通过，失败 0、跳过 0；原 16 个回归加五曲真实 Activity 流程及管理员草稿隐藏像素测试。
- APK 构建成功。Lint 0 错误、11 个非阻塞警告和 1 个提示；主要为固定依赖的更新建议、KTX 写法建议与原有 Compose 装箱提示。没有以关闭 Lint 代替修复。
- Schema 仍为 3，既有 1→2→3 数据保留、权限拒绝、共享发布/锁定、导入事务与 PDF 错误测试继续通过。

核心 Android 用例使用真实 MainActivity/Hilt、Room、Domain、Native PdfRenderer 和实际 Activity KeyEvent 分发。先在设置中学习 F1/F2 并测试、确认配置存储，再进入五曲演出；曲目插入顺序故意打乱，Domain 按 sortOrder 排序。默认 Violin I，第二/第四曲具有单曲 Violin II 授权和实际分配，第三个未授权声部始终不存在于 UI。前者绑定 PDF 2–3 页，后者 3–4 页。

进入读谱后，演出切换及十页全程推进、末尾边界、反向跨曲均仅用按键，不点击翻页或曲目，不出现 App 确认窗口。每一步断言真实绑定的声部、版本、源页码和页面标签；窗口重建后仍为第四曲 Violin II 的 PDF 第 4 页和演出流模式，再由按键进入第五曲。退出后按键不重新打开阅读。新增像素测试确认管理员红色未发布共享草稿在演出流中完全不显示，退出后草稿仍保留。

JVM 另覆盖缺失下一曲不跳过、显式反向恢复、重复/长按/抖动、独立设备释放、取消、暂停清理、禁用、系统键、未知设备、学习冲突/错设备、配置失败、新成员隔离、版本/路径变化停读、窗口/Fit 不绕过停读和加载期间不积累输入。

## 可视审查

检查真实 Android 导出的 800×1200 和 1280×1800、density 160 截图及谱面非空像素：曲名、声部、页码不重叠；普通导航和编辑栏隐藏；顶部控制保持固定 dp 密度，PDF 按当前原生视口重渲染而不是放大旧位图。截图是测试尺寸，不意味着真实 20–27 英寸硬件验收。

- [普通 Pad](../app/build/reports/screenshots/pedal-standard-pad.png)
- [大 Pad](../app/build/reports/screenshots/pedal-large-pad.png)
- [APK](../app/build/outputs/apk/debug/app-debug.apk)
- [JVM 报告](../app/build/reports/tests/testDebugUnitTest/index.html)
- [Android 报告](../app/build/reports/androidTests/connected/debug/index.html)
- [Lint](../app/build/reports/lint-results-debug.html)

build 内产物会被后续构建替换，本文数字保留为本次阶段证据。

## 范围与剩余验收

本批次未发现阻塞软件范围通过的遗留问题。真实蓝牙无线配对、断连/重连、厂商键值/descriptor 稳定性、电量、延迟、旧系统零 vendor/product HID 与长时间复杂 PDF 仍须物理 Pad/脚踏测试，不能由虚拟 KeyEvent 推定。设备移除回调和清理已实现；无真实无线断连测试证据。首次全屏系统引导在真机可能需要用户确认一次，不属于跨曲确认。

当前脚踏协议限系统 HID。完整演出离线可靠性、预加载/复杂 PDF 性能、常亮策略及其他沉浸细节按 Phase 7/9 继续；生产登录、完整管理、NAS、商业版仍按原规划。当前不额外实现蓝牙专用库或服务端方案。

实现依据 Android 官方文档：[KeyEvent](https://developer.android.com/reference/android/view/KeyEvent)、[InputDeviceListener](https://developer.android.com/reference/android/hardware/input/InputManager.InputDeviceListener)。
