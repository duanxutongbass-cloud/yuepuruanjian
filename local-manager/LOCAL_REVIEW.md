# 本地可视复核原型

本批建立在 PM 限定通过的 PR #3 / `168d8c4ebaf7c12b6e221b5be42c3c71cfec4b1f` 上，仅加可替换本地可视层。现有 Workflow、确认摘要、回执和事务保持原实现；不选择最终 Windows UI 栈，不改 Android 或 R2，不提供新授权写入。

## 使用

先通过 [CLI](WORKFLOW.md) 创建并编辑草稿；保持所选源文件原位。使用同一个 Python 环境，从仓库根目录运行：

```sh
PYTHONPATH=local-manager python -m score_manager.local_review \
  --db /absolute/workbench.sqlite --draft DRAFT_UUID \
  --renderer /absolute/trusted/pdftocairo
```

服务只绑定 `127.0.0.1`；端口默认随机，不能传入监听地址。仅在这台机器的浏览器打开控制台打印的完整 URL。片段中的随机本机会话标识不会作为 URL 请求发送给服务器，页面读取后从地址栏移除；它防止无意跨来源操作，不是用户账号或生产认证。关闭命令停止服务。本环境的回环地址不是用户电脑可访问的部署地址。

1. 左侧查看管理员完整待提交变更：每个操作的参数、结果、源文件指纹，以及所有资产行的 before/after、原 PDF 版本字节指纹和页几何差异。折叠项可逐一展开；无静默截断，不只显示 operationCount。恢复包的每一项新增资产也进入差异。摘要与成员预览从同一个只读快照生成，对应同一个已有 confirmationHash。
2. 选择模拟成员。右侧只列现有 Domain 允许的声部，分别显示演奏选择；管理员和默认乐器不授予查看权。点击源页按钮查看真实 PDF 渲染的单页 PNG。选项仅来自批准页段，服务器还独立复核成员/声部/绑定/原页/精确版本。更换成员、重核验、错误、保存或取消立即清除旧图像并撤销 Blob URL；迟到响应不能恢复旧成员图像。
3. 勾选“已复核”再确认保存。服务器仍使用 Workflow.confirm 的摘要核验和资产/回执单事务；重复确认只返回原回执。取消调用原 Workflow.cancel。过期草稿、修改后的来源或冻结保护不会因使用页面而绕过。断开页面不会撤销已经提交的事务，以正式库回执为准；可用原 CLI status/confirm 恢复查询或幂等重试。

## 管理员边界与 PDF 路径

整个页面、所有摘要接口和服务器都属于受信本机管理员环境。管理员摘要可包含完整母库元数据，**不能把此页面或会话发给成员**。成员切换只是管理员模拟，不是服务端身份权限边界。没有账号、授权编辑、后台、上传、成员客户端、下载母 PDF 或任意文件路由。

取图时在同一只读快照内重新调用原 Workflow 校验，然后确认请求页属于当前成员允许的声部绑定，才读取该精确原版本 BLOB。母 PDF 仅写入本机私有临时目录，交给显式指定的本地 renderer；工具只渲染该页，长边最多 1200 像素、15 秒超时、输出最多 8 MiB。发送 PNG 前再次检查所选源文件。临时目录在成功/失败/超时后清理；不写回源 PDF、不生成配送 PDF、不把母文件发给浏览器。

HTTP 无目录浏览/CORS，严格限制 Host、Origin、Sec-Fetch-Site 和随机本机会话请求头，API 仅接受限定 JSON POST；静态资源只有三份。响应 no-store，脚本不使用 innerHTML 或外部 CDN，CSP 不允许 eval/内联脚本/外部连接/嵌框。这里不是多用户安全服务，也不是任意恶意 PDF 的隔离执行沙箱。连接线程处理浏览器预连接，领域操作由单锁串行执行；全量核验优先保证小批次可审查性，未做大库性能验收。

## 依赖与范围

基础 pypdf 锁定版本不变。可视层额外调用本机已安装的 Poppler `pdftocairo`，通过显式绝对路径注入，不下载、不打包、不再分发其二进制；本次实际版本 25.03.0。后续 Windows 安装来源、许可证分发及永久引擎选择另行决策，本批不作该承诺。工具缺失时拒绝启动，不能伪造页预览。

官方来源：[Poppler](https://poppler.freedesktop.org/)；参数含义：[Debian pdftocairo 手册](https://manpages.debian.org/testing/poppler-utils/pdftocairo.1.en.html)。本机 `/usr/share/doc/poppler-utils/copyright` 列有 GPL-2/GPL-3 等组件条款。只把现成工具作为本机可选适配器；本批未做再分发许可结论。

浏览器 QA 使用环境已安装 Playwright 1.62.0 和 Chromium（版本记入证据），均仅为测试工具，不是原型运行依赖。没有安装网站框架、Node 包或新 Python 运行包；Sites 流程仅应用本地预览，不注册/发布/部署。

## 复现和证据

完整套件仍使用 `tests/run_validation.py 新目录`，新增 `test_local_review.py` 对原摘要、HTTP 请求及原子确认作回归；环境无可选 renderer 时该组会明确跳过，必须补装可信工具后才能声称可视层验证通过。独立真实浏览器验证：

```sh
PYTHONPATH=local-manager:local-manager/tests python local-manager/tests/check_review_browser.py /tmp/new-browser-evidence
```

该 QA 脚本需要解释器可导入已安装 Playwright，当前脚本测试适配器使用 `/usr/bin/chromium` 与 `/usr/bin/pdftocairo`，只在本 Linux 环境实测。它生成全新五页合成谱面标记，逐页不同且明确注明 SYNTHETIC；不读取真实曲谱。检查管理员无授权、授权第 2/3 页、真实图像、成员切换清图、显式/重复确认、取消、并发编号过期及原 PDF 变化拒绝，并保存桌面/窄屏/状态截图和实际 HTTP 请求结果；不保存会话标识。

完整日志、浏览器报告、已查看的截图、合成 PDF、精确补丁及远端最终 SHA 随 Library 交接包提供；不打包 SQLite/草稿/会话标识/第三方二进制。此批不实现新导入表单，草稿编辑与开放包导出沿用已有 CLI；没有整场演出/批注图层显示、真机 Windows、网络配送或整阶段验收。
