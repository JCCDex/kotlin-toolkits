# H 级问题修复方案

## H-01：CachingSecretProvider 缓存键应包含 origin

**问题：** 缓存键 `pk:$address` 不含 origin。同一地址在 A 站授权后，5–20 秒窗口内 B 站也能用缓存的私钥。

**修复：** 缓存键改为 `pk:$origin|$address` / `sec:$origin|$address`。每次 DApp 切换页面时 `clearCache()`。

```kotlin
// CachingSecretProvider.kt
private fun cacheKey(prefix: String, origin: String, address: String): String =
    "$prefix$origin|$address"

fun clearCache() { pkCache.clear(); secCache.clear() }
```

**文件：** `CachingSecretProvider.kt`，~10 行。**对 app 影响：** 无。

---

## H-02：EVM 操作应全链路传递 origin

**问题：** `signTransaction`、`signTypedData`、`decrypt`、`getEncryptionPublicKey` 的 handler 传空字符串 `""` 而非真实 origin。

**修复：** 4 个 handler 加上 `getOrigin()` 参数透传。Native 侧已有 `getOrigin()`，只需在 handler 中读取并传递。

```kotlin
// 修复前
val privateKey = secretProvider.getPrivateKeyForAddress(address, "")

// 修复后
val privateKey = secretProvider.getPrivateKeyForAddress(address, getOrigin())
```

**文件：** `WebAppInterface.kt`，4 处改动。**对 app 影响：** 无。

---

## H-03：evaluateJavascript 参数应 JSON 转义

**问题：** `$id`、`$resultStr` 以字符串插值嵌入 JS，特殊字符可逃逸单引号注入。

**修复：** `loadInitJs` 的参数用 `JSONObject.quote()` 包裹；`sendResponse` 中 `resultStr` 已处理，`$nonce` 本身就是 UUID 无特殊字符。主要风险点在 `loadInitJs` / `loadAddressJs` 中未转义的 `chainIdHex`、`rpcUrl`、`address`。

```kotlin
// DAppConnectSdk.kt
fun loadInitJs(chainIdHex: String, rpcUrl: String): String {
    return """
(function () {
  window._ccdaoProviderState.chainId = ${JSONObject.quote(chainIdHex)};
  window._ccdaoProviderState.rpcUrl = ${JSONObject.quote(rpcUrl)};
})();
"""
}
fun loadAddressJs(address: String, isSwtc: Boolean): String {
    val fn = if (isSwtc) "_updateSwtcSelectedAddress" else "_updateSelectedAddress"
    return "if (window.$fn) { window.$fn(${JSONObject.quote(address)}); }"
}
```

**文件：** `DAppConnectSdk.kt`，3 处。**对 app 影响：** 无。

---

## H-04：getPrivateKeyInternal / getMnemonicInternal 应限制访问

**问题：** 这两个 public 方法绕过密码认证；且 `derivedKey()` 在无 session 时仍回退磁盘 `derivedKey`，未真正关闭「未解锁可读密钥」。已有外部调用方（`AccountOrchestrator`、`ExportBackupUseCase`、jdid 派生/取钥）。

**修复：** 见专用方案 [H04_VAULT_INTERNAL_SESSION_FIX.md](./H04_VAULT_INTERNAL_SESSION_FIX.md)。

要点：

1. `require(isUnlocked)` + 去掉磁盘 key 回退；unlock 时迁移并清空旧 `derivedKey`。  
2. 产品模型：冷启动解锁一次，会话内派生子钱包免密（对齐市面钱包）。  
3. jdid：复用现有解锁页，补 `vault.unlock()` / `lock()`。  
4. ccdao：新增进入 App 解锁页。  
5. 长期将 `*Internal` 改为 `internal`。

**状态：** ⏳ 待实施（C-01 Phase 1 仅部分覆盖，H-04 未关闭）。

---

## H-05：bindVcidToDid 应验证凭证签名

**问题：** `bindVcidToDid` 仅检查 `id` 非空，不调用 `verifyCredential()`。攻击者可构造无签名假 VC 绑定到 DID。

**修复：** 在 `bindVcidToDid` 中调用 `queryAndValidateVcid` 的验证逻辑（或直接复用），验证通过后才发布。

```kotlin
// DidSdk.kt — bindVcidToDid
val vcResult = queryAndValidateVcid(vcid, credentialJson)
if (!vcResult.valid) throw IllegalArgumentException("Invalid credential")
// ... 发布
```

**文件：** `DidSdk.kt`，~10 行。**对 app 影响：** 可能改变 bind 行为的严格度，已在链上的无效 VC 会绑定失败。

---

## H-06：NFT 元数据 SSRF 防护

**问题：** 从链上 URL 拉取 NFT metadata 时无 hostname 校验，可被诱导请求内网地址。

**修复：** 在 `NftRemoteAssetResolver` 和 `SwtcChainNftClient` 中添加 URL 校验：

```kotlin
private fun isSafeUrl(url: String): Boolean {
    val uri = URI(url)
    if (uri.scheme !in setOf("https", "ipfs")) return false
    val host = uri.host ?: return false
    val addr = InetAddress.getByName(host)
    if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress) return false
    return true
}
```

**文件：** `NftRemoteAssetResolver.kt`、`SwtcChainNftClient.kt`，~20 行。**对 app 影响：** 如果 NFT 托管在局域网 IP 上会拉取失败（属安全预期）。

---

## H-07：敏感信息日志脱敏

**问题：** `postMessage` 完整 JSON 打到 logcat；bridge JS 中有 console.log 交易对象。

**修复：**
- `postMessage` 日志改为只记 `method + nonce`（release 构建）
- bridge JS 的 console.log 已于 C-04 清理
- console 转发已于 C-04 加 `BuildConfig.DEBUG` 守卫

剩余工作：`postMessage` 脱敏：

```kotlin
// WebAppInterface.kt
open fun postMessage(json: String) {
    if (BuildConfig.DEBUG) Log.d(TAG, "postMessage: $json")
    else Log.d(TAG, "postMessage method=${obj.optString("name")}")
    ...
}
```

**文件：** `WebAppInterface.kt`，1 处。**对 app 影响：** 无。

---

## 改动量总览

| ID | 文件 | 行数 | 复杂度 |
|----|------|------|--------|
| H-01 | `CachingSecretProvider.kt` | ~10 | 低 |
| H-02 | `WebAppInterface.kt` | ~4 处 | 低 |
| H-03 | `DAppConnectSdk.kt` | ~3 处 | 低 |
| H-04 | — | 0 | 已由 C-01 覆盖 |
| H-05 | `DidSdk.kt` | ~10 | 中 |
| H-06 | `NftRemoteAssetResolver.kt` + `SwtcChainNftClient.kt` | ~20 | 中 |
| H-07 | `WebAppInterface.kt` | ~2 | 低 |

总计 ~50 行，7 个问题。

## 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-29 | 初版 |
