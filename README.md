# Codex LAN Remote

在 Android 手机上继续操作电脑里的 Codex：发送任务、查看进度、处理审批，离开电脑也能跟进工作。

项目由 **Android 客户端**和 **Windows「手机连接」插件**组成，支持局域网直连和 EasyTier 异地组网。

[下载安装包](https://github.com/fabulouszcc/codex-lan-remote/releases) · [电脑端使用指南](docs/电脑端插件使用指南.md)

## 解决什么问题

- **离开电脑后，任务进度难以跟进**：手机同步查看聊天、任务状态和工具活动，完成或需要审批时接收通知。
- **需要回到电脑才能继续操作**：在手机发送或追加指令、中断当前任务、切换聊天，并选择电脑实际支持的模型和思考强度。
- **手机与电脑不在同一网络**：通过内置 EasyTier 组网连接，也可以沿用自己已有的组网服务。
- **连接与配对配置繁琐**：电脑提供统一设置页，手机扫码配对；使用内置 EasyTier 时可同时导入入网参数，由 DHCP 自动分配手机地址。

## 使用条件

| 设备 | 要求 |
| --- | --- |
| 电脑 | Windows x64，已安装 Codex 桌面端 |
| 手机 | Android 9 或更新版本，ARM64 |
| 网络 | 同一局域网，或通过 EasyTier 等方式互通 |

远控聊天时，电脑需要保持开机，Codex 桌面端需要运行，并允许手机访问对应聊天。

## 安装与连接

### 1. 安装电脑插件

**交给 Codex 安装**：在 Windows 电脑上的 Codex 中新建聊天，复制发送下面这段话：

```text
请帮我在这台 Windows 电脑上安装 Codex LAN Remote「手机连接」插件：
https://github.com/fabulouszcc/codex-lan-remote

请从最新 Release 下载 Windows 插件 ZIP，完整解压到固定目录，运行 install-desktop-plugin.ps1 完成安装；如果已经安装，请升级并保留现有配置、证书和手机授权。
安装完成后，在 Codex 右侧面板打开「手机连接设置」，并提供同一 Release 的 Android APK 下载链接，方便我在手机上安装和扫码配对。
```

如出现 Windows 授权提示，按提示确认即可。手机 APK 仍需在手机上安装。

**手动安装**：

从 [Releases](https://github.com/fabulouszcc/codex-lan-remote/releases) 下载 Windows 插件 ZIP，完整解压，在解压目录执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\install-desktop-plugin.ps1
```

首次安装需要联网补齐依赖。升级时运行新版安装器即可，已有连接配置、证书和手机授权会保留。请保留解压目录。

安装完成后，通过以下任一入口打开设置：

- 在 Codex 中启用「手机连接」插件，选择「打开手机连接设置」，在右侧面板管理连接。
- 打开桌面或开始菜单中的「手机连接设置」。

### 2. 选择连接方式

| 连接方式 | 适用场景 | 配置方法 |
| --- | --- | --- |
| 局域网直连 | 手机和电脑连接同一路由器 | 选择「局域网 / 已有网络」，选中电脑的 Wi-Fi 或以太网地址 |
| 已有组网 | 已使用 EasyTier 或其他组网工具 | 手机先加入对应网络，再在「局域网 / 已有网络」中选择电脑的虚拟网卡地址 |
| 内置 EasyTier | 希望由插件和手机 App 管理组网 | 选择「内置 EasyTier」，填写网络名称、密钥、电脑虚拟 IPv4/前缀及组网节点 |

点击「保存并启用」。内置 EasyTier 创建虚拟网卡时，如需管理员权限，使用设置页的「请求管理员启动」，并确认 Windows 提示。

插件只管理自己启动的 EasyTier 实例，已有系统组网服务由你原来的工具管理。

### 3. 安装手机 App 并扫码配对

1. 从同一 Release 下载 Android APK 并安装。
2. 在电脑设置页生成配对二维码。
3. 手机首次打开 App，点击「扫码添加电脑」，扫描二维码；也支持从图片识别或粘贴完整配对信息。
4. 确认配对。使用内置 EasyTier 时，确认导入网络参数并允许 Android VPN 授权，手机自动获取虚拟地址后连接电脑。
5. 连接成功后选择聊天，即可查看记录、发送任务和处理审批。

已有组网模式只完成远控配对，不会导入或接管外部组网配置。

### 4. 日常使用

- 在手机顶部切换连接方式，在设置中修改 EasyTier 参数。
- 通过模型菜单调整电脑支持的模型和思考强度，通过右侧记录栏快速定位聊天内容。
- 为 App 开启通知权限；如需顶部横幅，在系统设置中开启悬浮通知，勿扰模式会影响提醒。
- 在电脑设置中开启「保持后台运行，登录 Windows 时自动恢复」，让连接后台独立运行，重启 Codex 后仍可使用。
- 不再使用的手机可在电脑设置中撤销授权。

配对二维码包含连接凭据，内置 EasyTier 二维码还包含组网密钥，请只向自己的手机展示。

## 从源码编译

安装包以 Releases 标注的版本为准；需要源码中的最新功能时，可自行构建。

### Windows 插件

需要 Windows、Node.js 24 和 npm。打包脚本会下载并校验固定版本的依赖。

```powershell
npm ci
npm test
.\scripts\build-desktop-plugin.ps1
```

生成的插件目录和 ZIP 位于 `.local/releases/`。解压后的安装方式与上文一致。

### Android App

需要 JDK 17、Gradle 8.9 和 Android SDK 35。可指定已安装的工具路径：

```powershell
.\scripts\build-android.ps1 `
  -SdkPath "C:\Android\Sdk" `
  -JavaPath "C:\Java\jdk-17" `
  -GradlePath "C:\Gradle\gradle-8.9\bin\gradle.bat"
```

脚本会准备固定版本的 EasyTier 原生库、运行状态层测试，并将 APK 输出到 `.local/releases/`。自行构建使用本机签名；覆盖安装旧版需要使用同一签名。

本机配置、签名文件、证书、组网密钥及构建产物均不应提交到仓库。

## 说明

这是非官方预览项目，使用 Codex 桌面内部接口，桌面升级可能影响兼容性。中断聊天不保证终止已经启动的底层命令。

第三方组件保留各自许可证，来源和许可随安装包提供。
