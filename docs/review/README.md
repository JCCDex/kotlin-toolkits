# 代码审查修复计划（批次总览）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md)（同目录，§4.2 明确的重复项 / §7 P0 资金安全等）。本 README 为代码审查修复计划的**批次总览**，后续所有批次的计划均在此维护。
> 目标：以「改动小、无行为变化、收益直接」为序，先落地零风险的收敛项，并把两个同文件、同批次即可完成的安全 P0 一起做掉。
> 原则（项目 CLAUDE.md）：**编辑任何符号前先跑 `impact` 分析**；**提交前跑 `detect_changes()`**；每项完成后 `./gradlew ktlintCheckAll` + 相关模块测试。
> 目录约定：本 README 为批次总览，**所有批次的修复计划均直接维护在本 README 内**（不拆分独立文件）。  
> **状态同步**：2026-09-01（§26 宿主画像、jdid 非交易类不适用 A1；详见 [CODE_REVIEW_ANALYSIS.md §11](CODE_REVIEW_ANALYSIS.md)）。

---

## 1. 范围与批次

| 批次 | 内容 | 理由 | 状态 |
| --- | --- | --- | --- |
| **第 1 波** | C-1、C-3、C-6（4.2 收敛小项）+ H-A1、H-A2（账户安全 P0） | 改动小、独立、无行为变化；H-A1/H-A2 与收敛项同文件（account）可一并做 | ✅ 已实现并提交（bf6a42a；见 §2.5 实施记录） |
| **第 2 波** | C-2（HttpFetcher）+ M-3/M-8N/M-9N（OOM 上限）；C-13（Hashing/SecureCompare）+ M-W1/M-W4（哈希路径） | 收敛与安全修复强绑定，需评审 | ✅ 已实现并提交（4a40356/525adaf；见实施记录） |
| **第 3 波** | C-4（JSON 策略）、C-10（安全工具）、C-9（异常体系）、C-8（单例）、C-5（Room 模板）等 | 架构演进，§4.4 排 P1/P2 | ✅ 部分完成（C-4/P2-3 64dd80f；P2-1 525adaf；P2-2 4a40356 + c37c259 POST 回归修复；**C-5/C-8/C-9 待 P2-5**；**C-10/P2-4 暂缓**） |
| **模块批次 · app-update** | H-W2（证书 fail-closed）、H-W3/L-4（HTTPS 强制 + 同源重定向）、M-3（大小上限）、M-7（路径穿越）、M-W4（哈希走 JniVerifier）、M-W6（兼容 fail-closed）、P-17W（缓存清理） | 更新链安全面一次收干净，全部局限 `app-update` 模块内（已依赖 `:apk-verify`） | ✅ 已实现并提交（2c58d5b；见 §2.6 实施记录） |
| **快速安全项 · wallet** | M-W7/B-29（钱包模型 `toString` 泄露私钥/助记词） | 最小改动、零行为变化、独立，堵真实泄露面 | ✅ 已实现并提交（0145e1d；见 §2.7 实施记录） |
| **剩余 P0 批次** | H-W1（桥回调伪造）、H-D1+H-D2（dapp-connect origin/批量）、H-DID2+H-DID3（did 缓存一致性） | 剩余资金安全/数据一致性 P0 | ✅ 已实现并提交（1dbf08a；见 §2.8 实施记录） |
| **did 批次** | H-DID1（盲签确认）、M-DID1/M-DID6（凭证验证语义）、M-DID2/M-DID3（陈旧 baseDoc/覆盖）；~~H-DID2 持久化~~（已放弃，避免动数据库） | did 安全/一致性面一次收干净，与已改的 DidCoreService 同模块 | ✅ 已实现并提交（d9b9382；见 §2.9 实施记录） |
| **nft 批次** | M-8N（SSRF 返回面）、M-9N/M-3（HTTP 大小上限 OOM）、M-13N（chainId 一致性）、M-17N（硬转换/误导白名单） | nft 是唯一未修复模块，含真实 OOM/DoS；均为行为级修复不动架构 | ✅ 已实现并提交（59737a7；见 §2.10 实施记录） |
| **剩余高危批次** | H-W4（自校验信任根循环）、H-DID4（私钥经 WebView String 传递） | 报告 §2.1 剩余 2 项高危；含架构级修复（需设计评审） | ✅ 已实现（小改项；架构项待评审；见 §2.11 实施记录） |
| **逐笔签名确认批次** | M-4/M-D4（EVM + SWTC 签名/转账无逐笔用户确认） | P1 资金安全项；需跨 SDK + App 同迭代（破坏性 API） | ✅ SDK 7b3d8b1 + a59e17f；ccdao **cecf940**；jdid **N/A**（非交易类） |

**不建议**第一波就整段做 §4.2 全部 24 项：C-2/C-4/C-9/C-8 是大改，需设计评审；C-2 还牵动 app-update 更新链与 nft SSRF，必须单独一个批次。

---

## 2. 第 1 波逐项计划

### C-1：`Path` 模型去重（`wallet.model.Path` → `core.model.Path`）

**问题**：`wallet/model/WalletModels.kt:8-15` 的 `Path(chain, account, change, index)` 与 `core/model/Path.kt` 字段与 `toString` 一致，core 额外多 `isRoot()`/`root()`（超集，去重安全）；`AccountOrchestrator.kt:313-327` 被迫写 `toCorePath()` / `toWalletPath()` 两个转换函数。

**改动**：
1. `wallet/build.gradle.kts` — 加 `implementation(project(":core"))`（当前 wallet 只依赖 `:webview-bridge`）。
2. 删除 `WalletModels.kt` 中的 `Path` 类，所有 `wallet.model.Path` 引用改 `core.model.Path`（`grep -rn "Path" wallet/src` 全量排查，含 `WalletModels.kt` 各 data class 的属性类型、`WalletSdk` 派生参数）。
3. `AccountOrchestrator.kt` — 删除 `toCorePath()`/`toWalletPath()`（:313-327），`importHdWallet`/`deriveSubAccount`/`importSubAccount` 直接用 `core.model.Path`。

**依赖/注意**：
- 与 **M-14A**（`Path.chain` 未持久化，Room 往返后根账户路径被改写）共享同一类型——本次只做类型统一，**不改持久化语义**；M-14A 作为独立跟进项（需实体加 `pathChain` 列），不在本波改。
- account 已 `api(project(":core"))`，无需动。

**验证**：`./gradlew :wallet:test :account:test :core:test`；`grep -rn "toCorePath\|toWalletPath"` 应无残留。

---

### C-3：hex 编码统一到 core `Hex`

**问题**：2 套手写 hex —— `did/.../ChecksumUtils.kt:36` `bytesToHex`（查表 StringBuilder）与 `apk-verify/.../ApkDigest.kt:36` `sha256Hex` 内的逐字节 `"%02x".format(it)`（低效）。

**改动**：
1. 新增 `core/src/main/java/com/jccdex/toolkits/core/encoding/Hex.kt`：
   ```kotlin
   fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)
   fun String.fromHex(): ByteArray = HexFormat.of().parseHex(this)
   ```
2. `ChecksumUtils` — `bytesToHex` 改为委托 `core.encoding.toHex`（或删除私有实现，调用点改用公共版）。
3. `ApkDigest.sha256Hex` — hex 化改用 `toHex()`，删除 `joinToString { "%02x".format(it) }`。

**验证**：`./gradlew :did:test :apk-verify:test :core:test`；`ChecksumUtilsTest`、`ApkDigest` 相关测试全绿。

---

### C-6：`ByteArray.wipe` / `CharArray.wipe` 上移 core 并推广

**问题**：安全敏感工具仅存在于 vault（`vault/util/Wipe.kt`），account/dapp-connect 等处理密钥的模块无法引用。

**改动**：
1. 新增 `core/src/main/java/com/jccdex/toolkits/core/security/Wipe.kt`（内容同 vault 版：`ByteArray.wipe() = fill(0)`、`CharArray.wipe() = fill('\u0000')`）。
2. 删除 `vault/util/Wipe.kt`；**全仓 grep `\.wipe()` 更新所有调用方的 import**（扩展函数跨包必须显式 import，包名从 `vault.util` 变 `core.security`）。
3. 推广到 account：`AccountOrchestrator` 中 `mnemonic?.fill(0)` 等改 `wipe()`（`grep -rn "fill(0)\|fill('\u0000')" account/src` 排查）。

**依赖注意**：vault 当前**零模块依赖**（`vault/build.gradle.kts` 无 `project(...)`）——Wipe 移入 core 后，vault 需新增 `implementation(project(":core"))`。core 是纯 JVM 叶子、零第三方依赖，依赖方向无环，可放心加。

**注意**：这是跨包 rename 性质，调用点多，务必先 `impact({target: "wipe", direction: "upstream"})` 看 blast radius，再用批量替换并逐个确认。

**验证**：`./gradlew :vault:test :core:test`；`grep -rn "vault.util.wipe"` 应无残留。

---

### H-A1：`importHdWallet` 查重提前到清除动作之前

**问题**（`AccountOrchestrator.kt:69-89`）：`if (clearExisting) { vault.clearAllData(...); store.clearAllAccounts() }` 在 `store.findRootAccountByAddress(...)`（:87）**之前**执行——清空账户表后查重恒为 null，**重复检查永不触发**，实际是静默清库重导、连错误都不报。

**改动**：
1. 把「查重」移到 `clearExisting` 块**之前**：先 `findRootAccountByAddress`，命中即返回 `AccountAlreadyExists`，**不执行任何清除**。
2. `clearExisting` 语义更新为「查重通过后才清除」，KDoc 显式警告该参数会销毁既有钱包数据。

**验证**：`AccountOrchestratorTest` 新增用例——`clearExisting=true` 重导同一助记词 → 返回 `AccountAlreadyExists` 且 vault/账户表**未被清空**。

---

### H-A2：`importSubAccount` 空私钥入库

**问题**（`AccountOrchestrator.kt:155-178,301`）：子账户导入无真实私钥，却用 `Keypair(privateKey = "", ...)` 经 `vault.importPrivateKey(derived.address, "".toByteArray())` 写入 vault → 该地址 `addressInKeys` 恒真，后续真实私钥导入被静默跳过，地址被**永久锁死**（资金可用性缺陷）。

**改动**（注意：`DerivedSubAccount` 无 `privateKey` 字段，空串是 `importSubAccount`（:167-170）内部硬编码的输入——**不能在入口 `require` 空私钥**，否则会拒绝所有子账户导入）：
1. **主修（行为保留）**：`persistVaultMaterial` else 分支（:300-302）对空 `keypair.privateKey` **跳过 vault 写入**——子账户只落 store 记录、不写 vault。既不污染 `addressInKeys`（后续真实私钥导入可解锁），也不打断导入流程。
2. **纵深防御**：`vault/VaultRepository.lockedImportPrivateKey` 在 `addressInKeys` 短路前对 `privateKey.isEmpty()` 抛异常（而非静默 `wipe(); return`），防止未来再次误写空密钥。
3. KDoc 说明：子账户签名本就依赖「从根助记词派生真实私钥」的设计落地（现状 vault 内为空密钥、签名不可用），本次只消除「永久锁死」，不引入新行为。

**验证**：`AccountOrchestratorTest` + `VaultRepositoryTest` 新增用例——空私钥子账户导入成功、store 有记录但 **vault 不落库**，`addressInKeys` 不含该地址（后续真实私钥导入可正常解锁）；`lockedImportPrivateKey` 对空密钥抛异常。

---

## 2.5 第 1 波实施记录（2026-08-25，已提交 bf6a42a）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| C-1 | 删除 `wallet.Path`，全仓改 `core.Path`；删除 `toCorePath()/toWalletPath()` | wallet 依赖用 **`api(:core)`**（非计划的 `implementation`）——wallet 公共 API 暴露 `core.Path`，必须 api 传播给下游 | `:wallet/:account/:core` 测试绿 |
| C-3 | 新增 `core.encoding.Hex`（`toHex`/`fromHex`）；ChecksumUtils/ApkDigest 委托 | **apk-verify 需新增 `implementation(:core)`**（计划未提；did 已有 `api(core)` 无需动）；实现用 `toHexString(HexFormat.Default)`（与计划的 `HexFormat.of().formatHex` 等价） | HexTest 绿；hex 输出逐字节一致，`toChecksumAddress` 不变 |
| C-6 | Wipe 移入 `core.security`；vault/VaultRepository import 更新；account `mnemonic?.fill(0)` → `wipe()` | vault 新增 `implementation(:core)`（计划已注明）；`mockkStatic` 路径同步改为 `core.security.WipeKt` | `:core/:vault` 强制重编译绿 |
| H-A1 | 查重移至 `clearExisting` 之前，命中即返回 `AccountAlreadyExists` 且不执行清除；KDoc 补充 `clearExisting` 销毁语义 | — | 新测试 `importHdWallet_clearExisting_doesNotClearWhenRootExists` 通过 |
| H-A2 | `persistVaultMaterial` else 分支对空 `keypair.privateKey` **跳过 vault 写入**；`lockedImportPrivateKey` 对空密钥 `require(isNotEmpty())` | 按修订版方案实施（`DerivedSubAccount` 无 privateKey，入口 `require` 不可行） | 新测试 `importSubAccount_doesNotPersistEmptyKeyToVault` + `importPrivateKey_rejectsEmptyKey` 通过 |

**实施过程中的发现**：`core/security/Wipe.kt` 初版把 `'\u0000'` 写成了字面 NUL 字节（git 判为二进制、编译存疑），已改为转义并强制重编译验证通过。

**提交前门禁**：`detect_changes()` = **CRITICAL**（AccountOrchestrator 高扇入枢纽，波及 importHdWallet/importSubAccount/deriveSubAccount/removeAccount 及 toChecksumAddress 相关流程）——为预期影响面，合并前需知晓。

**API 影响**：`ByteArray.wipe()/CharArray.wipe()` 从 `vault.util` 移至 `core.security`——正常集成（依赖 account 等 `api(:core)` 模块）无需额外改动；仅单独依赖 `:vault` 且直接使用 `wipe()` 的消费方需显式依赖 `:core`。另两处相关变化：`wallet.model.Path` 删除 → 消费方改用 `core.model.Path`（wallet 已 `api(:core)`，类型传递可见）；`importSubAccount` 不再写 vault → 子账户地址的 `getPrivateKey` 从返回空数组变为抛 `IllegalArgumentException("Private key is not exist")`。

---

## 2.6 app-update 批次实施记录（2026-08-25，已提交 2c58d5b）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-7 | 新增 `safeApkFileName`（白名单 `[A-Za-z0-9._-]+`）+ `canonicalPath` 防御；`downloadAndVerify` 拒绝非法文件名 | — | 新测试 `safeApkFileName` 2 例（接受合法/拒绝 `../`、`\`、空名） |
| H-W2 | 证书校验 fail-closed：`expectedCert` 非空时 `actualCert` 为空 → `Failed`；移除宽泛 catch（异常走外层 catch 返回 `Failed`） | **未实现计划第 3 条「manifest 加载失败→fail」**——属 H-W4（信任根本身缺失），且会误伤未内置 manifest 的合法升级流程 | 编译 + 行为推理 |
| H-W3/L-4 | 新增 `UpdateHttp.openHttps(url, connectTimeoutMs, readTimeoutMs)`：https 强制 + 非空 host + 手动同源重定向（≤3 跳）；超时在 `responseCode` 前设置（复核发现首版「先连接后设超时」是回归，已修——超时经参数传入、`openHttpsOnce` 内 connect 前应用）；AppUpdateChecker/Installer 两处入口改用 | — | 新测试 `openHttps` 拒绝 http/ftp/非法 URL（不触网路径） |
| M-3 | checksums 流式读取上限 1MB；APK 下载 `Content-Length` >200MB 拒绝、累计超限中断 `Failed` | — | 编译 + 行为推理 |
| M-W4 | `ApkDigest.sha256Hex(target)` → `JniVerifier.computeSha256(target)`（native 优先、Java 回退保持）；移除未用 `ApkDigest` import | — | 编译 + 行为推理 |
| M-W6 | `isSigningCompatibleWithInstalled` 返回 `Boolean?`（null=未知，调用方中止），移除 `runCatching{...}.getOrDefault(true)` fail-open | **API 变更**：`Boolean` → `Boolean?`（app-update 无仓内消费者，安全） | 编译 + 行为推理 |
| P-17W | 新增 `pruneCache(maxKeep=2)`，`downloadAndVerify` 成功时调用 | — | 编译 + 行为推理 |

**实施过程中**：app-update 从未被 ktlint 检查（不在 pre-commit 模块集），主源码/测试有预存格式违规——已用 `ktlintFormat` 规范化（纯格式、无逻辑变化），并清掉 `AppUpdateCheckerTest` 2 处预存空行违规。

**测试覆盖说明**：H-W2/M-3/M-W4/M-W6/P-17W 涉及 Android Context + 真实网络，app-update 现有测试依赖仅 junit + kotlin.test（无 Robolectric/mockk），未补行为级测试；仅补了不触网络的纯逻辑测试（M-7 白名单、H-W3/L-4 拒绝路径）。如需完整行为测试需为 app-update 增加 Robolectric/mockk 依赖（另议）。

---

## 2.7 wallet 安全快修实施记录（2026-08-25，已提交 0145e1d）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-W7/B-29 | `Keypair`/`Mnemonic`/`GenerateHDWalletResult`/`TraditionalDeriveResult` 覆写 `toString()`，掩码 `privateKey`/`value`/裸 `mnemonic`/`secret`/`sourcePrivateKey` 为 `***`；**只覆写 toString**，`equals/hashCode` 保持字段语义 | 计划第 3 条细化：`SubWallet` **未覆写**——其 `keypair` 字段经 `Keypair.toString` 自动掩码，默认 toString 已安全 | 新测试 `toString_masksSensitiveFields` + `toString_masking_doesNotAffectEqualityOrHashCode` |

**验证**：`:wallet:testDebugUnitTest` + `:wallet:ktlintCheck` 全绿；wallet 模块图编译通过；`detect_changes()` = LOW（仅 WalletModels.kt/Test，无执行流受影响）。

---

## 2.8 剩余 P0 批次实施记录（2026-08-25，已提交 1dbf08a）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| H-D2 | `SwtcMiddleware.MAX_BATCH_SIZE = 50`；`batchTransactions` 入口校验三项总条数超限抛 `IllegalArgumentException` | — | 新测试 `batchTransactions rejects oversized batch`（51 笔被拒） |
| H-DID2 | `pendingCreateDids` 改 `ConcurrentHashMap<String, Long>`（did→创建时间戳）；`handleMissingChainDocument` 5 分钟宽限期内保留本地文档不删；`clock` 可注入 | **持久化（计划第 2 条）未实施**——内存宽限期已解决 P0（重复 resolve 误删）；进程重启场景仍暴露，已并入 **did 批次**（见 §6） | 新测试 `keeps local on repeated misses within grace period` + `deletes local after grace period expires` |
| H-DID3 | `pendingDeleteUpdated` 检查提前到 `localDoc == null` 分支之前；删除确认期内链上旧文档不回填（返回 null 保持删除态） | — | 新测试 `keeps deleted state when pending delete timestamp matches chain` |
| H-D1 | `WebAppInterfaceWithWebView` 覆写 `getOrigin()` 从 `webView.url` 实时解析（回退预设） | 计划第 2 条「导航失效缓存」未单独做——实时 origin 使 CachingSecretProvider 缓存键随 origin 变化，等效缓解缓存窗；M-5 另议 | 新测试 `getOrigin_derivesFromWebViewUrl` + `getOrigin_fallsBackToPresetWhenNoWebUrl` |
| H-W1 | `IPromiseGateway.pageActive` 页面状态标志；`onPromiseResult` 非活动页拒绝 + 结果长度上限 1MB；`WebviewBridgeClient` onPageStarted 清除 / onPageFinished 桥页置位 | 计划第 1 条「双向 nonce」未单独做——`callbackMap.remove(id)` 已是一次性 id，页面状态校验为主防，nonce 增益有限 | 新测试 `onPromiseResult_rejectsWhenPageInactive` + `rejectsOversizedPayload` |

**实施中发现**：
1. **`DidCoreServiceTest` 原为纯 JVM**（无 Robolectric），`org.json.JSONObject` 是 Android stub → `extractUpdated`/`readProfileField` 恒返回 null → **did 缓存逻辑从未被真正测试**（现有测试按 stub 降级行为写）。已加 `@RunWith(RobolectricTestRunner)`，并修正 1 个按 stub 写的错误断言（`updates store when chain doc is newer` 原断言 local 保留，真实 JSONObject 下应更新到 chain）。
2. `WebviewBridgeEngineTest.callJsMethod_afterDestroy_recreatesWebViewAndResolves` 是**预存 flaky**（隔离通过、套件内间歇失败，与本次改动无关）。
3. dapp-connect 有 **18 个文件的预存 ktlint 违规**（模块不在 pre-commit ktlint 门禁内）——本次未处理，另排独立清理项。

**测试覆盖说明**：H-D1/H-D2/H-DID2/H-DID3/H-W1 均有行为级测试；`WebviewBridgeClient` 的 pageActive 生命周期（onPageStarted/onPageFinished）靠编译 + 推理（Robolectric 的 WebView 生命周期难稳定模拟）。

---

## 2.9 did 批次实施记录（2026-08-25，已提交 d9b9382）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-DID1 | `CredentialVerificationResult` 加 `unknown` 字段；`verifyCredential` 对 grantee check `fetchFailed=true` 返回 `unknown=true`（非 invalid） | — | 新测试 `marks unknown when grantee owner doc fetch fails` |
| M-DID6 | `Instant.parse(expirationDate)` 解析失败 → `verified=false`（fail-closed），不再跳过过期检查 | — | 新测试 `returns false for malformed expirationDate` |
| M-DID2 | `resolveBaseDoc` 链上 resolve 抛异常 → 返回 null 中止写操作（不再静默回退本地陈旧文档） | — | 新测试 `updateDidNickname aborts when chain resolve fails` |
| M-DID3 | `uploadInitialDidDoc` didStat 失败 → 拒绝发布；cid 非空（链上已存在）→ 拒绝覆盖 | 现有 2 个 uploadInitialDidDoc 测试的 didStat stub 从 `cid="cid"` 改为 `cid=null`（DID 不存在前提） | 新测试 `refuses when DID already exists` + `refuses when didStat fails` |
| H-DID1 | `signCredentialForDApp` 加 `onConfirm` 参数（默认 null→拒绝，fail-closed）；`WebAppInterface` 加 `setDidCredentialConfirm` 宿主钩子并接线 | issuer/subject 语义校验未做（需钱包 DID 上下文，委托给宿主确认回调）；**API 变更** | 新测试 `rejects without confirm` + `rejects when confirm returns false` |

**H-DID2 持久化：已放弃**（避免数据库 schema 变更），保持内存宽限期；进程重启场景为已知限制（见 §8 跟进项）。

---

## 2.10 nft 批次实施记录（2026-08-25，已提交 59737a7）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-17N | `fetchJson` 硬转换改 `as? HttpURLConnection ?: return null`；SsrfGuard 白名单移除误导性 `"ipfs"` | — | 现有 SsrfGuard 测试（ipfs 本就被 URL 构造拒绝）+ 编译 |
| M-3/M-9N | nft 模块新增 `readTextLimited`（5MB 上限）+ `MAX_HTTP_RESPONSE_CHARS`；fetchJson/fetchText/fetchMetadataImage/SwtcChainNftClient 改用 + contentLength 预检 | 落库前截断未做——readText 上限已使 fullContent ≤5MB，不再撑爆 DB | 新测试 `readTextLimited` ×2（超限返回 null / 限内返回内容） |
| M-13N | NftStore 公开入口统一 `normalizeChainIdHex`（`"1"` 与 `"0x1"` 归一化）；缺失/非法 chainId → null（不查 `0x0`）；`resolveEthrAvatar` 缺 chainId 返回 null | — | 新测试 `chainId_normalizedHexAndDecimalHitSameRecord` |
| M-8N | `resolveRemoteImageUrl` 每个返回 URL 过 `SsrfGuard.check`（`isSafeReturnableUrl`）；`SsrfGuard.enabled` 改 `internal` | 因 M-8N 引入 SSRF 检查，2 个既有 resolveEthrAvatar 测试（example.com 图片）需设 `SsrfGuard.enabled = false`（测试环境 DNS 不解析 example.com） | 新测试 `resolveRemoteImageUrl rejects internal url` + `returns safe external url` |

**实施中发现**：`resolveRemoteImageUrl` 是 suspend（测试需 `runTest`）；`SsrfGuard.enabled` 改 internal 后同模块测试仍可访问（测试在 nft 模块内）；`data:` URL 现被 `isSafeReturnableUrl` 拒绝（SsrfGuard.check 无法 URL 构造 data:）——与 M-8N 报告「data: 直返同理撑爆内存」的意图一致。

---

## 2.11 剩余高危批次实施记录（2026-08-25，已提交 cf13cdb）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| H-W4-1/2 | `verifyInstalledPackage` 定位 KDoc（防误装提示，非信任根）；`skipSignatureCheck: Boolean` → `SignatureCheckPolicy { ENFORCE, SKIP }` 枚举 | SKIP 的「release 禁止」由宿主用自身 `BuildConfig.DEBUG` 保证（库无 BuildConfig）；verifyInstalledPackage 无仓内调用方 | 编译 + apk-verify 测试 |
| H-DID4-1 | `DidSdk` 类级 KDoc 声明「私钥进入 WebView 进程」安全边界 | — | 编译 |
| H-DID4-2 | `WebviewBridgeClient` WebView 显式 `setWebContentsDebuggingEnabled(false)` | — | 编译 + webview-bridge 测试 |
| H-DID4-3 | 顶层 SHA-256 常量（**9 个 asset：4 个 html/glue + 5 个 crypto 库**，含 did-0.3.2.min.js）+ `verifyBridgeAssets` 加载时校验，不匹配记严重日志 | 复核发现初版只哈希 4 个 html/glue，**漏掉真正处理私钥的 5 个 min.js**（did-0.3.2.min.js 9.6MB 等）——已补齐覆盖全部 `<script>` 加载的库（同步 ~30-50ms，可接受）；**检测性（log-only），不阻断**——哈希在同一 APK，真防重打包需 native 哈希（H-DID4-4 架构项）；避免「asset 更新忘更哈希 → 桥不可用」的维护地雷 | 编译 + webview-bridge 测试 |

**架构项（未实施，需设计评审）**：H-W4-3（信任根外置：服务端 API + Play Integrity / native 硬编码证书）；H-DID4-4（Keystore 签名 / Kotlin API 改 CharArray）。—— **H-W4-3 已于 §2.12 多宿主收敛处置实施；H-DID4-4 放弃挂账（见 §8）**。

---

## 2.12 H-W4-3 收敛处置实施记录（2026-08-25，✅ 已提交 87d7f9c）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| H-W4-3 | 移除 `verifyInstalledPackage`（:104-129）+ `SignatureCheckPolicy` 枚举（:7-13）——消灭「被校验 APK 信任自身 manifest」的自引用信任根；`verifyApkFile` 补 TRUST MODEL KDoc（每宿主 manifest 锚 + `checksums` 带外锚 + 防误装不防重打包） | 计划一致；`ApkIntegrityVerifierTest` 有 **4 处预存 ktlint 违规**（类体首行空行，PR #16 `69f69c1` 引入）——用 `ktlintFormat` 纯格式化（-4 行，无逻辑变化） | `:apk-verify:testDebugUnitTest` + `:apk-verify:ktlintCheck` + `ktlintCheckAll` 全绿；`detect_changes` low（索引过期，手动 diff 确认仅 apk-verify 两文件） |

**API 影响**：`verifyInstalledPackage`、`SignatureCheckPolicy` 删除——仓内无调用方，但 **jdid-android 生产代码** `MainNavGraph.kt:73`（启动自校验）在用，需替代实现（详见 §11 宿主适配计划 A4）；`verifyApkFile` 行为不变（两宿主无需改调用点）。

---

## 3. app-update 更新链批次逐项计划（H-W2 / H-W3 + 关联项）

> 依赖前提（已核实）：`app-update` 已依赖 `:apk-verify`（JniVerifier/ApkSigningFingerprint/ReleaseChecksums 可直接用）；全部改动局限在 `app-update` 模块内，无跨模块新依赖。

### H-W2：证书校验 fail-closed（[AppUpdateApkInstaller.kt:92-104](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L92-L104)）

**问题**：三处 fail-open——`expectedCert` 加载失败→空串→跳过检查；`actualCert` 提取失败→空串→静默通过；`catch (_: Exception) { /* ignore */ }` 吞异常放行。

**改动**：
1. `expectedCert` 非空时 `actualCert` 为空 → `Failed`（不得跳过）。
2. 移除宽泛 catch，校验异常走外层 catch 返回 `Failed`。
3. manifest 加载失败（无信任锚）按失败处理，不放行。

**验证**：`archiveCertSha256` 返回 null/空 → `Failed` 且 `target` 被删除；证书不匹配 → `Failed`。

### H-W3 + L-4：HTTPS 强制 + 同源重定向（[AppUpdateChecker.kt:50-65](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateChecker.kt#L50-L65) + [AppUpdateApkInstaller.kt:52-58](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L52-L58)）

**问题**：`URL(url).openConnection()` 无 scheme 校验（L-4）；`instanceFollowRedirects = true` 跟随跨 scheme 重定向 https→http（H-W3）。

**改动**：
1. 两处入口统一加 https 强制 + host 校验（`protocol == "https"`；host 白名单或至少非空、非 IP 字面量）。
2. `instanceFollowRedirects = false`，3xx 手动解析 `Location`——必须 https 且同 host 才跟随（≤3 跳），否则 `Failed`。
3. 抽 `openHttps(url)` 帮助函数两处共用（未来 C-2 收敛 core 时再吸收）。

**验证**：`http://` 输入 → `Failed`；重定向到 http/异 host → `Failed`。

### M-3：HTTP 响应/下载大小上限（[AppUpdateChecker.kt:59](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateChecker.kt#L59) + [AppUpdateApkInstaller.kt:63-75](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L63-L75)）

**问题**：checksums `readText()` 无上限；APK 下载 `downloaded` 只用于进度、无总字节上限。

**改动**：checksums 流式读取计数 >1MB 返回 null；APK 下载 `contentLengthLong` >200MB 拒绝、`downloaded` 超限中断 `Failed`；常量集中定义（`MAX_METADATA_BYTES` / `MAX_APK_BYTES`）。

**验证**：mock 超大 body → 中断 `Failed`；`Content-Length` 超限 → 拒绝。

### M-7：下载文件名路径穿越（[AppUpdateApkInstaller.kt:47](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L47)）

**问题**：`File(updateCacheDir(context), "${apkNamePrefix}-v${remote.versionName}.apk")` —— `versionName`/`apkNamePrefix` 来自远端/调用方，含 `/`、`..` 可逃逸缓存目录。

**改动**：文件名白名单 `[A-Za-z0-9._-]+`（非法 `Failed`）；防御纵深 `canonicalPath.startsWith(cacheDir.canonicalPath)`。

**验证**：`versionName = "../evil"` → `Failed` 且无越界文件。

### M-W4：下载校验哈希统一走 JniVerifier（[AppUpdateApkInstaller.kt:86](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L86)）

**问题**：`ApkDigest.sha256Hex(target)`（纯 Java），而 `ApkIntegrityVerifier` 用 `JniVerifier.computeSha256`（native 优先）——下载这个最敏感环节绕开了 native 反篡改路径。

**改动**：`ApkDigest.sha256Hex(target)` → `JniVerifier.computeSha256(target)`（已 import、已依赖 `:apk-verify`；native 不可用时内部回退 ApkDigest，行为保持，但入口统一不再双路径）。

**验证**：哈希断言走 `JniVerifier.computeSha256`；现有安装链路测试保持绿。

### M-W6：`isSigningCompatibleWithInstalled` fail-closed（[AppUpdateApkInstaller.kt:117-121](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L117-L121)）

**问题**：`runCatching { ... ?: return true }.getOrDefault(true)`——任何读取/解析失败都返回「兼容」，无法确认兼容时反而放行升级。

**改动**：返回 `Boolean?`（null = 未知/失败，由调用方中止升级）或 sealed 结果；至少失败时返回 `false` + 显式日志，不得默认 true。

**验证**：`installedReleaseCertSha256`/`archiveCertSha256` 返回 null 时结果不为 true。

### P-17W：`updateCacheDir` 只增不清（[AppUpdateApkInstaller.kt:31-32](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L31-L32)）

**改动**：安装成功后删除缓存 APK，或按份数/时间清理旧文件（保留既有 `.tmp` 清理逻辑）。

### 可选 L-25 / B-27（[AppUpdateCheckThrottle.kt](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateCheckThrottle.kt)）

- **L-25**：`force: Boolean` 无默认值且语义含糊 → 改 `enum ForcePolicy { NEVER, FORCE }` 或加默认值 + KDoc。
- **B-27**：`downloadAndVerify` 5 个参数 → 抽 `DownloadRequest` data class。

**实施顺序**（低风险 → 高风险，`openHttps` 第 3 步抽、第 4 步复用）：M-7 → H-W2 → H-W3/L-4 → M-3 → M-W4 → M-W6 → P-17W → 可选 L-25/B-27。全部在 `app-update` 内，1-7 相互独立（同文件但改动点不重叠），可分批提交。

---

## 4. wallet 安全快修计划（M-W7 / B-29）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) M-W7 / B-29。目标：钱包模型默认 `toString()` 输出私钥/助记词明文，一旦进入日志/异常 message/崩溃上报即泄露——以最小改动掩码化，零行为变化。
> 依赖前提（已核实）：`wallet/src/main/.../model/WalletModels.kt` 5 个 data class 均未覆写 `toString()`；`app-update`/`dapp-connect` 未直接引用这些类（无仓内消费者风险）。

### M-W7：敏感 data class `toString` 泄露私钥/助记词

**问题**（[WalletModels.kt](wallet/src/main/java/com/jccdex/toolkits/wallet/model/WalletModels.kt)）：`Keypair(privateKey, publicKey)`、`Mnemonic(value, language)`、`SubWallet(..., keypair)`、`GenerateHDWalletResult(..., mnemonic, keypair, ...)`、`TraditionalDeriveResult(..., keypair, secret, sourcePrivateKey, ...)` 均为 data class，默认 `toString()` 原样输出私钥/助记词/secret。

**改动**：
1. `Keypair`：覆写 `toString()` → `Keypair(privateKey="***", publicKey=<实际>)`。
2. `Mnemonic`：覆写 `toString()` → `Mnemonic(value="***", language=<实际>)`。
3. `GenerateHDWalletResult`：`mnemonic` 是裸 String、`keypair` 字段，覆写 `toString()` 掩码 `mnemonic`；`SubWallet`/`TraditionalDeriveResult` 经字段类型的掩码 `toString()` 自动生效，但 `TraditionalDeriveResult.secret`/`sourcePrivateKey` 为裸 String 需各自掩码。
4. **只覆写 `toString()`**，不动 `equals/hashCode`（data class 的 `equals/hashCode` 基于字段，覆写 toString 不影响它们；掩码仅作用在日志路径）。

**注意**：
- 不能移除默认 `toString()`——data class 删除默认 toString 不破坏 equals/hashCode（它们独立），但直接用掩码覆写更简洁。
- 掩码策略：敏感字段输出 `"***"`，非敏感字段（address/chain/language/publicKey/path）保留实际值，便于调试。

**验证**：`WalletModelsTest` 新增用例——`Keypair("priv","pub").toString()` 不含 `"priv"`；`GenerateHDWalletResult(...).toString()` 不含助记词；`TraditionalDeriveResult(...).toString()` 不含 `secret`；`equals/hashCode` 行为不变（对同样构造的对象仍相等）。

**实施顺序**：单文件、无依赖，一次完成。

---

## 5. 剩余 P0 批次逐项计划（H-W1 / H-D1+H-D2 / H-DID2+H-DID3）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §7 P0 剩余项。目标：堵住桥接层伪造、DApp 面信任边界与批量耗尽、did 本地缓存一致性三类资金安全/数据完整性问题。
> 依赖前提（已核实）：行号与行为已对照当前源码；`WebAppInterfaceWithWebView` 已持有 WebView 引用（H-D1 修复的基础）；did 的 `pendingDeleteUpdated`/`pendingCreateDids` 均为内存标志。

### H-W1：桥接回调可被页面 JS 伪造签名/地址（webview-bridge + dapp-connect）

**问题**（[JsPromiseGateway.kt](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/JsPromiseGateway.kt) `onPromiseResult` → `callbackMap.remove(id)?.invoke(resultJson)`；[WebviewBridgeClient.kt](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt) :211-232 `UUID` 对页面可见）：页面内任意脚本可抢先调用 `onPromiseResult(uuid, 伪造结果)`——`remove` 原子、先到先得，原生直接 `cont.resume` 信任，等价于伪造签名/地址。

**修复**：
1. **主防**：`onPromiseResult` 内校验 WebView 当前 URL 仍为预期桥页（`file:///android_asset/...` 或维护页面状态标志），不符即丢弃。
2. **纵深**：双向 nonce——native 生成 `id`+`nonce` 传入 JS，回调必须携带 `nonce` 且仅接受一次（`remove` 语义保留）。
3. **纵深**：native 对回调结果做结构/长度白名单校验（防超长/畸形 payload）。

**关联**：M-6（`onBridgeReady` 同源校验）、L-24（callbackMap 经 object 公开暴露）。

**验证**：新测试——非桥页 URL 下 `onPromiseResult` 被拒；伪造 `id`/`nonce` 被拒；正常回调一次生效。

---

### H-D1：postMessage 信任边界——origin 只校验宿主预设值（dapp-connect）

**问题**（[WebAppInterface.kt](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/WebAppInterface.kt) :47-72 `dappOrigin` 预设、`getOrigin()`；:98-115 `postMessage` 仅对预设值做 blank/isSafeUrl 校验）：origin 依赖宿主在导航后调用 `setOrigin`，未更新即形成信任窗口；不校验真实调用 frame。

**修复**：
1. `WebAppInterfaceWithWebView` **覆写 `getOrigin()`**：每次从 `webView.url` 实时解析 origin（替换预设值），`postMessage` 用实时值校验。
2. 页面导航（`onPageStarted`/`onPageFinished`）时强制刷新 origin 并失效 `CachingSecretProvider` 缓存（配合 M-5 缓存窗）。
3. 实时 origin 与白名单不符即拒绝（复用 `isSafeUrl` + host 校验）。

**关联**：B-46（`dappOrigin` 非 `@Volatile`——若保留预设路径需加）、M-D5/M-D6（链切换/响应队列，可在同批次处理或后续）。

**验证**：新测试——导航后旧 origin 的 postMessage 被拒；实时 URL origin 与预设不符被拒。

---

### H-D2：`swtc_batchTransactions` 无批量上限（dapp-connect）

**问题**（[SwtcBatchTransactions.kt](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/SwtcBatchTransactions.kt) :41-64 `parseTransfers/parseCreateOrders/parseCancelOrders` 直接 `(0 until arr.length()).map`；[SwtcMiddleware.kt](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/SwtcMiddleware.kt) :297-360 `batchTransactions` 逐笔 `delay(200)` 签名+广播）：攻击者可传 10 万笔，`mode="send"` 下逐笔广播持续数小时，耗尽账户余额。

**修复**：
1. **批量上限**（如 50 笔）：`batchTransactions` 入口校验三项总条数，超限抛 `IllegalArgumentException`。
2. **金额/总数上限**：配合 M-D8 补充每笔金额与总计校验。
3. **逐笔确认**：批量请求走统一确认回调（配合 M-4/M-D4，可作后续项）。

**验证**：新测试——超限批量被拒；边界值（50）通过；非法金额被拒。

---

### H-DID2：新建 DID 的保护标志一次性失效（did）

**问题**（[DidCoreService.kt](did/src/main/java/com/jccdex/toolkits/did/service/DidCoreService.kt) :17 `pendingCreateDids` 纯内存、:77-85 `handleMissingChainDocument` 首次 miss 即 remove（:82），二次 resolve 走 `store.delete`（:85））：创建→publish→链上传播（IPFS 钉扎/索引延迟可达分钟级）期间触发两次 resolve → 本地刚创建的文档被删；进程重启标志即失效。

**修复**：
1. `pendingCreateDids` 改 `Map<String, Long>`（did→创建时间戳）；miss 时若在**宽限期**（如 5 分钟）内，保留本地文档、不删除。
2. pending 状态**持久化**（Room/DataStore）——进程重启后宽限期内仍受保护。
3. 超期后正常删除（`handleMissingChainDocument` 的删除逻辑保留）。

**验证**：新测试——宽限期内二次 resolve 不删文档；超期后删除；模拟重启后宽限期内仍受保护。

---

### H-DID3：DID 删除后链上旧文档复活（did）

**问题**（[DidCoreService.kt](did/src/main/java/com/jccdex/toolkits/did/service/DidCoreService.kt) :36-44——`localDoc == null` 时 :37-38 先 `store.upsert(chainDoc)`，`pendingDeleteUpdated` 检查 :44 只在 `localDoc != null` 分支；[DidSdk.kt](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt) :586 `publishDidDelete` 经 `core.deleteDidDocument`（DidCoreService :91-97）写入 pending）：删除后 localDoc 为 null，链上仍返回旧文档（删除未传播）→ upsert 把已删文档写回复活；`pendingDeleteUpdated` 分支无法触达。

**修复**：把 `pendingDeleteUpdated[did]` 检查**提前到 `localDoc == null` 分支之前**——若链上 `updated` 等于已删除时间戳（待确认期内），拒绝回填并返回（保持删除状态），随后移除 pending 条目。

**验证**：新测试——删除后 resolve 返回 `updated == deleted 时间戳` 的旧文档 → 不复活、本地仍为已删。

---

**实施顺序**（按独立性/复杂度）：H-D2 → H-DID2+H-DID3（同文件一起做）→ H-D1 → H-W1（跨模块、复杂度最高，最后）。
**跨项关联**：H-D1/H-W1 都与「页面来源校验」同源，可共享一个 origin 校验工具；H-D2 与 M-D8 金额校验绑定；H-DID2/H-DID3 都在 `DidCoreService` 单个文件内。

---

## 6. did 批次逐项计划（H-DID1 / M-DID1 / M-DID2 / M-DID3 / M-DID6）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) 剩余 P1 + §7 跟进项 #1。目标：一次收干净 did 模块的安全/一致性面——DApp 盲签、凭证验证语义、陈旧 baseDoc 发布、uploadInitialDidDoc 覆盖、pending 持久化。
> 依赖前提（已核实）：全部在 did 模块；H-DID2 的 `pendingCreateDids` 现为内存 `Map<String, Long>`（`clock` 已可注入）；行号对照当前源码。

### H-DID2 pending 状态持久化（原跟进项 #1，进程重启场景）

**问题**：`pendingCreateDids` 纯内存——进程重启后消失；传播窗口内重启 → 首次 miss 即删除本地文档（H-DID2 只修了一半）。

**决定（2026-08-25）：已放弃，不实施。** 原因：持久化需要改动数据库 schema（加列/迁移），用户明确「不动数据库」。保持内存宽限期方案（已在 1dbf08a 提交）——它覆盖了 P0 主场景（传播窗口内重复 resolve 误删）；**进程重启场景为已知限制**（挂账，见 §8 跟进项）。曾尝试「复用现有 did_documents 表加 `pendingCreateAt` 列 + ALTER TABLE 迁移」，后按用户要求完整回滚，schema 未动。

### H-DID1：`signCredentialForDApp` 强制确认回调 + issuer 校验

**问题**（[DidSdk.kt:280-299](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L280-L299)）：仅 3 项结构校验（@context/type、credentialSubject、issuer/issuerObject）后 `bridge.call("signCredential")` 盲签；KDoc 声明「宿主必须自行确认」纯靠自觉。恶意 DApp 可诱导签署「将用户 NFT 使用权授予攻击者」的授权凭证。

**改动**：
1. 增加**强制确认回调**参数（`onConfirm: (payload) -> Boolean`），不传或返回 false 即拒绝签名（**API 变更**，宿主需适配）。
2. ~~校验 `issuer` 等于钱包 DID；校验 subject 归属与 usageRights 语义~~ —— **未实施**（需钱包 DID 上下文，SDK 无法独立校验，委托给宿主确认回调）。见 §2.9 记录。

**验证**：新测试——无确认回调 → 拒绝；确认返回 false → 拒绝。（issuer/subject 校验由宿主在确认回调内完成。）

### M-DID2：写操作基于陈旧 baseDoc 发布（last-writer-wins 回滚）

**问题**（[DidSdk.kt:1091-1110](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L1091-L1110)）：`resolveBaseDoc` 链上 resolve 失败被 `runCatching` 静默吞掉后回退本地缓存文档；以旧文档为基底发布覆盖其他设备刚写入链上的更新。

**改动**：链上解析失败 → **中止写操作**并报「无法获取最新文档」，而非静默回退本地。

**验证**：新测试——链上 resolve 抛异常 → 写操作返回失败，不发布。

### M-DID3：`uploadInitialDidDoc` 无「链上已存在」保护直接覆盖

**问题**（[DidSdk.kt:333-441](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L333-L441)）：方法名「初始文档」却无「仅当链上不存在时」守卫；`didStat` 失败 → `previousCid` 为空 → 用全新文档 `publishDid` 覆盖已存在的 verificationMethods/credentials/服务端点，且断开 previousCid 链。

**改动**：publish 前确认链上不存在；`previousCid` 获取失败时拒绝发布。

**验证**：新测试——链上已存在 DID 时 `uploadInitialDidDoc` 拒绝；didStat 失败拒绝发布。

### M-DID1：凭证吊销检查把网络失败判为「已撤销」

**问题**（[DidSdk.kt:879-969](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L879-L969)）：owner 文档获取失败返回 `isUpdate=true, fetchFailed=true`，但 `verifyCredential` 只读 `isUpdate`、忽略 `fetchFailed`——一次瞬时网络故障就把有效授权凭证判为已撤销。

**改动**：`verifyCredential` 对 `fetchFailed=true` 返回「状态未知」而非 invalid。

**验证**：新测试——`fetchFailed=true` 时验证结果为「未知」而非 invalid。

### M-DID6：过期日期解析失败被当作「未过期」

**问题**（[DidSdk.kt:886-892](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L886-L892)）：`runCatching { Instant.parse(expirationDate) }.getOrNull()` 解析失败返回 null → 跳过过期检查。

**改动**：解析失败按「无法验证」处理（verified=false 或单独错误码）并记日志。

**验证**：新测试——畸形 `expirationDate` → 验证不通过。

---

**实施顺序**（独立性/风险）：M-DID1/M-DID6（纯验证逻辑）→ M-DID2/M-DID3（写路径）→ H-DID1（API 变更，宿主适配）。H-DID2 持久化已放弃（不动数据库）。

**API 影响**：H-DID1 会给 `signCredentialForDApp` 增加确认回调参数（破坏性变更，宿主需适配）；`WebAppInterface` 新增 `setDidCredentialConfirm` 宿主钩子。

---

## 7. nft 批次逐项计划（M-8N / M-9N / M-3 / M-13N / M-17N）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md)。目标：一次收干净 nft 的 HTTP 大小上限（OOM/DoS）、SSRF 返回面、chainId 一致性、硬转换/误导白名单。nft 是唯一未做修复的模块。
> 依赖前提（已核实）：`SsrfGuard` 已存在（fetchJson/fetchText/fetchMetadataImage 内部用，但 resolveRemoteImageUrl 的对外返回 URL 不经它）；行号对照当前源码。

### M-8N：SSRF 防护缺口——resolveRemoteImageUrl 直接返回 URL 不经 SsrfGuard

**问题**（[NftRemoteAssetResolver.kt:112-151](nft/src/main/java/com/jccdex/toolkits/nft/remote/NftRemoteAssetResolver.kt#L112-L151)）：`resolveRemoteImageUrl` 对可直接加载的 `imageUrl`/`metadataUri` 直接 return（:127/:146），返回的 URL 由宿主图片加载器抓取（无 SSRF 防护）——恶意 NFT 的 image 填云元数据/内网 URL 即构成 SSRF。`SsrfGuard.enabled`（:155）是公开 `@Volatile var`。

**改动**：
1. `resolveRemoteImageUrl` 每个「对外返回的 URL」在 return 前过 `SsrfGuard.check`，不通过则返回 null。
2. `SsrfGuard.enabled` 改 `internal`（禁止运行时关闭；测试用依赖注入替代）。

**验证**：新测试——image 为 `http://169.254.169.254/...` / `http://10.0.0.5/x` → 返回 null；合法 https → 正常返回。

### M-9N + M-3：HTTP 响应/元数据无大小上限（OOM / 磁盘 DoS）

**问题**：`readText()` 无上限——[NftStore.fetchJson](nft/src/main/java/com/jccdex/toolkits/nft/storage/room/NftStore.kt#L497)/[fetchText](nft/src/main/java/com/jccdex/toolkits/nft/storage/room/NftStore.kt#L516)、[NftRemoteAssetResolver.fetchMetadataImage](nft/src/main/java/com/jccdex/toolkits/nft/remote/NftRemoteAssetResolver.kt#L182)、[SwtcChainNftClient](nft/src/main/java/com/jccdex/toolkits/nft/remote/SwtcChainNftClient.kt#L82)；NftStore:283 整篇元数据落库。

**改动**：统一「带大小上限的流式读取」——`readText()` 改为流式计数，超过阈值（如 5MB）中断返回 null/Failed；NftStore 落库前截断（仅保留解析所需字段）。

**验证**：新测试——mock 超大 body → 返回 null/中断；超限不落库。

### M-13N：chainId 格式不统一 + 缺失默认 0

**问题**（[NftStore.kt:142,245](nft/src/main/java/com/jccdex/toolkits/nft/storage/room/NftStore.kt#L142) 内部 `"0x${chainId.toString(16)}"` 键 vs :38-52,107-112 公开 API 透传原始 chainId；:219 `toLongOrNull() ?: 0L`）：同一逻辑链 ID 两套格式并存 → 查询 miss/重复写入；VC 缺 chainId 默认 0 → 查空。

**改动**：`NftStore` 入口统一归一化 chainId（hex 小写）；缺失 chainId 返回 null/空而非 0 兜底。

**验证**：新测试——`"1"` 与 `"0x1"` 查同一记录；缺 chainId → 不查 `0x0`。

### M-17N：fetchJson 硬转换 + SSRF 白名单误导性 ipfs

**问题**：[NftStore.fetchJson:489](nft/src/main/java/com/jccdex/toolkits/nft/storage/room/NftStore.kt#L489) `(URL(url).openConnection() as HttpURLConnection)`（非 http/https 抛 ClassCastException，应为 `as?` 返回 null）；SsrfGuard 白名单（[NftRemoteAssetResolver.kt:160](nft/src/main/java/com/jccdex/toolkits/nft/remote/NftRemoteAssetResolver.kt#L160)）含 `"ipfs"`——ipfs 无法 URL 构造，属死代码误导。

**改动**：fetchJson 改 `as? HttpURLConnection ?: return null`；SsrfGuard 白名单只留 `http/https`。

**验证**：新测试——非 http(s) URL → fetchJson 返回 null 不抛异常。

**实施顺序**（最小→大）：M-17N → M-3/M-9N（大小上限）→ M-13N（chainId）→ M-8N（SSRF 返回面）。

---

## 8. 剩余高危批次逐项计划（H-W4 / H-DID4）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §2.1 剩余 2 项高危。目标：处理安全面最高的两项——更新链自校验信任根、私钥经 WebView 的架构性暴露。其中含**架构级修复（需设计评审）**，本批先落地可独立完成的安全加固。
> 依赖前提（已核实）：行号对照当前源码；`verifyInstalledPackage` 读的是被校验 APK 自身 assets 的 manifest；DidSdk 有 8 处 `put("privateKey")`；WebviewBridgeClient 未显式关闭 WebView 调试。

### H-W4：自校验信任根循环（apk-verify）

**问题**（[ApkIntegrityVerifier.kt:91-116](apk-verify/src/main/java/com/jccdex/toolkits/apkverify/ApkIntegrityVerifier.kt#L91-L116)）：`verifyInstalledPackage` 读取的 `official_release_manifest.json` 位于**被校验 APK 自身 assets**——重打包者改 manifest 的 `signingCertSha256` 即可恒过；`JniVerifier` native 库也在同一 APK 内（自验证弱点）；从不校验已装 APK 哈希（永远返回 `PassedSignatureOnly`）；`skipSignatureCheck=true` 误传即完全失效。

**改动**：
1. **定位修正**：`verifyInstalledPackage` KDoc 明确「防误装提示，非防篡改信任根」——manifest 来自自身 APK，不能作为真实信任锚。
2. **skipSignatureCheck 显式化**：改 `SignatureCheckPolicy { ENFORCE, SKIP }`（或 `@VisibleForTesting`），release 构建禁止 `SKIP`（仅 `BuildConfig.DEBUG` 允许测试跳过）。
3. **信任根外置（架构项，需设计评审）——详见下方「架构项设计评审：H-W4-3」。**

**验证**：新测试——`SignatureCheckPolicy.SKIP` 在 release 语义下被拒；manifest 被篡改（模拟改 `signingCertSha256`）→ 校验失败；定位修正后 KDoc 声明。

### H-DID4：私钥经 WebView JS 桥以不可擦除 String 传递（did + webview-bridge）

**问题**（[DidSdk.kt](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt) 8 处 `put("privateKey", ...)`；[WebviewBridgeClient.kt:211-235](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt#L211-L235) 经 JS 桥把私钥传给页面 JS）：私钥同时存在于 Kotlin String（不可 wipe）、中间 JSON、JS 引擎字符串堆三处。WebView 内核被利用或调试构建误开调试 → 私钥可被提取。

**改动**：
1. **安全边界文档化**：`DidSdk` 所有签名/发布路径 KDoc 明确「私钥进入 WebView 进程」边界与风险。
2. **显式关闭 WebView 调试**：`WebviewBridgeClient` WebView 设置显式 `WebView.setWebContentsDebuggingEnabled(false)`（或提供默认关闭的开关）。
3. **asset JS 完整性自检**：对 `did-bridge.js`/`wallet-bridge.html` 等 asset 内置 SHA-256，加载前校验（防 asset 被篡改注入恶意脚本提取密钥）。
4. **减少 String 传递（架构项，需设计评审）——详见下方「架构项设计评审：H-DID4-4」。**

**验证**：新测试——WebView 调试默认关闭（config 断言）；asset 哈希校验——篡改 asset → 拒绝加载；KDoc 边界声明。

---

**实施顺序**：H-W4-1/2（小改）→ H-DID4-1/2/3（小改）→ 架构项（H-W4-3 / H-DID4-4，需设计评审，可后续批次）。

---

### 架构项设计评审：H-W4-3 信任根外置（多宿主定案）

**目标**：消除「被校验 APK 自信任自身 manifest」的自引用信任根。

**现实约束（2026-08-25 多宿主调研后定案）**：
- **无额外服务**——方案 A（服务端 API + Play Integrity）不可行。
- **多宿主复用**（已核实两个宿主 app：ccdao-connector-android、jdid-android）：宿主签名证书各异，SDK 无法预知 → **方案 B（native 硬编码证书）不可行**——一个 `integrity.so` 只能编一个证书，会让其他宿主校验恒失败。
- **manifest 锚是每宿主自建、签名进宿主 APK**：`official_release_manifest.json` 由各宿主自己的 `scripts/generate-release-checksums.sh` 生成（各自 `signingCertSha256` + release 哈希），非 SDK 内置——锚天然按宿主隔离。
- **参数化 expected 证书（调用者传参）无实际安全增益**：值在宿主 dex 里，重打包者同样可改，与 manifest 锚等价；被重打包的宿主可伪造 UI 显示「通过」，任何 SDK 内锚都拦不住。

**收敛处置（确定方向）**：
1. **`verifyInstalledPackage`（自引用、仓内无调用方；jdid-android 生产 `MainNavGraph.kt:73` + 测试在用）→ 移除**——消灭「被校验 APK 信任自身 manifest」的自引用 API，这是本库内唯一真正可做的「信任根外置」；jdid 的启动自校验改为宿主本地实现（见 §11 宿主适配计划 A4）。
2. **`verifyApkFile`（真实生产面，两个宿主 settings 页在用）→ 保留每宿主 manifest 作锚**，KDoc 写清信任模型：保护「合法宿主下防误装/防错 APK」，不防「宿主被重打包」。
3. 比较继续走 native 常量时间（`JniVerifier.hashEquals`）；`ReleaseChecksums` 单独文件导入（`verifyApkFile` 的 checksums 参数）保留为带外锚。

**实施范围**：本仓改动（`verifyInstalledPackage` 移除 + `verifyApkFile` KDoc + 测试调整）已完成；**宿主侧有迁移成本**——jdid 生产 `MainNavGraph` 的启动自校验需替代实现（§11 A4）。

**验证**：`verifyInstalledPackage` 移除后相关测试删除/调整；`verifyApkFile` 行为不变（两宿主无需改调用点）。

**H-W4-3 执行计划（多宿主收敛处置，2026-08-25）**：
1. **移除 `verifyInstalledPackage`**（[ApkIntegrityVerifier.kt:104-129](apk-verify/src/main/java/com/jccdex/toolkits/apkverify/ApkIntegrityVerifier.kt#L104-L129)）：删除函数 + 其「防误装提示」KDoc（:92-103）；**`SignatureCheckPolicy` 枚举（:7-13）随之移除**——verifyInstalledPackage 独占参数，无其他引用（cf13cdb 添加的 H-W4-2 中间形态被本定案覆盖）；`ApkVerificationResult.PassedSignatureOnly` 保留（`verifyApkContents` 仍用，:244）。
2. **`verifyApkFile` 补 KDoc 信任模型**（:134-138）：锚 = 每宿主 `official_release_manifest.json`（宿主自建、随宿主 APK 签名分发）；保护「合法宿主下防误装/防错 APK」，不防「宿主被重打包」；`checksums` 参数为带外锚（用户单独导入的 `ReleaseChecksums`）。
3. **测试**：已核实仓内 `ApkIntegrityVerifierTest` 无 `verifyInstalledPackage`/`SignatureCheckPolicy` 引用 → 无删除项；编译通过即覆盖移除。
4. **验证**：`./gradlew :apk-verify:testDebugUnitTest` + `:apk-verify:ktlintCheck` + `ktlintCheckAll`；`detect_changes({scope:"compare", base_ref:"main"})` 确认仅影响 apk-verify。

**宿主迁移提示（跨仓，非本仓改动）**：jdid-android 的 **生产代码** `MainNavGraph.kt:73`（启动自校验：`verifyInstalledPackage(context, skipSignatureCheck = BuildConfig.DEBUG)`）与测试 `ApkIntegrityVerifierTest.kt:26-29` 均调用 `verifyInstalledPackage`——升级 SDK 后生产功能需替代实现（§11 A4），详见 §11 宿主适配计划。

---

### 架构项设计评审：H-DID4-4 私钥签名移出 WebView

**目标**：让私钥**不进入 WebView JS 堆**——签名在可信环境（Keystore/安全元件/native）完成，JS 只处理结果。

**约束确认：两个方案均无外部服务依赖**——方案 A 用设备自带 Keystore（on-device），方案 B 纯改 API 类型，均可行。

**方案 A（根本解）：宿主 Keystore 签名**
- 架构：`DidSdk` 的签名/发布路径（signCredentialForDApp、uploadInitialDidDoc、updateDidNickname、updateDidAvatar、publishDid 等 8 处 `put("privateKey")`）改为由宿主注入的 `DidSigner` 完成——私钥存 Keystore，签名在 native/Keystore 执行；JS 桥只做 didResolve/didStat/文档构造等无密钥操作。
- **关键工作**：
  1. 新增 `DidSigner` 抽象（host 实现：Keystore 生成/导入 DID 密钥、BIP32 派生、ECDSA/ed25519/Keccak 签名）。
  2. 重实现 JS 侧 did-0.3.2 的签名逻辑（VC/VP 签名格式、JWT、proof 构造）到 native/Kotlin。
  3. `DidSdk` 公开 API 移除 `privateKey: String`，改传 did 标识（host 从 Keystore 取钥）。
- **决策点**：
  1. **兼容性**：native 签名产物必须与现有 JS 签名格式字节级兼容（影响链上验签）。
  2. **Keystore 密钥类型**：Android Keystore 对 ed25519/secp256k1 的支持因 API level/厂商而异。
  3. **迁移路径**：存量私钥（现以 String/助记词存 vault）如何导入 Keystore。

**方案 B（过渡）：Kotlin API 改 CharArray**
- 架构：`DidSdk` 公开方法签名 `privateKey: String` → `CharArray`（或 `ByteArray`），签名前置零；内部仍会转 String 过桥，但减少 Kotlin 堆中不可擦除的 String 副本。
- **局限**：私钥**仍进入 WebView JS**（核心暴露未消除），仅降低 Kotlin 侧滞留。

**决定（2026-08-25，修订）：方案 B 放弃，H-DID4-4 整体挂账为已知设计限制。** 原因：实施前清点发现 `DidSdk` 有 **12 个公开方法**（+5 内部 helper：`publishDid`/`generateAvatarVc`/`generateNftVc`/`buildGenerate*VcParams`）的 `privateKey: String` 需改 `CharArray`——广泛破坏性 API 变更；而收益仅消除宿主侧 String（SDK 内部仍 `put("privateKey")` 转 String 过桥，私钥仍进 JS 堆），性价比低。保留 H-DID4-1/2/3 加固兜底（边界文档 / 关调试 / asset 哈希）；方案 A（Keystore 根本解）为长期规划项，需专门评审。

**验证**：方案 A——Keystore 签名结果与现有 JS 签名逐字节一致（链上验签通过）；方案 B——CharArray 置零断言。

---

## 9. 逐笔签名确认批次计划（M-4 / M-D4）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) M-4 / M-D4。目标：为 EVM + SWTC 链的签名/转账类 RPC 增加逐笔用户确认机制，防止 DApp 在用户授权连接后无感发起转账（资金安全 P1）。
> 范围：需跨 **SDK (kotlin-toolkits)** + **App** 同迭代，含破坏性 API 变更。**交易类宿主（ccdao）必须**实现确认 UI；**非交易类宿主（jdid）不适用**（见 §26 宿主画像、CODE_REVIEW_ANALYSIS §11）。
> 原则：与现有 `RequestAccountsCallback` 同构，复用宿主确认 UI。

### 9.1 问题现状

**当前状态**：
- `requestAccounts`（连接授权）有 `RequestAccountsCallback` 做用户确认
- `sendTransaction`/`signTransaction`/`signMessage`/`personalSign`/`signTypedData`/`decrypt` 等**直接执行**，无逐笔确认钩子
- `require(origin.isNotBlank())` 只保证 origin 存在，不保证用户知情

**风险**：恶意 DApp 在用户授权连接后，可无感发起转账（资金损失）。

**影响链**：
- EVM 链：`eth_sendTransaction`/`eth_signTransaction`/`personal_sign`/`eth_signTypedData_v4`/`eth_decrypt`
- SWTC 链：`swtc_sendTransaction`/`swtc_signTransaction`

### 9.2 修复方案（SDK + App 协同）

#### SDK 改动（kotlin-toolkits）

**新增接口**（与 `RequestAccountsCallback` 同构）：

```kotlin
// dapp-connect/.../middleware/interfaces.kt
interface TransactionConfirmCallback {
    suspend fun onConfirm(request: TransactionRequest): Boolean
}

sealed class TransactionRequest {
    data class SendTransaction(
        val chain: ChainType,
        val origin: String,
        val to: String?,
        val value: String?,
        val data: String?,
        val gas: String?,
        val gasPrice: String?,
        val nonce: String?,
        val txParams: JSONObject  // 完整参数
    ) : TransactionRequest()
    
    data class SignMessage(
        val chain: ChainType,
        val origin: String,
        val message: String,
        val type: SignType  // PERSONAL_SIGN / SIGN_TYPED_DATA
    ) : TransactionRequest()
    
    // SWTC 批量交易单独类型（含总条数/总额校验）
    data class SwtcBatchTransaction(
        val origin: String,
        val totalCount: Int,
        val totalAmount: String?,
        val transfers: List<JSONObject>
    ) : TransactionRequest()
}

enum class SignType { PERSONAL_SIGN, SIGN_TYPED_DATA_V4 }
```

**中间件改造**：

| 中间件 | 方法 | 改动 |
|--------|------|------|
| `EthMiddleware` | `sendTransaction` | 调 `confirmCallback?.onConfirm(SendTransaction(...))`，false → 拒绝 |
| `EthMiddleware` | `signTransaction` | 同上 |
| `EthMiddleware` | `personalSign` | 调 `confirmCallback?.onConfirm(SignMessage(...))` |
| `EthMiddleware` | `signTypedData` | 同上 |
| `SwtcMiddleware` | `sendTransaction` | 调 `confirmCallback?.onConfirm(SendTransaction(...))` |
| `SwtcMiddleware` | `signTransaction` | 同上 |
| `SwtcMiddleware` | `batchTransactions` | 调 `confirmCallback?.onConfirm(SwtcBatchTransaction(...))`（含总量校验） |

**注入点**：
- `WebAppInterface` 或 `DAppConnectSdk` 持有 `TransactionConfirmCallback?`
- 新增 `setTransactionConfirmCallback(callback)` 方法
- 未设置时行为：**拒绝执行**（fail-closed，安全优先）

#### App 改动（ccdao / jdid）

| App | 改动点 | 说明 |
|-----|--------|------|
| ccdao | 实现 `TransactionConfirmCallback` | 弹确认对话框，展示 origin/to/value/gas 等，用户点确认返回 true |
| ccdao | `DappConnectViewModel` 注入回调 | `setTransactionConfirmCallback(viewModel)` |
| jdid | 同上 | 同构实现 |

**确认 UI 要素**（EVM）：
- DApp origin（来源）
- 接收地址
- 金额（value）
- Gas 费估算
- 合约交互数据（data，可解码显示函数名）
- 风险提示（未知合约/大额转账）

**确认 UI 要素**（SWTC）：
- DApp origin
- 批量条数（单次上限 50 笔已强制）
- 总金额
- 代币类型
- 风险提示

### 9.3 实施顺序

1. **SDK Phase 1**：新增接口 + 中间件改造（EVM 链）
2. **SDK Phase 2**：SWTC 链 + 批量交易确认
3. **SDK Phase 3**：测试覆盖（未设回调 → 拒绝；确认 true → 执行；确认 false → 拒绝）
4. **App 同迭代**：**ccdao** 实现确认 UI 并注入回调；**jdid 不需要**（非交易类，探索页 DApp 仅连接账户）
5. **文档**：更新 `dapp-connect/README.zh-CN.md` 说明破坏性变更

### 9.4 API 影响评估

**破坏性变更**：
- 未设置 `TransactionConfirmCallback` 时，签名/转账类 RPC **拒绝执行**（需宿主显式注入）
- 与 Phase B（`sendTransaction` 加 origin 参数）类似，需跨仓同迭代

**兼容性**：
- **ccdao** 等交易类宿主：须注入 `TransactionConfirmCallback`，否则签名/转账不可用（安全优先）
- **jdid** 等非交易类宿主：不注入为预期；仅连接类 RPC 可用
- 新增接口为可选 setter，不强制所有宿主实现全部钩子

### 9.5 验收标准

- [x] EVM `sendTransaction` 无回调 → 拒绝；有回调且 false → 拒绝；true → 执行
- [x] EVM `personalSign` 同上
- [x] EVM `signTypedData` 同上
- [x] EVM `getEncryptionPublicKey` 同上
- [x] EVM `decrypt` 同上
- [x] SWTC `sendTransaction` 同上
- [x] SWTC `signMessage` 同上
- [x] SWTC `batchTransactions` 含总量展示
- [x] `./gradlew :dapp-connect:test` 全绿（94 tests）
- [x] 文档更新
- [x] ccdao 确认 UI 实现并注入（`cecf940` fix26）
- [x] jdid — **不适用**（非交易类 App；不实现 `TransactionConfirmCallback` 为产品决策，非遗漏）

### 9.6 实施记录（2026-08-28，已提交 7b3d8b1）

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| SDK Phase 1 | 新增 `TransactionConfirmCallback` 接口 + `TransactionRequest` sealed class（6 子类：SendTransaction/SignMessage/SignTypedData/Decrypt/GetEncryptionPublicKey/SwtcBatchTransaction） | — | 编译 |
| SDK Phase 2 | `IEthMiddleware`/`ISwtcMiddleware` 新增 `setTransactionConfirmCallback()`；`EthMiddleware` 在 personalSign/signTypedData/getEncryptionPublicKey/decrypt/signTransaction/sendTransaction 调回调，无/false → `UserRejectedException`；`SwtcMiddleware` 在 sendTransaction/signMessage/batchTransactions 调回调 | — | dapp-connect 146 测试全绿 |
| SDK Phase 3 | 新增 24 个测试用例：EVM 17 例（无回调/拒绝/参数验证）、SWTC 7 例（无回调/拒绝/参数验证） | — | 新增测试覆盖所有敏感方法 |

**API 影响评估**：
- **破坏性变更**：未设置 `TransactionConfirmCallback` 时，签名/转账类 RPC **拒绝执行**（`UserRejectedException`）
- **宿主适配（跨仓）**：**ccdao 必须**实现 `TransactionConfirmCallback` 并注入；**jdid 不需要**（产品定位为身份/探索，非链上交易；未注入时 sign/send/batch 等 RPC 由 SDK fail-closed 拒绝，符合预期）
- **影响方法**：
  - EVM：`personalSign`/`signTypedData`/`getEncryptionPublicKey`/`decrypt`/`signTransaction`/`sendTransaction`
  - SWTC：`sendTransaction`/`signMessage`/`batchTransactions`

---

## 10. 全局约束（每项必做）

1. **编辑前**：对将改动的符号跑 `impact({target, direction: "upstream"})`，向用户报告 blast radius；若 HIGH/CRITICAL 先停下确认。
2. **提交前**：`detect_changes()` 核对仅影响预期符号与流程。
3. **每项完成后**：`./gradlew ktlintCheckAll` + 对应模块测试全绿。
4. **回归**：全部完成跑 `./gradlew jacocoAllModulesReport`（报告 §8 建议的门禁）。

## 11. 验收标准

### 剩余高危批次（§8，已提交；见 §2.11 实施记录）
- [x] `verifyInstalledPackage` KDoc 定位 + `SignatureCheckPolicy` 显式枚举（H-W4-1/2，cf13cdb）——**已被架构定案覆盖：随 `verifyInstalledPackage` 一并移除（H-W4-3 收敛处置）**
- [x] WebView 调试显式关闭（H-DID4-2）
- [x] asset JS SHA-256 完整性自检（检测性日志，H-DID4-3）
- [x] `DidSdk` KDoc 声明「私钥进入 WebView 进程」边界（H-DID4-1）
- [x] 相关模块测试 + `ktlintCheck` 全绿
- [x] **架构项**：H-W4-3 信任根外置——**多宿主调研后定案（2026-08-25）**：方案 B（native 硬编码）不可行（证书各异）；收敛为「移除自引用 `verifyInstalledPackage` + `verifyApkFile` KDoc 信任模型」（无宿主迁移，已实施，见 §2.12）；H-DID4-4 签名移出 WebView——**已放弃（2026-08-25）**：12 个公开方法破坏性变更 + 收益边际，挂账为已知设计限制（见 §8 跟进项），长期等方案 A（Keystore）

### nft 批次（§7，已提交 59737a7；见 §2.10 实施记录）
- [x] `resolveRemoteImageUrl` 返回的 URL 均过 `SsrfGuard.check`，云元数据/内网 URL 被拒（M-8N）
- [x] `SsrfGuard.enabled` 为 `internal`（M-8N）
- [x] fetchJson/fetchText/fetchMetadataImage/SwtcChainNftClient 响应超过大小上限 → 返回 null/中断（M-3/M-9N）
- [x] chainId `"1"` 与 `"0x1"` 归一化后查同一记录；缺 chainId 不查 `0x0`（M-13N）
- [x] fetchJson 非 http(s) URL 返回 null 不抛异常；SsrfGuard 白名单只留 http/https（M-17N）
- [x] `./gradlew :nft:testDebugUnitTest` + `:nft:ktlintCheck` 全绿

### did 批次（§6，已提交 d9b9382；见 §2.9 实施记录）
- [x] `signCredentialForDApp` 无确认回调 / 确认 false → 拒绝（H-DID1；issuer 校验委托宿主）
- [x] 链上 resolve 失败 → 写操作中止不发布（M-DID2）
- [x] 链上已存在 DID 时 `uploadInitialDidDoc` 拒绝；didStat 失败拒绝发布（M-DID3）
- [x] 凭证验证 `fetchFailed=true` → 返回「未知」而非 invalid（M-DID1）
- [x] 畸形 `expirationDate` → 验证不通过（M-DID6）
- [x] `./gradlew :did:testDebugUnitTest` + `:did:ktlintCheck` 全绿
- [x] ~~H-DID2 pending 状态重启后仍受宽限期保护~~ —— **已放弃**（避免数据库 schema 变更），见 §8 跟进项

### 第 1 波（C-1/C-3/C-6/H-A1/H-A2，已提交 bf6a42a）
- [x] `grep -rn "wallet.model.Path\|toCorePath\|toWalletPath"` 无残留（C-1）
- [x] `grep -rn "vault.util.wipe\|%02x"` 无残留（C-3/C-6）
- [x] H-A1/H-A2 新增测试通过且覆盖「清库不被误触发」「空私钥不落 vault、地址可被真实导入解锁」
- [x] `./gradlew ktlintCheckAll` 与相关模块测试全绿
- [x] `detect_changes()` 确认无预期外符号受影响

### app-update 批次（§3，已提交 2c58d5b）
- [x] `http://` 输入的 checksums URL / APK URL 被拒绝（H-W3/L-4）
- [x] 证书提取失败 → `Failed` 而非放行（H-W2）；无法确认兼容 → 返回 `null` 不放行升级（M-W6）
- [x] `versionName`/`apkNamePrefix` 含路径字符 → 拒绝且无越界文件（M-7）
- [x] checksums/APK 超过大小上限 → 中断 `Failed`（M-3）
- [x] 下载校验哈希走 `JniVerifier.computeSha256`（M-W4）
- [x] `./gradlew :app-update:testDebugUnitTest` + `:app-update:ktlintCheck` 全绿

### wallet 安全快修（§4，已提交 0145e1d）
- [x] `Keypair`/`Mnemonic`/`GenerateHDWalletResult`/`TraditionalDeriveResult` 的 `toString()` 不含私钥/助记词/secret（M-W7）
- [x] `equals/hashCode` 行为不变（M-W7）
- [x] `./gradlew :wallet:testDebugUnitTest` + `:wallet:ktlintCheck` 全绿

### 剩余 P0 批次（§5，已提交；见 §2.8 实施记录）
- [x] 非桥页 URL 下 `onPromiseResult` 被拒；伪造 id/nonce 被拒（H-W1）
- [x] 导航后旧 origin 的 `postMessage` 被拒；实时 origin 与预设不符被拒（H-D1）
- [x] 超限批量被拒、边界值通过（H-D2）
- [x] did 宽限期内二次 resolve 不删本地文档；超期后删除（H-DID2）
- [x] did 删除后链上旧文档不复活（H-DID3）
- [x] 相关模块测试 + `ktlintCheckAll` 全绿；`detect_changes()` 无预期外符号受影响

### 跟进项（不在本批次范围，挂账）
- [ ] **#1 H-DID2 重启场景**：`pendingCreateDids` 内存方案在进程重启后失效——持久化需动数据库（用户明确不做），保持已知限制；如需重启保护需另议非 DB 持久化方案
- [x] **#2 dapp-connect 预存 ktlint 债清理**：已收口（2026-09-03）——模块 check 全绿 + 纳入 `ktlintCheckAll`（见 §14）
- [ ] **#3 H-DID4-4 签名移出 WebView（挂账）**：方案 B 放弃（12 个公开方法 `privateKey: String` 改 CharArray 破坏性变更 + 收益仅消宿主侧 String，SDK 仍转 String 过桥）；保留 H-DID4-1/2/3 加固（边界文档/关调试/asset 哈希）；方案 A（Keystore）长期规划，需专门评审

---

## 12. 宿主适配计划（ccdao-connector / jdid → 当前 SDK）

> 目标：把两个宿主 app 从 8e7394d（或更早版本）适配到当前 SDK HEAD。范围：4 处编译破坏（必改）+ 行为验证。背景：SDK 已按「多宿主信任模型」定案——锚在宿主侧，SDK 不自带统一信任根。
> 涉及跨仓改动，**非本仓提交**；宿主各自独立实施。

### A. 编译破坏适配（必改）

**A1. `wallet.model.Path` → `core.model.Path`**（bf6a42a C-1；import 切换，字段一致）
- **ccdao**：生产 `model/WalletModels.kt` 改 import + **删重复 `SdkPath.toApp()`（:58）与无用 import**——实施发现：ccdao 原本有 `wallet.Path.toApp()`（WalletModels）与 `core.Path.toApp()`（CoreModelMapping:32）两个扩展；SDK 去重后两者同 receiver（core.Path）、同 body，冲突；删 WalletModels 那份、保留 CoreModelMapping 的（body 相同，调用点不变）+ 测试 `viewmodel/WalletViewModelTest.kt:60`
- **jdid**：测试 5 文件（OnboardingViewModelTest / OnboardingViewModelQrDecryptTest / WalletViewModelTest / PrimaryWalletRepositoryTest / PrimaryHdWalletAddressesTest）
- 验证：编译通过

**A2. `wipe()` import `vault.util` → `core.security`**（bf6a42a C-6，`vault.util.Wipe` 已删除）
- **ccdao**：生产 3 处 import（`BiometricUnlockManager.kt:15` / `VCScreen.kt:84` / `UnlockViewModel.kt:16`）——ccdao 自封装 `com.android.ccdaoconnector.utils.wipe` **不动**
- **jdid**：生产 8 处 import（BiometricUnlockManager / PrimaryWalletRepository / VaultAuthRepository / ImportQrBackupDecrypt / BackupQrViewModel / CredentialViewModel / WalletViewModel / IdentityViewModel）+ 测试 2 处（ImportQrBackupDecryptTest / QrBackupTestFixtures）
- 验证：编译通过

**A3. `isSigningCompatibleWithInstalled` `Boolean` → `Boolean?`**（2c58d5b M-W6，两宿主生产）
- 现写法 `if (!AppUpdateApkInstaller.isSigningCompatibleWithInstalled(...))` → `!Boolean?` 编译错
- 改法（fail-closed）：`true` → 兼容继续；`false` → 拒绝；**`null`（无法确认）→ 中止升级返回失败**
- 位置：两宿主 `security/release/AppUpdateDownloadRepository.kt`
- 验证：`AppUpdateDownloadRepository` 相关测试 + 编译

**A4. `verifyInstalledPackage` 替代（jdid 启动自校验）——方案 1（采纳）**
- 现状：jdid `MainNavGraph.kt:73` release 启动校验已装包证书，`CertMismatch` → 弹「防误装」警告；调用旧签名 `verifyInstalledPackage(context, skipSignatureCheck = BuildConfig.DEBUG)`
- **方案 1（采纳）**：jdid 本地实现——`ApkSigningFingerprint.installedReleaseCertSha256(context)` 读已装包证书，与 jdid 自有 expected 证书常量（取自 jdid `official_release_manifest.json` 的 `signingCertSha256`，硬编码进 jdid 代码）做 `JniVerifier.hashEquals` 对比，不等 → 警告；约 5 行
- 说明：符合多宿主信任模型（锚在宿主侧）；expected 常量在 dex 可被重打包者改，为已接受限制（与 manifest 锚等价，被重打包的宿主可伪造 UI 显示通过）
- jdid 测试 `ApkIntegrityVerifierTest.kt:26-29` 删除
- 备选（未采纳）：方案 2——SDK 加回参数化 `verifyInstalledPackage(context, expectedCertSha256)`，SDK 面扩回，暂不做

### B. 行为变化验证（不改代码，跑宿主用例确认不坏）

- **did 写路径 fail-closed**：`updateDidNickname`/`uploadInitialDidDoc` 链上 resolve 失败 / DID 已存在 → 返回失败，宿主 UI 需展示新失败原因（不再静默成功）
- **nft 更严**：内网/云元数据 URL → null；metadata 文档 >5MB → null（**图片文件本身不受限**，宿主图片加载器自行拉取）
- **H-A2 子账户**：空私钥不再落 vault → 子账户地址 `getPrivateKey` 从返回空数组变**抛异常**（ccdao importSubAccount 流程验证）
- **H-D2 批量上限 50**：ccdao dapp 批量用例确认不超限
- **H-DID4-2 WebView 调试关闭**：debug 构建确认无依赖
- **M-W7 toString 掩码**：日志不再有密钥明文（正常集成无影响）

### C. 适配顺序与验证

1. 每宿主：A1 → A2 → A3（纯 import + null 处理）→ 编译通过
2. jdid：A4 方案 1 实现 + 删旧测试用例
3. `./gradlew :app:testDebugUnitTest` + 关键行为用例（did 写路径 / nft 列表 / 子账户导入 / dapp 批量）
4. release 构建 + 手测：jdid 启动自校验警告、两宿主 APK 校验页

**实施状态（2026-08-25）**：A1-A4 已实施——ccdao（6 文件：A1 含删重复 toApp、A2×3、A3）+ jdid（18 文件：A1×5 测试、A2×10、A3、A4 MainNavGraph 本地锚 + 删 verifyInstalledPackage 测试）均通过 `-Pjccdex.toolkits.mode=local` 编译验证（主源码 + 测试单元）；jdid `ApkIntegrityVerifierTest`（verifyApkFile 2 例）通过。**宿主改动待各自仓库 commit**；行为级验证（§B）待宿主测试套件回归。

---

## 13. P1 剩余跨模块批次计划（M-1 / M-W1 / B-4）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §7 P1 剩余项。目标：一次收干净 P1 里剩余的跨模块正确性/安全项——协程取消传播、哈希常量时间比较、locale 归一。全部为行为/实现级修复，不动架构、不动数据库。

### M-1：协程取消被 `catch(Exception)`/`runCatching` 吞掉（account/did/nft/app-update/dapp-connect）

**问题**（[报告 :101](CODE_REVIEW_ANALYSIS.md)）：`CancellationException` 是 `Exception` 子类——`catch (e: Exception)`/`runCatching` 把协程取消当普通失败吞掉，导致 `cancel()`/`withTimeout` 失效、后台任务继续执行昂贵操作（Argon2 KDF/网络/IO）、状态更新竞态。

**范围**（grep 实测）：5 模块 13 文件——`catch(Exception)` **63 处**（account 2 / did 24 / nft 5 / app-update 4 / dapp-connect 28）+ `runCatching` **22 处**（did 6 / nft 15 / apk-verify 1）。注：apk-verify 的 1 处（`JniVerifier.kt:15` 包 `loadLibrary`）是**非 suspend 的 object 初始化**，`CancellationException` 不可能在此抛出——计入总数但**无需改**；修复只针对 suspend/重操作上下文。

**改动**：
1. **模式**：`catch (e: CancellationException) { throw e }` 置于 `catch(Exception)` 之前；`runCatching` 用 `.onFailure { if (it is CancellationException) throw it }` 或改显式 try/catch。
2. **优先级**：
   - **真实风险点（suspend/长操作包覆）先修**：did `DidSdk.kt:1098`（包 `bridge.call`）、`DidSyncService.kt:20`（resolveDid）、`DidCoreService.kt:71`；account `AccountOrchestrator.kt:251/:309`（含 Argon2 KDF）；nft `NftStore`/`SwtcChainNftClient` 的 fetch/解析类 catch；dapp-connect `WebAppInterface`/`SwtcMiddleware`/`EthMiddleware` 的 suspend catch。
   - **纯函数包覆**（`Instant.parse`/`toChecksumAddress` 等，报告标注风险低）：一并机械加 CancellationException 重抛，保持全库一致。
3. **验证**：新增 1 个测试（runTest 下取消 → `CancellationException` 被重抛，不吞）；现有测试全绿。

### M-W1：JniVerifier Java 回退非常量时间比较（apk-verify）

**问题**（[JniVerifier.kt:31](apk-verify/src/main/java/com/jccdex/toolkits/apkverify/JniVerifier.kt#L31)）：native 加载失败（.so 缺失/ABI 不符）**静默降级** Java，回退用 `a.equals(b, ignoreCase = true)`——非常量时间（理论时序侧信道）+ `ignoreCase` 对 hex 哈希无意义。

**改动**（手术式；收敛 core `SecureCompare` 留 C-13 P2）：
1. `hashEquals` 回退改**恒定时间比较**：lowercase 归一后逐字节 XOR 累加，全程遍历不等早退。
2. native 加载失败加**显著日志**（`Log.e`），消除静默降级。
3. 移除 `ignoreCase`（哈希已 lowercase 归一）。

**验证**：新增测试——回退路径恒定时间比较正确（大小写归一相等 / 不等 false / 长度不等 false）。

### B-4：`lowercase()` 无 `Locale.ROOT`（全库 17 处）

**问题**：`String.lowercase()` 用默认 locale——土耳其语环境 `I/i` 映射差异导致地址/hex/校验码大小写归一错误（如 `"I"` 变 `"ı"`）。

**范围**（grep 实测 17 处，`.uppercase()` 0 处）：did 2（`DidSyncService`/`DidSdk`）、nft 8（`NftStore`）、dapp-connect 2（`WebOrigin`）、apk-verify 2（`ReleaseChecksumsFile`）、vault 3（`VaultRepository` AAD）。

**改动**：`.lowercase()` → `.lowercase(Locale.ROOT)`（补 `import java.util.Locale`）。

**验证**：编译 + 相关模块测试全绿。

### 实施顺序
M-W1（单文件小改）→ B-4（机械批量 17 处）→ M-1（最大面，按 account → did → nft → app-update → dapp-connect 逐模块）

### 全局验证
`./gradlew testDebugUnitTest`（全模块）+ `ktlintCheckAll`；`detect_changes` 确认仅影响预期文件。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-1 | 5 模块 14 文件插入 `catch (e: CancellationException) { throw e }`（**67 处**；AppUpdateApkInstaller 已有 1 处正确模式，跳过）+ **6 处 suspend 包覆 runCatching** 加 `.onFailure { if (it is CancellationException) throw it }`（DidSyncService:21、NftStore:387、SwtcChainNftClient:37、NftRemoteAssetResolver:144、EvmRpcClient:21/35）；17 文件补 `import kotlinx.coroutines.CancellationException` | **纯函数 runCatching（~13 处：Instant.parse/toChecksumAddress/URL/JSONObject 等）未加**——不包 suspend、`CancellationException` 不可能抛出（报告标注风险低）；机制测试 `CancellationPropagationTest` 首版 `isCancelled` 断言为**假阳性**（取消后恒 true），已改用 `UNDISPATCHED` + `continuedAfterCancel` 副作用标志（验证取消后协程体不继续执行），并实测「有修复通过 / 移除 onFailure 失败」证明非假阳性 | account 69（含新测试）/ did 167 / nft 80 / app-update 13 / dapp-connect 72 全绿 |
| M-W1 | `JniVerifier.hashEquals` 回退改**恒定时间比较**（lowercase 归一 + 逐字节 XOR 累加）；native 加载失败加 `Log.e`（**best-effort 用 `runCatching` 包住**——防纯 JVM 测试里 `Log` stub 抛异常炸类初始化，首轮 11 个 apk-verify 测试因此失败已修复）；移除 `ignoreCase` | — | apk-verify 25 测试全绿（含新增「长度不等 false」用例） |
| B-4 | 17 处 `.lowercase()` → `.lowercase(Locale.ROOT)`（did 2 / nft 8 / dapp 2 / apk 2 / vault 3）+ 6 文件补 `import java.util.Locale`；**VaultRepository：静态导入 `Locale.getDefault` 改类导入 + `:371` 裸 `getDefault()` 改 `Locale.ROOT`**（地址归一本就应 ROOT） | `ktlintFormat` 修正 4 处（did/nft import 排序 + NftStore :127/:136 行长——B-4 把两行推超 120，纯格式） | vault 43 全绿 |

**验证**：受影响 7 模块共 **469 测试全绿** + `ktlintCheckAll` 通过；`detect_changes` 确认仅影响预期 21 主源文件 + 1 测试。

---

## 14. dapp-connect 批次计划（M-D5 / M-D6 / M-D7 + 跟进项 #2 ktlint 债）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §7 P1 剩余 + 跟进项 #2。目标：一次收干净 dapp-connect 模块的安全面——链状态静默切换、响应队列无界、gas 静默回退——并清掉预存 ktlint 债。**做完整批 dapp-connect 的 P1 清零**；account 的 P1（M-18A/19A/21A）留单独批次。

### M-D5：`handleSwtcRequestAccounts` 静默切换全局链状态（[WebAppInterface.kt:346-348](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/WebAppInterface.kt#L346-L348)）

**问题**：DApp 只需发起 `swtc_requestAccounts` 就静默 `ethMiddleware.setCurrentChainType(SWTC)`——无用户确认、无来源提示，把钱包**全局链状态**改掉（影响 ETH 侧所有后续请求、`eth_chainId` 返回值）。

**改动**：
1. `handleSwtcRequestAccounts` 里 `currentChainType != SWTC` 时，改走 **`chainProvider.requestChainSwitch(currentChain, SWTC, origin)` 确认**（复用 WebAppInterface 已有 `chainProvider` 字段，与 `wallet_switchEthereumChain` 同一确认通道）；拒绝 → `UserRejectedException`；provider 未设 → `IllegalStateException`（fail-closed，与 `switchEthereumChain` :415 一致）。
2. 确认通过后才 `setCurrentChainType(SWTC)`。
3. KDoc 注明：链状态变更必须经 ChainProvider 确认，禁止中间件静默改全局状态。

**验证**：新测试——未确认时 requestAccounts 不切链且抛 `UserRejectedException`；确认后切 SWTC 并返回账户。

### M-D6：`NativeResponseChannel` pending 无界 + 无速率限制

**问题**：`pending = ArrayDeque` 只增不降（:26，页面未 install 时响应全堆积，`flushPending` 失败也不回缩）；DApp 高频请求无速率限制 → 恶意页面刷屏 → 内存 DoS。

**改动**：
1. `NativeResponseChannel.enqueue`：`pending.size >= MAX_PENDING`（如 **100**）时**丢弃最旧** + `Log.w`。
2. **RPC 速率限制**：`WebAppInterface.postMessage`（[@JavascriptInterface](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/WebAppInterface.kt#L109) 入口）加**轻量令牌桶**——按 origin 计数，如每秒 ≤60 次，超限拒绝并回错误响应；实现用内存计数器（防滥用，非精确 QPS 控制）。
3. KDoc 说明限制语义。

**验证**：新测试——pending 超限丢最旧；postMessage 超速被拒。

### M-D7：gas 估算失败静默回退 21000（[EthMiddleware.kt:324-327](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/EthMiddleware.kt#L324-L327)）

**问题**：`estimateGas` 抛异常 → `txParams.put("gas", "0x5208")`（21000 是简单转账值）——复杂合约交易大概率 out-of-gas 失败（**手续费损失**），用户无感知。

**改动**：`catch (e: Exception)` 分支改 **fail-closed 抛明确错误**（`throw IllegalStateException("Gas estimation failed: ...")`），不再静默 0x5208；KDoc 注明。备选（未采纳）：高安全系数回退需调节点数据，语义不如抛错清晰。

**验证**：新测试——estimateGas 失败 → 抛异常而非 0x5208。

### 跟进项 #2：dapp-connect 预存 ktlint 债（18 文件）——**✅ 已收口（2026-09-03）**

**范围**：DAppConnectSdk / WebAppInterface / EthMiddleware / SwtcBatchTransactions / SwtcMiddleware / Models / CachingSecretProvider + 10 测试文件。
**历史（2026-08-25）**：曾决定拆出（`ktlintFormat` diff 过大、且不在 `ktlintCheckAll` 门禁内）。后续安全/功能提交（含 `051faea` 等）已逐步清掉违规。
**收口（2026-09-03）**：`:dapp-connect:ktlintCheck` 全绿（main + test）；把 `dapp-connect` 纳入根 `ktlintCheckAll` / `ktlintFormatAll`（`ktlintModules`，不进 jacoco `coverageModules`），防止回潮。

### 实施顺序
M-D7（单点小改）→ M-D5（确认流程）→ M-D6（队列上限 + 限流）→ ~~跟进项 #2（ktlintFormat）~~ 拆出

### 全局验证
`./gradlew :dapp-connect:testDebugUnitTest`（全模块）+ `ktlintCheckAll`；`detect_changes` 确认仅影响 dapp-connect。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-D7 | `EthMiddleware.signTransaction` gas 估算失败从静默 `0x5208` 改 **fail-closed 抛 `IllegalStateException`** | — | 新测试 `signTransaction throws when gas estimation fails...`（estimateGas 抛异常→断言抛错且消息含 Gas estimation） |
| M-D5 | `handleSwtcRequestAccounts` 改走 **`chainProvider.requestChainSwitch` 确认**（复用 WebAppInterface 已有 provider 字段）；未确认抛 `UserRejectedException`、provider 未设抛 `IllegalStateException`，确认后才 `setCurrentChainType(SWTC)` | — | 新测试 2 例（拒绝→不切链 / 确认→切 SWTC），`WebAppInterfaceSecurityTest` |
| M-D6 | `NativeResponseChannel.enqueuePending` **上限 100 丢最旧** + `WebAppInterface.postMessage` **按 origin 令牌桶限流**（60/秒，超限回错误响应）——`TokenBucketRateLimiter` 为 internal 供测试 | `postMessage` 限流测试落在 rate limiter 机制层（WebAppInterface 异步 IO 难稳定测） | 新测试 2 例（pending 上限 / 令牌桶预算+拒绝+独立 key） |

**验证**：dapp-connect **77 测试全绿**（含新增 6 例）+ `--rerun-tasks` 强制重跑确认 + `ktlintCheckAll` 通过；改动 +133/-6（5 文件 + 1 新测试，无格式 churn）。

---

## 15. account 批次计划（M-18A / M-19A / M-21A）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §7 P1 剩余最后 3 项。目标：收干净 account 模块的重复地址检查、并发保护、锁定异常透传。**做完本批 P1 全部清零。**

### M-18A：重复地址检查不完整（[AccountOrchestrator.kt:33,76](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L33) + [AccountDao.kt:37-44](account/src/main/java/com/jccdex/toolkits/account/storage/room/AccountDao.kt#L37-L44)）

**问题**：`importSingleAccount` 只查 `findNonRootAccount`（不查同地址 **HD 根账户**）；`importHdWallet` 只查 `findRootAccountByAddress`（不查同地址**传统/子账户**）→ 同地址多类型账户可并存（vault「一地址一密钥」归属二义、`getSameAccountsCount` 语义混乱）。

**改动**：
1. `importSingleAccount`（:33）：`findNonRootAccount(address, chain)` → **`findByAddress(address, chain)`**（全量，含 HD 根）。
2. `importHdWallet`（:76）：查重改为**全量**——`findByAddress(hdResult.address, chain)`（覆盖传统账户/子账户/HD 根）。
3. 语义：同地址（+chain）**任何类型**账户已存在即拒绝，统一返回 `AddressAlreadyExists`/`AccountAlreadyExists`。

**验证**：新测试——HD 根已存在时 `importSingleAccount` 拒绝；传统账户已存在时 `importHdWallet` 拒绝。

### M-19A：orchestrator Mutex 实例级，并发保护失效（[AccountOrchestrator.kt:22](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L22) + [AccountSdk.kt:16-17](account/src/main/java/com/jccdex/toolkits/account/AccountSdk.kt#L16-L17)）

**问题**：`AccountOrchestrator` 实例级 `Mutex`；`AccountSdk.orchestrator()` **每次 new** → 并发 `deriveSubAccount`（索引分配）/`removeAccount`（同地址删除）跨实例不串行 → 派生同一索引 / 孤儿密钥。

**改动**：`AccountSdk.orchestrator(vaultRepository)` **按 vaultRepository 缓存单例**（`ConcurrentHashMap`），多次调用返回同一实例（共享 mutex）。备选（宿主建多个 AccountSdk 实例时）：mutex 提升 store 级 / companion 共享。

**验证**：新测试——同一 vault 两次 `orchestrator()` 返回同一实例。

### M-21A：`VaultAuthLockedException` 被 `runOperation` 吞成 Failure（[AccountOrchestrator.kt:327-334](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L327-L334) + [AccountOperationError.kt](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOperationError.kt)）

**问题**：锁定期 `verifyPassword`（removeAccount/clearWalletData 路径）抛 `VaultAuthLockedException` → `catch(Exception)` 包装为 `Failure`——调用方无法**类型化**区分「密码错误」与「账户锁定」，无法展示锁定倒计时（`authLockRemainingMs()` 存在但未透传）。

**改动**：
1. `AccountOperationError` 新增 `data class VaultLocked(val remainingMs: Long)`。
2. `runOperation` 在 `catch(Exception)` 前加 `catch (e: VaultAuthLockedException) { Error(VaultLocked(e.remainingMs)) }`（CancellationException 重抛已在 §12 加）。

**API 影响**：`AccountOperationError` 加新子类型——宿主若有 `when` 穷举需加分支（ccdao/jdid 需核查）。

**验证**：新测试——锁定异常 → `Error(VaultLocked(remainingMs))` 而非 `Failure`。

### 实施顺序
M-18A → M-19A → M-21A

### 全局验证
`./gradlew :account:testDebugUnitTest` + `ktlintCheckAll`；`detect_changes`。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| M-18A | `importSingleAccount` 查重 `findNonRootAccount` → **`findByAddress(address, chain)`**；`importHdWallet` 根查重 `findRootAccountByAddress` → **`findByAddress(address, ChainType.SWTC)`**（HD 根恒为 SWTC，覆盖传统账户/HD 根） | importHdWallet 子账户循环（:133）的 `findNonRootAccount` 保留（skip-if-exists 语义，非导入查重） | 新测试 2 例（HD 根存在拒 importSingleAccount / 传统账户存在拒 importHdWallet） |
| M-19A | `AccountSdk.orchestrator()` 按 vaultRepository **`ConcurrentHashMap` 缓存单例**（共享 mutex） | 备选（宿主多 AccountSdk 实例）未做——store 级共享 mutex 需更大改动，暂以单例缓存覆盖主场景 | 新测试 1 例（同一 vault 两次调用返回同一实例） |
| M-21A | `AccountOperationError` 新增 `VaultLocked(remainingMs)`；`runOperation` 在 catch(Exception) 前 `catch (e: VaultAuthLockedException)` 透传 | — | 新测试 1 例（clearAllData 抛 VaultAuthLockedException(5000) → `Error(VaultLocked(5000))`） |

**验证**：account **73 测试全绿**（含新增 4 例）+ `ktlintCheckAll` 通过。

**宿主适配提示（跨仓）**：M-21A 给 `AccountOperationError` 加新子类型——**ccdao 的 `AccountOperationMapping.kt` `toUiMessage()` 是穷举 when 无 else**，升级后需加 `is AccountOperationError.VaultLocked -> ...` 分支（jdid 待核查）。见 §11 宿主适配模式。

---

## 16. P2 架构批次规划 + P2-1 计划（core 安全/编码收敛）

> P1 已全部清零（M-1/M-W1/B-4 + M-D5/6/7 + M-18A/19A/21A）。P2 为大工程，按批次推进，每批需设计评审。**排除项：M-14A/M-DID4/M-11N/M-20A（数据库 schema）——用户明确不动数据库。**

### P2 批次规划（roadmap）

| 批次 | 内容 | 依赖/决策 |
| --- | --- | --- |
| **P2-1（本批）** | core 安全/编码收敛：C-13（Hashing/SecureCompare）+ C-20 部分（`ChainType.toEvmChainIdHex`） | 纯 JVM，无 Android 依赖，不需 core-android 决策 |
| **P2-2** | C-2/C-17 HttpFetcher 统一（6 处 HTTP 样板 + SSRF/大小上限/重定向） | **需决策**：HttpFetcher 用 `java.net` 纯 JVM 可行；`WebOrigin.normalize` 依赖 `android.net.Uri` → 或拆 `core-android`，或保留 dapp-connect internal |
| P2-3 | C-4 JSON 策略统一（org.json 安全读取工具 + Gson/org.json 分工约定） | 需评审 |
| P2-4 | C-7/C-10 安全工具（EvmAddress checksum / WebOrigin / URL 白名单收敛 core） | 随 P2-2 |
| P2-5 | C-9/C-12 统一异常体系 + RPC 错误码（ToolkitException + ErrorCodes） | 需评审 |
| ~~P2-6~~ | C-14/C-15/C-16/C-21 DID/NFT 解析 + 标准常量收敛 | did/nft 内部 | ✅ `JsonPath`/`NftStandards`/`DidDocumentReader` + VC ID/元数据解析收敛 |
| ~~P2-7~~ | C-22「非根账户」判定 SQL 与 `isSubHD()` 单一来源 | account | ✅ `AccountClassification` + AccountDao SQL 收敛 |
| ~~P2-8~~ | X-1/X-2/X-4/C-24 桥接运行时合并（双 WebView/死 API/callbackMap 契约） | webview-bridge + wallet/did | ✅ P2-8a `4d2311f` + P2-8b `42a5f32`（见下方实施记录） |
| ~~DB~~ | ~~M-14A/M-DID4/M-11N/M-20A~~ | **排除**（用户不动数据库） |

### P2-8 实施记录（2026-09-03 收口）

> 对应 [CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §8.2 X-1/X-2/X-4 与 C-24。模块 README：`webview-bridge/README.zh-CN.md`、根 `README.md` §4.4。

| 子项 | 提交 | 内容 |
| --- | --- | --- |
| **P2-8a**（X-2/X-4/C-24） | `4d2311f` | 删除死公共 API `WebviewBridgeEngine` / `JsPromiseGateway`；唯一入口 `WebviewBridgeClient` + 实例级 `PromiseGateway`/`callbackMap`；wallet 双接口合并为 `IWalletBridge` |
| **P2-8b**（X-1） | `42a5f32` | `SharedWebviewBridge` + `unified-bridge.html`（wallet+did 单隐藏 WebView）；`ToolkitBridgeRuntime.shutdown()` / `reloadSharedBridge()`；生产路径 lazy 创建 |

**隔离契约**：生产默认 1 WebView + 1 `callbackMap`；测试/自定义 runtime 仍可注入独立 `wallet-bridge.html` / `did-bridge.html`。`WalletSdk.destroy()` 只释放 wallet 门面，不销毁共享桥。

**宿主**：进程退出调用 `ToolkitBridgeRuntime.shutdown()`（ccdao：`WebviewBridge.shutdownSharedBridge()`；jdid：`JDIDApp` 已接）。钱包重置勿 shutdown 共享桥——ccdao 用 `WebviewBridge.resetWalletAfterWipe()`。

**验证**：`SharedWebviewBridgeTest` / `UnifiedBridgeClientTest` / `ToolkitBridgeRuntimeTest` + wallet/did runtime 单测；跟进项 #1/#3、P2-4 暂缓仍挂账；#2 ktlint 已另批收口。

### P2-1 计划：core 安全/编码收敛（C-13 + C-20 部分）

**C-13：Hashing / SecureCompare 收敛 core**（纯 JVM）

现状：哈希实现散落——`ApkDigest.sha256Hex`（apk-verify）、`JniVerifier.constantTimeHexEquals`（M-W1 的本地实现）、`SwtcChainNftClient` 证书钉扎 MessageDigest、`ApkSigningFingerprint`。`ChecksumUtils` 已用 core Hex（C-3 完成）。

**改动**：
1. 新增 `core/security/Hashing.kt`：`sha256(bytes: ByteArray): ByteArray`、`sha256(inputStream: InputStream): ByteArray`、`sha256Hex(...): String`（用 `MessageDigest`，纯 JVM）。
2. 新增 `core/security/SecureCompare.kt`：`constantTimeEquals(a: ByteArray, b: ByteArray)`（逐字节 XOR 累加）+ `constantTimeHexEquals(a: String, b: String)`（lowercase 归一，与 M-W1 语义一致）。
3. **收敛调用点**：
   - `ApkDigest.sha256Hex(bytes/inputStream/file)` → 委托 core Hashing（保留 ApkDigest 薄封装或改调用点，实现时定）。
   - `JniVerifier.constantTimeHexEquals` → 委托 core SecureCompare（**兑现 M-W1「收敛 core SecureCompare」**）。
   - `SwtcChainNftClient` 钉扎的 `MessageDigest` → core Hashing。
4. 验证：既有 JniVerifierTest/ApkDigest 测试 + 新增 Hashing/SecureCompare 单测。

**C-20 部分：`ChainType.toEvmChainIdHex()`**（纯 JVM）

**改动**：
1. `core/model/ChainType.kt` 加扩展 `fun ChainType.toEvmChainIdHex(): String?`（`evmChainId?.let { "0x${it.toString(16)}" }`，与 M-13N 归一语义一致）。
2. `NftStore` 的 `"0x${...toString(16)}"` 收敛：**:185/:291（ChainType/entity 场景）→ 用 `ChainType.toEvmChainIdHex()`**；**:159 是 `normalizeChainIdHex` 内部 String→Long→hex 的格式化（非 ChainType 场景）**——另加 `Long.toEvmChainIdHex()` 辅助（`"0x${toString(16)}"`）供两处复用。
3. 验证：NftStore 测试（chainId 归一）保持绿。

### 实施顺序
SecureCompare/Hashing 加入 core → JniVerifier/ApkDigest 收敛 → SwtcChainNftClient → ChainType 扩展 + NftStore 收敛

### 全局验证
`./gradlew testDebugUnitTest`（全模块）+ `ktlintCheckAll`；`detect_changes` 确认仅影响 core/apk-verify/nft。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| C-13 | 新增 `core.security.Hashing`（`sha256` ByteArray/InputStream/File → hex）+ `core.security.SecureCompare`（`constantTimeEquals`/`constantTimeHexEquals`）；`JniVerifier` 回退委托 SecureCompare（兑现 M-W1「收敛 core」）；`ApkDigest` 变 Hashing 薄封装（调用点不变）；`SwtcChainNftClient` 钉扎 MessageDigest → Hashing | `ApkDigest` 保留为门面（避免改 JniVerifier/ApkIntegrityVerifier 调用点），后续可删 | core 26（+6 新测试：已知 sha256("hello")、stream/file 一致、大小写/长度/字节比较）/ apk-verify 25 / nft 80 全绿 |
| C-20 部分 | `ChainType.toEvmChainIdHex()` + `Long.toEvmChainIdHex()` 扩展；`NftStore` 3 处 `"0x${toString(16)}"` 收敛（:159/:185/:291 均为 Long 场景，用 Long 扩展） | 审查确认 :159 是 normalizeChainIdHex 内部 Long→hex（非 ChainType 场景）——已用 Long 扩展 | nft 80 全绿（chainId 归一测试保持） |

**验证**：core/apk-verify/nft 全绿 + `ktlintCheckAll` 通过；改动 5 文件 + 3 新文件（Hashing/SecureCompare/测试）。

---

## 17. P2-2 计划（C-2/C-17 HttpFetcher 收敛 core）

> 目标：消除 6 处重复 HTTP 样板，core 新增 `HttpFetcher`。**风险：动 app-update 更新链（H-W3/L-4 已加固）+ nft SSRF（M-8N 已加固）——必须保留这些语义，不得回归。**

### core 纯度决策（方案 A，已定）
`HttpFetcher` 用 `java.net` / `javax.net.ssl`（纯 JVM）+ **`java.util.Base64` 替代 `android.util.Base64`**（minSdk 26+ 可用，钉扎可单测）；SSRF 检查是纯 JVM 钩子（`java.net.InetAddress`）。**`WebOrigin.normalize`（android.net.Uri）留在 dapp-connect**——它是 origin 归一不是 HTTP 传输。core 保持「零 Android 依赖」定位。

### HttpFetcher API（`core/net/HttpFetcher.kt`，纯 JVM）
```kotlin
enum class RedirectPolicy { NONE, SAME_HOST_HTTPS }   // 逐站保留现状，宿主零行为变化

class HttpFetcher(
    val connectTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 10_000,
    val maxResponseBytes: Int = 5 * 1024 * 1024,      // M-3/M-9N 大小上限
    val httpsOnly: Boolean = true,                     // H-W3
    val redirectPolicy: RedirectPolicy = RedirectPolicy.SAME_HOST_HTTPS,
    val maxRedirects: Int = 3,                         // 仅 SAME_HOST_HTTPS 生效
    val ssrfCheck: ((String) -> Boolean)? = null,      // M-8N 钩子（返回 false 拒绝）
    val certificatePins: Set<String> = emptySet()      // C-17：sha256/<base64>，java.util.Base64
) {
    suspend fun get(url: String): Result<String>
    suspend fun getBytes(url: String): Result<ByteArray>
    suspend fun postJson(url: String, body: JSONObject): Result<String>
    suspend fun downloadToFile(url: String, target: File): Result<File>  // APK 流式 + 上限
}
```
错误语义：`Failure(HttpException(code, msg))` / `Failure(SizeExceeded)` / `Failure(SsrfBlocked)` / `Failure(RedirectExceeded)`。

**重定向策略逐站保留（关键：宿主零行为变化）**：`redirectPolicy` 按现状映射——NftStore/NftRemoteAssetResolver 用 **`NONE`**（现不跟随）；app-update/SwtcChainNftClient/EvmRpcClient 用 **`SAME_HOST_HTTPS`**（其中 RPC 两处现为 follow-all，收紧为同 host https 是**刻意的安全改进**：跨 host RPC 重定向可疑，行为更严但更安全）。

### 收敛清单（6 处）
| 位置 | 现状 | 收敛到 |
| --- | --- | --- |
| `AppUpdateChecker` checksums | `openHttps` + 1MB cap | `get(maxResponseBytes=1MB, redirectPolicy=SAME_HOST_HTTPS)` |
| `AppUpdateApkInstaller` APK | `openHttps` + 200MB cap 流式 | `downloadToFile(max=200MB, redirectPolicy=SAME_HOST_HTTPS)` |
| `NftStore.fetchJson`/`fetchText` | SsrfGuard + 5MB + **不跟随重定向** | `get(ssrfCheck=SsrfGuard, redirectPolicy=NONE)` |
| `NftRemoteAssetResolver.fetchMetadataImage` | SsrfGuard + 5MB + **不跟随重定向** | `get(ssrfCheck=SsrfGuard, redirectPolicy=NONE)` |
| `SwtcChainNftClient` erc_info | POST + 钉扎 + 5MB + **follow-all** | `postJson(pins, redirectPolicy=SAME_HOST_HTTPS)` |
| `EvmRpcClient` JSON-RPC | POST + **follow-all** | `postJson(redirectPolicy=SAME_HOST_HTTPS)` |

### 保留语义（防回归，逐条对应）
- **H-W3/L-4**：`httpsOnly` + 同 host 重定向（HttpFetcher 内置，与 `openHttps` 的 `resolveSameHost` 一致；AppUpdateApkInstaller 的 HTTP 拒绝路径测试保持）
- **M-8N**：`ssrfCheck` 钩子在读取/返回前调用（nft 的 SsrfGuard 传入）
- **M-3/M-9N**：`maxResponseBytes` 流式计数中断（与 `readTextLimited` 一致）
- **C-17**：`certificatePins`（`java.util.Base64`，替换 `android.util.Base64`，SwtcChainNftClient 钉扎单测可运行）
- `openHttps`/`UpdateHttp.kt` 收敛后删除或变薄封装（实现时定）

### 实施顺序（低风险→高）
1. core `HttpFetcher` 本体 + 单测（GET/POST/重定向/大小上限/SSRF 钩子/钉扎）
2. `EvmRpcClient`（最简 POST）
3. `NftStore.fetchJson/fetchText` + `NftRemoteAssetResolver`（SSRF 保留）
4. `SwtcChainNftClient`（钉扎）
5. `AppUpdateChecker` + `AppUpdateApkInstaller`（**更新链最敏感，最后**）

### 全局验证
每步对应模块测试全绿；重点回归：app-update `http://` 拒绝 + 重定向逃逸测试、nft `SsrfGuard` 内网拒绝 + 大小上限测试、`SwtcChainNftClient` 钉扎测试。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| HttpFetcher | `core/net/HttpFetcher.kt`：**阻塞式零依赖**（core 无 kotlinx-coroutines/org.json）——get/getBytes/postJson(body: String)/downloadToFile(onProgress/cancelCheck)；`RedirectPolicy`/`HttpResult`/`HttpError`；CancellationException 用 `java.util.concurrent`（kotlinx 是它的 typealias）重抛 | **偏差**：原计划 suspend API——core 无协程依赖改为阻塞式，调用方已有 `withContext(IO)`；postJson 收 String（避免 org.json 依赖）；downloadToFile 加 onProgress/cancelCheck 保 AppUpdateApkInstaller 进度/取消语义 | core 30（+4 HttpFetcherTest：https 拒绝/ssrf/无效 URL/map） |
| EvmRpcClient / NftStore ×2 / NftRemoteAssetResolver / SwtcChainNftClient / AppUpdateChecker | 6 处全部收敛 HttpFetcher；`UpdateHttp.kt` 删除（openHttps 无生产引用）、相关测试移除 | **关键偏差**：nft 4 处 `httpsOnly=false`——原 nft 允许 http（只靠 SsrfGuard 挡内网），HttpFetcher 默认 httpsOnly 会回归（测试暴露：MockWebServer http://localhost 被拒）；**https 强制仅 app-update（H-W3）保留** | nft 80 / app-update 12 全绿 |
| AppUpdateApkInstaller | 下载收敛 `downloadToFile`（onProgress + `coroutineContext.ensureActive()` 经 cancelCheck）；失败映射：HttpException→"Download failed (code)" / SizeExceeded→"APK exceeds size limit" / else→"Unable to open download"；删除 BUFFER_SIZE | app-update 补 `implementation(:core)`；跨模块 public 属性不能 smart-cast → 先赋本地 val | 全绿 |

**验证**：core 30 / nft 80 / app-update 12 全绿 + `ktlintCheckAll` 通过；改动 8 文件 + 2 新文件（HttpFetcher/测试）+ 1 删除（UpdateHttp.kt）。

---

## 18. P2-3 计划（C-4 JSON 策略 + C-14 JSON 点路径收敛 core）

> 目标：确定唯一 JSON 策略并收敛散落的解析逻辑。**C-4**（org.json 安全读取 + Gson/org.json 分工）+ **C-14**（JSON 点路径读取器去重）。

### JSON 策略约定（写入 core KDoc，作为全库约定）

- **org.json**：无类型/动态 JSON 解析（DID 文档、RPC 响应、WebView 桥消息）——Android 平台自带，无需依赖。
- **Gson**：data class 类型化反序列化（wallet/did/nft/webview-bridge）——纯 JVM 库。
- 禁止同一模块混用两者做同一件事（did 目前两者都用，按「动态用 org.json / 类型化用 Gson」分流）。

### core 依赖决策（✅ 方案 A 已定：core 引入 Gson）

`JsonPath`（Gson 点路径）需 **core 引入 `libs.gson`**。**已定案（2026-08-25）**：core 本就不是「纯 JVM 零依赖」——它是 Android library（compileSdk 36，android.jar 自带 org.json 等）；它只是「零第三方主依赖」。**Gson 是纯 JVM 库，非 Android 依赖**——加它不触发报告 §4.3 的 core 纯度担忧（那担忧针对 HttpFetcher/WebOrigin 的 android.net 依赖）。理由：Gson 纯 JVM（core 单测可测）、wallet/did/nft/webview-bridge 4 模块在用（import 口径）、无替代；org.json 是 Android stub（纯 JVM 单测不可测）。**备选已否决**：独立 `core-json` 模块（为一个工具开模块过重）、org.json 手写路径（不可单测）。

### core 新增

**`core/json/Json.kt`**（org.json 安全读取，platform 提供 org.json 无需依赖）：
- `fun JSONObject.optStringSafe(key: String): String?`（返回 null 而非 `""`，收敛 did/nft/dapp-connect 的 `optString(...).orEmpty()` 变体）
- `fun JSONObject.optJSONObjectSafe(key: String): JSONObject?`（同类语义）

**`core/json/JsonPath.kt`**（Gson 点路径读取，收敛 C-14 同款实现）：
- `fun readElement(doc: String, path: String): JsonElement?`（`$.a.b.c` 遍历，与 DidSdk.readElement/NftStore.parseString 同款）
- `fun readString(doc: String, path: String): String?` + `readString(doc, path, default)`

### 收敛调用点

| 位置 | 现状 | 收敛到 |
| --- | --- | --- |
| `DidSdk.readElement`/`readString`（:1230-1260） | 私有 Gson 点路径 | `JsonPath`（删本地实现） |
| `NftStore.parseString`（:475） | 同款 Gson 点路径 | `JsonPath` |
| `DidSdk.readProfileField`/`readJsonArray` | org.json 防御读取 | 保留（`optJSONObjectSafe` 等，若适用） |
| 明显重复的 `optString(...).orEmpty()` 模式（did/nft/dapp-connect 抽样） | 各处手写 | `optStringSafe`（按重复度收敛，非全量 98 处） |

### 实施顺序
core Json.kt/JsonPath.kt + 单测 → DidSdk/NftStore 点路径收敛 → org.json 重复模式抽样收敛

### 全局验证
`:core:testDebugUnitTest`（JsonPath 纯 JVM 可测）+ did/nft 相关测试 + `ktlintCheckAll`。

**实施记录（2026-08-25，已提交）**：

| 项 | 实际改动 | 与计划的偏差/补充 | 验证 |
| --- | --- | --- | --- |
| core 依赖 | `core/build.gradle.kts` 加 `implementation(libs.gson)` | 方案 A 落地（core 唯一第三方主依赖） | core 编译 |
| `core/json/JsonPath.kt` | `readElement`/`readString`/`readString(default)`——Gson 点路径读取（C-14） | — | 新增 5 单测（点路径/缺失/default/畸形文档/element） |
| `core/json/Json.kt` | `optStringSafe`/`optJSONObjectSafe`（org.json 安全读取，C-4） | org.json 是 Android stub，core 纯 JVM 不可单测——仅薄封装 | 编译 |
| DidSdk | `readElement`/`readString`/`readString(default)` → 委托 `JsonPath`（删本地实现，移除 `JsonParser` import） | 私有函数保留为委托（调用点零改动） | did 167 全绿 |
| NftStore | `parseString` → 委托 `JsonPath.readString`（保留 try/catch fail-safe + CancellationException 重抛） | — | nft 80 全绿 |
| optStringSafe 抽样 | `SwtcNftMetadataParser`（name/description）+ `EvmRpcClient`（result）3 处 `optString(...).takeIf{isNotBlank()}` → `optStringSafe` | 抽样收敛（非全量 98 处——其余为一次性用法） | nft 80 全绿 |

**验证**：core 35（+5 JsonPathTest）/ did 167 / nft 80 全绿 + `ktlintCheckAll` 通过；改动 6 文件 + 2 新文件（Json.kt/JsonPath.kt + 测试）。

---

## 19. P2-4 计划（C-7/C-10 安全工具收敛——设计评审前置）

> 两项都带依赖摩擦（BouncyCastle / Android 依赖），先定决策再实施。

### C-7 EVM 地址（core.crypto.EvmAddress）

**现状**：`ChecksumUtils.toChecksumAddress`（did）依赖 **BouncyCastle Keccak**；调用面**仅 did**（DidSdk/DidCredentialHelper + 测试），无 dapp-connect/account 复用。

**决策（推荐 B）**：
- **校验部分（纯 JVM）**：`0x` + 40 hex 校验 → `core.crypto.EvmAddress.isValidEvmAddress(address): Boolean`，供未来多模块复用。
- **checksum 计算（Keccak）**：**留 did**（BouncyCastle 留在 did deps）——为 did-only 用途让 core 引入第三个依赖（BouncyCastle）不值；core 手写 Keccak-256 有工作量/正确性风险。`toChecksumAddress` 内部校验改用 core 的 `isValidEvmAddress`（部分收敛）。

### C-10 URL/origin 安全工具（core.net）

**现状**：`DAppConnectSdk.isSafeUrl`（android.util.Patterns）、`WebOrigin.normalize`（android.net.Uri）——**依赖 Android**；nft `SsrfGuard`（纯 JVM）。

**决策（推荐 B + SsrfGuard 暂缓）**：
- **`isSafeUrl`/`WebOrigin` 留 dapp-connect**：Android 依赖（android.util/android.net）——core 保持纯 JVM，**core-android 拆分另议**（报告 §4.3 已标注）。纯 JVM regex 重写 `isSafeUrl` 会改变行为（Patterns.WEB_URL 更宽松），不冒险。
- **`SsrfGuard` 上移 core.net**：纯 JVM 本可上移，但 **`enabled` 是 internal**（M-8N 故意禁止运行时关闭）——上移后 nft 测试无法设 `enabled=false`（2 个测试）。**暂缓**，除非改注入机制（测试用 DI 替代 enabled 开关）。

### 收敛范围（推荐版）
1. `core.crypto.EvmAddress.isValidEvmAddress`（纯 JVM）+ did `toChecksumAddress` 内部改用（checksum 留 did）。
2. SsrfGuard / isSafeUrl / WebOrigin 暂不移动（依赖/可见性摩擦，记录在案）。

**决策（2026-08-25）：P2-4 暂缓（不做）**——完整收敛被依赖卡死（checksum 需 BouncyCastle 为 did-only 用途；URL 工具需 Android 依赖违背 core 纯 JVM 约束）；缩减版（isValidEvmAddress / SsrfGuard 上移）均无当前复用（投机代码，违反 Simplicity First）。**C-7/C-10 的 core 收敛挂账**，待 core-android 拆分或 BouncyCastle 决策。优先做必需项：宿主适配（M-21A VaultLocked 编译破坏，见 §19）。

---

## 20. 宿主适配执行计划（M-21A VaultLocked 分支 + §12 收尾）

> 背景：M-21A（§14）给 `AccountOperationError` 加了 `VaultLocked(remainingMs)` 子类型。两个宿主的 `AccountOperationError` 穷举 `when` 无 `else` → **升级 SDK 编译必失败**（已核实：ccdao `toUiMessage`、jdid `mapAddIdentityError`）。需加分支。
> **2026-09-01**：VaultLocked 与 §11 Path/wipe 等适配已在两宿主 commit（见 §26）；§20 下方「回滚后待 commit」记录为历史快照，不再代表当前状态。

### ccdao-connector-android
- **位置**：`model/AccountOperationMapping.kt:6` `fun AccountOperationError.toUiMessage(): UiMessage`（穷举 when 无 else）。
- **改动**：加 `is AccountOperationError.VaultLocked -> UiMessage.fromException(Exception("Vault locked, try again later"))`（或专用 UiMessage；`remainingMs` 可展示锁定倒计时）。
- **验证**：`ModelMappingTest` 加 VaultLocked 用例；编译。

### jdid-android
- **位置**：`viewmodel/identity/support/SubIdentityProvisioner.kt:172` `mapAddIdentityError(error)`（穷举 when 无 else）。
- **改动**：加 `is AccountOperationError.VaultLocked -> "Vault locked, try again later"`（可带 remainingMs）。
- **验证**：编译。

### §11 收尾
- 两宿主工作区 A1-A4 适配 + 本次 VaultLocked 分支**一起 commit**（各自仓库，ccdao 分支 `release` / jdid 分支 `jdid01`）。
- 行为回归：`./gradlew :app:testDebugUnitTest`（local 模式）。

### 验证
两宿主 local 模式编译 + 测试；release 手测（VaultLocked 展示）。

### §19 实施记录（2026-08-26）
- **ccdao**（分支 `release`）：
  - `model/AccountOperationMapping.kt:15-16` 加 `is AccountOperationError.VaultLocked -> UiMessage.fromException(Exception("Vault locked, try again later (${remainingMs / 1000}s)"))`。
  - `ModelMappingTest.kt:86` 加 VaultLocked 映射用例（断言含 "Vault locked"）。
- **jdid**（分支 `jdid01`）：
  - `viewmodel/identity/support/SubIdentityProvisioner.kt:183-184` `mapAddIdentityError` 加 `is AccountOperationError.VaultLocked -> "Vault locked, try again later (${error.remainingMs / 1000}s)"`。
  - `repository/PrimaryWalletRepository.kt:218-223` 另一处穷举 `when`（persistPrimaryHdWalletDefault 的 `AccountOperationResult.Error` 分支）加 `is AccountOperationError.VaultLocked` 分支 → `PersistPrimaryIdentityResult(success=false, errorMessage=...)`。
- **测试**（local 模式全量）：jdid **627 tests / 0 fail**；ccdao **175 tests / 0 fail**。
- **commit 尝试与回滚**：两宿主先行 commit 后（ccdao `90a0fa5` / jdid `075c4dc3`），用户要求**先 review 再提交**，已按指令 `git reset --soft HEAD~1` 回滚两仓库（未 push，无影响）。
  - ⚠️ 发现：ccdao 的 ktlint pre-commit hook 在 "restaging" 阶段会把工作区改动的 `gradle.properties` 一并 `git add`（commit 显示 9 files 而非暂存的 8 files）。**将来再提交 ccdao 时必须处理**：提交前 `git stash` 掉 gradle.properties 改动、提交后再 `git stash pop`；或提交后立即 `git restore --staged gradle.properties`（注意 hook 可能再次拖入）。
  - 回滚后状态：jdid `jdid01` 19 文件暂存、`gradle.properties=local` 未暂存、HEAD 回 `41e07222`；ccdao 的 §11+§19 改动保存在悬空 commit `90a0fa5`（含 `gradle.properties=local`），用户已切到 `fix26` 分支干自己的活，ccdao 适配待用户决定如何恢复。
  - `gradle.properties`（mode=local）是用户刻意改动、用于本地 SDK 影响测试，**不入库**。

---

## 21. dapp-connect 安全收尾批次计划（M-D1 / M-D2 / M-D3 / M-D8）

> 背景：这是报告 §2.2 中危里**从未排入任何批次**的 4 项，全部集中在 dapp-connect 模块。M-D5/6/7（§13）已把该模块的响应队列、速率限制、gas 回退收干净，本批把剩余 WebView 安全面一次收尾。全部为模块内改动，**无宿主 API 变化**（例外：`DAppConnectSdk.isSafeUrl` 是公开方法，见 M-D1 宿主影响提示）。

### M-D1：`isSafeUrl` 兜底接受 `ftp://`/`rtsp://` 等协议（[DAppConnectSdk.kt:183-189](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/DAppConnectSdk.kt#L183-L189)）

- **现状**：`pattern.matches(url) || android.util.Patterns.WEB_URL.matcher(url).matches()` —— 自定义正则只收 `http(s)://` + 带点主机名，但 `WEB_URL` 兜底会接受 `ftp://`、`rtsp://` 等危险 scheme。
- **宿主直调（已核实，3 处）**：`isSafeUrl` 是 `DAppConnectSdk` 公开方法，两宿主直调 3 处，**用途分两类**：
  | 宿主 | 位置 | 用途 | 语义 |
  |---|---|---|---|
  | ccdao | `DiscoverSearchViewModel.kt:62` | 发现搜索 URL 校验 → 门控 `fetchWebsiteMetaSafe`（**Jsoup 抓取**） | **抓取前校验（SSRF 敏感）** |
  | ccdao | `WebsiteMeta.kt:40` | 网站元数据抓取前校验（**Jsoup.connect**） | **抓取前校验（SSRF 敏感）** |
  | jdid | `ExploreScreen.kt:89` | 探索页 URL 校验 → 门控 WebView 导航 | 导航门（scheme 敏感） |
- **改动（仅收紧 scheme，不做整体放宽）**：**保留**自定义严格正则（「至少一个点」语义不变——`192.168.1.1` 这类带点 IP 本就能过，真正挡的是 `localhost` 等单标签主机名）；`WEB_URL` 兜底前加 **scheme ∈ {http, https}** 短路检查：
  ```kotlin
  val scheme = runCatching { java.net.URI(url).scheme?.lowercase(Locale.ROOT) }.getOrNull()
  return pattern.matches(url) ||
      ((scheme == "http" || scheme == "https") && android.util.Patterns.WEB_URL.matcher(url).matches())
  ```
  → 拒绝 `ftp`/`rtsp`/`file`/`javascript`/`data`，**不**改变 `WEB_URL` 现有的主机接受面（不新增 localhost/IP 接受）。**注意**：`scheme` 短路在 `WEB_URL` 调用之前，故 `ftp` 等拒绝用例可在纯 JVM 下测（不需 Robolectric）。
- **⚠️ 宿主侧 SSRF 面（本批不做，另挂宿主跟进项）**：两处抓取门（DiscoverSearchViewModel / WebsiteMeta）**当前**已继承 `WEB_URL` 对内网 IP 的接受（`http://192.168.1.1` 等）——这是**既有**暴露（非 M-D1 引入）。宿主抓取类调用点应独立加「拒绝私网 IP/localhost」校验（如 `InetAddress` 私有段判定），建议作为宿主批次跟进。
- **验证**：`DAppConnectSdkTest` 补 `ftp`/`rtsp`/`file`/`javascript`/`data` 拒绝用例（纯 JVM，因 scheme 短路）；现有接受用例（https/http/端口/路径/query/域名）保持绿。**不改写任何接受语义**——不新增 localhost/IP 接受测试。

### M-D2：`postMessage` 对非法 JSON 无容错（[WebAppInterface.kt:138](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/WebAppInterface.kt#L138)）

- **现状**：origin（H-D1）+ 速率限制（M-D6）检查后 `val obj = JSONObject(json)` 直接解析，非法 JSON 抛异常——`@JavascriptInterface` 方法抛异常无定义行为。速率限制拒绝路径已有 `runCatching`（:129），主解析路径没有。
- **改动**：**解析与字段提取一并包进 `runCatching`**（`JSONObject` + `getString("name"/"network"/"id")` + nonce）；失败记 `Log.w` 并 `return`（解析不出 network/nonce，无法回错误响应，静默丢弃即可）。——覆盖**非法 JSON** 与**合法 JSON 缺必填字段**（`{}`）或字段类型错误两种 bridge 抛异常面。
- **验证**：`WebAppInterfaceSecurityTest` 加非法 JSON 用例（如 `"not json{{{"`）——不抛、被拒。

### M-D3：`postWebMessage` 握手用 `targetOrigin="*"`（[NativeResponseChannel.kt:47-50](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/NativeResponseChannel.kt#L47-L50)）

- **现状**：`install()` 握手把携带 JS 消息端口的 `WebMessage` 以 `targetOrigin="*"` 广播——同 WebView 内**任意 frame** 都能截获原生↔DApp 通道的响应。
- **改动（零宿主影响，不改 `install()` 签名）**：`NativeResponseChannel` 自身持有 `webView`，在 `install()` 的 `runOnMain` 内用 **H-D1 同源推导**：`webView.url?.let { WebOrigin.normalize(it) }` 作为 targetOrigin（如 `https://example.com`），`webView.url` 为 null（页面未加载）时回退 `"*"` 并 `Log.w`。install 正常发生在 provider JS 注入后、页面已加载，正常都有 origin。
  - **为何不改签名**：ccdao 宿主**直接构造** `NativeResponseChannel(webView)` 并在 [WebAppInterface.kt:188](ccdao-connector-android) 调 `install()`（无参）——改签名会编译破坏 ccdao。内部推导保持 `install()` 无参不变，SDK 内 `WebAppInterfaceWithWebView`（:73）与 ccdao 宿主都自动受益。
  - `WebOrigin.normalize` 与 H-D1 `getOrigin()` 逻辑一致（`webView.url` → `scheme://host[:port]`，非 http(s) 返回 null）；`webView.url` 读取已位于 `runOnMain` 内（Android View 属性需主线程）。
- **验证**：`NativeResponseChannelTest`（Robolectric + mock WebView）设 `webView.url` 为 `https://example.com/page`，断言 `postWebMessage` 收到的 targetOrigin 为 `https://example.com`（非 `"*"`）；url 为 null 时回退 `"*"`。

### M-D8：批量交易金额/币种校验不完整（[SwtcBatchTransactions.kt](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/SwtcBatchTransactions.kt) + [SwtcMiddleware.kt:334-336](dapp-connect/src/main/java/com/jccdex/toolkits/dappconnect/middleware/SwtcMiddleware.kt#L334-L336)）

- **现状**：`isValidTransfer` 只做格式校验（正十进制 + currency-issuer 正则 + 地址合法）；`parseTransfers` 的 memo 无长度限制；金额无单笔/总量上限。H-D2 已把批量上限压到 50（`MAX_BATCH_SIZE`，SwtcMiddleware.kt:29）。
- **改动**（在 `SwtcBatchTransactions` companion 加常量，全部可调）：
  1. **单笔金额上限** `MAX_TRANSFER_AMOUNT`（如 `1e12`，默认值实施时定）——`isValidTransfer` 拒绝超限；
  2. **批次总额上限** `MAX_BATCH_TOTAL_AMOUNT`——`SwtcMiddleware.batchTransactions` 汇总 `transfers` 金额后拒绝超限；
  3. **memo 长度** `MAX_MEMO_LENGTH`（如 64 字符，对齐宿主单笔转账或 `@swtc/utils`；无明确基准则设白名单常量）——`parseTransfers` 拒绝超长；
  4. **十进制精度**：`scale ≤ 6` **仅对 native SWT/SWTC（无 issuer）强制**（对齐 `@swtc/utils` 6 位）——非 native token 合法精度可 >6 位，不卡（**决策 2026-08-26 用户拍板：native-only**）。
  5. **issuer 归属**：非 native 币种必须显式带 issuer 且地址合法（已由 `defaultIssuerIfNonNative=false` 强制，现状已覆盖）；「issuer 是否真的发行该 token」需链上查询，超出纯格式校验范围——**文档注明不做**。
- **验证**：`SwtcBatchTransactionsTest` 加金额超限 / 总额超限 / memo 超长 / 精度超 6 用例；`SwtcMiddleware` 现有批量测试保持绿。

### 实施顺序
M-D1（isSafeUrl 收紧，纯 JVM 化）→ M-D2（JSON 容错）→ M-D3（targetOrigin）→ M-D8（金额/memo 校验，最大改动最后）

### 全局验证
`./gradlew :dapp-connect:testDebugUnitTest` + `./gradlew ktlintCheckAll`；`detect_changes` 确认仅影响 dapp-connect。
**宿主影响**：`isSafeUrl` 被两宿主直调 3 处（见 M-D1 表）——本批**不改写其主机接受语义**（仅拒绝非 http(s) scheme），对合法 http(s) 使用零行为变化；宿主抓取门的内网 IP SSRF 为**既有**问题，另挂宿主跟进项。

### §20 实施记录（2026-08-26）
- **M-D1**：`DAppConnectSdk.isSafeUrl` 保留严格正则 + `WEB_URL` 兜底前加 `scheme ∈ {http, https}` 短路（`java.net.URI` 解析）。**不改写任何主机接受语义**。测试：5 个纯 JVM 拒绝用例（ftp/rtsp/file/javascript/data，因 scheme 短路不需 Robolectric）。
- **M-D2**：`WebAppInterface.postMessage` **解析 + 字段提取一并包进 runCatching**（`PostMessageRequest` 私有 data class 承载 method/network/id/nonce/obj，`obj` 供下游 RPC 处理器复用），非法或缺字段 JSON 记 `Log.w` 丢弃。测试：2 个（非法 JSON、`{}`/仅 name 缺字段——**复核发现初版只 guard 解析、`getString("name")` 等仍抛，已补**）。
- **M-D3**：`NativeResponseChannel` 新增 `internal fun resolveTargetOrigin()`（`webView.url?.let { WebOrigin.normalize(it) } ?: "*"`，与 H-D1 同源推导，`"*"` 回退时 `Log.w` 告警），`install()` 用它作 `postWebMessage` 的 targetOrigin。**不改 `install()` 签名**（ccdao 直接无参调用）。测试：3 个 resolveTargetOrigin 用例（https 派生 / url 空回退 "*" / 非 http(s) 回退 "*"）。⚠️ **发现**：mockk 无法拦截 WebView 的 `postWebMessage`（对照实验确认），故不测 mock 上的 postWebMessage 调用，改测推导函数。
  - **⚠️ 宿主回归修正（§10，2026-09-01）**：jdid/ccdao in-app WebView 上严格 origin handshake **静默失败** → DApp 连接挂起。SDK **恢复 v0.3.2 的 `targetOrigin="*"`**；`resolveStrictTargetOrigin()` 保留供测试。H-D1 不在 `WebAppInterfaceWithWebView` 覆盖 `getOrigin()`。jdid 宿主：补 `ChainProvider`、授权前 `installResponseChannel()`、勿重注入 provider JS。详见 [CODE_REVIEW_ANALYSIS.md §10](CODE_REVIEW_ANALYSIS.md#10-实施记录与回归复盘m-d3-nativeresponsechannel--dapp-钱包连接)。
- **M-D8**：`SwtcBatchTransactions` 加 `MAX_TRANSFER_AMOUNT`/`MAX_BATCH_TOTAL_AMOUNT`（1e12，防御性可调）、`MAX_MEMO_LENGTH`（64）、`MAX_AMOUNT_SCALE`（6）；`parseTransfers` 拒超长 memo；`isValidTransfer` 用 `isBoundedPositiveAmount(value, enforceScale)`——**精度 scale≤6 仅对 native SWT/SWTC 强制**（用户决策 2026-08-26，非 native token 不卡精度）；`SwtcMiddleware.batchTransactions` 加批次总额上限。issuer「是否真发行」需链上查询，注明不做。测试：7 个（memo 超长/临界、单笔超限、native 精度超 6、max 边界、批次总额超限、**非 native 高精度接受**）。
- **测试**：`:dapp-connect:testDebugUnitTest` **94 tests / 0 fail**。`ktlintCheckAll` 仍红 = **预存债务**（跟进项 #2，测试源集 255 违规），本批**新增 0 违规**（逐行核对被标记行均为预存代码；NativeResponseChannel.kt 主源集全绿）。
- **跟进项（本批外）**：~~`params.getJSONObject(0)` 形状错误~~ —— **已收口**：WebAppInterface 走 `paramObject`/`requireParamsArray`（`5f8188f`）；`SwtcBatchTransactions.parse*` 与 `SwtcMiddleware.multiSign` 改 `optJSONObject` + `IllegalArgumentException`（不再抛 `JSONException`）。

---

## 22. 未排批中危项收尾批次计划（M-15A / M-22A / M-13A + M-DID5 / M-DID7）

> 背景：报告 §2.2 中危里**从未排入任何批次**、且是 SDK 侧真实缺口的 5 项。P0/P1/§20 已清零，本批把 account/vault/did 剩余正确性与安全项收尾。全部为行为级修复、**不动数据库 schema**；宿主 API 变化点已标注。

### M-15A：未知链码静默回退 `ChainType.ETH`（[AccountEntity.kt:24](account/src/main/java/com/jccdex/toolkits/account/storage/room/AccountEntity.kt#L24)）

- **现状**：`toWalletAccount()` 里 `ChainType.fromBip44Code(chain) ?: ChainType.ETH` —— DB 出现未知 chain 码（数据损坏/未来链）时静默当 ETH 账户，签名/展示可能错误路由资金。
- **实际抛错面（已核实 9 处，`RoomAccountStore`）**：
  - **6 个无 chain 过滤的 Flow/map**（`:23 accounts`、`:32 currentAccount`、`:39 rootHDAccounts`、`:44 subHDAccounts`、`:49 traditionalAccounts`、`:127 getSubAccountsOf`）→ 损坏行以 **Flow error** 浮现（单个损坏行使整个 Flow 终态错误，宿主一个账户都列不出）。
  - **3 个无 chain 过滤的 getter**（`:110 findByAddress(address)` 单参、`:115 findRootAccountByAddress`、`:122 findById`）→ **同步抛异常**（宿主需在 suspend 调用点 catch）。
  - **不抛（chain 过滤）**：`:54 getAccountsByChain`、`:105 findByAddress(address, chain)`、`:120 findNonRootAccount`——SQL 按 chain 过滤，未知链码行到不了 `toWalletAccount`。
  - **M-18A 导入预检**用 `findByAddress(address, chain)`（:105，chain 过滤）→ **不触发**；导入期无异常化。
- **改动**：去掉 `?: ChainType.ETH` 兜底，改为**显式抛 typed 错误** `UnknownChainCodeException(chain)`（含原始码）。
- **设计权衡（已定 2026-08-26：loud-fail 抛错）**：
  - **loud-fail vs per-row skip**：抛错使损坏可观测，但**单个损坏行会让整个 accounts Flow 终态错误 / 5 个 getter 抛异常**——宿主一个账户都列不出来（可用性代价）；对比方案是 per-row skip + 上报损坏地址（可用性优先，但损坏行静默消失、宿主难察觉）。**已选 loud-fail**（符合审查「返回可观测错误」意图；损坏 DB 属严重异常，宁可停而非误路由资金）。
  - **与导入期不对称**：`AccountOrchestrator:134` 仍是 `?: continue`（导入期**静默跳过**未知链）。同一条件两种策略（导入静默/读取抛错）——自洽（导入未知链子账户无资金风险，读取未知链账户有展示/签名风险），写入计划注明。
- **验证**：`AccountEntityTest` 加未知 chain 码 → 抛 `UnknownChainCodeException`；正常链码映射不变；account 全量测试。

### M-22A：`importHdWallet` 中 `keys.add` 先于查重（[AccountOrchestrator.kt:122-142](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L122-L142)）

- **现状**：循环里 `keys.add(VaultPrivateKeyImport(...))`（:124）在 `store.findNonRootAccount(...)` 查重（:126）**之前**——重复子账户的密钥也被加入 `keys` 并在 `vault.importPrivateKeys(keys)`（:152）导入。
- **harm 修正**：重复子账户 = store 已有该地址记录 → 其密钥进 vault 后（vault 已有→幂等过滤 no-op；vault 缺→补上，修复既有缺口）**都不产生「无 store 记录的孤儿密钥」**（store 记录本就存在，这正是「重复」的定义）。但语义上 `continue` 已决定跳过该账户，却仍把密钥导入 vault——**不一致/卫生问题**。
- **改动**：**查重提前到 `keys.add` 之前**（`continue` 时不再入 keys）。目的：**避免导入已决定跳过的重复子账户密钥（语义一致性/卫生）**，而非防孤儿。
- **验证**：`AccountOrchestratorTest` 加重复子账户导入用例——`keys` 不含重复地址（断言 `vault.importPrivateKeys` 收到的集合）；fresh 导入行为不变。

### M-13A：vault 与 store 双写非原子（[AccountOrchestrator.kt:36-49,100-152](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L36)）

- **现状**：`persistVaultMaterial`/`importHdWallet` 先写 vault（`importMnemonic`→`importPrivateKeys`）再 `store.addAccounts`——中途异常/被杀留下孤儿密钥或反之。M-19A 已把 orchestrator 收敛为进程级单例（并发互斥到位，本批不再动）。
- **改动**（受「不动 DB schema」约束，取可行部分）：
  1. **补偿回滚**：`importHdWallet` 的 `store.addAccounts` 抛异常时，尝试 `vault.removeAddress`（已核实存在，:400，**需 password**，importHdWallet 作用域内可用）回滚本次导入的 root + children 密钥——把孤儿窗口从「异常+被杀」缩小到「仅被杀」。`persistVaultMaterial` 同理（store 失败 → 回滚 vault 写入）。
  - ⚠️ **顺序依赖 M-22A（必须先行）**：回滚集 = **M-22A 修复后的 `keys`（不含重复 children）+ root**。`importHdWallet` 只预检 root（M-18A `findByAddress`，:80），**children 无预检**——若 M-22A 未先实现，`keys` 含重复 children（vault 已有其密钥 + store 已有其记录），`removeAddress` 会**误删既有账户的密钥**。root 回滚安全前提：M-18A 预检保证 root 为本调用新导入（已存在 root 提前返回 `AccountAlreadyExists`，不进入写路径）。**实施顺序 M-22A 必须排在 M-13A 之前**（当前实施顺序第 2→第 5 满足）。
  2. **对账 API** `AccountSdk.listOrphanKeys()`：对比 `vault.listAccounts()`（:427）与 store 记录，返回「vault 有密钥但 store 无记录」的孤儿地址（检测工具，宿主可据此清理）。
  - **边界注明**：进程级 killed 窗口的完全原子性需跨存储事务/顺序调整，超出无 schema 变更约束——保留为已知限制。
- **验证**：`AccountOrchestratorTest` 加 store 写入失败 → vault 密钥被回滚的用例；`listOrphanKeys` 单测（构造孤儿态断言返回）。

### M-DID5：校验和/地址转换失败被 `runCatching` 吞掉 → 空 contract 进凭证 ID（[DidCredentialHelper.kt:32,82](did/src/main/java/com/jccdex/toolkits/did/util/DidCredentialHelper.kt#L32) + [DidSdk.kt:1331,1356](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L1331)）

- **现状**：4 处 `runCatching { ChecksumUtils.toChecksumAddress(it) }.getOrNull().orEmpty()` —— 非法地址静默生成**空合约地址**的 VC ID/subject，后续匹配/撤销全部失配（`toChecksumAddress` 对非法输入抛异常，[ChecksumUtils.kt:8](did/src/main/java/com/jccdex/toolkits/did/util/ChecksumUtils.kt#L8)）。
- **改动**：4 处去掉 `runCatching` 兜底，让 `toChecksumAddress` **显式抛错**（`contract != null` 时非法地址 → 抛 `IllegalArgumentException`，凭证生成失败可观测，不再产出空 contract 的坏 VC ID）。`contract == null` 的 `orEmpty()` 保留（语义是「无合约」而非「转换失败」）。
- **列表级中止（已核实）**：`buildAvatarCredential` 是单资产函数，但被 `sourceCandidates.map { ... }`（[DidSdk.kt:212-213](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L212-L213)）**循环调用**——一个非法 contract → 整个 NFT 凭证列表构建中止（不只坏那一项）。
- **设计权衡（已定 2026-08-26：loud-fail 抛错，列表级中止接受）**：DidSdk:1331/:1356 是多资产场景，**一个非法 contract 让整个凭证构建抛错中止**（列表级）。对比方案是 per-asset skip + 日志（可用性优先，坏资产不出凭证、其余正常）。**已选 loud-fail**——非法地址说明数据/调用方有误，宁可中止不产坏 ID；列表级中止在 `sourceCandidates.map` 调用层体现，已接受。
- **验证**：`ChecksumUtilsTest` 已有非法输入用例；补 VC ID 生成用例——非法 contract 抛错、null contract 产出空段；did 全量测试。

### M-DID7：`DidSyncService` 单账户异常中断整批同步（[DidSyncService.kt:11-35](did/src/main/java/com/jccdex/toolkits/did/service/DidSyncService.kt#L11-L35)）

- **现状**：`didSdk.toDid(account)`（:13）在 `runCatching` **之外**——一个损坏账户让**整个** sync 抛异常中止；`resolveDid`（:20）在 runCatching 内失败则**静默丢账户**（无日志无统计）。
- **toDid 抛错面（已核实）**：仅一条真实路径——**EVM 链账户地址非 40-hex**（`toChecksumAddress` 的 require，[DidSdk.kt:73](did/src/main/java/com/jccdex/toolkits/did/sdk/DidSdk.kt#L73)）；SWTC 路径安全；`else -> error("Unsupported chain type")` **不可达**（ChainType 全集 ETH/BSC/POLYGON/ARB1/BASE/MOAC 全 EVM + SWTC）。**触发例 = 损坏的 EVM 地址**（与 M-15A 同根因：数据损坏），非「MOAC 地址」——MOAC 是 EVM 链（evmChainId=99），正常地址本就是 40-hex。
- **改动**：**per-account 隔离**——每账户 `toDid` + `resolveDid` 独立 try/catch（`CancellationException` 重抛），失败记 `Log.w`（含地址 + 原因）并 `continue`；`DidSyncResult`（公共模型，宿主可构造）增加 **`failedCount: Int = 0`**（**带默认值 = source-compatible**，宿主 `DidSyncResult(entries)` 不破；无逐账户明细）。
- **验证**：`DidSyncServiceTest` 加非法账户混在合法账户中的用例——合法账户仍同步、失败账户被记录、不中断；did 全量测试。

### 实施顺序
M-DID5（4 处去 runCatching，最机械）→ M-22A（查重重排）→ M-15A（unknown chain 抛错，已核实 12 调用点的双传播面）→ M-DID7（per-account 隔离）→ M-13A（补偿回滚 + listOrphanKeys，最大改动最后，**依赖 M-22A 已先行**）

### 全局验证
`./gradlew testDebugUnitTest`（全模块）+ `ktlintCheckAll`；`detect_changes` 确认仅影响 account/did 模块。**宿主影响**：M-15A 的 Flow 错误传播（宿主可捕获）、M-13A 的 `listOrphanKeys` 是新 API（不破坏现有）；其余零宿主影响。

### §21 实施记录（2026-08-27）
- **M-DID5**：DidCredentialHelper.kt:32/:82 + DidSdk.kt:1331/:1356 共 4 处去掉 `runCatching` 兜底，非 null 非法 contract 直接 `toChecksumAddress` 抛错；null contract 的 `orEmpty()` 保留。测试 +1（`DidCredentialHelperTest.generateVcId throws for invalid evm contract address`）。
- **M-22A**：`importHdWallet` 循环把 `keys.add` 移到 `findNonRootAccount` 查重之后。现有 `importHdWallet_skipsDuplicateChildren` 断言更新（`importedKeys` 1→0）。
- **M-15A**：新增 `storage/room/UnknownChainCodeException`（含原始 chain 码）；`AccountEntity.toWalletAccount` 去掉 `?: ChainType.ETH`，未知链码抛 `UnknownChainCodeException`。现有 `toWalletAccount_unknownChain_fallsBackToEth` 更新为断言抛错。**loud-fail 决策落地**。**精确爆炸面（9 处，复核修正）**：6 个无 chain 过滤 Flow/map（accounts/currentAccount/rootHDAccounts/subHDAccounts/traditionalAccounts/getSubAccountsOf）+ 3 个无 chain 过滤 getter（findByAddress 单参/findRootAccountByAddress/findById）；chain 过滤的 getAccountsByChain/findByAddress(address,chain)/findNonRootAccount **不触发**（SQL 层过滤）；**M-18A 导入预检（chain 过滤）不触发**。宿主影响仅限损坏行（accounts Flow 终态错误 + 3 getter 同步抛）。
- **M-DID7**：`DidSyncService.syncAccounts` per-account 隔离（每账户 try/catch，`CancellationException` 重抛，失败 `Log.w` + `failedCount++` 后 continue）；`DidSyncResult` 加 `failedCount: Int = 0`（source-compatible）。测试 +1（损坏账户不中止整批）。⚠️ `Log.w` 包 `runCatching`（纯 JVM 测试 `android.util.Log` not mocked）。
- **M-13A**：`importHdWallet` 补偿回滚（`store.addAccounts`/循环查重失败时，`vault.removeAddress` 移除 root + post-M-22A keys 中的 child，best-effort；password 为 null 时跳过——孤儿由 `listOrphanKeys` 检测）；`AccountOrchestrator.listOrphanKeys()` + `AccountSdk.listOrphanKeys(vaultRepository)` 对账 API（vault keys − store 地址）。**listOrphanKeys 用 raw 地址**（新增 `IAccountStore.listAllAddresses()` + DAO `getAllAddresses()`），不经 `toWalletAccount` → 不受 M-15A 损坏行影响（复核发现原 `store.accounts.first()` 在 store 有未知链码行时会抛，已修）。测试 +2（store 提交失败回滚、孤儿检测）+ 1（raw-address 交互）。⚠️ **接口面变化**：`IAccountStore.listAllAddresses()` 是新增**抽象方法**——自定义 IAccountStore 实现者需补（本仓库仅测试 RecordingAccountStore，已补）；宿主走 AccountSdk facade，直接实现该接口的可能性低。与计划「不破坏现有」表述有出入（additive 但非零改动），如实记录。**范围决策**：`importSingleAccount`（persistVaultMaterial 路径）**无 password 参数 → 不做 vault 回滚**，孤儿仅靠 listOrphanKeys 检测（计划「persistVaultMaterial 同理」按此收缩）。
- **测试**：SDK 全量 `test + ktlintCheckAll` **BUILD SUCCESSFUL**；did **169**（+2）/ account **76**（M-22A/M-15A 用例更新 + M-13A 回滚/孤儿 + raw-address 交互测试），其余模块全绿。改动仅 account/did 模块。

---

## 23. webview-bridge 安全收尾批次计划（M-W8 / M-W9 / M-W10）

> 背景：报告 §2.2 中 `webview-bridge` 模块的最后 3 项遗留（M-W8/M-W9/M-W10）。§20 清了 dapp-connect（另一 WebView 面向模块），本批把 `webview-bridge` 自身的 WebView 安全面收尾。全部模块内改动，涉及 `WebviewBridgeClient` 的 WebView 配置/导航边界。

### M-W8：`initialize` 可重复调用但配置被静默忽略（[WebviewBridgeClient.kt:65-71](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt#L65-L71)）

- **现状**：`initialize`（:65-71）只覆盖 `appContext`/`config`，无状态检查；`startInternal`（:128）受 `if (getWebView() != null) return` 保护只建一次。**start 后再次 `initialize` 换配置**：旧 WebView 用旧配置；若 `jsInterfaceName` 改变，`onPageFinished` 探测 JS（:178）引用新名字而接口注册在旧名字下——桥接**静默永久不可用**。
- **改动**：新增 `@Volatile private var started = false`，`startInternal` 建 WebView 后置 true，`destroy()` 置 false；`initialize` 开头 `check(!started) { "WebviewBridgeClient already started; call destroy() before re-initializing" }`（未 start 前多次 initialize 允许，last-wins）。
- **验证**：`WebviewBridgeClientTest` 加用例——start 后 initialize 抛 `IllegalStateException`；未 start 前重复 initialize 不抛。

### M-W9：导航限制是前缀匹配 + 子资源不受控 + bridgeUrl 无校验（[WebviewBridgeClient.kt:153-185](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt#L153-L185) + [WebviewBridgeConfig.kt](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeConfig.kt)）

- **现状**：
  1. `shouldOverrideUrlLoading`（:158）用 `!url.startsWith("file:///android_asset/")` **前缀匹配**——放行任意 asset 路径（`../../`、宿主其他 asset html 共享 JS 接口）。
  2. iframe/子框架与**所有子资源**（`shouldInterceptRequest` 未覆写）不受限。
  3. `bridgeUrl` 可配置为**任意 URL**（`WebviewBridgeConfig` 无校验）——宿主误配 https:// 远程页会拿到 JS 接口。
  4. ⚠️ **发现 latent bug**：默认 `bridgeUrl=androidAssetUrl("bridge.html")` 指向**不存在的 asset**（assets 只有 wallet-bridge.html / did-bridge.html）——用默认配置的桥接永远加载失败（15s awaitReady 超时）。已核实两宿主不直接配 bridgeUrl（走 SDK 内部 wiring）。
- **改动**：
  1. **config 构造期校验**：`WebviewBridgeConfig.init{}` 要求 `bridgeUrl` 为 `file:///android_asset/` + 已知桥接页（允许清单 = `wallet-bridge.html`/`did-bridge.html`）→ 拒绝远程 URL 与未知 asset。**决策点**：默认值 `bridge.html` 指向不存在 asset——建议改为 `wallet-bridge.html`（修 latent bug；两宿主不依赖默认值）；或保持默认但校验放行（挂账 bug）。倾向**改默认值**。
  2. **导航精确匹配**：`shouldOverrideUrlLoading` 仅放行 `url == bridgeUrl`（或允许清单精确匹配），拒绝前缀匹配。
  3. **pageActive 精确匹配（仅 onPageFinished）**：`:175 onPageFinished` 的 `startsWith("file:///android_asset/")` 改为允许清单精确匹配；`:167 onPageStarted` **不改**——它无条件 `pageActive=false` 是正确行为（任何导航开始都应失效，直到 onPageFinished 确认加载到合法页面）。
  4. **子资源白名单**：覆写 `shouldInterceptRequest`——仅放行已知 bridge asset（`bridgeAssetHashes` 的 key + 桥接 html），其余拒绝 + 记日志（防 iframe/子资源加载任意内容）。
- **验证**：`WebviewBridgeEngineTest`/`WebviewBridgeClientTest` 更新（config 校验用例：非法 URL/未知 asset 抛错；导航/子资源拒绝用例）。**受影响现有测试**：`WebviewBridgeEngineTest:34` 用 `custom.html`（非法 asset，按新校验会抛，需更新）；`WebviewBridgeClientBehaviorTest:131` 用 `WebviewBridgeConfig(jsInterfaceName="BridgeJs")`（**默认 bridgeUrl**）——**与默认值决策耦合**：若保持默认 bridge.html + 校验会失败；若改默认值为 wallet-bridge.html 则自动通过。**决策链**：改默认值 → :131 免改；保持默认 → :131 需改配。
- **设计权衡（记录）**：硬编码允许清单 {wallet-bridge.html, did-bridge.html} 意味着**第三方宿主无法用自定义桥接页**（即使 asset 是宿主自己打的）——「安全 vs 灵活性」取舍，当前两宿主不受影响，可接受。

### M-W10：`allowFileAccess=false` 与 `android_asset` 加载的兼容性风险（[WebviewBridgeClient.kt:145](webview-bridge/src/main/java/com/jccdex/toolkits/webviewbridge/WebviewBridgeClient.kt#L145)）

- **现状**：`allowFileAccess = false`（:145）——**若**部分 API level/WebView 实现连 `android_asset` 一起禁止属实，则当前桥接（含 did bridge）在真实设备上**从未加载成功**（不只「兼容性风险」）——每次调用空等 awaitReady 15s 超时且无日志。**严重性理解需确认**：该组合是否在目标设备实测过？若从未成功加载，本项是 P0 级（桥接整体不可用），不只是风险项。
- **改动**：
  1. **改配置组合**（review 推荐标准安全组合）：`allowFileAccess = true` + `allowFileAccessFromFileURLs = false` + `allowUniversalAccessFromFileURLs = false`——确保 asset 加载可靠，同时 JS 不能跨源访问 file://。
  2. **加载失败可观测**：`onReceivedError` 或 `onPageFinished` 检查加载失败 → 记 `Log.w`（含 URL + 错误），不再静默超时。
- **顺序安全**：本批 M-W9 先行（config 校验 + 导航/子资源白名单）约束了 file:// 加载面，M-W10 的 `allowFileAccess=true` 放宽在 M-W9 之后被白名单约束——顺序正确。
- **决策点**：`allowFileAccess=true` 是安全面放宽（虽有两个 file-URL 访问标志兜底）——若用户偏好保持 `false`，则仅加加载失败日志（检测项）不改配置。倾向 **review 推荐的组合 + 日志**。
- **验证**：现有桥接行为测试保持绿；新增加载失败日志用例（mock onReceivedError）。

### 实施顺序
M-W8（状态机守卫，最机械）→ M-W9 config 校验 → M-W9 导航/子资源白名单 → M-W10（配置组合 + 日志）

### 全局验证
`./gradlew :webview-bridge:testDebugUnitTest` + `ktlintCheckAll`。**宿主影响**：两宿主不直接配 bridgeUrl（走 SDK wiring）——M-W9 config 校验 + M-W10 配置组合对宿主**零直接调用影响**；但 M-W9 改默认 bridgeUrl 若宿主误用默认值会受影响（已核实无）。

### §22 实施记录（2026-08-27）
- **M-W8**：新增 `@Volatile started`；`initialize` 在 started 时抛 `IllegalStateException`；`startInternal` 置 true、`destroy` 置 false。**destroy 改为主线程同步**（原 `mainHandler.post` 造成 Robolectric 下 started 残留、真实场景 destroy→initialize 误报——已修）。测试 +1（`initialize_afterStart_throws`）。
- **M-W9**：
  - **config 校验**：`WebviewBridgeConfig.init{}` 要求 `bridgeUrl` 为 `file:///android_asset/` + `{wallet-bridge.html, did-bridge.html}`；**默认值 `bridge.html` → `wallet-bridge.html`**（修 latent bug：旧默认指向不存在 asset，桥接永远加载失败）。测试 +3（远程 URL/未知 asset 拒绝、已知页接受）。
  - **导航精确匹配**：`shouldOverrideUrlLoading` 从 `startsWith` 前缀改 `isAllowedBridgeUrl`（BRIDGE_PAGES 精确匹配）。
  - **pageActive 精确匹配**：仅 `onPageFinished` 的 startsWith → 精确匹配（`onPageStarted` 不改，无条件失效是正确行为）。
  - **子资源白名单**：`shouldInterceptRequest` 仅放行 `bridgeAssetNames`（bridgeAssetHashes keys + BRIDGE_PAGES），其余 403 + 日志。
- **M-W10**：`allowFileAccess=true` + `allowFileAccessFromFileURLs=false` + `allowUniversalAccessFromFileURLs=false`（ensure android_asset 跨 API 加载 + JS 不能跨源 file://）；`onReceivedError` 记 `Log.w`（原静默 15s 超时）。⚠️ `allowFileAccessFromFileURLs`/`allowUniversalAccessFromFileURLs` setter **deprecated**（新 API 上仍可用，warning 不阻断）。
- **受影响测试更新**：`WebviewBridgeEngineTest`（custom.html→did-bridge.html、默认断言→wallet-bridge.html）；`AndroidDidWebRuntimeTest`（file:///test.html→did-bridge.html + androidAssetUrl import）。
- **测试**：SDK 全量 `test + ktlintCheckAll` **BUILD SUCCESSFUL**；webview-bridge **45**（+4）。改动仅 webview-bridge + did 测试（config 默认值跨模块影响 did 一个测试）。

---

## 24. §2.2 最后一批收尾计划（M-W2 / M-W3 / M-W5 / M-10N / M-12N / M-17A）

> 背景：报告 §2.2 中危的**最后 6 项未排批**（apk-verify / app-update / nft / account 四模块）。完成本批即 §2.2 全部中危被处理（修复或明确决策）。含 2 个决策项 + 1 个重新定范围项 + 1 个宿主 API 影响项。

### M-W2：`copyUriToTemp` 无大小上限（[ApkIntegrityVerifier.kt:206-225](apk-verify/src/main/java/com/jccdex/toolkits/apkverify/ApkIntegrityVerifier.kt#L206-L225)）

- **现状**：`input.copyTo(output, BUFFER_SIZE)` 无限流式拷贝 `content://` URI 至 cacheDir——其他应用/远程 provider 可撑爆磁盘（DoS）；调用线程同步执行。
- **改动**：加 `MAX_APK_SIZE`（如 512MB）——手动循环读 buffer 并累计字节数，超限中断返回 `ReadFailed`；IO 线程由 M-W3 的 suspend 化覆盖。
- **验证**：`ApkIntegrityVerifierTest` 加超限中断用例（mock 超长流）。

### M-W3：`verifyApkFile` 同步重 IO + 双解析（[ApkIntegrityVerifier.kt:93-155](apk-verify/src/main/java/com/jccdex/toolkits/apkverify/ApkIntegrityVerifier.kt#L93)）

- **现状**：`verifyApkFile` 是**非 suspend**（:93），拷贝整包 + 两次 `getPackageArchiveInfo` + 全量 SHA-256 全在调用线程（UI 直接 ANR）；versionCode 解析（:112）与 cert 解析（:139-152）对同一临时文件解析两次。
- **改动**：**改 `suspend`** + `withContext(Dispatchers.IO)`；`getPackageArchiveInfo` 一次调用用 `GET_SIGNING_CERTIFICATES` 标志同时取 versionCode + cert；拷贝时流式算哈希（`HttpFetcher.downloadToFile` 已有 onProgress/cancel——或保持现状拷贝+验证分离）。
- **⚠️ 宿主 API 影响**：`verifyApkFile` 变 suspend——ccdao `ApkIntegrityScreen:118`（生产）与 jdid 测试需加协程上下文（ccdao 该调用在 UI screen，通常已在协程内，需核实）。**宿主适配模式同 M-21A/§19**。
- **验证**：现有 `ApkIntegrityVerifierTest` + 宿主测试适配后全绿。

### M-W5：FileProvider 隐性契约 + startInstall 无权限检查（[AppUpdateApkInstaller.kt:171-175](app-update/src/main/java/com/jccdex/toolkits/appupdate/AppUpdateApkInstaller.kt#L171)）

- **⚠️ 重新定范围**：已核实**两宿主都声明了** FileProvider `${applicationId}.fileprovider`（ccdao/jdid manifest:38-39）——「模块自带 provider」会与宿主声明**冲突**（重复 authority → manifest merge 失败/运行时崩溃）。**不做模块级 provider**；当前隐性契约对两宿主已满足。
- **改动**：`startInstall` 先检查 `canRequestPackageInstalls()`——未授权时返回类型化错误（而非安装时抛异常）；已在 `AppUpdateApkInstaller:69` 有 `canRequestInstall(context)` helper，接线即可。
- **验证**：`AppUpdateApkInstallerTest` 加未授权分支用例。

### M-10N：SWTC RPC 节点无 https 强制（[SwtcChainNftClient.kt](nft/src/main/java/com/jccdex/toolkits/nft/remote/SwtcChainNftClient.kt)）

- **现状**：已收敛到 `HttpFetcher`（`httpsOnly=false`、`redirectPolicy=SAME_HOST_HTTPS`、`certificatePins=certificatePins.toSet()`）。默认节点本为 https；`httpsOnly=false` 是**为 MockWebServer http://localhost 测试保留**。SAME_HOST_HTTPS 已防 http→https 降级（重定向目标校验），但直接 http 请求不升级。
- **改动（决策已定 2026-08-27）**：维持 `httpsOnly=false` + SAME_HOST_HTTPS（现状），在 `SwtcChainNftClient` 构造时**校验 nodeUrl 为 https**（拒绝 http 节点配置，生产默认节点即 https）。**防御面**：防「配置误用 http 节点」，非恶意节点（https 节点的 MITM 由证书固定覆盖）。
  - **测试策略（方案 1，已定）**：MockWebServer 用 http://localhost——加 **internal 构造（跳过校验）**供测试使用（`internal constructor(nodeUrl, ..., enforceHttps: Boolean = true)` 或 internal 工厂），生产公开构造默认强制校验。最小侵入、测试面明确。
  - 方案 B（节点 fetcher httpsOnly=true）**不采用**——会误伤合法 http 元数据/图片。
- **验证**：现有 nft 测试改用 internal 构造保持绿；加生产构造 http 节点拒绝用例。

### M-12N：`SsrfGuard` 之外 URL 直接加载面（[NftRemoteAssetResolver.kt:55-60](nft/src/main/java/com/jccdex/toolkits/nft/remote/NftRemoteAssetResolver.kt#L55)）

- **现状**：`isLoadableRemoteAssetUrl` 允许 `http://`、`https://`、`data:`。`http://` 明文加载 NFT 图片可被 MITM 替换（NFT 头像场景诱导误认）；`data:` 无大小限制。
- **改动（决策项）**：`http://` → **拒绝（https only）**（安全正，但**合法 http 图片会加载失败**——权衡：钱包展示面 https-only 更安全）；`data:` → 加大小上限（如 1MB，内联小图不受影响，防 DoS）。**倾向：https only + data 上限**（review 意图）。
- **验证**：`NftRemoteAssetResolverTest` 加 http 拒绝 / data 超限用例。

### M-17A：`clearWalletData` 密码数组被 wipe（[AccountOrchestrator.kt:328-343](account/src/main/java/com/jccdex/toolkits/account/orchestrator/AccountOrchestrator.kt#L328)）

- **现状**：`clearWalletData` 把 `password` 直接传 `vault.clearAllData(password)`（H-R5 会 wipe）——成功后调用方数组被清零，复用即拿到全零数组（KDoc 有警告，API 层脆弱）。`removeAccount` 已用 `password.copyOf()` 规避。
- **改动**：`clearWalletData` 改用 `vault.clearAllData(password.copyOf())`（与 removeAccount 一致）。
- **验证**：`AccountOrchestratorTest` 加用例——clearWalletData 后调用方数组未被 wipe。

### 实施顺序
M-17A（copyOf，最机械）→ M-W2（大小上限）→ M-W5（权限检查，重新定范围）→ M-12N（https only + data 上限，决策）→ M-10N（nodeUrl https 校验，决策）→ M-W3（suspend 化，最大 + 宿主 API 影响，最后）

### 全局验证
`./gradlew testDebugUnitTest` + `ktlintCheckAll`。**宿主影响**：M-W3 的 `verifyApkFile` 变 suspend（ccdao ApkIntegrityScreen:118 + jdid 测试需适配，§19 模式）；M-W5 重新定范围后零宿主影响（FileProvider 已由宿主声明）；**M-12N 经 `NftStore.isSupportedRemoteAssetUrl` 公开行为收紧（http URL true→false，方向正确；两宿主未直调 NftStore、走 NftSdk facade，实际零影响）**；M-10N 私有构造零宿主影响（宿主不引用 SwtcChainNftClient）；其余零宿主影响。

### §23 实施记录（2026-08-27）
- **M-17A**：`clearWalletData` 改 `vault.clearAllData(password.copyOf())`（同 removeAccount 先例，H-R5 wipe 不再清零调用方数组）。测试 +1（`clearWalletData_doesNotWipeCallerPasswordArray`，capture 断言传入副本非同一实例）。**跟进（同 class 未覆盖）**：`importHdWallet` 的 clearExisting 分支 `vault.clearAllData(pwd)`（AccountOrchestrator:93）同样 wipe `clearExistingPassword` 数组——一次性清除凭证、不复用，wipe 影响小，M-17A 范围只覆盖 clearWalletData（可后续对齐 copyOf）。
- **M-W2**：`copyUriToTemp` 手写 buffer 循环 + `MAX_APK_SIZE`（512MB）字节计数中断（含超限/catch/null-input 三路径 temp 清理）；不再用无界 `copyTo`。**测试**：`copyStreamToTemp` internal seam + 小 `maxBytes` 单测（成功 / 超限删 temp）。
- **M-W5**：`startInstall` 改返回 `Boolean`——`canRequestInstall` 未授权返回 false（不再 install 时抛）；source-compatible。**测试**：Robolectric `setCanRequestPackageInstalls(false)` → `startInstall` 返回 false 且不启 Activity。
- **M-12N**：`isLoadableRemoteAssetUrl` **https only**（拒 http，防 NFT 头像 MITM）+ `data:` 1MB 上限。测试 +1（http 拒 / data 超限拒）。
- **M-10N**：`SwtcChainNftClient` **私有主构造 + 公开 `create()` factory（恒强制 https）+ internal `createForTest()` seam**。⚠️ **审核发现并修正**：初版用 `internal val enforceHttps` 构造参数——Kotlin 的 `internal` 只限属性读取、**不限构造调用**，宿主可传 `enforceHttps=false` 绕过。改为私有主构造 + factory 后外部**不可绕过**（仅经 create() 强制校验）。NftStore 改用 `create()`。测试 +2（public factory 拒 http / createForTest 放行 http）。
- **M-W3**：`verifyApkFile` **改 suspend** + `withContext(Dispatchers.IO)`（防 UI ANR）；`getPackageArchiveInfo` **单次调用**带 `GET_SIGNING_CERTIFICATES` 同时取 versionCode + cert（新增 `ApkSigningFingerprint.certSha256FromInfo(info)` 复用，消除双解析）。
- **宿主适配（M-W3）**：✅ 两宿主已适配——ccdao/jdid `ApkIntegrityScreen` 用 `scope.launch` + `withContext(Dispatchers.IO)`；jdid `ApkIntegrityVerifierTest` 用 `runTest`（见 §26 A3）。
- **测试**：SDK 全量 `test + ktlintCheckAll` **BUILD SUCCESSFUL**；account **77**（+1）/ nft **83**（+3），其余模块全绿。改动：account/apk-verify/app-update/nft 四模块。

---

*下一步（2026-09-03 更新）：**P2-8a/8b 已收口**；**跟进项 #2 ktlint 已收口**（`dapp-connect` 纳入 `ktlintCheckAll`）。§26 宿主适配已完成。挂账：#1（H-DID2 重启宽限）、#3（H-DID4-4 Keystore）、P2-4 暂缓。优先：`fix`→main/release + 宿主共享桥 smoke（wallet 派生 / DID 签发 / DApp 签名）。*

---

## 25. P0 修复计划汇总（已完成）

> 来源：[CODE_REVIEW_ANALYSIS.md](CODE_REVIEW_ANALYSIS.md) §7 P0——立即（资金安全/数据不可逆损坏）。本节为 P0 项目的计划与完成状态汇总。

### P0 项目清单与状态

| # | 问题 | 批次 | 状态 | 提交 |
|---|------|------|------|------|
| H-A2 | `importSubAccount` 空私钥入库 | 第 1 波 | ✅ 已完成 | bf6a42a |
| H-A1 | `importHdWallet` 查重提前到清除之前 | 第 1 波 | ✅ 已完成 | bf6a42a |
| H-W2 | 更新校验链证书 fail-closed | app-update 批次 | ✅ 已完成 | 2c58d5b |
| H-W3 | HTTPS 强制 + 同源重定向 | app-update 批次 | ✅ 已完成 | 2c58d5b |
| H-W1 | 桥接回调双向 nonce + 页面来源校验 | 剩余 P0 批次 | ✅ 已完成 | 1dbf08a |
| H-D2 | 批量交易上限 50 笔 | 剩余 P0 批次 | ✅ 已完成 | 1dbf08a |
| H-D1 | `postMessage` 实时校验 origin | 剩余 P0 批次 | ✅ 已完成 | 1dbf08a |
| H-DID2 | DID 创建保护（宽限期） | 剩余 P0 批次 | ✅ 已完成 | 1dbf08a |
| H-DID3 | DID 删除后链上旧文档不复活 | 剩余 P0 批次 | ✅ 已完成 | 1dbf08a |
| H-W4 | 自校验信任根循环 | 剩余高危批次 | ✅ 已完成 | 87d7f9c |
| H-DID4 | 私钥经 WebView 传递（加固项） | 剩余高危批次 | ✅ 部分完成 | cf13cdb |

### H-DID4 加固项明细

| 子项 | 内容 | 状态 |
|------|------|------|
| H-DID4-1 | `DidSdk` KDoc 声明「私钥进入 WebView 进程」安全边界 | ✅ 完成 |
| H-DID4-2 | WebView 显式 `setWebContentsDebuggingEnabled(false)` | ✅ 完成 |
| H-DID4-3 | asset JS SHA-256 完整性自检（9 个文件） | ✅ 完成 |
| H-DID4-4 | 私钥签名移出 WebView（Keystore） | ❌ 放弃（架构项，需设计评审） |

### 后续优先级

**P1 已全部完成**：M-1（取消传播）、M-W1（恒定时间比较）、B-4（locale 归一）、M-D5/6/7（dapp-connect 安全）、M-18A/19A/21A（account 安全）、M-4/M-D4（逐笔签名确认）。

**P2 架构批次**：
- P2-5：C-9/C-12 统一异常体系 + RPC 错误码 ✅
- P2-6：C-14/C-15/C-16/C-21 DID/NFT 解析收敛 ✅
- P2-7：C-22「非根账户」判定单一来源 ✅
- P2-8：X-1/X-2/X-4/C-24 桥接运行时合并 ✅（P2-8a `4d2311f` + P2-8b `42a5f32`）
- P2-4：C-7/C-10 安全工具 core 收敛 — **暂缓**（见 §19）

**跟进项**：
- #1 H-DID2 重启场景（进程重启后宽限期失效，需持久化但不动 DB）
- ~~#2 dapp-connect 预存 ktlint 债~~ ✅（纳入 `ktlintCheckAll`，2026-09-03）
- #3 H-DID4-4 签名移出 WebView（方案 A 长期规划）

### 宿主适配状态（2026-09-01）

| App | 项目 | 来源 | 状态 | 提交/说明 |
|-----|------|------|------|-----------|
| ccdao | `TransactionConfirmCallback` 确认 UI | §9 M-4/M-D4 | ✅ | `cecf940`（fix26） |
| jdid | `TransactionConfirmCallback` 确认 UI | §9 M-4/M-D4 | ➖ N/A | **非交易类 App**；探索页 DApp 仅 `requestAccounts`，不注入时 sign/send/batch fail-closed 为预期 |
| ccdao | `AccountOperationError.VaultLocked` 分支 | §15 M-21A | ✅ | `8ac598b`（fix26） |
| jdid | `AccountOperationError.VaultLocked` 分支 | §15 M-21A | ✅ | `c03f2b92` |
| ccdao | `verifyApkFile` suspend 化适配 | §24 M-W3 | ✅ | `ApkIntegrityScreen` + `scope.launch` |
| jdid | `verifyApkFile` suspend 化适配 | §24 M-W3 | ✅ | 同上 + `ApkIntegrityVerifierTest` `runTest` |
| ccdao | DApp：`ChainProvider` + 授权前 `installResponseChannel()` | §10 M-D3 / M-D5 | ✅ | `12973cc` |
| jdid | DApp：`ChainProvider` + 授权前 `installResponseChannel()` | §10 M-D3 / M-D5 | ✅ | `87138bab` |

---

## 26. 跨仓适配清单与实施计划（ccdao-connector-android / jdid-android）

> 来源：SDK 升级后的破坏性 API 变更。本节汇总需在宿主仓库执行的适配项。
> 前置条件：SDK 已提交 M-4/M-D4（7b3d8b1）、M-21A（6ff30c3，§15）、M-W3（a5b1b69，§24）。
> **状态同步**：2026-09-01——**两宿主适配项均已完成**（见下方宿主画像：jdid 不适用 A1）。

### 宿主画像（适配范围）

| 宿主 | 产品定位 | 须实现的 SDK 钩子 | 明确**不需要** |
|------|----------|-------------------|----------------|
| **ccdao-connector-android** | 交易 / 连接器 | A1–A4 全部 | — |
| **jdid-android** | 身份 / 探索（**非交易类**） | A2、A3、A4 | **A1 `TransactionConfirmCallback`**（DApp 仅连接钱包；签名/转账 RPC 未注入回调时 SDK 拒绝，符合产品预期） |

### 适配清单

| # | 项目 | 优先级 | 来源 | ccdao | jdid |
|---|------|--------|------|-------|------|
| A1 | TransactionConfirmCallback 确认 UI | ccdao **必须** / jdid **N/A** | §9 M-4/M-D4 | ✅ `cecf940` | ➖ 不适用 |
| A2 | VaultLocked 分支 | 必须 | §15 M-21A | ✅ `8ac598b` | ✅ `c03f2b92` |
| A3 | verifyApkFile suspend 化 | 必须 | §24 M-W3 | ✅ | ✅ |
| A4 | DApp：`ChainProvider` + 授权前 `installResponseChannel()` | 必须 | §10 M-D3 / M-D5 | ✅ `12973cc` | ✅ `87138bab` |

---

### A1. TransactionConfirmCallback 确认 UI（M-4/M-D4）

> **宿主范围**：**ccdao 必须**；**jdid 不适用**（非交易类，见上方宿主画像）。下文步骤仅针对交易类宿主。

**背景**：SDK 新增 `TransactionConfirmCallback` 接口，未设置时签名/转账类 RPC 拒绝执行。

**破坏性变更**：
- `IEthMiddleware.setTransactionConfirmCallback(callback)`
- `ISwtcMiddleware.setTransactionConfirmCallback(callback)`
- 未设置时抛 `UserRejectedException`

**影响方法**：
- EVM：`personalSign`/`signTypedData`/`getEncryptionPublicKey`/`decrypt`/`signTransaction`/`sendTransaction`
- SWTC：`sendTransaction`/`signMessage`/`batchTransactions`

**适配步骤**：

1. **创建回调实现**：
   ```kotlin
   class TransactionConfirmCallbackImpl(
       private val context: Context
   ) : TransactionConfirmCallback {
       override suspend fun onConfirm(request: TransactionRequest): Boolean {
           return when (request) {
               is TransactionRequest.SendTransaction -> showTransactionConfirmDialog(request)
               is TransactionRequest.SignMessage -> showSignMessageDialog(request)
               is TransactionRequest.SignTypedData -> showSignTypedDataDialog(request)
               is TransactionRequest.Decrypt -> showDecryptConfirmDialog(request)
               is TransactionRequest.GetEncryptionPublicKey -> showPublicKeyConfirmDialog(request)
               is TransactionRequest.SwtcBatchTransaction -> showBatchConfirmDialog(request)
           }
       }
   }
   ```

2. **确认 UI 要素（EVM）**：
   - DApp origin（来源）
   - 接收地址
   - 金额
   - Gas 费估算
   - 合约交互数据
   - 风险提示（未知合约/大额转账）

3. **确认 UI 要素（SWTC）**：
   - DApp origin
   - 批量条数（上限 50 笔已强制）
   - 总金额
   - 代币类型

4. **注入回调**：
   ```kotlin
   // 在 DAppConnectSdk 初始化时
   DAppConnectSdk.setTransactionConfirmCallback(TransactionConfirmCallbackImpl(context))
   ```

**位置**：
- ccdao：`WebviewScreen.kt` + `TransactionConfirmDialog.kt`（`cecf940`）
- jdid：**无需实现**

**验证**：
- ccdao：DApp 发起签名/转账时弹出确认对话框；用户确认后交易执行，拒绝后返回错误
- jdid：探索页 DApp `swtc_requestAccounts` 连接成功即可；若 DApp 误调 sign/send，应收到 SDK 拒绝（预期）

---

### A2. VaultLocked 分支（M-21A）

> 详细实施步骤与已回滚的提交 SHA 见 **§20 宿主适配执行计划**。

---

### A3. verifyApkFile suspend 化适配（M-W3）

**背景**：SDK `verifyApkFile` 从同步方法改为 `suspend` 函数。

**破坏性变更**：
- 签名：`fun verifyApkFile(...): ApkVerificationResult` → `suspend fun verifyApkFile(...): ApkVerificationResult`
- 调用点需协程上下文

**适配步骤**：

**ccdao**：
- 位置：`ApkIntegrityScreen.kt:118`（生产）
- 改动：调用点改用协程
  ```kotlin
  // 原：val result = ApkIntegrityVerifier.verifyApkFile(...)
  // 改：
  lifecycleScope.launch {
      val result = ApkIntegrityVerifier.verifyApkFile(...)
      // 处理结果
  }
  ```

**jdid**：
- 位置：`ApkIntegrityVerifierTest.kt`（测试）
- 改动：测试改用 `runTest`
  ```kotlin
  @Test
  fun test() = runTest {
      val result = ApkIntegrityVerifier.verifyApkFile(...)
  }
  ```

**验证**：
- 编译通过
- 测试通过
- 生产环境 APK 校验正常

---

### 实施顺序

1. ~~A1（TransactionConfirmCallback）~~ — ccdao ✅；jdid ➖ N/A
2. ~~A2（VaultLocked）~~ — ✅
3. ~~A3（verifyApkFile）~~ — ✅
4. ~~A4（ChainProvider + installResponseChannel）~~ — ✅

**跨仓宿主适配：已全部完成**（2026-09-01）。

### 全局验证

- 两宿主 local 模式编译 + 测试通过
- release 构建成功
- 关键行为验证：
  - DApp 签名/转账确认 UI 正常
  - Vault 锁定展示倒计时
  - APK 校验功能正常

---

## 28. SDK 回归修复记录（2026-09-01）

> 本地联调（`jccdex.toolkits.mode=local`）期间发现的问题与修复；均已提交 `fix` 分支。

| 提交 | 问题 | 修复 |
|------|------|------|
| `c37c259` | HttpFetcher `postJson` 在设置 POST body 前读 `responseCode`，EVM NFT `tokenURI` RPC 静默失败 | 独立 POST 路径，先写 body 再读响应 |
| `7b2606d` | M-D3 严格 `targetOrigin` 导致部分 WebView 宿主 WebMessagePort 握手失败 | 恢复 `"*"` 握手；宿主须在授权前 `installResponseChannel()` |
| `a59e17f` | `batchTransactions` 先查 accounts 再弹确认，DApp 确认框延迟/竞态 | 确认回调提前；`getAccountByAddress` 后置 |

**宿主侧（非本仓，见 §26）**：ccdao `cecf940` 稳定 Compose 确认弹窗（Mutex 排队 + SideEffect 接线）。

---

## 27. 低危项修复记录与计划

> 本节记录已完成的低危项修复及待实施计划。

### 已完成项目（提交 4d9bb77）

| # | 项目 | 模块 | 状态 |
|---|------|------|------|
| L-1 | getBiometric 异常类型改进（Error → IllegalStateException） | vault | ✅ 已完成 |
| L-2 | Argon2idKdf 密钥派生注释（标注需迁移策略） | vault | ✅ 已完成（注释） |
| L-3 | 异常捕获精确化（Throwable → GeneralSecurityException/Exception） | vault | ✅ 已完成 |
| L-9 | 移除 onConsoleMessage 死代码（if (false)） | webview-bridge | ✅ 已完成 |
| L-11 | 日志记录增强（空 catch 块添加日志） | did | ✅ 已完成 |
| L-12 | 时间戳比较修复（字符串 → Instant.parse） | did | ✅ 已完成 |
| L-13 | credentialId 大小写比较统一（ignoreCase = true） | did | ✅ 已完成 |
| L-15 | JSON 编码防注入注释 | webview-bridge | ✅ 已完成 |
| L-16 | parseBooleanOrThrow 方法（替代 .toBoolean()） | wallet | ✅ 已完成 |
| L-19 | BIP-39 助记词长度验证（128/160/192/224/256） | wallet | ✅ 已完成 |

### 已完成项目（本次提交）

| # | 项目 | 模块 | 状态 |
|---|------|------|------|
| L-8 | ConcurrentHashMap 内存泄漏修复（PendingEntry + cleanupStaleEntries） | did | ✅ 已完成 |
| L-10 | WebView attach/detach 生命周期钩子 | webview-bridge | ✅ 已完成 |
| L-17 | APK 多签名者支持（allCertSha256FromInfo + matchesAnySigner） | apk-verify | ✅ 已完成 |

**实施记录**：
- **L-8**：新增 `PendingEntry` 数据类（值 + 时间戳）+ `cleanupStaleEntries()` 方法（清理超过 1 小时的条目）
- **L-10**：新增 `attach(Activity)` / `detach()` 生命周期钩子（可选，供宿主使用）
- **L-17**：新增 `allCertSha256FromInfo()` / `matchesAnySigner()` 方法 + 多签名者警告日志

---

### 待实施项目

（无）

---

### L-8. ConcurrentHashMap 内存泄漏（did）

**背景**：`DidCoreService` 中 `pendingDeleteUpdated` / `pendingUpdateAvatar` / `pendingUpdateNickname` 等 `ConcurrentHashMap` 只增不减（特定路径移除），长生命周期内可能缓慢增长。

**位置**：`did/.../service/DidCoreService.kt:23-26`

**问题分析**：
- `pendingCreateDids`：创建后首次 resolve 成功时移除（正常）
- `pendingDeleteUpdated`：删除后链上版本匹配时移除（正常）
- `pendingUpdateAvatar` / `pendingUpdateNickname`：更新成功后移除（正常）
- 但若操作中途失败或异常退出，条目可能残留

**修复方案**：

1. **方案 A（推荐）**：添加定期清理机制
   - 在 `DidCoreService` 中添加清理方法，定期检查并移除过期条目
   - 条目附加时间戳，超过阈值（如 1 小时）自动清理

2. **方案 B**：改用 `WeakHashMap`
   - 键为 DID 字符串，当 DID 被其他地方释放时自动清理
   - 但 DID 字符串通常是强引用，效果有限

**实施步骤**：
1. 为每个 pending map 添加 `ConcurrentHashMap<String, PendingEntry>`
   ```kotlin
   data class PendingEntry(val value: String, val createdAt: Long)
   ```
2. 添加 `cleanupStaleEntries()` 方法，清理超过 1 小时的条目
3. 在 `resolveAndSaveDid` 开始时调用清理方法
4. 添加单元测试验证清理逻辑

**验证**：
- 编译通过
- 测试通过
- 长时间运行后内存稳定

---

### L-10. WebView applicationContext 反模式（webview-bridge）

**背景**：`WebviewBridgeClient` 使用 `applicationContext` 创建 WebView，部分机型上 WebView 需绑定 Activity 生命周期才能正确释放。

**位置**：`webview-bridge/.../WebviewBridgeClient.kt:77,160`

**问题分析**：
- 当前实现：`appContext = context.applicationContext`
- WebView 持有 ApplicationContext 会导致组件泄漏
- 部分机型 WebView 需绑定 Activity context 才能正确释放

**修复方案**：

1. **方案 A（推荐）**：由宿主传入 Activity context
   - `initialize()` 接受 Activity context
   - 在 `destroy()` 中显式清理

2. **方案 B**：添加生命周期钩子
   - 提供 `attach(Activity)` / `detach()` 方法
   - 宿主在 Activity 生命周期中调用

**实施步骤**：
1. 修改 `initialize()` 方法，添加 `requireActivity` 参数或文档说明
2. 或添加 `attach(Activity)` / `detach()` 生命周期钩子
3. 在 `destroy()` 中确保 WebView 完全释放
4. 添加文档说明生命周期管理要求

**风险**：
- 需要宿主侧配合改动
- 可能影响现有调用方

**验证**：
- 编译通过
- 测试通过
- Activity 泄漏检测工具验证

---

### L-17. APK 多签名者仅取 signers[0]（apk-verify）

**背景**：`ApkSigningFingerprint` 仅取 `signers[0]`，多签名者 APK（证书轮换/双签）若官方证书非第一个会误判。

**位置**：`apk-verify/.../ApkSigningFingerprint.kt:27,50`

**问题分析**：
- 当前实现：仅检查第一个签名者
- 多签名 APK（如证书轮换、双签场景）可能误判
- 未对 `hasMultipleSigners` 告警

**修复方案**：

1. **方案 A（推荐）**：检查所有签名者
   ```kotlin
   fun verifyFingerprint(signers: Array<Signature>, expectedFingerprints: Set<String>): Boolean {
       return signers.any { signer ->
           expectedFingerprints.contains(computeFingerprint(signer))
       }
   }
   ```

2. **方案 B**：对多签名者告警
   - 检测 `signers.size > 1` 时输出警告日志
   - 仍只验证第一个签名者（保持兼容）

**实施步骤**：
1. 修改 `ApkSigningFingerprint` 以支持多签名者
2. `expectedFingerprint` 参数改为 `Set<String>` 或保持单个但遍历所有签名者
3. 添加多签名者告警日志
4. 添加单元测试覆盖多签名者场景

**验证**：
- 编译通过
- 测试通过
- 多签名 APK 校验正确

---

### 实施顺序

1. **L-8**（内存泄漏）- 低风险，可独立实施
2. **L-17**（APK 多签名）- 低风险，可独立实施
3. **L-10**（WebView context）- 需评估对宿主的影响

### 全局验证

- 全量测试通过
- ktlint 检查通过
- 内存泄漏检测（L-8）
- Activity 泄漏检测（L-10）
- 多签名 APK 校验（L-17）
