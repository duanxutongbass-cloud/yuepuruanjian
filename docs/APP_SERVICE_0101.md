# 0.10.1 内置团队服务连接

用户要求将已部署的服务地址融合进 App。Android 内置 `https://124355.xyz:18443`，登录和邀请注册界面移除服务地址输入框；管理员只需输入账号 `admin` 和自己设置的密码，成员使用管理员发放的邀请码注册。没有内置密码或登录令牌。

新安装直接使用内置 HTTPS 服务；升级不再读取旧 SharedPreferences 的服务地址。恢复加密登录前先检查其服务地址：与当前服务相同才允许联网验证，不同或无效则删除旧会话并要求重新登录，禁止把旧令牌转发到新服务。本机曲谱、设备标识及原有账号管理行为保持不变。正常证书校验和拒绝重定向继续生效。

Android 版本 0.10.1（versionCode 13），数据库仍为 5；保持此前测试包签名用于覆盖安装。

账号专项 15 项通过：登录/邀请注册不输入地址、提交后清除密码、首次安装与旧地址设置、匹配会话保留和旧服务/无效会话删除、ViewModel 使用内置地址、原有设备及权限行为、真实 Kotlin/Python 本机协议互通。新增登录页原生渲染截图已实际检查，账号/密码控件正常且无地址框。

2026-10-16 00:01 完成本批最终验证：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest` 成功；41 个测试类共 **240 项，0 失败、0 错误、0 跳过**。Lint **0 错误、14 警告**，少于上批 15 项（已移除地址偏好写入产生的风格提示）。实际构建日志 `work/app-builtin-service-final-build.log`，交付校验记录 `outputs/app-0.10.1-verification.json`，界面截图 `outputs/app-0.10.1-login.png`。AndroidTest APK 仅构建，没有运行物理平板测试；本批未改 NAS/电脑端，未重跑其历史回归。

首次受限构建因 Kotlin/Gradle 缓存访问被拒失败，改用同一台电脑上获准的完整构建权限。新增截图测试的两处无效导入已修正，账号专项复测成功；未跳过失败测试。

安装包：当前工作区 `outputs/yuepuruanjian-0.10.1-builtin-service.apk`，20,016,182 字节，SHA-256 `5701C865226B4663FB0324B6835BEF6587EE88FE2D609E9FCFCC2329CF1FDC30`。apksigner 校验通过，签名证书 SHA-256 `9f360690612dabcdc4c13c8fb0b89af99402a64782f8fdcba79cddfb2be745fa`，与 0.10.0 相同；aapt2 实际确认包名 `com.ensemble.scorelibrary`、versionCode 13、versionName 0.10.1。最终 APK 的 classes14.dex 中已确认包含内置 HTTPS 地址。

安装时直接覆盖此前测试版，勿先卸载；从「我的 → 团队账号与设备」输入账号和密码即可。安装包不包含 NAS 证书私钥、ACME 文件、管理员密码、真实会话或曲谱数据。源码未提交或推送。

本批只处理 App 连接配置；正式平板自动同步仍为编号 5 待开发。真实密码登录由用户在设备上验证，不在开发日志或聊天中收集密码。NAS 证书自动续期仍待配置，当前证书有效至 2027-01-13。
