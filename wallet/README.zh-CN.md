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

## 4. 测试

```bash
./gradlew :wallet:testDebugUnitTest
```
