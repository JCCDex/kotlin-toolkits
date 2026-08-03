# M 级问题修复方案

## M-01：verifyPassword 无 Argon2 重算 / 无速率限制

**问题：** `verifyPassword` 不走 Argon2 重算，无暴力破解防护。

**修复：**
- C-02：HMAC 路径每次从输入密码 Argon2 派生再验 proof。
- M-01：`VaultAuthLockout` 持久化失败次数；连续 5 次失败后锁定，时长阶梯 1 min → 5 min → 15 min；成功或 `clearAllData` 清零。锁定时 `unlock`/`verifyPassword` 抛 `VaultAuthLockedException`。

**状态：** ✅

---

## M-02：importPrivateKeys 缺少 mutex

**问题：** `importPrivateKeys` 不上锁，与其他写操作并发可能破坏数据。

**修复：**

```kotlin
suspend fun importPrivateKeys(privateKeys: MutableList<VaultPrivateKeyImport>) =
    mutex.withLock {
        // 现有逻辑
    }
```

**文件：** `VaultRepository.kt`，~2 行。**对 app 影响：** 无。

---

## M-03：changePassword 批量解密时明文未及时 wipe

**问题：** `changePassword` 解密所有条目后才重加密，中间明文 key 在内存中保持时间过长。

**修复：** 逐条处理——解密一条 → 擦除旧 ciphertext → 重加密 → 立即 wipe 明文：

```kotlin
for (entry in keys) {
    val pt = AESCrypto.decrypt(iv, ct, oldKey, aad)
    val (newIv, newCt) = AESCrypto.encrypt(pt, newKey, aad)
    pt.wipe()
    // 更新 entry
}
```

**文件：** `VaultRepository.kt`，~10 行。**对 app 影响：** 无。

---

## M-04：Protobuf 解析无大小限制

**问题：** `CodedInputStream` 无 size limit，恶意超大 vault.pb 可 OOM。

**修复：** ciphertext / plaintext 均 ≤ 10MB；`CodedInputStream.setSizeLimit`；`keys` / `mnemonics` / `secrets` 条数 ≤ 1024，超限回落空 vault。

**文件：** `VaultSerializer.kt`。**对 app 影响：** 已有的超大合法 vault 可能解析失败（几率极低）。**状态：** ✅

---

---

## M-05：postMessage 无 origin 强制校验

**问题：** 库内不校验 DApp origin，依赖 app 层检查。

**修复：** `postMessage` 拒绝空白 origin 与非 `isSafeUrl` origin；宿主必须 `setOrigin`（两 App 已导航同步）。

**状态：** ✅

---

## M-06：eth_requestAccounts 无 EIP-1193 connect 授权

**问题：** `eth_requestAccounts` 直接返回所有账户，不询问用户。

**修复：** 改为需要 app 层用户确认。SDK 提供 `requireUserApproval` 回调，app 弹出确认 UI 后回调 SDK。

**文件：** SDK ~20 行 + app UI 层。**对 app 影响：** 中——需加用户确认 UI。

---

## M-07：桥接 WebView 无导航白名单

**问题：** 隐藏 WebView 可被重定向到任意 URL。

**修复：**

```kotlin
override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
    return !request.url.toString().startsWith("file:///android_asset/")
}
```

**文件：** `WebviewBridgeClient.kt`，~5 行。**对 app 影响：** 无。

---

## M-08：allowFileAccess = true

**问题：** 隐藏 WebView 开启了文件访问。

**修复：** 改为 `false`。`file:///android_asset/` 不受此设置影响（Android 文档：_Assets and resources are still accessible using file:///android_asset_）。bridge WebView 仅加载 assets，不受影响。

**文件：** `WebviewBridgeClient.kt`，1 行。**对 app 影响：** 无。

---

## M-09：Room 数据库明文存储

**问题：** 账户数据以明文存储在 Room SQLite 中。

**修复：** 使用 SQLCipher 加密数据库。需引入依赖 `net.zetetic:android-database-sqlcipher`，替换 `Room.databaseBuilder` 为 `SupportFactory`。

**文件：** 各 `*RoomDatabase.kt` + `build.gradle.kts`。**对 app 影响：** 大——数据库需要迁移，现有数据需加密处理。

---

## M-10：deriveSubAccount 并发可产生相同 index

**问题：** `getMaxIndexByChain` + `deriveChild` 非原子，并发可能重复 index。

**修复：**

```kotlin
suspend fun deriveSubAccount(...) = mutex.withLock {
    // 现有逻辑
}
```

**文件：** `AccountOrchestrator.kt`，~3 行。**对 app 影响：** 无。

---

## M-11：DidCoreService pending 状态非线程安全

**问题：** `pendingResolves` 为普通 `HashMap`，多协程 unsafe。

**修复：** 改用 `ConcurrentHashMap` 或加 `Mutex`。

**文件：** `DidCoreService.kt`，~2 行。**对 app 影响：** 无。

---

## M-12：setCurrentAccount 不校验 accountId

**问题：** 可以设置不存在的 accountId 为当前账户。

**修复：** 写入前 `findById(accountId) ?: throw NoSuchElementException`。

**文件：** `RoomAccountStore.kt`，~3 行。**对 app 影响：** 无。

---

## M-13：updatePreferredAvatar 不校验 credential

**问题：** 不检查 credential 是否存在就发布。

**修复：** 发布前 `credentials.any { it.id == avatarCredId }`。

**文件：** `DidSdk.kt`，~3 行。**对 app 影响：** 无。

---

## M-14：removeAccount 跳过密码校验

**问题：** 账户不存在时直接 `return`，不要求密码。

**修复：** 先 `verifyPassword`，再检查账户存在性。缺失账户返回 `Success`（幂等删除，刻意不为 `AccountNotFound`，避免破坏调用方）。

**文件：** `AccountOrchestrator.kt`。**对 app 影响：** 无。**状态：** ✅

---

## M-15：signCredentialForDApp 签名 DApp 可控 payload

**问题：** DApp 传入的 VC 内容无 schema 校验即签名。

**修复：** SDK 侧结构校验（`@context`/`type`、`credentialSubject`、issuer）。库内确认回调会破坏 API，改为 **宿主确认后调用**（KDoc + README）。

**状态：** 📌

---

## M-16：NFT/RPC HTTP 无证书固定

**问题：** 无 TLS certificate pinning。

**修复：** 对已知 RPC 节点配置证书固定（`okhttp3.CertificatePinner`）。

**文件：** `OkHttpClient` 配置，~10 行。**对 app 影响：** 证书过期未更新会导致连接失败。

---

## M-17：DerivedSubAccount 携带私钥

（已关闭，见 SECURITY_AUDIT。）

---

## M-18：SWTC getSecretForAddress 传空 origin

**问题：** 内部 NFT 路径曾硬编码空 origin。

**修复：** DApp 路径传真实 origin；原生 NFT 使用哨兵 `WebOrigin.WALLET_INTERNAL`（非可授权 web origin），避免空串与「未设 origin」混淆。

**状态：** 📌

---

## 改动量总览

| ID | 复杂度 | 行数 | 已有覆盖 |
|----|--------|------|----------|
| M-01 | — | 0 | ✅ C-02 |
| M-02 | 低 | ~2 | |
| M-03 | 中 | ~10 | |
| M-04 | 低 | ~2 | |
| M-05 | — | 0 | 📌 warn + 文档 |
| M-06 | 中 | ~20 | |
| M-07 | 低 | ~5 | |
| M-08 | 低 | ~1 | |
| M-09 | **大** | ~50 | |
| M-10 | 低 | ~3 | |
| M-11 | 低 | ~2 | |
| M-12 | 低 | ~3 | |
| M-13 | 低 | ~3 | |
| M-14 | 低 | ~3 | |
| M-15 | 中 | ~15 | |
| M-16 | 中 | ~10 | |
| M-17 | 低 | ~5 | |
| M-18 | 低 | ~2 | |

需新实现 15 项，总计 ~140 行。M-01 和 M-05 已由 C-02/C-03 覆盖。M-09（SQLCipher）是架构级改动，建议单独立项。

## 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-29 | 初版 |
