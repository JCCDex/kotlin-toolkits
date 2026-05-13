# DID SDK（`kotlin-toolkits/:did`）

本 SDK 提供 DID 文档的**创建 / 更新 / 发布 / 解析 / 本地存储（Room）**能力。

默认 Android 接入下，SDK 已内置：
- DID 专用 WebView JS Runtime
- Room 本地存储
- `DidBridge` / `DidResolver` 默认实现

接入方通常只需要提供头像相关扩展点。

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.did.DidSdk.create(...)`
- **本地存储**：
  - `com.jccdex.toolkits.did.storage.room.DidRoomDatabase`
  - `com.jccdex.toolkits.did.storage.room.RoomDidStore`（实现 `DidStore`）
- **核心服务**：
  - `com.jccdex.toolkits.did.service.DidCoreService`（DID 文档本地读写 + resolveAndSave）
  - `com.jccdex.toolkits.did.service.DidSyncService`（对一组账户批量 resolve）
- **端口（Ports）**：
  - `com.jccdex.toolkits.did.port.DidBridge`
  - `com.jccdex.toolkits.did.service.DidResolver`
  - `com.jccdex.toolkits.did.store.DidStore`
  - `com.jccdex.toolkits.did.port.DidAvatarResolver`（可选）
  - `com.jccdex.toolkits.did.port.DidAvatarCredentialSource`（可选，提供头像 NFT 候选，供 SDK 组装 credential）

## 2. 端口（Ports）说明

如果你使用默认 Android 工厂 `DidSdk.create(context, ...)`，`DidBridge` / `DidResolver` / `DidStore` 不需要自己实现。
只有在你要替换默认 runtime 或默认存储时，才需要关注这些端口。

### 2.1 `DidBridge`（必需）

用于调用链侧/JS Runtime 的方法（例如：`didResolve`、`publishDid`、`generateVC` 等）。

```kotlin
interface DidBridge {
    suspend fun call(method: String, params: String? = null): String
    suspend fun <T> callAs(method: String, params: String? = null, clazz: Class<T>): T
}
```

实现要求：
- **必须支持后台线程调用**（SDK 内部可能在 `Dispatchers.IO` 等线程执行）。
- 如果底层由 WebView 驱动，务必保证 **WebView 初始化发生在主线程**（见「4. Android WebView 初始化」）。

### 2.2 `DidResolver`（必需）

用于从链 / 网络解析 DID 文档（返回字符串 JSON；链上不存在可能返回 `"{}"` 或空）。

```kotlin
interface DidResolver {
    suspend fun resolve(did: String): String
}
```

### 2.3 `DidStore`（必需）

SDK 的本地 DID 文档存储端口。Android 默认实现使用 Room：`RoomDidStore`。

```kotlin
interface DidStore {
    fun observeAll(): Flow<List<DidEntity>>
    fun observe(did: String): Flow<DidEntity?>
    suspend fun get(did: String): DidEntity?
    suspend fun upsert(entity: DidEntity)
    suspend fun delete(did: String)
}
```

### 2.4 头像相关（可选）

- `DidAvatarResolver`：用于把 VC 解析成 `Nft` 展示数据（例如补全图片、名称等）。
- `DidAvatarCredentialSource`：用于从你的业务数据源里列出“可选头像 NFT”（通常来自本地 DB 或网络）。
`DidSdk` 会把 `DidAvatarCredentialSource` 的结果组装成可直接用于头像选择和发布的 credential。

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
- 默认 `DidBridge`：SDK 内置 DID 专用 WebView runtime
- 默认 `DidResolver`：通过内置 DID runtime 做链上 DID 解析
- 默认 `DidStore`：SDK 内置 Room 存储
- `DidAvatarCredentialSource`：头像候选来源，供 SDK 组装 credential
- `DidAvatarResolver`：VC 到展示用 NFT 数据的补全器

### 3.2 常用能力一览（`DidSdk`）

- **地址转 DID**：`toDid(walletAccount)`
- **监听 / 读取本地文档**：`observeDidDocument(did)` / `getDidDocument(did)`
- **解析并落库**：`resolveDid(did)`
- **创建并发布初始 DID 文档**：`uploadInitialDidDoc(privateKey, did, nickname)`
- **更新昵称并发布**：`updateDidNickname(privateKey, did, nickname, currentDoc)`
- **更新头像并发布**：`updateDidAvatar(privateKey, did, currentDoc, selectedAvatar)`
- **发布删除**：`publishDidDelete(privateKey, did)`
- **生成展示模型**：`generateDid(did)`、`generateProfileVC(did)`、`generateSwtcNft(vc)`、`generateEthrNft(vc)`

### 3.3 最小接入示例

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DidModule {
    @Provides fun provideDidAvatarCredentialSource(
        swtcNftRepository: SwtcNftRepository,
        evmTokenRepository: EvmTokenRepository
    ): DidAvatarCredentialSource =
        AppDidAvatarCredentialSource(swtcNftRepository, evmTokenRepository)
    @Provides fun provideDidSdk(
        @ApplicationContext context: Context,
        avatarResolver: DidAvatarResolver,
        avatarCredentialSource: DidAvatarCredentialSource
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

如果你自己替换 `DidBridge` 实现，仍然需要满足：
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
