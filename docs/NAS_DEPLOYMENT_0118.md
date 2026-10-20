# 0.10.18 NAS发布记录：已完成

**当前实际结果（2026-10-18 04:34，覆盖下文历史准备状态）：** NAS服务升级任务03:41:10–03:42:08正常0；新映像yuepu-team-api:0.10.17、同数据卷、库2完整性ok/外键0，实际源码/原业务/PDF和隔离六位码HTTP验证通过。旧映像121.5MB、原/目标compose和新完整账号/PDF备份、独立恢复均保留于pre-0118-root-20261017T194111Z。详见父outputs/nas-0118-service-upgrade-success-20261018.json。

同一自动执行关闭的任务改用已上传发布脚本，SHA256 acc0ee718ef4f619a9d287a66a91a97b7b6583c69357877141a647961e50da97；04:29:54–55实际正常0。Docker入口仅调用UID10001的现有应用发布器，再次核对13份运行文件、备份文件/恢复证明、库2/PDF、六位码HTTP及实际APK后发布30；不再重启或迁库。旧指针另存/data/backups/pre-0118-root-20261017T194111Z/pre-publication-manifest.json，结果在同目录publish-0118-proof.json。私有文件未逐份独立下载，来源为已审SHA固定脚本正常0，不冒充另行读回其内容。

独立公网正常TLS、无代理、无重定向清单200/版本30；完整APK下载21,072,686字节，SHA256 3a4fac1dcbec5e4d7b2502fd91a360bedd259b1eed4ca024b408b7e47ca2bbfe，ETag/Content-Length均一致，17私有接口未登录401，空注册400。实际证明父outputs/nas-0.10.18-live-verification.json、完整下载nas-0.10.18-download-verified.apk及回执nas-0118-publication-task-success-20261018.png；冻结本机候选报告保持原样。1000以上编号尚未开放；平板18已安装，实体手写笔和更多真实谱仍待用户验收。下面是保留的部署步骤与历史检查点，旧“尚未上传/等待密码”不代表当前状态。

**最新root执行检查点：** 原项目YAML及旧映像ID已保存，releases内服务ZIP实读48145字节/e32163…e736、18份清单核对通过，工具包f2c48a…4910已解压到本次私人备份tools。用户明确允许本次root服务升级，创建任务Yuepu 0118 service upgrade once，自动执行保持关闭；本人首次复核后首轮03:14:01–02退出1，日志23字节，未形成新切换目录。具体前置失败未定位，首轮不算升级成功。attempt2只加trace和原YAML的LF/CRLF等价内容校验，SHA7acf7afd6d4b783f6484b243c565136938731adc25419fa4c5589458067f214c，已上传并填入同一任务；当前保存触发第二次DSM密码复核，待用户本人提交后先确认保存和停用状态，再执行。无需再问root升级授权。证明父outputs/nas-0118-root-task-checkpoint-20261018.json。新脚本先校验18份实际host源码、导出旧映像、构建成功，再建立当次新备份/独立恢复后切换；runtime与隔离HTTP全部通过才继续APK18步骤，当前未发布。

**2026-10-18最新实际检查点：** 管理标签已恢复可操作，旧映像0.10.15/DSM良好、非root UID10001、运行源码旧版、库2完整性ok外键0已实读。当次私人备份/data/backups/release-20261017T172846Z中账号完整库及PDF ZIP核验与独立恢复通过（1份/575844字节），旧清单与源码SHA另存；详见父outputs/nas-0118-backup-checkpoint-20261018.json和截图。17服务ZIP已上传并解压，实际源码目录为/volume1/docker/yuepuruanjian-team-service/yuepuruanjian-team-service-0.10.17/yuepuruanjian-team-service-0.10.17/team-service（注意双层同名目录，不能套用旧预案路径）。尚未切换映像/上传18 APK/改发布指针。项目配置与旧镜像保留、远端精确SHA及后续门槛仍待完成；原compose.yaml下载超时，续做改用Container Manager项目编辑器读取，不重复失败下载或要求用户再登录。下文旧“没有新备份/入口”文字为历史准备状态，以本条及证明为准。

以父outputs/app-0.10.18-verification.json为本机封存证明，不能将本页或构建通过当成NAS已更新。线上最后实际验证发布为0.10.16（28），当前控制入口没有新的可用状态，本轮未重试旧失败绑定、上传、终端或证书处理。

## 本次应使用的文件

| 用途 | 文件 | 精确大小 / SHA256 |
| --- | --- | --- |
| App更新 | yuepuruanjian-0.10.18-admin-library.apk | 21,072,686字节 / 3a4fac1dcbec5e4d7b2502fd91a360bedd259b1eed4ca024b408b7e47ca2bbfe |
| 服务升级 | yuepuruanjian-team-service-0.10.17.zip | 48,145字节 / e32163b586dc8b6fc7bb3684e611df3426d37ae06d6d19e347a8ef6eae60e736 |
| 源码留存 | yuepuruanjian-0.10.18-source-snapshot.zip | 1,450,236字节 / a6456459a2511eb9c0583fbc08cea580f870578b763513ad4dc6a8dfe4e4451e |

App编号30/版本0.10.18，签名9f360690612dabcdc4c13c8fb0b89af99402a64782f8fdcba79cddfb2be745fa，Room仍6。服务源码与0.10.17候选18份生产/配置文件完全一致，数据库仍2；由线上16首次升级时仍要升级此服务，才能使用六位乐团码。没有额外的0.10.18服务源码变更，不能为此空迁库。仪器测试APK只用于开发验收，不是用户更新包。

## 发布门槛与顺序

1. 仅在出现新的可用管理入口后接手。先读当前真实容器/镜像/YAML/挂载、运行源码SHA、数据库版本/完整性/外键及线上发布清单，不能由旧报告推断当前状态；状态有差异先保留现场并核对。
2. 按[0.10.17部署准备](NAS_DEPLOYMENT_0117.md)的第1–6步建立**当次新的完整备份**，独立恢复验证，保存旧清单、镜像、项目配置和数据。私人备份不放公开releases，不用历史备份目录冒充本次证明。
3. 若服务仍旧，部署17服务包到独立目录/镜像，核验实际解压结构，仅变更已核实的build/image；不套用通用compose覆盖端口、卷或代理。若实际已经是目标服务，也必须以运行源码/DB/健康证明核实；不重复迁库或无故重启。原账号/PDF/编号/授权保留。
4. 实际健康、运行源码18份清单、库2/integrity=ok/foreign_key_check=0及隔离六位码验证齐全后，上传并核对上述**18 APK**的大小/SHA。保留旧包/旧镜像/旧数据，不用旧文档中的29发布命令；当前目标是30。
5. 再备份当前发布指针，通过实际运行的server.py发布30并保留回执。示例仅在前述门槛全部完成且容器挂载已核实时适用：

```sh
python /app/server.py --data /data publish-app --apk /releases/yuepuruanjian-0.10.18-admin-library.apk --version-code 30 --version-name 0.10.18 --notes '默认先入管理员总库，取消选择50份上限，自动分批、进度恢复和部分取消；包含17版阅读、编号和布局修复，真实平板继续验收'
```

6. 用正常TLS、拒绝重定向从公网读清单并完整下载主APK，精确核对版本30、大小/SHA、ETag/Content-Length及17个私有入口未登录401，新增本次公开证明。只有上述实际结果齐全才写“已发布”，App内正常更新提示随后才可出现；不能绕过证书警告来制造证明。

失败时保存现场及新数据，不直接让旧镜像读取未核实的新库，也不重新初始化或覆盖data卷。按实际库版本和当次独立恢复证明选择兼容恢复路径。当前没有18版的新NAS备份、运行源码/健康或公网整包证明，仍为未上传候选。
