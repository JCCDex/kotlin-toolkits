# VaultSession 重构方案 v2

## 1. 背景

当前 vault 模块在初始化时通过 Argon2id 派生加密密钥，以 hex 字符串写入 `protobuf.Vault.derivedKey`，后续所有加解密直接从磁盘读取此密钥。任何能访问 `vault.pb` 文件的攻击者都可以解密全部私钥/助记词/secret，绕过了用户密码保护。

安全审计发现 `C-01` 将其列为 Critical 级别。

## 2. 目标

- 删除 protobuf 中 `derivedKey` 字段的持久化
- 密钥仅在 `unlock()` 时派生一次，存储在内存 `VaultSession` 中
- 进程死亡后密钥自动销毁，强制重新输入密码
- 公开 API 签名不变，最小化外部调用方改动

## 3. 架构对比

```
Before:  initializePassword → derivedKey 写入 proto 磁盘
         getPrivateKey       → 从磁盘 proto 读 derivedKey → 解密
         
After:   unlock(password)   → Argon2id → VaultSession（内存，仅进程内存活）
         getPrivateKey       → session.derivedKey() → 解密
         进程死亡            → session 自动销毁
```

## 4. Phase 1：SDK `vault` 模块（破坏性 API 变更）

### 4.1 Proto 层

```
Vault.derivedKey (field 4) → deprecated，保留字段编号不删除（兼容旧数据读取）
```

### 4.2 VaultSession

```kotlin
// VaultRepository.kt
@Volatile
private var vaultSession: VaultSession? = null
val isUnlocked: Boolean get() = vaultSession != null

class VaultSession(key: ByteArray) {
    fun derivedKey(): ByteArray = key
    fun destroy() { key.wipe() }
}
```

### 4.3 unlock / lock

```kotlin
suspend fun unlock(password: ByteArray): Boolean {
    val key = Argon2idKdf.deriveKey(password, saltFromProto, params)
    if (!verifyProof(key, proofFromProto)) {
        key.wipe()
        password.wipe()
        return false
    }
    vaultSession = VaultSession(key)
    password.wipe()
    return true
}

fun lock() {
    vaultSession?.destroy()
    vaultSession = null
}
```

### 4.4 initializePassword 自动 unlock

```kotlin
suspend fun initializePassword(password: ByteArray) {
    // ... 同现有逻辑，但 derivedKey 不再写入 proto
    vaultSession = VaultSession(key)  // 创建后自动进入 unlocked 状态
}
```

### 4.5 verifyPassword 优化

```kotlin
suspend fun verifyPassword(password: ByteArray): Boolean {
    val session = vaultSession
    if (session != null) {
        // unlocked: 直接用 session key 验证 proof
        return constantTimeVerify(session.derivedKey(), proofFromProto)
    }
    // locked: 临时派生 → 验证 → 立即 wipe
    val key = Argon2idKdf.deriveKey(password, salt, params)
    val ok = constantTimeVerify(key, proofFromProto)
    key.wipe()
    return ok
}
```

### 4.6 内部方法守卫

**需要 `require(isUnlocked)`（调用方自行管理会话状态）：**

```
getPrivateKeyInternal
getMnemonicInternal
importPrivateKey
importMnemonic
importSecret
importPrivateKeys
lockedImportPrivateKey
changePassword          // 接受 old + new 两个密码，语义要求已解锁
```

**自动 unlock（接受单个 password 参数，内部 `isUnlocked || unlock(password)`）：**

```
getPrivateKey
getSecret
```

**不需要守卫（proto 明文字段，不经过加解密）：**

```
getMnemonicLanguage
listAccounts
verifyPassword
hasPassword
hasBiometric
addressIn*
removeAddress
```

### 4.7 changePassword

```kotlin
suspend fun changePassword(oldPassword: ByteArray, newPassword: ByteArray) {
    checkNotNull(vaultSession)
    // oldPassword 额外验证 → 通过后用 session.key 解密全部条目
    // newKey = Argon2id(newPassword, newSalt, params)
    // 所有条目用 newKey 重加密 → 写回 proto
    // 更新 proto salt/params/proof
    session.key.wipe()
    vaultSession = VaultSession(newKey)
}
```

### 4.8 Biometric

```kotlin
suspend fun unlockWithBiometric(ciphertext: ByteArray): Boolean {
    val key = AndroidKeyStore.decrypt(ciphertext) ?: return false
    vaultSession = VaultSession(key)
    return true
}
```

### 4.9 clearAllData

```kotlin
suspend fun clearAllData() {
    lock()  // 必须先销毁 session
    vaultStore.clearAll()
}
```

### 4.10 公开 API 签名

**不变。** 所有现有方法签名保持原样，内部从 `vaultSession.derivedKey()` 取值。外部调用方零改动。

### 4.11 工作量

~200 行改动，`VaultRepository.kt` 一个文件。

---

## 5. Phase 2: jdid 接入

jdid 已有 `UnlockViewModel` + `UnlockProfileRepository`。

| 文件 | 改动 |
|------|------|
| `UnlockViewModel.kt` | 冷启动调 `vault.unlock(password)`；切后台调 `vault.lock()` |
| `WalletViewModel.kt` | 现有 unlock 流程适配新 SDK API |
| `AccountOrchestrator` | 构造不变（VaultRepository 单例内部持有 session） |

工作量：~3–5 文件，改动量小。

---

## 6. Phase 3: ccdao 接入

ccdao 当前无冷启动解锁流程，需从零搭建。

| 类型 | 文件 | 说明 |
|------|------|------|
| 新增 | `UnlockScreen.kt` | 解锁页 UI（密码输入框） |
| 新增 | `UnlockViewModel.kt` | `vault.unlock()` / `vault.lock()` |
| 改造 | `NavGraph.kt` | 启动路由：`isUnlocked ? MainScreen : UnlockScreen` |
| 改造 | 各敏感操作处 | 调用前检查 `isUnlocked`，未解锁弹密码框 |

可选：后台超时自动 `lock()`、biometric 快速解锁。

工作量：3~4 新文件 + 入口路由改造，中等。

---

## 7. Phase 4: 收尾

- `unlock()` 中检测 proto 仍携带旧 `derivedKey` 字段 → 用旧 key 解密全部条目 → 新 key 重加密 → 清空旧字段
- 下个 SDK 大版本从 proto schema 删除 `derivedKey`（field 4）
- 删除 `VaultRepository.derivedKey()` 私有方法

---

## 8. 改动量总览

| Phase | 仓库 | 改动量 | 风险 |
|-------|------|--------|------|
| 1 | SDK vault | ~200 行 | 中（SDK 破坏性变更） |
| 2 | jdid | 3–5 文件 | 低 |
| 3 | ccdao | 3–4 新文件 + 入口路由 | 中（新 UI） |
| 4 | SDK vault | ~50 行 | 低 |

瓶颈在 Phase 3——ccdao 需从零搭建解锁 UI，但无法绕过。

---

## 9. 实现审查记录

代码审查（2026-07-28，Phase 1 初版实现）发现以下问题。

### 9.1 clearAllData 缺少 lock()

**问题**：`clearAllData()` 未调用 `lock()`。在 unlocked 状态下调用会清空 proto 数据，但 `vaultSession` 仍然存活——后续 `derivedKey()` 返回旧 session key，状态不一致。

**当前实现**（需修复）：
```kotlin
suspend fun clearAllData() = mutex.withLock {
    vaultStore.updateData { Vault.getDefaultInstance() }
}
```

**修复后**（与 `4.9` 一致）：
```kotlin
suspend fun clearAllData() = mutex.withLock {
    lock()  // 必须先销毁 session
    vaultStore.updateData { Vault.getDefaultInstance() }
}
```

**测试缺口**：`test_z8` 仅在 locked 下调 `clearAllData`，需补 unlocked 场景（assert `clearAllData` 后 `isUnlocked == false`）。

### 9.2 verifyProof 缺少防御性比较

**问题**：`verifyProof()` 仅检查 AES-GCM 解密是否成功（无 AEAD 异常即返回 true），省略了原 `verifyPassword` 中的 `MessageDigest.isEqual(pt, password)` 比较。

**当前实现**：
```kotlin
private fun verifyProof(key: ByteArray, env: PasswordEntry): Boolean {
    return try {
        AESCrypto.decrypt(env.proofIv.toByteArray(), env.proofCt.toByteArray(), key, env.aad.toByteArray())
        true  // AEAD 未抛异常 → 认为 key 正确
    } catch (_: Throwable) {
        false
    }
}
```

**风险**：当前依赖 AES-GCM 认证失败来拒绝错误 key，实际安全。但如果将来加密算法被替换为无认证模式（如 AES-CTR），会静默接受错误 key。

**建议**：保持当前实现（AEAD 认证已足够），但加注释说明依赖 AES-GCM 的认证特性。若后续安全审计要求加固，改为显式比较：

```kotlin
// 防御性加固版本（可选）
val pt = AESCrypto.decrypt(...)
MessageDigest.isEqual(pt, ???)  // 需要原始 password，但 unlock() 未保留
```

> 注意：`unlock()` 不保留原始 password，无法在 `verifyProof` 中做 `MessageDigest.isEqual` 比较。如需加固，应在 `unlock()` 中保留 password 引用直到验证完成。

---

## 10. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-28 | 初版，A 方案（session 传参） |
| v2 | 2026-07-28 | 改为 B 方案（内部持有 session），API 签名不变；补充守卫清单、自动 unlock、线程安全、initialize 自动 unlock |
| v2.1 | 2026-07-28 | 代码审查：补充 `clearAllData` lock 缺失、`verifyProof` 防御性比较说明 |
| v2.2 | 2026-07-28 | Phase 1 实现：VaultSession + unlock/lock/auto-unlock。10/10 新测试通过。老测试 wipe 计数待适配 |
