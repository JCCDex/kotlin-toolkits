# Webview Bridge（`kotlin-toolkits/:webview-bridge`）

本模块提供共享的隐藏 WebView 运行时与 JS Promise 通信能力，供 `did` / `wallet` 等 SDK 复用。

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.webviewbridge.WebviewBridgeEngine`
- **客户端**：`com.jccdex.toolkits.webviewbridge.WebviewBridgeClient`
- **配置**：`com.jccdex.toolkits.webviewbridge.WebviewBridgeConfig`
- **Promise 网关**：
  - `JsPromiseGateway`
  - `PromiseGatewayImpl`

## 2. 能力说明

- 初始化隐藏 WebView
- 注入 JS 接口
- 调用 JS 方法
- 等待 Promise 结果返回
- 销毁 WebView 并清理状态

## 3. 快速接入

```kotlin
WebviewBridgeEngine.initialize(context)
WebviewBridgeEngine.start()
```

常用方法：
- `callJsMethod(...)`
- `callJsMethodAs(...)`
- `destroy()`

## 4. 说明

如果你使用 `did` 或 `wallet` 的默认 Android 实现，通常不需要直接操作这个模块。
它主要作为底层桥接层存在。

## 5. 测试

```bash
./gradlew :webview-bridge:testDebugUnitTest
```
