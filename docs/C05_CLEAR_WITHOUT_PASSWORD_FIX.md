# C-05 修复方案：钱包擦除需密码验证

## 1. 问题

三个破坏性操作无需密码：

1. `VaultRepository.clearAllData()` — 清空 vault proto，无认证
2. `AccountOrchestrator.importHdWallet(clearExisting = true)` — 在密码校验**之前**清空旧钱包
3. `AccountOrchestrator.clearWalletData()` — 同样无门控

```kotlin
// 当前 — 无需密码即可擦除全部数据
vault.clearAllData()  // 任何人调了就全清
```

## 2. 修复

### 2.1 clearAllData 加密码验证

`VaultRepository.clearAllData()` 新增可选的密码参数。调用方传密码 → 验证 → 清空。不传密码 → 仍可清空（兼容无密码场景，如导入备份覆盖旧 vault）。

```kotlin
suspend fun clearAllData(password: ByteArray? = null) = mutex.withLock {
    if (password != null && !verifyPassword(password)) {
        throw IllegalArgumentException("Password is wrong")
    }
    lock()
    vaultStore.updateData { Vault.getDefaultInstance() }
}
```

> 设计决策：`password` 设为可选参数。导入备份时用户提供的是备份文件的密码，不是当前 vault 的密码（可能已遗忘）。强制要求当前密码会阻止合法恢复流程。

### 2.2 importHdWallet(clearExisting=true) 先验证密码

`AccountOrchestrator` 对外接口使用 `String` 类型密码（与现有接口一致），内部转 `ByteArray` 传给 `VaultRepository`。

```kotlin
suspend fun importHdWallet(
    mnemonic: String,
    password: String,
    name: String,
    clearExisting: Boolean = false,
    currentPassword: String? = null  // 新增：擦除旧数据时的密码
): AccountOperationResult<*> {
    if (clearExisting) {
        if (currentPassword != null) {
            vaultRepository.clearAllData(currentPassword.toByteArray(Charsets.UTF_8))
        } else {
            vaultRepository.clearAllData()  // backward compat
        }
    }
    // ... 继续导入
}
```

### 2.3 clearWalletData 加密码

同上，新增可选 `currentPassword: String?`。

### 2.4 类型统一

| 层 | 密码类型 | 理由 |
|----|---------|------|
| `VaultRepository` | `ByteArray?` | 内部一致，与 `verifyPassword(ByteArray)` 对齐 |
| `AccountOrchestrator` | `String?` | 对外一致，所有公开接口都用 `String` |

转换点：`String.toByteArray(Charsets.UTF_8)` 在 orchestrator 层完成。

## 3. 与 C-01 的关系

C-01 Phase 1 的 `clearAllData()` 已加入 `lock()` 调用（销毁 session）。C-05 在此基础上加上密码门控。两者独立，不冲突。

## 4. 对两 app 的影响

| App | 调用方 | 是否需要改 |
|-----|--------|-----------|
| jdid | 需确认 `clearAllData` / `clearWalletData` 的调用点 | 查一下 |
| ccdao | `RestoreBackupScreen` 走导入备份 → 不需旧密码 | 不受影响 |
| ccdao | `ExportBackupUseCase` 不调 clearAllData | 不受影响 |

## 5. 工作量

~20 行，3 个文件：`VaultRepository.kt`、`AccountOrchestrator.kt`。

## 6. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1 | 2026-07-28 | 初版 |
| v1.1 | 2026-07-28 | 类型统一：Orchestrator 用 String，Vault 用 ByteArray |
