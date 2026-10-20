# 0.10.0：编号 1–3 的第一批实现

日期：2026-10-15。用户要求按编号继续开发，并自行继续平板实测。本批复用现有 Android/Windows 项目，保留 0.9.9 改动，不更换曲库、不重置数据库。Android versionName 0.10.0 / versionCode 12 / Room schema 5，现有本地备份格式不变。

## 功能与范围

编号 1 的账号/设备软件闭环已实现：持久化账号、运维初始化管理员、一次性普通成员邀请注册、账号密码登录、设备绑定、查看/撤销设备、修改密码并使全部会话失效、停用/启用成员、退出/切换账号。Android 入口是「我的 → 团队账号与设备」。密码与邀请码不进入 saved state；会话采用 Android Keystore AES-GCM 加密，文件和设备标识保存在 noBackupFilesDir。进程重开先联网校验服务端身份，不拿缓存身份当在线认证；网络故障保留可重试的加密凭据。退出时即使网络失败也清除本机登录，并如实提示远端撤销尚未确认。

编号 2 的服务端部分已实现：认证后的主体只来自会话；角色每次查询服务器数据；客户端不能提交其他 memberId/role 提权；已撤销设备、已停用成员及过期会话拒绝访问。独立声部 PDF 的暂存、发布、逐文件授权分离；未发布文件不会出现在成员清单，管理员也需要明确阅读授权；原始字节保留，Range 续传请求再次鉴权，旧清单/猜测 UUID 不能绕过撤权。

编号 3 已完成本地服务与部署候选：Python/SQLite/Waitress，Dockerfile、Compose、可信 HTTPS 代理约束、账号数据库一致备份与独立目录恢复。2026-10-15 已通过用户登录的 QuickConnect 实际检查 DS720+、DSM 7.2.1-69057 Update 11、18 GB 内存；Container Manager 显示 1 个项目、3 个容器运行正常。源码包已上传并解压到 NAS 的独立目录 `/docker/yuepuruanjian-team-service/team-service`，页面确认 13 个服务文件。未构建容器镜像、启动新服务或初始化管理员。

外网入口尚未可用：DDNS 列表为空，证书仅有默认及 QuickConnect 项，DSM 自定义域为空；旧反向代理中找到域名线索，但它使用 HTTP、目标主机拼写异常，本机 DNS 查询旧主机及主域均返回名称不存在。用户确认电脑与 NAS 不在同一网络。实际地址只保留在当前线程/本地检查记录，不写入分发源码。未修改既有容器、证书、网络、代理或防火墙设置。一次跟进已更新为 2026-10-16 10:00 确认旧域名归属与外网入口；代码上传不代表 NAS 服务或 TLS 已验收。

## 验证

- 最终 Android：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` 成功。
- JVM/Robolectric **235 项，0 失败、0 错误、0 跳过**：原 225 项加 5 项地址/加密/UI 测试、4 项账号状态测试、1 项真实 Python 服务与 Kotlin HTTP 客户端互通测试。
- 互通测试实际启动本机临时 HTTP 服务，完成管理员登录、邀请码、成员注册、同设备不同身份、错误设备拒绝、成员越权拒绝、退出失效；不是仅比较 JSON 示例。
- Lint **0 错误、15 警告**；新增的一项为 SharedPreferences.edit 的 KTX 风格建议，其余为既有代码风格/依赖版本提示。未通过屏蔽规则压过错误。
- 服务端最终统一运行 **29 项，0 失败/错误**：20 项账号、8 项文件授权、1 项真实 Waitress 可信代理边界；耗时 214.279 秒。
- Windows JDK 21 / Gradle 8.14.3 / Python 3.12.14 本机执行。未运行物理 Pad 或新 Android instrumentation 用例；AndroidTest APK 仅构建，不能把它计为真机通过。
- 构建证据：工作区 `work/account-final-build.log`，Gradle XML/HTML 测试报告，`work/team-service-final.log`。

APK：`outputs/yuepuruanjian-0.10.0-accounts.apk`，20158444 字节，SHA-256 `1189CDE9070E8D128B7DFF6C378DDDA8F656E90244F88A78007FC2F7A3F578E1`。apksigner 实际验证通过，证书 SHA-256 为 `9f360690612dabcdc4c13c8fb0b89af99402a64782f8fdcba79cddfb2be745fa`，与此前 0.9.9 一致。新源码/容器候选压缩包不含真实账户、数据库、凭据或曲谱。

## 本批发现并修复

1. SQLite backup 的连接上下文没有自动关闭文件句柄，导致 Windows 恢复目录无法释放；改为明确关闭并重新验证独立目录恢复。
2. 凭据重复写入在 Windows Robolectric 的 Android AtomicFile 仿真中无法证明覆盖成功；改为同目录临时文件、fsync、原子替换，并验证密文每次不同、可解密、篡改被拒绝、删除后不可恢复登录。
3. Android debug 回环网络配置缺少 includeSubdomains 属性被 Lint 拒绝；补为 false，只允许精确 127.0.0.1。
4. 服务端备份会包含历史会话；现在在备份副本里清除会话，独立恢复后旧 token 拒绝，账号密码重新登录成功。

## 仍未完成

- 现有本地成员切换仍是本机模式。团队账号不会自动接管本机库，也没有导入服务器文件/成员数据；需要编号 4–7 的身份映射、后台同步及离线看谱策略。不宣称线上权限已经保护旧本机资料。
- 本批文件授权以独立整份声部 PDF 为边界，不自动拆总谱、不提供源版本/源页映射。电脑整理器的批量发布、动态声部权限映射与管理界面尚未接入。
- 当前在线 API 会话 12 小时，不擅自定义无限离线授权。未来离线账号解锁/期限、多设备批注冲突、冻结演出进程恢复需要单独落实。
- 注册只保存批注备份知情确认，没有上传个人批注，也没有把管理员读取个人层的网络功能提前开放。
- `backup` 命令只备份账号/权限数据库，不包含 PDF；完整数据库与文件一致归档/恢复仍待编号 3 补齐。
- NAS 部署、外网 HTTPS、完整自动同步、文件夹备份、真机/排练和商业功能均未通过。QuickConnect 不直接支持这个自建 API，需另行落实受保护连接入口。
- 当前 APK 仍使用原 debug 签名，版本递增可覆盖安装；不是正式上架包。代码保留在本地工作区，未推送远端。

按编号追踪以 [NUMBERED_DEVELOPMENT](NUMBERED_DEVELOPMENT.md) 为准；服务运行、接口及部署候选说明见 [team-service/README](../team-service/README.md)。
