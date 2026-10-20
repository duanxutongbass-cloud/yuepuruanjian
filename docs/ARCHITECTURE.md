# 架构与演进边界

## 当前 Android 基线

Kotlin、Jetpack Compose、Material 3、Navigation Compose、Room、Hilt、Coroutines/Flow、ViewModel。既有工程持续开发，不重建，不改成 PWA。

核心关系：Piece → Movement / PiecePart → PartDefinition；PartScoreBinding 关联 PiecePart、可空 Movement、ScorePdf 和页段；ScorePdf → ScorePdfVersion。

成员分开保存 role、defaultPartDefinitionId、MemberPartPermission、MemberPiecePartPermission、PiecePartAssignment。权限决定能看什么，默认/分配决定演什么，后两者不授予权限。管理员身份不绕过谱权限。

Concert → ConcertItem → Piece，按 Domain 排序并解析当前成员真正的个人演出谱。缓存以准确 PDF 版本和成员为边界。

## 数据与文件不变量

- 核心 ID 使用 UUID String；重要数据归档，非随意级联删除。
- ScorePdf.activeVersionId 是当前版本唯一来源；不得重新引入 isCurrent 双状态源，不以最大 versionNumber 或旧文件兜底。
- 保留唯一约束 `(scorePdfId, versionNumber)`、`(pieceId, partDefinitionId)`、`(concertId, sortOrder)` 等；SQL nullable 唯一约束不足处由业务校验补足。
- Room 外键有效、schema 导出、增量迁移保留旧数据；禁用 destructive migration。
- Binding 页段为一基、闭区间；PDF 渲染边界才转为零基源页索引，必须验证 actual pageCount。
- SAF content URI → 复制应用 managed storage → SHA-256 / PDF 校验 → 版本入库。源 URI 仅溯源，运行不长期依赖外部 URI。
- Native PdfRenderer、受控内存、后台 IO、资源关闭和取消隔离；绝不错误回退到旧版本。
- 批注绑定 PDF 版本和源页码，使用页面归一化坐标。新版批注默认隔离，后续明确迁移而非屏幕像素复制。

## 当前批注实现

数据库版本 3 增加按版本/页/owner 唯一的批注页、不可变发布记录及一场演出唯一的共享快照。AnnotationStore 作为 ViewModel 仓储边界，实际 Room 实现每次读写重新验证 Domain，个人层不读取他人数据，共享草稿仅有谱权限的管理员可编辑和发布。

发布按页，快照一次捕获整场演出相关版本的已发布状态和谱清单。不存在的共享页在快照中固定为空；后续发布不改变旧快照，已锁定节目单拒绝追加导入。版本/页段/节目单不一致会明确报错，不能重新映射旧批注。具体编辑及会话语义只在 PHASE5_REVIEW 记录。

## HID 与连续演出

系统已配对的 HID 设备通过 Activity KeyEvent 输入。PedalViewModel 管理按键门禁、设备回调和持久化映射；不直接访问 PDF 文件、不实现蓝牙扫描/GATT。默认和自定义按键统一产生 PedalAction，PdfReaderViewModel 使用 Domain 提供的完整 ResolvedConcert，通过 ConcertCursor 推进页与曲目；UI 不重写授权、分配或排序。

演出身份包括成员、曲目/声部/谱段顺序、当前版本、hash 和文件位置。变更停读、显式重开；配置恢复按准确身份隔离。批注笔画和保存期间输入不切页、不排队；演出显示已发布或冻结共享层，不显示管理员草稿。断开只清理设备按键并提示，阅读继续。SavedStateHandle 保存会话游标，SharedPreferences 保存设备键映射，均不是生产账号系统。

API 29+ 以公开 external 标志识别外接设备；API 26–28 保守识别有 vendor/product 身份的键盘 HID，零身份设备兼容性待真机。虚拟键盘 KeyEvent 自动化验证软件路径，不证明无线配对与延迟。实现证据和硬件边界见 PHASE6_REVIEW。

## 正式演出准备与离线

Phase 7 的 PerformancePreparation 复用完整 Domain 个人谱和 AnnotationStore：入场前校验本地文件/hash、打开全部允许源页、验证每页批注及已锁定快照，不制造另一套权限/分配规则。正式演出恢复必须重验；准备期间可取消、不积累输入，运行期错误退出正式演出。快照读取只依赖冻结 payload 与 manifest，不依赖后来可变的发布指针。当前页入场刷新，正式节目单冻结图层就绪前不显示旧排练图层；常亮由阅读 View 生命周期控制。单曲专注不要求节目单锁定。

这是本地就绪检查，不是网络下载或永久缓存。10,000 页检查上限、逐页事务开销及无全场位图预加载的性能边界见 PHASE7_REVIEW；不把模拟器通过冒充大规模或物理设备验收。

## 管理写入边界

LibraryManagement 在 Room 事务内重新验证管理员、外键目标、唯一/归档和锁定约束。管理目录是显式管理员元数据能力，不读取批注页；阅读、实际分配候选及当前个人谱继续由 ScoreResolver 解析。管理员 role 不自动授予读谱。当前本地身份入口仍不是生产鉴权。

ManagementViewModel 按成员隔离目录与编辑 token。旧表单、异步文件检查、SAF 返回使用所读 LibrarySnapshot，提交前重新检查；成员变化清除目录和取消未提交操作。已进入不可取消事务的导入保持原子完成，不能把取消当成撤销已提交数据。操作排队即取消也必须清除 busy。

ManagedPdfImporter 供快速导入和版本替换共用：content URI 复制、SHA-256、256 MB 上限、验证全部 PDF 页、同步文件、原子记录版本；失败删除本次新文件。版本不可变，切换前校验真实文件、版本归属及全部启用绑定页段，activeVersionId 唯一；旧版本文件与批注保留，不自动迁移。进程强杀的孤儿文件清扫仍待完善。

授权不等于分配，默认声部不授权。撤权保留显式覆盖分配标记，让 Domain 明确拒绝，而非改奏默认声部。更换分配先验证授权及完整谱段，错误在事务中回滚，不能毁掉旧分配。节目项重排使用事务内临时不冲突槽位，包含归档占位，保留所有记录身份。

锁定快照保护节目项顺序及引用曲目/乐章/声部/绑定/PDF 结构；不解锁、不覆盖历史。成员授权/分配可显式变更，现有阅读必须按 Domain 失效/重验。ConcertStatus 是管理元数据，不代替 Phase 7 的实际准备检查。归档不删除文件、个人批注或快照。

管理元数据目录当前按全表事务快照计算成员×曲目 Domain 投影，锁定查询逐节目单执行；规模化性能优化归 Phase 9，不宣称万条记录已验收。数据库维持版本 3，不引入无实际用途的迁移。

## NAS 与商业版方向

2026-10-10 新确认：Windows 工具不存在，将在 10.1 新建；成员/声部/分类动态维护，不内置名单或固定七人上限；必须支持外网同步。实际 NAS 条件待核验。当前规则/草案分别见 PHASE10_0_REQUIREMENTS 与 TEAM_SYNC_CONTRACT，不把草案当已实现功能。

以下是最新确定的方向，不是当前已经实现的系统：

- 保持原生 Android 核心演出能力，断网仍可使用已管理的本地内容。
- 本地资产开放导出与恢复归新增 Phase 9.5，在联网前落实；不是等商业版才做。母库、个人批注和灾难恢复备份分别明确权限与同意机制，管理员身份不等于个人层读取权限。
- DS720+ 作为近期 NAS、备份和测试设施；Phase 10 按 10.0 规则/工具盘点、10.1 Windows、10.2 NAS 服务、10.3 Android 自动同步、10.4 七人试运行细分。详细范围唯一维护于 PRODUCT_ROADMAP。不能让客户端直连 NAS 就冒充生产鉴权。
- 文件级隔离严于 UI 隐藏：不同声部共享一个母 PDF 页段时不能向成员下发整个母文件，需独立分谱或授权衍生文件及稳定原版本/源页映射。复用现有 Domain，不以默认声部或实际分配代替权限。
- 原版本 hash/pageCount/源页与配送分谱 hash/pageCount/文件页序是不同身份；10.3 要显式适配并测试批注映射，不直接改 managedFilePath 或重编号后冒称兼容。普通成员同步投影不能使用完整母库 ZIP/全表 LibrarySnapshot。旧原型设备母库残留也需明确备份和隔离迁移。
- 上传、核验、发布与各设备同步是不同状态；发布后按权限自动分发，节目单仅负责顺序。离线已取得内容无法保证即时撤回，跨设备批注冲突策略在 10.0 冻结，不静默覆盖。
- 存储抽象按 Local / NAS / OSS 演进，业务使用逻辑文件/版本身份，不把网络挂载路径写死进 Domain。当前 managed file 实现不等于这些后端已完成。
- 商业版预留 tenantId 方向。Phase 11 必须覆盖所有租户拥有的数据、授权、文件、批注、缓存、快照、唯一约束与迁移，进行跨租户拒绝测试；现在不能插一个字段就宣称完成多租户。
- 后续阿里云可作为云端方向；当前不引入未确认的部署、计费、同步冲突或实时协同架构。
- 本地开放备份恢复已在 Phase 9.5 落实；服务端鉴权、多租户隔离及联网灾难恢复在后续阶段落实。本地成员选择仅为开发测试身份入口。

## 本地资产备份边界

Phase 9.5 使用开放 ZIP/JSON 格式 1，固定 schema 3 字段白名单，不复制 Room 数据库文件。管理员具有显式母库导出能力，但不因此获得阅读权限或个人批注权限；母库保留全部原 PDF 版本、授权/绑定/节目单与共享发布和冻结记录，排除所有个人层。个人备份仅包含本人已保存的批注及最小版本引用，不含 PDF。具体格式和校验见 ASSET_ARCHIVE_FORMAT。

恢复先验证全部内容，再提交目标库；母库仅允许空库，文件写入随机应用管理路径，不使用 ZIP 路径写磁盘。个人恢复要求原成员 ID 与版本 ID/hash/pageCount 匹配，同内容幂等，冲突拒绝。复用现有 Domain 验证恢复后可见谱和页段，不新写 UI 权限。未加密备份需独立保管；本地身份选择、进程断电恢复及未来跨 schema 迁移不因此成为已解决问题。

详细阶段归属在 PRODUCT_ROADMAP，当前落地状态在 PROJECT_STATE。
