# kotlin-toolkits

JCCDex / CCDAO 的 Android Kotlin 共通工具库：账户元数据、加密密钥库、隐藏 WebView 密码学桥、DID / NFT、以及 DApp EIP-1193 连接。

本 README 侧重**框架结构、技术思路、设计原则与实现注意事项**，便于学习与接入。各模块 API 细节见对应 `README.zh-CN.md`。

---

## 模块一览

| 模块 | 职责 | 主入口 |
|------|------|--------|
| `:core` | 共享领域模型（`ChainType`、`Path`、`WalletAccount`） | 模型类 |
| `:account` | 钱包账户元数据（Room：`ccdao_accounts.db`） | `AccountSdk` |
| `:vault` | 加密密钥库（DataStore + Protobuf + Tink） | `VaultRepository` |
| `:webview-bridge` | 隐藏 WebView 运行时与 JS 资产宿主 | `WebviewBridgeClient` |
| `:wallet` | 助记词 / 派生 / 签名（经隐藏 WebView） | `WalletSdk` |
| `:nft` | NFT 元数据缓存、头像解析、远端图片规范化 | `NftSdk` |
| `:did` | DID 文档、凭证签发/验证/绑定、头像 VCID | `DidSdk` |
| `:dapp-connect` | DApp JS 桥、EIP-1193、EVM/SWTC 中间件 | `DAppConnectSdk` |

**minSdk：** `:did` / `:nft` = 30；其余多为 26。

---

## 1. 框架：模块依赖与职责边界

### 1.1 依赖关系

```text
                         :core（仅模型，无业务依赖）
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
        :account          :nft            :did
            │               │               │
            ├─► :vault      └──────►────────┤
            └─► :wallet ──► :webview-bridge ┘
                              ▲
                              │
                       :dapp-connect
                         ├─► :core
                         ├─► :wallet
                         ├─► :did
                         └─► :webview-bridge
```

独立模块（不依赖其它业务工程模块）：`:core`、`:vault`、`:webview-bridge`。

### 1.2 数据归属（谁存什么）

| 关注点 | 归属模块 | 存储 |
|--------|----------|------|
| 账户列表 / 当前账户 / 路径等元数据 | `:account` | Room `ccdao_accounts.db` |
| 私钥 / 助记词 / SWTC secret | `:vault` | DataStore 文件 `vault.pb` |
| DID 文档与本地缓存 | `:did` | Room `did_storage.db` |
| NFT 元数据 / 头像缓存 | `:nft` | Room `nft_storage.db` |
| 钱包 / DID 密码学 JS | `:webview-bridge` + 各 SDK Runtime | 隐藏 WebView |
| DApp 页面交互 | `:dapp-connect` | 宿主 WebView + App 侧 Provider |

原则：**元数据与密钥分离**。`AccountSdk` 不存私钥；密钥只进 `VaultRepository`。

### 1.3 分层（自下而上）

```text
JS 资产 / 第三方密码学库
  → Bridge Runtime（隐藏 WebView + Promise 网关）
    → Store / Vault / Room
      → Orchestrator / Service / Middleware
        → SDK Facade（对外 API）
          → 宿主 App（实现 Provider / 注入 Context）
```

| 层级 | 代表类型 | 作用 |
|------|----------|------|
| Facade | `AccountSdk`、`DidSdk`、`NftSdk`、`WalletSdk`、`DAppConnectSdk` | 对外稳定入口 |
| Orchestrator | `AccountOrchestrator` | 跨模块流程：导入 HD、派生子账户、带密码删除 |
| Service | `DidCoreService`、`DidSyncService` | DID 本地/同步逻辑 |
| Middleware | `EthMiddleware`、`SwtcMiddleware` | DApp RPC → 钱包/DID/节点 |
| Store / Ports | `IAccountStore`、`IDidStore`、`IDidBridge`、`SecretProvider`… | 可替换实现，便于测试与宿主注入 |
| Bridge | `WebviewBridgeClient`、`AndroidWalletWebRuntime`、`AndroidDidWebRuntime` | 主线程 WebView + JS Promise |

---

## 2. 技术思路

### 2.0 NFT 模块（v0.4.0 新增）

#### EVM Token URI 解析

**架构**：纯 Kotlin 实现，无外部依赖。

```
应用层 → EvmTokenUriClientFactory.create() → EvmTokenUriClient
         ↓
       EvmAbiCodec（ABI 编解码）
         ↓
       EvmRpcClient（JSON-RPC 调用 + Fallback）
         ↓
       normalizeRemoteAssetUrl（IPFS URL 规范化）
```

**核心类**：
- **ChainDefaults** - 链配置管理（v0.4.0+）
  - `ChainDefaults.Evm.getRpcUrls(chainId)` - 获取 EVM 链默认 RPC 节点列表
  - `ChainDefaults.Evm.getDefaultRpcUrl(chainId)` - 获取 EVM 链默认 RPC 节点
  - `ChainDefaults.Swtc.getRpcUrls()` - 获取 SWTC 链默认 RPC 节点列表
  - 提供 EVM 和 SWTC 配置分离，遵循单一职责原则
  
- **EvmAbiCodec** - ABI 编解码工具（纯静态方法）
  - `buildTokenUriCallData()` - 构建 ERC-721 `tokenURI(uint256)` 调用数据
  - `decodeAbiString()` - 解码 ABI 动态字符串
  - `decodeBytes32()` - 解码静态 bytes32
  
- **EvmRpcClient** - EVM JSON-RPC 客户端
  - 支持多节点 fallback（失败自动切换）
  - 可配置超时（默认 10s connect + 10s read）
  
- **EvmTokenUriClient** - 实现 `EthTokenUriResolver` 接口
  - `EvmTokenUriClientFactory.createDefault()` - 使用 ChainDefaults 默认节点
  - `EvmTokenUriClientFactory.create(provider)` - 完全自定义节点
  - `EvmTokenUriClientFactory.createWithFallback(additionalNodes)` - 企业推荐方案
  - `EvmTokenUriClientFactory.createWithOverride(customNodes)` - 部分覆盖

**使用示例**：

```kotlin
// 方式1: 默认配置（开发测试）
val client = EvmTokenUriClientFactory.createDefault()

// 方式2: 完全自定义（企业完全控制）
val client = EvmTokenUriClientFactory.create { chainId ->
    when (chainId) {
        1L -> listOf("https://eth.your-node.com")
        137L -> listOf("https://polygon.your-node.com")
        else -> emptyList()
    }
}

// 方式3: 扩展默认节点（企业推荐：公共节点优先，私有节点 fallback）
val client = EvmTokenUriClientFactory.createWithFallback(
    additionalNodes = mapOf(
        1L to listOf("https://eth.your-private-node.com"),
        137L to listOf("https://polygon.your-private-node.com")
    )
)

// 方式4: 部分覆盖（只修改部分链）
val client = EvmTokenUriClientFactory.createWithOverride(
    customNodes = mapOf(
        1L to listOf("https://eth.your-private-node.com")
    )
)

// 调用
val tokenUri = client.resolveEthrTokenUri(
    contract = "0x...",
    tokenId = "123",
    chainId = 1L
)
```

**迁移价值**：
- 消除应用层重复实现（jdid-android、ccdao-connector-android 各 100+ 行）
- 统一 ABI 编解码逻辑，避免不一致
- 简化 DI 配置，避免循环依赖
- 提供 ChainDefaults 统一管理节点配置，避免配置分散

---

### 2.1 密钥库（`:vault`）双层加密

1. **整文件层（Tink AEAD + Android Keystore）**  
   `VaultSerializer` 把 Protobuf `Vault` 打成密文写入 DataStore；主密钥由 Keystore 保管。
2. **条目层（Argon2id → AES-GCM）**  
   用户密码派生密钥；私钥 / 助记词 / secret 分条目加密，AAD 绑定 `address:` / `mnemonic:` / `secret:`。

敏感字节在 `finally` 中 `wipe()`；多数写路径用 `Mutex` 串行。

> 学习时注意：当前实现会把派生密钥等材料落在 vault 结构内（外层仍有 Tink）。威胁模型与加固项见 [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md)。

### 2.2 隐藏 WebView 做密码学（`:webview-bridge` + `:wallet` / `:did`）

- 使用**不可见** WebView 加载模块内 asset（`wallet-bridge` / `did-bridge`）。
- Kotlin 通过 `PromiseBridge.call(method, params, id)` 调 JS；结果经 `@JavascriptInterface` 回传。
- **Wallet 与 DID 各有独立 Runtime / WebView**，不要共用一个实例。

这样可复用既有 JS 钱包 / DID 库，代价是密钥会短暂进入 JS 堆——集成时避免打日志、用完销毁 bridge。

### 2.3 账户编排（`:account`）

- 日常列表 / 当前账户：`AccountSdk` → `RoomAccountStore`。
- 导入、派生、删账户并动 vault：`AccountSdk.orchestrator(vault)` → `AccountOrchestrator`（组合 `IAccountStore` + `VaultRepository` + `WalletSdk`）。

### 2.4 DID 与 NFT（`:did` / `:nft`）

- DID 文档存 VC；头像偏好通过 `preferredAvatar` 指向 credential id。
- `NftSdk` 负责候选 NFT / 图片；`DidSdk` 负责签发、验证、绑定、更新头像。
- 区分 API：`updateDidAvatar`（签发新所有权类 VC）vs `updatePreferredAvatar`（指向已有 VCID，含授权头像流）。

### 2.5 DApp 连接（`:dapp-connect`）

- 注入 `ccdao-eip1193-provider.js`，暴露 `window.ethereum` / `window.ccdao`。
- 页面 → `_tw_.postMessage` → `WebAppInterface` → Eth/SWTC Middleware。
- 宿主实现 `AccountProvider`、`SecretProvider`、`NodeProvider`（及可选 `ChainProvider`、`NftProvider`）。
- `CachingSecretProvider` 用于短时合并多次取钥（少弹密码）；**缓存键与 origin 策略需宿主自己把关**。

---

## 3. 设计原则

1. **Ports / 接口隔离**  
   持久化与桥接面向接口（`IAccountStore`、`IDidBridge`、`SecretProvider`…），SDK 可注入测试实现（`createForTest` / `installBridgeForTest`）。
2. **元数据 ≠ 密钥**  
   Room 只存可展示/可同步的元数据；密钥只在 vault。
3. **Facade 薄、编排集中**  
   跨库写操作放在 `AccountOrchestrator` 等编排层，避免 App 自行拼 vault + account 顺序。
4. **显式结果类型**  
   如 `AccountOperationResult` / `AccountOperationError`，以及 DID 的 `DidWriteResult`、`CredentialVerificationResult` 等，减少「抛异常当业务分支」。
5. **进程内单例要克制**  
   `AccountSdk.get`、`VaultRepository.get`、Room `getInstance` 便于 App 复用；测试用 `resetForTest` / 命名 DB 隔离。
6. **敏感内存尽量短命**  
   `wipe()`、协程 `finally` 清助记词；DApp 侧缓存要可 `clearCache`。
7. **主线程约束**  
   WebView 初始化与 `evaluateJavascript` 必须在主线程；Bridge 内部用 Main `Handler` 投递。

---

## 4. 实现注意事项（接入与贡献）

### 4.1 接入清单

| 场景 | 建议 |
|------|------|
| 只展示账户列表 | `AccountSdk` |
| 创建/导入/派生/删钱包 | `AccountOrchestrator` + 已初始化的 `VaultRepository` + `WalletSdk` |
| 签名 / 助记词派生 | `WalletSdk`（先初始化 bridge） |
| DID 读写 / 凭证 | `DidSdk`（独立 DID WebView） |
| DApp 浏览器 | `DAppConnectSdk`；DID 相关方法还需 `setDidSdk` |
| 取私钥给 DApp | App 实现 `SecretProvider`，按 origin 授权；可用 `CachingSecretProvider` 包装 |

### 4.2 密码与特权 API

- 面向用户的读取：`getPrivateKey` / `getMnemonic` / `getSecret` 走 `verifyPassword`。
- `getMnemonicUnlocked` / `getPrivateKeyUnlocked` 需已 `unlock`，供编排层派生——**App UI / 导出请用带密码的 getMnemonic / getPrivateKey**。`get*Internal` 已为 `internal`。
- 首次 HD 导入且 vault 无密码时，需传入密码，否则 `PasswordRequired`。
- 删除账户：`removeAccount(id, password)`；仅当该地址无其它账户时才会从 vault 移除密钥。

### 4.3 DApp / Origin

- `SecretProvider` 带 `origin` 参数，宿主应按站点授权。
- 注入响应、URL 安全校验（`isSafeUrl`）由库提供基础能力；**完整 connect 授权 UX 在宿主**。
- 已知风险（缓存键未含 origin、部分路径传空 origin、JS 回调可被页面脚本干扰等）见安全审计，集成时务必做授权门与生命周期清缓存。

### 4.4 WebView 与密钥

- Wallet / DID 默认共享单个 `SharedWebviewBridge`（`unified-bridge.html`）；legacy 单域页面 `wallet-bridge.html` / `did-bridge.html` 仍可用于测试注入。
- 私钥、助记词会以参数进入 JS：禁止 log、进程退出前调用 `ToolkitBridgeRuntime.shutdown()`；`WalletSdk.destroy()` 仅释放 wallet 门面。不要把 debug WebView 留给生产。
- 自定义 `IDidBridge` 时仍须在主线程创建 WebView。

### 4.5 模型包名

- 跨模块共享优先用 `com.jccdex.toolkits.core.model.*`。
- `:did` / `:nft` 等可能有本地同名模型，调用处不要混用。
- Orchestrator 内会做 `wallet.model.Path` ↔ `core.model.Path` 映射。

### 4.6 测试与贡献约定

- **既有单元测试默认锁定**：实现功能时一般不要改已有 `*Test.kt` 断言，防止行为静默漂移；必须改时需人工确认。优先**新增**测试。见 `.cursor/rules/protect-existing-unit-tests.mdc`。
- 本地跑测需配置 `ANDROID_HOME` 或 `local.properties`；Robolectric SDK 35 依赖较大，可用阿里云 Central（仓库已配置 `robolectric.dependency.repo.url`）。说明见 [docs/TEST_AUDIT.md](docs/TEST_AUDIT.md)。
- **不要**对 Robolectric 开 `offline=true`（会错误地在 CWD 找 jar）。

### 4.7 并发与其它坑

- `deriveSubAccount` 取 maxIndex 非事务，并发可能撞号。
- Vault 部分批量写路径历史上存在锁不一致问题，改 vault 时对照现有 `mutex.withLock` 用法。
- 密码请按 UTF-8 文本处理；二进制密码在 Argon2 路径上可能被损坏。

---

## 5. 推荐学习路径

1. 读 `:core` 模型 → `:vault` 加解密与 wipe → `:account` Store + Orchestrator。  
2. 读 `:webview-bridge` → `:wallet` / `:did` 的 Runtime 与 JS 调用约定。  
3. 读 `:nft` 缓存与远端解析 → `:did` 凭证与头像流。  
4. 读 `:dapp-connect` Provider / Middleware / EIP-1193 注入，对照宿主应实现的端口。  
5. 对照 [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md) 理解威胁模型，对照 [docs/TEST_AUDIT.md](docs/TEST_AUDIT.md) 理解测试边界。

---

## SDK 文档

- [did/README.zh-CN.md](did/README.zh-CN.md)
- [account/README.zh-CN.md](account/README.zh-CN.md)
- [vault/README.zh-CN.md](vault/README.zh-CN.md)
- [webview-bridge/README.zh-CN.md](webview-bridge/README.zh-CN.md)
- [nft/README.zh-CN.md](nft/README.zh-CN.md)
- [wallet/README.zh-CN.md](wallet/README.zh-CN.md)
- [dapp-connect/README.zh-CN.md](dapp-connect/README.zh-CN.md)

## 审计文档

- [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md) — 安全审计
- [docs/TEST_AUDIT.md](docs/TEST_AUDIT.md) — 测试体系审计（含实测）

## Test

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
