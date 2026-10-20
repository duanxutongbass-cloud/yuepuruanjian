# 编号13：扩位编号（0.10.22 / Windows 0.1.3）

旧001–999编号、曲目身份、原PDF和路径保持。1000起使用显式分隔格式，例如`01-01-1000-天空之城.pdf`；上限9223372036854775807，接口和存储使用规范十进制字符串。新曲逐个选择全库真正未占用的最小空号；所有状态、merged历史、退休台账及批次显式编号占号，不自动合并、覆盖或改号。

## Windows

完整解压0.1.3程序目录，双击启动整理工具.cmd。同一用户可读取原旧库；先备份原数据库、原PDF和双目录。关闭其他整理器，在“数据管理→升级编号容量（独立副本）…”选择独立目标并核对确认。程序复制并核验数据库和双目录，再激活副本；原库保留。之后默认记住扩位库。扩位指针无效、库身份变化或库缺失会阻断，不自动回退旧库。保留的旧库可用`启动保留旧库.cmd`明确打开；不要让两份库同时成为业务母库。

源代码启动：`python -m score_organizer.main`；显式打开已激活库：`python -m score_organizer.main --expanded-library <library.sqlite3>`。新版可读取旧三位和新扩位文件；旧程序拒绝扩位数据库。新库上传只调用/v2，逐次核对同乐团管理员及服务ready能力，遇旧服务即停止，不降级、不截号。

## Android

安装同签名0.10.22（34）APK，保留原应用数据。联网后重新核对当前团队身份；协议由鉴权服务明确返回。支持扩位编号导入、最小空号规划、团队上传、授权下载、节目单冻结、Room关联、排序、备份及加密离线恢复。Room仍6。旧服务仍使用v1；扩位服务只走v2编号接口，写入前核验能力。

## NAS

数据库保持4，命名格式升2，使用独立目录和显式激活身份。普通`--permanent`入口保持旧格式；扩位使用`--expanded`。只有激活库可返回expandedPublicationReady=true；准备副本不具备发布能力。

旧DB4/命名1完整备份为score-team-backup-v3；扩位DB4/命名2完整备份为score-team-backup-v4。恢复只能进入全新目录，再显式激活并合并当前权威退休台账。旧恢复器拒绝新备份，禁止降级或绕过退休记录。CLI包括prepare-expanded、activate-expanded（--confirmed --source-stopped）、backup-full、verify-expanded、restore-expanded。

部署顺序：预构建并核对源码/工具/APK SHA → 确认旧写入停止 → 当次原数据库和完整备份 → 独立旧格式恢复并核验 → 独立扩位准备/激活并核验业务行、身份、授权、退休台账和全部PDF → 新格式完整备份及第二次独立恢复 → 保留原卷/目录/镜像后切换 → 健康与私有鉴权检查 → 发布APK并完整公网下载核对大小和SHA。任何步骤缺证据都不能标记发布完成。新服务已接受写入后禁止自动切回旧数据库。

## 验证与验收

自动化、隔离测试和实际Windows候选启动结果以交付证据为准；失败和复测日志均保留。真实Windows母库、平板阅读/手写笔/离线重连以及实际管理员上传由用户本人验收；未收到反馈不得填写真机通过。NAS的上线结果另见部署证明，本文的操作说明不等于已经部署。

Python服务测试需requirements.txt、pytest、pypdf及电脑运行依赖；把desktop-organizer/src和desktop-organizer/tests加入PYTHONPATH（服务真实HTTP测试复用电脑归档夹具）。电脑使用pytest和QT_QPA_PLATFORM=offscreen；打包使用PyInstaller与packaging/score_organizer.spec。Android使用Java21/Gradle8.14.3，执行testDebugUnitTest、assembleDebug、assembleDebugAndroidTest、lintDebug；Python互操作夹具需TEAM_SERVICE_TEST_PYTHON和服务Python依赖。
