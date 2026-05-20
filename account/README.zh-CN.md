# Account SDK（`kotlin-toolkits/:account`）

本 SDK 负责**钱包账户元数据**的本地持久化与业务编排（不包含私钥；私钥由 `:vault` 模块管理）。

主要能力包括：
- HD 根账户 / 子账户 / 传统账户的增删改查
- 当前选中账户（`currentAccount`）管理
- 按链、地址、父账户等维度查询
- 与 `:vault`、`:wallet` 协作的导入 / 派生 / 删除流程（`AccountOrchestrator`）

共享领域模型来自 `:core`（`WalletAccount`、`ChainType`、`Path`）。

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.account.AccountSdk`
- **业务编排**（需配合 `VaultRepository`）：
  - `com.jccdex.toolkits.account.orchestrator.AccountOrchestrator`
  - `com.jccdex.toolkits.account.orchestrator.AccountOperationResult` / `AccountOperationError`
- **存储抽象**：
  - `com.jccdex.toolkits.account.store.IAccountStore`
  - `com.jccdex.toolkits.account.store.RoomAccountStore`（默认实现）
- **Room 本地库**（默认库名 `ccdao_accounts.db`）：
  - `com.jccdex.toolkits.account.storage.room.AccountRoomDatabase`
  - `com.jccdex.toolkits.account.storage.room.AccountDao` / `CurrentAccountDao`

## 2. 快速接入

### 2.1 获取 SDK 实例

```kotlin
// 推荐：进程内单例（Room 默认库 ccdao_accounts.db）
val accountSdk = AccountSdk.get(context)

// 或每次创建新实例（共享同一 Room 数据库文件）
val accountSdk = AccountSdk.create(context)
```

### 2.2 观察账户列表

```kotlin
// 全部账户
accountSdk.accounts.collect { list -> /* ... */ }

// 当前选中账户（Flow，可为 null）
accountSdk.currentAccount.collect { account -> /* ... */ }

// 按类型筛选
accountSdk.rootHDAccounts.collect { /* HD 根账户 */ }
accountSdk.subHDAccounts.collect { /* HD 子账户 */ }
accountSdk.traditionalAccounts.collect { /* 传统账户 */ }

// 按链筛选
accountSdk.getAccountsByChain(ChainType.ETH).collect { /* ... */ }
```

### 2.3 常用存储 API

```kotlin
// 写入
accountSdk.addAccount(walletAccount)
accountSdk.addAccounts(listOf(a, b))
accountSdk.setCurrentAccount(accountId)

// 查询
accountSdk.findById(id)
accountSdk.findByAddress(address, ChainType.ETH)
accountSdk.findByAddress(address)
accountSdk.findRootAccountByAddress(address)
accountSdk.findNonRootAccount(address, chain)
accountSdk.getSubAccountsOf(parentId).first()
accountSdk.getMaxIndexByChain(parentId, ChainType.ETH)
accountSdk.getSameAccountsCount(address)

// 更新 / 删除
accountSdk.updateAccountName(accountId, name)
accountSdk.updateAccountNameByAddress(address, name)
accountSdk.updatePublicKey(accountId, publicKey)
accountSdk.updateParentId(accountId, parentId)
accountSdk.removeAccount(accountId)   // 同时清理 current 选中
accountSdk.clearAllAccounts()
```

### 2.4 导入与派生（Orchestrator）

需先初始化 `:wallet` 与 `:vault`，再获取编排器：

```kotlin
WalletSdk.initialize(context)
WalletSdk.start()

val vault = VaultRepository.get(context)
val orchestrator = accountSdk.orchestrator(vault)

// 导入传统 / 单链账户（配合 WalletSdk.deriveFromPrivateKey 等）
val derived: TraditionalDeriveResult = WalletSdk.deriveFromPrivateKey(...)
when (val result = orchestrator.importSingleAccount(derived, chain, name, isHD = false, parentId = null)) {
    is AccountOperationResult.Success -> { /* result.value 为 accountId */ }
    is AccountOperationResult.Error -> { /* result.error */ }
}

// 导入 HD 钱包
val hd = WalletSdk.hdWalletFromMnemonic(...)
orchestrator.importHdWallet(hd, name = "My HD", password = passwordBytes)

// 派生子账户（返回 DerivedSubAccount，可再调用 importSubAccount 落库）
orchestrator.deriveSubAccount(chain = ChainType.ETH, rootAccountId = parentId)

// 删除账户（同步清理 vault 中对应密钥）
orchestrator.removeAccount(accountId, password)
```

常见错误类型见 `AccountOperationError`：`AddressAlreadyExists`、`AccountAlreadyExists`、`PasswordRequired`、`WrongPassword` 等。

## 3. 说明

- **数据边界**：`AccountSdk` 只存账户元数据（地址、链、名称、HD 路径、公钥等）；助记词 / 私钥由 `VaultRepository` 加密存储。
- **依赖关系**：`AccountOrchestrator` 依赖 `:wallet`（派生地址）与 `:vault`（导入密钥）；仅做列表读写时可只使用 `AccountSdk` + Room。
- **自定义存储**：测试或特殊场景可使用 `AccountSdk.createForTest(customStore)` 注入 `IAccountStore` 实现。
- **数据库**：默认文件 `ccdao_accounts.db`；`AccountRoomDatabase.getInstance(context, databaseName)` 支持按名称隔离多库。

## 4. 测试

```bash
./gradlew :account:testDebugUnitTest
./gradlew accountJacocoReport
```

覆盖率报告：`build/reports/jacoco/account/html/index.html`
