# Codex LAN Remote

用于 Android + EasyTier + Codex 桌面本地插件方案的接口验证和设计工作区。

非官方开发预览项目。Windows x64 桌面插件与 Android ARM64 客户端配合使用，连接电脑上已经运行的 Codex 桌面端。

当前版本为 Android 0.3.3 / 电脑插件 0.3.3：可从电脑实际可用列表选择模型和思考强度，聊天右侧增加记录定位栏，Codex 回复使用气泡，底部同步任务与思考状态。工具按每轮对话合并折叠，网络切换入口位于顶部连接状态；聊天使用可回收列表和按需加载工具详情。电脑提供桌面和开始菜单“手机连接设置”入口。已在真机验证并安装。详见 [界面升级与验收](docs/界面升级与验收.md)。

[下载 0.3.3 Preview](https://github.com/fabulouszcc/codex-lan-remote/releases/tag/v0.3.3-preview)：[Windows 电脑插件 ZIP](https://github.com/fabulouszcc/codex-lan-remote/releases/download/v0.3.3-preview/codex-lan-desktop-0.3.3-preview.zip) 和 [Android ARM64 APK](https://github.com/fabulouszcc/codex-lan-remote/releases/download/v0.3.3-preview/codex-lan-0.3.3-preview.apk)。Android 源码位于 `android/`。电脑端解压后执行 `install-desktop-plugin.ps1`，详细步骤见下方使用指南。Android 安装后可选择局域网直连，或自行配置 EasyTier，再与电脑配对。安装包不携带开发者的组网配置、配对授权、证书或聊天记录，SHA-256 位于 Release 介绍中。

当前交付包括桌面 IPC 适配器、HTTPS 配对与连接服务、控制流程测试驱动、ARM64 Android 预览 APK，以及可安装的 Windows 电脑端“手机连接”插件。

Android 与电脑插件当前均为 0.3.3-preview。已在小米 14 Ultra 验证聊天同步、停止、模型设置与记录定位；此前版本验证过完成/审批横幅和取消审批，开启勿扰时可能仅出现在通知列表。电脑支持已有网络/内置 EasyTier 配置、加密保存密钥、配对码与二维码、授权撤销、连接状态及后台管理。

- [本机接口验证与限制](docs/接口验证.md)
- [电脑端插件安装与使用](docs/电脑端插件使用指南.md)
- [电脑端插件实现与验收](docs/电脑端插件实现与验收.md)
- [已有网络 / 内置 EasyTier 与配置入口设计](docs/插件设计.md)
- [桌面实时接入：1 份快照、29 批连续增量](docs/桌面实时接入验证.md)
- [完整控制流程实测与进程停止限制](docs/完整控制流程验证.md)
- [小米 14 Ultra 安装、组网与配对指南](docs/手机测试指南.md)
- [Moonlight V+ 内置 EasyTier 源码核查](docs/Moonlight内置EasyTier参考.md)
- [0.2 升级、修复原因与验证](docs/0.2升级与验证.md)
- `server/desktop-ipc.mjs`：连接现有桌面的内部协调管道，发现持有者、读取设置、订阅状态。
- `scripts/verify-desktop-ipc.mjs`：实测现有桌面的状态订阅，不创建第二个 app-server。
- `scripts/verify-interfaces.mjs`：初始化、历史、可选恢复、Schema 和 EasyTier 状态探测。
- `scripts/verify-shared-runtime.mjs`：双客户端与临时 app-server 实验。

连接服务使用 Node.js 24，无 npm 运行依赖。TLS 测试需要 JDK keytool；Android 构建需要 JDK 17、Gradle 8.9、SDK 35 和两个固定校验值的 EasyTier 原生库，见手机测试指南。机器相关输出和生成的协议文件放入 `.local/`，不会提交到版本库。`.Codex/` 按用户项目约定保留；其中 settings.json 当前为空，不代表已经注册了 Codex 插件。

```powershell
npm test
npm run verify:desktop -- --thread <桌面聊天ID> --duration-ms 20000
```

HTTPS 验证脚本需要先在本机设置 `CODEX_LAN_TEST_THREAD`；控制回归还需要 `CODEX_LAN_TEST_WORKSPACE`。历史切换验证需要 `CODEX_LAN_SECOND_TEST_THREAD`。这些值只使用自己明确授权的专用测试聊天，实际调用可能消耗模型额度。不要提交本机环境变量、`.local/`、证书、密钥或 EasyTier 配置。

IPC 适配器目前针对 Windows 桌面 `26.1007.9446.0` 中观察到的内部协议，默认只读，写操作需要明确限定聊天。18 项 Node 自动化测试通过；提交、继续、排队、steer、会话打断、真实命令/文件审批、客户端重连及记录落盘已实测。HTTPS 配对、发送、流式结果、重试去重与撤销授权的 9 项本机联调检查通过；Android 配置校验的独立 Java 检查通过。

0.2 增量验证：22 项 Node 测试、7 项 Android 状态层 JUnit 测试通过；HTTPS 控制回归 12 项、无模型消息的多聊天历史验证 7 项通过。新 APK 和 0.1 签名一致，可覆盖安装。

电脑端当前通过 29 项 Node 测试、安装后 MCP/页面检查和真实 HTTPS 收发回归；Windows 新建证书与 Node TLS、EasyTier 固定版本配置检查通过。原电脑证书和手机授权已迁移到插件数据目录，当前手机连接由插件后台管理。

重要限制：会话打断没有保证停止底层命令。内置 EasyTier 创建虚拟网卡和与用户网络互联仍需填写参数并按需确认 UAC 后验收；长期锁屏、自动发现和工具清单同步未全部验证。此本地预览包未上架公开插件目录。


