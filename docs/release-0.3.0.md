# Codex LAN Remote 0.3.0 Preview

非官方 Windows x64 桌面插件预览版，供 Android 客户端连接本机 Codex 桌面端。

## 下载与安装

下载并完整解压 codex-lan-desktop-0.3.0-preview.zip，在 PowerShell 运行根目录 install-desktop-plugin.ps1。首次安装需联网补齐官方 EasyTier 原生依赖。详细步骤见源码 docs/电脑端插件使用指南.md。

Android 手机下载 `codex-lan-0.2.4-preview.apk`（Android 9+、ARM64），内置 EasyTier。此 APK 与已经实测的 0.2.4 文件和签名完全一致，可覆盖安装同签名旧版。它是开发调试签名预览包，未配置组网参数或手机授权；第三方原生库来源和许可证可在 App 中查看。原生组件的完整可复现构建仍待补齐。

## 功能

- 已有局域网/自建异地组网与插件内置 EasyTier 两种模式。
- 组网配置、DPAPI 加密密钥、配对码与二维码、设备授权撤销。
- 与 Android 客户端同步聊天，发送任务、追加、打断、处理审批。

发布包从干净源码构建，不包含开发者的网络名称、密钥、节点、连接地址、证书、手机授权或聊天记录。使用者需自行配置和配对。

## 限制

需要已运行的 Codex Windows 桌面端；依赖已验证版本的内部接口，升级后可能需要适配。内置 EasyTier 创建网卡可能需要 UAC。停止会话不能保证终止底层项目进程；长期锁屏、自动发现、工具清单同步尚未全部验收。此版本未上架官方插件目录。

此 Release 同时提供电脑插件 ZIP 与 Android APK，二者版本号分别为 0.3.0 和 0.2.4。Android 源码位于 android/。第三方组件的许可证及固定来源包含在发布包 licenses/ 中。

