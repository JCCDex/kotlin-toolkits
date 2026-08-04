# Vault 密钥模型说明（现状）

**关联：** [SECURITY_AUDIT.md](./SECURITY_AUDIT.md)（C-01 / C-02 / H-04）、[SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md)、[C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md](./C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md)  
**日期：** 2026-08-04  
**目的：** 澄清 `derivedKey`、HMAC proof、AES 加密、解锁后内存里到底有什么、派生子钱包为何「免密」——避免把「磁盘字段 / 私有方法 / 会话密钥 / 明文私钥 / HD 派生」混为一谈。

---

## 1. 一句话总览

```text
密码
  → Argon2id（salt + params 在 PasswordEntry）
  → session key（仅内存 VaultSession）
       ├─ HMAC-SHA256 → 与 proof 比对（只验密码对不对）
       └─ AES-GCM     → 加解密私钥 / 助记词 / secret（落盘仍是密文）
```

- **解锁后常驻内存的是 session key（包装密钥）**，不是整库明文私钥。
- **私钥 / 助记词默认仍以 AES 密文存在 `vault.pb`**；用到时再临时解密。
- **proto 已无 `Vault.derivedKey` 字段**（field 4 `reserved`）；加解密只经内存 session。

---

## 2. 两个容易混淆的 `derivedKey`

| 名字 | 是什么 | 现在还用吗 |
|------|--------|------------|
| **proto** `Vault.derivedKey`（field 4） | 旧版把 Argon2 密钥以 hex **写进磁盘** | **已删除**。`reserved 4` / `reserved "derivedKey"`，禁止复用 |
| **私有方法** `VaultRepository.derivedKey()` | 从 `VaultSession` 取 AES key 副本；未解锁则 `error("Vault is locked")` | **还在用**。所有加解密路径都经它取 session key |
| **`VaultSession.derivedKey()`** | session 内 key 的 `copyOf()` | **还在用**（给上面私有方法调用） |

私有方法本质是 **session AES key 访问器 + 锁定守卫**，名字沿用旧叫法，易与已删除的 proto 字段混淆。后续可改名为 `sessionKey()` / `requireSessionKey()`，行为不变。

---

## 3. HMAC vs AES：改的是 proof，不是密文格式

| 用途 | 算法 | 说明 |
|------|------|------|
| **密码证明（C-02）** | **HMAC-SHA256**（新格式） | `HMAC(sessionKey, domain_separator)` 存入 `proof_ct`，`proof_iv` 为空。只能验证，不能从 proof 还原明文密码 |
| **私钥 / 助记词 / secret** | **AES-GCM** | 仍用 session key 加解密条目；这与 C-02 无关 |
| **旧 proof 兼容** | AES-GCM 加密密码 | `proof_iv` 非空时走旧逻辑；`changePassword` 可迁到 HMAC |

C-02 解决的是：以前用 AES 把密码本身加密进 proof，再叠加热盘 `derivedKey`，可能反解出明文密码。  
C-01 / H-04 解决的是：派生密钥不再长期落盘，且未 `unlock` 不能解密 vault。

---

## 4. App 解锁后内存里有什么

### 4.1 Vault 层（解锁成功后）

| 内容 | 是否常驻内存 |
|------|----------------|
| Argon2 派生的 **session key**（`VaultSession`） | ✅ 是，直到 `lock()` / 进程退出 |
| 全部私钥明文 | ❌ 否 |
| 磁盘 `vault.pb` 中的条目 | 仍是 AES 密文 |

用到某地址私钥时：

```text
getPrivateKey / getPrivateKeyInternal
  → derivedKey() 取 session key 副本
  → AES-GCM 解密该条
  → 明文交给调用方（业务侧应尽快用完并 wipe）
```

### 4.2 DApp 桥接层（额外、短时）

`CachingSecretProvider` 会在桥接窗口 / TTL 内**按 origin+地址**缓存刚取出的明文私钥 / secret，用于折叠多步 DApp 请求。这与 VaultSession **无关**，属于按需短时缓存，生命周期结束或 `clearCache()` 后应清掉。

### 4.3 锁定

- `lock()`：销毁 session → 再解密会失败（Vault locked）。
- 杀进程：内存 session 自然消失；下次冷启动必须再解锁。

---

## 5. 派生子钱包：为什么「免密」却仍需要密钥材料

产品上的「会话内派生免密」指的是：**不再弹密码、用户不用手输私钥**；  
**不是**「只用 session key、完全不碰助记词/私钥就能 HD 派生」。

`AccountOrchestrator.deriveSubAccount` 实际路径：

```text
已 unlock（内存里有 session key）
  → vault.getMnemonicInternal(根地址)
      → 用 session key AES 解密磁盘上的助记词（临时明文）
  → WalletSdk.deriveChild(mnemonic, chain, index)
      → HD 算出子地址 + 子私钥
  → vault.importPrivateKey(子地址, 子私钥)
      → 再用 session key 把子私钥加密写回磁盘
  → finally：助记词 buffer wipe
```

| 说法 | 实际含义 |
|------|----------|
| 「会话内派生免密」 | 不弹密码；session 已解锁，可静默解助记词 |
| 「不要私钥」 | 用户不用手输/导出私钥；系统从助记词 HD 派生 |
| session key 本身 | **不能**直接派生子钱包；只是打开 vault 的钥匙 |

未 `unlock` 时 `getMnemonicInternal` 会因 `derivedKey()` → `Vault is locked` 失败，因此派生必须先解锁。

---

## 6. 旧测试包 / 升级说明

1. **解密从不读磁盘 field 4**：`unlock` 用密码 + salt 重新 Argon2 → `VaultSession`。  
2. **Schema**：field 4 已 `reserved`；API 无 `getDerivedKey`。极旧 blob 里若仍有 wire field 4，会变成 unknown（javalite 默认保留）；不参与加解密。外层仍有 Tink。  
3. **清残留（可选）**：升级前用仍含 `clearDerivedKey` 的旧 build 解锁一次；或清应用数据；或日后对 `VaultSerializer` 使用 `DiscardUnknownFieldsParser`（见 C-01 方案 §3.2）。

结论：**读钱包只依赖密码重新派生**，不依赖磁盘 `derivedKey`。

---

## 7. proto field 4（已收口）

```protobuf
message Vault {
  repeated PrivateKeyEntry keys = 1;
  repeated MnemonicEntry mnemonics = 2;
  PasswordEntry password = 3;
  reserved 4;
  reserved "derivedKey";
  repeated SecretEntry secrets = 5;
  BiometricEntry biometric = 6;
}
```

| 项 | 状态 |
|----|------|
| field 4 | ✅ `reserved`，禁止复用编号与字段名 |
| 私有方法 `derivedKey()` | 保留；建议日后改名消除歧义 |

---

## 8. 相关修复索引

| 议题 | 文档 / 编号 | 落地结果 |
|------|-------------|----------|
| 派生密钥落盘 | C-01 / VaultSession | 密钥只进内存 session；field 4 reserved |
| 磁盘 key 回退 + App 解锁 | H-04 | `derivedKey()` 仅读 session；ccdao/jdid 冷启动 unlock |
| 密码 proof 可还原 | C-02 | 新 proof 用 HMAC-SHA256 |
| 会话内存模型与 HD 派生 | 本文 §4–§5 | 常驻 session key；派生时临时解密助记词 |

---

## 9. 组件状态一览

| 组件 | 角色 | 当前状态 |
|------|------|----------|
| proto field 4 | 旧版磁盘持久化密钥 | ✅ `reserved`；schema 无此字段 |
| `VaultRepository.derivedKey()` | 取 session AES key | 使用中（建议日后改名） |
| `VaultSession` | 进程内包装密钥 | 使用中；`lock` / 进程退出即销毁 |
| Password proof | 校验密码 | 新格式 HMAC-SHA256；旧 AES proof 可读并可迁移 |
| AES-GCM 条目密文 | 私钥 / 助记词 / secret | 使用中；与 proof 算法无关 |
| HD 派生子账户 | `getMnemonicInternal` → `deriveChild` → `importPrivateKey` | 需已 unlock；会话内不弹密码 |
| `CachingSecretProvider` | DApp 短时明文缓存 | 与 VaultSession 独立；按 TTL / 生命周期清理 |
