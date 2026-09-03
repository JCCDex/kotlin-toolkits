# kotlin-toolkits 测试审计报告

| 项目 | 内容 |
|------|------|
| **审计对象** | kotlin-toolkits 全部 8 个模块的单元测试与 CI 配置 |
| **审计日期** | 2026-07-22（静态）/ 2026-07-23（实测） |
| **审计类型** | 测试体系静态分析 + 全量单元测试实测 |
| **关联文档** | [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) |

---

## 1. 执行摘要

项目共 **40 个测试类**、**381 个 `@Test` 方法**，以 **JUnit 4 + Robolectric + kotlinx-coroutines-test + MockK** 为主，辅以 **AssertJ**、**MockWebServer**。整体呈现「**中间层与 DID 测试充分，DApp 连接层测试严重不足**」的格局。

**总体评价：B-（功能回归实测通过，安全与边界覆盖仍不足）**

| 维度 | 评分 | 说明 |
|------|------|------|
| 测试数量与模块分布 | B+ | 381 用例，`:did` / `:account` 覆盖较好 |
| 高风险路径覆盖 | C | `:dapp-connect` 核心类几乎无测；vault 安全属性未断言 |
| CI 完整性 | C- | GitHub Actions 漏跑 `:nft`、`:dapp-connect` |
| 覆盖率工具 | B | JaCoCo 已配置，但缺门禁；`:dapp-connect` 未纳入 |
| 测试基础设施 | B+ | 内存 Room、MockWebServer、TestFixtures 较成熟 |
| 与安全审计对齐 | D+ | 已知安全问题多数无对应回归测试 |
| **全量实测结果** | **通过** | **2026-07-23：381 passed / 0 failed / 0 skipped（约 9m35s）** |

### 1.1 全量实测结果（2026-07-23）

```bash
./gradlew \
  :core:testDebugUnitTest :account:testDebugUnitTest :vault:testDebugUnitTest \
  :webview-bridge:testDebugUnitTest :did:testDebugUnitTest :nft:testDebugUnitTest \
  :wallet:testDebugUnitTest :dapp-connect:testDebugUnitTest
# BUILD SUCCESSFUL in 9m 35s
```

| 模块 | tests | failures | errors | skipped |
|------|------:|---------:|-------:|--------:|
| `:core` | 9 | 0 | 0 | 0 |
| `:account` | 62 | 0 | 0 | 0 |
| `:vault` | 21 | 0 | 0 | 0 |
| `:webview-bridge` | 39 | 0 | 0 | 0 |
| `:did` | 155 | 0 | 0 | 0 |
| `:nft` | 40 | 0 | 0 | 0 |
| `:wallet` | 35 | 0 | 0 | 0 |
| `:dapp-connect` | 20 | 0 | 0 | 0 |
| **合计** | **381** | **0** | **0** | **0** |

**跑通条件与踩坑：**

1. 需要 `ANDROID_HOME` 或根目录 `local.properties`（`sdk.dir=...`）。
2. Robolectric `@Config(sdk = [35])` 会拉取约 190MB 的 `android-all-instrumented` jar；国内建议预热 `~/.m2`，并配置 `robolectric.dependency.repo.url` 指向阿里云 Central（已写入 `gradle.properties` / `build.gradle.kts`）。
3. 勿使用 `robolectric.offline=true`：会在 CWD 查找 `./android-all-instrumented-*.jar` 导致 `Path is not a file`。
4. JaCoCo agent 版本与 AGP 对齐为 `0.8.13`（根 `build.gradle.kts`）。
5. `settings.gradle.kts` 增加阿里云 Google/Central 镜像，降低依赖解析 TLS/超时失败概率。

> 说明：全量通过仅证明「现有用例全部绿」；**不改变**下文关于覆盖缺口与安全回归缺失的结论。

### 1.2 工程约定：既有测试默认锁定

既有单元测试是已实现行为的契约。**实现功能时默认不得修改已有测试代码**，以免行为静默漂移；若确需修改，必须经人工确认。优先新增测试覆盖新行为。项目规则：`.cursor/rules/protect-existing-unit-tests.mdc`。

---

## 2. 测试资产统计

### 2.1 模块维度

| 模块 | 主代码文件 | 主代码行数 | 测试文件 | 测试行数 | `@Test` 数 | 测试/主代码行比 |
|------|-----------|-----------|---------|---------|-----------|----------------|
| `:core` | 3 | 115 | 2 | 175 | 9 | 152% |
| `:account` | 11 | 942 | 8 | 1,573 | 62 | 167% |
| `:vault` | 7 | 786 | 3 | 631 | 21 | 80% |
| `:webview-bridge` | 4 | 456 | 4 | 1,103 | 39 | 242% |
| `:did` | 18 | 2,291 | 14 | 3,700 | 155 | 161% |
| `:nft` | 9 | 1,323 | 7 | 1,534 | 40 | 116% |
| `:wallet` | 3 | 498 | 4 | 802 | 35 | 161% |
| `:dapp-connect` | 11 | 2,073 | 2 | 233 | 20 | **11%** |
| **合计** | **66** | **8,484** | **44** | **9,751** | **381** | **115%** |

> 行数为 Kotlin 源码近似统计；`:dapp-connect` 主代码量第二（2,073 行），测试行数垫底。

### 2.2 测试类型分布

| 类型 | 数量/情况 |
|------|-----------|
| 单元测试（`src/test`） | 40 测试类 |
| 仪器化测试（`src/androidTest`） | **0**（全库无 androidTest） |
| 集成测试 | `DidSdkIntegrationTest`（8 用例）、部分 Robolectric 真 WebView 行为测 |
| 属性/模糊测试 | 无 |
| 快照/Golden 测试 | 无 |

### 2.3 技术栈

| 依赖 | 使用模块 |
|------|----------|
| JUnit 4 | 全部 |
| Robolectric | core, account, vault, webview-bridge, did, nft, wallet |
| kotlinx-coroutines-test | account, vault, did, nft, wallet, webview-bridge, dapp-connect |
| MockK | account, vault, did, nft, wallet, webview-bridge |
| AssertJ | 多数模块 |
| MockWebServer | did, nft |
| kotlin-test | 全部 |

`:dapp-connect` **未引入** Robolectric、MockK、AssertJ，限制了对其 Android/WebView 相关类的测试能力。

---

## 3. CI 与质量门禁

### 3.1 GitHub Actions（`.github/workflows/ci.yml`）

**已纳入 CI 的模块：**

```
:vault, :core, :account, :webview-bridge, :did, :wallet
```

**未纳入 CI 的模块：**

| 模块 | 风险 |
|------|------|
| `:nft` | 40 个用例完全不在 CI 中执行 |
| `:dapp-connect` | 20 个用例完全不在 CI 中执行；且为安全审计高风险模块 |

### 3.2 Pre-commit（`.githooks/pre-commit`）

仅执行 `ktlintFormatAll` + `ktlintCheckAll`，**不运行单元测试**。

`ktlintCheckAll` 覆盖模块与 JaCoCo 一致，**不含 `:dapp-connect`**。

### 3.3 JaCoCo 覆盖率

根 `build.gradle.kts` 配置了：

- 单模块报告：`:coreJacocoReport` … `:walletJacocoReport`
- 汇总报告：`jacocoAllModulesReport`

**缺口：**

- `:dapp-connect` 未纳入 `coverageModules`
- 无覆盖率阈值门禁（fail on low coverage）
- CI 未生成或上传覆盖率报告

### 3.4 建议的统一验证命令

```bash
./gradlew \
  :core:testDebugUnitTest \
  :account:testDebugUnitTest \
  :vault:testDebugUnitTest \
  :webview-bridge:testDebugUnitTest \
  :did:testDebugUnitTest \
  :nft:testDebugUnitTest \
  :wallet:testDebugUnitTest \
  :dapp-connect:testDebugUnitTest \
  jacocoAllModulesReport
```

---

## 4. 分模块测试分析

### 4.1 `:core` — adequate

| 测试类 | 覆盖对象 |
|--------|----------|
| `PathTest` | `Path` 解析与序列化 |
| `WalletModelsTest` | `WalletAccount` 等模型 |

**缺口：** `ChainType` 无独立测试（逻辑简单，可接受）。

---

### 4.2 `:account` — good

| 测试类 | 覆盖对象 |
|--------|----------|
| `AccountDaoTest` / `AccountEntityTest` / `AccountRoomDatabaseTest` | Room 层 CRUD |
| `RoomAccountStoreTest` | Store 门面（22 用例） |
| `AccountOrchestratorTest` | 导入/派生/擦除编排（21 用例，MockK vault） |
| `AccountSdkTest` | SDK 单例与委托 |

**优点：**

- 内存数据库 `AccountTestDatabase` + `AccountTestFixtures` 复用良好
- Orchestrator 覆盖 HD 导入、子账户派生、批量导入等主要路径

**缺口：**

| 缺口 | 关联安全发现 |
|------|-------------|
| `importHdWallet(clearExisting=true)` 仅断言会调用 `clearAllData()`，**未断言需先验证密码** | [SECURITY C-05](./SECURITY_AUDIT.md) |
| `deriveSubAccount` 无并发/竞态测试 | [SECURITY M-10](./SECURITY_AUDIT.md) |
| `setCurrentAccount` 传入不存在 ID 的行为未测 | [SECURITY M-12](./SECURITY_AUDIT.md) |
| `removeAccount` 账户不存在时的行为未测 | [SECURITY M-14](./SECURITY_AUDIT.md) |

---

### 4.3 `:vault` — moderate（功能有测，安全属性未测）

| 测试类 | 覆盖对象 |
|--------|----------|
| `VaultRepositoryTest` | 初始化、导入、读取、改密、删除（12 用例，有序执行） |
| `CryptoHelpersTest` | wipe、Argon2 参数选择、VaultSerializer 往返 |
| `VaultPrivateKeyImportTest` | 导入模型 |

**优点：**

- `VaultRepositoryTest` 使用真实 Robolectric + DataStore，非纯 mock
- 覆盖 `importPrivateKeys` 去重、`changePassword` 全流程
- 验证 `wipe()` 调用次数（mock 静态方法）

**缺口：**

| 缺口 | 关联安全发现 |
|------|-------------|
| **无测试断言「密码验证应重新派生密钥」**；现有测试实际上验证了 `derivedKey` 持久化后的读写行为 | [SECURITY C-01](./SECURITY_AUDIT.md) |
| 无 `getPrivateKeyInternal` / `getMnemonicInternal` 无密码访问的负面测试 | [SECURITY H-04](./SECURITY_AUDIT.md) |
| 无 `importPrivateKeys` 并发竞态测试 | [SECURITY M-02](./SECURITY_AUDIT.md) |
| 无 `clearAllData` 需认证测试 | [SECURITY C-05](./SECURITY_AUDIT.md) |
| `TinkManager`、`Argon2idKdf` 无已知向量测试 | 加密正确性保障不足 |
| `VaultRepositoryTest` 使用 `@FixMethodOrder(NAME_ASCENDING)`，测试间共享 DataStore 状态，**脆弱且难并行** | 测试工程问题 |

---

### 4.4 `:webview-bridge` — good

| 测试类 | 覆盖对象 |
|--------|----------|
| `WebviewBridgeClientTest` | 初始化、JS 调用 payload、bridge 页校验 |
| `WebviewBridgeClientBehaviorTest` | 线程安全、超时、并发调用（17 用例） |
| `PromiseGatewayImplTest` | Promise 回调网关（实例级） |
| `SharedWebviewBridgeTest` | 共享 singleton、unified 页、reload |
| `UnifiedBridgeClientTest` | unified 页加载、wallet+did 双方法 dispatch |
| `ToolkitBridgeRuntimeTest` | shutdown / reload 公共生命周期 |

**优点：**

- `WebviewBridgeClientBehaviorTest` 是本项目测试质量最高的文件之一：多线程、CountDownLatch、并发 map
- 覆盖后台线程初始化 WebView

**缺口：**

| 缺口 | 关联安全发现 |
|------|-------------|
| 无导航白名单 / `shouldOverrideUrlLoading` 测试 | [SECURITY M-07](./SECURITY_AUDIT.md) |
| 无 `onPromiseResult` 伪造回调的负面测试 | [SECURITY H-10](./SECURITY_AUDIT.md) |

---

### 4.5 `:did` — excellent（数量与深度最佳）

| 测试类 | 用例数（约） | 覆盖对象 |
|--------|-------------|----------|
| `DidSdkTest` | 53 | SDK 全功能（含 bind、verify、credential） |
| `DidCredentialHelperTest` | 53 | 凭证校验辅助 |
| `DidCoreServiceTest` | 14 | 同步/缓存服务 |
| `DidSyncServiceTest` | 4 | 同步逻辑 |
| `DidSdkIntegrationTest` | 8 | 跨组件集成 |
| Room 相关 | 15+ | DAO、Entity、Store |
| 工具类 | 4+ | Checksum、Resolve |

**优点：**

- 测试行数（3,700）超过主代码（2,291），比率高
- 大量 mock bridge + 内存 store，隔离良好
- `DidCredentialHelperTest` 边界条件充分

**问题（测试固化已知缺陷）：**

```kotlin
// DidSdkTest.kt — bindVcidToDid 接受无签名最小 JSON
val credential = """{"id":"$vcid","type":["VerifiableCredential","NFTUsageAuthorization"]}"""
val result = localSdk.bindVcidToDid("secret", did, "", credential)
assertTrue(result.success)  // 未调用 verifyCredential
```

此测试**记录并固化了** [SECURITY H-05](./SECURITY_AUDIT.md) 中的行为，修复后需同步更新。

**缺口：**

- `DidCoreService` 并发 pending 状态无多线程测试
- 无针对 `signCredentialForDApp` 任意 payload 的 schema 约束测试

---

### 4.6 `:nft` — good（但不在 CI）

| 测试类 | 覆盖对象 |
|--------|----------|
| `NftStoreTest` | 存储、元数据拉取（11 用例，含 MockWebServer） |
| `NftSdkTest` | SDK 门面（18 用例） |
| `NftDaoTest` / `NftRoomDatabaseTest` | Room 层 |
| `SwtcChainNftClientTest` | 链上客户端 |
| `NftModelsTest` | 模型 |

**缺口：**

| 缺口 | 关联安全发现 |
|------|-------------|
| `NftRemoteAssetResolver` **无测试文件** | [SECURITY H-06](./SECURITY_AUDIT.md) |
| `SwtcNftMetadataParser` **无测试文件** | 解析逻辑未覆盖 |
| 无 SSRF 防护（内网 URL 拒绝）测试 | [SECURITY H-06](./SECURITY_AUDIT.md) |
| **40 个用例未在 CI 执行** | CI 缺口 |

---

### 4.7 `:wallet` — good（偏契约测试）

| 测试类 | 覆盖对象 |
|--------|----------|
| `WalletSdkTest` | 28 用例：验证 JS payload 构建与结果解析 |
| `WalletModelsTest` | 模型 |
| `AndroidWalletWebRuntimeTest` | 运行时初始化 |
| `RealWalletWebBridgeClientTest` | 1 用例（真实 bridge 冒烟） |

**特点：** `WalletSdkTest` 主要 mock `WebviewBridgeClient`，断言 **传给 JS 的参数结构**，不验证真实密码学正确性（依赖 JS 库）。

**缺口：**

- 无端到端签名向量测试（需真实 WebView 或 JNI）
- 无密钥不落 JS 堆的架构级测试（当前架构无法满足）

---

### 4.8 `:dapp-connect` — poor（严重欠测）

| 测试类 | 用例数 | 覆盖对象 |
|--------|--------|----------|
| `DAppConnectSdkTest` | 9 | 仅 `isSafeUrl` 正向用例 + `loadAddressJs` 字符串片段 |
| `CachingSecretProviderTest` | 11 | 缓存命中、并发、TTL |

**完全无测试的主代码（2,000+ 行）：**

| 源文件 | 行数（约） | 风险 |
|--------|-----------|------|
| `WebAppInterface.kt` | ~680 | `@JavascriptInterface` 入口、全部 RPC 分发 |
| `EthMiddleware.kt` | ~430 | EVM 签名、发交易、切链 |
| `SwtcMiddleware.kt` | ~350 | SWTC 签名、NFT 交易 |
| `WebAppInterfaceWithWebView.kt` | ~110 | `evaluateJavascript` 响应注入 |
| `DAppMethod.kt` | ~200 | 方法枚举与路由 |
| `DidDocumentMutationListener.kt` | ~50 | DID 变更监听 |

**已知测试缺口与安全审计直接对应：**

| 应有测试 | 现状 | 安全发现 |
|----------|------|----------|
| `isSafeUrl` 拒绝 `javascript:`、`file:`、空串 | 注释写明「需 Robolectric，由 app 层覆盖」 | [SECURITY M-15](./SECURITY_AUDIT.md) |
| `CachingSecretProvider` 跨 origin 不复用缓存 | **无**；仅有同 origin 并发测试 | [SECURITY H-01](./SECURITY_AUDIT.md) |
| `EthMiddleware` 传 origin 给 `SecretProvider` | **无 EthMiddleware 测试** | [SECURITY H-02](./SECURITY_AUDIT.md) |
| `evaluateJavascript` 特殊字符转义 | **无** | [SECURITY H-03](./SECURITY_AUDIT.md) |
| `eth_requestAccounts` 需用户授权 | **无** | [SECURITY H-09](./SECURITY_AUDIT.md) |
| `loadInitJs` RPC URL 注入 | **无** | [SECURITY H-03](./SECURITY_AUDIT.md) |

**DAppConnectSdkTest 中的技术债注释：**

```kotlin
// Note: reject tests (file, javascript, empty, malformed, ftp) use
// android.util.Patterns.WEB_URL which requires Robolectric. Those are
// covered by integration tests in the app layer.
```

将关键安全边界测试外推到宿主 App，**库本身缺乏回归保障**。

---

## 5. 测试质量评估

### 5.1 优秀实践

| 实践 | 示例 |
|------|------|
| 内存 Room 隔离 | `AccountTestDatabase`、`DidTestDatabase`、`NftTestDatabase` |
| TestFixtures 复用 | `AccountTestFixtures` |
| MockWebServer 测 HTTP | `NftStoreTest`、`NftSdkTest` |
| Bridge mock 契约测试 | `WalletSdkTest`、`DidSdkTest` |
| 并发场景测试 | `CachingSecretProviderTest`、`WebviewBridgeClientBehaviorTest` |
| 有序状态测试 + 真实 DataStore | `VaultRepositoryTest`（虽有脆弱性） |
| 协程测试 | 广泛使用 `runTest` |

### 5.2 反模式与风险

| 问题 | 位置 | 影响 |
|------|------|------|
| `@FixMethodOrder` 共享可变状态 | `VaultRepositoryTest` | 测试顺序依赖、无法并行、难维护 |
| mock `wipe()` 为空操作 | `VaultRepositoryTest.setup` | 无法验证真实内存清理 |
| 测试固化错误行为 | `DidSdkTest.bindVcidToDid` | 修复安全 bug 时测试会「阻止」正确行为 |
| 安全边界外推给 App | `DAppConnectSdkTest` 注释 | 库级回归缺失 |
| Orchestrator 全 mock vault | `AccountOrchestratorTest` | 无法发现 vault 与 account 集成问题 |
| 无 androidTest | 全库 | WebView/DApp 真实环境无自动化验证 |
| CI 模块不全 | `ci.yml` | 2/8 模块测试不在流水线 |

### 5.3 测试与安全审计对齐矩阵

| 安全发现 ID | 描述 | 有对应测试？ | 测试是否验证「应修复」？ |
|-------------|------|-------------|------------------------|
| C-01 | derivedKey 明文持久化 | 间接（读写通过） | **否**（测试当前错误行为） |
| C-02 | 可逆密码 proof | 无 | 否 |
| C-03 | sendResponse 可伪造 | 无 | 否 |
| C-04 | 密钥在 JS 堆 | 无（架构限制） | 否 |
| C-05 | 无密码擦除 | 有（断言会擦除） | **否**（未要求密码） |
| H-01 | 缓存不含 origin | 无 | 否 |
| H-02 | EVM 不传 origin | 无 | 否 |
| H-03 | evaluateJavascript 注入 | 无 | 否 |
| H-05 | bindVcidToDid 未验签 | 有 | **否**（断言未签名可成功） |
| H-06 | NFT SSRF | 无 | 否 |
| M-02 | importPrivateKeys 无 mutex | 无 | 否 |
| M-10 | deriveSubAccount 竞态 | 无 | 否 |

**结论：** 381 个用例中，绝大多数验证**功能正确性**；对安全审计发现的 **0 项** 有「修复导向」的回归测试。

---

## 6. 未覆盖源文件清单

以下 `src/main` 文件无对应直接测试（通过间接调用覆盖的除外）：

### `:dapp-connect`（9/11 文件无测）

- `WebAppInterface.kt`
- `WebAppInterfaceWithWebView.kt`
- `EthMiddleware.kt`
- `SwtcMiddleware.kt`
- `MiddlewareInterfaces.kt`
- `DAppMethod.kt`
- `Models.kt`
- `Interfaces.kt`
- `DidDocumentMutationListener.kt`

### `:nft`（2/9 文件无测）

- `NftRemoteAssetResolver.kt`
- `SwtcNftMetadataParser.kt`

### `:vault`（3/7 文件无直接测）

- `TinkManager.kt`
- `Argon2idKdf.kt`（仅参数选择间接覆盖）
- `Wipe.kt`（有单测）

### 其他模块

主路径基本有测；`:core` 的 `ChainType.kt` 无独立测试文件。

---

## 7. 改进建议

### P0 — 立即（补齐 CI 与高危缺口）

| # | 行动 | 预期效果 |
|---|------|----------|
| 1 | CI 增加 `:nft:testDebugUnitTest`、`:dapp-connect:testDebugUnitTest` | 堵住 60 个用例的执行盲区 |
| 2 | 新增 `CachingSecretProviderTest`：不同 origin 同 address **不应**复用缓存 | 覆盖 [H-01] |
| 3 | 为 `DAppConnectSdk.isSafeUrl` 补充 Robolectric 拒绝用例（`javascript:`、`file:`、空） | 库内安全边界 |
| 4 | 新增 `EthMiddlewareTest`（MockK SecretProvider）：断言 `signTransaction` 等传递 origin | 覆盖 [H-02] |

### P1 — 短期（安全回归测试）

| # | 行动 |
|---|------|
| 5 | `WebAppInterfaceWithWebViewTest`：特殊字符 `id`/result 的 JS 注入 |
| 6 | 修复 `DidSdkTest.bindVcidToDid`：改为断言未签名凭证**被拒绝** |
| 7 | `AccountOrchestratorTest`：`clearExisting` 必须先 `verifyPassword` |
| 8 | `VaultRepositoryTest`：移除 `@FixMethodOrder`，每用例独立 DataStore |
| 9 | `NftRemoteAssetResolverTest` + SSRF 拒绝内网 URL |

### P2 — 中期（工程化）

| # | 行动 |
|---|------|
| 10 | `:dapp-connect` 加入 `coverageModules` 与 `ktlintCheckAll` |
| 11 | JaCoCo 设置模块级最低覆盖率门禁（建议 vault/dapp-connect ≥ 60%） |
| 12 | pre-commit 或 CI 增加 `testDebugUnitTest`（至少 changed modules） |
| 13 | 抽取共享 `TestCoroutineDispatcher` / Robolectric 基类，减少重复 setup |

### P3 — 长期

| # | 行动 |
|---|------|
| 14 | 增加 `androidTest`：DApp WebView 端到端（连接、签名确认流） |
| 15 | Vault 加密已知向量测试（Argon2 + AES-GCM + Tink 分层） |
| 16 | 安全修复后建立「安全回归测试清单」，与 SECURITY_AUDIT 双向链接 |

---

## 8. 建议新增测试用例清单（可直接落地）

### `CachingSecretProviderTest`

```kotlin
@Test
fun `different origins do not share cached private key`() = runTest { ... }

@Test
fun `cache clear on new origin navigation`() = runTest { ... }
```

### `DAppConnectSdkTest`（需 `@RunWith(RobolectricTestRunner::class)`）

```kotlin
@Test fun `isSafeUrl rejects javascript scheme`()
@Test fun `isSafeUrl rejects file scheme`()
@Test fun `isSafeUrl rejects empty string`()
@Test fun `loadInitJs escapes rpc url quotes`()
```

### `EthMiddlewareTest`

```kotlin
@Test fun `signTransaction passes origin to secret provider`()
@Test fun `personalSign rejects address not in wallet`()  // 若已有可保留
@Test fun `requestAccounts requires prior connect grant`()  // 修复后
```

### `VaultRepositorySecurityTest`（修复 derivedKey 后）

```kotlin
@Test fun `derived key is not persisted in vault proto`()
@Test fun `verifyPassword re-derives with argon2`()
@Test fun `getPrivateKeyInternal requires session`()
@Test fun `clearAllData requires password`()
```

### `NftRemoteAssetResolverTest`

```kotlin
@Test fun `rejects loopback url`()
@Test fun `rejects private network ip`()
@Test fun `allows https public url`()
```

---

## 9. 附录

### 9.1 测试文件索引

```
core/src/test/           2 files,  9 tests
account/src/test/        8 files, 62 tests
vault/src/test/          3 files, 21 tests
webview-bridge/src/test/ 4 files, 39 tests
did/src/test/           14 files, 155 tests
nft/src/test/            7 files, 40 tests
wallet/src/test/         4 files, 35 tests
dapp-connect/src/test/   2 files, 20 tests
```

### 9.2 运行命令

```bash
# 环境
export ANDROID_HOME="$HOME/Library/Android/sdk"
# 或确保根目录 local.properties 含：sdk.dir=/path/to/Android/sdk

# 全模块单测
./gradlew :core:testDebugUnitTest \
  :account:testDebugUnitTest \
  :vault:testDebugUnitTest \
  :webview-bridge:testDebugUnitTest \
  :did:testDebugUnitTest \
  :nft:testDebugUnitTest \
  :wallet:testDebugUnitTest \
  :dapp-connect:testDebugUnitTest

# 覆盖率报告
./gradlew jacocoAllModulesReport
# 输出：build/reports/jacoco/allModules/html/index.html
```

### 9.3 文档修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-07-22 | 初版测试体系审计（静态） |
| 1.1 | 2026-07-23 | 全量单测实测通过（381/381）；补充 Robolectric/镜像跑通说明 |

---

*本报告已完成静态分析与全量单元测试实测。安全回归缺口与 CI 模块遗漏仍待后续跟进。*
