# 项目文档入口

2026-10-20 当前已发布0.10.28（40）：新增仅释放NAS中已彻底删除整首曲目的所选编号，其余曲目保持原号，详见[编号释放](NUMBER_RELEASE_0128.md)。管理员多选预览/备份确认、新旧身份隔离；App767/服务258通过，先发布再由用户本人更新验收。下方旧版本状态保留为历史。

2026-10-19 当前已发布0.10.27（39）：编号15逐点压感和可配置主/副侧键临时整笔擦除，App757/服务248项覆盖通过。NAS兼容服务27、当次备份/独立恢复、整包核验完成，真机由用户升级后本人验收，详见[手写笔27](STYLUS_0127.md)。下方各旧候选状态保留为历史。

2026-10-17 候选 **0.10.15（27）未上传、未发布**：自助注册→乐团码申请→管理员逐人审核已实现，旧账号/邀请码兼容；未加入账号不激活团队曲库，整页遮挡旧本机内容，服务器逐请求拦截。批准后仍明确逐PDF分配；单实例一个乐团，不涉及编号19多团队或收费。完整 Android **552项/93类**，0失败/错误/跳过，APK/AndroidTest/Lint成功（0错误/16警告，8m 36s）；服务 **103项通过，948.394s**，同原签名。客户端schema6保留，后端数据库升级2，需要新完整备份/独立恢复验证后升级NAS。正常TLS/无重定向公开清单仍22；管理登录接手事项未变化，本轮原标签及新标签绑定均超时，未取得NAS操作页面，未上传/部署。实际平板未测。见 [注册审核候选](PUBLIC_REGISTRATION.md)。

2026-10-17 候选 **0.10.14（26）未上传、未发布**：第 17 项原谱版本对比已完成，明确搜索选择两份获授权曲目/声部，按编号/分类/乐器核对，独立翻页、双谱对照及单侧放大；退出/后台/账号变化清空，逐侧重验源页/哈希/权限并丢弃迟到帧。完整 Android **535 项/90 类**，0 失败/错误/跳过，APK/AndroidTest/Lint 成功（0 错误/16 警告，8m 55s）；服务 **88 项通过，640.418s**，同原签名。 schema 6/后端/原 PDF/编号保留；实际 Android PDF 渲染及平板未测。最新正常 TLS/无重定向公开清单仍 22；沿用已记录的 NAS 超时和证书登录接手阻断，本轮未重试浏览器、终端或上传。见 [版本对比候选](VERSION_COMPARE.md)。

2026-10-17 候选 **0.10.13（25）未上传、未发布**：编号 15 基础输入模式、笔/手掌分离、系统取消回滚与可撤销笔尾整笔擦除已实现。完整 Android **510 项/86 类**，0 失败/错误/跳过，APK/AndroidTest/Lint 成功（0 错误/16 警告，8m 47s）；服务 **88 项通过，680.832s**，同原签名。 原 PDF/编号/schema 6/后端保留。真实 M-Pencil 等笔与普通/大平板未验收，压感/侧键待适配。NAS 文件管理操作和既有页面重新连接分别超时；没有选择文件或发送 25 APK，亦未重试终端。最新正常 TLS/无重定向公开清单仍 22，已有 23/24 候选与 NAS 文件保留。详见 [手写笔候选](STYLUS_INPUT.md)。

2026-10-17 候选 **0.10.12（24）尚未发布**：编号 14 本机账号加密最近阅读/收藏、冷启动页码、换版核验、当前目录筛选与继续声部、分别确认清空已完成。完整 Android **489 项/83 类**、服务 **88 项**及 APK/AndroidTest/Lint 通过（0 错误/15 警告），同原签名。线上本轮正常 TLS 核对仍 22；NAS 登录接手待答复，APK 已上传并核对大小/MD5，但尚未发布，未做新包公网下载。详见 [编号 14 候选](RECENT_READING.md)。

2026-10-17 候选：0.10.11（23）编号 11 未保存笔迹恢复已实现，完整 Android 453 项/77 类、服务 88 项及 APK/AndroidTest/Lint 通过。候选同签名 APK 已上传 NAS，但终端两次报 Socket 已关闭，另见管理入口证书错误；公开更新仍为 22，尚未发布和整包校验。需用户本人核对管理证书并登录，未绕过证书提示；详见 [0.10.11 候选](UNSAVED_INK_0111.md)。

2026-10-17 最新：0.10.10 本人文件夹/封面跨设备同步、离线修改与持久重试、双方冲突选择和过期编辑器保护已发布。Android 完整 405 项/72 类、服务 88 项通过；NAS 完整备份校验、新镜像健康、实际源码/APK 核验，版本 22 公网整包一致，11 私有读写入口未登录 401。物理平板由用户验收，详见 [0.10.10](FOLDER_SYNC_0110.md)。下一项编号 11 未保存笔迹恢复，公开注册仍待开发。

2026-10-16 上批：0.10.9 文件夹/封面独立 ZIP、导出回读/预览确认/冲突拒绝及团队个人文件夹已发布。381 项 Android、74 项服务回归通过，版本 21 公网整包一致，9 私有入口未登录 401；当时后端仍 0.10.7，无重启/库迁移。物理平板/实际选择器由用户验收，详见 [0.10.9](FOLDER_BACKUP_0109.md)。

2026-10-16 上批：0.10.8 下载管理已发布，逐份进度/搜索/暂停/重开续传与授权后原子入库；Android 359 项、服务 74 项通过，版本 20 公网完整下载一致，9 私有入口未登录 401。后端继续 0.10.7，无服务重启或数据迁移，物理平板由用户验收。详见 [0.10.8](DOWNLOAD_MANAGEMENT_0108.md)。

2026-10-16 上批：0.10.7 NAS 不可变节目单冻结、授权下发/离线恢复与有限在演场次已发布；Android 337 项、服务端 74 项通过，NAS 健康、版本 19 公网完整下载一致，9 私有入口未登录 401。原件/编号/历史保留，物理平板由用户验收。使用与边界见 [0.10.7](TEAM_PROGRAM_FREEZE.md)，当前编号见 [开发清单](NUMBERED_DEVELOPMENT.md)。0.10.6 的印章、上传与测试期诊断记录继续保留，详见 [0.10.6](BUGFIX_0106.md)。

2026-10-16 历史：0.10.3 已完成编号 6 批注跨设备同步、冲突选择及管理员只读查看已上传个批，277 项 Android 和 52 项服务端检查通过；NAS 已升级，APK 更新发布及公网完整下载校验通过，说明见 [批注同步](ANNOTATION_SYNC_0103.md)。

2026-10-16 上批：Android 0.10.2（14）已完成正式账号前台自动同步、六项平板反馈修复及 App 内检查/下载/系统确认更新。265 项 Android 与 44 项服务端回归通过，构建通过；NAS 0.10.2 已运行，安装包发布及公网完整下载校验通过。范围与未测边界见 [0.10.2 验证](APP_SYNC_0102.md)，当前编号见 [开发清单](NUMBERED_DEVELOPMENT.md)。以下旧版本和数量为历史阶段。

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
