# C-01 收口：删除 proto `Vault.derivedKey`（field 4）

**关联：** [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) C-01 · [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) · [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) Phase F  
**状态：** 📝 方案待实施  
**日期：** 2026-08-04  
**仓库：** 仅 `kotlin-toolkits`（`sdk20`）；两 App **无** `derivedKey` 引用，一般无需改代码  

---

## 1. 背景

| 阶段 | 状态 |
|------|------|
| C-01 Phase 1 | ✅ 已做：`VaultSession` + `unlock`/`lock`；加解密只读 session |
| 磁盘字段 | 🟨 proto 仍保留 `string derivedKey = 4`；`unlock` 成功后若非空则 `clearDerivedKey()` |
| 正式发版 | ❌ 尚未发布含 vault 的正式 toolkits / App 商店包 |

业务上 **已不再读写 field 4 做加解密**。字段仅作「旧测试包残留」兼容。未正式发版 → **现在删字段比发版后再迁更干净**。

---

## 2. 目标与非目标

### 2.1 目标

1. 从 `private_key_vault.proto` **移除** `derivedKey` 字段定义。  
2. 用 protobuf **`reserved`** 永久占用编号 4 与字段名，禁止复用。  
3. 删除 `VaultRepository.unlock` 中 `clearDerivedKey()` 迁移分支。  
4. 同步文档：C-01 → ✅；`VAULT_KEY_MODEL` 去掉「磁盘残留」叙述。  
5. SDK vault 相关单测通过。

### 2.2 非目标（本轮不做）

| 项 | 原因 |
|----|------|
| 重命名私有方法 `derivedKey()` / `VaultSession.derivedKey()` | 仅命名易混，行为正确；可另开清理 PR |
| 改两 App | 无 proto 字段依赖 |
| C-04 / M-09 / M-16 | 独立长期项 |
| 强制用户清数据的产品流程 | 仅文档提示内部测试机可选清数据 |

---

## 3. 为什么可以现在做

1. **无正式发版兼容义务** — 没有商店用户必须从「写磁盘 derivedKey 的旧包」平滑升级。  
2. **代码已不依赖该字段解密** — `derivedKey()` 仅读 `VaultSession`；未解锁直接 `error("Vault is locked")`。  
3. **新 vault 本就不写 field 4** — `initializePassword` 等路径早已不写 proto `derivedKey`。  
4. **两 App 零引用** — grep `derivedKey` / `clearDerivedKey` 仅落在 SDK vault + docs。

### 3.1 内部测试机风险（可接受）

若某设备仍装着 **C-01 之前** 写入的 `vault.pb`（含 field 4 hex），且从未用新版 `unlock` 清过：

| 现象 | 影响 |
|------|------|
| wire 上 field 4 变成 unknown | 不再被 API 读取；**不参与解密** |
| 敏感 hex  theoretically 仍躺在 blob 里直到整文件重写 | 外层仍有 Tink；正式产品本就不会发带磁盘 key 的包 |
| 极端：极旧包 + 依赖磁盘 key 的逻辑 | 当前 `sdk20` 已无此逻辑；坏了就清应用数据 / 重新导入 |

**约定：** 内部验证时若遇异常 vault，清数据或恢复备份即可。

---

## 4. 实施方案

### 4.1 Proto（唯一破坏性表面）

**文件：** `vault/src/main/proto/private_key_vault.proto`

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

**禁止：** 删除 field 后把 `= 4` 分给新字段（即使未发版，也保留 reserved 习惯）。

### 4.2 VaultRepository

**文件：** `vault/src/main/java/com/jccdex/toolkits/vault/VaultRepository.kt`

删除 `unlock` 成功后的迁移块：

```kotlin
// 删除整段
if (data.derivedKey.isNotEmpty()) {
    vaultStore.updateData { vault ->
        vault.toBuilder().clearDerivedKey().build()
    }
}
```

保留：

- `VaultSession.derivedKey()`（内存 session key 副本）  
- 私有方法 `derivedKey()`（session 访问器 + locked 守卫）

二者 **不是** proto 字段，名称本轮可不改。

### 4.3 生成代码

本地 / CI：`./gradlew :vault:generateDebugProto`（或完整 `:vault:testDebugUnitTest` 会触发）。确认生成类中无 `getDerivedKey` / `clearDerivedKey` / `DERIVED_KEY_FIELD_NUMBER`。

### 4.4 测试

| 动作 | 说明 |
|------|------|
| 跑 `:vault:testDebugUnitTest` | 必过 |
| 既有单测 | **默认不改断言语义**；若生成 API 删除导致编译失败，只改编译层面（去掉对 `derivedKey` proto accessor 的引用），不放宽安全断言 |
| 可选补测 | 「unlock 成功且无 clearDerivedKey 调用」——非必须；字段已不存在即达标 |

### 4.5 文档回写（同 PR）

| 文件 | 改动 |
|------|------|
| [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) §1.1 | C-01 → ✅；正文状态改为 field 4 `reserved`，无磁盘持久化 |
| [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) | 删「proto 残留 / unlock 清空」；表内改为 reserved |
| [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) §8 / §13.5 | C-01 标 ✅，链到本文 |
| [README.md](./README.md) | 索引加入本文 |

---

## 5. 实施清单（勾选）

- [ ] Proto：`reserved 4` + `reserved "derivedKey"`，删除 `string derivedKey = 4`  
- [ ] 删除 `unlock` 内 `clearDerivedKey` 分支  
- [ ] `:vault:testDebugUnitTest` 通过  
- [ ] 文档四处回写  
- [ ] （可选）内部装机：旧测试 vault 解锁 / 清数据冒烟  

**App：** ccdao / jdid 无需改代码；继续 `mode=local` 联调即可。

---

## 6. 验收标准

1. Schema 无 `derivedKey` 字段；存在 `reserved 4`。  
2. `VaultRepository` 无 `clearDerivedKey` / `data.derivedKey` 引用。  
3. 新建密码 → unlock → 导入密钥 → 加解锁循环，行为与改前一致。  
4. 审计矩阵 C-01 = ✅。  

---

## 7. 回滚

Git 还原 proto + `unlock` 迁移块即可。未发版场景下无商店用户数据迁移回滚问题。

---

## 8. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-08-04 | 初稿：未正式发版前提下收口删除 field 4 |
