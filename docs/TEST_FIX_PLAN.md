# 安全修复后测试同步方案

基于 [TEST_AUDIT.md](./TEST_AUDIT.md) 和本轮 C/H/M 级安全修复，需同步更新以下测试。

---

## 1. 🔴 会失败：`DidSdkTest` — `bindVcidToDid` 两个测试

**根因：** H-05 修复在 `bindVcidToDid` 中增加了 `verifyCredential()` 调用（[DidSdk.kt:766-767](../did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L766-L767)），但测试传入的是无签名凭证，且未 mock `bridge.call("verifyCredential", ...)`。

### 1.1 `bindVcidToDid adds credential when not present`（行 1806）

```kotlin
// 修改前 — 无签名凭证直接通过
val credential = """{"id":"$vcid","type":["VerifiableCredential","NFTUsageAuthorization"]}"""

// 修改后 — 需要 mock verifyCredential 返回 verified:true
coEvery { bridge.call("verifyCredential", any()) } returns """{"verified":true}"""
```

### 1.2 `bindVcidToDid replaces existing credential with same id`（行 1858）

同样加一行 mock。

### 修复后完整用例（以 add 为例）

```kotlin
@Test
fun `bindVcidToDid adds credential when not present`() =
    runTest {
        val did = "did:ethr:0x1234567890abcdef1234567890abcdef12345678"
        val vcid = "$did#nft-0xabc-1-did:ethr:0xgrantee"
        val credential =
            """{"id":"$vcid","type":["VerifiableCredential","NFTUsageAuthorization"]}"""
        val store =
            object : com.jccdex.toolkits.did.store.IDidStore {
                override fun observeAll() = kotlinx.coroutines.flow.flowOf(emptyList<DidEntity>())
                override fun observe(did: String) = kotlinx.coroutines.flow.flowOf(null)
                override suspend fun get(did: String) =
                    DidEntity(did = did, doc = """{"credentials":[],"service":[]}""")
                override suspend fun upsert(entity: DidEntity) = Unit
                override suspend fun delete(did: String) = Unit
            }
        coEvery {
            bridge.callAs("publishDid", any(), PublishDidResult::class.java)
        } returns PublishDidResult(code = "0", message = "ok")
        coEvery {
            bridge.callAs("didStat", any(), com.jccdex.toolkits.did.model.DidStatResult::class.java)
        } returns com.jccdex.toolkits.did.model.DidStatResult(cid = "")
        // ← 新增：mock signature verification passes
        coEvery { bridge.call("verifyCredential", any()) } returns """{"verified":true}"""
        val localSdk =
            DidSdk(bridge, DidCoreService(store, mockk(relaxed = true)), avatarResolver, avatarCredentialSource)

        val result = localSdk.bindVcidToDid("secret", did, "", credential)

        assertTrue(result.success)
        assertTrue(result.didDocument.orEmpty().contains(vcid))
    }
```

**改动量：** 2 行 mock + 调整缩进。

---

## 2. 🔴 会失败：`DidSdkTest` — `updatePreferredAvatar` 测试

**根因：** M-13 修复在 `updatePreferredAvatar` 中增加了 credential 存在性校验（[DidSdk.kt:820-824](../did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L820-L824)）。测试传入的 `currentDoc` 为 `""`，走 `resolveBaseDoc` → 从 store 读取。需要确认测试 store 返回的 doc 中包含对应 credential。

当前测试（行 1911-1965）中 store 返回的 doc 已包含 credentials 数组，且 `credId` 与其中 credential 的 `id` 匹配。**此测试应正常通过，无需修改。**

---

## 3. 🟡 建议新增：`:dapp-connect` 安全回归测试

`:dapp-connect` 模块测试/主代码行比仅 11%，本轮修改的 4 个核心文件全部无测。按优先级排列：

### 3.1 `EthMiddlewareTest`（新建文件）

```kotlin
@RunWith(RobolectricTestRunner::class)
class EthMiddlewareTest {

    // M-06: requestAccounts 应检查用户授权
    @Test
    fun `requestAccounts throws when callback rejects`() = runTest { ... }

    @Test
    fun `requestAccounts succeeds when callback approves`() = runTest { ... }

    @Test
    fun `requestAccounts succeeds when no callback set`() = runTest { ... }
}
```

**依赖：** 需引入 Robolectric + MockK 到 `:dapp-connect`（当前未引入）。

### 3.2 `SwtcMiddlewareTest`（新建文件）

```kotlin
@Test
fun `getSecretForAddress passes origin to secret provider`() = runTest { ... }
```

### 3.3 `WebAppInterfaceTest`（新建文件）

```kotlin
@Test
fun `postMessage rejects unsafe origin`() = runTest { ... }

@Test
fun `signCredentialForDApp validates credential structure`() = runTest { ... }
```

---

## 4. 🟢 无需修改的测试

| 测试文件 | 原因 |
|----------|------|
| `AccountOrchestratorTest` | M-17 `keypair`→`publicKey` 已同步修改 |
| `CachingSecretProviderTest` | H-01 缓存键加上 origin 前缀，同 origin 并发测试不受影响 |
| `DAppConnectSdkTest` | `isSafeUrl` 测试不涉及本次改动 |
| `VaultRepositoryTest` | M-02/M-03 的内部实现变更对测试透明 |
| `DidCoreServiceTest` | M-11 `ConcurrentHashMap` 替换对测试透明 |

---

## 5. 改动量汇总

| 文件 | 类型 | 行数 |
|------|------|------|
| `DidSdkTest.kt` | 修复（2 处加 mock） | ~4 |
| `EthMiddlewareTest.kt` | 新建（建议） | ~50 |
| `SwtcMiddlewareTest.kt` | 新建（建议） | ~30 |
| `WebAppInterfaceTest.kt` | 新建（建议） | ~40 |

---

## 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-29 | 初版 |
| v1.1 | 2026-07-29 | 全部实施完成 |

## 实施状态

| 文件 | 类型 | 用例数 | 状态 |
|------|------|--------|------|
| `DidSdkTest.kt` | 修复 `bindVcidToDid` ×2 | 2 | ✅ |
| `DidSdkTest.kt` | 新增 `signCredentialForDApp` 验证 | 3 | ✅ |
| `EthMiddlewareTest.kt` | 新建 (M-06 callback) | 5 | ✅ |
| `SwtcMiddlewareTest.kt` | 新建 (M-18 origin) | 1 | ✅ |
| `CachingSecretProviderTest.kt` | 新增 H-01 跨 origin 缓存隔离 | 4 | ✅ |
| `NftRemoteAssetResolverTest.kt` | 新建 H-06 SSRF 守卫 | 8 | ✅ |
| `.github/workflows/ci.yml` | P0-1 补全 `:nft` `:dapp-connect` | — | ✅ |

## 未实施项及原因

| 建议 | 原因 |
|------|------|
| P0-3 `isSafeUrl` 拒绝 `javascript:`/`file:` | `:dapp-connect` 未引入 Robolectric，需要加依赖 |
| P1-5 `WebAppInterfaceWithWebView` JS 注入 | 同上 |
| P1-7 `AccountOrchestrator` clearExisting 验密码 | C-05 设计为向后兼容——`clearAllData()` 无参仍可用，非 bug |
| P1-8 `VaultRepositoryTest` 去 `@FixMethodOrder` | 纯工程重构，安全无关 |
| H-02 origin 透传测试 | origin 在 WebAppInterface 层传递，需 Robolectric |

## 新模块覆盖缺口 (不在原 TEST_AUDIT 范围内)

2026-07-29 新增 `:apk-verify`、`:app-update` 两个模块，审计未覆盖。

| 模块 | 源文件 | 测试 | testImplementation | CI |
|------|--------|------|-------------------|---|
| `:apk-verify` | 6 | **0** | ❌ 未配置 | ✅ 已加 |
| `:app-update` | 3 | **0** | ❌ 未配置 | ✅ 已加 |

两者均需先配置 `testImplementation(libs.junit)` + `testImplementation(kotlin("test"))`，再补测试。
