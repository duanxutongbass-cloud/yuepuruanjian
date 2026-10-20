# 编号 10：本人文件夹跨设备同步（0.10.10 已发布）

2026-10-17。0.10.10（22）完整本机验证、NAS 升级和公网安装包校验已完成，可在 App 内更新。物理平板由用户继续验收。

## 使用

同一团队账号在设备上联网同步一次，可在曲库文件夹栏进入“文件夹同步”。编辑后自动重试，回到前台及前台定时核验后也会同步；暂停 PDF 下载不会暂停文件夹。离线修改留本机，联网核验后继续；新设备的空列表先接收已有云端内容，不清空云端。

双方均有不同修改时显示冲突，查看两套名称、引用数量、封面后，明确选择“确认保留本机整套”或“确认使用云端整套”，也可暂不处理。此选择包含删除结果；预览后本机/云端发生变化则重新查看。编辑窗口持有旧记录时，保存/删除会拒绝覆盖后来收到的内容。ZIP 导出仍可用于额外备份。

同步内容只含文件夹、图片和稳定曲目引用，不增加 PDF 阅读权限，不按同名曲目替换。管理员同样只能取本人文件夹；不同账号和服务的同步日志隔离。成功上传的最新头与回执进入 NAS 完整备份，未上传的离线修改仍只在原设备。

## 已完成的接口

- `GET /v1/personal-folders`：会话绑定的本人头，`protocolVersion/accountId/teamId/revision/payload/sha256`。`payload` 为 UTF-8 规范 JSON 字符串，对原字符串校验 SHA-256 后解析；初始版本 0、内容 `[]`。
- `POST /v1/personal-folders`：`protocolVersion:1/requestId/baseRevision/folders`。不接受 owner/accountId 等身份覆盖。管理员也只能访问本人，不新增读取他人文件夹入口。引用是已有稳定 ID，不按曲名/编号匹配，不授予 PDF 权限。
- 同一账号的更新以 SQLite 写事务及 baseRevision 比较提交。200 回执绑定账号、团队、请求 ID、新版本和内容 SHA；409 `folder_conflict` 保存冲突时版本/摘要，客户端另取最新头。持久回执重试保持原结果，即使之后头已前进；同请求 ID 不同正文返回 409 `request_id_reused`。
- 最新头至多 8 MiB、100 个文件夹、100 个 UTF-16 单元名称，每夹 10000 个不重复引用。图片至多 512 KiB、512×512，互斥图片/乐谱引用封面。沿用 App 压缩输出的静态 8 位 RGB/RGBA PNG；校验签名、CRC、块顺序、尺寸和有界 zlib 栅格，App 再实际解码。该入口不接收任意 PNG 变体；结构依据 [W3C PNG](https://www.w3.org/TR/png-3/) 第 5、10、11 节。
- 大正文仅本路由可用，鉴权后才读取，读完再次鉴权；其他 JSON 路由 16 KiB 限制保留。响应无缓存，不记录凭据/文件夹名/图片内容。
- 仅头保存图片，回执不重复图片。每账号最多 50000 回执、实例最多 1000000；头总容量 512 MiB。到限额拒绝新写入，既有头/回执保留，不清理后把旧重试当成新更新。
- 完整 NAS 备份自动含两张新 SQLite 表；备份恢复专项验证头、成功/冲突回执和重新登录后重试，旧会话不能复活。

## 验证与待办

- 服务专项首轮 11 项通过（71.583s）；补充停用/撤设备、真实图片总量及 PNG 边界后 14 项通过（88.988s）。日志在工作区 work/service-personal-folders-first.log、service-personal-folders-boundaries.log，未覆盖失败或既有测试。
- App 严格响应/规范编码、实际 PNG 和加密账号绑定日志首批 8 项通过（3m20s）。同步状态机加既有备份/编辑 31 项通过（3m31s）：断线回执/新编辑、离线重开、双设备冲突选择、过期预览、中断接收恢复、账号隔离、云端回退和迟到响应。
- 服务完整 88 项通过（657.493s）。App 前台/编辑触发、曲库冲突选择 UI 已接入，真实 HTTP/原生 PNG/双设备及界面专项正在运行。同步进度不广播至阅读页；暂停 PDF 下载仍可单独同步文件夹。
- 真实 Python HTTP/Android 原生 PNG/两台设备/大于 16KB 请求、离线修改/冲突选择、撤销登录和原 PDF 哈希保留联通通过；界面实际绘制已检查。相关 64 项通过（3m43s），补充暂停 PDF 独立同步与过期编辑器保护后 66 项通过（3m31s）。日志 work/app-folder-integration-first.log、app-folder-final-focused.log。
- 最终 Android 完整 405 项/72 类，0 失败/错误/跳过，APK/AndroidTest/Lint 成功（0 错误/15 警告，7m54s）；服务完整 88 项通过（657.493s）。物理平板仍由用户验收，设备测试 APK 仅构建，不能称真机通过。
- 同签名 App 0.10.10（22），20,708,231 字节，SHA-256 `aadd586d4e1ec95e3dbf411276ee776b13741fa989d98b7d4f9f8d39da041388`，schema 6。产物与本机证据位于仓库上级 outputs/yuepuruanjian-0.10.10-folder-sync.apk、app-0.10.10-verification.json；18 文件后端 ZIP 已 CRC 校验，临时版本说明已维护，nasPublished 为 true。
- NAS 新完整备份 `/data/backups/pre-0110-20261017.zip` 95,746 字节，SHA-256 `ec39d34ef0bce631cac416f17643d4b2c029e2ba54e7169a89f90689a17e8b86`，创建并独立校验成功，截图 nas-0.10.10-preupgrade-backup.png。7 个生产/配置文件与新版 APK 已上传。项目级停止失败，公网旧版当时仍在线；改为仅容器级停止后构建，新镜像 yuepu-team-api:0.10.10、容器 `2c67c7a49215724567cddb3a417bee5b893b67c3f9e61344ade6c623ae38e26a` 于 01:24 健康运行，其他项目/旧镜像/数据卷保留。
- 发布前实际核验 server.py/personal_folders.py 两份新源码、7 天会话常量、完整备份 SHA 及 APK SHA/大小；`pre-0110-release-manifest.json` 保留旧版本 21。版本 22 正常 TLS/无重定向公网整包下载 SHA/大小/ETag 与本机一致，11 私有读写入口（含新文件夹 GET/POST）未登录均 401；证据 outputs/nas-0.10.10-live-verification.json、nas-0.10.10-download-verified.apk、nas-0.10.10-published.png。
- 下一项编号 11 未保存笔迹恢复。公开注册→乐团码申请→管理员审核仍待开发，商业 Phase 11（含编号 19）前停止并停用续做；同一工作区，无代理/分支/提交/推送。
