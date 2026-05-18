# Vault SDK（`kotlin-toolkits/:vault`）

本 SDK 提供加密密钥库能力，负责在本地安全保存：
- 主密码校验信息
- 私钥
- 助记词
- Secret
- 批量私钥

底层使用：
- DataStore
- Protobuf
- Tink

## 1. 模块与关键类

- **唯一入口**：`com.jccdex.toolkits.vault.VaultRepository.get(context)`
- **核心类**：`com.jccdex.toolkits.vault.VaultRepository`
- **加密**：
  - `com.jccdex.toolkits.vault.security.AESCrypto`
  - `com.jccdex.toolkits.vault.security.Argon2idKdf`
  - `com.jccdex.toolkits.vault.security.TinkManager`
- **序列化**：`com.jccdex.toolkits.vault.serializer.VaultSerializer`

## 2. 能力说明

`VaultRepository` 主要负责：
- 初始化主密码
- 校验主密码
- 导入私钥 / 助记词 / secret
- 修改主密码
- 读取、删除和清理存储数据

## 3. 快速接入

```kotlin
val vault = VaultRepository.get(context)
```

常见调用：
- `initializePassword(password)`
- `verifyPassword(password)`
- `importPrivateKey(address, privateKey)`
- `importMnemonic(address, mnemonic, privateKey)`
- `importSecret(address, privateKey, secret)`

## 4. 存储说明

默认会使用应用私有目录下的 `vault.pb` 作为存储文件。
接入方通常不需要自己创建数据库或表。

## 5. 测试

```bash
./gradlew :vault:testDebugUnitTest
```
