# NFT SDK（`kotlin-toolkits/:nft`）

本 SDK 提供 NFT 头像相关能力：
- 读取本地 NFT 数据
- 解析 SWTC / EVM 头像 VC
- 拉取并缓存 NFT 元数据
- 生成头像候选数据

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

## 4. 存储说明

默认会自动创建 `NftRoomDatabase`。
接入方一般不需要自己建表。

## 5. 测试

```bash
./gradlew :nft:testDebugUnitTest
```
