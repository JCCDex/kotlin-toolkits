# C-04 修复方案：私钥/助记词在 WebView JavaScript 堆中处理

## 1. 问题

签名操作在隐藏 WebView 中通过 JS 完成。私钥、助记词、secret 以明文序列化进 `JSONObject`，经 `evaluateJavascript` 传入 JS 执行。密钥在 JS 堆中停留；`wallet-bridge.js` 存在 `console.log` 交易对象；`WebviewBridgeClient` 将所有 JS console 转发到 `Log.d`。

```kotlin
// WalletSdk.kt — 私钥以明文传入 JS
val params = JSONObject().apply {
    put("privateKey", privateKey)  // ← 明文进入 JS 堆
    put("tx", txParams)
}
WebviewBridge.callJsMethod("signEthTransaction", params)
```

## 2. 修复：短期（可立即实施）

### 2.1 生产环境禁用 console 转发

```kotlin
// WebviewBridgeClient.kt
override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "JS: ${msg.message()}")
    }
    return true  // 生产环境吞掉所有 JS console
}
```

### 2.2 清理 bridge JS 中的 debug log

`wallet-bridge.js` 中移除 `console.log(txObject)` 等调试日志。

### 2.3 `evaluateJavascript` 注入点脱敏

所有通过 `evaluateJavascript` 传回的 JS 回调中，在 release 构建里只记录 `method + id/nonce`：

```kotlin
// WebAppInterfaceWithWebView.kt
override fun sendSuccessResponse(network: String, nonce: String, result: Any?) {
    if (BuildConfig.DEBUG) {
        Log.d(TAG, "Success: network=$network, nonce=$nonce, result=$resultStr")
    }
    // ... 注入 JS 回调
}
```

### 2.4 WebView 设置加固

```kotlin
// WebviewBridgeEngine.kt — WebView 初始化时
settings.apply {
    // 禁用文件访问
    allowFileAccess = false
    allowContentAccess = true  // 加载 assets 需要
    // 禁用 JS 访问 file:// URL
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}
```

## 3. 修复：长期（架构迁移，不做本次）

签名完全迁移到 Native / Android Keystore。JS 桥接仅用于 DApp 通信，不接触密钥。

## 4. 工作量

| 改动 | 文件 | 行数 |
|------|------|------|
| console 转发加 `BuildConfig.DEBUG` 守卫 | `WebviewBridgeClient.kt` | ~3 行 |
| 移除 JS debug log | `wallet-bridge.js` | ~5 行 |
| release 构建脱敏日志 | `WebAppInterfaceWithWebView.kt` | ~10 行 |
| WebView 设置加固 | `WebviewBridgeEngine.kt` | ~3 行 |

总计 ~20 行，4 个文件。

## 5. 注意事项

C-04 短期修复降低风险（减少 logcat/内存泄露面），但**不能根除**——只要签名还在 WebView JS 里做，密钥就会短暂出现在 JS 堆中。根除需要 C-04 长期方案（签名迁 Native），属于架构级重构，不在本次范围。

## 6. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-28 | 初版 |
