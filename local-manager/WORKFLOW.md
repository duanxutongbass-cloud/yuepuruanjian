# Phase 10.1 可复审本地操作流程

这是 PM 已放行的离线核心 `4a00ba811dd6de56c3f158d444737fa317417295` 之上的独立最小批次。Python CLI 是可替换适配器，不决定最终 Windows UI 技术栈；本批没有修改该基线的核心文件或 Android 文件。

## 实际入口

先按 README 安装固定依赖。在仓库根目录用 `PYTHONPATH=local-manager python -m score_manager --db /绝对路径/workbench.sqlite ...` 调用。输入和输出均为 JSON；`--args` 必须是 JSON 对象。

1. `init --confirm-create`：仅创建新的空工作库，拒绝覆盖。
2. `begin`：返回 `draftId`，私有暂存目录为工作库路径加 `.drafts`。开始、编辑和预览不修改正式资产。
3. `edit DRAFT ACTION --request-id REQUEST --args JSON`：暂存操作。同一个 request-id、同一输入重试返回同一结果；不同输入复用该 ID 会拒绝。每个新操作使用新 request-id。
4. `review DRAFT --member MEMBER`：返回只读成员可见元数据及 `confirmationHash`。这里的成员参数是管理员模拟查看对象，不是登录身份。
5. `confirm DRAFT --member MEMBER --review-hash HASH --yes`：显式提交刚审阅的草稿；资产与提交回执在同一个 SQLite 事务中保存。重复确认返回原回执，不重复导入。
6. `status DRAFT` 查看 DRAFT/COMMITTED/CANCELLED；`cancel DRAFT` 取消未提交草稿，清除暂存 PDF BLOB 引用，正式库不变。取消不是安全擦除；已提交结果不能靠 cancel 撤销。
7. `export --owner ADMIN --output /新路径/bundle.zip --confirm-complete-bundle`：仅导出正式库的完整管理员备份，拒绝以草稿库作为正式库。完整包包含 `library.zip`、`catalog.json`、`README.txt`。
8. 在另一个空工作库中 begin，然后 `edit ... restore_archive --args '{"source":"/绝对路径/bundle.zip","expected_sha256":"实际文件摘要"}'`，review/confirm 后恢复。此流程不会覆盖非空库。

`import_pdf` 的参数例：`{"path":"/绝对路径/synthetic.pdf"}`；`add_category`：`{"code":"73","name":"用户分类"}`；`add_piece`：`{"title":"合成曲目","category_id":"分类ID"}`；`add_part`：`{"piece_id":"曲目ID","instrument_id":"字典ID"}`；`bind`：`{"part_id":"声部ID","pdf_id":"PDF ID","start_page":2,"end_page":3}`。页段输入为从 1 开始的闭区间，预览的 sourcePages 是从 0 开始的原页映射。

允许动作集中在 workflow.ACTIONS：成员/分类/乐器新增、CSV 字典导入、曲目和编号、声部/乐章/页段、PDF 单个或批量导入、开放包恢复。新增成员不自动获得权限，管理员也无隐式声部权限。权限/演奏分配编辑、已有资产归档及版本激活不属于本批 CLI。

## 草稿与确认规则

草稿保存正式库的一致快照、正式资产摘要、操作请求和所选源文件摘要。每次 review 以及 confirm 都重读所选源文件、校验暂存 PDF 内容和所有关系。confirm 获取草稿锁、正式库写锁后，再核对数据/文件摘要与预览摘要，并重查已有核心的冻结保护。数据变化返回 STALE_DRAFT，须开始新草稿；草稿编辑后旧摘要返回 STALE_PREVIEW，须重新 review。没有自动合并或自动重编号。

正式写入只接受记录动作对应的新增行和本地编号变更，不批量覆盖已有 Room 行、授权、批注或冻结载荷。开放恢复只能作用于空库。事务失败、取消、提交前进程退出均不留下正式新增资产；提交后进程退出由正式库中的回执辨认结果。草稿文件暂留以便状态/重试，不提供后台垃圾回收或跨机器草稿迁移。

本地 SQLite 草稿是管理员暂存材料，可能含完整母库，仅适用于受信本机管理环境，不可发给成员。CLI 不构成安全沙箱或认证服务。

## 成员只读预览

visibility.py 从现有 ScoreResolver.kt 的规则投影，不读写 PDF 文件：必须是有效成员；有效声部必须有全局乐器授权或精确到该声部的额外授权；默认乐器和演奏分配不授予查看权，管理员身份不绕过授权。无任何允许声部的曲目整体省略。

输出分别列出 `visibleParts` 和 `playing`，后者是分配优先/默认后备的选择结果，不等于前者。未授权的分配只返回错误码，不泄露目标声部 ID。每个允许声部仅返回绑定身份、精确 activeVersion 身份和该绑定源页索引；不返回母 PDF 路径、原文件 hash、总页数、字节或未授权页。

这是元数据模拟，不实现 PDF 渲染、切片或可安全分发的文件，不证明登录用户真的有权限，也不实现整场演出、冻结渲染或 Android 缓存选择。

| 现有 Domain 规则 | 新增回归证据 |
| --- | --- |
| 默认值不是授权、管理员无特权 | test_visibility：default/admin；test_workflow：九个动态成员 |
| 分配优先且无未授权后备 | assignment/ambiguous/dangling/default 测试 |
| 单曲额外权限隔离成员和其他曲目 | scoped_permission 测试 |
| 归档过滤、允许集合与演奏选择区分 | archived_entities、assignment 测试 |
| 精确 activeVersion、不以最高版本替代 | exact_active_version、version_ownership 测试 |
| 页段边界、乐章匹配/完整性/排序 | ranges、bindings、movement_order 测试 |
| 预览只读、不泄露母文件和未授权页 | pure_preview；DB 字节/iterdump 不变测试 |
| 确认时再次检查管理冻结规则 | frozen_guard、existing_frozen_operations 测试 |

这些是参照现有 Kotlin 源码/测试构建的 Python 回归，本批没有运行 Kotlin/Gradle/Android；不声称跨运行时实测等价。后续 Domain 更改须同步复核这个投影。

## 开放包边界和复现

完整本地包才恢复编号、分类和历史保留号。单独 Android v1 `library.zip` 可兼容恢复原母库，但结果明确标为 `ANDROID_V1_ONLY_NO_NUMBERING_CATEGORIES_HISTORY`，不能称完整恢复。本批保持原身份/版本/源页，不把个人层混入管理员母库。工作流回执和草稿属于本机操作状态，不进入开放资产格式。

从仓库根目录运行：

```sh
PYTHONPATH=local-manager python local-manager/tests/run_validation.py /tmp/new-validation
PYTHONPATH=local-manager python local-manager/tests/demo_workflow.py /tmp/new-cli-demo
```

demo 使用独立旧 v1 合成包和新空白 PDF，通过真正的 CLI 子进程完成导入→编号→绑定→预览→显式确认→重复确认→取消→完整导出→另一空库恢复；还验证缺少 --yes 拒绝、草稿不得导出、v1 子包恢复能力的差别。保存每条实际 argv/退出码/stdout/stderr 与结构化结论。demo 的 SQLite/草稿仅留在指定本地输出目录，不放入代码或交接包。
