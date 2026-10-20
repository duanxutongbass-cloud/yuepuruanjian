# 团队账号与授权分谱服务（0.10.15 开发候选，尚未部署）

新增独立 POST `/v1/auth/signup`（username/password/displayName/deviceId/deviceName/backupConsent布尔值，不接收角色或团队身份），注册后尚未加入现有乐团。GET/POST `/v1/me/membership` 查看状态或以 requestId/baseRevision/code 申请；管理员 GET/POST `/v1/admin/join-code` 查看是否配置/按baseRevision生成或更换（仅返回一次明码，库内只存摘要），GET `/v1/admin/join-requests` 显示最早200项及总人数，POST `/v1/admin/join-requests/review` 以requestId/accountId/baseRevision/approved审核。申请和审核的同一requestId可持久安全重试，内容变化或旧代次拒绝。乐团码不授文件权限，批准成为普通成员后仍须明确逐PDF分配。原单次邀请码注册兼容，旧账号保留已入团状态，不创建第二个团队。

未加入、待审核和被拒绝账号只可操作本人身份、密码、设备、备份同意和入团申请，所有团队资料在HTTP读正文前及模块事务内拦截；管理员名单/发布/节目单接收人仅包含已加入账号。SQLite核心版本升级2，原账号/PDF/编号/授权/批注不重写；旧版本服务会拒绝2版库，不能直接切旧镜像回滚。上线前必须从当前NAS创建完整备份、独立验证恢复，再保留数据卷升级；失败回滚须使用升级前备份恢复至新目录/卷并核验，保留新库待排查。禁止重新初始化、删除原数据卷或覆盖旧备份。旧1版完整备份可由新工具恢复并迁移。候选及真实部署状态见 [公开注册](../docs/PUBLIC_REGISTRATION.md)，本地通过不等于NAS已经更新。

0.10.7 新增管理员不可变节目单冻结及按当前逐 PDF 授权投影的快照下载；新增表保留原账号、编号、PDF 和批注。管理员 POST `/v1/admin/program-freezes` 使用 `programId,requestId,expectedRevision`，GET `/v1/program-freezes/{programId}/{freezeId}` 返回经哈希校验的原分谱/已发布共享批注，不包含个人层或共享草稿，不授予新权限。已冻结内容不可修改，需复制为新场次；仍可归档/恢复。本机完整 74 项服务检查和 337 项 Android 检查通过；部署前须完整备份，实际发布状态见 [协议与检查记录](../docs/TEAM_PROGRAM_FREEZE.md)。
0.10.2 新增 App 更新：公开 GET `/v1/app-release` 与带精确 If-Match 的 GET `/v1/app-release/apk`，仅发布软件 APK；账号和乐谱仍鉴权。运维将同签名 APK 放入只读 `/releases`，执行 `python /app/server.py --data /data publish-app --apk /releases/文件名.apk --version-code 14 --version-name 0.10.2`。发布程序核对结构/大小/哈希并原子更新版本指针，客户端最终验证包名、版本和签名。NAS 配置与验证边界见 [0.10.2](../docs/APP_SYNC_0102.md)。

本批新增电脑端管理员暂存上传、逐份接收成员、整批确认发布与幂等回执；保留编号/分类/乐器。新增 `backup-full`、`verify-backup`、`restore-full`，用于数据库与 PDF 一致备份及新目录恢复。完整使用说明和测试证据见 [电脑端发布与完整备份](../docs/TEAM_PUBLISHING_0101.md)。Android 0.10.2 已接入编号 5 的前台自动同步。

DS720+ 使用 `compose.nas.yaml`；本机实际遇到 NanoCPUs/CFS 不支持，因此此配置不设 `cpus`，仍限制内存、使用 UID 10001、只读根目录和本机端口。NAS 专用分发包把 `compose.yaml` 与 `docker-compose.yml` 保持相同，避免 DSM 编辑器与 Compose 默认文件名不同导致旧配置仍生效。通用 `compose.yaml` 继续要求显式设置可信代理地址。

这是独立于 `test-service/` 的持久服务代码。测试服务继续只使用合成账号；此目录实现真实密码、账号、设备与服务端文件权限。当前 Android 从「我的 → 团队账号与设备」连接这里。本机曲库仍独立运行，没有把本地成员选择转换成服务端身份，正式团队曲库通过账号独立目录接入前台同步，不复用本机身份。

## 已实现

- 本地运维初始化唯一首位管理员；一次性邀请注册普通成员；账号名唯一；成员与设备动态维护。
- 账号密码登录、同一设备多账号依次登录、密码修改、退出、设备撤销与管理员恢复、停用/启用成员、运维重置密码。
- 数据库保存加盐 scrypt 密码哈希及不可逆会话/邀请码摘要，不保存明文密码或 bearer token。采用 N=32768/r=8/p=3 的 [OWASP scrypt 参数组合](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html#scrypt)。
- 当前开发版 API 会话有效期 7 天，严格绑定账号与设备；有效会话通过 `/v1/me` 核验成功后续期 7 天，普通传输不续期，已过期会话必须重新登录。密码修改、账号停用及设备撤销立即影响后续请求。App 离线阅读另受加密授权清单和完整缓存约束，不会因为账号续期恢复已撤权文件；NAS 是否已升级以发布记录为准。
- 登录限速、严格 JSON 字段、拒绝请求方伪造角色/成员、最小审计、禁止重定向和明文公网入口。
- 个人批注备份说明在邀请注册时明确勾选确认；既有/初始化管理员有补确认入口。0.10.3 起已接通个人批注同步与管理员只读审计访问，读取仍须目标账号同意及管理员本人的 PDF 授权。公开注册/乐团码申请尚未实现。
- 独立整份声部 PDF 的本地暂存；授权与发布各自显式执行；成员专属清单；逐文件鉴权；范围续传与版本校验。管理员角色本身不授予 PDF 下载权限。

## 本机运行

Python 3.12 或更高版本，先在独立虚拟环境安装 `requirements.txt`。不要将运行数据放入 Git 仓库。

```text
python -m venv .venv
python -m pip install -r requirements.txt
python server.py --data /private/score-team init --team "我的乐团" --username director --name "管理员"
```

运行安装命令时应使用虚拟环境里的 Python。初始化通过终端隐藏输入密码并再次确认，命令参数不接收密码。已初始化的库拒绝覆盖。忘记密码由有服务器运维权限的人执行 `reset-password --username director`，同样隐藏输入，并撤销该账号全部会话。

正式服务由 Waitress 承载，位于可信 HTTPS 反向代理后：

```text
python server.py --data /private/score-team serve --host 127.0.0.1 --port 8787 --trusted-proxy 127.0.0.1
```

反向代理必须覆写 `X-Forwarded-Proto`，并配置准确的源 IP。不能设为通配符。服务拒绝未被可信代理确认为 HTTPS 的请求。[Waitress 代理配置说明](https://docs.pylonsproject.org/projects/waitress/en/stable/arguments.html)

只有隔离测试可以使用 `serve --dev-loopback-http`，它仅绑定并接受 `127.0.0.1`。Android debug 包只允许这一精确回环地址的 HTTP；release 账号客户端只接受 HTTPS，并沿用系统证书校验。

## DS720+ 部署准备

2026-10-15 已通过用户登录的 QuickConnect 页面核验：DS720+，DSM 7.2.1-69057 Update 11，18 GB 内存，Container Manager 显示服务正常。部署源码已上传并解压到独立目录 `/docker/yuepuruanjian-team-service/team-service`，尚未构建或启动新容器。电脑与 NAS 不在同一网络；现有 DDNS 列表为空，旧反向代理域名查询返回不存在，独立 HTTPS 入口待恢复或另行配置。

`Dockerfile` 与 `compose.yaml` 是部署候选，当前电脑没有可用 Docker，尚未构建镜像/实际导入 Container Manager。使用 `linux/amd64`，以 UID 10001 运行；账号数据库与分谱持久保存在独立 `team-data` volume。不要挂载已有母库目录作为服务数据目录。

部署前配置 `TEAM_TRUSTED_PROXY` 为实际反向代理源 IP，容器只把 8787 映射到 NAS 本机回环，外部经受保护的 HTTPS 服务入口访问。`docker compose run --rm team-api init ...` 初始化后再启动服务。不要使用 `down -v` 删除数据卷。`compose.yaml` 不包含公网端口开放、域名、证书或默认密码；这些须结合实际 NAS 和网络入口核验。

QuickConnect 可以继续用于支持的群晖服务，但第三方应用不在其支持范围，不能把 QuickConnect ID 直接填成这个 App 的 API 服务地址。外网方案需要独立核验 DDNS/域名 + HTTPS 入口或受控私网连接。[群晖官方 QuickConnect 支持范围](https://kb.synology.com/en-global/DSM/tutorial/Which_services_support_QuickConnect)

## API

除登录/注册外，所有请求需要 `Authorization: Bearer …` 与 `X-Device-Id`。GET 不接受查询参数。POST 只接受 JSON，最大 16 KiB。服务只运行一个团队，不宣称支持商业多租户。

| 方法和路径 | 操作 |
| --- | --- |
| POST `/v1/auth/login` | `username,password,deviceId,deviceName` |
| POST `/v1/auth/register` | 同上，加 `displayName,invitation,backupConsent:true` |
| GET `/v1/me` | 服务端真实身份、团队、角色、确认状态和会话期限 |
| POST `/v1/auth/logout` | 空对象，撤销当前会话 |
| POST `/v1/auth/password` | `currentPassword,newPassword`，撤销全部会话 |
| POST `/v1/me/backup-consent` | `accepted:true`，记录既有账号首次确认 |
| GET `/v1/me/devices` | 仅本人设备 |
| POST `/v1/me/devices/revoke` | `deviceId`，仅本人设备 |
| POST `/v1/admin/invitations` | 空对象，一人一次、48 小时有效的普通成员邀请码 |
| GET `/v1/admin/members` | 团队成员元数据 |
| POST `/v1/admin/members/status` | `accountId,active`，不允许此接口停用管理员 |
| POST `/v1/admin/devices/restore` | `accountId,deviceId`，恢复后必须重新登录 |
| GET `/v1/admin/audit` | 最近 200 条不含凭据的操作审计 |
| GET `/v1/catalog` | 当前账号已授权且已发布的文件元数据 |
| GET `/v1/artifacts/{UUID}` | 当前账号授权文件；支持单范围 Range 与带引号的 If-Match |
| GET `/v1/admin/artifacts` | 暂存/已发布文件元数据，不赋予文件阅读权限 |
| POST `/v1/admin/artifacts/publish` | `artifactId,published`，发布/撤回 |
| POST `/v1/admin/grants` | `accountId,artifactId,allowed`，逐文件授予/撤销 |

通过本地运维命令暂存一个**已经拆分好的独立声部文件**：

```text
python server.py --data /private/score-team stage-part --pdf /input/violin.pdf --title "曲名" --part "小提琴" --independent-part
```

该声明表示整份 PDF 都属于一个可授权声部，不是仅显示母谱的几页。工具验证 PDF、页数、完整字节和哈希，拒绝加密、附件及文档级活动内容，不修改原文件；不能判断纸面上实际写了什么乐器。当前不提供自动拆母谱，不实现总谱源版本/源页与分谱映射。单份上限 100 MiB/2000 页。暂存不会自动发布、授权或上传个人批注。服务返回新 artifact UUID 供后续显式发布/授权调用使用；电脑整理器的可视发布步骤属于编号 4。

## 备份和恢复边界

`backup --output /backup/team.sqlite3` 使用 SQLite backup API 保存一致的数据库（包含已提交 WAL），拒绝覆盖已有备份，移除备份里的会话，避免恢复时旧 token 重新生效。只恢复到独立空数据目录，恢复后重新登录。测试覆盖了独立目录恢复。

**这条命令仅备份账号/权限数据库，不包含 PDF 文件，不能作为整个曲库的完整备份。** 有分谱数据时，还需要对应 `artifacts/` 文件及其哈希校验；完整数据库+文件一致归档/恢复流程仍属于编号 3 的未完成部分。备份含账号密码哈希与成员信息，应保存在受限目录。密钥、离线凭据期限、私人批注备份和多设备同步尚未实现，不能把这份数据库备份当成个人批注备份。

## 测试

```text
python -m unittest -v test_accounts test_catalog test_waitress
```

测试只使用临时目录、合成账号和自行生成的空白 PDF。Android 的 `TeamAccountInteropTest` 会启动一次临时 Python 服务，真实验证 Kotlin HTTP 客户端登录/邀请注册/设备绑定/权限拒绝/退出。Windows 运行 Gradle 前可设置 `TEAM_SERVICE_TEST_PYTHON` 为本机 Python 绝对路径；Linux 默认 `python3`。`interop_fixture.py` 使用标准库测试服务器，仅用于该自动化测试；部署使用 Waitress。

完整结果见 `../docs/ACCOUNT_SERVICE_0100.md`，NAS/HTTPS 公网可用性与平板测试不由本机测试替代。
# 0.10.10 文件夹同步更新

新增鉴权后的 `/v1/personal-folders` GET/POST，本人独立快照、版本 CAS 和持久重试回执；不增加 PDF 授权，不开放管理员读取他人文件夹。Dockerfile 必须包含 `personal_folders.py`。升级前创建并验证新的完整备份，保留数据卷、旧镜像与 APK。接口约束及本机验证见 `../docs/FOLDER_SYNC_0110.md`，上线状态以该文档实际回执为准。



## 0.10.19库4候选运维入口（尚未NAS部署）

生产仍为0.10.17/库2。候选普通启动保持库2兼容；彻底删除仅在经明确激活的库4上开放。`--permanent`为全局参数，需放在子命令之前。新路由为POST `/v1/deletions`、`/v1/admin/permanent-deletions/preview`、`/v1/admin/permanent-deletions`；管理员确认请求须带最新revision、scopeDigest、稳定requestId及confirmed=true，不允许跳过预览重核。

实际切换前须停止写入源服务，制作并校验当次库2完整归档且独立恢复核验；保留原数据目录、旧映像及公开APK渠道。准备只写新的独立数据目录：`python server.py --data NEW prepare-permanent --archive OLD_FULL_BACKUP`。再以已停止且业务行完全一致的权威源执行：`python server.py --data NEW activate-permanent --retirement-source OLD --confirmed --source-stopped`。普通旧服务拒绝库4，库4服务通过`python server.py --data NEW --permanent serve ...`启动。新目标激活后不能恢复运行旧源继续接受写入；不能只替换二进制后把旧库当新库。

库4完整备份用`python server.py --data ACTIVE --permanent backup-full --output NEW_ARCHIVE`；`verify-permanent --archive ARCHIVE`做新归档验证，`restore-permanent --archive ARCHIVE`只恢复到新的独立目标。恢复副本在启动前仍须activate-permanent并指定当前已激活且已停止的库4权威源，把该源累计删除/占号台账合入；无法取得权威源时保持关闭，不能绕过防复活门禁。原归档不被修改；历史私有备份按原保留策略管理，不声称已被全部抹除。恢复会话/一次邀请码不重新激活。

这套命令的本机合成库/故障/真实回环HTTP验证不能替代NAS路径、权限、容器和真实曲库验收。Dockerfile仅打包所需模块，编号13扩位入口未启用，生产编号上限仍999。部署和验收以本次交付说明及verification.json的实际状态为准。
