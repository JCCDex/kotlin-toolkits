# C-03 修复方案：请求 nonce 防伪造响应

## 1. 问题

`window.ccdao.sendResponse(id, result)` 暴露在页面全局。任意脚本可用猜测的请求 `id`（单调递增整数）抢先调用，伪造 native 响应。影响 `eth_requestAccounts`、`personal_sign`、`eth_sendTransaction` 等所有经 `sendToNative` 的方法。

```js
// 当前 — 任何脚本都能调
window.ccdao.sendResponse(1, "0xFAKE_ACCOUNT");
window.ccdao.sendError(2, { code: 4001, message: "User rejected" });
```

## 2. 修复

为每个请求生成随机 nonce，响应时验证 nonce 才能匹配回调。恶意页面无法猜测 nonce，不能伪造响应。

```js
// Before:                 After:
// id = ++requestId        nonce = crypto.randomUUID()
// queue[id] = callback    queue[nonce] = callback
// send {id, params}       send {id, nonce, params}
// sendResponse(id, r)     sendResponse(nonce, r)
```

### 2.1 JS Provider 改动

**`sendToNative`：**

```diff
- const id = ++requestId;
- window._ccdaoRequestQueue[id] = callback;
+ const nonce = crypto.randomUUID();
+ window._ccdaoRequestQueue[nonce] = callback;

  const message = JSON.stringify({
      name: method,
      network: ...,
-     id: String(id),
+     id: String(++requestId),   // 保留 id 给 native 识别方法调用
+     nonce: nonce,               // 新增 nonce，native 回传
      params: params || []
  });
```

**`sendResponse` / `sendError`：**

```diff
- window.ccdao.sendResponse = function(id, result) {
-     const callback = window._ccdaoRequestQueue[id];
+ window.ccdao.sendResponse = function(nonce, result) {
+     const callback = window._ccdaoRequestQueue[nonce];
      if (callback) {
-         delete window._ccdaoRequestQueue[id];
+         delete window._ccdaoRequestQueue[nonce];
          callback({ result: result });
      }
  };
```

### 2.2 Native 侧改动

`WebAppInterfaceWithWebView.kt` 中所有调用 `evaluateJavascript("window.ccdao.sendResponse(...)")` 的地方，从传 `id` 改为传 `nonce`：

```diff
// 读取 native 请求消息时解析 nonce
- val id = json.optString("id")
+ val id = json.optString("id")      // 保留，用于日志/追踪
+ val nonce = json.optString("nonce")

// 响应时传 nonce 而不是 id
- webView.evaluateJavascript("window.ccdao.sendResponse('$id', $escapedResult)", null)
+ webView.evaluateJavascript("window.ccdao.sendResponse('$nonce', $escapedResult)", null)

- webView.evaluateJavascript("window.ccdao.sendError('$id', $escapedError)", null)
+ webView.evaluateJavascript("window.ccdao.sendError('$nonce', $escapedError)", null)
```

### 2.3 `isSafeUrl` 硬错误

当前 `isSafeUrl` 校验在 `WebAppInterface.postMessage()` 中返回 `true`/`false`，DApp 可用无效 URL 探测请求 ID 范围。

建议：安全校验失败时**不响应**（不做任何 evaluateJavascript），不泄露任何信息。

## 3. 安全性分析

| 威胁 | Before | After |
|------|--------|-------|
| 猜 ID 伪造响应 | ✅ 可攻击（id=1,2,3...） | ❌ nonce 随机 128-bit，猜中概率 ~0 |
| 回放旧响应 | ✅ 可攻击（queue 不清除） | ❌ 一次性 nonce，用完即删 |
| 跨请求混淆 | ✅ 可攻击（id 碰撞） | ❌ UUID 避免碰撞 |

## 4. 兼容性

- ~~`sendResponse` / `sendError` 仍暴露在 `window.ccdao` 上~~（**已移除，C-03 第二阶段**）
- 请求消息含 `nonce` 字段；Native 经 `WebMessagePort` 回传 JSON `{nonce, result|error}`
- Host 必须在注入 `ccdao-eip1193-provider.js` 后调用 `WebAppInterface.installResponseChannel()`
- `id` 字段保留但不再用于回调匹配，仅用于日志/追踪

## 5. 工作量（历史 nonce 阶段）

约 30 行改动，涉及 2 个文件：
- `ccdao-eip1193-provider.js`（~10 行）
- `WebAppInterfaceWithWebView.kt`（~15 处 `sendResponse`/`sendError` 调用，每处改参数名）

### 兼容性（nonce 阶段）

`crypto.randomUUID()` 需要 WebView Chrome 92+（API 30+ 理论支持，但旧 WebView 可能缺失）。建议加一行 polyfill：

```js
function randomUUID() {
    return crypto.randomUUID?.() ??
        'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, c => {
            const r = crypto.getRandomValues(new Uint8Array(1))[0];
            return (c === 'x' ? r & 15 : (r & 0x3 | 0x8)).toString(16);
        });
}
```

## 6. 第二阶段：WebMessagePort（2026-08-03）

| 组件 | 改动 |
|------|------|
| `NativeResponseChannel` | `createWebMessageChannel` + `postWebMessage(HANDSHAKE, port)`；`postMessage` JSON 信封 |
| `ccdao-eip1193-provider.js` | 闭包 `requestQueue`；监听 `__CCDAO_NATIVE_PORT__`；删除 `window.ccdao.sendResponse/Error` |
| Apps | `evaluateJavascript(provider) { wai.installResponseChannel() }` |

威胁模型：页面无法再调用全局完成回调；也无法从 `window` 读取请求队列。仍须防同源 XSS 对闭包变量的调试器级访问（与一般 Web 威胁一致）。

## 7. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-28 | 初版 |
| v1.1 | 2026-07-28 | 补充 `crypto.randomUUID()` polyfill 兼容性说明 |
| v2 | 2026-08-03 | WebMessagePort 回传；移除 window sendResponse/Error |
