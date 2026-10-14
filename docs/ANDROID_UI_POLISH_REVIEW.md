# Android 冷灰纸面 UI 精修验收

继续同一草稿 PR #5，起点 `156751b279b715adec41b2b9ab3335986f56d266`；main 与批注 R2 远端仍分别为 `32b702e`、`6e39372`。版本 0.9.6 / code 8 / Room schema 3 不变。没有修改 local-manager、同步、数据层、预览 ViewModel/位图所有权或批注交互。

## 精修范围

独立审核方已查看第三版 PNG 与上一轮 App 截图，并给出本轮测色与结构要求。本环境原 PNG/ZIP 仍因受支持下载通道的 403 未取得像素；本轮依审核方明确要求实施，不声称自行查看原图，也不绕过访问限制。第三版整体一致性仍交独立产品审核。

- 工作区 `#232831`，侧栏 `#1D222A`，预览面板 `#2C3440`，选中 `#3B4C65`，搜索 `#303844`，PDF 白纸保持原像素。
- 合并顶部产品/曲库标题，当前成员与实际已授权声部同区呈现。长文本限制行数并省略，不虚构声部。
- 常驻离线大横幅改为“离线 · 说明”按钮；点击可查看完整本地数据与权限说明，再关闭。没有声称 NAS 已实现。
- 右侧显示所选实际曲名、声部，打开阅读器按钮与标题并排且保留独立宽度；左列表/窄侧栏/真实 PDF 结构不变。缺文件、撤权错误仍出现在谱页区，禁止打开。
- 阅读器只继承冷灰主题，保留谱面全屏和演出入口。

## 证据定义

`evidence/android-ui-polish/before/` 为上一轮 `156751b` 的真实 800×1200 全窗口截图。新截图全部使用唯一合成成员/声部/曲目和合成 PDF；本轮刻意加长标题，故前后截图文字不同。测试不清空数据库、不卸载应用，只归档自己的测试实体。

完整 App 用例继续验证：输入搜索后选曲收键盘、授权源页、打开同声部阅读器、全屏、返回保留查询和选中、暂停清屏/恢复重新鉴权、真实 Room 撤权/恢复、缺文件禁用及重试、节目单入口、无授权成员拒绝；新增离线说明展开关闭、关闭后谱页仍显示且选中不变、长标题下按钮可见且可点击。

API34 的历史系统 ANR 未由本轮精修解决，不能宣称通过；物理平板、笔、掌触、真实 HID 未执行。JVM 布局截图与模拟器原生截图分别标注。

## 本轮发现与修复

首次原生精修用例在选曲后持续加载。原因是新增 Ready 声部标题改变了谱页高度，尺寸绑定清除 Ready 后标题又消失，形成重渲染循环。主动停止该测试进程（原始输出因此为 Process crashed，非通过），保留 `interrupted-resizing-preview.txt` 和 `observed-preview-resizing.png`。固定曲名与声部各两行的布局高度，空/失败状态保持空白声部，不泄露旧授权内容；只调整 UI，不改预览 VM。JVM 生命周期用例加入长文本和就绪后不再重新绑定的断言。停止仅针对隔离测试应用，没有清库或卸载。

## 最终结果与交付

代码提交 `81e20f3`。最终 Debug/AndroidTest APK 构建成功，完整 JVM 168 项通过（0 失败/错误/跳过），Lint 0 错误、10 警告、1 提示。API30 800×1200 完整真实 App UI 用例通过，190.949 秒；原始结果见 [ui-final-800.txt](evidence/android-ui-polish/ui-final-800.txt)。

[本轮首页](evidence/android-ui-polish/native-800/app-home-preview.png) / [阅读器](evidence/android-ui-polish/native-800/app-reader.png) / [上一轮首页](evidence/android-ui-polish/before/app-home-preview.png)。其余离线说明、返回、缺文件、撤权、节目单及全屏截图在同一 native-800 目录。首页两条合成曲目中一条来自主动中止用例保留的隔离测试数据；没有混入私人曲谱。两个实际窗口均 800×1200，已查看长文字、按钮和真实 PDF。

1280×1800 仅有本轮 JVM 布局截图，本轮未做原生大窗验收。按用户最新收敛要求，不再因大窗/API34 追加不稳定测试循环。API34、物理 Pad/笔/掌触/HID 均未通过本轮验收。第三版整体视觉匹配待独立审核。

独立附件：
- [测试 APK](https://chatgpt.com/api/library/files/libfile_8020beb56f2c81919716310109649408/download)，20,131,495 字节，SHA-256 `29cd8d427760ae7c35aa2191d5542e1bb03d508be1da310b4ae9fa8c5a132847`。
- [实际首页 PNG](https://chatgpt.com/api/library/files/libfile_6f67718953308191b7bc4c5d2d77de75/download)。
- [实际阅读器 PNG](https://chatgpt.com/api/library/files/libfile_b9053f0190688191bb3b5b57d9795dfc/download)。

Debug 签名未确认与既有安装一致。不要卸载旧 App 来绕过签名冲突；保留既有曲谱和批注。本轮仅在隔离合成模拟器 `install -r` 验证，不宣称用户设备可原地覆盖。

受影响 API30 原生回归最终 **6 项通过，0 失败/跳过，70.555 秒**：真实预览 1、NativePdfEngine 3、导出恢复 2；见 [native-final.txt](evidence/android-ui-polish/native-final.txt)。测试批次分别计数，不与历史结果相加。
