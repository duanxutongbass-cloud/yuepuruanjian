# Pad test build 0.9.6

Current device operation guide. Authoritative state: PROJECT_STATE.md; management evidence: PHASE8_REVIEW.md; preparation/offline evidence: PHASE7_REVIEW.md; HID evidence: PHASE6_REVIEW.md; annotation evidence: PHASE5_REVIEW.md. Paths beginning app/ below are relative to the project root, not docs/.

0.9.6 adds independent fullscreen, native zoom, reading swipes, reversible edit lock, a visible symbol palette and in-page management actions. 0.9.5 backup/recovery remains available. Final acceptance and exact test counts are maintained in PROJECT_STATE.md and the relevant review, not duplicated here. Physical Pad/pedal acceptance remains pending.

## First Run

1. Install `app/build/outputs/apk/debug/app-debug.apk` on Android 8.0 or newer.
2. Open My/Profile and choose Create Administrator. Administrators open Management Center from this page; there is no separate management bottom tab.
3. Optionally add a member; this does not change the current administrator.
4. Enter a new piece title and part name, select the recipient, and enter a one-based inclusive page range. A blank end page means the last PDF page.
5. Optionally enter a concert title; an existing active concert with that title receives the piece at its end. For a shared-annotation test with another member, explicitly enable “同时授权当前管理员”; it is off by default and grants only this piece.
6. Select a PDF through the Android system document picker and wait for the result.
7. Open the profile tab and select the recipient if it differs from the administrator.
8. Open the piece in the library, select its visible part, and test reading, previous/next, fit modes, rotation, and returning to the library.

## Boundaries

- This is a local test build, not production authentication. Anyone with access to the local member picker can select the administrator.
- The quick-import tab creates a NEW piece with one whole-piece binding. Other management tabs edit existing pieces/parts/movements/bindings, immutable versions, permissions/assignments and concerts. Do not use quick import to replace an existing version.
- By default only the selected recipient receives piece-scoped permission and assignment. The optional administrator checkbox explicitly grants that same piece to the current administrator, without a global part permission or permission bypass.
- Imported files are copied into private managed storage. The source document is not modified or required after import. No persistent external URI permission is necessary.
- PDF limit: 256 MB. Encrypted or unreadable documents and invalid ranges are rejected.
- System-paired HID key input is implemented; physical Bluetooth pairing/reconnection remains unverified. Dedicated BLE/GATT protocols and permanent reading history are not implemented. On API 26–28, zero vendor/product HID devices are not guaranteed to be recognized.
- Installing over the previous build with the same signing key preserves Room data. Uninstalling or clearing application data removes local records and PDFs.
- Physical Pad testing remains necessary, especially large/complex PDF memory use and document providers.

## 管理端实测

1. 选择管理员后打开管理；“导入”保留原快速流程，其他标签分别管理曲目、成员、标准声部、节目单和 PDF 版本。普通成员无管理目录，管理员目录也不自动授权看谱。
2. 在标准声部新增定义，在曲目详情新增声部和谱段；起止页为一基闭区间。整曲和乐章谱段不能混用，每个有效乐章需要完整绑定才能正常解析。多步编辑未完成时显示业务错误，不回退其他谱。
3. 编辑成员默认声部，再分别勾选全局/单曲授权，选择实际演奏分配并保存。默认、权限和实际分配是三件事。撤销最后一个权限后应明确拒绝原分配，不能静默改奏默认声部；撤权不删除覆盖标记。
4. 在节目单追加曲目，调整启用/归档与上下顺序。归档通过“含归档”重新编辑恢复，保留原记录。排序需避开归档占位；上下移动可自动重排全部槽位。不能归档仍被启用节目项引用的曲目，或最后一位启用管理员。
5. 在 PDF 版本详情替换文件并确认，新版必须覆盖所有启用绑定页段。验证新文件打开、旧批注不出现在新版；回滚旧版后旧批注应仍存在。损坏/短页数/错误归属的版本不能激活，旧文件不删除。
6. 锁定节目单后不能修改节目项、引用曲目结构或 PDF 版本；需另建新曲目/新节目单。锁定不是永久授权，成员撤权或显式改分配仍触发 Domain 失效/重验。
7. 测试普通 Pad 单栏详情返回和大 Pad 列表/详情分栏、旋转、长文本、空目录、错误保留表单，以及打开文件选择器后切换成员。目录更新后旧表单不能覆盖新记录，应关闭后重新编辑。

## 批注实测

1. 看谱页先选择“我的批注”，用手写/荧光图标作标记，切换颜色和笔宽；每次完成动作后等待保存进度结束。
2. 用套索圈选批注后自动进入移动，拖动或删除选中；工具区显示选中数量。橡皮删除整条对象。测试撤销、重做，再退出并重新打开确认批注仍在。
3. 常显音乐符号按钮打开分类面板，选择力度、渐变、变音或演奏记号，在谱面点按放置；同样可套索移动或擦除。这不是手写识别。
4. 用“我的”“共享”复选框独立隐藏/显示。切换阅读工具后再拖动 Fit Width 谱面；不要把阅读拖动与写画混为一个模式。
5. 测试前后页、Fit Width/Page、旋转和分屏；确认标记始终对齐源 PDF 页面，其他页没有串入。

## 全屏、缩放与编辑锁定

1. 从曲库打开已授权谱，点全屏图标：应只剩谱面及当前可见批注，无应用顶部/底部/页码/批注工具，不要求冻结节目单。点按页面恢复工具，系统返回先退出全屏。
2. 阅读模式左右滑动翻页，第一页/最后一页不能越授权页段；放大后拖动移动谱面、不翻页。用双指或放大按钮调整 1–4 倍，再用复位按钮回到正常比例。测试缩放后标记对齐、不同尺寸和配置恢复。
3. 点“锁定编辑”后工具禁用但可以阅读、翻页；点“解除编辑锁定”后可编辑自己的批注，不删除数据，也不解冻共享演出版本。
4. 管理员曲库页可直接导入，节目单页可直接新建，详情可编辑；“我的”里有管理中心/成员与声部授权。全局声部勾选覆盖所有相应曲目，例外与实际分配独立，普通成员不显示管理动作。

## 共享发布与演出快照

1. 管理员导入新的测试曲目，授权普通成员，显式勾选“同时授权当前管理员”，并加入一个新的独立测试节目单。
2. 管理员从曲库打开这份谱，选择“共享草稿”，编辑后点发布当前页图标。发布是逐页的，未发布的草稿不对普通成员可见。
3. 切换到普通成员，打开同一曲目；应只看到已发布共享内容，不能编辑共享层，也不能看到管理员个人批注。
4. 切回管理员，从该节目单进入看谱，点“冻结本场共享版本”并确认影响。管理员需要对节目单所有启用曲目具有真实权限，否则 Domain 明确拒绝，不绕过授权。这与可解除的“锁定编辑”不同。
5. 回曲库修改并重新发布共享层，再从节目单打开；演出仍显示锁定时的内容，包括当时没有批注的空页。演出共享层只读，个人层独立。
6. 当前版本没有覆盖/解锁旧演出快照的入口；需要改变锁定节目时创建新演出。若 PDF、页段或启用节目单变化，应看到明确错误，不静默使用旧批注。

## 脚踏与连续演出实测

1. 在 Android 系统蓝牙设置配对键盘模式/HID 脚踏；非 HID 厂商 App/GATT 模式不在当前范围。到“我的”开启脚踏；系统蓝牙设置按钮只是跳转，不在 App 中扫描。
2. 选择默认映射或当前外接设备。下一页默认 Page Down/右或下箭头/Space，上一页 Page Up/左或上箭头，Enter 切换演出流。也可依次点学习并按脚踏；冲突时先清除冲突动作的映射。
3. 打开按键测试，确认收到的设备、键名和动作；测试不会翻页。调整防抖，测试长按只触发一次、释放后正常触发。关闭测试，退出设置再进入曲谱。
4. 准备至少五首新测试曲目，用曲库导入依次追加同一个未冻结节目单，全部明确授权并分配给测试成员。管理中心支持完整编辑/重排；不要试图追加已冻结节目单。
5. 管理员先在该节目单阅读页冻结本场共享版本，普通成员再从第一首进入，确认曲名、实际声部和源 PDF 页码。点“准备正式演出”图标或对应脚踏，等待整场准备检查通过后编辑工具消失、屏幕常亮；未冻结只能普通阅读/排练，正式入场会明确拒绝。普通全屏与这个动作独立，不要求演出检查或冻结。首次隐藏系统栏可能出现 Android 自带引导，确认一次后再测试。
6. 只用脚踏推进五首。当前曲最后一页进入下一曲第一页，不弹确认；第一页向前进入上一曲最后一页；全场首尾只有弱提示，不能循环。触屏翻页按钮也使用同一连续游标。
7. 写画期间按脚踏应不切页，松手且保存结束后再次按才翻页；不能在保存结束时突然补执行。断开脚踏后触屏仍可阅读，重连后确认设备映射与键值。
8. 旋转/分屏后确认成员、曲目、页、适配模式和演出流保持。退出看谱后脚踏不应干扰普通导航或文本输入；配置保存后重开 App 仍应保留映射。
9. 用独立测试数据检查下一曲文件缺失/损坏：明确报错，不跳到再下一曲。若节目单/版本改变，明确停读，显式重新打开。不要删除正式演出文件来模拟故障。

本地成员选择只是测试身份入口，不是生产鉴权。无线延迟/断连重连、压力、掌拒、厂商触控和长时稳定性仍需实机验证；NAS、云端同步和多租户尚未实现。

## 正式演出与离线实测

1. 按上面步骤导入整场并锁定共享批注。管理员须有全部个人演出谱的真实权限，锁定不自动授予任何成员权限。
2. 关闭网络连接后进入正式演出。准备会逐曲检查文件、源页、版本及每页批注；中途取消不应在稍后自动进入，也不补执行准备时按过的脚踏。
3. 用独立测试文件模拟缺失末曲：第一曲仍可普通阅读，但正式入场须提前报告末曲缺谱，不是演到中途才发现。不要删除正式文件做破坏性测试。
4. 就绪后全程按键演出，确认共享批注已锁定、管理员草稿不出现、屏幕保持亮起。退出正式演出/返回普通页面后常亮应解除；厂商省电模式的实际效果须真机确认。
5. 旋转或重建窗口后重新检查，再恢复原曲、页与模式。当前是会话恢复，不保证强杀后永久阅读历史。
6. 准备通过不是永久保证：之后文件/批注损坏应明确报错并退出正式演出，不自动跳曲或读旧版本。阅读刷新在准备/正式演出中禁用，退出后恢复。
7. 本地检查最多 10,000 个授权页；大规模检查时间、复杂 PDF、长时间耗电/内存和真实无线仍待 Phase 9，不把上限当作性能认证。
