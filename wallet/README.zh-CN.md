# Wallet SDK（`kotlin-toolkits/:wallet`）

本 SDK 提供钱包相关的 WebView Bridge 能力，主要包括：
- 助记词生成
- 助记词校验
- 派生子钱包
- 从私钥 / 助记词派生地址
- 地址校验
- 签名与验签

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.wallet.sdk.WalletSdk`
- **WebView runtime**：
  - `com.jccdex.toolkits.wallet.sdk.AndroidWalletWebRuntime`
  - `com.jccdex.toolkits.wallet.sdk.IWalletBridge`

## 2. 快速接入

```kotlin
WalletSdk.initialize(context)
WalletSdk.start()
```

常用 API：
- `generateMnemonic()`
- `validateMnemonic(mnemonic)`
- `deriveChild(...)`
- `deriveFromMnemonic(...)`
- `deriveFromPrivateKey(...)`
- `signMessage(...)`
- `signTransaction(...)`

## 3. 说明

该 SDK 通过隐藏 WebView 调用钱包 JS 能力。
接入方通常只需要在首次使用前调用 `initialize(context)`。

进程退出或不再使用钱包能力时，调用 `WalletSdk.destroy()` 释放 wallet 门面。

默认与 `DidSdk` 共享单个隐藏 WebView（`unified-bridge.html`）；`WalletSdk.destroy()` **不会**销毁该 WebView。进程退出前宿主应调用 `ToolkitBridgeRuntime.shutdown()`（ccdao：`WebviewBridge.shutdownSharedBridge()`）。钱包重置请用 `WebviewBridge.resetWalletAfterWipe(context)`，勿 shutdown 共享桥。

首次 JS 调用时才 lazy 创建共享 WebView；`WalletSdk.initialize()` / `start()` 仅准备 Kotlin facade。

## 4. 测试

```bash
./gradlew :wallet:testDebugUnitTest
```
