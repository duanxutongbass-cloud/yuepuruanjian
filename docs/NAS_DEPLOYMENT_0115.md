# 0.10.15 实际部署与验收

2026-10-17：App 0.10.15（27）与NAS服务已上线；数据库升级为2，App Room仍6。公开注册后用乐团码申请，管理员审核，批准后仍按PDF明确授权。旧候选0.10.11—0.10.14的功能累计随27发布。

## 本次完成与证明

1. 用户已允许一次root备份核验并亲自确认；任务`Yuepu pre0115 backup check`自动执行关闭，仅手动运行一次，10:53:48—10:53:50正常(0)。备份96176字节，SHA `8922349297382faa211ee4c20b250df1cbab13ee4218a568a976883a6d43e343`，独立恢复通过。本机实际下载proof，完整ZIP未下载本机。
2. 上传冻结服务ZIP和APK：ZIP45427字节/MD5 `fae6a49b18cd5ad910c21195da57efb8`；APK21132343字节，实际容器SHA `9cc9f6a6f51fd4a1332ef72c3e8304f8611e9a69f75c066f8542eeaa9cb2c3b8`。ZIP解压至独立新目录，旧源码保留。ZIP本身18份内容在本机封存检查过，NAS仅整ZIP大小/MD5核验，不把它称为NAS18份解压文件SHA证明。
3. 切换前又通过现有容器非root终端建立新完整备份：`/data/backups/pre-0115-switch-20261017T035917Z/full.zip`，96176字节，SHA `a580ecbd7cf45728ef03c069a3302711604ef672a5ffd75fd43abdef300d2745`；同目录`restored`独立恢复通过，旧库1，1账号/1文件。对应本机父outputs/nas-0115-switch-backup-proof.json。
4. 停止原项目容器，原YAML保存为父outputs/nas-pre0115-project-original.yaml；只改build至`/volume1/docker/yuepuruanjian-team-service/yuepuruanjian-team-service-0.10.15/team-service`及image至`yuepu-team-api:0.10.15`。原端口、卷、网络、资源限制与只读保护保留，其他NAS项目未动。构建弹窗曾显示失败，但实际新容器随后运行且健康；最终容器ID `351d41a1bd069dcca0dd363c6c3c35d9dd9887e44635031f9550f72690fc7ddc`，NAS12:14启动，发布后观察Up25mins/良好。
5. 新容器实际12份Python源码逐份SHA一致；SQLite2完整性/外键通过；原账号/原PDF及21张业务表与切换前独立恢复备份一致，旧账号默认joined，七天604800秒。sessions/invitations在恢复备份按设计清空，不列入逐行相等；throttle/sqlite_sequence也排除。证明runtime-0115-proof.json保存于上述备份目录，本机转录nas-0115-runtime-proof.json，不输出密码或真实谱正文。
6. 使用新NAS运行代码、隔离临时数据库与合成PDF，通过真实本机HTTP验证注册、申请、拒绝、重申请、批准、批准前阻断和批准后明确分谱授权；测试结束清理临时测试目录，未变更生产账号/PDF。证明registration-smoke-proof.json与本机nas-0115-registration-smoke-proof.json。属于NAS隔离验收，不是真实管理员操作或物理平板验收。
7. 发布前备份旧22更新清单到`/data/backups/pre-0115-release-manifest.json`，重新核对源码、库2、完整备份SHA和APK后发布27；实际终端`PUBLISHED_0115_OK`，本机父outputs/nas-0.10.15-publish-proof.json。
8. 外部正常TLS、无重定向完整下载21132343字节，SHA与ETag均为上述APK哈希；17个原/新增私有入口未登录401。公网空注册400、没有创建生产账号。父outputs/nas-0.10.15-live-verification.json及nas-0.10.15-download-verified.apk。首次网络沙盒10013失败，取得网络执行权限后成功；没有关闭TLS校验或绕过证书。

## 保留与回滚

保留旧源码/镜像、原卷、旧APK、两次本次备份及旧清单。后端库已升2，**不可直接用0.10.10镜像打开当前库**。需要回滚时先保留2版数据，升级前完整包恢复到全新目录/卷并独立验证，再切旧服务读取恢复后的1版库；不得重新初始化或覆盖当前数据。

已有552项Android/103项服务完整检查结果不变；本次无生产代码修改，不重复全套回归。原生PDF渲染、物理平板、笔/踏板、真实团队和排练仍未验收。注册、个人批注隔离与逐谱授权已上线；没有开发多团队或收费。下一项按编号清单处理非商业范围，先核对编号13扩位兼容方案。
