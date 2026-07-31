# H-04：Vault Internal 读密钥 + 会话解锁修复方案

**关联：** [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) H-04 / C-01，[VAULT_SESSION_REDESIGN.md](./VAULT_SESSION_REDESIGN.md)，[VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md)  
**状态：** ✅ Phase A/B/C 已落地（2026-07-31）；密钥模型说明见 VAULT_KEY_MODEL

### 已落地摘要

- **Phase A（vault）**：`derivedKey()` 仅读 session；`initializePassword` / `changePassword` 不再写磁盘 `derivedKey`；`unlock` 清空旧字段；`getPrivateKey`/`getMnemonic`/`getSecret` 通过 `ensureUnlockedWithPassword`。
- **Phase B（jdid）**：`verifyAndUnlock` → `vault.unlock`；`lockSession` → `vault.lock`。
- **Phase C（ccdao）**：新增 `UnlockScreen` / `UnlockViewModel`；有钱包且未 unlock 时启动进解锁页；后台超时 lock 后回前台回解锁页；导出备份改 `unlock`。

---

## 1. 问题

`VaultRepository.getMnemonicInternal` / `getPrivateKeyInternal` 为 **public**，且**不校验密码**，直接通过 `derivedKey()` 解密。

当前 `derivedKey()` 实现仍为：

```kotlin
vaultSession?.derivedKey() ?: Hex.decode(vaultStore.data.first().derivedKey)
```

即：**未 `unlock()` 时仍可回退到磁盘 `derivedKey`**。  
C-01 Phase 1 已引入 `VaultSession`，但 H-04 并未真正关闭「未解锁可读密钥」路径。

### 1.1 生产调用方

| 仓库 | 位置 | 用途 | 当前是否先 vault.unlock |
|------|------|------|-------------------------|
| kotlin-toolkits | `AccountOrchestrator.deriveSubAccount` | HD 派生子账户 | 否（依赖磁盘回退） |
| ccdao-connector-android | `ExportBackupUseCase.getSingleKeyData` / `buildCCDAOHDKeyPair` | 导出备份读明文 | 否（仅 `verifyPassword`） |
| jdid-android | `SubIdentityProvisioner.deriveSwtcSubAccount` | 预加载派生子身份 | 否（依赖磁盘回退） |
| jdid-android | `IdentityViewModel`（取派生地址私钥） | 创建子身份 | 否（仅 `verifyPassword`） |

### 1.2 产品约束（与市面钱包对齐）

- **App 内派生子钱包：会话内不再弹密码**（市面常见行为）。
- 不安全点不是「派生免密」，而是「**从未建立解锁会话也能解**」。
- 正确模型：**冷启动 / 回前台门禁解锁一次 → 会话内派生免密 → 超时或杀进程后 `lock()`**。

---

## 2. 目标

1. 未 `unlock` 时，任何路径都不能解密助记词 / 私钥 / secret。  
2. 已 `unlock` 时，`get*Internal` 与 `deriveSubAccount` 可继续免密使用。  
3. jdid：**复用现有解锁页**，补上真正的 `vault.unlock()`。  
4. ccdao：**新增进入 App 解锁页**（有钱包时）。  
5. 导出备份等仍可在会话之上再做一次确认（已有指纹/密码流程）。  
6. 长期将 `*Internal` 收窄为 `internal`（或仅编排层可访问）。

---

## 3. 目标架构

```
冷启动
  ├─ 无钱包 → 创建/导入（initializePassword 后自动 unlock）
  └─ 有钱包 → Unlock 页（密码 / 指纹）
                → vault.unlock(password)
                → 进入主页（isUnlocked == true）

会话内
  ├─ 派生 HD 子账户 / jdid 预加载子身份 → getMnemonicInternal（要求 isUnlocked，不再弹窗）
  ├─ 导出备份 / 签名等 → 可再确认密码或指纹 → unlock（若已 unlock 可跳过 KDF）→ 业务
  └─ 后台超时 / 主动锁定 → vault.lock()

禁止
  └─ derivedKey() 回退读 proto.derivedKey
```

---

## 4. Phase A：kotlin-toolkits（vault SDK）

### A.1 强制会话守卫

对以下方法（及同类加解密路径）增加：

```kotlin
require(isUnlocked) { "Vault is locked" }
```

至少覆盖：

- `getPrivateKeyInternal`
- `getMnemonicInternal`
- `importPrivateKey` / `importMnemonic` / `importSecret` / `importPrivateKeys`
- `lockedImportPrivateKey`（语义需一并理清：未 unlock 应失败或改为接受 password 后临时 unlock）

### A.2 去掉磁盘 key 回退

```kotlin
private fun derivedKey(): ByteArray =
    vaultSession?.derivedKey()
        ?: error("Vault is locked")
```

### A.3 旧数据迁移（与 VAULT_SESSION_REDESIGN Phase 4 对齐）

在 `unlock()` 成功后：

1. 若 proto 仍有非空 `derivedKey`：用旧 key 解密全部条目 → 用 session key 重加密 → **清空 proto.derivedKey**。  
2. 下一大版本再从 proto schema 删除 field 4。

### A.4 可见性（可放 Phase D）

短期保持 `public` + `require(isUnlocked)`，避免立刻 break 编译。  
中期改为 `internal`，app 只通过 `AccountOrchestrator` / 专用 use case 访问。

### A.5 测试

- `get*Internal` 在 locked 状态抛错。  
- `unlock` 后可读；`lock` 后再读失败。  
- 旧 vault（含磁盘 derivedKey）首次 unlock 后字段被清空。  
- `AccountOrchestrator.deriveSubAccount`：unlocked 成功 / locked 失败。

> 注意：仓库约定「既有单测默认锁定」；本项属于安全修复，新增测试优先，改旧测需人工确认。

---

## 5. Phase B：jdid-android（已有解锁页）

**不新增解锁页。**

### B.1 解锁页接到 vault session

| 现状 | 目标 |
|------|------|
| `verifyAndUnlock` → `verifyPassword` + UI `isSessionUnlocked` | 成功路径调用 **`VaultRepository.unlock(password)`**，再 `markSessionUnlocked()` |
| 指纹解锁只把密码交给业务 | 指纹解出密码后同样 `vault.unlock(password)` |
| `lockSession()` 只清 UI 标志 | 同步调用 **`vault.lock()`** |
| 后台自动锁时序已有 | 确认触发时调用 `vault.lock()` |

### B.2 预加载派生子身份

`loadDerivedSubIdentityProfile` / `SubIdentityProvisioner.deriveSwtcSubAccount`：

- **不再**在此处弹密码/指纹。  
- 依赖进入主页前已 `vault.isUnlocked`。  
- 若意外 locked：失败并引导回解锁页（或触发 relock UI），而不是静默读磁盘 key。

### B.3 创建子身份取私钥

`IdentityViewModel` 中 `verifyPassword` + `getPrivateKeyInternal`：

- 改为：已 unlock 则直接 `getPrivateKeyInternal`；未 unlock 则先 `unlock` 再读。  
- 或统一走「会话已解锁」前提，确认创建时不再重复验密（产品可选再确认）。

---

## 6. Phase C：ccdao-connector-android（新增解锁页）

**当前 `startDestination = MAIN`，无 App 级解锁门禁。**

### C.1 新增进入解锁流

有钱包（`hasPassword()` / 已有账户）时：

1. 启动进入 `UnlockScreen`（密码 + 可选指纹）。  
2. 成功 → `vault.unlock(password)` → 进入 `MAIN`。  
3. 无钱包 → 仍直接欢迎/创建/导入。

建议文件（可按现有结构微调）：

| 类型 | 建议 |
|------|------|
| 新增 | `UnlockScreen` / `UnlockViewModel` |
| 改造 | `NavGraph` 启动路由 |
| 复用 | 现有 `BiometricUnlockManager`、`VaultAuthentication` 组件模式 |

### C.2 会话内派生

`WalletViewModel.deriveSubAccount` / `AccountOrchestrator.deriveSubAccount`：

- **保持无密码 UX**（与市面一致）。  
- 前提：冷启动已 unlock。  
- locked 时返回明确错误，引导重新解锁，而不是磁盘回退。

### C.3 导出备份

设置页已接指纹优先鉴权：

- 鉴权得到密码后优先 `vault.unlock(password)`（若尚未 unlock），再执行 `ExportBackupUseCase`。  
- UseCase 内可保留 `verifyPassword` 或改为依赖 `isUnlocked`；**禁止**仅凭磁盘 key 导出。  
- Loading 文案 / 成功 Toast（含 Downloads 路径）保持现有 i18n。

### C.4 后台锁定（建议与 jdid 对齐）

- App 进入后台超过阈值 → `vault.lock()`，回前台进解锁页。  
- 进程被杀后 session 自然消失，下次冷启动必解锁。

---

## 7. 实施顺序

| 顺序 | 内容 | 破坏性 |
|------|------|--------|
| 1 | Phase A：`require(isUnlocked)` + 去掉回退 + unlock 迁移清磁盘 key | 高：未接 unlock 的 app 会立刻失败 |
| 2 | Phase B：jdid 解锁页接 `vault.unlock` / `lock` | 中 |
| 3 | Phase C：ccdao 新增解锁页 + 路由 | 中（产品可见） |
| 4 | Phase D：`*Internal` → `internal`，导出/派生 API 收口 | 中（编译层） |

**建议：** B/C 的 unlock 接线可与 A 同 PR 或紧随其后；不要只合并 A 而不接 app，否则派生/导出会全面失败。

---

## 8. 验收标准

- [ ] Locked 状态下调用 `getMnemonicInternal` / `getPrivateKeyInternal` 失败。  
- [ ] 磁盘 `derivedKey` 在首次成功 unlock 后被清空；之后仅靠 session。  
- [ ] jdid：解锁页成功后 `isUnlocked == true`；预加载派生不再弹窗且成功。  
- [ ] jdid：`lockSession` / 超时后 `isUnlocked == false`，再进敏感路径需重新解锁。  
- [ ] ccdao：有钱包冷启动必经解锁页；解锁后派生 HD 子账户仍免密。  
- [ ] ccdao：导出备份在指纹/密码后成功，且未 unlock 时不能导出。  
- [ ] 杀进程后两 App 均需重新解锁才能读密钥。

---

## 9. 明确不做 / 非目标

- **不为每次派生子钱包弹密码**（与市面不一致，亦非本方案目标）。  
- 不宣称可防御 root / 调试器 / 内存 dump；目标是关闭「未认证可读落盘密钥」类问题。  
- 不在本方案内改 DApp bridge、VC 绑定等其它 H/C 项。

---

## 10. 风险与回滚

| 风险 | 缓解 |
|------|------|
| 旧安装仍带磁盘 derivedKey | unlock 时迁移并清空 |
| 只发 SDK 不发 app | 禁止；A 与 B/C 同发或 app 先行接线 |
| 指纹只取密码未 unlock | 解锁成功路径统一 `vault.unlock` |
| 派生在 MAIN 前竞态 | 路由保证未 unlock 不能进需派生的页面 |

回滚：恢复 `derivedKey()` 磁盘回退仅作紧急手段，且应视为安全降级，需限期移除。
