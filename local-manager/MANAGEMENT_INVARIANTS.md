# 本批管理写操作与 Android Domain 不变量对照

2026-10-13 PM 复审修订。对照基线 `32b702e` 的
`app/src/main/java/com/ensemble/scorelibrary/data/LibraryManagement.kt`，不修改 Android。
冻结场次是存在 `annotation_concert_snapshots` 的场次；被保护曲目由其未归档且启用的节目项决定，**不因场次归档而忽略**。

`snapshot_manifest` 用于既有版本/页段快照一致性检查，不包含所有可编辑结构。
因此新增写前 `_guard_piece` / `_guard_pdf`，在同一个 `BEGIN IMMEDIATE` 事务中查询保护集；
不能用“改完后 manifest 相同”替代写入授权。导入/导出和事务提交另做当前数据不变量校验。

| 本批公开写操作 | Android 对照与本批处理 | 回归覆盖 |
| --- | --- | --- |
| `Catalog(create=True)` | 只新建独立工作库；现有文件拒绝，不打开/迁移 Android 库 | 既有 `create_never_overwrites_existing_asset` |
| `add_member` | Member：名称裁剪、100 UTF-16 单元、唯一 ID/名称；只新增且默认声部为空，不提供降级/归档/删除管理员 API | 既有字典/独立档案，新增 `public_management_input_bounds`、`unrelated_new_records`；最后管理员变更不属于已暴露操作 |
| `add_category` / category `edit_dictionary` | Android 尚无分类列；仅写侧车，唯一两位用户分类号，稳定 ID，不修改谱/版本/节目项/冻结 payload | 既有分类改名/换号/归档/CSV、`unrelated_new_records` |
| `add_instrument` | Definition：名称裁剪、100 单元，family 裁剪/空转 null/100 单元，非负 Int 排序，包含归档记录的唯一名称/ID | 既有字典，新增 `public_management_input_bounds`、`management_labels_trim` |
| instrument `edit_dictionary` | Definition：所有使用该定义的未归档声部先 `guardPiece`，即使只是改名；归档时不得被任何未归档声部或启用成员默认声部使用 | `frozen_piece_rejects_used_instrument`、`active_part_blocks_instrument`、`active_member_default_blocks_instrument`、`unused_instrument` |
| `import_dictionary_csv` | 仅追加分类/定义，已存在 ID/代码/名称拒绝，不能利用导入绕过编辑门禁；定义沿用上述文本/排序限制；整批事务 | 既有 CSV 冲突/类型/往返；`public_management_input_bounds` 覆盖第二行失败时首行回滚 |
| `add_piece` | Piece：标题裁剪/200 单元，新 ID 才可插入；编号另外遵循全局保留规则 | 既有并发、重号、身份、边界测试；`public_management_input_bounds` |
| `assign_number` | 只改变独立侧车，不修改 Android pieces 表、PDF 身份或快照；保留全局历史占号；冻结曲目的侧车标注仍允许，不能由此改曲目结构 | 既有编号/保留号往返；`unrelated_new_records` 逐字核对冻结快照不变 |
| `archive_piece` | Piece：有任何未归档且启用节目项即拒绝，覆盖普通/冻结/已归档场次；须先处理节目项。冻结节目项在本批没有编辑入口，不能暗中解除冻结 | `enabled_unfrozen_item`、`enabled_frozen_item`、`archived_concert`、`disabled_item`、`archived_item` |
| `add_part` | Part：曲目和定义均未归档，先 `guardPiece`；包含归档记录的曲目×定义唯一；不提供转移/重建已有 part ID | `frozen_piece_rejects_unbound_part_addition`、既有同曲声部/重号；`unrelated_new_records` |
| `add_movement` | Movement：曲目未归档、`guardPiece`、非负 Int 排序，含归档记录的顺序唯一；本批仅新增，无归档/转移入口 | `frozen_piece_rejects_unbound_movement_addition`、`public_management_input_bounds`、既有跨曲页段测试 |
| `bind` | Binding：声部、所属曲目、定义、PDF、可选乐章均未归档；`guardPiece`；页段/归属与全曲/乐章组合校验；已有同声部/乐章绑定即便归档也不能换 ID 重建（包括 NULL 全曲绑定） | 既有页段/跨曲/重复组合；新增 `binding_rejects_archived_owner_piece`、`binding_does_not_replace_archived_whole_piece_binding` |
| `import_pdf` / `import_pdfs` | 新 PDF 为独立来源；给已有 PDF 加版本先 `guardPdf`，冻结引用连非当前版本追加也拒绝；版本号正 Int，不替换原字节。本批新版本先不激活，不改变当前页段；较短的非当前版本可暂存，须经显式激活检查 | 既有批量/重复/取消/失败/历史版本；`frozen_pdf_rejects_inactive_version_import`、`public_management_input_bounds` |
| `activate_version` | activateVersion：版本必须属于 PDF，`guardPdf`，实际原件 hash/长度/页数/几何检查后才能改变指针；已有启用页段不能越界；冻结场次连激活同一版本也拒绝 | 既有版本换版/回滚/页段；`frozen_pdf_rejects_activation_even_if_manifest_would_stay_equal`、`activation_checks_original_bytes` |
| `restore_archive` | 空库专用，旧 v1 身份/版本/共享层及冻结快照原样保留；当前状态新增拒绝“启用节目项→已归档曲目”“活跃声部/成员默认→已归档定义”；不存在的状态不得靠重新计算快照掩盖 | `import_rejects_enabled_archived_piece_in_unfrozen_and_frozen_packages`、`import_rejects_archived_instrument`、既有完整导入拒绝矩阵 |
| `export_bundle` | 原母库权限/私人隔离保持；导出前校验当前状态，已存在的非法归档引用也不得导出；无覆盖发布，不改数据库 | `export_rejects_preexisting_invalid_library_without_final_file`、既有个人层隔离/失败发布/目标存在 |

新拒绝路径均检查 SQLite 全部 `iterdump()` 前后相等，包含 schema 3 表、侧车/编号保留和原 PDF BLOB，不只比曲目归档字段。`validation_rolls_back_other_writes` 还先写入另一字段，再制造非法归档，证明整个事务回滚。

范围区别：本批同步本地函数每次在即时写锁下重新读引用，不提供 UI 表单快照覆盖接口；正式 UI 接入时仍须实现 expected revision/过期表单保护。成员编辑、授权/分配修改、节目项编辑/重排、快照捕获/解除等操作没有在本模块开放，不能绕过 Android 既有入口，也不能把母库恢复当作非空更新接口。SQLite 是可信本机整理存储，不是网络身份鉴权。
