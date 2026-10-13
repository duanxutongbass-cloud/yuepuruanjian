# 本地资产备份格式 v1

这是独立可读的开放数据格式，不是数据库文件拷贝，不依赖在线服务或专有解密接口。Android 当前支持 formatVersion 1 / databaseVersion 3。未识别版本或字段拒绝恢复；后续 schema/编号/分类演进必须明确兼容转换和旧档恢复验证。

0.9.6 扩展了批注符号集合，InkCodec 容器/源页归一化锚点不变。旧 0.9.5 可能拒绝含新增符号的包；请保留原备份并用 0.9.6 或兼容后续版本恢复，不能静默删除不认识的符号。格式 v1 不保证旧客户端能理解后续新增的批注值。

## ZIP 布局

```text
manifest.json             UTF-8 JSON
README.txt                简短独立恢复说明
files/<version-id-hash>.pdf  仅母库，原始 PDF 字节
```

文件名中的 hash 是原 versionId 的 UTF-8 SHA-256，实际 PDF 内容 SHA-256 单独记录。只接受上述已声明条目，无目录、无重复或额外文件；解包不使用条目路径创建目标路径，而使用本次私有暂存文件及随机 managed 文件名。

## 清单字段

| 字段 | 定义 |
| --- | --- |
| format | `ensemble-score-library` |
| formatVersion / databaseVersion | 整数 1 / 3 |
| kind | `LIBRARY` 或 `PERSONAL` |
| owner | 原导出成员 String ID，不能占用 `shared` 保留标识 |
| createdAtEpochMillis | 导出快照时间，毫秒 |
| tables | 表名到行对象数组；列名使用 schema 3 的原名称，null 保留，SQLite INTEGER 使用整数，布尔为 0/1，枚举为字符串 |
| files | `{versionId, entry, bytes, sha256}` 数组，仅母库 |
| versionReferences | `{id, sha256, pageCount}` 数组，仅个人批注包的最小版本引用 |

母库 tables 包含：pieces、part_definitions、members、movements、piece_parts、score_pdfs、score_pdf_versions、part_score_bindings、member_part_permissions、member_piece_part_permissions、piece_part_assignments、concerts、concert_items、annotation_pages、annotation_revisions、annotation_concert_snapshots。共享页 ownerKey 为 shared；个人页不在母库中。版本 managedFilePath 为对应 ZIP entry，不保留旧机器绝对路径；恢复重新定位。完整列定义参照工程导出的 Room schema 3 JSON，不把 Room 私有文件作为恢复前提。

个人包 tables 只有 annotation_pages，所有 ownerKey 等于 owner，publishedRevisionId 必须为空；files 必须为空。个人原版本 identity/hash/pageCount 与恢复目标匹配后导入，不修改授权。共享发布记录通过 documentId / publishedRevisionId 原身份关联；冻结 payload / manifest 原样保存，不重新捕获最新发布。

批注 payload 是现有 InkCodec JSON 字符串：标记 id/kind/color/width/symbol 与归一化 points；pageIndex 为 PDF 零基源页，不是绑定中的相对页号。需要独立处理时再次解析 payload 字符串。activeVersionId 是唯一当前版本字段，不以最高版本号替代。

## 校验与上限

- ZIP 条目数最多 5,002（最多 5,000 个 PDF）；清单最多 16 MiB、README 最多 64 KiB、单 PDF 最多 256 MiB、PDF 累计及 ZIP 输入各最多 2 GiB；这些是拒绝上限，不是已验证性能规模。元数据读取和 JSON 嵌套也有限制，超限明确失败。
- 所有条目核对 ZIP CRC，PDF 流式核对长度和 SHA-256，原生打开实际全部页并核对 pageCount；Room 外键/唯一约束与业务结构在隔离暂存库校验。
- 恢复确认绑定整包 SHA-256，防止预览后源 URI 变更；CRC/SHA-256 仅证明完整性，不是签名或身份认证，ZIP 不加密。
- 不恢复缓存路径/状态、账号密钥、设备键映射、未提交笔画、会话阅读页或撤销栈。被归档成员的本人包恢复需先显式恢复其成员记录，不自动解除归档。

## 独立取回与恢复

母库 PDF 可用标准 ZIP 工具直接取出；依据 files.versionId 关联 score_pdf_versions，再关联 score_pdfs、part_score_bindings 与曲目/声部。保留原 ID、版本与源页，不按文件名猜声部或搬移批注坐标。

完整 App 恢复先在空安装的“我的”选择母库并确认，再选择原成员分别恢复其个人包。已有库不执行覆盖；需保留其独立备份，再在隔离安装恢复。默认不存在管理员汇总其他成员个人层的恢复入口。

`tools/Verify-AssetArchive.py` 使用 Python 标准库，只读检查 ZIP/JSON、文件 SHA-256 和 owner 过滤，不提取文件、不执行 README，也不替代完整 Room/Domain 校验。示例：

```text
python tools/Verify-AssetArchive.py <archive.zip>
```

定期保留独立离线副本并实际演练恢复。文件只放在原设备或同一 NAS 并非独立灾难备份；不能承诺没有备份时任何情况都可找回。
