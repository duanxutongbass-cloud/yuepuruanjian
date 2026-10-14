# 项目文档入口

2026-10-14 已按用户要求整合已有分支到本地 main，当前状态以 PROJECT_STATE 顶部合并状态为准。本次实际测试和环境限制见 [LOCAL_MERGE_REVIEW.md](LOCAL_MERGE_REVIEW.md)；下文测试数字及旧阶段状态为历史批次。

决策同步日期：2026-10-13。来源：主项目“产品设计方案”最新确认、当前 Work 的已执行构建与测试记录。

本目录是后续开发的唯一有效项目规范。历史审查报告只证明对应阶段当时的范围和结果，不能覆盖当前规范。新决策须先更新其所属文档，并同步项目状态，不能另建互相矛盾的方案。

最新顺序已同步：保留已有 Android，9.5 开放备份后进入 10.0–10.4，再进入 11/12；完整范围只在 PRODUCT_ROADMAP 维护。最初 0–5 总结不是另一套编号，不从零重做。当前基线 0.9.6 / 数据库 3 / 170 个通过用例。用户已新授权启动 10.0，确认 Windows 工具需新建、成员/声部自由维护、外网同步必需；NAS 条件待核验，未实现联网服务，真机仍暂缓。

| 文档 | 唯一职责 |
| --- | --- |
| [PROJECT_STATE.md](PROJECT_STATE.md) | 当前事实、在做什么、现有缺口、验证基线 |
| [PRODUCT_ROADMAP.md](PRODUCT_ROADMAP.md) | 最新阶段编号、范围和依赖 |
| [PRODUCT_DESIGN.md](PRODUCT_DESIGN.md) | Pad 交互与界面规则 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 数据、权限、存储和 NAS/商业演进边界 |
| [PHASE5_REQUIREMENTS.md](PHASE5_REQUIREMENTS.md) | Phase 5 功能及验收要求 |
| [PHASE6_REQUIREMENTS.md](PHASE6_REQUIREMENTS.md) | Phase 6 HID 与连续演出验收要求 |
| [PHASE7_REQUIREMENTS.md](PHASE7_REQUIREMENTS.md) | 正式演出、整场准备与本地离线验收要求 |
| [PHASE8_REQUIREMENTS.md](PHASE8_REQUIREMENTS.md) | 管理写入、版本替换、授权分配和节目单编辑验收要求 |
| [PHASE9_REQUIREMENTS.md](PHASE9_REQUIREMENTS.md) | 真机与性能范围、数据安全和验收边界 |
| [PHASE9_5_REQUIREMENTS.md](PHASE9_5_REQUIREMENTS.md) | 用户确认的母库/个人导出权限及恢复验收要求 |
| [ASSET_ARCHIVE_FORMAT.md](ASSET_ARCHIVE_FORMAT.md) | 开放 ZIP/JSON 格式、校验、恢复步骤和限制 |
| [PHASE9_5_REVIEW.md](PHASE9_5_REVIEW.md) | 本地资产恢复的实际测试证据与审查结果 |
| [PHASE10_0_REQUIREMENTS.md](PHASE10_0_REQUIREMENTS.md) | 新确认、工具盘点和联网细则的待冻结清单 |
| [TEAM_SYNC_CONTRACT.md](TEAM_SYNC_CONTRACT.md) | 发布、授权分谱、源页和同步接口边界草案 |
| [PHASE10_0_REVIEW.md](PHASE10_0_REVIEW.md) | 本次实际盘点、设计纠正与未核验项，进行中 |
| [PHASE10_1_LOCAL_REVIEW.md](PHASE10_1_LOCAL_REVIEW.md) | 获准独立实施的无网络本地核心候选、测试与未验范围；不代表完整 Windows 交付 |
| [PHASE9_DEVICE_CHECKLIST.md](PHASE9_DEVICE_CHECKLIST.md) | 普通/大 Pad 的实际设备验收记录矩阵 |
| [INTERACTION_REVIEW.md](INTERACTION_REVIEW.md) | 全流程人机交互审查、证据与限制，不代替阶段通过报告 |
| [INTERACTION_FIX_REQUIREMENTS.md](INTERACTION_FIX_REQUIREMENTS.md) | 实测八项反馈的 0.9.6 修复范围 |
| [INTERACTION_FIX_REVIEW.md](INTERACTION_FIX_REVIEW.md) | 修复、测试、截图证据和保留边界 |
| [UX_ITERATION_BACKLOG.md](UX_ITERATION_BACKLOG.md) | 后续交互改进建议、优先级和验收条件；未实施/未冻结 |
| [DEVELOPMENT_RULES.md](DEVELOPMENT_RULES.md) | 开发、验证、审查与阶段交付规则 |
| [PHASE_HISTORY.md](PHASE_HISTORY.md) | 阶段事实索引，不重复产品方案 |
| PHASEx_REVIEW.md | 当阶段实际改动、修复和验证证据 |
| [PAD_TEST_GUIDE.md](PAD_TEST_GUIDE.md) | 当前设备测试操作说明 |

已废止：PWA 替代原生 Android、物理英寸/固定像素布局、简单放大手机 UI、UI 自行判定权限、双 current 状态源、长期依赖外部 content URI、破坏性迁移、把完整管理/NAS/商业版提前混入核心演出阶段、发现问题即宣布阶段完成。

历史报告中“以后实现”“尚未实现”等句子以当时阶段为时间范围；今天的事实以 PROJECT_STATE 为准。不保留第二份旧规划。
