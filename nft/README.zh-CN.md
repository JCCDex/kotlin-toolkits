# NFT SDK（`kotlin-toolkits/:nft`）

本 SDK 提供 NFT 头像相关能力：
- 读取本地 NFT 数据
- 解析 SWTC / EVM 头像 VC
- 拉取并缓存 NFT 元数据
- 统一解析 NFT 远程图片地址（IPFS / 相对路径 / metadata image）
- 生成头像候选数据
- **EVM Token URI 解析（v0.4.0+）**

---

## 0. EVM Token URI 解析（v0.4.0+）

### 架构

纯 Kotlin 实现，无外部依赖：

```
应用层 → EvmTokenUriClientFactory → EvmTokenUriClient
          ↓
        ChainDefaults（默认节点配置）
          ↓
        EvmRpcClient（JSON-RPC + Fallback）
          ↓
        EvmAbiCodec（ABI 编解码）
```

### 核心类

- **ChainDefaults** - 链配置管理
  - `ChainDefaults.Evm.getRpcUrls(chainId)` - 获取 EVM 链默认 RPC 节点列表
  - `ChainDefaults.Swtc.getRpcUrls()` - 获取 SWTC 链默认 RPC 节点列表

- **EvmTokenUriClientFactory** - 工厂类（提供 4 种创建方式）
  - `createDefault()` - 使用 ChainDefaults 默认节点
  - `create(provider)` - 完全自定义节点
  - `createWithFallback(additionalNodes)` - 企业推荐方案
  - `createWithOverride(customNodes)` - 部分覆盖

### 使用示例

```kotlin
// 方式1: 默认配置（开发测试）
val client = EvmTokenUriClientFactory.createDefault()

// 方式2: 完全自定义（企业完全控制）
val client = EvmTokenUriClientFactory.create { chainId ->
    when (chainId) {
        1L -> listOf("https://eth.your-node.com")
        else -> emptyList()
    }
}

// 方式3: 扩展默认节点（企业推荐）
val client = EvmTokenUriClientFactory.createWithFallback(
    additionalNodes = mapOf(
        1L to listOf("https://eth.your-private-node.com")
    )
)
// 执行顺序：公共节点（先）→ 私有节点（fallback）

// 方式4: 部分覆盖
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

### 企业推荐方案

**createWithFallback**:
- 执行顺序：公共节点（先尝试）→ 私有节点（fallback）
- 优点：公共节点免费，私有节点保证可用性
- 适用：生产环境

---

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.nft.NftSdk.create(...)`
- **核心类**：
  - `com.jccdex.toolkits.nft.NftSdk`
  - `com.jccdex.toolkits.nft.storage.room.NftStore`
- **Room 数据库**：
  - `com.jccdex.toolkits.nft.storage.room.NftRoomDatabase`
  - `com.jccdex.toolkits.nft.storage.room.NftDao`
- **实体**：
  - `NftMetaEntity`
  - `SwtcNftEntity`
  - `EvmNftItemEntity`
  - `EvmNftCollectionEntity`

## 2. 能力说明

- `getAvatarCandidates(account)`：返回可用于头像选择的 NFT 候选
- `resolveSwtcAvatar(vc)`：解析 SWTC 头像 VC
- `resolveEthrAvatar(vc)`：解析 EVM 头像 VC
- `fetchAndCacheNftMeta(contract, tokenId, tokenUri)`：拉取并缓存元数据
- `resolveCredentialImage(imageUrl, metadataUri)`：统一解析 NFT 图片地址，必要时读取 metadata 里的 `image`
- `resolveCredentialImages(requests)`：批量解析凭证图片，并按 NFT / metadata / resolved URL 自动去重
- `extractSwtcMetadataUri(tokenInfosPayload)`：从 SWTC explorer 的 `TokenInfos` 载荷中提取 metadata URI
- `fetchMetadataFields(metadataUri)`：拉取 metadata，并返回规范化后的 `image` / `name` / `description`
- `normalizeAssetUrl(rawUrl, baseUrl)`：规范化 IPFS、HTTP 网关地址、相对路径
- `extractResolvedMetadataImageUrl(metadataBody, metadataUri)`：从 metadata JSON 中提取并规范化 `image`
- `fetchResolvedMetadataImage(metadataUrl)`：直接拉取 metadata 并返回解析后的图片地址
- `isSupportedRemoteAssetUrl(url)`：判断是否可直接加载远程资源

## 3. 快速接入

```kotlin
val nftSdk =
    NftSdk.create(
        context = context
    )
```

如果你已经有自己的 `NftDao`，也可以手动装配：

```kotlin
val nftSdk = NftSdk.create(nftDao)
```

解析 NFT 图片地址：

```kotlin
val resolvedImage =
    nftSdk.resolveCredentialImage(
        imageUrl = nft.image,
        metadataUri = nft.uri
    )
```

批量预解析并去重：

```kotlin
val resolvedImages =
    nftSdk.resolveCredentialImages(
        listOf(
            CredentialImageRequest(
                imageUrl = nft.image,
                metadataUri = nft.uri,
                chainId = nft.chainId,
                contractAddress = nft.contract,
                tokenId = nft.tokenId
            )
        )
    )
```

解析 SWTC metadata：

```kotlin
val metadataUri = nftSdk.extractSwtcMetadataUri(tokenInfosJson)
val fields = metadataUri?.let(nftSdk::fetchMetadataFields)
```

## 4. 存储说明

默认会自动创建 `NftRoomDatabase`。
接入方一般不需要自己建表。

## 5. 测试

```bash
./gradlew :nft:testDebugUnitTest
```
