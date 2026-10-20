# 重奏团曲谱库

2026-10-18 0.10.21（33）已NAS发布：纠正20只补“退出并返回”的不完整处理。已登录账号进入团队曲库加载／重试页；账号详情“返回曲库”只关闭详情、保留会话，退出另列。修复可信私有根目录等价路径导致身份／离线／删除台账激活拒绝；旧受管PDF同目录同名等价路径兼容，子目录／内容／身份保护保留。隔离3项修复前失败，53项专项及完整Android689项／113类均通过（0失败／错误／跳过），APK／AndroidTest／Lint成功，Lint0错误10警告，同签名。完整公网21188418字节SHA 6179a8eafc89c95f8ec10ace6e22f4e6f2dd768e6b0fae63380f7aa5fe7b3b1e，20匿名私有401。服务仍19／库4、无迁库／重建／真实曲谱删除，旧32保留。NAS旧终端断开，关闭重开交互面板后核对上传脚本SHA并发布；此前无结果尝试没有被记为成功。证据见新聊天outputs/0.10.21-verification.json及nas-0.10.21-{public,deployment}-verification.json。是否为用户设备同一触发点尚未确认，真机由用户本人验收。automation-2仍暂停；1000+和商业阶段仍关闭。此条覆盖下方旧待办。

2026-10-18 0.10.20（32）紧急修复已完成并NAS发布：账号拦截页固定返回与系统返回、安全退出及旧操作取消等待；全部9处下拉菜单明确关闭；上传、团队管理、本地删除恢复加载时可返回。32项专项、完整Android684项／112类均0失败／错误／跳过，APK／AndroidTest／Lint构建成功（0错误18警告），同签名。首次缓存权限及Python夹具环境失败日志保留，环境修复后完整通过，未减弱断言。公网21188154字节SHA fc05b7f2011eeefee89644ca548d885d2baccabeb6cb31fc2a00d5bc28622d0d，20私有401。服务仍0.10.19／库4，源码不变，没有重建／迁库／实际曲谱删除；旧31保留。发布后内部普通HTTP检查403已记录，外部HTTPS验证通过。新聊天outputs/0.10.20-verification.json及nas-0.10.20-{public,deployment}-verification.json为证据。待用户本人真机验收；automation-2仍暂停，1000+与商业阶段关闭。此条覆盖下方升级未完成的历史状态。

2026-10-18 当前接手状态（覆盖下方历史待办）：用户要求先完成可安装候选，实体平板稍后验收。App 0.10.19（31）完整676项/111类测试通过，0失败/错误/跳过；主APK、AndroidTest APK、Lint构建完成，同既有签名。Windows 0.1.2已构建，以独立空资料目录实际启动核对标题/主要入口；1086项完整电脑回归的121份输入核对仅版本显示变化，另15项相关检查通过。服务完整回归238项通过，0失败/错误/跳过；按固定完整清单分4组执行，无遗漏/重复，原全量收集的既有TestOperations提示保留。首次因测试夹具Python路径漏配出现1失败，修正环境单项及完整复跑通过，源码/断言未改，原结果保留。生产仍App 0.10.18（30）/服务0.10.17/库2，本轮没有NAS发布、实际迁库或真实PDF删除。

本轮已接通管理员影响预览/勾选确认、鉴权HTTP提交与分页通知、激活前及跨账号受管目录清理、未保存笔迹/离线库/收藏最近/文件夹持久退休过滤、在演终止、下载屏障及最终Room/PDF清理，成功后才确认游标。服务通知提供全局消失pieceReferences；不以某账号本机无谱推断整曲消失。库4准备/恢复副本须显式激活；历史备份激活前合并当前退休台账，保留公开APK渠道。

仍须验收：真实平板曲库小预览/阅读/手写笔/管理员删除及另一台设备离线后重连；配套服务在NAS上的新完整备份、独立恢复、库4迁移激活、容器与HTTPS下载验证。未识别来源的18旧账号缓存不猜测擦除，能验证身份的账号激活时清理；离线设备下次联网处理。原PDF来源、电脑母库及历史私有备份保留。已存在/回收站/合并/历史/退休编号和批次显式号继续占号，新曲只补真正空号。1000+、编号13正式开放、商业Phase11/编号19不在本次交付范围。旧聊天已停止，automation-2保持暂停；不创建分支、提交或推送。


Android Kotlin / Jetpack Compose project, application candidate 0.10.15 (latest published 0.10.10; baseline 0.9.6, database schema 6).

0.10.15 adds public account signup, orchestra-code applications and explicit administrator approval within the existing single team. Pending identities cannot activate/read the library; approval still requires per-PDF assignments. Full Android 552 tests/93 classes and service 103 tests pass; APK/AndroidTest/Lint and the existing signer pass. Candidate 27 and its backend schema-2 upgrade are not uploaded or deployed. The public channel remains 22; a fresh verified NAS backup and the pending management-login handoff are required before deployment. Physical tablet checks remain unverified. See [registration and deployment](docs/PUBLIC_REGISTRATION.md).

0.10.14 adds explicit authorized score comparison with searchable numbered parts, independent page turns, side focus, revocation checks and bounded image ownership. Android 535 tests/90 classes, service 88 tests, APK/AndroidTest/Lint and the existing signer pass. Candidate 26 is not uploaded or published; the NAS handoff remains pending and the public channel still serves 22. Physical tablet/native PDF checks remain unverified. See [comparison verification](docs/VERSION_COMPARE.md).

0.10.13 adds an explicit device input mode, owned stylus strokes, palm/cancel handling and undoable hardware-eraser strokes. Android 510 tests/86 classes and service 88 tests pass; APK/AndroidTest/Lint and the existing signer pass. Candidate 25 is not uploaded or published: the NAS file page and reconnect timed out. The public channel remains 22; previous candidates are retained. Physical pens/tablets and pressure/side buttons remain unverified. See [input scope and verification](docs/STYLUS_INPUT.md).

0.10.12 adds account-private encrypted recent reading, cold-start page restoration, favorites and confirmed independent clearing, combined with the current library search and folders. Android 489 tests/83 classes, service 88 tests, APK/AndroidTest/Lint and the existing signer pass. Candidate 24 is built and uploaded to the NAS, with its exact size and MD5 verified through File Station; it is not published. The public channel still serves 22, and the NAS login handoff remains pending. Physical tablet checks remain with the user. See [candidate verification](docs/RECENT_READING.md).

0.10.11 completes account/source-bound encrypted unsaved-ink recovery, background durability checkpoints, exact lost-acknowledgement handling, explicit conflict previews and permission-change hiding. Android 453 tests and service 88 tests pass; same-signer APK 23 is built and uploaded to the NAS. Publication is blocked by closing DSM terminal connections and management-certificate errors: the public channel still serves version 22. Runtime source checks, release-pointer backup and the new public full download are not confirmed. Physical tablet checks remain with the user. See [candidate status and verification](docs/UNSAVED_INK_0111.md).

0.10.10 adds account-private NAS folder/cover sync, durable offline edits and retry receipts, explicit two-device conflict choices, and stale-editor protection. Folder sync remains independent of paused PDF downloads and reader state. Android 405 tests and service 88 tests pass; backend 0.10.10 and same-signer APK 22 are published, with a verified full public download. Physical tablet checks remain with the user. See [usage and verification](docs/FOLDER_SYNC_0110.md).

0.10.9 adds personal folder/cover ZIP backups, verified export, explicit restore previews and conflict-safe merges for the same account/library. Team accounts can use personal folders. PDFs, score numbers, annotations and grants stay unchanged. Android 381 tests and service 74 tests pass; same-signer APK 21 is published and its full public download is verified. NAS remains 0.10.7; physical tablet checks remain with the user. See [usage and verification](docs/FOLDER_BACKUP_0109.md).

0.10.8 adds account-scoped score download progress, searchable tasks, queue pause/retry, and authorized Range resumption across restart. Complete hashes/PDF checks and a fresh final catalog precede atomic installation; progress stays separate from reader state. Android 359 tests and service 74 tests pass. Same-signer APK 20 is published with a verified full public download; the NAS backend remains 0.10.7. Physical tablet checks remain with the user. See [usage and verification](docs/DOWNLOAD_MANAGEMENT_0108.md).

0.10.7 connects immutable NAS programme freezes to authorized Android sync, cold offline recovery, and finite entered performances. Administrator confirmation freezes only original parts and published shared ink; later changes require a new scene. Android 337 tests and service 74 tests pass. NAS and same-signer APK version 19 are published; full public HTTPS download hash/size/ETag match and nine private endpoints reject unauthenticated requests. Physical tablet and rehearsal checks remain with the user. See [usage and verification](docs/TEAM_PROGRAM_FREEZE.md).

0.10.6 fixes the team-administrator annotation refresh loop, uses a compact stamp overlay without resizing the PDF, adds independent persistent stamp sizing, explains batch-upload validation and existing voices, and adds temporary encrypted diagnostic records to the team's NAS. Android 326 tests and service 66 tests pass; NAS and APK version 18 are published with a verified full public download. Physical tablet verification remains with the user. See [usage and verification](docs/BUGFIX_0106.md).

0.10.5 adds encrypted seven-day offline authorization, verified cached-score recovery after restart, reconnect identity checks, and durable revocation handling. Android 309 tests and service 61 tests pass; NAS and APK version 17 are published with a verified full public download. Connect and synchronize once after upgrading. Frozen-performance revocation exceptions and physical tablet acceptance remain pending. See [usage and verification](docs/OFFLINE_0105.md).

0.10.4 adds real NAS administrator management, multi-PDF batch publishing, reversible library/programme removal, whole-score programme selection including every part, and automatic update notices with a temporary offline release-history button. Android 291 tests and service 59 tests pass; NAS 0.10.4 and APK version 16 are published and the full public download is verified. See [usage and verification](docs/TEAM_MANAGEMENT_0104.md).

0.10.3 adds account-scoped ink sync, durable CAS/idempotent requests, explicit conflict choices, audited administrator read-only access to uploaded personal ink, and published shared ink. See [verification and limitations](docs/ANNOTATION_SYNC_0103.md).

0.10.2 adds authorized team-library foreground synchronization, annotation/stamp layout fixes, numbered program search and filters, and in-app verified APK updates. The NAS service is deployed over HTTPS. See [implementation and verification limits](docs/APP_SYNC_0102.md) and [numbered development progress](docs/NUMBERED_DEVELOPMENT.md).

The user-designated Windows companion is [desktop-organizer](desktop-organizer/COMPANION.md), adopted from yuepuruanjianqianzhi. Android recognizes its PDF filenames and shared instrument/category dictionaries; see [integration and verification](docs/ORGANIZER_COMPANION.md). The older local-manager directory remains a technical prototype.

0.9.9 adds fullscreen reading, stable annotation layout, prepared music stamps, personal library folders/covers, concert audiences and numbered metadata; see [usage and verification boundaries](docs/READER_AND_LIBRARY_099.md).

0.9.7 separated PDF selection from import confirmation; 0.9.8 adds the supplied organizer's filename and dictionary recognition. See [PDF import entry fix](docs/PDF_IMPORT_ENTRY_REVIEW.md) for the earlier repair.

2026-10-14: Local main integrates the Android UI, annotation reliability, sync core, isolated HTTP/offline test adapter, and local management workflow/review prototype. See [local merge verification](docs/LOCAL_MERGE_REVIEW.md), [local manager](local-manager/README.md), and [HTTP test service](test-service/README.md).

- [Documentation](docs/README.md)
- [Roadmap](docs/PRODUCT_ROADMAP.md)
- [Current status and verification boundaries](docs/PROJECT_STATE.md)

Open with Android Studio; use Gradle 8.14.3 and a compatible JDK/Android Studio JBR. Configure your own Android SDK. Android SDK 36 Robolectric tests require JDK 21; the Android paper-workspace slice was also verified with Gradle 8.13. This snapshot does not include a Gradle wrapper; use a local Gradle installation.

Private scores, personal backups, signing keys, local configuration and generated build outputs are excluded. Included score.pdf is a generated synthetic test fixture.

Real-device acceptance remains pending; NAS deployment and full public update downloads are verified as described above. Full JVM tests include real Python/Kotlin account and score-transfer protocol checks; Python 3.12+ is required (`TEAM_SERVICE_TEST_PYTHON` may specify its executable on Windows).
