# C-02 修复方案：密码 proof 从可逆加密改为 HMAC

## 1. 问题

`initializePassword` 用 AES-GCM(derivedKey) 加密原始密码，存入 `proof_iv` / `proof_ct`。结合 proto 中持久化的 `derivedKey`（C-01），攻击者可解密 proof 恢复明文密码。

```kotlin
// 当前 — 可逆
val (iv, ct) = AESCrypto.encrypt(password, key, aad)
// 验证时
val pt = AESCrypto.decrypt(proofIv, proofCt, key, aad)
MessageDigest.isEqual(pt, password)  // ← password 存在 proto 里
```

## 2. 修复

改用 HMAC-SHA256：对固定域分隔符做 HMAC，store 结果作为 proof。验证时用输入密码重新派生 key、重新计算 HMAC、常量时间比较。没有人能从 proof 反推出密码。

```kotlin
// 修复后 — 不可逆
val proof = HMAC-SHA256(key, "CCDAO_VAULT_V1_PASSWORD_PROOF".toByteArray())
// 验证时
val key = derivedKey()
val expected = HMAC-SHA256(key, "CCDAO_VAULT_V1_PASSWORD_PROOF".toByteArray())
MessageDigest.isEqual(proof, expected)
```

关键在于 proof 不包含密码——它只证明调用方知道正确的密码（因为只能从正确的 derived key 产生正确的 HMAC）。

## 3. Proto 迁移

### 3.1 写入（新格式）

- `proof_iv` → 设空（0 长度 ByteString）
- `proof_ct` → 存 HMAC-SHA256（32 字节）

### 3.2 读取（兼容旧格式）

```kotlin
fun isNewProofFormat(env: PasswordEntry): Boolean = env.proofIv.size() == 0

fun computeProof(key: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(DOMAIN_SEPARATOR)
}
```

`verifyPassword` 逻辑：

1. 读 `derivedKey`
2. 如果是新格式（`proofIv` 为空）→ `computeProof(key)` vs `proofCt`
3. 如果是旧格式 → 解密 `proofCt` → `MessageDigest.isEqual`
4. 旧格式验证通过后，在 `changePassword` 或下次 `unlock` 时自动迁移为新格式

## 4. 代码改动

### 4.1 `initializePassword`（改动 2 行）

```diff
- val (iv, ct) = AESCrypto.encrypt(password, key, aad)
+ val proof = computeProof(key)
  ...
- .setProofIv(ByteString.copyFrom(iv))
- .setProofCt(ByteString.copyFrom(ct))
+ .setProofIv(ByteString.EMPTY)
+ .setProofCt(ByteString.copyFrom(proof))
```

### 4.2 `verifyPassword`（改动 8 行）

```diff
  val key = derivedKey()
  val valid = try {
-     val pt = AESCrypto.decrypt(env.proofIv, env.proofCt, key, env.aad)
-     MessageDigest.isEqual(pt, password)
+     if (env.proofIv.isEmpty) {
+         MessageDigest.isEqual(computeProof(key), env.proofCt.toByteArray())
+     } else {
+         val pt = AESCrypto.decrypt(env.proofIv, env.proofCt, key, env.aad)
+         MessageDigest.isEqual(pt, password)
+     }
  } catch (_: Throwable) { false }
  password.wipe()
  key.wipe()
```

### 4.3 `changePassword`（自动迁移）

changePassword 时会解密所有条目然后重加密，此时顺便把 proof 从旧格式迁移到新格式。

### 4.4 新增 `verifyProof` 共享方法（unlock + verifyPassword 共用）

C-01 的 `unlock()` 内联了旧格式 proof 检查（AES-GCM decrypt）。需要抽取双格式方法，`unlock()` 和 `verifyPassword()` 共用：

```kotlin
/** Returns true if [key] validates against the stored proof (old or new format). */
private fun verifyProof(key: ByteArray, env: PasswordEntry): Boolean {
    return try {
        if (env.proofIv.isEmpty) {
            // New format: HMAC-SHA256
            MessageDigest.isEqual(computeProof(key), env.proofCt.toByteArray())
        } else {
            // Old format: AES-GCM encrypted password
            val pt = AESCrypto.decrypt(
                env.proofIv.toByteArray(), env.proofCt.toByteArray(),
                key, env.aad.toByteArray()
            )
            // For unlock(), we don't have the original password —
            // AES-GCM authentication is sufficient (wrong key → AEADBadTagException)
            true
        }
    } catch (_: Throwable) {
        false
    }
}

private fun computeProof(key: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key, "HmacSHA256"))
    return mac.doFinal(PROOF_DOMAIN_SEPARATOR)
}
```

`unlock()` 改为调用 `verifyProof(key, data.password)` 替代内联的 AES-GCM decrypt。

`verifyPassword()` 改为调用 `verifyProof(key, env)` + 额外 `MessageDigest.isEqual(pt, password)`（仅旧格式需要，新格式只验证 key）。

### 4.5 常量定义

```kotlin
private companion object {
    val PROOF_DOMAIN_SEPARATOR = "CCDAO_VAULT_V1_PASSWORD_PROOF".toByteArray()
}
```

## 5. 测试计划

| 测试 | 说明 |
|------|------|
| 新 vault proof 不可逆 | `initializePassword` 后 proto 的 `proofIv` 为空，`proofCt` 为 32 字节 HMAC |
| 密码验证通过 | 正确密码 → `verifyPassword` 返回 true |
| 密码验证失败 | 错误密码 → 返回 false |
| 旧格式兼容 | 旧 vault（有 `proofIv` + 加密的 `proofCt`）仍可验证 |
| changePassword 迁移 | 旧 vault 改密码后 proof 变为新格式 |

## 6. 工作量

1 个文件（`VaultRepository.kt`），约 20 行改动，无破坏性 API 变更。不影响 jdid / ccdao。

## 7. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-28 | 初版 |
| v1.1 | 2026-07-28 | 补充 `verifyProof` 共享方法，兼容 unlock() 双格式；说明 C-02 与 C-01 的关系 |
