# 本地 main 合并记录

日期：2026-10-14。用户明确要求把已完成开发分支合并进下载的本地仓库。

## 合并范围

- `integration/android-http-offline`：`34744d4`，包含批注 R2、Android 曲库/真实预览/冷灰界面和 HTTP 离线测试闭环。
- `feat/android-sync-state-core`：`b2ea782`，核心代码与 HTTP 分支内的副本一致；保留独立分支提交历史和审查报告。
- `feature/phase10-1-local-review-20261013`：`b47491c`，包含离线管理核心、CLI 草稿工作流、可视复核与竞态修复；更早本地管理分支已包含在其祖先中。

生产代码无冲突。PROJECT_STATE、PRODUCT_ROADMAP、PHASE_HISTORY 三份文档有冲突，保留 Android 和本地管理双边历史，更新当前合并状态。其他历史报告中的未合并说明保留其当时语义。

## 本次实际验证

Windows / Python 3.12.14，使用本机已提供的 pypdf 6.10.0：

- 本地管理 unittest：116 项发现，105 项通过、11 项跳过，0 失败/错误，106.408 秒。跳过的 11 项要求 pdftocairo，本机仅提供 pdfinfo/pdftoppm。
- HTTP 服务端 unittest：6 项通过，0 失败/错误/跳过，3.119 秒；真实本机 HTTP 请求覆盖身份/授权/撤权/版本/字节和 Range。
- git diff --check 通过，无残留冲突标记。
- app/ 和 test-service/ 与 `34744d4` 一致；local-manager/ 与 `b47491c` 一致。Android 和本地管理源码没有因合并改变。

尝试安装仓库锁定 pypdf 6.20.1 时网络套接字被环境拒绝，未修改依赖锁定文件。上述测试使用 6.10.0，不能代称锁定依赖环境验收。

本机没有可用 JDK、Gradle 或 Android SDK，未重跑 Android APK/JVM/Lint/模拟器，也未安装或覆盖用户 Pad。各开发分支原有通过结果仍属于历史批次，不能计入本次测试总数。后续在 Android 开发环境按 test-service/README.md 重跑构建及测试。

## 交付边界

已合并本地 main，保留 Git 历史。此次未推送远端，不改变 GitHub PR 状态。版本 0.9.6、code 8、schema 3 不变。临时 HTTP 适配器仅启用于 syncTest；debug/release 保持本地入口。

合并不是发布或部署验收。NAS/生产认证/公网、完整自动同步和个人批注上传、Windows 安装包、七人试运行与物理设备长期验收仍未完成。
