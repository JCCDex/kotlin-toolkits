# PR #16 复审残留修复方案

**关联：** [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) §1.1 / §6.2（复审 2026-07-31）  
**基线：** `c2f00dc`（复审文档）/ 代码对照 PR `#16`（`69f69c1`）及当前 `sdk20`  
**状态：** ✅ Phase A–E 已实施；F 待做；实施后残留见 **§13**  
**日期：** 2026-08-03  

**本文结构：** §1–2 目标与阶段 → §3–8 Phase A–F → §9–11 跨仓顺序 / 测试 / 矩阵 → §12 审核纪要 → **§13 实施后残留（唯一维护入口）** → §14 修订记录。

**仓库约定：**

| 仓库 | 路径 | 工作分支 |
|------|------|----------|
| SDK | `kotlin-toolkits` | `sdk20` |
| ccdao app | `ccdao-connector-android` | `sdk6` |
| jdid app | `jdid-android` | `jdid02` |

---

## 1. 背景与目标

PR `#16` 已实质推进 VaultSession、HMAC proof、nonce、origin 缓存、VC 验签、编排锁与私钥不出模型等。复审结论：**未完全收口**，并新增 **R-01**。

本方案只覆盖复审后仍打开的项，按 §6.2 优先级落地；已 ✅ 关闭项不重复施工。

### 1.1 目标

1. 堵住 DApp 响应通道的 `nonce` JS 注入（R-01 / H-03 残余）。  
2. `eth_sendTransaction` 全链路携带真实 origin（H-02 残余）。  
3. Orchestrator 擦除路径强制密码门控（C-05 编排层）。  
4. NFT HTTP 拉取全面过 `SsrfGuard`，DNS 失败改拒绝（H-06）。  
5. 清理敏感日志与 connect 授权缺口（H-07 / C-04 短期、M-06）。  
6. 文档与测试同步；补丁后再做一次复审勾选。

### 1.2 非目标（本轮不做）

| 项 | 原因 |
|----|------|
| C-04 长期：签名迁出 WebView | 架构重构 |
| M-09 Room SQLCipher | 独立大工程 |
| M-16 RPC pinning | 需运维/清单配合 |
| C-03 去掉 `window.ccdao.sendResponse` / WebMessagePort | P2；本轮以 R-01 止血 |
| H-04 将 `*Internal` 改为 `internal` | 会话守卫已落地；可见性收窄单独排期 |
| 删除 proto `derivedKey` field 4 | 大版本迁移项，见 [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) |

---

## 2. 阶段总览

| 阶段 | 优先级 | 项 | 主要模块 | 预计破坏性 |
|------|--------|----|----------|------------|
| **A** | P0 | R-01 + H-03 响应转义（只 quote，不强校验 UUID） | `:dapp-connect` | 低（API 不变） |
| **B** | P0 | H-02 `sendTransaction(origin)` | `:dapp-connect` + 两 App 同迭代 | 中（接口签名） |
| **C** | P0 | C-05 Orchestrator 强制**旧**密码 | `:account` + 两 App 同迭代 | 中（调用方必改） |
| **D** | P1 | H-06 NftStore + DNS fail-closed | `:nft` | 低～中 |
| **E** | P1 | H-07 / C-04 日志；M-06 connect | `:dapp-connect` / `:webview-bridge` + App | 低～中 |
| **F** | P2+ | C-03 / M-01 / H-04 可见性等 | 多模块 | 另立里程碑 |

建议：**A → B → C** 同迭代合入；**D → E** 紧随；**F** 单独排期。

---

## 3. Phase A — R-01 / H-03：响应 `nonce` 安全回传

### 3.1 问题

`WebAppInterfaceWithWebView`（约 L48 / L64 / L80）：

```kotlin
val callback = "window.ccdao.sendResponse(\"$nonce\", $resultStr)"
```

`nonce` 来自页面 `postMessage`，未 `JSONObject.quote`。恶意 `nonce` 可打断字符串执行任意 JS。

### 3.2 SDK 修复（本阶段必做）

**文件：** `dapp-connect/.../WebAppInterfaceWithWebView.kt`

1. 所有 `sendSuccessResponse` / `sendErrorResponse` / `sendErrorResponseWithCode` 中：
   - **`nonce` → `JSONObject.quote(nonce)`（本阶段唯一必做的防注入手段）**
   - **字符串型 `result` 一律 `JSONObject.quote`**：既防注入，也修复 `0x...` 被 JS 解析成数字的隐藏 bug（见 §12 二次核验）
2. 去掉或降级成功路径里对完整 `result` 的 `Log.d`（与 Phase E 重叠时可一并做）。

```kotlin
private fun jsCallback(fn: String, nonce: String, payloadJs: String): String =
    "window.ccdao.$fn(${JSONObject.quote(nonce)}, $payloadJs)"
```

### 3.3 不要在本阶段强制 UUID 校验（审核修订）

`postMessage` 现逻辑为：

```kotlin
val nonce = obj.optString("nonce", id)
```

无 `nonce` 时回退到 DApp 传入的 `id`。历史 / 非标 DApp 可能仍用自增 `requestId++`（审计 L-05 曾提及），**强制 UUID 形态会拒掉老 DApp**。

| 策略 | 本阶段 |
|------|--------|
| `JSONObject.quote(nonce)` | ✅ **必须做** |
| 解析后校验 UUID，否则 `sendError` | ❌ **不做**；另立评估（可与 C-03 / 新 major 一起，需 provider 与兼容矩阵） |

若未来做 UUID 门禁：应先保证官方 `ccdao-eip1193-provider.js` 始终发 UUID，再提供兼容开关或分版本启用。

### 3.4 App 影响

| App | 影响 |
|-----|------|
| ccdao / jdid | 无 API 变更；若自研子类覆盖了 send*，需同样 quote |

### 3.5 验收

- [x] 含 `"`、`);`、换行的恶意 nonce 注入失败（断言最终 JS 字符串已 quote）。  
- [x] 合法 UUID **与** 非 UUID 回退 id（如数字字符串）在 quote 后仍能回调（不因形态被拒）。  
- [x] 复审矩阵：R-01 → ✅；H-03 → 至少 ✅（响应通道）。

---

## 4. Phase B — H-02：`eth_sendTransaction` 传递 origin

### 4.1 问题

多数 EVM 敏感路径已传 `getOrigin()`，但：

```kotlin
// WebAppInterface.handleEthSendTransaction（约 L485）
ethMiddleware.sendTransaction(txParams)  // 无 origin
```

`IEthMiddleware.sendTransaction(txParams)` 亦无 origin；`signTransaction` 默认 `origin = ""` 时缓存/授权可被空 origin 绕过。

### 4.2 SDK 修复

**文件：**

- `MiddlewareInterfaces.kt` — `IEthMiddleware`
- `EthMiddleware.kt`
- `WebAppInterface.kt`
- 相关单测（`EthMiddlewareTest` 等）

```kotlin
// IEthMiddleware
suspend fun sendTransaction(txParams: JSONObject, origin: String): String

// EthMiddleware
override suspend fun sendTransaction(txParams: JSONObject, origin: String): String {
    val result = signTransaction(txParams, origin)
    return nodeProvider.broadcastTransaction(result.data, result.chain)
}

// WebAppInterface
ethMiddleware.sendTransaction(txParams, getOrigin())
```

可选加固（本阶段建议一并做）：

- `signTransaction` / `sendTransaction`：`origin.isBlank()` 时拒绝或走显式「内部」常量（禁止默默用 `""`）。  
- 与 `CachingSecretProvider` 约定：空 origin 不命中跨站缓存（H-01 已隔离键；空串仍应避免）。

### 4.3 App 影响（接口破坏性 — 必须与 SDK 同迭代）

| App | 影响 |
|-----|------|
| ccdao `sdk6` | `SdkAdapter` / 本地 `EthMiddleware` 等实现 `IEthMiddleware` 的适配层 **同步加 `origin`**；调用处传 `getOrigin()` |
| jdid `jdid02` | DApp WebView 适配层同样改签名 |

> **跨仓：** 本阶段不可只合 SDK。顺序见 §9：先 sdk20，再立刻改两 App 适配层，避免中间态编译失败。

### 4.4 验收

- [x] `handleEthSendTransaction` 验证传给 middleware 的 origin == `setOrigin(...)`。  
- [x] 空 origin 行为有明确测试（拒绝或内部常量，二选一并写进文档）。  
- [x] ccdao / jdid 适配层编译通过。  
- [x] 复审矩阵：H-02 → ✅。

---

## 5. Phase C — C-05：Orchestrator 擦除强制密码

### 5.1 问题

`VaultRepository.clearAllData(password?)` 已支持可选密码，但：

```kotlin
// AccountOrchestrator.kt 约 L61 / L231
if (clearExisting) {
    store.clearAllAccounts()
    vault.clearAllData()          // 无参
}
suspend fun clearWalletData() {
    store.clearAllAccounts()
    vault.clearAllData()          // 无参
}
```

任意调用方仍可无密码清空。

### 5.2 设计决策（相对旧 C-05 文档收紧）

| 场景 | 策略 |
|------|------|
| `clearWalletData` | **必须** `password: ByteArray`；错误密码抛错，不清空 |
| `importHdWallet(..., clearExisting = true)` | 若 `vault.hasPassword()` → **必须**用**当前钱包密码**调用 `clearAllData(password)`；无密码 vault 才允许无参清空 |
| 备份恢复覆盖 | App 层先用当前密码（或已确认的重置流程）清空，再导入 |
| `VaultRepository.clearAllData(null)` | 可保留给「确认无密码 / 测试 / 受控迁移」；Orchestrator **不得**在有密码时走 null |

旧方案 [C05_CLEAR_WITHOUT_PASSWORD_FIX.md](./C05_CLEAR_WITHOUT_PASSWORD_FIX.md) 的「可选密码兼容」导致编排层漏洞未关；**本阶段以编排层强制门控为准**。

### 5.3 密码语义（审核修订 — 必读）

`clearAllData(pwd)` / `verifyPassword(pwd)` 验证的是 **当前 vault 已存在的密码**，**不是**即将 `initializePassword` / 导入的新密码。

| 调用 | `password` 含义 |
|------|-----------------|
| `importHdWallet(..., clearExisting = true, password = X)` | **X = 旧钱包密码**（用于擦除）；导入后若需设新密码，应另参或后续 `initializePassword(新密码)`，不可把「重置页上的新密码」误当成擦除凭证 |
| `clearWalletData(password)` | 当前钱包密码 |

**ccdao reset 风险：** `WalletViewModel.importHDWallet(..., isResetMode = true)` 当前把同一 `password` 传给 `importHdWallet`。重置 UI 若只收集「新密码」，强制门控后会 **验密失败、无法清空**。

**App 层必须：**

1. Reset / 忘记密码覆盖：走「先验旧密码（或助记词重置流程已证明控制权）→ `clearAllData(旧密码或受控 API)` → 再用新密码初始化」；或  
2. 拆参：`clearExistingPassword: ByteArray?`（旧）与 `newPassword: ByteArray?`（新）在 Orchestrator / ViewModel 层分离。

jdid `PrimaryWalletRepository` `clearExisting = true` 同样核对传入的是当前密码还是新密码。

### 5.4 SDK 修复

**文件：** `account/.../AccountOrchestrator.kt` + `AccountOrchestratorTest.kt`

推荐拆参，避免语义混淆：

```kotlin
suspend fun importHdWallet(
    hdResult: GenerateHDWalletResult,
    name: String,
    password: ByteArray?,                 // 导入后初始化 / 已有密码场景下的业务密码
    clearExisting: Boolean = false,
    clearExistingPassword: ByteArray? = null  // 仅 clearExisting 时：当前 vault 密码
): AccountOperationResult<ImportHdWalletResult> = runOperation {
    if (clearExisting) {
        if (vault.hasPassword()) {
            val pwd = clearExistingPassword
                ?: return@runOperation Error(PasswordRequiredForClear)
            vault.clearAllData(pwd)
        } else {
            vault.clearAllData()
        }
        store.clearAllAccounts()
    }
    // ... 导入；initializePassword(password) 等用「新/业务密码」
}

suspend fun clearWalletData(password: ByteArray): AccountOperationResult<Unit> =
    runOperation {
        vault.clearAllData(password)
        store.clearAllAccounts()
    }
```

注意顺序：有密码时 **先** `clearAllData(password)` 验证成功，再清 account store。

### 5.5 App 影响（接口破坏性 — 必须与 SDK 同迭代）

C-05 的边界是 **Orchestrator 门控**：有密码时编排层不得无参 `clearAllData()`。App 在已证明控制权的路径上，仍可直接调用 `VaultRepository.clearAllData()`（无参）——这是**受控 App 路径**，不是编排漏洞。

| App | 位置 | 改造 / 策略 |
|-----|------|-------------|
| ccdao | `WalletViewModel.importHDWallet(..., isResetMode)` | 拆清「旧密码 / 新密码」；若 UI 已验证助记词控制权且未提供旧密码，先走 `vault.clearAllData()` 再导入（§5.3） |
| ccdao | `clearWalletData()` | 改为必传当前密码（经 Orchestrator） |
| ccdao | `ImportBackupUseCase` | **受控恢复**：仅在备份解密成功（备份密码正确）后、`isResetMode` 下调用 `vault.clearAllData()`；密码错误提前返回，永不擦除。凭证是**备份文件密码**，不是当前 vault 密码，故不经 Orchestrator `clearExistingPassword` |
| jdid | `PrimaryWalletRepository` | 助记词/QR 证明控制权后可 `vault.clearAllData()`；经 Orchestrator 的 `clearExisting` 必须传**当前**密码，勿用新密码 |

### 5.6 验收

- [x] 有密码 vault：`clearExisting=true` + 错误旧密码 → 不清空。  
- [x] 有密码 vault：仅提供新密码、不提供旧密码 → 失败（防 ccdao reset 误用）。  
- [x] `clearWalletData` 无公开无参 API。  
- [ ] 两 App reset/恢复路径手测通过。  
- [x] 复审矩阵：C-05 → ✅。

---

## 6. Phase D — H-06：NftStore SSRF + DNS fail-closed

### 6.1 问题

1. `SsrfGuard` 已用于 `NftRemoteAssetResolver.fetchMetadataImage`，但 `NftStore.fetchJson` / `fetchText`（约 L483 / L501）**未调用**。  
2. `NftRemoteAssetResolver.kt` 约 L163：`InetAddress.getByName(host).getOrNull() ?: return true` 为 **fail-open**。

### 6.2 SDK 修复

**文件：**

- `nft/.../NftRemoteAssetResolver.kt`（`SsrfGuard`）
- `nft/.../storage/room/NftStore.kt`
- `NftRemoteAssetResolverTest` / 相关 Nft 测试

```kotlin
// SsrfGuard.check
val addr = runCatching { InetAddress.getByName(host) }.getOrNull()
    ?: return false   // fail-closed

// NftStore
private suspend fun fetchJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
    if (!SsrfGuard.check(url)) return@withContext null
    // ... 原 HTTP 逻辑
}
```

`fetchText` 同样入口守卫。若 URL 经 IPFS 网关改写，**在最终请求 URL 上**做 check。

### 6.3 App 影响

通常无 API 变更；依赖「内网 metadata」的调试环境需 `SsrfGuard.enabled = false`（仅 debug/测试）。

### 6.4 验收

- [x] DNS 失败 / 私网 / localhost → `check == false`。  
- [x] `NftStore` 拉取路径单测 mock 断言未过 guard 不发请求（`fetchAndCacheNftMeta_ssrfGuardBlocksPrivateUrlWithoutHttp`）。  
- [x] 复审矩阵：H-06 → ✅。

---

## 7. Phase E — H-07 / C-04 短期日志 + M-06 connect

### 7.1 日志（H-07 / C-04 残余）

| 位置 | 动作 |
|------|------|
| `WebAppInterface` / `WithWebView` | release 不打完整 `result` / error message 中的敏感域 |
| `wallet-bridge.js` | 移除或 `DEBUG` 守卫交易对象 log |
| `WebviewBridgeClient` console 转发 | 确认 release 关闭 |

### 7.2 M-06 connect 授权（接口/行为破坏性）

| 动作 | 说明 |
|------|------|
| SDK | 文档强制：生产必须 `setRequestAccountsCallback`；未设置时 `requestAccounts` 拒绝或仅返回空（**破坏性**，需版本说明） |
| SDK | SWTC `requestAccounts` 对齐同一回调接口 |
| App | ccdao / jdid 接上授权 UI，按 origin 持久化（若尚未做） |

> 与 Phase B 相同：SDK 行为变更后两 App 须同迭代接线，见 §9。

### 7.3 验收

- [ ] release 构建抽样 logcat 无完整 tx / 私钥 / 助记词。  
- [x] 未设回调时行为符合文档（拒绝或空列表）。  
- [x] H-07 → ✅（主路径脱敏）；M-06 → ✅（强制回调 + App UI）。

---

## 8. Phase F — 后续（本方案只建账）

| 项 | 方向 | 文档 |
|----|------|------|
| C-03 | 隐藏/移除页面可调 `sendResponse`；改 WebMessagePort | [C03_REQUEST_NONCE_FIX.md](./C03_REQUEST_NONCE_FIX.md) |
| M-01 | 失败次数锁定 / 退避 | [M_ISSUES_FIX_PLAN.md](./M_ISSUES_FIX_PLAN.md) |
| H-04 | `*Internal` → `internal` + 编排 API | [H04_VAULT_INTERNAL_SESSION_FIX.md](./H04_VAULT_INTERNAL_SESSION_FIX.md) |
| C-01 | 大版本删 proto field 4 | [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) |
| C-04 长期 / M-09 / M-16 | Native 签名、SQLCipher、pinning | 审计 §6.2 P3 |
| （可选）nonce UUID 门禁 | 在 quote 之上评估强制 UUID；需兼容矩阵 | 本方案 §3.3 |

---

## 9. 跨仓落地顺序

```text
1. sdk20：Phase A（可先合，无 API 破坏）
2. sdk20：Phase B + 同 PR/紧随 PR 改 ccdao sdk6、jdid02 的 IEthMiddleware / SdkAdapter
3. sdk20：Phase C + 同迭代改两 App（reset 旧密码语义、clearWalletData 必参）
4. sdk20：Phase D（通常无 App API 变更）
5. sdk20：Phase E + 两 App connect 回调接线
6. 更新 SECURITY_AUDIT §1.1 矩阵 + 本方案状态栏
7. SDK 全量 unit test + 两 App 相关回归
```

**原则：**

- A 可单独合入。  
- **B / C / E 含破坏性接口或行为，禁止「只发 SDK、App 滞后」**。  
- D 可紧随 A–C；F 单独排期。

---

## 10. 测试计划（SDK）

| 阶段 | 测试要点 |
|------|----------|
| A | 恶意 nonce → JS 已 quote；非 UUID 数字 id 不被拒、仍可回调 |
| B | sendTransaction origin 透传；空 origin 策略；App 适配编译 |
| C | 错误**旧**密码不清空；误传**新**密码不清空；clearWalletData 必参 |
| D | fail-closed DNS；NftStore 守卫 |
| E | 日志脱敏；callback 强制行为 |

约束：既有单测默认不改断言语义；接口变更导致的测试更新需人工确认（见工程约定）。

---

## 11. 复审矩阵回写（完成后勾选）

| ID | 复审前 | 本方案目标 |
|----|--------|------------|
| R-01 | ❌ | ✅（quote；不做强制 UUID） |
| H-03 | 🟨 | ✅（响应通道） |
| H-02 | 🟨 | ✅ |
| C-05 | 📌/❌ | ✅（编排强制 + 旧密码语义） |
| H-06 | 🟨 | ✅ |
| H-07 | 🟨 | ✅ |
| M-06 | 🟨 | ✅ |

| C-03 / M-01 / H-04 可见性 | 🟨 | 仍归 Phase F |

---

## 12. 外部审核纪要（2026-08-03）

方案整体准确；下列问题已对照代码核实为真实存在：

| 阶段 | 代码位置 | 问题 | 核实 |
|------|----------|------|------|
| A (R-01) | `WebAppInterfaceWithWebView.kt:48/64/80` | nonce 直接拼 JS 未转义 | ✅ |
| B (H-02) | `IEthMiddleware.sendTransaction` + `WebAppInterface.kt:485` | eth_sendTransaction 丢 origin | ✅ |
| C (C-05) | `AccountOrchestrator.kt:61/231` | clearAllData 无参调用 | ✅ |
| D (H-06) | `NftRemoteAssetResolver.kt:163` fail-open + `NftStore.kt:483/501` 无守卫 | SSRF 缺口 | ✅ |

**已吸收的修订：**

1. Phase A：**只做 quote**；UUID 强校验单独立项，避免拒掉 `nonce` 回退 `id` 的老 DApp。  
2. Phase C：写清 `clearAllData` 校验的是**当前钱包密码**；ccdao reset 须先验旧密或拆参。  
3. Phase B / E：接口/行为破坏性变更；两 App 按 §9 与 SDK 同迭代。  
4. 顺序 A→B→C 同迭代、D→E 紧随、F 另排 — **维持不变**。

**二次核验（2026-08-03，可进入实施）：**

1. Phase A 一律 quote 字符串 `result` **顺带修复隐藏 bug**：provider `sendResponse(nonce, result)` 把第二参当 JS 实参解析；当前对 `0x...` 不加引号会变成数字（如 `0x123` → `291`），quote 后才是字符串 `"0x123"`。  
2. Phase C 方案中的 `PasswordRequiredForClear`：当前 `AccountOperationError` 仅有 `PasswordRequired` — **实施时新增枚举变体或复用现有并区分语义**（预期工作，非方案错误）。  
3. A/B/C/D 问题代码位置再次核对属实；v1.1 修订已到位，**批准实施实施**。

---

## 13. A–E 实施后跨仓审核残留（2026-08-03）

> 原独立文档已并入本方案，避免多文件分叉。范围：三仓 staged（SDK `sdk20` / ccdao `sdk6` / jdid `jdid02`）。只读审核；High 已二次验证属实。  
> **2026-08-03 补丁：** §13.5 中 P0/P1/文档项已落地（见各 ID「状态」）；H-R2/H-R4 与其余 M/L 仍按表执行。

### 13.1 Phase 对齐速览

| Phase | SDK | App1 (ccdao) | App2 (jdid) |
|-------|-----|--------------|-------------|
| A quote | ✅ | ✅ 本地 override 已 quote | ✅ 走 SDK `WebAppInterfaceWithWebView` |
| B sendTx+origin | ✅ ETH send 强制非空；sign/SWTC 亦拒 blank（M-R2） | ✅ sign/send 均传 origin；导航更新 origin（H-R2） | ✅ setOrigin 随导航更新 |
| C 擦除门控 | ✅ | ✅ 拆参 + 受控 wipe | ✅ 助记词证明后 wipe |
| D SSRF | ✅ 入口守卫 + **禁 redirect**（H-R3） | N/A | N/A |
| E 日志/connect | ✅ 强制 callback | ✅ grants clear + **规范化 origin**（M-R4） | ✅ clear + 规范化 + 地址门控 |

两 App 均 `jccdex.toolkits.mode=local`；切回远端 `v0.2.5` 会编译失败（H-R4，流程项）。Critical：无（R-01 未回退）。

### 13.2 High — 验证与处置

| ID | 问题 | 验证 | 处置 / 状态 |
|----|------|------|-------------|
| H-R1 | Grants 无 `clear()`，钱包重置后旧 origin 授权残留 | ✅ | **✅ 已修** — 两 App `clear()` + wipe/reset/import 路径 |
| H-R2 | `getOrigin()` 冻结为入口 URL，导航后可绕过 | ✅ | **✅ 已修** — 导航 `onPageStarted/Finished` 同步 `setOrigin`；SDK `WebOrigin.normalize` |
| H-R3 | HTTP redirect 绕过 `SsrfGuard` | ✅ | **✅ 已修** — `instanceFollowRedirects = false` + 单测 |
| H-R4 | 远端 `v0.2.5` 无 A–E API | ✅ | **发布流程**（本轮不做） |
| H-R5 | 同一 `ByteArray` clear+init 被 `wipe()` | ✅ | **✅ 已文档化** — Orchestrator / Vault KDoc |

**H-R1（✅）** — `DappConnectGrants.clear()`；ccdao：`WalletViewModel.importHDWallet(isResetMode)` / `resetAllData` / `ImportBackupUseCase`；jdid：`PrimaryWalletRepository.persistPrimaryHdWalletDefault`。

**H-R2（✅）** — SDK `setOrigin` 规范化；ccdao `syncOriginFromUrl`；jdid `wai.setOrigin(url)` 在页面起止回调；grant/`isGranted` 用规范化键（M-R4）。

**H-R3（✅）** — `NftStore.fetchJson`/`fetchText`、`NftRemoteAssetResolver.fetchMetadataImage` 禁用自动 redirect；`fetchAndCacheNftMeta_doesNotFollowHttpRedirect`。

**H-R4（流程，跳过）** — 先发含 A–E 的 toolkits，再关 `jccdex.toolkits.mode=local`。

**H-R5（✅）** — `AccountOrchestrator.importHdWallet` / `VaultRepository.clearAllData` KDoc：禁止 clear 与 init 同引用。

### 13.3 Medium / Low

| ID | 说明 | 处置 / 状态 |
|----|------|-------------|
| **M-R1** | App1 sign 丢 origin | **✅ 已修** — `signTransaction(tx, origin)` + `SdkEthAdapter` 透传；blank reject |
| **M-R2** | SDK sign / SWTC send 空 origin | **✅ 已修** — ETH `signTransaction` + SWTC `sendTransaction` `require` 非空；App SWTC 同 |
| **M-R3** | Connect 单槽 continuation | **✅ 已修** — 对话框已展示则拒新请求（ccdao Connect/SwitchChain；jdid pending） |
| **M-R4** | Grant 键未规范化 | **✅ 已修** — `WebOrigin.normalize` + grants 存/查规范化键（含 legacy 迁移） |
| M-R5 | `clearWalletData(password)` 无法清无密码 vault | **✅ 已修** — 无密码时走无参 `clearAllData()` |
| **M-R6** | App1 Swtc 签名结果日志 | **✅ 已修** — 不再打印 result/signature 内容 |
| **M-R7** | jdid `pushWalletAddressToDapp` 不依赖 grant | **✅ 已修** — 仅 `isGranted` 后推送；批准后立即推送 |
| **L-R1** | 无 revoke UI | **✅ 已修** — 两 App「已连接的 DApp」设置页（列表/撤销/清空） |
| **L-R2** | `apply()` 丢 grant | **✅ 已修** — grants 写盘改 `commit()` |
| **L-R3** | ipfs 未 normalize | **✅ 已修** — `fetchAndCacheNftMeta` 先 `normalizeRemoteAssetUrl` |
| **L-R4** | 验收 checkbox 超前 | **✅ 已修** — §3.5–7 验收项回写 |
| **L-R5** | callback breaking 未写 README | **✅ 已修** — `dapp-connect/README.zh-CN.md` §2.3 |

### 13.4 已对齐（A–E 主路径）

| Phase | 要点 |
|-------|------|
| A | SDK quote + 单测；App1 本地 send* 已 quote |
| B | SDK/App1 adapter `sendTransaction(tx, origin)`；sign 亦传 origin；导航更新 origin |
| C | `clearExistingPassword`；vault 先于 store；受控 wipe 符合 §5.5 |
| D | DNS fail-closed + 入口守卫 + **禁 redirect** + loopback / redirect 单测 |
| E | 强制 callback；grants wipe + 规范化；jdid 地址推送门控；`wallet-bridge.js` `DEBUG=false` |

### 13.5 下一轮优先级

| 优先级 | 项 | 动作 | 状态 |
|--------|----|------|------|
| **P0** | H-R1 | 两 App：`DappConnectGrants.clear()` + wipe/reset/import 路径 | ✅ |
| **P1** | H-R3 | SDK：禁 redirect + 单测 | ✅ |
| **P1** | M-R1 | App1：sign/send 全链路传 origin | ✅ |
| **P1** | M-R7 | jdid：connect 前门控地址推送 | ✅ |
| **后置→已做** | H-R2 + M-R4 | 规范化 origin + 导航同步 | ✅ |
| **本轮** | M-R2 / M-R3 / M-R6 | blank origin；并发对话框；日志脱敏 | ✅ |
| **本轮** | M-R5 / L-R1–5 | 无密码 clear；revoke UI；commit；ipfs；文档 | ✅ |
| **流程** | H-R4 | 先发 toolkits，再关 local | **跳过（发布时再做）** |
| **文档** | H-R5 | KDoc 禁止同引用 | ✅ |
| **排期** | — | Phase F（C-03 / H-04 可见性收窄等） | 待做 |
| **软修** | H-07 残留 / H-04 软 / M-01 软 | 中间件地址脱敏；`*Internal` `@Deprecated`；解锁失败短退避 | ✅ |
| **无感** | M-04 / M-14 | vault parse size+entry cap；removeAccount 幂等 KDoc | ✅ |

---

## 14. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| 1.0 | 2026-08-03 | 基于 `c2f00dc` 复审残留起草；覆盖 A–F 与两 App 影响 |
| 1.1 | 2026-08-03 | 吸收外部审核：A 取消强制 UUID；C 明确旧密码语义；B/E 强调跨仓同迭代 |
| 1.2 | 2026-08-03 | 二次核验通过：补 0x result quote bug 说明；标注 `PasswordRequiredForClear` 需新增；进入实施 |
| 1.3 | 2026-08-03 | A–E 实施后跨仓审核残留首版（曾拆独立文件） |
| 1.4 | 2026-08-03 | High 二次验证属实 + 处置优先级（H-R1 P0 等） |
| 1.5 | 2026-08-03 | **并入单一文档**：实施后残留改为本文件 §13；删除 `SECURITY_REAUDIT_POST_IMPL_FINDINGS.md` |
| 1.6 | 2026-08-03 | §4.3 / §9 过时分支 `ccdao main` → `sdk6`（与仓库约定一致） |
| 1.7 | 2026-08-03 | 落地 H-R1 / H-R3 / H-R5 / M-R1 / M-R7；§13 状态回写 |
| 1.8 | 2026-08-03 | 落地 H-R2 / M-R4 / M-R2 / M-R3 / M-R6；H-R4 明确跳过 |
| 1.9 | 2026-08-03 | 落地 M-R5 / L-R1–5；验收 checkbox 与 README 同步 |
| 1.10 | 2026-08-03 | 低影响软修：日志地址脱敏；`*Internal` Deprecated；解锁退避 |
| 1.11 | 2026-08-03 | 无感边角：M-04 CodedInputStream+entry cap；M-14 幂等 KDoc |
