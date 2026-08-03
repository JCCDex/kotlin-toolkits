# DApp Connect SDK（`kotlin-toolkits/:dapp-connect`）

供 Android WebView 与 DApp 通过 `window.ethereum` / `window.ccdao` 进行连接、签名和交易的能力。包括中间件层（EVM / SWTC）、账户/密钥/节点/NFT 提供者接口，以及 EIP-1193 provider JS 注入。

## 1. 模块与关键类

- **统一入口**：`com.jccdex.toolkits.dappconnect.DAppConnectSdk`
- **WebAppInterface**：`WebAppInterface` / `WebAppInterfaceWithWebView` — JS `_tw_.postMessage` 的接收端，路由到对应中间件
- **中间件**：
  - `middleware.EthMiddleware` — EVM 链的 `eth_requestAccounts`、`eth_sendTransaction`、`wallet_switchEthereumChain` 等
  - `middleware.SwtcMiddleware` — SWTC 链的 `swtc_requestAccounts`、`swtc_requestNfts` 等
- **提供者接口（Provider）**：
  - `provider.AccountProvider` — 账户列表 / 当前账户 / 按地址查找
  - `provider.SecretProvider` — 私钥获取（密码弹窗等）
  - `provider.NodeProvider` — RPC 节点 URL / gas / nonce
  - `provider.NftProvider` — NFT 列表 / 查询
  - `provider.CachingSecretProvider` — 批次内密码缓存包装器
- **数据模型**：`model.WalletAccount`、`model.ChainType` 等

## 2. 快速接入

### 2.1 基本 WebView 结构

```kotlin
val providerJs = DAppConnectSdk.loadProviderJs(context)
val initJs = DAppConnectSdk.loadInitJs("0x1", "https://eth-rpc.example.com")

val webView = WebView(context).apply {
    settings.javaScriptEnabled = true
    addJavascriptInterface(
        DAppConnectSdk.createWebAppInterface(this, eth, swtc, accounts, secrets, nfts),
        "_tw_"
    )
    webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            view?.evaluateJavascript(providerJs, null)
            view?.evaluateJavascript(initJs, null)
        }
    }
    loadUrl("https://dapp.example.com")
}
```

### 2.2 Provider 实现要点

**AccountProvider** — 提供当前链可用账户：

```kotlin
object : AccountProvider {
    override val accounts: Flow<List<WalletAccount>> = accountsFlow
    override fun getAccountsByChain(chain: ChainType) = accounts
    override val currentAccount: Flow<WalletAccount?> = currentAccountFlow
    override suspend fun getAccountByAddress(address: String) = ...
    override suspend fun setCurrentAccount(accountId: String) { ... }
    override suspend fun getAccountName(address: String) = ...
}
```

**SecretProvider** — 按需获取私钥，通常包装一层 `CachingSecretProvider` 避免批量操作重复弹密码：

```kotlin
val secretProvider = CachingSecretProvider(object : SecretProvider {
    override suspend fun getPrivateKeyForAddress(address: String, origin: String): String? {
        val pwd = showPasswordDialog()
        return vaultRepo.getPrivateKey(address, pwd.toByteArray()).toString(Charsets.UTF_8)
    }
    override suspend fun getSecretForAddress(address: String, origin: String): String? {
        val pwd = showPasswordDialog()
        return vaultRepo.getSecret(address, pwd.toByteArray()).toString(Charsets.UTF_8)
    }
})
```

`CachingSecretProvider` 的缓存策略：
- **批次内复用**：首次输入密码后，5 秒内的后续请求不再弹窗
- **绝对上限 20 秒**：超过后强制重新认证
- **生命周期清理**：通过 `clearCache()` 在切后台 / 锁屏 / 切换账户时清除

### 2.3 强制 `requestAccounts` 回调（M-06，破坏性）

生产环境 **必须** 在 ETH 与 SWTC middleware 上设置 `setRequestAccountsCallback`。未设置时 `eth_requestAccounts` / `swtc_requestAccounts` 会抛出 `UserRejectedException`（拒绝连接），不再默默返回账户。

```kotlin
eth.setRequestAccountsCallback { origin ->
    // 展示确认 UI；已授权 origin 可直接 true
    showConnectDialog(origin)
}
swtc.setRequestAccountsCallback { origin -> showConnectDialog(origin) }
```

建议按规范化 web origin（`scheme://host[:port]`）持久化授权，并在钱包重置时清除。

### 2.4 地址推送

DApp 内切换账户后，主动推送让 DApp 感知：

```kotlin
// SWTC
webView.evaluateJavascript(
    DAppConnectSdk.loadAddressJs(address, isSwtc = true), null)

// EVM
webView.evaluateJavascript(
    DAppConnectSdk.loadAddressJs(address, isSwtc = false), null)
```

### 2.5 URL 安全校验

```kotlin
if (!DAppConnectSdk.isSafeUrl(url)) {
    // 拒绝非 http/https 或格式不合法的 URL
    return
}
```

宿主须在导航时调用 `webAppInterface.setOrigin(url)`。`postMessage` **拒绝空白或非安全 origin**（M-05）；生产环境必须接线 `setOrigin`。

原生 NFT 等非 DApp 路径取 secret 使用哨兵 `WebOrigin.WALLET_INTERNAL`（不是可授权的 web origin，M-18）。

`signCredentialForDApp` 只校验 VC 结构；**用户确认须由宿主 UI 完成**后再调用（M-15）。

## 3. Provider JS 注入

SDK 内置 `ccdao-eip1193-provider.js`，实现：
- `window.ethereum` — 兼容 EIP-1193 的 EVM provider
- `window.ccdao` — CCDAO 扩展 provider（含 `request`、`on`、`removeListener`）
- `_updateSelectedAddress(address)` — 推送 EVM 地址变更，触发 `accountsChanged`
- `_updateSwtcSelectedAddress(address)` — 推送 SWTC 地址变更，触发 `swtcAccountsChanged`
- `_updateChainId(chainIdHex, rpcUrl)` — 推送链切换，触发 `chainChanged`

## 4. 测试

```bash
./gradlew :dapp-connect:testDebugUnitTest
```
