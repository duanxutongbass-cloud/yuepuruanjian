# 实际 App 验证发现（2026-10-14，修复前证据）

代码 9bfc854，Android 11/API30 AOSP 软件模拟器，800×1200。

- observed-ime-overlap.png：真实 MainActivity、Hilt Room、Domain 与 NativePdfEngine；合成成员实际声部的 PDF 第 2 页已渲染，但搜索键盘仍遮挡下方页面。
- observed-resume-stall.png：打开同声部阅读器、返回保留查询/选择均完成；随后 Activity CREATED→RESUMED，旧预览正确清空但未重新绑定，等待 180 秒失败。
- 修复：主动隐藏 IME；用恢复事件计数触发重新绑定，避免暂停/恢复在停止重组期间合并成相同布尔值；拒绝仍被生命周期收集缓存持有的已退役图片。
- 新增 PreviewLifecycleTest，断言同一次 UI 调度中暂停/恢复后重新鉴权并得到新帧。修复后完整 JVM 168 项通过，Lint 0 错误。最终 App 回归结果以 ANDROID_PAPER_UI_REVIEW 为准。

这些是发现问题的真实窗口截图，不是最终通过截图，不是第三版参考像素验收。
