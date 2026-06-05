# DID SDK（`kotlin-toolkits/:did`）

本 SDK 提供 DID 文档的**创建 / 更新 / 发布 / 解析 / 本地存储（Room）**能力，以及 **NFT 凭证（VC）签发、验签、删除与授权头像绑定**。

默认 Android 接入下，SDK 已内置：
- DID 专用 WebView JS Runtime
- Room 本地存储
- `IDidBridge` / `IDidResolver` 默认实现
- `NftSdk` 默认接入（用于 NFT 头像与远程图片解析）

接入方通常只需要提供头像相关扩展点。

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.did.DidSdk.create(...)`
- **本地存储**：
  - `com.jccdex.toolkits.did.storage.room.DidRoomDatabase`
  - `com.jccdex.toolkits.did.storage.room.RoomDidStore`（实现 `IDidStore`）
- **核心服务**：
  - `com.jccdex.toolkits.did.service.DidCoreService`（DID 文档本地读写 + resolveAndSave）
  - `com.jccdex.toolkits.did.service.DidSyncService`（对一组账户批量 resolve）
- **端口（Ports）**：
  - `com.jccdex.toolkits.did.port.IDidBridge`
  - `com.jccdex.toolkits.did.service.IDidResolver`
  - `com.jccdex.toolkits.did.store.IDidStore`
  - `com.jccdex.toolkits.did.port.IDidAvatarResolver`（可选）
  - `com.jccdex.toolkits.did.port.IDidAvatarCredentialSource`（可选，提供头像 NFT 候选，供 SDK 组装 credential）

## 2. 端口（Ports）说明

如果你使用默认 Android 工厂 `DidSdk.create(context, ...)`，`IDidBridge` / `IDidResolver` / `IDidStore` 不需要自己实现。
只有在你要替换默认 runtime 或默认存储时，才需要关注这些端口。

### 2.1 `IDidBridge`（必需）

用于调用链侧/JS Runtime 的方法（例如：`didResolve`、`publishDid`、`generateVC`、`verifyCredential` 等）。

```kotlin
interface IDidBridge {
    suspend fun call(method: String, params: String? = null): String
    suspend fun <T> callAs(method: String, params: String? = null, clazz: Class<T>): T
}
```

实现要求：
- **必须支持后台线程调用**（SDK 内部可能在 `Dispatchers.IO` 等线程执行）。
- 如果底层由 WebView 驱动，务必保证 **WebView 初始化发生在主线程**（见「4. Android WebView 初始化」）。

### 2.2 `IDidResolver`（必需）

用于从链 / 网络解析 DID 文档（返回字符串 JSON；链上不存在可能返回 `"{}"` 或空）。

```kotlin
interface IDidResolver {
    suspend fun resolve(did: String): String
}
```

### 2.3 `IDidStore`（必需）

SDK 的本地 DID 文档存储端口。Android 默认实现使用 Room：`RoomDidStore`。

```kotlin
interface IDidStore {
    fun observeAll(): Flow<List<DidEntity>>
    fun observe(did: String): Flow<DidEntity?>
    suspend fun get(did: String): DidEntity?
    suspend fun upsert(entity: DidEntity)
    suspend fun delete(did: String)
}
```

### 2.4 头像相关（可选）

- `IDidAvatarResolver`：用于把 VC 解析成 `Nft` 展示数据（例如补全图片、名称等）。
- `IDidAvatarCredentialSource`：用于从你的业务数据源里列出“可选头像 NFT”（通常来自本地 DB 或网络）。
`DidSdk` 会把 `IDidAvatarCredentialSource` 的结果组装成可直接用于头像选择和发布的 credential。

## 3. 快速接入（Android）

### 3.1 建议的初始化方式

```kotlin
val didSdk =
    DidSdk.create(
        context = context,
        avatarResolver = didAvatarResolver, // 可选
        avatarCredentialSource = didAvatarCredentialSource // 可选
    )
```

职责边界：
- `DidSdk`：DID 文档创建、更新、发布、解析与本地读写的统一 facade
- 默认 `IDidBridge`：SDK 内置 DID 专用 WebView runtime
- 默认 `IDidResolver`：通过内置 DID runtime 做链上 DID 解析
- 默认 `IDidStore`：SDK 内置 Room 存储
- `IDidAvatarCredentialSource`：头像候选来源，供 SDK 组装 credential
- `IDidAvatarResolver`：VC 到展示用 NFT 数据的补全器

### 3.2 常用能力一览（`DidSdk`）

#### DID 文档基础

- **地址转 DID**：`toDid(walletAccount)`
- **监听 / 读取本地文档**：`observeDidDocument(did)` / `getDidDocument(did)` / `observeAllDidDocuments()`
- **解析并落库**：`resolveDid(did)`
- **读取 Profile**：`getProfile(doc)` / `nickname(doc)`
- **创建并发布初始 DID 文档**：`uploadInitialDidDoc(privateKey, did, nickname)`
- **更新昵称并发布**：`updateDidNickname(privateKey, did, nickname, currentDoc)`
- **发布删除**：`publishDidDelete(privateKey, did)`

#### 头像相关

- **列出可选头像 NFT**：`getAvatarNftCredentials(account)`（依赖 `IDidAvatarCredentialSource`）
- **更新头像（签发自有 NFTOwnership VC）**：`updateDidAvatar(privateKey, did, currentDoc, selectedAvatar)`
  - 会调用 `generateVC` 生成新的 **NFTOwnership** 凭证并写入 DID 文档
  - 适用于钱包持有的自有 NFT
- **绑定已有 VC 为头像（不重新签发）**：`updatePreferredAvatar(privateKey, did, currentDoc, credentialId)`
  - 仅更新 Profile 中的 `preferredAvatar`
  - 适用于文档中已有的 **NFTUsageAuthorization** 等凭证 ID

#### 展示模型

- **生成 DID 元数据**：`generateDid(did)`
- **生成 Profile + 头像展示**：`generateProfileVC(did)`
  - 按 VC 内容（`jingtumNFT` / `ERC-721`）路由解析头像，而非按 DID 链类型
  - 支持跨链头像（例如 ETH DID + SWTC NFT VC）
- **从 VC 解析 NFT 展示**：`generateSwtcNft(vc)` / `generateEthrNft(vc)`
- **统一解析 NFT 图片地址**：`resolveCredentialImage(imageUrl, metadataUri)`
- **批量预解析凭证图片**：`resolveCredentialImages(requests)`
- **提取 SWTC metadata 地址**：`extractSwtcMetadataUri(tokenInfosPayload)`
- **拉取 metadata 关键字段**：`fetchMetadataFields(metadataUri)`

#### NFT 凭证（VC）管理

| API | 说明 |
|-----|------|
| `readCredentials(doc)` | 从 DID 文档 JSON 读取凭证列表（每项为 JSON 字符串） |
| `addCredentialToDid(...)` | 签发并添加 NFTOwnership（self）或 NFTUsageAuthorization（others）VC，然后发布 |
| `deleteCredentialFromDid(...)` | 从 DID 文档删除指定 VC；若该 VC 为当前头像则清空 `preferredAvatar` |
| `verifyCredential(credentialJson)` | 验签；对 UsageAuthorization 会先检查是否已被撤销/变更 |
| `checkGranteeCredentialUpdate(credentialJson)` | 检查被授权方 VC 是否在链上被删除、转授或修改到期日 |

`addCredentialToDid` 使用 `UnifiedNftCredentialData` 描述 NFT 与授权信息，详见下文 **§8**。

#### 授权头像绑定（VCID）

被授权方通过 VCID 绑定他人授权的头像：

| API | 说明 |
|-----|------|
| `queryAndValidateVcid(vcid)` | 从 VCID 解析 owner DID，在链上文档查找 VC 并验签，返回 `QueryVcidResult` |
| `bindVcidToDid(...)` | 将校验通过的 VC 合并进当前 DID 文档（存在则更新，不存在则追加）并发布 |
| `updatePreferredAvatar(...)` | 将 `preferredAvatar` 设为已有 VCID，不生成新 VC |

典型流程（被授权方）：

1. 从授权方处获得 VCID（格式如 `did:ethr:0xOwner#nft-0xContract-tokenId-did:ethr:0xGrantee`）
2. `queryAndValidateVcid(vcid)` 校验 VC 有效，且 `credentialSubject.id` 为当前用户 DID
3. `bindVcidToDid(...)` 把 VC 同步到本地 DID 文档
4. `updatePreferredAvatar(..., credentialId = vcid)` 设为头像

> **注意**：`updateDidAvatar` 与 `updatePreferredAvatar` 用途不同。前者为**自有 NFT 新签 VC**；后者为**绑定已有 VCID**。绑定授权头像应使用后者，而非 `updateDidAvatar`。

### 3.3 最小接入示例

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DidModule {
    @Provides fun provideDidAvatarCredentialSource(
        swtcNftRepository: SwtcNftRepository,
        evmTokenRepository: EvmTokenRepository
    ): IDidAvatarCredentialSource =
        AppDidAvatarCredentialSource(swtcNftRepository, evmTokenRepository)
    @Provides fun provideDidSdk(
        @ApplicationContext context: Context,
        avatarResolver: IDidAvatarResolver,
        avatarCredentialSource: IDidAvatarCredentialSource
    ): DidSdk =
        DidSdk.create(
            context = context,
            avatarResolver = avatarResolver,
            avatarCredentialSource = avatarCredentialSource
        )
}
```

## 4. Android WebView 初始化（重要）

默认 Android 工厂内部使用 headless WebView 作为 DID runtime。
- SDK 会在首次使用时自行初始化
- 不需要 app 再额外初始化 DID 专用 bridge
- SDK 内部已经保证 WebView 创建发生在主线程

如果你自己替换 `IDidBridge` 实现，仍然需要满足：
- 后台线程可调用
- WebView 创建必须发生在主线程

如果你还在 app 中保留钱包用的 `WebviewBridge`，它和 DID SDK 的 runtime 现在是分开的，不会互相覆盖。

## 5. Room 数据库说明

`DidSdk.create(context, ...)` 默认会通过 `DidRoomDatabase.getInstance(context)` 自动创建数据库与表结构（Room 默认行为）。
- 默认 DB 名：`DidRoomDatabase.DEFAULT_DATABASE_NAME`
- 别的 App 接入时不需要手动创建 `did_documents` 表
- 如需自定义 DB 名，可使用 `DidSdk.create(context, ..., databaseName = "...")`

如果你不用默认工厂，也可以继续手动构建：

```kotlin
val didStore = RoomDidStore(DidRoomDatabase.getInstance(context).didDao())
```

## 6. 手动装配模式

如果你明确需要替换默认 runtime / resolver / store，仍然可以使用手动装配：

```kotlin
val didSdk =
    DidSdk.create(
        bridge = didBridge,
        store = didStore,
        resolver = didResolver,
        avatarResolver = didAvatarResolver,
        avatarCredentialSource = didAvatarCredentialSource
    )
```

## 7. 测试

在 `kotlin-toolkits` 目录下：

```bash
./gradlew :did:testDebugUnitTest
```

## 8. NFT 凭证数据模型

凭证相关模型位于 `com.jccdex.toolkits.did.model`：

| 类型 | 用途 |
|------|------|
| `UnifiedNftCredentialData` | 签发/添加 VC 时的输入（self 所有权 / others 授权） |
| `CredentialAuthorizationType` | `SELF` / `OTHERS` |
| `UsageRights` | 授权用途，如 `AVATAR`、`NON_COMMERCIAL_DISPLAY` |
| `NftCredentialRestrictions` | 授权限制（commercial、territories 等） |
| `DidWriteResult` | 写操作结果（`success`、`didDocument`） |
| `CredentialVerificationResult` | 验签结果（`verified`、`results`） |
| `GranteeCredentialUpdateResult` | 被授权 VC 变更检测（`isUpdate`、`credential`） |
| `QueryVcidResult` | VCID 查询校验结果（`isValid`、`credential`） |
| `DidAvatarCredential` | 头像选择器用的 NFT 候选（含 `credentialId`、链信息等） |

### 8.1 签发自有 VC（self）

```kotlin
val data = UnifiedNftCredentialData(
    type = CredentialAuthorizationType.SELF,
    granteeDid = ownerDid,
    ownerDid = ownerDid,
    chainId = 1,
    tokenId = "123",
    standard = "ERC-721",
    contractAddress = "0x..."
)
val result = didSdk.addCredentialToDid(privateKey, ownerDid, currentDoc, data)
```

### 8.2 授权他人使用（others）

```kotlin
val data = UnifiedNftCredentialData(
    type = CredentialAuthorizationType.OTHERS,
    granteeDid = granteeDid,
    ownerDid = ownerDid,
    chainId = 1,
    tokenId = "123",
    standard = "ERC-721",
    contractAddress = "0x...",
    usageRights = listOf(UsageRights.AVATAR, UsageRights.NON_COMMERCIAL_DISPLAY),
    restrictions = NftCredentialRestrictions()
)
val result = didSdk.addCredentialToDid(privateKey, ownerDid, currentDoc, data)
```

VCID 由 `DidCredentialHelper.generateVcId(data)` 规则生成，EVM 形如：

`{ownerDid}#nft-{checksumContract}-{tokenId}-{granteeDid}`

SWTC 形如：

`{ownerDid}#nft-{tokenName}-{nftIssuer}-{tokenId}-{granteeDid}`

## 9. 授权头像绑定示例

被授权方粘贴 VCID 并完成绑定：

```kotlin
// 1. 校验 VCID（无需私钥）
val query = didSdk.queryAndValidateVcid(vcid)
if (!query.isValid || query.credential == null) {
    // VC 无效或不存在
    return
}

// 2. 确认当前用户是被授权方
val subjectId = JSONObject(query.credential!!)
    .getJSONObject("credentialSubject")
    .getString("id")
require(subjectId == currentDid)

// 3. 合并 VC 到当前 DID 文档
val bindResult = didSdk.bindVcidToDid(
    privateKey = privateKey,
    did = currentDid,
    currentDoc = currentDoc,
    credentialJson = query.credential!!
)
if (!bindResult.success) return

// 4. 设为头像（不重新签发 VC）
val avatarResult = didSdk.updatePreferredAvatar(
    privateKey = privateKey,
    did = currentDid,
    currentDoc = bindResult.didDocument ?: currentDoc,
    credentialId = vcid
)
```

## 10. JS Bridge 方法

默认 `IDidBridge`（`did-bridge.js`）与凭证相关的方法包括：

| Bridge 方法 | SDK 调用方 |
|-------------|-----------|
| `didResolve` | `resolveDid`、`queryAndValidateVcid`、`checkGranteeCredentialUpdate` |
| `publishDid` | 所有写操作（更新昵称/头像/凭证等） |
| `generateVC` | `addCredentialToDid`、`updateDidAvatar` |
| `verifyCredential` | `verifyCredential`、`queryAndValidateVcid` |
| `didStat` | 写操作时填充 `previousCid` |

`generateVC` 通过 `contextType` 区分 `ownership` 与 `usageAuthorization` 上下文。
