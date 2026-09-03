# Webview Bridge（`kotlin-toolkits/:webview-bridge`）

本模块提供共享的隐藏 WebView 运行时与 JS Promise 通信能力，供 `did` / `wallet` 等 SDK 复用。

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.webviewbridge.WebviewBridgeClient`
- **配置**：`com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig`
- **Promise 网关（内部）**：每个 `WebviewBridgeClient` 实例持有独立的 `PromiseGatewayImpl`，`callbackMap` 与 WebView 生命周期绑定，互不共享。

## 2. 能力说明

- 初始化隐藏 WebView
- 注入 JS 接口
- 调用 JS 方法
- 等待 Promise 结果返回
- 销毁 WebView 并清理状态

## 3. 快速接入

```kotlin
val client = WebviewBridgeClient()
client.initialize(context, WebviewBridgeConfig(bridgeUrl = androidAssetUrl("wallet-bridge.html")))
client.start()
```

常用方法：
- `callJsMethod(...)`
- `callJsMethodAs(...)`
- `destroy()`

**隔离契约（X-4 / P2-8b）**：生产路径下 wallet 与 did 共享 `SharedWebviewBridge`（单 WebView + 单 `callbackMap`，加载 `unified-bridge.html`）。测试或自定义 runtime 仍可使用独立的 `wallet-bridge.html` / `did-bridge.html` 页面。

- `WalletSdk.destroy()` 仅释放 wallet 门面，**不会**销毁共享 WebView（did 可能仍在使用）。
- 进程退出时宿主应调用 `ToolkitBridgeRuntime.shutdown()`。ccdao：`WebviewBridge.shutdownSharedBridge()`；钱包重置请用 `WebviewBridge.resetWalletAfterWipe()`（或 `destroyWallet()` + `initialize()` + `reloadSharedBridge()`），勿 shutdown 共享桥。

- **共享运行时（P2-8b）**：`SharedWebviewBridge` / `ToolkitBridgeRuntime.shutdown()` / `ToolkitBridgeRuntime.reloadSharedBridge()`
- **Promise 网关（内部）**：每个 `WebviewBridgeClient` 实例持有独立的 `PromiseGatewayImpl`

## 4. 说明

直接集成 `WebviewBridgeClient` 时使用独立实例；通过 `WalletSdk` / `DidSdk.create` 时默认走 `SharedWebviewBridge`。

**Lazy 创建**：`SharedWebviewBridge.client(context)` 在首次 wallet 或 DID JS 调用时才创建并 `start()` 隐藏 WebView（`Application.onCreate` 里的 `WalletSdk.start()` 在共享模式下可为 no-op）。

## 5. 测试

```bash
./gradlew :webview-bridge:testDebugUnitTest
```
