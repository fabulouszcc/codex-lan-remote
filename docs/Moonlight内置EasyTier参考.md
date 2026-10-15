# Moonlight V+ 内置 EasyTier 实现核查

核查日期：2026-10-15。源码固定在 `qiin2333/moonlight-vplus` 的 `799f50d6e7bd288cd69fe6df2081c9d9502c45b4`。这是源码与二进制静态检查，不是手机实测。

## 实现链路

1. `app/src/main/jniLibs/arm64-v8a/` 直接存放两个预编译库：`libeasytier_android_jni.so`（6,292,512 字节）和 `libeasytier_ffi.so`（17,934,504 字节）。Android 构建将其装入 APK；不依赖另装 EasyTier App。
2. `EasyTierJNI.kt` 调用 `System.loadLibrary("easytier_android_jni")`，提供解析配置、启动实例、状态采集、停止实例和设置 TUN FD 的 JNI 方法。
3. `EasyTierController.kt` 提供配置/状态 UI，`EasyTierTomlCodec.kt` 把表单转换为 TOML，包含网络名称、密钥、静态 IPv4、监听和 peer URI。
4. `EasyTierManager.kt` 启动原生实例，每 3 秒采集状态，提取虚拟 IPv4 和代理路由，按变化启动或重建 VPN。
5. `EasyTierVpnService.kt` 继承 Android `VpnService`，建立系统 TUN 后调用 `setTunFd`，让原生 EasyTier 处理虚拟网络数据。

## 已直接检查的二进制事实

使用 Android NDK 的 `llvm-readelf` 检查：JNI 库依赖 `libeasytier_ffi.so` 和 Android 系统库；FFI 库只依赖 `libc/libdl/libm`。JNI 导出的方法与本项目声明的六个入口对应。两个库的 LOAD 段均为 `0x4000` 对齐。这里只说明二进制结构，不等同于 Android 运行兼容性验收。

| 库 | SHA-256 |
| --- | --- |
| libeasytier_android_jni.so | ce6c9e8e7643c30fb2bb28c24e9118cacca31df90c2e6a1aa18fb5d15986de4c |
| libeasytier_ffi.so | 9be5f96d99e530e1817d86f9ae29645ee4dc7c0c6a156e1bc0c90900ea96d70e |

## 本项目的采用范围

预览版使用该固定提交的未修改 EasyTier 二进制，独立编写 Java 配置、VPN 生命周期和聊天代码，没有复制 Moonlight 的应用控制器或 UI 实现。`scripts/prepare-easytier.ps1` 下载和校验这两个库；构建所用文件位于被忽略的 `.local/native/easytier/`。APK 附带参考仓库提供的 EasyTier LGPL-3.0/GPL-3.0 许可文本，并提供来源入口。

参考仓库的应用源码是 GPL-3.0；它为 EasyTier 提供独立许可说明。当前没有确认这两个预编译库对应的 EasyTier 上游精确源码提交，不能标为官方某个版本的自行编译产物。正式发布前需补齐可对应的原生源码和构建流程；当前提供的是本机开发测试预览。

与参考实现不同，本项目不输出完整配置或原始网络状态到日志；组网配置用 Keystore 加密；VPN 由显式用户操作启动并提供前台通知。首版只支持静态 IPv4 和对应虚拟子网路由，不自动应用远端代理网段，也不接管默认互联网路由。

## 源码位置

- [原生库](https://github.com/qiin2333/moonlight-vplus/tree/799f50d6e7bd288cd69fe6df2081c9d9502c45b4/app/src/main/jniLibs/arm64-v8a)
- [JNI 接口](https://github.com/qiin2333/moonlight-vplus/blob/799f50d6e7bd288cd69fe6df2081c9d9502c45b4/app/src/main/java/com/easytier/jni/EasyTierJNI.kt)
- [实例管理](https://github.com/qiin2333/moonlight-vplus/blob/799f50d6e7bd288cd69fe6df2081c9d9502c45b4/app/src/main/java/com/easytier/jni/EasyTierManager.kt)
- [Android VPN](https://github.com/qiin2333/moonlight-vplus/blob/799f50d6e7bd288cd69fe6df2081c9d9502c45b4/app/src/main/java/com/easytier/jni/EasyTierVpnService.kt)
- [配置序列化](https://github.com/qiin2333/moonlight-vplus/blob/799f50d6e7bd288cd69fe6df2081c9d9502c45b4/app/src/main/java/com/limelight/utils/easytier/EasyTierTomlCodec.kt)
