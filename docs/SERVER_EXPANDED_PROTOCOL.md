2026-10-19 更新：DB4/命名2显式激活、v2三端和NAS0.10.22已发布，准备副本仍不具备发布能力。完整说明见 [NUMBER13_RELEASE.md](NUMBER13_RELEASE.md)，下方保留候选协议的设计与历史验证。

# 编号13：隔离服务的扩位上传与读取协议

2026-10-19 接手核对：当前已发布服务0.10.19/库4、App0.10.21(33)，下方库2/服务17为历史。v2候选仍只接受库3，不能用于降级生产库4。接手基线协议/曲库22项通过；发现v2 number-inventory误调用v1盘点器，实际含1000号请求426。已修复为显式v2盘点：完整十进制编号精确排序，现有/待上传/退休号占用，最小空号按占号集合扫描，可用数量以十进制字符串返回；v1不放宽。修复后相关28项及另3项边界通过，未完成全服务回归，原缺失187项最终证明仍不补造。新Work outputs/backend-expanded-inventory-verification.json保存本轮证据。库4备份/恢复激活、电脑及Android、正式GUI仍待整合，不开放ready。

本机候选组件，尚未打包、开放启动入口或部署NAS。线上仍为服务0.10.17/库2、App0.10.18；生产电脑库0/v1/999，1000以上尚未开放。

`expanded_protocol.ExpandedApplication`只接受已通过存储身份/目录门禁的`ExpandedCandidateService`库3。复用原鉴权、分谱上传、原子发布、逐PDF授权、节目单及冻结实现，通过显式`/v2/`路径处理完整编号。普通Application、CLI及Docker的默认入口不变，既有库不自动迁移。候选模块不在现有映像COPY列表中。

## 路径与兼容策略

候选的`/v2/catalog`、`/v2/artifacts/{UUID}`、`/v2/admin/uploads`及其content、publications、grants、artifacts、ensemble、programs、catalog-actions、program-freezes等路径使用扩位解析器。JSON/PDF请求容量、会话/设备/成员资格、管理员权限、HTTPS、无Origin和无身份覆盖规则仍由原入口执行。

在此显式候选应用上，对应`/v1/`编号读写路径在账号鉴权之后、读取JSON/PDF之前返回426和`reader_numbering_upgrade_required`。不会把1000截成000，不会只返回旧号而让旧客户端误认为新谱已被撤回。UUID身份的账号、批注、个人文件夹等其他原协议继续保留。每个请求复制自己的WSGI环境并覆盖内部版本标识，线程间不共享当前读取模式。

`/v1/admin/capabilities`仍只返回v1、999、`expandedPublicationReady=false`。候选`/v2/admin/capabilities`如实返回protocolVersion2、namingVersions[1,2]、字符串上限9223372036854775807、candidateOnly=true，且ready仍为false。此组件完成服务端读写，不代表电脑传输、Android、正式迁移和启动验收都已完成；现有电脑扩位发布器仍会在未就绪能力处停止，不因本组件自动开始写入。

## 编号与数据行为

写入只接受旧七位规范号（001–999）或明确分隔的新号，例如`01-01-1000`。完整序号以十进制字符串传输和保存，拒绝溢出、前导零、全角数字和含糊的八位连续数字。原001–999编号、UUID和文件保持。

节目单曲目键使用完整`sequence:1000`，曲组编号使用完整序号并按精确整数排序。新声部补入同曲时沿用完整序号；同号不同曲/分类、同声部重复和批内冲突均拒绝。批量发布和直接发布共用写锁内检查，回收站编号仍占用。第二版必须由调用方明确安排新号和标题，不自动合并、创建版本或覆盖PDF。

v2曲库元数据保留完整scoreNumber，并明确返回namingVersion及字符串sequence。PDF仍逐份独立授权，管理员总库不会自动授权给所有成员。慢上传结束后重新检查当前账号及上传规格；撤权后的旧发布回执重试不重新授权。两个并发重复发布只有一个成功，原始PDF均保留。

新冻结快照使用protocolVersion2，保留节目单指定次序和完整曲目键；读取按当前每份PDF授权投影，撤权即不再返回该分谱。已迁移的旧冻结清单不改写，仍保留protocolVersion1及原内容。实际通过此接口上传的最大64位编号与PDF可经完整v2归档备份、独立新目录恢复后读取。

## 验证

首轮新增12项通过（105.47秒），补充并发发布和上传结束后的会话过期检查后，相关71项通过（287.09秒）。代码复核发现上传规格会先对编号执行标签trim，因此补为鉴权后直接验证原始scoreNumber，避免默许两端空格；新增非规范输入用例后，14项通过（119.42秒）。随后发现独立Catalog入口未创建回收站表，新共用校验查询会报错；中止初次完整回归，使用原有test_catalog明确复现1失败（6.25秒）。修复只按实际表是否存在构造占号查询，不新增schema；22项曲库/扩位复测166.59秒通过，最终187项完整回归执行中。各轮输入快照、失败/中止日志保留，不将修改前测试冒充最终源码结果。最终结果以父outputs/backend-expanded-protocol-verification.json为准。

全部为Windows临时合成账号/PDF及localhost真实HTTP；不代表NAS容器、Android扩位、真实用户库迁移、另一电脑或千份队列吞吐已验收。下一项接电脑端显式v2传输和Android完整编号处理，再完成正式启动/GUI与旧EXE隔离。三端闭合之前不翻转ready、不递增空版本或发布扩位包。

后续接入定位：电脑端`services/team_publish.py`现有prepare/content/publications仍使用v1路径，`expanded_team_publish.py`和`domain/publication_capabilities.py`仍要求v1能力声明；需显式v2传输与能力契约，不在基础TeamClient中自动猜测或降级。Android除`TeamScoreClient`的曲目身份/清单/下载地址外，`UploadNumberPlan`、`TeamPublisher`、`TeamPublishScreen`和`TeamLibrarySync`仍使用七位/末三位规则，`OrganizerNaming`字典/文件名仍为v1；须连同备份、Room持久引用、节目单冻结和旧缓存恢复一并核对，不能只改清单正则。
