# kotlin-toolkits 安全审计报告

| 项目 | 内容 |
|------|------|
| **审计对象** | kotlin-toolkits（含后续新增 `:apk-verify`、`:app-update`） |
| **初审日期** | 2026-07-22 |
| **复审日期** | 2026-07-31（对照 PR `#16` / `e98c2a0..69f69c1`） |
| **审计类型** | 静态代码安全审计 + 修复复审 |
| **审计范围** | 密钥保管、WebView 桥接、DApp 连接、账户/DID/NFT；复审覆盖审计修复与新增模块 |

---

## 1. 执行摘要

kotlin-toolkits 是一套面向 Android 的钱包/DID/NFT 工具库，涉及助记词、私钥、签名与 DApp 交互等高风险能力。

### 1.0 初审结论（2026-07-22）

- 外层 Tink + Keystore 合理；内层因 **derivedKey 落盘** 与 **可逆密码 proof** 被严重削弱。
- DApp 层 origin / 响应完整性 / JS 注入存在缺口。
- 存在无密码擦除、`bindVcidToDid` 未验签等问题。

| 初审严重程度 | 数量 |
|----------|------|
| Critical | 5 |
| High | 7 |
| Medium | 18（M-01～M-18） |
| Low | 12 |

### 1.1 修复复审结论（2026-07-31）

PR `#16`（`fix: C/H/M-level security audit fixes…`）**实质推进**了多项修复（VaultSession、HMAC proof、nonce、origin 缓存、VC 验签、编排锁与私钥不出模型等），但 **未完全收口** C-05 / H-02 / H-03 / H-06 / H-07 / M-06；并在 DApp 响应通道引入 **1 个新的 Medium 问题（R-01）**。

**复审状态图例：** ✅ 已关闭 · 🟨 部分修复 · ❌ 未关闭 / 新发现 · 📌 有意兼容残留

| ID | 复审状态 | 说明 |
|----|----------|------|
| C-01 | 🟨 | VaultSession + unlock；磁盘 `derivedKey` 仍保留至首次 unlock 迁移 |
| C-02 | ✅ | HMAC proof；校验路径重跑 Argon2 |
| C-03 | 🟨 | UUID nonce；`sendResponse`/`sendError` 仍挂在 `window` |
| C-04 | 🟨 | 导航/文件访问收紧、部分日志关闭；密钥仍进 JS（长期项） |
| C-05 | 📌/❌ | vault 可选密码擦除；**Orchestrator 仍无密码调用 `clearAllData()`** |
| H-01 | ✅ | 缓存键含 `origin\|address` |
| H-02 | 🟨 | 多条路径已传 origin；**`eth_sendTransaction` 仍丢 origin** |
| H-03 | 🟨 | `load*Js` 已 `jsQuote`；**响应里 `nonce` 未转义（见 R-01）** |
| H-04 | 🟨 | 需解锁会话；`*Internal` 仍 public |
| H-05 | ✅ | `bindVcidToDid` 强制 `verifyCredential` |
| H-06 | 🟨 | `SsrfGuard` 用于 resolver；**`NftStore.fetch*` 未守卫；DNS fail-open** |
| H-07 | 🟨 | 部分日志关闭；成功响应 / bridge 仍有残留 |
| M-01 | 🟨 | Argon2 重算有；无速率限制 |
| M-02 | ✅ | `importPrivateKeys` 加 mutex |
| M-03 | ✅ | changePassword 逐条 wipe |
| M-04 | 🟨 | 有 blob 大小上限；无字段级 limit |
| M-05 | 🟨 | origin 非空才 `isSafeUrl`；空 origin 可跳过 |
| M-06 | 🟨 | ETH 可选回调；未设置则仍无授权；SWTC 无对等回调 |
| M-07 | ✅ | bridge 仅允许 asset URL |
| M-08 | ✅ | `allowFileAccess = false` |
| M-09 | ❌ | Room 仍明文（未在本轮） |
| M-10 | ✅ | `deriveSubAccount` mutex |
| M-11 | ✅ | pending 用 ConcurrentHashMap |
| M-12 | ✅ | `setCurrentAccount` 校验存在 |
| M-13 | ✅ | preferredAvatar 校验 credential |
| M-14 | 🟨 | 先验密码；缺失账户仍 Success |
| M-15 | 🟨 | 结构校验；无库内用户确认 |
| M-16 | ❌ | 无 pinning（未在本轮） |
| M-17 | ✅ | 派生不再向外返回私钥 |
| M-18 | 🟨 | DApp SWTC 传 origin；内部 NFT 路径用 `wallet_internal` |
| **R-01** | ❌ **新** | `WebAppInterfaceWithWebView` 拼接未转义 `nonce` → JS 注入 |

**复审后建议优先阅读 / 跟进顺序（供逐项了解）：**

1. R-01（新洞，配合 H-03）
2. H-02（`eth_sendTransaction` origin）
3. C-05（Orchestrator 擦除）
4. H-06（NftStore + DNS fail-open）
5. H-07 / M-06 / C-03 / C-04（残余）

相关修复方案文档见 [docs/README.md](./README.md)。

---

## 2. 审计范围与方法

### 2.1 模块与风险等级

| 模块 | 职责 | 风险等级 |
|------|------|----------|
| `:vault` | 加密密钥库（Tink + Argon2id + Protobuf） | 极高 |
| `:dapp-connect` | DApp WebView 桥接、EIP-1193 Provider | 极高 |
| `:webview-bridge` | 隐藏 WebView 运行时、JS 资产宿主 | 高 |
| `:wallet` | 助记词/派生/签名（经 WebView） | 高 |
| `:did` | DID 文档、凭证签发/验证/绑定 | 高 |
| `:account` | 账户元数据存储与编排 | 中高 |
| `:nft` | NFT 元数据缓存与远程拉取 | 中 |
| `:core` | 共享领域模型 | 低 |

### 2.2 审计方法

- 静态源码审查（Kotlin、JavaScript、Protobuf）
- 跨模块调用链分析（AccountOrchestrator → VaultRepository → WalletSdk/DidSdk）
- 威胁建模：离线窃取、root/进程内攻击、恶意 DApp、弱密码暴力破解、内存取证、SSRF

### 2.3 未覆盖项

- 动态渗透测试、Fuzzing
- 第三方 JS 库（`jcc-wallet`、`did-0.3.2.min.js`）源码审计
- 集成方（宿主 App）的 UI 授权实现

---

## 3. 发现项详情

### 3.1 Critical（严重）

#### C-01：Vault 将派生密钥明文持久化，密码保护层形同虚设

| 字段 | 内容 |
|------|------|
| **文件** | `vault/src/main/java/com/jccdex/toolkits/vault/VaultRepository.kt` |
| **行号** | 79, 468, 531 |
| **Proto** | `vault/src/main/proto/private_key_vault.proto`（`derived_key` 字段） |

**描述：** Argon2id 派生出的 AES 密钥以 hex 字符串写入 `Vault.derivedKey`。后续所有加解密（`importPrivateKey`、`getPrivateKey`、`changePassword` 等）均通过 `derivedKey()` 读取，**不再从密码重新派生**。一旦外层 Tink/Keystore 被攻破（root、进程内恶意代码、内存 dump），攻击者可在不知道用户密码的情况下解密所有私钥、助记词和 secret。

**修复建议：**

1. 从 protobuf schema 移除 `derived_key`（需数据迁移）。
2. 每次使用时从 `password + salt + params` 重新派生密钥，用后立即 wipe。
3. 如需性能，用 Keystore/生物识别包裹短期会话密钥，禁止持久化派生密钥。

**状态：** 🟨 Phase 1 已完成（2026-07-28）。SDK 新增 `VaultSession` + `unlock()` / `lock()` / `isUnlocked`。解锁后密钥在内存；`unlock` 成功会清理磁盘上的 `derivedKey`。proto 字段仍保留以兼容迁移。详见 [`VAULT_SESSION_REDESIGN.md`](./VAULT_SESSION_REDESIGN.md)。

**复审（2026-07-31）：** 会话模型有效降低「落盘 derivedKey 永久可用」风险；未解锁不可解密。残余：旧 vault 在首次 unlock 前磁盘上仍可能有 derivedKey；进程内解锁后仍可读全部密钥。

---

#### C-02：密码以可逆形式存储

| 字段 | 内容 |
|------|------|
| **文件** | `vault/src/main/java/com/jccdex/toolkits/vault/VaultRepository.kt` |
| **行号** | 61–72, 142–149, 454–464 |
| **Proto** | `PasswordEntry.proof_iv`、`proof_ct` |

**描述：** `initializePassword` 用派生密钥加密原始密码并存入 `proof_iv` / `proof_ct`。结合已存储的 `derivedKey`，攻击者可解密并恢复明文密码。

**修复建议：**

1. 改用不可逆验证（如 HMAC-SHA256 固定域分隔值，或 Argon2 输出常量时间比较）。
2. 禁止存储可逆加密的密码副本。
3. 现有 vault 升级时迁移 proof 格式。

**修复方案：** 详见 [`C02_PASSWORD_PROOF_FIX.md`](./C02_PASSWORD_PROOF_FIX.md)。核心思路：用 `HMAC-SHA256(derivedKey, domain_separator)` 替代 AES-GCM 加密密码。proof 不再包含密码原文，只能验证不能恢复。旧格式 vault 在 changePassword 时自动迁移。

> C-02 是对 C-01 的防御性加固：C-01 解决 derivedKey 落盘后，proof 的敏感度已大幅降低；C-02 确保即使 derivedKey 在进程内泄漏，proof 也不会暴露明文密码。

**复审（2026-07-31）：** ✅ 已关闭。HMAC 路径校验会重跑 Argon2。

---

#### C-03：页面 JavaScript 可伪造 Native 响应回调

| 字段 | 内容 |
|------|------|
| **文件** | `dapp-connect/src/main/assets/ccdao-eip1193-provider.js` |
| **行号** | 74–88 |

**描述：** `window.ccdao.sendResponse` 与 `window.ccdao.sendError` 暴露在页面全局。任意脚本可用猜测或观察到的请求 `id` 抢先返回伪造结果，影响 `eth_requestAccounts`、`personal_sign`、`eth_sendTransaction` 等。

**修复建议：**

1. 不在 `window` 暴露完成回调；使用 `WebMessagePort` 或带 nonce 的 native→JS 单向通道。
2. 请求 ID 使用 `crypto.randomUUID()`。
3. 响应与 native 侧生成的 nonce 绑定，拒绝重复完成。

**修复方案：** 详见 [`C03_REQUEST_NONCE_FIX.md`](./C03_REQUEST_NONCE_FIX.md)。核心思路：每个请求生成 `crypto.randomUUID()` nonce，回调队列 key 从猜得到的 `id` 改为不可猜的 `nonce`，native 响应时回传 nonce。约 30 行改动，JS + Native 各一处。

**复审（2026-07-31）：** 🟨 部分修复。单调 `id` 猜测已缓解；**`window.ccdao.sendResponse` / `sendError` 仍暴露在页面**。另见 **R-01**（响应侧 `nonce` 未转义）。

---

#### C-04：私钥/助记词在 WebView JavaScript 堆中处理

| 字段 | 内容 |
|------|------|
| **文件** | `wallet/src/main/java/com/jccdex/toolkits/wallet/sdk/WalletSdk.kt` |
| | `did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt` |
| | `webview-bridge/src/main/assets/did-bridge.js` |
| | `webview-bridge/src/main/assets/wallet-bridge.js` |
| | `webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt` |

**描述：** 私钥、助记词、secret 序列化进 `JSONObject`，经 `evaluateJavascript` 传入 JS 执行签名。密钥在 JS 堆中停留；`wallet-bridge.js` 存在 `console.log` 交易对象；`WebviewBridgeClient` 将所有 JS console 转发到 `Log.d`。

**修复建议：**

1. **长期：** 签名迁移至 Native / Android Keystore，JS 仅处理非敏感逻辑。
2. **短期：** WebView 独立进程、禁用调试、生产环境移除 console 转发与 bridge JS 中的 debug log。
3. 传参使用句柄/引用，避免原始密钥字符串。

**修复方案：** 详见 [`C04_WEBVIEW_KEY_LEAK_FIX.md`](./C04_WEBVIEW_KEY_LEAK_FIX.md)。短期：console 转发加守卫、清理 bridge JS debug log、release 脱敏日志、WebView 设置加固。长期签名迁移属架构重构，不在本次范围。

**复审（2026-07-31）：** 🟨 短期部分落地（`allowFileAccess=false`、asset 导航白名单、部分 console 关闭）。密钥仍经 JS 签名；`wallet-bridge.js` 仍可能残留 debug log。长期 Native 签名未做。

---

#### C-05：钱包擦除无需密码验证

| 字段 | 内容 |
|------|------|
| **文件** | `account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt` |
| **行号** | 54–57, 214–217 |
| | `vault/src/main/java/com/jccdex/toolkits/vault/VaultRepository.kt`（`clearAllData`） |

**描述：** `importHdWallet(..., clearExisting = true)` 在密码校验**之前**调用 `store.clearAllAccounts()` 与 `vault.clearAllData()`。`clearWalletData()` 同样无密码门控。`VaultRepository.clearAllData()` 本身也不要求认证。

**修复建议：**

1. 所有破坏性操作必须先 `verifyPassword()`。
2. 配合显式用户确认 UI（如二次确认「输入 DELETE」）。
3. 审计所有 `clearAllData` 调用点。

**修复方案：** 详见 [`C05_CLEAR_WITHOUT_PASSWORD_FIX.md`](./C05_CLEAR_WITHOUT_PASSWORD_FIX.md)。`clearAllData` / `importHdWallet(clearExisting=true)` / `clearWalletData` 增加可选 `password` 参数——空参数向后兼容，传参则先验密码再执行。

**复审（2026-07-31）：** 📌/❌ **编排层未关闭。** `VaultRepository.clearAllData(password?)` 仅在传入密码时门控；`AccountOrchestrator` 的 `clearExisting` 与 `clearWalletData()` 仍调用无参 `clearAllData()`，任意调用方可无密码擦除。若生产坚持兼容，须审计并限制所有 call site。

---

### 3.2 High（高危）

> 全部 H 级问题修复方案合并在一份文档中：**[`H_ISSUES_FIX_PLAN.md`](./H_ISSUES_FIX_PLAN.md)**（H-01 到 H-07）

#### H-01：`CachingSecretProvider` 缓存键未包含 origin

| 字段 | 内容 |
|------|------|
| **文件** | `dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/provider/CachingSecretProvider.kt` |
| **行号** | 80–94, 96–109 |

**描述：** 缓存键仅为 `pk:$address` / `sec:$address`。同一地址在不同 origin 间复用缓存私钥（5–20 秒窗口），绕过底层 `SecretProvider` 的按 origin 授权。

**修复建议：** 缓存键改为 `"$PRIVATE_KEY_PREFIX$origin|$address"`；`onPageStarted` 新 origin 时 `clearCache()`。

**复审（2026-07-31）：** ✅ 已关闭（缓存键含 origin）。残余：库内未在导航切换时自动 `clearCache()`，需宿主生命周期调用。

---

#### H-02：高风险 EVM 操作未传递 origin

| 字段 | 内容 |
|------|------|
| **文件** | `dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/EthMiddleware.kt` |
| **行号** | 163–164, 174–175, 196–197, 320–321 |
| | `dapp-connect/.../WebAppInterface.kt`（对应 handler） |

**描述：** `personalSign`、`switchEthereumChain` 传递 origin；`signTransaction`、`signTypedData`、`decrypt`、`getEncryptionPublicKey` 等传空字符串 `""`。`UnauthorizedException`（`Models.kt`）已定义但未被抛出。

**修复建议：** 全链路传递 `getOrigin()`；origin 缺失或未授权时抛出 `UnauthorizedException`（4100）。

**复审（2026-07-31）：** 🟨 部分修复。`signTypedData` / `decrypt` / `getEncryptionPublicKey` / `signTransaction` 已可传 origin；**`eth_sendTransaction` → `sendTransaction(txParams)` 仍不带 origin**，默认 `""`，宿主按 origin 授权会被绕过。`UnauthorizedException` 仍未见强制抛出。

---

#### H-03：`evaluateJavascript` 字符串拼接存在注入风险

| 字段 | 内容 |
|------|------|
| **文件** | `dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/WebAppInterfaceWithWebView.kt` |
| **行号** | 36–48, 59–64, 75–80 |
| | `dapp-connect/.../DAppConnectSdk.kt`（`loadInitJs` 等，95–120） |

**描述：** `id`、字符串结果未 JSON 转义；`loadInitJs` / `loadAddressJs` 将 `chainIdHex`、`rpcUrl`、`address` 以单引号字面量嵌入 JS。

**修复建议：** 使用 `JSONObject.quote()` 或 Base64 安全传参；优先 `WebMessagePort`。

**复审（2026-07-31）：** 🟨 `DAppConnectSdk.load*Js` 已用 `jsQuote()`。**响应通道仍不安全：** `WebAppInterfaceWithWebView` 将 `nonce` 直接拼进 `window.ccdao.sendResponse("$nonce", …)`。页面可经 `@JavascriptInterface postMessage` 传入恶意 `nonce` 打断字符串 → **任意 JS 执行**。见 **R-01**。

---

#### H-04：Vault 公开 API 绕过密码认证

| 字段 | 内容 |
|------|------|
| **文件** | `vault/src/main/java/com/jccdex/toolkits/vault/VaultRepository.kt` |
| **行号** | 376–391（`getMnemonicInternal`）, 399–417（`getPrivateKeyInternal`） |

**描述：** 上述 public 方法直接用 `derivedKey()` 解密，无密码校验。`AccountOrchestrator.deriveSubAccount()` 会调用 `getMnemonicInternal`。

**修复建议：** 改为 `internal` 或要求 `VaultSession` 令牌；解密路径统一要求认证。

**复审（2026-07-31）：** 🟨 解密依赖已解锁会话（`derivedKey()` 在锁定时失败）。`*Internal` 仍为 public，进程内任意代码在 unlock 后仍可调用。详见 [`H04_VAULT_INTERNAL_SESSION_FIX.md`](./H04_VAULT_INTERNAL_SESSION_FIX.md)。

---

#### H-05：`bindVcidToDid` 未验证凭证签名

| 字段 | 内容 |
|------|------|
| **文件** | `did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt` |
| **行号** | 742–788 |

**描述：** 与 `queryAndValidateVcid` 不同，`bindVcidToDid` 仅做结构检查（如 `id` 非空）即发布到链上 DID 文档，未调用 `verifyCredential()`。测试用例接受无签名最小 JSON。

**修复建议：** 发布前 `verifyCredential(credentialJson)` 且 `verified == true`；校验 `credentialSubject` 与目标 DID 一致。

**复审（2026-07-31）：** ✅ 已关闭（发布前强制验签）。

---

#### H-06：NFT 元数据拉取存在 SSRF 风险

| 字段 | 内容 |
|------|------|
| **文件** | `nft/src/main/java/com/jccdex/toolkits/nft/storage/room/NftStore.kt` |
| | `nft/src/main/java/com/jccdex/toolkits/nft/remote/NftRemoteAssetResolver.kt` |
| | `nft/src/main/java/com/jccdex/toolkits/nft/remote/SwtcChainNftClient.kt` |

**描述：** 对 metadata URI、VC、链响应中的 URL 发起 HTTP 请求，无内网 IP（`127.0.0.1`、`10.x`、`169.254.x`）拦截，无 scheme 白名单。

**修复建议：** 生产环境仅允许 `https`；解析 hostname 并拒绝私有/保留地址；限制响应大小与重定向次数。

**复审（2026-07-31）：** 🟨 `SsrfGuard` + 单测已用于 `NftRemoteAssetResolver.fetchMetadataImage`。缺口：① `NftStore.fetchJson` / `fetchText` **未调用** `SsrfGuard`；② DNS 解析失败时 `getOrNull() ?: return true`（**fail-open**），存在解析竞态 SSRF。

---

#### H-07：敏感信息经日志泄露

| 字段 | 内容 |
|------|------|
| **文件** | `dapp-connect/.../WebAppInterface.kt`（`postMessage` 完整 JSON） |
| | `webview-bridge/.../WebviewBridgeClient.kt`（JS console 转发） |
| | `webview-bridge/src/main/assets/wallet-bridge.js`（交易对象 log） |

**描述：** 交易参数、地址等可能出现在 logcat；release 构建未脱敏。

**修复建议：** release 仅记录 method + id；移除 bridge JS 中 debug log；禁止记录含 secret 的 payload。

**复审（2026-07-31）：** 🟨 `postMessage` 完整 JSON 日志已禁用；console 转发有关闭。残余：成功响应路径仍可能记录 `result`；bridge JS 调试日志未完全清干净。

---

#### R-01（复审新增）：响应 `nonce` 未转义导致 `evaluateJavascript` 注入

| 字段 | 内容 |
|------|------|
| **严重程度** | Medium |
| **文件** | `dapp-connect/.../WebAppInterfaceWithWebView.kt` |
| **行号** | ~48（`sendResponse`）、~64/~80（`sendError`） |
| **关联** | H-03、C-03 |

**描述：** 修复请求 nonce 后，Native 用 `window.ccdao.sendResponse("$nonce", $resultStr)` 回传。`nonce` 与部分字符串结果未 `JSONObject.quote`。恶意页面可直接调用 `@JavascriptInterface postMessage`，构造含 `"` / `);` 的 `nonce`，在 WebView 中执行任意 JS。

**修复建议：**

1. `JSONObject.quote(nonce)`（及所有字符串结果）。
2. 或仅接受 UUID 形态 nonce（与 provider 生成规则一致）。
3. 长期改用 `WebMessagePort` / 安全 JSON 信封。

**复审（2026-07-31）：** ❌ 新发现，待修复。

---
### 3.3 Medium（中危）

> 全部 M 级问题修复方案合并在一份文档中：**[`M_ISSUES_FIX_PLAN.md`](./M_ISSUES_FIX_PLAN.md)**（M-01 到 M-18）

| ID | 问题 | 位置 | 修复要点 | 复审 |
|----|------|------|----------|------|
| M-01 | `verifyPassword` 不走 Argon2 重算，无速率限制/账户锁定 | `VaultRepository.kt` | 验证时重跑 Argon2；失败计数与退避 | 🟨 有 Argon2；无锁定 |
| M-02 | `importPrivateKeys` 缺少 `mutex` | `VaultRepository.kt` | 与其他写操作一致加锁 | ✅ |
| M-03 | `changePassword` 批量解密时明文未及时 wipe | `VaultRepository.kt` | 逐条处理并在 `finally` 中 wipe | ✅ |
| M-04 | protobuf 解析无大小限制 | `VaultSerializer.kt` | `CodedInputStream.setSizeLimit()`；限制 repeated 字段 | 🟨 有 blob 上限 |
| M-05 | `postMessage` 无 origin 强制校验、无 per-method 用户确认 | `WebAppInterface.kt` | 库内校验 URL origin；敏感方法需宿主确认 | 🟨 非空才校验 |
| M-06 | `eth_requestAccounts` 直接返回全部账户 | `EthMiddleware.kt` | EIP-1193 connect 授权；按 origin 持久化授权 | 🟨 可选回调 |
| M-07 | 桥接 WebView 无导航白名单 | `WebviewBridgeClient.kt` | 仅允许 asset URL | ✅ |
| M-08 | `allowFileAccess = true` | `WebviewBridgeClient.kt` | 非必要则关闭 | ✅ |
| M-09 | Room 数据库明文存储 | 各 `*RoomDatabase.kt` | SQLCipher 或 Keystore 包裹 DB 密钥 | ❌ |
| M-10 | `deriveSubAccount` 并发可产生相同 index | `AccountOrchestrator.kt` | `@Transaction` 或串行 mutex | ✅ |
| M-11 | `DidCoreService` pending 状态非线程安全 | `DidCoreService.kt` | `ConcurrentHashMap` 或 `Mutex` | ✅ |
| M-12 | `setCurrentAccount` 不校验 accountId 存在 | `RoomAccountStore.kt` | 写入前 `findById` 校验 | ✅ |
| M-13 | `updatePreferredAvatar` 不校验 credential 存在 | `DidSdk.kt` | 发布前校验 credentials 列表 | ✅ |
| M-14 | `removeAccount` 账户不存在时跳过密码校验 | `AccountOrchestrator.kt` | 统一先验证或返回 `AccountNotFound` | 🟨 先验密码 |
| M-15 | `signCredentialForDApp` 签名 DApp 可控 payload | `DidSdk.kt` | Schema 校验 + 用户确认 UI | 🟨 结构校验 |
| M-16 | NFT/RPC HTTP 无证书固定 | 各 remote client | 已知 RPC 节点 pinning | ❌ |
| M-17 | `DerivedSubAccount` 向调用方返回明文私钥 | `AccountOrchestratorModels.kt` | 编排器内原子导入 vault | ✅ |
| M-18 | SWTC NFT 路径 `getSecretForAddress(address, "")` | `SwtcMiddleware.kt` | 传递并校验 origin | 🟨 DApp 已传；内部路径 `wallet_internal` |

---

### 3.4 Low（低危）

| ID | 问题 | 位置 |
|----|------|------|
| L-01 | `initializePassword` 已有密码时静默返回 | `VaultRepository.kt:53-56` |
| L-02 | Argon2 参数无下限校验 | `Argon2idKdf.kt`, `Argon2ParamChooser.kt` |
| L-03 | 密码经 `String(password).toByteArray(UTF_8)` 可能损坏二进制密码 | `Argon2idKdf.kt:26-27` |
| L-04 | `isSafeUrl` 允许 `http://`，库内未强制使用 | `DAppConnectSdk.kt:126-133` |
| L-05 | 请求 ID 单调递增 `requestId++` | `ccdao-eip1193-provider.js:26, 53` |
| L-06 | `consumer-rules.pro` 在 vault 模块缺失 | `vault/build.gradle.kts` |
| L-07 | 错误信息区分「密码错误」/「助记词不存在」等 | `VaultRepository.kt` 多处 |
| L-08 | `VaultPrivateKeyImport` 私钥参与 `hashCode()` | `VaultPrivateKeyImport.kt` |
| L-09 | `Cipher.getInstance` 未指定 Provider | `AESCrypto.kt` |
| L-10 | 默认 `bridgeUrl` 指向不存在的 `bridge.html` | `WebviewBridgeConfig.kt` |
| L-11 | Provider 设置 `isMetaMask: true` | `ccdao-eip1193-provider.js:93` |
| L-12 | `AccountSdk` 进程级单例 | `AccountSdk.kt:94-103` |

---

## 4. 正面实践

| 领域 | 实现 |
|------|------|
| 外层加密 | Tink AES-256-GCM + Android Keystore（`TinkManager.kt`） |
| 内层 AAD | 按 `address:` / `mnemonic:` / `secret:` 绑定，防篡改 |
| SQL 注入 | Room 全部参数化查询，**未发现 SQL 注入** |
| 密码比较 | `MessageDigest.isEqual` 常量时间比较 |
| 盐值 | 16 字节 `SecureRandom` |
| 敏感数据清理 | 广泛使用 `ByteArray.wipe()` + `finally` |
| 并发写 | 多数 vault 写路径使用 `Mutex` |
| 地址校验 | `ChecksumUtils`、EVM 地址格式验证 |
| VC 添加 | `addCredentialToDid` 校验 `ownerDid == did` + `validateCredentialData` |
| VC 过期 | `verifyCredential` 检查 `expirationDate` |
| 桥接调用 ID | Kotlin→JS 使用 UUID（`WebviewBridgeClient.callJsMethod`） |
| 方法名引用 | `JSONObject.quote(method)` 防 JS 注入 |
| 链切换 | `ChainProvider.requestChainSwitch` 用户确认 |
| 地址归属 | Eth/Swtc Middleware 签名前校验地址属于钱包 |

---

## 5. 威胁模型

### 5.1 初审时（2026-07-22）

| 攻击者能力 | 当时防护 | 主要缺口 |
|------------|----------|----------|
| 离线窃取 `vault.pb`（无 Keystore） | Tink AEAD | 较强 |
| Root / 进程内恶意代码 | Keystore 可在应用内解密 | stored derivedKey 绕过密码 |
| 恶意 DApp / 同源 XSS | 部分地址归属校验 | 无 origin 门控、可伪造 sendResponse |
| 弱密码在线暴力破解 | 无 | verify 不走 Argon2、无锁定 |
| 内存 / logcat 取证 | 部分 wipe | JS 堆、hex 字符串、日志转发 |
| 内网 SSRF（NFT metadata） | 无 | 任意 URL fetch |
| 恶意 VC 上链 | `addCredentialToDid` 有校验 | `bindVcidToDid` 缺验签 |

### 5.2 复审后（2026-07-31）

| 攻击者能力 | 当前防护 | 仍存缺口 |
|------------|----------|----------|
| 离线窃取 `vault.pb`（无 Keystore） | Tink AEAD | 较强；旧 vault 首次 unlock 前可能仍含 derivedKey |
| Root / 已 unlock 进程 | 会话密钥 + HMAC proof | 解锁后 `*Internal` 仍可读；编排层无密码擦除 |
| 恶意 DApp | origin 缓存隔离、部分签名传 origin、nonce | **sendTransaction 丢 origin**；**nonce 注入 (R-01)**；connect 可选 |
| 弱密码在线猜解 | Argon2 校验成本 | 仍无失败锁定 |
| 内存 / logcat | 部分日志关闭 | 密钥仍在 JS；响应日志残余 |
| 内网 SSRF | `SsrfGuard`（部分路径） | `NftStore` 未守卫；DNS fail-open |
| 恶意 VC 上链 | `bindVcidToDid` 验签 | ✅ 已缓解 |

---

## 6. 修复路线图

### 6.1 原路线图（初审）

见下文历史 P0–P3；其中多项已在 PR `#16` 落地。

### 6.2 复审后剩余优先项（2026-07-31）

| 优先级 | 行动项 | 关联 |
|--------|--------|------|
| **P0** | `WebAppInterfaceWithWebView`：`JSONObject.quote(nonce)` / 校验 UUID | R-01, H-03 |
| **P0** | `eth_sendTransaction` 全链路传 `getOrigin()` | H-02 |
| **P0** | Orchestrator `clearExisting` / `clearWalletData` 强制密码 | C-05 |
| **P1** | `NftStore` 全 HTTP 套 `SsrfGuard`；DNS 失败拒绝 | H-06 |
| **P1** | 清干净响应/result 日志与 bridge debug log | H-07, C-04 |
| **P1** | 强制/文档要求 `setRequestAccountsCallback`；SWTC 对齐 | M-06 |
| **P2** | 移除/隐藏 `window.ccdao.sendResponse` 或改 WebMessagePort | C-03 |
| **P2** | verifyPassword 失败锁定 | M-01 |
| **P3** | 签名迁出 WebView；Room 加密；RPC pinning | C-04, M-09, M-16 |

### P0 — 立即（核心安全模型）〔初审原文〕

| 序号 | 行动项 | 关联发现 | 复审 |
|------|--------|----------|------|
| 1 | 移除 `derivedKey` 持久化；每次从密码派生 | C-01 | 🟨 Session |
| 2 | 密码 proof 改为不可逆验证；迁移现有 vault | C-02 | ✅ |
| 3 | 所有 wipe/clear 路径强制 `verifyPassword` | C-05 | 📌/❌ |
| 4 | 限制 `getMnemonicInternal` / `getPrivateKeyInternal` 访问 | H-04 | 🟨 |

### P1 — 短期（DApp 与桥接）〔初审原文〕

| 序号 | 行动项 | 关联发现 | 复审 |
|------|--------|----------|------|
| 5 | 加固 native→JS 响应通道，禁止页面伪造 | C-03 | 🟨 + R-01 |
| 6 | `CachingSecretProvider` 按 origin 隔离缓存 | H-01 | ✅ |
| 7 | EVM 全路径传递并校验 origin | H-02 | 🟨 sendTx |
| 8 | 修复所有 `evaluateJavascript` 注入点 | H-03 | 🟨 + R-01 |
| 9 | release 移除敏感日志与 console 转发 | H-07, C-04 | 🟨 |

### P2 — 中期（业务与基础设施）〔初审原文〕

| 序号 | 行动项 | 关联发现 | 复审 |
|------|--------|----------|------|
| 10 | `bindVcidToDid` 增加 `verifyCredential` | H-05 | ✅ |
| 11 | NFT fetch URL 白名单与 SSRF 防护 | H-06 | 🟨 |
| 12 | `verifyPassword` Argon2 + 速率限制 | M-01 | 🟨 |
| 13 | `importPrivateKeys` 加 mutex | M-02 | ✅ |
| 14 | 桥接 WebView 导航白名单 | M-07 | ✅ |
| 15 | `eth_requestAccounts` connect 授权流 | M-06 | 🟨 |

### P3 — 长期（架构）〔初审原文〕

| 序号 | 行动项 | 关联发现 | 复审 |
|------|--------|----------|------|
| 16 | 签名逻辑迁出 WebView（Native / Keystore） | C-04 | ❌ |
| 17 | Room 数据库加密（SQLCipher） | M-09 | ❌ |
| 18 | 统一 `VaultSession` 认证模型 | H-04, C-01 | 🟨 |
| 19 | RPC TLS 证书固定 | M-16 | ❌ |

---
## 7. 附录

### 7.1 关键文件索引

```
vault/
  VaultRepository.kt          # 密钥库核心 API
  security/Argon2idKdf.kt     # KDF
  security/AESCrypto.kt       # 内层 AES-GCM
  serializer/VaultSerializer.kt
  proto/private_key_vault.proto

dapp-connect/
  WebAppInterface.kt          # @JavascriptInterface 入口
  WebAppInterfaceWithWebView.kt
  middleware/EthMiddleware.kt
  middleware/SwtcMiddleware.kt
  provider/CachingSecretProvider.kt
  assets/ccdao-eip1193-provider.js

webview-bridge/
  WebviewBridgeClient.kt
  WebviewBridgeEngine.kt
  assets/did-bridge.js
  assets/wallet-bridge.js

account/
  orchestrator/AccountOrchestrator.kt

did/
  sdk/DidSdk.kt
  service/DidCoreService.kt

wallet/
  sdk/WalletSdk.kt

nft/
  storage/room/NftStore.kt
  remote/NftRemoteAssetResolver.kt
```

### 7.2 测试命令

```bash
./gradlew :core:testDebugUnitTest
./gradlew :account:testDebugUnitTest
./gradlew :vault:testDebugUnitTest
./gradlew :webview-bridge:testDebugUnitTest
./gradlew :did:testDebugUnitTest
./gradlew :wallet:testDebugUnitTest
./gradlew :nft:testDebugUnitTest
./gradlew :dapp-connect:testDebugUnitTest
```

### 7.3 相关文档

- [TEST_AUDIT.md](./TEST_AUDIT.md) — 测试体系审计
- [C02_PASSWORD_PROOF_FIX.md](./C02_PASSWORD_PROOF_FIX.md) 等 C/H/M 修复方案（见 [README.md](./README.md)）
- [VAULT_SESSION_REDESIGN.md](./VAULT_SESSION_REDESIGN.md) / [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md)

### 7.4 新增模块简评（复审附带）

`:apk-verify` / `:app-update`：下载后 SHA-256 + 证书指纹校验方向正确；更新元数据本身未签名、部分校验 fail-open、`skipSignatureCheck` 由调用方控制。属供应链加固，默认低于钱包密钥风险，但生产须禁用 skip、固定官方清单。

### 7.5 文档修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-22 | 初版全库静态安全审计 |
| 1.1 | 2026-07-22 | 移至 `docs/` 目录 |
| 1.2 | 2026-07-28～31 | 各修复方案文档与 SECURITY 状态备注陆续补充 |
| **1.3** | **2026-07-31** | **PR `#16` 修复复审：闭合矩阵、R-01、剩余路线图** |

---

*本报告基于源码静态分析与修复 diff 复审，不构成渗透测试结论。建议对照 §1.1 / §6.2 逐项跟进，并在补丁后再次复测。*
