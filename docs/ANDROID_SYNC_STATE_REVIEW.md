# Android 同步事务内核：独立产品审核

日期：2026-10-14。状态：内核准备，不是已完成同步。

## 基线与范围

- 精确父基线：`6e39372e185e4238ae243726d16d4f56fc55ba81`，R2 分支 `feat/annotation-reliability-phase10-r2`。
- 独立 worktree / 功能分支：`feat/android-sync-state-core`；草稿 PR 以 R2 为 base，不合并 main。
- 已读该提交的 `TEAM_SYNC_CONTRACT.md` 和 `PHASE10_0_REQUIREMENTS.md`。工作区/仓库未发现 AGENTS.md；`.agents` 无可用技能文件。
- 只新增 `domain/sync`、对应 JVM 测试、本文件。未改 Activity、Library/Preview/Reader、既有 Domain、Room/schema/版本/build、共享状态历史、local-manager 或并行 PR5 UI。

## 实现与调用契约

`SyncStateCore` 是纯 Kotlin、单 owner 线程的内存协调器。`receive` 接收完整替换清单；`next` 产出一次带 token 的待执行命令；外部适配器注入校验、提交、查询、确认结果。没有 Android、HTTP、文件或数据库调用。命令只表达意图，不是下载/授权/提交证据。

| 步骤 | 条件 / 输出 |
| --- | --- |
| MANIFEST_RECEIVED → VERIFYING | 协议 1、有效清单、当前会话和 revision；逐资源校验命令 |
| VERIFIED → COMMITTING | 注入证据必须与完整 artifact 集合一致；包含 artifactId、sourceVersionId、hash、size、页数和有序 pageMap/变换标识 |
| COMMIT_UNKNOWN → QUERYING_COMMIT | 结果未知/超时只查原 operationId；查询也未知则继续查询 |
| 查询已提交 | 保存 commitId 和完整集合，进入 RECEIPT_PENDING，不再次下载或提交 |
| 查询明确未提交 / 明确提交失败 | 保留原 operationId 与清单安全重试提交，不重新下载 |
| RECEIPT_PENDING → CONFIRMING → SERVICE_CONFIRMED | receipt 仅在已校验且匹配提交证据后生成；丢回包只重发同一 receipt |

同账号/设备的 operationId 记录跨本内核会话保留，同 ID 异清单返回 CONFLICT；重复请求不重复发命令。账号、设备、sessionId、单调 sessionEpoch、revision、operationId、逐次 requestNumber 共同隔离结果；A1→B2→A3 不接收第一次 A 回调。未知协议显式拒绝，不当空库。空集合必须是受支持协议下显式合法的完整清单。

传入和传出的集合均复制，调用方修改列表不能改变已接受事务。hash 等字段是外部校验器提供的标识，本内核不计算真实哈希，不检查 PDF 字节、隐藏资源或几何变换。

## 撤权、可读和个人数据边界

- 已收到 FORBIDDEN 按账号/设备锁定；DEVICE_REVOKED 按设备锁定。会话切换、旧 cursor、revision 或重试不能解锁；本批没有重新授权 API。
- 撤权后不产出新增校验/下载、提交、查询或确认命令。适配器必须与内核在同一 owner 线程串行运行，在执行排队命令前调用 `mayExecute(token)`；返回 false 丢弃排队工作。外部真实网络仍必须逐请求服务端鉴权，本内核不提供服务端安全保证。
- 已经发出的提交可能客观完成：当前会话的匹配回调可记录 commit 事实，但 permission 保持撤销，不能发 receipt，不能称撤权已回滚。旧会话回调不更新活动事务；跨会话事实对账尚待实现。
- 没有删除曲谱或个人笔画的命令，不改现有演出权限。已开始冻结场次可完成原则保留；不实现播放器策略、退出/后台/强杀连续性、首次登录解锁、期限、密钥或临时授权。
- 本地可读需要外部 `readabilityChecked` 的匹配会话/revision/commitId 打开检查；整场就绪另需 `readinessChecked` 的 programId、冻结 revision、证据 ID。确认成功不推断两者；本批没有实际执行这些检查。
- 个人 payload 标志进入 CONSENT_REQUIRED，无下载、提交、receipt 或上传；默认备份不替代知情同意。资源内核不实现个人上传、注册说明、管理员读取或 consent 校验 API，因此即便外部取得同意也应交给未来专用流程，不在此解除隔离。调用方必须正确分类数据，本模型不是个人数据检测器。

## 故障注入与限制

测试使用合成标识、内存 FakeStore 和 FakeTransport。FakeStore 用一次集合替换模拟原子提交，以原 identity/operationId 查询 journal；FakeTransport 模拟服务收到 receipt 但丢回包。测试不使用真实凭据、曲谱、个人数据、HTTP 或 NAS。

覆盖：成功链；清单后/下载/校验/提交前/提交后/确认前后断点；全部 artifact 字段和集合顺序错配；重复、异 payload 冲突、乱序、旧 revision、重试迟到；A→B→A、不同设备；撤权每个步骤、撤设备和迟到提交；未知协议、个人数据隔离；完整旧集合/新集合/显式空集合；输入输出防变更；独立可读和就绪事件。

明确未完成：真实资源下载/验证、Room 与文件系统跨资源原子提交、持久化 outbox、服务鉴权、进程强杀恢复、原生 PDF、模拟器/真机、UI 接线、NAS/部署。fake journal 仅存在内存，不能称持久化验证。

会话切换立即清活动状态。若同账号/设备已有未决提交或待回传 receipt，新的清单保守 BLOCKED；不静默丢弃或跨会话恢复。已越过提交边界的事务当前无跨会话恢复入口，需要后续经审核的持久化/会话重新授权适配器。内核实例生命周期内的幂等历史未设淘汰机制，未来持久化实现须补容量和回收策略。不能通过重建内核实例作为重新授权或恢复方案。

## 首轮验证记录（341c53a）

2026-10-14 最终复跑：`testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --max-workers=2`，日志结尾 `BUILD SUCCESSFUL in 53s`。26 个测试类 **172 项 JVM，0 失败、0 错误、0 跳过**，其中本批 `SyncStateCoreTest` **20 项全通过**。应用 APK 与测试 APK 构建通过。Lint **0 错误、10 警告、1 提示**，与 R2 审核记录数量一致；不在本范围修订既有警告。

环境：临时安装 Gradle 8.13、完整 Temurin JDK 21.0.8、Android SDK 36 / build-tools 35.0.0，代理与信任证书通过环境参数注入，无仓库构建配置改动。首轮 JDK 17 下新增 20 项通过，但既有 Robolectric SDK 36 要求 Java 21 导致全集失败；切换 JDK 21 后完整通过，并在最后调整测试事件顺序后再次完整复跑。未使用模拟器、未运行 connectedAndroidTest。

本工作区证据：`/workspace/android-sync-state-evidence/summary.json`、`jvm-results/`、`validation-final.log`、Lint XML/HTML/TXT。仅含合成测试与构建报告，不上传私有资料。

APK SHA-256（构建证据，不发布）：
- app-debug.apk：`7375d4095f35aeeee737e4ce7dc4e41bb12002bbf2373c26c97d14340a979b80`
- app-debug-androidTest.apk：`dff24193a11c900eb968bd4f7ad5c9833f68251305f7d887108553cb974f13c2`

提交后仅推送独立分支并创建以 R2 为 base 的草稿 PR，供独立产品审核。真实联网/本地原子提交/设备/原生回归及 UI 联调仍待后续验收。


## PR6 独立审核修复：前提交会话重绑

复现基线：首轮提交 `341c53abf1b5821bf1fbfdf06205ee5403692d9b`。先只增加测试，原实现下执行 9 项 `rebind*` 用例，6 项失败：MANIFEST_RECEIVED、VERIFYING、VERIFIED、VERIFICATION_FAILED 四种前提交状态的 A→B→A，同设备恢复，以及最新 revision 的恢复都返回 STALE。旧操作记录与 revision 高水位使没有新 revision 的资源事务停滞。撤权、已发提交边界、个人数据隔离用例在原实现已通过。失败日志与 XML 保留在证据目录 `rebind-red/`。

最小修复仅修改 `receive` 的已有 operation 分支：当前外部建立的会话重新收到完全相同的 operationId/清单，账号/设备相同、epoch 已更换、revision 仍为该身份最高 revision，且旧状态严格位于上述四项白名单时，显式创建新的活动事务。保留原 operationId/payload/revision，丢弃旧 pending token 和旧校验证据，从 MANIFEST_RECEIVED 重新开始；下一命令必为资源验证并使用新 epoch/requestNumber，不直接提交。重复接收为 DUPLICATE；异 payload 为 CONFLICT；已被更高 revision 替代的旧操作仍为 STALE。

不是 `commit == null` 判断：COMMITTING、COMMIT_FAILED、COMMIT_UNKNOWN、QUERYING_COMMIT、RECEIPT_PENDING、CONFIRMING、SERVICE_CONFIRMED 均不得重绑旧操作。CONSENT_REQUIRED 不在白名单，保持个人数据隔离；sticky 撤权先于重绑判断。不同设备拥有不同记录，不借另一设备的验证证据。此修复不建立新认证规则，不恢复已发送提交，不改变离线/进程恢复政策。

新增 9 项测试覆盖四种状态各自完成恢复链、同/异 payload、旧 session/token、A→B→A、撤权限/撤设备、不同设备、旧 revision 与全部提交后状态，以及 consent 隔离。最终重新执行完整 `testDebugUnitTest assembleDebug assembleDebugAndroidTest lintDebug --no-daemon --max-workers=2`，`BUILD SUCCESSFUL in 1m 29s`：26 类 **181 项 JVM，0 失败/错误/跳过**，其中内核 **29 项全部通过**；应用和测试 APK 构建通过；Lint **0 错误、10 警告、1 提示**。未运行设备测试、未连接真实同步服务。

修复后证据：`/workspace/android-sync-state-evidence/rebind-final/summary.json`、`jvm-results/`、`validation-final.log` 和 Lint 报告。应用 APK SHA-256：`df4bc0370602c148c4a1c37bdc1d5065e1b11f0b56764a48e3c3e9bfe18c318d`；测试 APK SHA-256 与首轮相同。修复追加至原功能分支及草稿 PR6，仍以精确 R2 基线为 base，不合并、不发布。
