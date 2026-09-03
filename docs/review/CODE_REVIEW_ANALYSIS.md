# kotlin-toolkits 代码审查报告（安全 / 性能 / 架构收敛 / Kotlin 最佳实践）

> 审查日期：2026-08-25  
> **状态同步**：2026-09-01（§10–§11 宿主适配完成度、M-4/M-D4 实施状态、jdid 非交易类宿主范围）  
> 审查范围：`core`、`account`、`vault`、`webview-bridge`、`wallet`、`did`、`nft`、`dapp-connect`、`apk-verify`、`app-update` 十个模块的 `src/main` 全部 Kotlin 源码（约 8000 行手写代码，不含 KSP/Room 生成代码）。
> 说明：本文档聚焦「代码漏洞」「性能优化」「共通代码收敛到 core」「Kotlin 最佳实践」四个维度；与既有 `docs/SECURITY_AUDIT.md` / `SECURITY_REAUDIT_FIX_PLAN.md` 的发现相互参照（标注「既有审计已覆盖」），避免重复造轮子。

---

## 1. 执行摘要

| 维度 | 结论 |
| --- | --- |
| 安全 | 整体安全意识强（Tink + Argon2id + AAD + 密码 wipe + 锁定策略 + SSRF 防护 + origin 校验均有落地），但发现 **12 个高危**（部分同根）：① 账户导入链：清库后查重失效、静默清库无报错（H-A1）、空私钥入库锁死真实私钥（H-A2）；② 桥接层：`postMessage` 信任边界缺失 + 私钥缓存窗（H-D1）、桥接回调可被页面 JS 伪造签名结果（H-W1）、私钥经 WebView 以不可擦除 String 传递（H-DID4）；③ 签名/批量：DApp 盲签凭证（H-DID1）、批量交易无上限（H-D2）；④ DID 缓存一致性：创建保护标志一次性失效导致文档误删（H-DID2）、删除后被链上旧文档复活（H-DID3）；⑤ 更新链路：证书校验 fail-open（H-W2）、无 HTTPS 强制/重定向降级（H-W3）、自校验信任根循环（H-W4）。另有中危若干：协程取消被吞、HTTP 无大小上限、`sendTransaction` 无逐笔确认、JNI 校验静默降级、SSRF 解析结果直返等 |
| 性能 | 主要问题集中在：HTTP 响应无大小上限（OOM 风险）、`CoroutineScope(...).launch` 大量 fire-and-forget、循环内 `Regex` 编译、Room 查询可合并、hex 编码低效 |
| 架构收敛 | **存在明确可收敛项**：`wallet.model.Path` 与 `core.model.Path` 完全重复；6 处几乎相同的 `HttpURLConnection` fetch 实现；2 套手写 hex 编码；JSON 处理在 org.json 与 Gson 之间混用；Room「Database 单例 + DAO + Store」样板在 account/did/nft 三模块重复；`ByteArray.wipe` 等安全工具仅存在于 vault 模块 |
| 最佳实践 | 命名总体清晰（动词+宾语、语义明确），但存在 `!!`、`catch (e: Exception)` 吞取消、`runCatching` 吞 `CancellationException`、魔法数字、locale 敏感 `lowercase()`、误导性参数（`sendTransactionWithPassword` 的 `password` 参数未被使用）等问题 |

---

## 2. 代码漏洞与安全隐患

> 严重程度分级：🔴 高危（直接导致资金/密钥/数据泄露或提权）、🟠 中危（特定条件下可利用或可造成 DoS/数据损坏）、🟡 低危（防御性缺口/代码异味）。

### 2.1 🔴 高危

#### H-W1：桥接回调可被页面内任意 JS 伪造——伪造签名/地址结果「先到先得」

- **位置**：`webview-bridge/.../JsPromiseGateway.kt:36-41`（`onPromiseResult(id, resultJson)` 直接 `callbackMap.remove(id)?.invoke(resultJson)`）、`WebviewBridgeClient.kt:211-232`（UUID 对页面 JS 可见，无页面来源/身份校验、无双向 nonce、`onPromiseResult` 不校验当前 URL）。
- **问题**：页面内任意脚本可抢先调用 `JSBridge.onPromiseResult(uuid, '{"result":"<伪造签名/地址>"}')`——`callbackMap.remove` 原子，伪造结果先到先得，原生侧直接 `cont.resume` 信任。对钱包场景等价于**伪造签名/伪造地址**（配合 H-DID4 的密钥路径，是桥接层最高风险）。
- **修复**：① 每次调用生成**双向 nonce**（调用参数与回调返回值均携带），原生校验且回调仅接受一次；② `onPromiseResult` 内校验 WebView 当前 URL 仍为预期的 `file:///android_asset/...` 桥页（或维护页面状态标志）；③ 原生侧对返回结果做结构/长度白名单校验。

#### H-W2：更新校验链 fail-open——证书提取失败/校验异常即放行（更新功能可被劫持）

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:92-104`。
- **问题**：① `archiveCertSha256(...).orEmpty()` 提取失败得空串 → `actualCert.isNotBlank()` 为 false → **检查被静默跳过而非拒绝**；② 整个块 `catch (_: Exception)` 吞掉一切异常放行。checksums 文件（信任锚）仅靠 TLS（见 H-W3），证书检查是防恶意服务器的最后防线却 fail-open。
- **修复**：fail-closed——`expectedCert` 非空时提取失败必须返回 `Failed`；移除宽泛 catch。

#### H-W3：更新链路无 HTTPS 强制、重定向可降级 http，checksums 信任锚仅靠 TLS

- **位置**：`app-update/.../AppUpdateChecker.kt:50-65`、`AppUpdateApkInstaller.kt:52-61`（`instanceFollowRedirects = true`，scheme 无校验）。
- **问题**：`HttpURLConnection` 跟随跨 scheme 重定向（https→http；Android API 28+ 默认 cleartext 拦截会缓解该降级，但 `http://` 直连或显式开启 cleartext 时风险仍在）；调用方误传 `http://` 时 checksums 文件（含 `apkSha256`/`signingCertSha256`）与 APK 均可被 MITM 整体替换；checksums 文件本身无签名/公钥验证。
- **修复**：强制 `https://` + 校验 host；重定向仅允许同 host https→https；生产环境对 checksums 做签名或 TLS 证书固定。

#### H-W4：自校验信任根循环——被校验 APK 的「官方 manifest」位于该 APK 自身 assets

- **位置**：`apk-verify/.../ApkIntegrityVerifier.kt:99-115`（`verifyInstalledPackage` 读取 `OfficialReleaseManifestLoader` 校验的正是 `context.packageName` 自身）。
- **问题**：重打包者修改 assets 中 `official_release_manifest.json` 的 `signingCertSha256` 即可让校验恒过；`JniVerifier` 的 native 库与校验代码也在同一 APK 内（经典「自验证」弱点）。且 `verifyInstalledPackage` 从不校验已装 APK 哈希（永远不返回 `PassedFull`）；`skipSignatureCheck=true`（`BuildConfig.DEBUG` 建议）误传即完全失效。
- **修复**：明确该检查定位为「防误装提示」；真实信任根外置（服务端 API + Play Integrity，或签名公钥硬编码 native 层交叉校验）；`skipSignatureCheck` 改显式枚举，release 禁止跳过。

#### H-DID1：`signCredentialForDApp` 对 DApp 载荷仅做结构校验后即用钱包私钥盲签

- **位置**：`did/.../sdk/DidSdk.kt:280-300`（校验仅三项：`@context`/`type`、`credentialSubject`、`issuer`/`issuerObject` 存在性）。
- **问题**：SDK 不校验 `issuer` 是否等于钱包 DID、不校验 subject 归属、不校验 `usageRights`/`restrictions` 语义，也无任何库内确认回调（注释声明「宿主必须自行确认」，纯靠宿主自觉）。恶意 DApp 可诱导钱包签署「将用户 NFT 使用权授予攻击者」的授权凭证或含虚假声明的凭证。
- **修复**：SDK 内增加强制的确认回调（不传则拒绝签名）；校验 issuer 与钱包 DID 一致；对 subject/usageRights 做语义校验。

#### H-DID2：新建 DID 的本地保护是一次性内存标志——链上传播延迟期间本地文档可能被误删

- **位置**：`did/.../service/DidCoreService.kt:16-19,77-87,140`（`pendingCreateDids` 在第一次链上解析返回「无文档」时即被消费移除，之后再次 resolve 且链上未传播时 `store.delete(did)` 删除本地刚创建的文档）；标志纯内存，进程重启即失效。
- **触发**：创建 DID → publish 成功 → 链上可解析前（IPFS 钉扎/索引延迟可达分钟级）触发任意 resolve 两次。
- **修复**：改为基于时间的宽限期（创建后 N 分钟内缺失视为「传播中」），并把 pending 状态持久化（Room/DataStore）。

#### H-DID3：DID 删除后，链上旧文档会把本地「已删除」状态复活

- **位置**：`did/.../service/DidCoreService.kt:27-49` 与 `sdk/DidSdk.kt:586-604`。
- **问题**：`pendingDeleteUpdated` 保护分支只在 `localDoc != null` 时生效（`:44-48`）；删除后 localDoc 为 null，链上仍返回旧文档（删除未传播）时走 `:36-39` 的 `store.upsert` 把已删文档写回本地；`pendingDeleteUpdated` 条目从此滞留（内存泄漏）。
- **修复**：删除待确认期（链上 updated == 已删除时间戳）内禁止用链上旧文档回填；pendingDelete 检查在 localDoc 为 null 时也生效。

#### H-DID4：私钥经 WebView JS 桥全程以不可擦除 String 传递（架构性风险，既有审计 C-04 的完整面）

- **位置**：`did/.../sdk/DidSdk.kt:260-300,305-331,333-441,971-1039`（所有签名/发布路径 `put("privateKey", privateKey)`）；`webview-bridge/.../WebviewBridgeClient.kt:231-242`。
- **问题**：私钥同时存在于 Kotlin String（不可 wipe）、中间 JSON 字符串、JS 引擎字符串堆三处。WebView 内核一旦被利用（历史多个 WebView RCE/信息泄露 CVE）或调试构建误开 `setWebContentsDebuggingEnabled(true)`，私钥可被提取。
- **修复**：① 尽量由宿主在 Keystore/安全元件完成签名，JS 只收结果；② Kotlin API 至少改 `CharArray` 并置零；③ 显式关闭 WebView 调试、对 asset JS 做 SHA-256 完整性自检；④ 文档明确「私钥进入 WebView 进程」的安全边界。

#### H-A1：`AccountOrchestrator.importHdWallet` 先清库、后查重——重复导入会不可逆清除既有钱包

- **位置**：`account/.../orchestrator/AccountOrchestrator.kt:69-89`。
- **逻辑**：`if (clearExisting) { vault.clearAllData(pwd); store.clearAllAccounts() }` 在 `if (store.findRootAccountByAddress(hdResult.address) != null) { return Error(AccountAlreadyExists) }` **之前**执行。
- **问题**：宿主以 `clearExisting=true` 重导同一助记词（用户忘记已导入、或 UI 文案误导）时，**先清空 vault 与账户表**——且 `store.clearAllAccounts()` 已清空账户表后，`findRootAccountByAddress` 恒返回 null，**重复检查永不触发**：实际是**静默清库重导、连错误都不报**（并非「再发现重复并返回错误」），既有钱包数据被不可逆销毁。
- **修复**：把「查重」提前到任何清除动作之前；`clearExisting` 语义改为「查重通过后才清除」，并在 KDoc 中显式警告该参数会销毁数据（当前 KDoc 只解释了密码数组的 wipe 副作用，未强调数据销毁风险）。

#### H-A2：`AccountOrchestrator.importSubAccount` 以空字符串私钥写入 vault，可永久锁死该地址的真实私钥导入

- **位置**：`account/.../orchestrator/AccountOrchestrator.kt:155-178`（`importSubAccount` 构造 `Keypair(privateKey = "", ...)`）→ `:278-304`（`persistVaultMaterial` 走 `else` 分支）→ `vault.importPrivateKey(address, "".toByteArray())` → `vault/.../VaultRepository.kt:687-716`（`lockedImportPrivateKey` 以 `addressInKeys` 短路）。
- **问题**：子账户（HD 派生子账户）导入时**没有真实私钥**（应从根助记词派生），却把**空字节数组**当私钥加密写入 vault。之后：① 该地址在 vault 中「已存在密钥」，`addressInKeys(address)` 恒真，后续任何真实私钥导入被静默跳过（`privateKey.wipe(); return`）；② 该地址的 `getPrivateKey` 只能解密出空数组——**私钥永不可用，资产无法动用**。这是资金可用性级别的缺陷。
- **修复**：`importSubAccount` 不应写入 vault（子账户私钥可由根助记词派生）；或派生真实私钥后再导入；至少应拒绝空私钥（`require(privateKey.isNotEmpty())`），并在 `lockedImportPrivateKey` 对空密钥抛异常而非静默短路。

#### H-D1：`postMessage` 信任边界——origin 只校验宿主预设值，不校验实际调用 frame（与密码缓存窗组合为最高优先级）

- **位置**：`dapp-connect/.../WebAppInterface.kt:47-57,97-107`（`dappOrigin` 由宿主 `setOrigin` 预设，`postMessage` 仅比对预设值）。
- **问题**：`@JavascriptInterface postMessage` 不校验「当前 WebView 页面真实 origin」。若宿主在 `onPageFinished`/导航后忘记调 `setOrigin`（或页面通过 `window.location`/iframe 跳转至攻击者页面），旧 origin 仍然有效。结合 `CachingSecretProvider` 的 5–20s 明文私钥缓存窗（M-2/M-5），恶意页面可在缓存窗内**免密码调用签名/解密 RPC**——这是钱包类产品最高优先级的组合风险。
- **修复**：① `postMessage` 每次从 `webView.url`（或 `WebMessage` 来源）实时计算 origin 并与白名单比对，不依赖宿主预设；② 缓存窗缩短或按「是否发生页面导航」失效；③ 页面导航（`onPageStarted`）时强制失效缓存与 origin（宿主侧至少提供该钩子）。

#### H-D2：`swtc_batchTransactions` 无批量上限，可烧光账户余额手续费

- **位置**：`dapp-connect/.../middleware/SwtcBatchTransactions.kt`（`parseTransfers/parseCreateOrders/parseCancelOrders` 直接 `(0 until arr.length()).map`）与 `SwtcMiddleware.batchTransactions`（`SwtcMiddleware.kt:297-360`）。
- **问题**：批量交易条数无上限（攻击者可传 10 万笔），`mode="send"` 下逐笔签名+广播（每笔 200ms 延时仍可能持续数小时）——恶意 DApp 可在用户授权一次后批量耗尽账户余额（手续费与本金）。
- **修复**：① 单次批量上限（如 50 笔）并拒绝超限；② 每笔金额/总数校验；③ 批量请求同样走逐笔用户确认（见 M-4 / M-D4）。

### 2.2 🟠 中危

#### M-1：协程取消被异常捕获吞掉，破坏结构化并发（多处）

- **位置**：

  - `account/.../orchestrator/AccountOrchestrator.kt:251`（`catch (e: Exception)`）、`:309`（`runOperation` 的 `catch (e: Exception)`）
  - `did/.../service/DidCoreService.kt:71`（`catch (_: Exception)`）
  - `did/.../service/DidSyncService.kt:20`（`runCatching { didSdk.resolveDid(did) }`）
  - `did/.../util/DidCredentialHelper.kt:31,81`、`did/.../sdk/DidSdk.kt:888,1264,1289`（`runCatching` 包 Instant.parse / toChecksumAddress 等**纯函数**，取消吞掉风险低）与 `DidSdk.kt:1098`（包 `bridge.call` **suspend 调用**，真实风险点）

- **问题**：`CancellationException` 是 `Exception` 的子类。上述 `catch (e: Exception)` / `runCatching` 会把协程取消当成普通失败吞掉，导致：① 上层 `cancel()` / 超时（`withTimeout`）失效，协程继续执行昂贵操作（如 Argon2 KDF、网络请求）；② 用户退出页面后后台任务仍运行，浪费资源甚至引发状态更新竞态；③ 屏蔽结构化并发的取消传播。
- **修复**：

  ```kotlin
  catch (e: CancellationException) { throw e }   // 先重抛取消
  catch (e: Exception) { ... }
  ```

  `runCatching` 内部同样需要先 `if (e is CancellationException) throw e` 再 `getOrNull()`，或改用 `try/catch` 显式处理。

#### M-2：私钥 / 助记词 / secret 以不可擦除的 `String` 与明文内存缓存存在

- **位置**：

  - `dapp-connect/.../provider/CachingSecretProvider.kt:38,88,104`：私钥/secret 以 **明文 String 缓存在内存**，最长 20s（`MAX_AGE_MS`），且 `cache` 为普通 `mutableMapOf`（非并发安全容器，多线程读写下有可见性问题——虽有 `@Synchronized` 包住 begin/end，但 `cache` 的读写不在同一把锁下，见 M-5）。
  - `vault/.../VaultRepository.kt` 全篇：密码/私钥以 `ByteArray` 传递并 wipe——**这是正确做法**；但 `account/.../AccountOrchestrator.kt:222` `mnemonic.toString(Charsets.UTF_8)` 把助记词从 ByteArray 转回不可变 `String` 再传给 `WalletSdk`（经 WebView JS），该 String 无法 wipe，只能等 GC。（注：`:103,124,239` 等处是反向的 `.toByteArray()`，把 `WalletSdk` 返回的 String 转成 ByteArray 交 vault 加密存储——方向正确、不新建 String；不可擦除 String 的源头在 JS 返回值本身。）
  - `wallet/.../WalletSdk.kt`：所有签名 API 都以 `String` 传私钥/secret，经 JS 桥进入 JS 堆（既有审计 C-04 已覆盖，属架构性取舍）。

- **修复建议**：CachingSecretProvider 的缓存项改为 `CharArray`/`ByteArray` 并在过期时主动清零（`fill(0)`）；缓存条目过期即从 map 移除并 wipe。String 无法 wipe 的问题属既有架构取舍，至少应避免**额外复制**（见 P-2 中 EthMiddleware.personalSign 的冗余 JSONObject）。

#### M-3：HTTP 响应读取无大小上限，存在 OOM / 磁盘耗尽 DoS

- **位置**：

  - `nft/.../remote/NftRemoteAssetResolver.kt:182`（`readText()` 读元数据全文）
  - `nft/.../storage/room/NftStore.kt:497,516`（`fetchJson` / `fetchText`）
  - `nft/.../remote/SwtcChainNftClient.kt:82`（RPC 响应 `readText()`）
  - `app-update/.../AppUpdateChecker.kt:59`（`readText()`）
  - `app-update/.../AppUpdateApkInstaller.kt`（APK 下载无大小上限，恶意 `Content-Length` 声明与实际不符时无校验）

- **问题**：这些请求的目标 URL 部分来自链上/远端元数据（可被攻击者控制）。恶意/异常服务端返回超大 body 时，`readText()` 一次性分配全部内存 → OOM 崩溃（拒绝服务）。APK 下载无上限可写满磁盘。
- **修复**：统一封装「带大小上限的流式读取」工具（读取时累计字节数，超过阈值（如 5MB）即中断并返回失败），并校验 `Content-Length`。该工具正是第 4 节建议收敛到 core 的 `HttpFetcher`。

#### M-4：`eth_sendTransaction` / `swtc_sendTransaction` 在中间件层无逐笔用户确认回调

- **位置**：`dapp-connect/.../middleware/EthMiddleware.kt:350`（`sendTransaction` 直接签名并广播）、`SwtcMiddleware.kt:71`（同）。
- **问题**：`requestAccounts` 有 `RequestAccountsCallback` 做用户授权，但 **sendTransaction / signTransaction / signMessage 等直接使用 `secretProvider` 取私钥签名并广播**，中间件层没有逐笔确认钩子。若宿主的 `SecretProvider` 只按 origin 校验而不做逐笔 UI 确认，恶意 DApp 可在用户授权连接后无感发起转账（资金风险）。`require(origin.isNotBlank())` 只保证 origin 存在，不保证用户知情。
- **修复建议**：为签名/转账类方法增加可选的逐笔确认回调（与 `RequestAccountsCallback` 同构），或在 `WebAppInterface` 层对 `*_SENDTRANSACTION` 统一走确认流程；**交易类宿主**（ccdao）必须在 UI 层注入确认回调；**非交易类宿主**（jdid）可不注入，SDK fail-closed 拒绝 sign/send/batch（见 §11）。
- **状态（2026-09-01）**：✅ SDK 已实现 `TransactionConfirmCallback`（7b3d8b1 + a59e17f）；ccdao 已注入（`cecf940`）；jdid **不适用**。

#### M-5：`CachingSecretProvider` 内存可见性 / 竞态

- **位置**：`dapp-connect/.../provider/CachingSecretProvider.kt:40-69`。
- **问题**：`cache`（`mutableMapOf`）的读写没有与 `@Synchronized` 的 `beginOp/endOp/clearCache` 使用同一把锁；`cached()` 在 `privateKeyMutex.withLock` 内调用（串行化了私钥路径，OK），但 `clearJob = scope.launch { delay(BRIDGE_MS); cache.clear() }` 在 `Dispatchers.Default` 线程执行，与主调用线程的 `cache[cacheKey] = ...` 无 happens-before 保证（`clearJob?.cancel()` 之后立即 `cache.clear()` 的可见性依赖 Job 取消的同步语义，不严谨）。
- **修复**：改用 `ConcurrentHashMap`，或把整个 cache 访问收敛到单一锁/Mutex 内。

#### M-6：WebView 桥接的 JS 回调可被页面内任意脚本调用

- **位置**：`webview-bridge/.../JsPromiseGateway.kt:36,45`（`@JavascriptInterface onPromiseResult / onBridgeReady`）、`dapp-connect/.../WebAppInterface.kt:98`（`@JavascriptInterface postMessage`）。
- **问题**：`addJavascriptInterface` 暴露的对象对所有页面 JS 可见，且**不校验调用者 origin**。webview-bridge 场景下页面是本地 asset（风险低，`shouldOverrideUrlLoading` 也限制了导航），但 dapp-connect 场景页面是**第三方 DApp 页面**，`postMessage` 的 origin 校验只比对宿主预设的 `dappOrigin`（对预设值有 blank/isSafeUrl 格式检查，但不校验真实调用 frame），依赖宿主正确调用 `setOrigin()`——若宿主在页面导航后忘记更新 origin，或 DApp 页面通过 `window.location` 跳转到攻击者可控页，旧 origin 仍生效，形成信任窗口。`onPromiseResult` 同理：任何页面脚本都可伪造 `id` 触发已注册回调（C-03 已修复了 response 通道，但 `JsPromiseGateway` 的 `callbackMap` 仍可被伪造 id 触发——不过回调是 native→JS 调用结果的回传，JS 伪造只会让 native 端 `cont.resume` 提前完成，实际风险有限，仍建议在回调参数中绑定一次性 token）。
- **修复建议**：① `postMessage` 每次调用都从 WebView 当前 URL 实时计算 origin（或至少校验与 `setOrigin` 值一致）；② 回调 id 使用一次性随机 token 并在完成/超时后立即失效；③ `@JavascriptInterface` 方法对非法 origin 返回空响应而非继续执行。

#### M-7：`AppUpdateApkInstaller` 下载文件名路径穿越（低-中）

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:47`：`File(updateCacheDir(context), "${apkNamePrefix}-v${remote.versionName}.apk")`。
- **问题**：`versionName` 来自远端 checksums 文件（可被篡改/恶意服务端控制）。若 `versionName` 含 `/`（如 `../evil`），`File` 拼接可逃逸 `apk-updates` 缓存目录写入任意位置（覆盖应用缓存内其他文件，极端情况下配合符号链接可扩大影响）。`apkNamePrefix` 若由调用方传入外部输入同理。
- **修复**：对 `versionName` / `apkNamePrefix` 做白名单校验（仅允许 `[A-Za-z0-9._-]`），或改用 `File(cacheDir, sanitized)` + 断言 `canonicalPath` 以 `cacheDir.canonicalPath` 开头。

#### M-8N：NFT 资源解析的 SSRF 防护缺口——解析结果直返、绕过 `SsrfGuard`

- **位置**：`nft/.../remote/NftRemoteAssetResolver.kt:118-151`（`resolveRemoteImageUrl` 对可直接加载的 `imageUrl`/`metadataUri` **直接 return，不经过 `SsrfGuard`**，guard 仅在模块内部 `fetchMetadataImage`/`fetchText` 路径生效）；`NftRemoteAssetResolver.kt:153-167`（`SsrfGuard.enabled` 为公开 `@Volatile var`，任何调用方/测试可关闭安全开关）。
- **问题**：NFT 元数据内容可被攻击者控制。恶意 NFT 的 `image` 字段填入 `http://169.254.169.254/latest/meta-data/`（云元数据）、`//10.0.0.5/x`（协议相对地址，经 `resolveRelativeAssetUrl` 于 `NftRemoteAssetResolver.kt:202-208` 解析成内网 URL）时，`resolveRemoteImageUrl` 返回该 URL，调用方（宿主的图片加载器，**无 SSRF 防护**）随后抓取即构成 SSRF/内网探测。`data:` URI 直返同理（超大 data URI 撑爆内存）。
- **修复**：① 所有「对外返回的 URL」在返回前统一过 `SsrfGuard.check`（含 http/https/ipfs/data 分支）；② `SsrfGuard.enabled` 改为 `internal` 并禁止运行时关闭（测试用依赖注入替代）；③ 协议相对地址解析后强制补齐 scheme 再校验。

#### M-9N：NFT 元数据响应体无大小上限且整篇落库

- **位置**：`nft/.../storage/room/NftStore.kt:283`（完整元数据写入 DB）、`:497,516`（`fetchJson`/`fetchText` 的 `readText()`）、`NftRemoteAssetResolver.kt:182`、`SwtcChainNftClient.kt:82`。
- **问题**：恶意元数据服务器返回超大 body 时，① `readText()` 一次性分配内存导致 OOM；② 若侥幸读完，整篇元数据还会写入 Room，撑爆数据库。与 M-3 同根因，nft 模块是最直接暴露面。
- **修复**：统一走 core `HttpFetcher`（大小上限）；落库前截断/压缩（仅保留解析所需字段）。

#### M-10N：SWTC RPC 节点无 https 强制 + 跟随重定向 + 默认无证书固定

- **位置**：`nft/.../remote/SwtcChainNftClient.kt:26,75,93-99`。
- **问题**：默认节点（`ChainDefaults.kt`）本就是 https，但代码**无 https 强制、无默认证书固定**——`openPinnedConnection` 只对 `HttpsURLConnection` 且 `certificatePins` 非空时设置固定，默认 `certificatePins = emptyList()`；`postJson` 的 `instanceFollowRedirects = true` 且未校验重定向目标。中间人可把 RPC 响应替换为任意元数据 URI（后续解析仍过 SsrfGuard，但元数据内容本身可被篡改，影响 NFT 展示真实性）。
- **修复**：RPC 节点强制 `https://`；重定向关闭或校验目标；默认启用证书固定（至少 pin 默认节点），节点列表改为可配置。

#### M-11N：Room 无迁移策略 + DAO 用 `LOWER()` 使索引失效

- **位置**：`nft/.../storage/room/NftRoomDatabase.kt:16-17`（`version = 1` 未配 `migration`）；`nft/.../storage/room/NftDao.kt:32,35,43,50`（`WHERE LOWER(ownerAddress) = LOWER(:ownerAddress)`）。
- **问题**：① 库表结构变更时 `fallbackToDestructiveMigration` 未配置，升级会崩溃或需人工处理；② `LOWER()` 包裹列使 SQLite 无法使用索引，`swtc_nfts` 表大时全表扫描（性能问题，见 P-8N）。
- **修复**：① 配置显式 Migration 或至少在 `fallbackToDestructiveMigration()` 与版本号策略上做出明确决策；② 实体层统一小写存储地址（写入时 `lowercase(Locale.ROOT)`），查询直接等值比较，或列声明 `COLLATE NOCASE` 并建索引。

#### M-12N：`SsrfGuard` 之外的 URL 直接加载面

- **位置**：`NftRemoteAssetResolver.kt:42-47`（`isLoadableRemoteAssetUrl` 允许 `http://`、`data:`）。
- **问题**：`http://` 明文加载 NFT 图片可被中间人替换（图片展示内容被篡改，NFT 头像场景可诱导用户误认）；`data:` 无大小限制。见 L-6（与 app-update 的 http 问题同族）。

#### M-D1：`isSafeUrl` 的 `WEB_URL` 兜底接受 `ftp://`/`rtsp://` 等协议

- **位置**：`dapp-connect/.../DAppConnectSdk.kt:183-190`（`isSafeUrl`：正则只接受 http/https，但 `|| android.util.Patterns.WEB_URL.matcher(url).matches()` 兜底会放行 ftp/rtsp 等）。
- **问题**：origin 校验（`postMessage` 中调用 `isSafeUrl(origin)`）依赖该函数；`WEB_URL` 匹配的 `ftp://host` 会被当作安全 origin，破坏「仅 http/https」的信任假设。
- **修复**：删除 `WEB_URL` 兜底，或先解析 `Uri` 校验 scheme ∈ {http, https} 再走正则。

#### M-D2：`postMessage` 对非法 JSON 无容错（`JSONObject(json)` 直接抛）

- **位置**：`dapp-connect/.../WebAppInterface.kt:109`。
- **问题**：页面脚本传入畸形 JSON 时 `JSONException` 从 `@JavascriptInterface` 抛到 JS 侧（WebView 吞掉并打日志），后续 `obj.getString("name")` 等还可能抛 `JSONException`——行为不一致（部分分支有 try/catch，主流程没有）。
- **修复**：`postMessage` 入口统一 try/catch，返回结构化错误响应（`sendErrorResponse`）。

#### M-D3：`NativeResponseChannel` 的 `postWebMessage` 使用 `targetOrigin = "*"`

- **位置**：`dapp-connect/.../NativeResponseChannel.kt:46-49`。
- **问题**：`webView.postWebMessage(WebMessage(HANDSHAKE, arrayOf(jsPort)), Uri.parse("*"))` 向任意 origin 投递握手消息与端口。虽然端口只发往当前页面，但 `"*"` 语义上放弃了 origin 约束；若页面被替换，端口可能被新页面持有。
- **修复（初版，§20）**：改为向当前页面真实 origin 投递（从 `webView.url` 解析），并在收到 JS 侧消息时校验来源。
- **⚠️ 宿主回归（§10，2026-09-01）**：jdid-android / ccdao-connector-android 在 `fix` 分支 local SDK 下 **DApp 钱包连接失败**（一直「连接中」或首次确认后失败）。根因：C-03 响应经 WebMessagePort 回传；严格 `targetOrigin` 在 in-app WebView 上 handshake **静默失败**（不抛异常，JS `nativePort` 永为 null）。v0.3.2 使用 `"*"` 可正常工作。
- **当前决策（§10.4）**：`install()` **恢复 `targetOrigin = "*"`**（与 v0.3.2 一致）；保留 `resolveStrictTargetOrigin()` 供测试/后续 opt-in。H-D1 的 `WebAppInterfaceWithWebView.getOrigin()` **不在 WebView 宿主使用**（jdid/ccdao 依赖 `setOrigin()` 导航同步）。更严 origin 约束待 WebView 真机矩阵验证后再开。

#### M-D4：签名/解密类 RPC 无逐次用户确认（dapp-connect 层）

- **位置**：`EthMiddleware.kt:152-224`（`personalSign`/`signTypedData`/`decrypt`/`getEncryptionPublicKey`）、`SwtcMiddleware.kt:153-211`。
- **问题**：仅 `requestAccounts` 有 `RequestAccountsCallback`；签名类方法直接取私钥执行，无逐笔确认回调。与 M-4 同族，dapp-connect 是直接暴露面。
- **修复**：为敏感方法族增加统一确认回调（或在 WebAppInterface 层拦截），见 M-4。
- **状态（2026-09-01）**：✅ 同 M-4；ccdao 已适配；jdid **不适用**（§11）。

#### M-13A：vault 与 store 双写非原子——崩溃窗口产生孤儿密钥

- **位置**：`account/.../AccountOrchestrator.kt:36-49`（`persistVaultMaterial` 先写 vault，再 `store.addAccount`）、`:100-145`（`importHdWallet`：`vault.importMnemonic` → `vault.importPrivateKeys` → `store.addAccounts` 分步提交）。
- **问题**：任一步之间进程被杀/异常，vault 中已有私钥但账户表无记录（孤儿密钥），或反之。`runOperation` 捕获异常返回错误，但已写入的数据不回滚。且 **orchestrator 的 `Mutex` 是实例级**——宿主可创建多个 `AccountOrchestrator`（`AccountSdk.orchestrator(vault)` 每次 new），并发导入时 store 层无互斥（vault 层有内部 mutex 保护 vault 写入，但 store 写入与 vault 写入之间无原子性）。
- **修复**：① 导入流程改为「先写 store（暂存）→ 写 vault → 提交/补偿」或引入 `@Transaction` 语义（Room 的 `@Transaction` 只保护 DAO 层）；② orchestrator 使用进程级单例（复用 `AccountSdk` 的 instance）；③ 提供 `listOrphanKeys()` 对账 API。

#### M-14A：`Path.chain` 未持久化，Room 往返后根账户路径被改写

- **位置**：`account/.../storage/room/AccountEntity.kt:18-20,26-35,50-62`。
- **问题**：实体只存 `pathAccount/pathChange/pathIndex`，不存 `path.chain`；`toWalletAccount()` 用**账户自身的 chain**（`chain = chain`）重建 Path。根 HD 账户以 `Path(chain = 0)` 创建（`AccountOrchestrator.kt:98`），落库再读出后变成 `Path(chain = SWTC.bip44Code)`，`toString()` 从 `m/44'/0'/...` 变为 `m/44'/2147483963'/...`——派生路径语义被静默改变，影响后续子账户派生一致性（依赖 path 的派生代码需排查）。
- **修复**：实体增加 `pathChain` 列持久化 `path.chain`；或规定 Path.chain 恒等于账户 chain 并删除 `Path(chain = 0)` 的特殊用法（root 用 `ChainType.SWTC` 等真实链码）。

#### M-15A：未知链码静默回退 `ChainType.ETH`

- **位置**：`account/.../storage/room/AccountEntity.kt:24`（`ChainType.fromBip44Code(chain) ?: ChainType.ETH`）。
- **问题**：数据库中出现未知 chain 码（数据损坏/未来链）时静默当作 ETH 账户——签名/展示可能错误路由资金。应返回可观测的错误（或保留原始码并标记 unsupported）。

#### M-16A：`importHdWallet` 导入路径依赖会话密钥——未解锁会抛「Vault is locked」（已纠错：原「双重 Argon2」表述有误）

- **位置**：`account/.../AccountOrchestrator.kt:95`（`vault.initializePassword(password)` 触发一次 Argon2 并建立会话）→ `vault.importMnemonic`（内部 `lockedImportPrivateKey` + `derivedKey()`）与 `vault.importPrivateKeys(keys)`（`derivedKey()`）。
- **问题（修正后）**：`VaultRepository.derivedKey()`（`VaultRepository.kt:667-669`）是 `vaultSession?.derivedKey() ?: error("Vault is locked")`——**只是会话密钥副本，从不重新派生 Argon2**。正常导入仅在 `initializePassword` 处触发**一次** KDF；若 vault 已有密码但未解锁，`derivedKey()` 抛 `Vault is locked` 而非自动重派生。原「两次完整 Argon2、64MB×2」为误判，已撤销。
- **修复**：保持「导入前先 `initializePassword`/`unlock` 建立会话」的现状即可；若需支持「vault 已有密码时直接导入」，需在 orchestrator 明确先解锁的语义。

#### M-17A：`removeAccount` / `clearWalletData` 的密码数组生命周期依赖脆弱约定

- **位置**：`account/.../AccountOrchestrator.kt:186-203,263-276`。
- **问题**：`verifyPassword` 会 wipe 传入的数组（H-R5），orchestrator 用 `password.copyOf()` 规避（`removeAccount`），但 `clearWalletData` 直接把 `password` 传入 `vault.clearAllData(password)`——成功后该数组已被 wipe，调用方若复用会拿到全零数组（KDoc 有警告，但 API 层仍脆弱）。建议 vault 层改为「不 wipe 调用方数组，由调用方负责」，或返回明确契约对象。
- **补充（低危）**：`updatePublicKey` 无鉴权（任何持 SDK 引用的代码可改 publicKey，非安全边界但属 API 设计问题）；Room 库未加密（仅存地址/公钥/名称等非机密元数据，风险可控，但若未来扩展存敏感字段需加密）。

#### M-W1：JNI 完整性校验静默降级 + Java 回退为非恒定时间比较

- **位置**：`apk-verify/.../JniVerifier.kt:14-18,27-33`。
- **问题**：`System.loadLibrary("integrity")` 失败（.so 缺失/ABI 不符）时**静默降级**为纯 Java（无日志无告警）；回退用 `String.equals(a, b, ignoreCase = true)` 比较哈希——非常量时间（理论时序侧信道）且 `ignoreCase` 对哈希无意义。
- **修复**：回退改用恒定时间比较（逐字节 XOR 累加，收敛到 core `SecureCompare`）；降级时显著日志/指标；哈希先 `lowercase` 归一。

#### M-W2：`copyUriToTemp` 无大小上限、无超时（content:// 源可撑爆磁盘）

- **位置**：`apk-verify/.../ApkIntegrityVerifier.kt:234-249`。
- **问题**：对 `content://` URI（可来自其他应用/远程 provider）`input.copyTo(output)` 无限流式拷贝至 `cacheDir` 写满（DoS）；调用线程同步执行。
- **修复**：限最大字节数并计数中断；移 IO 线程、支持取消。

#### M-W3：`verifyApkFile` 同步执行重 IO + APK 被解析两次

- **位置**：`apk-verify/.../ApkIntegrityVerifier.kt:121-185`。
- **问题**：拷贝整包 + 两次 `getPackageArchiveInfo` + 全量 SHA-256 全部在调用线程同步执行（UI 线程直接 ANR）；`:139-143` 与 `:148-152` 对同一临时文件解析两次。
- **修复**：`suspend` + `withContext(Dispatchers.IO)`；一次调用同时取 versionCode 与 `GET_SIGNING_CERTIFICATES`；拷贝时流式计算哈希。

#### M-W4：下载 APK 的哈希计算绕过 JNI 反篡改路径

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:86`（`ApkDigest.sha256Hex` 纯 Java）对比 `ApkIntegrityVerifier` 走 `JniVerifier.computeSha256`。
- **问题**：同一仓库哈希计算存在两条路径，更新下载这一最敏感环节反而用无 native 保护的 Java 路径，与「native 提升运行时篡改门槛」的设计意图矛盾。
- **修复**：统一 `JniVerifier.computeSha256`；native 不可用时显式告警。

#### M-W5：FileProvider 仅靠宿主隐性契约——模块内无 manifest 声明

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:123-131`；已核实 `app-update/src/main/` **无 AndroidManifest.xml**，全仓无 `<provider>` 声明。
- **问题**：FileProvider 必须由宿主声明且 authority 恰好为 `${packageName}.fileprovider`、`file_paths` 含 `<cache-path>`——任何不一致都在安装时抛异常；`startInstall` 也未先检查 `canRequestPackageInstalls()`。
- **修复**：模块内自带 FileProvider 声明（authority 用 manifest placeholder `${applicationId}.fileprovider`）与 `file_paths` 资源；`startInstall` 先校验权限。

#### M-W6：`isSigningCompatibleWithInstalled` fail-open（升级决策方向性错误）

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:117-121`（`runCatching{...}.getOrDefault(true)`，内部 `?: return true`）。
- **问题**：签名兼容性检查任何异常/读取失败都返回「兼容」——无法确认兼容时反而放行升级。
- **修复**：返回 `Boolean?` 或 sealed 结果，未知/失败由调用方中止。

#### M-W7：钱包模型 data class 自动 `toString` 泄露私钥/助记词

- **位置**：`wallet/.../model/WalletModels.kt:3-6,17-20,22-27,29-44`（`Keypair(privateKey, ...)`、`Mnemonic(value)`、`SubWallet`、`GenerateHDWalletResult`、`TraditionalDeriveResult`）。
- **问题**：默认 `toString()` 原样输出私钥/助记词/secret，一旦进入日志、异常消息、崩溃上报即泄露。
- **修复**：密钥字段覆写 `toString()` 输出掩码（如 `privateKey="***"`）或 `@Redacted`。

#### M-W8：`WebviewBridgeClient.initialize` 可重复调用但配置被静默忽略

- **位置**：`webview-bridge/.../WebviewBridgeClient.kt:52-58,137-144`。
- **问题**：`start()` 后再次 `initialize` 换配置，旧 WebView 仍用旧配置；若 `jsInterfaceName` 改变，`onPageFinished` 注入的探测 JS 引用新名字而接口注册在旧名字下——桥接**静默永久不可用**。
- **修复**：已 start 后 `initialize` 抛异常或先 destroy 重建；引入状态机。

#### M-W9：WebView 导航限制是前缀匹配而非安全边界；子资源不受控

- **位置**：`webview-bridge/.../WebviewBridgeClient.kt:89-95`（`!url.startsWith("file:///android_asset/")`）。
- **问题**：`shouldOverrideUrlLoading` 主要拦主框架导航；iframe/子框架与所有子资源（`shouldInterceptRequest` 未覆写）不受限；允许**任意** asset 路径（宿主其他 asset HTML 若被加载即共享 JS 接口）；`bridgeUrl` 可配置为任意 URL（`WebviewBridgeConfig` 无校验）。
- **修复**：固定允许清单（仅精确匹配 `wallet-bridge.html`/`bridge.html`/`did-bridge.html`）；`onPageStarted` 校验实际加载 URL；`shouldInterceptRequest` 拒绝非 asset 子资源；config 构造期校验。

#### M-W10：`allowFileAccess=false` 与 `file:///android_asset/` 加载的兼容性风险

- **位置**：`webview-bridge/.../WebviewBridgeClient.kt:81,131`。
- **问题**：部分 API level/WebView 实现上 `allowFileAccess=false` 会连 `android_asset` 一起禁止，桥接页静默加载失败 → 每次调用空等 `awaitReady` 15s 超时且无日志。
- **修复**：目标设备/CI 覆盖该组合；必要时改 `allowFileAccess=true` + `allowFileAccessFromFileURLs=false` + `allowUniversalAccessFromFileURLs=false`，或改用 `WebViewAssetLoader`。

#### M-13N：链 ID 键格式不一致 + 缺失默认 0 → 缓存穿透与错误查询

- **位置**：`nft/.../storage/room/NftStore.kt:142,245`（内部用 `"0x${chainId.toString(16)}"` 十六进制键）与 `:38-52,107-112`（公开 API `observeEvmNftItems`/`getEvmNftItem` 直接透传原始 `chainId: String` 不归一化）；`:219`（`?.toLongOrNull() ?: 0L`）。
- **问题**：(a) 同一逻辑链 ID 两套格式并存——内部 `"0x1"`、调用方传 `"1"` 时查询 miss、写入重复数据；(b) VC 缺 `chainId` 时默认 `0L` → 以 `"0x0"` 查库必然查空且可能写入错误键。
- **修复**：`NftStore` 入口统一归一化 chainId（hex 小写）；缺失 chainId 直接返回 null/空，不用 0 兜底（core 提供 `ChainType.toEvmChainIdHex()`，见 C-表）。

#### M-14N：NFT 解析失败用空对象/空串作哨兵，调用方无法区分「解析失败」与「字段为空」

- **位置**：`NftStore.kt:204-213`（返回 `Nft(uri = "", image = null, ...)` 空对象）、`SwtcNftMetadataParser.kt:30` / `NftStore.kt:347-351`（`NftMetadataFields(null, null, null)`）、`NftStore.kt:437-441`（`sanitizeUri` 返回 `""` 而非 null）。
- **问题**：哨兵值让「元数据拉取失败」「字段本身为空」「URL 非法」三者无法区分，上层（did 的 `generateProfileVC` 等）会据此做展示决策；同时掩盖了网络/解析错误（无日志）。
- **修复**：sealed 结果类型（如 `ResolvedNftMeta.Success/Failed/Empty`），配合 core 统一结果类型（C-9/C-14 项）。

#### M-15N：`NftDao` 用 `@Insert(REPLACE)` 整行替换会静默重置 `updatedAt` 且 rowid 变更

- **位置**：`nft/.../storage/room/NftDao.kt:11-15,29-30,53-54`（`OnConflictStrategy.REPLACE`）配合实体默认 `updatedAt = System.currentTimeMillis()`（`NftEntities.kt`）。
- **问题**：REPLACE 语义是 DELETE+INSERT：① 每次 upsert 重建行（rowid 变更、索引重建开销，写放大）；② 若调用方传入旧实体但 `updatedAt` 默认值在构造时已求值，会静默覆盖真实更新时间。
- **修复**：对「存在即更新」场景改用 `@Upsert`（Room 2.7 支持，INSERT 冲突转 UPDATE），或显式 `@Update`。

#### M-16N：`SwtcChainNftClient` 钉扎路径 `defaultTrustManagers.first()` 可能 NoSuchElementException

- **位置**：`nft/.../remote/SwtcChainNftClient.kt:109-111`（`filterIsInstance<X509TrustManager>().first()`，部分厂商 ROM 可能为空）。
- **修复**：`firstOrNull() ?: 抛带说明异常`。

#### M-17N：`NftStore.fetchJson` 硬转换 `as HttpURLConnection` + SSRF 白名单含误导性 `"ipfs"`

- **位置**：`nft/.../storage/room/NftStore.kt:489`（`(URL(url).openConnection() as HttpURLConnection)`——非 http/https 协议直接 ClassCastException）；`NftRemoteAssetResolver.kt:160`（白名单含 `"ipfs"`，而 `URL("ipfs://...")` 抛 MalformedURLException，属误导性死代码）。
- **修复**：`as? HttpURLConnection ?: return null`（与 `fetchText` 一致）；白名单只留 `http/https`。

#### M-DID1：授权凭证吊销检查把「网络失败」默认判为「已撤销」

- **位置**：`did/.../sdk/DidSdk.kt:919-969`（`checkGranteeCredentialUpdate`）与 `:894-902`（`verifyCredential`）。
- **问题**：(a) owner 文档获取失败时返回 `isUpdate=true, fetchFailed=true`，但 `verifyCredential` 只读 `isUpdate`、**忽略 `fetchFailed`**——一次瞬时网络故障就把用户持有的有效授权凭证判为「已撤销/已更新」；(b) 链上替换检测只比较 `credentialSubject.id` 与 `expirationDate`（`:952-963`），同 id/同 subject/同过期但 `usageRights` 不同的替换版本检测不到。
- **修复**：`verifyCredential` 对 `fetchFailed=true` 返回「状态未知」而非 invalid；比较纳入 usageRights/restrictions 或整份凭证规范哈希。

#### M-DID2：写操作基于陈旧 baseDoc 发布，覆盖链上更新（last-writer-wins 回滚）

- **位置**：`did/.../sdk/DidSdk.kt:1091-1110`（`resolveBaseDoc`：链上解析异常被 `runCatching` 静默吞掉后**回退本地缓存文档**作基底）、`:971-984`（`publishDid`），调用点 451/517/632/682/774/823。
- **问题**：链上 resolve 暂时失败 + 本地有旧文档时，本次写操作以旧文档为基底发布，把其他设备刚写入链上的更新覆盖掉。
- **修复**：链上解析失败时**中止写操作**并报「无法获取最新文档」，而非静默回退本地。

#### M-DID3：`uploadInitialDidDoc` 对已存在的链上 DID 无保护，直接覆盖

- **位置**：`did/.../sdk/DidSdk.kt:333-441`。
- **问题**：方法名是「初始文档」却无「仅当链上不存在时」守卫；`didStat` 失败时 `previousCid` 为空（`:346-355,401-407`），随后用全新构造的文档直接 `publishDid` 覆盖——已存在的 verificationMethods/credentials/服务端点全部丢失，且断开 previousCid 链。
- **修复**：publish 前确认链上不存在；`previousCid` 获取失败时拒绝发布。

#### M-DID4：DID 文档存储无唯一索引、upsert 非原子 → 并发重复行

- **位置**：`did/.../storage/room/DidRoomDao.kt:11-30`（`WHERE did = :did` 无索引）、`RoomDidStore.kt:21-24`（upsert = findByDid + insert 两步）。
- **问题**：两个协程并发 upsert 同一新 did 时都 find 不到 → 各插一行重复记录；每次 upsert 两次查询且全表扫描。
- **修复**：`did` 加唯一索引 + `@Upsert` 或 `@Transaction` 保证原子性（与 nft 的 P-13N 同类：先查后写非原子）。

#### M-DID5：校验和/地址转换失败被 runCatching 静默吞掉，空 contract 进入凭证 ID

- **位置**：`did/.../sdk/DidSdk.kt:1263-1266,1288-1290`；`DidCredentialHelper.kt:29-33,79-82`。
- **问题**：`ChecksumUtils.toChecksumAddress` 对非法地址抛异常，但四处调用全部 `runCatching{...}.getOrNull().orEmpty()`——非法地址静默生成**空合约地址**的 credentialId/VC subject，后续匹配/撤销检查全部失配。
- **修复**：非法地址显式抛错或返回失败结果，禁止静默降级为空串。

#### M-DID6：`verifyCredential` 过期日期解析失败被当作「未过期」

- **位置**：`did/.../sdk/DidSdk.kt:886-892`（`runCatching { Instant.parse(expirationDate) }.getOrNull()?.takeIf{...}`）。
- **问题**：格式非法的 `expirationDate` 被静默跳过过期检查，交给 JS 桥验证——已过期/畸形凭证可能被判有效。
- **修复**：解析失败按「无法验证」处理（verified=false 或单独错误码）并记日志。

#### M-DID7：`DidSyncService` 单账户异常中断整批同步；resolve 失败静默丢账户

- **位置**：`did/.../service/DidSyncService.kt:11-35`（`toDid` 在 `runCatching` 之外，`:17`；`resolveDid` 在 `runCatching` 内 `:20`）。
- **问题**：一个异常账户（如 MOAC 地址格式非法触发 `require`）让**整个** sync 抛异常中止；resolveDid 失败则静默丢账户，无日志无统计。
- **修复**：per-account 隔离（独立 try/catch + 记录），返回每个账户的失败原因。

#### M-DID8：桥接对象无生命周期出口——`DidSdk.create` 可重复创建泄漏 WebView

- **位置**：`did/.../sdk/AndroidDidWebRuntime.kt:59-70`（构造即 `initialize()+start()` 创建隐藏 WebView 加载 ~9.6MB JS，即 `did-0.3.2.min.js`）、`DidSdk.kt:1310-1328`、`port/DidSdkPorts.kt:11-22`（`IDidBridge` 无 close）。
- **问题**：`RealDidWebBridgeClient.destroy` 存在却无人调用；宿主每次 `DidSdk.create` 新增一个永不销毁的 WebView（内存泄漏 + 进程常驻）。
- **修复**：提供 `DidSdk.close()` 透传 `destroy()`，文档化单例使用方式（与 B-9/B-25 同族）。

#### M-18A：重复地址检查不完整——HD 根账户与传统账户可同地址并存

- **位置**：`AccountOrchestrator.kt:32,87`；`account/.../storage/room/AccountDao.kt:37-44`（`getNonRootAccount` SQL：`(isHD=1 AND parentId IS NOT NULL) OR isHD=0`）。
- **问题**：`importSingleAccount` 只查 `findNonRootAccount`，不检查同地址的 **HD 根账户** → 可导入与既有 HD 根同地址的传统账户；`importHdWallet` 只查 `findRootAccountByAddress`，不检查同地址的传统账户/子账户。后果：同地址多账户、`getSameAccountsCount` 语义混乱、vault「一个地址一份密钥」归属二义。
- **修复**：统一按 address（+chain）做全量存在性检查。

#### M-19A：orchestrator 的 Mutex 是实例级，`AccountSdk.orchestrator()` 每次新建实例 → 并发保护失效

- **位置**：`AccountOrchestrator.kt:17-21`（实例级 `Mutex`）；`AccountSdk.kt:16-17`（`orchestrator(vaultRepository)` 每次 new）。
- **问题**：应用创建两个实例并发执行 `deriveSubAccount`（索引分配基于 `getMaxIndexByChain`，无原子保留）时，两实例 mutex 互不串行 → 可能派生同一索引；`removeAccount` 同地址并发删除（都读到 count==2 → 都不删 vault 密钥）→ 孤儿密钥。
- **修复**：Mutex 提升为 store/vault 级（或 AccountSdk 级）共享；`orchestrator()` 改为缓存单例或由调用方持有。

#### M-20A：Room 写路径无 `@Transaction`——两步删除非原子

- **位置**：`account/.../store/RoomAccountStore.kt:64-67`（`deleteById + clearIfCurrent`）、`:143-146`（`deleteAllAccounts + deleteAll`）。
- **问题**：两步分属独立隐式事务，中途失败 → `current_account` 指向已删账户（存在不一致窗口，`nft` 模块的 `deleteSwtcNftsByOwner` 同理）。
- **修复**：DAO 或 store 层加 `@Transaction` 方法合并。

#### M-21A：认证锁定异常被 `runOperation` 吞成通用 Failure

- **位置**：`AccountOrchestrator.kt:306-311` + `vault/VaultRepository.kt:281-285`。
- **问题**：锁定期调用 `verifyPassword`（removeAccount/clearWalletData 路径）抛 `VaultAuthLockedException`，被 `catch (Exception)` 包装为 `AccountOperationError.Failure`——调用方**无类型化路径**区分「密码错误」与「账户锁定」（`Failure.cause` 虽保留原始 throwable、技术上可手动解包，但不规范），无法展示锁定倒计时（vault 提供 `authLockRemainingMs()` 但 orchestrator 未透传）。
- **修复**：`runOperation` 先重抛 CancellationException（H3），再单独透传 `VaultAuthLockedException` 为领域错误。

#### M-22A：`importHdWallet` 中 `keys.add` 先于查重——重复子账户密钥可能生成孤儿密钥

- **位置**：`AccountOrchestrator.kt:122-142`（line 124 `keys.add(...)` 在 line 126 查重之前）。
- **问题**：重复子账户的密钥也加入 `keys` 并在 line 144 导入 vault；vault 端 `addressInKeys` 幂等过滤可兜底，但若 vault 恰好缺该地址密钥，则静默生成无 store 记录的孤儿密钥。
- **修复**：先查重（或一次性取该地址全部账户）再组装 keys。

#### M-D5：`handleSwtcRequestAccounts` 静默切换全局链状态

- **位置**：`dapp-connect/.../WebAppInterface.kt:334-336`（收到 SWTC requestAccounts 时若 `currentChainType != SWTC` 直接 `setCurrentChainType(SWTC)`）。
- **问题**：DApp 只需发起 `swtc_requestAccounts` 就能把钱包全局链状态（影响 ETH 侧所有后续请求、`eth_chainId` 返回）静默改成 SWTC——无用户确认、无来源提示，可能造成 ETH 请求被路由到错误链。
- **修复**：链切换必须走统一确认流程（`wallet_switchEthereumChain` 的 `ChainProvider` 确认回调），禁止中间件内部静默改全局状态。

#### M-D6：`NativeResponseChannel` pending 队列无界 + 无速率限制

- **位置**：`dapp-connect/.../NativeResponseChannel.kt:25`（`pending = ArrayDeque<String>()` 只增不降，`flushPending` 失败即停但队列不回缩）。
- **问题**：DApp 可高频发起请求（无速率限制），响应端口不可用时（页面未 install 通道）响应全部堆积在内存队列；恶意页面刷屏请求 → 队列无界增长（内存 DoS）。
- **修复**：pending 队列设容量上限（超限丢弃最旧并告警）；RPC 入口加速率限制。

#### M-D7：gas 估算失败静默回退 21000

- **位置**：`dapp-connect/.../middleware/EthMiddleware.kt:312-321`（`estimateGas` 抛异常时 `txParams.put("gas", "0x5208")`）。
- **问题**：复杂合约交易的 gas 估算失败被静默回退为 21000（简单转账值）——交易大概率 out-of-gas 失败（手续费损失），且用户无感知。
- **修复**：估算失败应返回明确错误或使用更高安全系数回退，禁止静默 21000。

#### M-D8：批量交易金额/币种校验不完整

- **位置**：`dapp-connect/.../middleware/SwtcBatchTransactions.kt:17,96,118`（金额/currency-issuer 正则）与 `SwtcMiddleware.batchTransactions`（`:327-329`）。
- **问题**：`isValidTransfer` 等校验只覆盖格式（正则），不校验金额总量上限、memo 长度、token 与实际 issuer 匹配等语义；配合 H-D2 无批量上限，恶意批次可构造「格式合法但总额巨大」的请求。
- **修复**：补充金额总量/单笔上限、memo 白名单长度、issuer 归属校验。

#### M-8：`CachingSecretProvider` / `VaultSession` 的密钥副本管理（设计层）

- **位置**：`vault/.../VaultRepository.kt:29-35`（`VaultSession.derivedKey()` 返回 `copyOf()`，好）；`VaultRepository.getPrivateKeyUnlocked/getMnemonicUnlocked`（H-04 已修复为 internal）。
- **问题**：`derivedKey()` 每次调用都 `copyOf()` 一份会话密钥，`changePassword` 内存中会短暂出现旧 key、新 key 与当条明文（`VaultRepository.kt:601-652` 逐条「解密→改密→wipe」，明文**不共存**）。对大钱包（1024 个地址上限）内存峰值仍可观；且 wipe 只能覆盖显式持有的数组，`Vault` builder 中的 `ByteString` 副本不可 wipe。
- **修复建议**：`changePassword` 改为逐条目「解密→改密→wipe」流式处理而非先全量解密；或至少在 builder 构建后统一 `clear()`（已有 `vault.clear()`，但 `ByteString` 内部数组不可清零，属 protobuf 固有限制，可在文档中注明）。

### 2.3 🟡 低危

- **L-1**：`vault/.../VaultRepository.kt:189` `getBiometric()` 抛 `Error("Biometric cache is not exist")`——`Error` 不该用于业务异常（JVM 约定 `Error` 表示不可恢复错误，不应被捕获；调用方 `catch (Throwable)` 才能捕获）。应改为自定义 `IllegalStateException` 或 sealed 错误类型。
- **L-2**：`vault/.../Argon2idKdf.kt:26` `String(password).toByteArray(Charsets.UTF_8)`：把密码字节转成不可变 String 再转回字节，中间 String 不可 wipe；且若原始密码字节不是合法 UTF-8 会静默替换（`?`），改变 KDF 输入。应直接使用 `password` 原始字节（BouncyCastle 的 `generateBytes` 接受 `byte[]`）。
- **L-3**：`vault/.../VaultRepository.kt:97,263` `catch (_: Throwable)` 捕获范围过大，应收窄为 `GeneralSecurityException`（AEAD 失败、HMAC 异常），避免吞掉程序性错误（如 OOM 前的 `Error` 也会被吞）。
- **L-4**：`app-update/.../AppUpdateChecker.kt` / `AppUpdateApkInstaller.kt` 未校验 URL scheme（允许 `http://`）。APK 有 SHA-256 校验兜底（篡改会被拒绝），但元数据（checksums 文件）本身是明文 HTTP 传输时可被中间人替换为「指向合法最新版本的旧版本」，造成降级攻击。建议强制 `https://`。
- **L-5**：`did/.../sdk/DidSdk.kt`、`dapp-connect/.../middleware/EthMiddleware.kt` 大量 `Log.d/e` 输出：虽然未打印私钥/助记词，但 `EthMiddleware.kt:73` 等把 origin、chain 打日志属正常；注意 `DidSdk.kt:98` 等 `Log.e("DidSdk", ..., e)` 中 `e` 的堆栈可能包含敏感参数（Kotlin 异常 message 常带参数值）。建议统一日志封装并禁止把 RPC 参数拼进日志（既有审计 H-07 已覆盖，此处为残余面）。
- **L-6**：`nft/.../remote/NftRemoteAssetResolver.kt:44` `isLoadableRemoteAssetUrl` 允许 `http://` 明文加载 NFT 图片；`data:` URI 无大小限制（超大 data URI 会撑爆 WebView/内存）。
- **L-7**：`apk-verify/.../ApkIntegrityVerifier.kt:183` 临时 APK 文件写入 `context.cacheDir` 后 `finally { temp.delete() }`——若进程被杀，临时文件残留（cacheDir 会被系统清理，风险低；且 :239 用 `File.createTempFile` 已带随机后缀，无并发覆盖冲突）。
- **L-8**：`did/.../service/DidCoreService.kt` 的 `pendingDeleteUpdated` / `pendingUpdateAvatar` 等 `ConcurrentHashMap` 只增不减（特定路径移除），长生命周期内可能缓慢增长（内存泄漏倾向）。
- **L-9**：`webview-bridge/.../WebviewBridgeClient.kt:118` `if (false)` 死代码块（控制台转发被硬编码禁用），应删除或改为配置开关。
- **L-10**：`webview-bridge/.../WebviewBridgeClient.kt:76-131` WebView 用 **applicationContext** 创建——已知 Android 反模式：WebView 持有 Activity Context 之外的引用会导致 WebView 组件泄漏/无法回收（部分机型上 WebView 需绑定 Activity 生命周期才能正确释放）。建议由宿主传入 Activity context，或至少提供 `attach/detach` 生命周期钩子。
- **L-11**：did 空 catch 无日志：`DidCoreService.kt:71`（`catch (_: Exception) {}` 后返回 null）、`DidSdk.kt:1130-1132,1148-1150,1170-1172`——`resolveDid` 返回 null 时无法区分「链上无文档/网络错误/解析错误」，上层据此做破坏性决策（见 H-DID2/H-DID3）。
- **L-12**：did 时间戳字符串比较：`DidCoreService.kt:64`（`chainUpdated > localUpdated`）依赖链上文档均为同格式 ISO-8601 UTC；其他实现生成的文档（不同时区/精度）会导致比较失真。应 `Instant.parse` 后比较。
- **L-13**：did 大小写语义不一致：`DidSdk.kt:827`（`== credentialId` 区分大小写）vs `DidCredentialHelper.kt:147`（`equals(ignoreCase=true)`）——同一凭证 ID 不同大小写时行为不一致，应统一 ignoreCase。
- **L-14**：`DidRoomDatabase.exportSchema = false`（`DidRoomDatabase.kt:14`）阻碍未来迁移验证（与 nft 的 M-11N 同族：迁移策略缺失），建议开启 schema 导出。
- **L-15**：`webview-bridge/.../WebviewBridgeClient.kt:231-232` JS 字符串拼接执行——当前 `method/id` 经 `JSONObject.quote`、`params` 经 `JSONObject.toString` 转义，实际注入面很小（防御纵深项）；建议统一 JSON 编码避免未来引入未转义字段。
- **L-16**：`wallet/.../WalletSdk.kt:63,159,247` `.toBoolean()` 吞错——JS 返回非 `"true"` 字符串（如未来返回 `"1"`）静默变 false，与「校验失败」无法区分。
- **L-17**：`apk-verify/.../ApkSigningFingerprint.kt:27,50` 仅取 `signers[0]`——多签名者 APK（证书轮换/双签）若官方证书非第一个即误判；未对 `hasMultipleSigners` 告警。
- **L-18**：`AppUpdateCheckThrottle.kt` 节流依赖系统时钟（改系统时间可绕过）；`lastCheckMs` 是否持久化由调用方决定。
- **L-19**：`WalletSdk.generateMnemonic` 长度参数无校验（默认 128，可传任意值如 64，无下限约束）。
- **L-20**：`dapp-connect/.../WebAppInterface.kt` 错误处理把 `e.message` 直传 DApp（`sendErrorResponse(network, nonce, e.message ?: ...)`）——异常 message 可能含内部实现细节（如 vault 锁定时长、地址等），建议映射为公开错误码 + 固定文案。
- **L-21**：`EthMiddleware.kt:116` `getChainId()` 对无 `evmChainId` 的链兜底 `?: 1L`（以太坊主网）——未知链静默返回主网 chainId，可能误导 DApp 与签名（与 account 的 M-15A 同族）。
- **L-22**：`DAppConnectSdk.jsQuote`（`:132-133`）只转义 `\` `"` `\n` `\r`，未转义 JS 行分隔符 `\u2028/\u2029`——旧版 JS 引擎中这些字符可被解释为换行截断字符串（现代引擎安全，属纵深防御项）。
- **L-23**：`apk-verify/.../ReleaseChecksumsFile.kt:23-37` 与 `OfficialReleaseManifest.kt:39,51-62`：`readText()` 无大小上限（大文件 OOM；字符集为 Kotlin 显式默认的 UTF-8，无平台默认问题）；`signingCertSha256`/`apkSha256` 无 hex 格式校验（非 hex 值仅比较时恒不等，报错语义差）。解析逻辑本身（hex 正则 + `toIntOrNull`）校验严格，是好的部分。
- **L-24**：`webview-bridge/.../WebviewBridgeEngine.kt:7` / `JsPromiseGateway.kt:83` 进程级单例——全局唯一 WebView 承载全部桥接调用，状态跨测试/跨业务泄漏；`callbackMap` 经 object 公开暴露（配合 H-W1 的伪造回调面）。
- **L-25**：`AppUpdateCheckThrottle.kt:10` 的 `force` 参数无默认值，调用点语义不清（`force=true` 与「未检查过」路径重合）；`lastCheckMs` 持久化由调用方决定，重启即失效。

---

## 3. 代码优化与性能优化

### 3.1 明确可优化项（按收益排序）

#### P-1：HTTP 层统一与大小限制（同时消除 M-3）

`NftRemoteAssetResolver.fetchMetadataImage`、`NftStore.fetchJson/fetchText`、`SwtcChainNftClient.postJson`、`AppUpdateChecker.fetchText`、`AppUpdateApkInstaller.downloadAndVerify`、`EvmRpcClient`（RPC 请求）是 **6 处几乎相同的 `HttpURLConnection` 样板**（connect/read 超时、responseCode 判断、`readText()`、`disconnect()`）。差异仅在：是否 SSRF 检查、是否重定向、读文本还是写文件。收敛为 core 的 `HttpFetcher`（参数：URL、超时、大小上限、SSRF 开关、重定向策略）后：

- 统一加大小上限（OOM 风险消除）；
- 统一 SSRF 防护（目前只有 nft 模块有 `SsrfGuard`，app-update 的 URL 来自远端 checksums 文件却没有 SSRF/白名单校验）；
- 统一异常/取消处理（补 M-1 的 CancellationException 重抛）。

#### P-2：消除 `EthMiddleware.personalSign` 的冗余私钥复制

`EthMiddleware.kt:164-170`：构造了包含 `privateKey` 的 `JSONObject params`，但随后调用 `WalletSdk.personalSign(privateKey, message)` 时**该 JSONObject 从未被使用**（死代码）。删除可减少一份私钥在内存/GC 中的存在时间。

#### P-3：`hex` 编码统一用 `HexFormat`（API 33+ 或 Kotlin stdlib `HexFormat`，Kotlin 2.2 已内建）

- `apk-verify/.../ApkDigest.kt:36`：`joinToString { "%02x".format(it) }`——`String.format` 每次调用开销大（Formatter 创建），大文件 SHA-256 的 hex 化（APK 可能上百 MB 的摘要只有 32 字节，实际影响小，但编码风格应统一）；
- `did/.../util/ChecksumUtils.kt:36`：手写 `StringBuilder` 版本（已较高效）。
- 建议 core 提供 `fun ByteArray.toHex(): String = HexFormat.of().formatHex(this)` 与 `fromHex`，两处手写实现收敛。

#### P-4：`NftMetadataImageCache` 与 `fetchLocks` 无界增长

`nft/.../remote/NftRemoteAssetResolver.kt:16-40`：`ConcurrentHashMap` 缓存永不淘汰（仅换入不换出），长期使用后内存增长；`fetchLocks` 的 `Mutex` 同样只增不减。建议加容量上限（如 256）或 LRU 淘汰。

#### P-5：`DidSdk` 中 `generateProfileVC` 等路径的 DB 查询循环

`did/.../service/DidSyncService.kt:11-35`：`syncAccounts` 对每个账户串行 `resolveDid`（网络调用）。账户多时耗时长，建议 `map` 并发（`coroutineScope { accounts.map { async { ... } } }`）并保留顺序（`awaitAll`）。

#### P-6：`AccountOrchestrator.deriveSubAccount` 循环派生可提前终止

`account/.../AccountOrchestrator.kt:219-235`：`while` 循环内每次 `WalletSdk.deriveChild` 都跨 JS 桥调用（昂贵），若 `index` 为空且地址碰撞频繁会串行多次。可接受（BIP44 碰撞罕见），但建议对 `index == null` 的情况限制最大尝试次数，避免极端情况下死循环（`store.findNonRootAccount` 一直命中时）。

#### P-7：`Argon2idKdf` 参数选择的二分逻辑可读性

`vault/.../Argon2idKdf.kt:55-61`：`when { mc <= 256 -> 64 to 3; ... }` 的 magic number 建议提取为命名常量并注释推导依据（内存 1/4 规则、迭代 3/2/2）。

#### P-8：Room 查询合并

- `account/.../RoomAccountStore.kt:139` `getCurrentAccountIdSync()` 每次 `suspend` 调用都查一次库；「accounts Flow 反复 `.first()`」的热点实际在 dapp-connect 侧（见 P-13D），account 模块主代码无 `.first()`——若出现该热点，可在 Provider 层做一次缓存快照。
- `nft/.../storage/room/NftStore.kt` 的 `getNftMeta` / `upsertNftMeta` 若在循环内调用（如批量同步），存在 N+1 查询风险（`NftStore.resolveCredentialImages` 批量路径需复查）。

#### P-8N：NFT DAO 用 `LOWER()` 包裹列，索引失效全表扫描

- **位置**：`nft/.../storage/room/NftDao.kt:32,35,43,50`（`WHERE LOWER(ownerAddress) = LOWER(:ownerAddress)` 等）。
- **问题**：SQLite 无法对 `LOWER(列)` 使用列索引，`swtc_nfts` 表行数增长后每次查询全表扫描；`observeSwtcNfts` 是 Flow（每次表变更都重查），放大开销。
- **修复**：写入时统一 `lowercase(Locale.ROOT)` 存储地址，查询直接等值比较（可命中索引）；或列声明 `COLLATE NOCASE` 并建索引。同时建议实体列加索引（`@ColumnInfo(index = true)`）。

#### P-9：字符串拼接与正则

- `did/.../util/DidCredentialHelper.kt:26` `"\\s+".toRegex()` 在 `generateVcId` 内每次调用重新编译正则 → 提取为 `companion object` 常量或改用 `filterNot { it.isWhitespace() }`。
- `vault/.../VaultRepository.kt` 大量 `"mnemonic:${address.lowercase()}".toByteArray()` 每次分配新字节数组——可接受（AAD 短），但 `lowercase()` 需带 `Locale.ROOT`（见 B-4）。

#### P-10：并发初始化单例模式可统一

`VaultRepository.get`、`AccountSdk.get`、`TinkManager.get`、`NftSdk.create`、`WebviewBridgeEngine` 等 6+ 处重复「`@Volatile instance` + `synchronized(this)` 双重检查」样板。可收敛为 core 的 `lazy` / `fun <T> singleton(init: () -> T)` 工具（见第 4 节 S-5）。

#### P-11N：NFT 批量图片解析串行网络请求

- **位置**：`nft/.../storage/room/NftStore.kt:321-330`（`resolveCredentialImages` 对请求列表顺序执行）、`NftStore.kt:161`（`resolveSwtcAvatar` 最多 4 阶段串行；原报告误标为 NftRemoteAssetResolver.kt，实际在此文件）。
- **问题**：N 个凭证的图片解析串行等待网络（每个 10s 超时），UI 场景明显卡顿。
- **修复**：`coroutineScope { requests.map { async { ... } }.awaitAll() }` 并发解析（保留顺序返回），并给整体加超时。

#### P-12N：`SwtcChainNftClient` 每请求重建 SSLContext

- **位置**：`nft/.../remote/SwtcChainNftClient.kt:101-114`（`createPinnedSslSocketFactory` 每次调用新建 `TrustManagerFactory` + `SSLContext`）。
- **问题**：证书固定场景下每请求重建 SSLContext 是昂贵操作（TLS 握手前初始化）。
- **修复**：懒加载单例 `SSLSocketFactory`（固定后不变）。

#### P-13N：`deleteSwtcNftsByOwner` N+1 查询 + `fetchAndCacheNftMeta` 冗余查询

- **位置**：`NftStore.kt:81-86,372-390`（对每个实体先 `getNftMeta` 再 `upsertNftMeta`，N 个 NFT = 2N 次 DB 操作）、`:292`（upsert 后再次 `getNftMeta` 返回，多一次查询）、`:268`（`withContext(Dispatchers.IO)` 包 suspend DAO 调用，Room 已自行调度）。
- **修复**：一次性 `SELECT ... IN (...)` 批量比对后批量 upsert；直接返回 entity；去掉冗余 withContext。

#### P-14N：NFT 元数据 JSON 双重解析

- **位置**：`NftStore.kt:274-275`（`content` 已是 `JsonObject`，`extractMetadataImageUrl(content.toString(), ...)` 又序列化后重新 `JSONObject(metadataBody)` 解析）。
- **修复**：`extractMetadataImageUrl` 增加接受 `JsonObject` 的重载，避免 parse → toString → 再 parse 的两次大字符串分配（MB 级元数据时明显）。

#### P-15N：观察查询无分页（数千 NFT 全量重发）

- **位置**：`NftDao.kt:32-33,56-75,125-131`（`observeSwtcNfts`/`observeEvmNftItems` 全量 + `ORDER BY`，Flow 每次表变更重发全量）。
- **修复**：分页（LIMIT/OFFSET 参数化）或观察接口只返回头部 N 条；`getAvatarCandidates` 等一次性读取改用 suspend DAO 而非 Flow 管道（`NftStore.kt:124-139`）。

#### P-16A：`RoomAccountStore` 六处重复的 `entities.map { it.toWalletAccount() }` + 死代码 DAO 方法

- **位置**：`account/.../store/RoomAccountStore.kt:20-23,36-39,41-44,46-49,51-54,124-127`（六处相同映射）；`AccountDao.kt:16-17`（`getAllAccountsSync`）、`:65-69`（`getSubAccountsByChain`）、`:128-129`（`deleteCurrentAccount`）主代码无调用。
- **修复**：抽取 `List<AccountEntity>.toWalletAccounts()` 扩展；删除死代码或补测试。

#### P-17A：`currentAccount` flow 每次 currentId 变化都重建订阅

- **位置**：`account/.../store/RoomAccountStore.kt:25-34`（`flatMapLatest` 在 current_account 表每次变更时重新订阅 `getAccountByIdFlow`）。
- **修复**：`distinctUntilChanged` 减少重建。

#### P-18W：`MessageDigest.getInstance("SHA-256")` 每次调用重建 + `onProgress` 回调线程语义未文档化

- **位置**：`apk-verify/.../ApkDigest.kt:16,22`（每次 `sha256Hex` 新建 MessageDigest）；`app-update/.../AppUpdateApkInstaller.kt:74`（`onProgress` 每 64KB 在 IO 线程回调一次，调用方若做 UI 操作会崩）。
- **修复**：MessageDigest 用线程局部实例复用（次要）；`onProgress` 回调线程与节流（≥100ms 一次）写入 KDoc。

#### P-13D：dapp-connect 每请求重复 `accountProvider.accounts.first()`

- **位置**：`EthMiddleware.kt:83,126,234`、`SwtcMiddleware.kt:50,81,122,161,194,225,263,318`（每个 RPC 处理器都 `.first()` 一次 Room Flow）。
- **问题**：每个 RPC 处理函数各查一次账户表（如 `sendTransaction` 一次调用触发 1 次，见 :234）；批量场景放大。账号多时是明显开销。
- **修复**：每个 RPC 处理函数内快照一次（`val accounts = accountProvider.accounts.first()` 复用），或 Provider 提供带缓存的 `getAccountsSnapshot()`。

#### P-14D：`ChainType.entries.find { it.evmChainId == chainId }` 重复实现 core 已有函数

- **位置**：`EthMiddleware.kt:250,383`（`ChainType.entries.find { it.evmChainId == chainId }`）、`NftStore` 类似逻辑。
- **问题**：core 的 `ChainType.fromChainId(chainId)`（`core/.../ChainType.kt:64`）已提供该查找，重复实现容易漏维护（如新增链后只改一处）。
- **修复**：统一改用 `ChainType.fromChainId`。

#### P-15W：`verifyApkFile` 同步重 IO（UI 线程 ANR 风险）+ 下载与哈希串行双读

- **位置**：`apk-verify/.../ApkIntegrityVerifier.kt:121-185`；`app-update/.../AppUpdateApkInstaller.kt:65-86`。
- **问题**：① `verifyApkFile` 整函数同步执行（拷贝 + 两次解析 + 全量 SHA-256），UI 线程调用即 ANR；② 下载先落盘再全量重读算哈希，多一次全文件 IO。
- **修复**：① `suspend` + `Dispatchers.IO` + 单次 `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`；② 拷贝循环内 `digest.update(buffer, 0, read)` 流式计算哈希（消除重读）。

#### P-16W：`callJsMethod` 双超时叠加（最坏等待约 2×timeout）

- **位置**：`webview-bridge/.../WebviewBridgeClient.kt:196-254`（`awaitReady(readyWaitMs.coerceAtMost(timeoutMs))` 后外层再 `withTimeout(timeoutMs)`）。
- **问题**：bridge 未 ready 时最坏等待 `readyWait + timeout`；且每次调用都重复 `ensureWebViewStarted` + `awaitReady` 检查。
- **修复**：首调用成功后缓存 ready 状态；合并等待逻辑为单次超时。

#### P-17W：`updateCacheDir` 只增不清，历史 APK 永久累积

- **位置**：`app-update/.../AppUpdateApkInstaller.kt:31-32,47`。
- **修复**：安装成功后删除；按版本保留 N 份或按时间清理。

#### P-18W：缓冲大小/超时常量 6 处重复

- **位置**：`ApkIntegrityVerifier.kt:81`(8192)、`ApkDigest.kt:12`(8192)、`AppUpdateApkInstaller.kt:29`(64×1024)、`WebviewBridgeClient.kt:199-200`(30_000/15_000) 及 wallet/did 桥接接口的重复签名。
- **修复**：收敛到 core 常量（`core.io.BufferSizes`、`core.Timeouts`），大文件拷贝统一 64KB+。

#### P-19W：`ApkDigest.sha256Hex` 的 `joinToString { "%02x".format(it) }` 逐字节格式化

- **位置**：`apk-verify/.../ApkDigest.kt:36`。
- **修复**：查表法或 `HexFormat`（见 C-2 收敛到 core `Hex`）。

#### P-20DID：did 写路径重复全量解析 DID 文档 + 每次写多一次 didStat 桥往返

- **位置**：`did/.../sdk/DidSdk.kt:509-584`（`updateDidAvatar` 对同一文档共 **3 次**解析：`:519` `JSONObject(doc)`、`readProfileField` 内部再解析、`:560` `json.toString()` 后再解析）、`:1080-1089`（每次更新额外一次 JS 桥 `didStat`）。
- **修复**：解析一次传对象；避免 `json.toString()` 后再解析；本地缓存 cid；`did-bridge` 加载的 ~9.6MB JS（`did-0.3.2.min.js`）评估懒加载/按需拆分。

---

## 4. 共通代码收敛到 core 模块分析

### 4.1 现状：core 模块目前只有模型

`core`（`com.jccdex.toolkits.core.model`）仅包含 `ChainType`、`Path`、`WalletAccount` 三个模型 + `ChainDefaults`（RPC 节点配置 object，共 4 个文件）+ `toBip44JsonArray` 扩展。**尚无任何工具层**（无 string/JSON/编码/网络/异常工具），这是收敛的天然落点。core 目前零第三方依赖、minSdk 26，非常适合承载纯 Kotlin 工具。

### 4.2 明确的重复项（已核实，按收敛价值排序）

| # | 重复内容 | 涉及位置 | 收敛方案 |
| --- | --- | --- | --- |
| C-1 | **`Path` 模型完全重复** | `wallet/.../model/WalletModels.kt:8-15` 与 `core/.../model/Path.kt` 字段与 `toString` 完全一致；`AccountOrchestrator.kt:313-327` 被迫写了 `toCorePath()` / `toWalletPath()` 两个转换函数 | 删除 `wallet.model.Path`，统一使用 `core.model.Path`（wallet 模块已依赖 core 的间接依赖链，直接加 `implementation(project(":core"))` 即可） |
| C-2 | **HTTP fetch 样板 ×6** | `NftRemoteAssetResolver.kt`、`NftStore.kt`（×2）、`SwtcChainNftClient.kt`、`AppUpdateChecker.kt`、`AppUpdateApkInstaller.kt`、`EvmRpcClient.kt` | core 新增 `HttpFetcher`（超时、大小上限、SSRF 开关、重定向、流式读写）。**已实施**——见 §9.1（`HttpFetcher` POST 顺序回归已修复） |
| C-3 | **hex 编码 ×2** | `ChecksumUtils.bytesToHex`、`ApkDigest.toHex` | core 新增 `Hex.kt`（`toHex/fromHex`，用 `HexFormat`） |
| C-4 | **JSON 工具散落** | org.json 的 `optString/optJSONObject` 防御式读取在 did（`DidSdk.readString/readElement/readJsonArray`、`DidCredentialHelper`、`DidCoreService.readProfileField`）、nft（`SwtcNftMetadataParser`、`NftRemoteAssetResolver`）、dapp-connect（`NativeResponseChannel.successPayload`）、core（`WalletAccount.toBip44JsonArray`）重复实现；Gson 在 wallet/did/nft/webview-bridge 用于类型化反序列化 | core 统一「org.json 安全读取工具」（`Json.optStringSafe` 等）+ 确定唯一 JSON 策略：**org.json 用于无类型/动态 JSON，Gson 用于 data class 反序列化**，避免同一模块混用（did 模块两者都用） |
| C-5 | **Room 样板 ×3** | `account/storage/room`、`did/storage/room`、`nft/storage/room`：几乎相同的 `RoomDatabase.getInstance` 单例 + DAO 接口 + Store 封装模式 | 不强制收敛（Room 数据库本就是各模块独立 schema），但可收敛「Database 单例模板」与「Entity↔Model 映射约定」到 core 文档/模板，或抽一个 `RoomDatabaseProvider` 辅助 |
| C-6 | **`ByteArray.wipe` / `CharArray.wipe` 仅存在于 vault** | `vault/.../util/Wipe.kt` | 这是**安全敏感工具**，所有处理密钥的模块（account 的 orchestrator、dapp-connect 的 provider）都应能引用 → 移到 core（`core.security.Wipe`）并全库推广（`AccountOrchestrator` 里 `mnemonic?.fill(0)` 应改用 `wipe()`） |
| C-7 | **地址/hex 校验逻辑** | `ChecksumUtils.toChecksumAddress`（40 位 hex 校验）、`DAppConnectSdk.isSafeUrl`、nft 的 `looksLikeIpfsIdentifier` 等 | 地址校验（EVM checksum）收敛到 core（`core.crypto.EvmAddress`），供 did / dapp-connect / account 共用 |
| C-8 | **单例样板 ×6** | 见 P-10 | core 提供 `SdkSingleton` 工具或统一约定 |
| C-9 | **统一异常体系缺失** | `account` 有 `AccountOperationError`（sealed）、`vault` 有 `VaultAuthLockedException`、`dapp-connect` 有 `UserRejectedException/ChainNotSupportedException`、`did` 无自有异常 | core 定义通用 `ToolkitException` 基类与「错误码 + 可读 message」约定；各模块 sealed 错误类型可保留但统一继承基类 |
| C-10 | **origin / URL 安全工具** | `WebOrigin.normalize`（dapp-connect）、`DAppConnectSdk.isSafeUrl`、nft 的 `SsrfGuard` | `WebOrigin`、URL 白名单、SSRF 检查收敛到 core（`core.net`），供 dapp-connect 与未来的 app-update 共用 |
| C-11 | **dapp-connect 死代码模型** | `dapp-connect/.../model/Models.kt:35-76`：`UnauthorizedException`、`TransactionException`、`JsonRpcError`、`JsonRpcResponse` **全仓库无引用**（已核实） | 删除或迁移到 core（若计划用于统一 RPC 错误模型，见 C-9） |
| C-12 | **EIP-1193 错误码** | `UserRejectedException.errorCode = 4001`、`ChainNotSupportedException.errorCode = 4902`、`UnauthorizedException.errorCode = 4100`（`dapp-connect/.../model/Models.kt`） | 收敛为 core 的 RPC 错误码常量（`core.rpc.ErrorCodes`），dapp-connect / 未来模块共用 |
| C-13 | **SHA-256 / 恒定时间比较 / hex** | `ApkDigest`（apk-verify）、`JniVerifier.hashEquals`（apk-verify）、`ChecksumUtils.bytesToHex`（did）、`SwtcNftMetadataParser`（nft） | core `core.security.Hashing`（`sha256(InputStream/File)`）+ `core.security.SecureCompare`（恒定时间）+ `core.encoding.Hex`——同时修复 M-W1（JNI 回退非常量时间）与 M-W4（两条哈希路径） |
| C-14 | **JSON 点路径读取器（逐行相同）** | `did/.../sdk/DidSdk.kt:1152-1179`（`readString`/`readElement`，Gson）与 `nft/.../storage/room/NftStore.kt:416-435`（`parseString`，同款 Gson 点路径） | core `core.json.JsonPath`（统一 Gson/org.json 策略）。**✅ 已修复（P2-3/P2-6）** |
| C-15 | **DID 文档解析/凭证查找/VC ID 生成** | `DidSdk`/`DidCoreService` 重复的 profile/services 读取；`findCredentialById`/`buildAvatarCredentialId` 与 `DidCredentialHelper` 同构 | did 模块 `DidDocumentReader` + `DidCredentialHelper` 单一实现。**✅ 已修复（P2-6）** |
| C-16 | **NFT 标准常量大小写不一致** | `DidSdk.kt:1307-1308`（`"jingtumnft"`/`"erc-721"`）vs `DidCredentialHelper.kt:15-16`（`"jingtumNFT"`/`"ERC-721"`） | core 常量（`core.nft.NftStandards`），消除隐式字符串协议。**✅ 已修复（P2-6）** |
| C-17 | **HTTP/SSRF/钉扎工具** | `NftRemoteAssetResolver`、`SwtcChainNftClient`（钉扎 TrustManager）、`AppUpdateChecker`、`AppUpdateApkInstaller`、nft `SsrfGuard` | core `core.net.Http`（HTTPS 强制 + 同源重定向 + SSRF + 大小上限 + 可选钉扎），与 C-2 合并实施 |
| C-18 | **URI→临时文件拷贝 / 节流器 / 超时与缓冲常量** | `ApkIntegrityVerifier.copyUriToTemp`、`AppUpdateCheckThrottle`、六处超时/缓冲常量、`WebviewBridgeClient`/`WalletSdk` 各自 `Gson()` | core `core.io.FileUtils`、`core.util.Throttle`、`core.Timeouts`、`core.json.Json`（单例 Gson） |
| C-19 | **did 模块内部重复（非跨模块）** | `DidSdk.isSwtcDid/isEthrDid` 死代码（`:1245,1247` 无引用）、`PublishDidResult.message` 死字段、`DidStatResult` 仅单字段 | 清理死代码；`PublishDidResult` 收敛到 `DidWriteResult` 风格 |
| C-20 | **Base64 / 链 ID hex / 缓存键工具** | `SwtcChainNftClient.kt:3,134` 直接用 `android.util.Base64`（仅 Android 可用不可单测）；`NftStore.kt:142,245` 的 `"0x${chainId.toString(16)}"`；`NftStore.kt:448-484` 的 `joinToString("\|")` 缓存键（字段含 `\|` 会键碰撞） | core `core.encoding.Base64Utils`（平台委托）、`ChainType.toEvmChainIdHex()`（挂在已有 ChainType）、core 分隔符转义工具 |
| C-21 | **NFT 元数据解析合并** | `SwtcNftMetadataParser.extractMetadataFields`（image/name/description）与 `NftRemoteAssetResolver.extractMetadataImageUrl`（**仅 image**）重叠 | `extractMetadataImageUrl` JSONObject/JsonObject 重载 + 单次解析。**✅ 已修复（P2-6）** |
| C-22 | **「非根账户」判定 SQL 与 core 模型语义不一致** | `AccountDao.getNonRootAccount` 的 SQL 谓词 `(isHD=1 AND parentId IS NOT NULL) OR isHD=0`（`AccountDao.kt:42`）vs core `WalletAccount.isSubHD()`（`core/model/WalletAccount.kt:21`）——`isHD=true, parentId=null, path 非 root` 时 SQL 判「根」、core 判「子」 | 非根判定收敛到 core 单一来源（`AccountClassification` Kotlin 谓词 + Room SQL 片段）。**✅ 已修复（P2-7）** |
| C-23 | **JS 引号转义 / SWTC 金额币种正则 / DAppMethod 映射** | `DAppConnectSdk.jsQuote`（`:132-133`）；`SwtcBatchTransactions.kt:17,96,118` 的金额/currency-issuer 正则；`WebAppInterface.postMessage` 的 `when(method)` 27 个 case 分支（28 个 DAppMethod 值）分发 | core `core.js.JsQuote`（含 `\u2028/\u2029` 转义）、`core.swtc.Regexes`、`core.rpc.MethodDispatcher`（方法→handler 映射表替代巨型 when） |
| C-24 | **wallet 桥接双接口近乎重复** | `wallet/.../AndroidWalletWebRuntime.kt:9-33`（`IWalletWebBridgeClient`）与 `:65-84`（`IWalletBridge`）字段与语义几乎一致 | 合并为单一接口（或统一由 core 桥接抽象提供），消除 2×N 方法签名维护成本。**✅ 已修复（P2-8a）**：仅保留 `IWalletBridge` |

### 4.3 建议的 core 模块扩展结构（目标形态）

```text
core/src/main/java/com/jccdex/toolkits/core/
├── model/          # 已有：ChainType / Path / WalletAccount
├── json/           # C-4：org.json 安全读取工具 + JSON 策略约定
├── net/            # C-2/C-10：HttpFetcher（大小上限+SSRF）、WebOrigin、URL 白名单
├── crypto/         # C-3/C-7：Hex、Base58、EVM 地址 checksum、Keccak 封装
├── security/       # C-6：Wipe、常量时间比较（MessageDigest.isEqual 封装）、敏感内存管理约定
├── coroutines/     # M-1：CancellationException 安全捕获工具（safeRunCatching）
├── error/          # C-9：ToolkitException 基类
└── util/           # C-8：singleton、locale 安全 lower/upper、正则常量
```

**注意**：core 目前是「零 Android 依赖的纯模型模块」，上述工具中 `HttpFetcher`、`WebOrigin`（android.net.Uri）依赖 Android——建议拆分为 `core`（纯 JVM 工具）与 `core-android`（Android 工具），或按需把 Android 相关工具放在各模块的 `internal` 层共用。若坚持单 core，需接受 core 引入 `androidx.core` 依赖（会改变 core 的「纯模型」定位，需团队决策）。

### 4.4 收敛优先级路线图

1. **P0（改动小、收益大、无风险）**：C-1 Path 统一（删除转换函数）；C-6 Wipe 上移 core；C-3 hex 统一。
2. **P1（收益大、需评审）**：C-2 HttpFetcher 统一（连带修复 M-3 大小限制、统一 SSRF）；C-4 JSON 策略统一。
3. **P2（架构演进）**：C-7/C-10 安全工具、C-9 异常体系、C-8 单例工具；考虑 core-android 拆分。

---

## 5. Kotlin 最佳实践

### 5.1 函数与 API 命名

**做得好的**（值得保持）：

- 动词开头的动作函数：`importPrivateKey`、`deriveSubAccount`、`resolveAndSaveDid`、`changePassword`——命名即文档；
- 返回布尔谓词用 `is`/`has`：`isEvmChain()`、`isRootHD()`、`hasPassword()`、`isAuthLocked()`；
- 安全语义通过命名明确：`getPrivateKeyUnlocked`（internal，明确「会话解锁后可用」）、`ensureUnlockedWithPassword`；
- 领域概念命名准确：`VaultSession`、`AuthLockout`、`WebOrigin`。

**改进建议**：

- **B-1**：`SwtcMiddleware.sendTransactionWithPassword(txParams, password, origin)` 的 `password` 参数**完全未被使用**（`SwtcMiddleware.kt:216-247`），是误导性 API——调用方会以为密码参与了校验。要么实现密码验证路径，要么删除参数并改名（如 `sendTransactionForNative`）并在 KDoc 说明 secret 来源。
- **B-2**：`EthMiddleware` 私有方法名 `isZeroOrEmpty`（`EthMiddleware.kt:268`）实际语义是「hex 值是否为 0 或空」，建议 `isZeroHexOrEmpty`。
- **B-3**：`NftStore` 的 `buildCredentialResolutionKey` / `buildCredentialAssetKey` 输出格式（`"image:..."`、`"nft:chainId:contract:tokenId"`）是隐式协议，建议提取为命名常量或独立 `CacheKey` 类，避免魔法分隔符 `|`、`:` 在字符串间漂移。
- 统一命名风格：项目内 `getMnemonic`（vault）与 `fetchMetadataFields`（nft）、`readString`（did）混用 get/fetch/read 前缀，建议模块内统一（对外 API 用 get/query，IO 操作用 fetch/load）。

### 5.2 空安全与异常处理

- **B-4**：全库 20+ 处 `lowercase()` 未指定 Locale（`VaultRepository.kt:718-722` 的 AAD、`DidSyncService.kt:26`、`NftStore.kt` 多处、`ReleaseChecksumsFile.kt:68-69`）。地址/哈希/域名规范化必须用 `lowercase(Locale.ROOT)`（土耳其语 locale 下 `I→ı` 会导致 AAD 不匹配、查询失效）。同理 `equals(ignoreCase = true)` 在敏感比对处（vault 地址匹配）应改为先 `lowercase(Locale.ROOT)` 再比较。
- **B-5**：`!!` 非空断言残留：`DidCredentialHelper.kt:75,87`（`data.restrictions!!`）——唯一调用点 `DidSdk.buildGenerateVcParams` 先调 `validateCredentialData`（上游 require 守住），**NPE 风险基本被守卫**，但 `!!` 仍是脆弱写法。建议改为 `requireNotNull(data.restrictions)` 并给出清晰 message。
- **B-6**：`VaultRepository.getBiometric()` 抛 `Error`（见 L-1）——业务失败必须用异常类型而非 `Error`。
- **B-7**：`runCatching` 的使用（did/nft/apk-verify/app-update 共 24 处）多数是「解析容错」场景（合理），但包裹 suspend 调用时吞掉 `CancellationException`（见 M-1）。建议 core 提供 `safeRunCatching`（内部先重抛取消）。
- **B-8**：`catch (_: Throwable)`（`VaultRepository.kt:97,263`、`WebviewBridgeClient.kt` 多处）应尽量收窄；WebView 的 `evaluateJavascript` 异常捕获用 Throwable 可接受（平台 API 抛 `Throwable` 子类不确定），但业务逻辑处应收窄。

### 5.3 协程与并发

- **B-9**：`WebAppInterface.kt` 23 处 `CoroutineScope(Dispatchers.IO).launch { }` fire-and-forget：无 Job 管理、无生命周期绑定、无取消。DApp 页面跳转/WebView destroy 后协程仍运行，回调 `NativeResponseChannel` 时 WebView 已销毁（已有 try/catch 兜底但会打日志噪音）。建议：注入统一 `CoroutineScope`（宿主生命周期），或改用 `SupervisorJob` + 显式 `cancel` 钩子。
- **B-10**：`CachingSecretProvider` 自建 `CoroutineScope(SupervisorJob() + Dispatchers.Default)` 做延迟清理——无生命周期管理（单例持有），进程级可接受，但建议注释说明生命周期归属。
- **B-11**：`AccountOrchestrator.runOperation` 与 `deriveSubAccount` 的 `catch (e: Exception)` 会把 `CancellationException` 转成业务错误返回（见 M-1），同时 `runOperation` 的泛型签名 `block: () -> AccountOperationResult<T>` 内嵌 suspend 调用（`runOperation { ... suspend 调用 ... }`）实际是 `inline` + 非 suspend 类型参数——inline lambda 内可调用 suspend 是因为内联到 suspend 函数体，工作正常但可读性差，建议改为 `suspend fun <T> runOperation(block: suspend () -> ...)`。
- **B-12**：`VaultRepository` 的 `Mutex` 使用正确（所有写操作 `mutex.withLock`），值得保持；但 `unlock/verifyPassword` 读路径未加锁（`vaultStore.data.first()` + 后续写入），DataStore 自身保证原子读，可接受。

### 5.4 不可变性与数据类

- **B-13**：`CachingSecretProvider.Entry(val value: String, val at: Long)` data class 持有私钥字符串——建议改为内部 `CharArray` + `wipe()` 语义（见 M-2）。
- **B-14**：`VaultPrivateKeyImport` 手动实现 `equals/hashCode`（`contentEquals`）——这是 data class + ByteArray 的正确姿势，但应加 KDoc 说明「为什么不用 data class 默认实现」（避免后人改回默认）。
- **B-15**：`ChainType`（core）与 `did.model.ChainType`（typealias）——已用 typealias 收敛，好；但 `did/model/ChainModels.kt` 的 typealias 让 `import com.jccdex.toolkits.did.model.ChainType` 与 core 的 `ChainType` 在 IDE 中可互换，易混淆，建议 did 内直接 import core 类型并删除 typealias。

### 5.5 其他

- **B-16**：魔法数字：`EthMiddleware.kt:319-320` `"0x5208"`（21000 gas）、`Argon2idKdf.kt:55-61`（内存参数）、`WebviewBridgeClient.kt:199` `30_000L` 超时——建议命名常量 + 注释（`VaultAuthLockout` 的锁定时长已是具名常量 `LOCK_MS_LEVEL_0/1/2`，保持）。
- **B-17**：`WebviewBridgeClient.callJsMethodAs` 用 `Class<T>` 反射式 Gson——建议对热点调用改为 `reified` + `TypeToken` 缓存，减少每次反射开销（次要）。
- **B-18**：`DAppConnectSdk` 是纯工具 object，内部 `didSdk` 可变字段非线程安全（`setDidSdk`/`getDidSdk` 无同步）——建议 `@Volatile` 或移除（属于宿主注入职责）。
- **B-19**：KDoc 质量整体高（`VaultRepository`、`SwtcMiddleware.batchTransactions`、`WebviewBridgeClient` 的注释解释了安全决策），继续保持；建议把「为什么」（安全权衡）写在注释里的习惯推广到 `ApkIntegrityVerifier`、`NftStore` 的 key 构造等隐含协议处。

### 5.6 各子代理补充的最佳实践问题（did / wallet / apk-verify / app-update）

- **B-20**：`did/.../DidSdk.kt:64` 公共 API 用 `error("Unsupported chain type")` 抛异常——`error` 隐式抛 `IllegalStateException` 且调用方无法类型化处理（`DidSyncService` 整批同步因此中断）。应返回密封结果或 `require` + 明确错误。
- **B-21**：布尔返回值 API 无法表达失败原因：`DidSdk` 的 `uploadInitialDidDoc/updateDidNickname/updateDidAvatar/publishDidDelete` 等 5 处返回 `Boolean`（4 个写 API + `isSupportedRemoteAssetUrl` 透传），链上拒绝/网络错误/参数非法无法区分（`DidWriteResult` 已是正确示范，应推广）。
- **B-22**：函数名与副作用：`DidCoreService.resolveAndSaveDid` 已含 AndSave（写库副作用已被命名披露），但「修改 pending 状态」未披露，建议 KDoc 说明或拆分（`DidSdk.nickname(doc)` 实为「提取昵称」，建议 `extractNickname`）。命名应暴露全部副作用。
- **B-23**：`DidSdk` 两个 `readString` 重载（`:1152,1175`）语义耦合 Gson 实现且调用点歧义，建议合并为单一函数并统一 JSON 库。
- **B-24**：魔法数字继续补充：`DidCredentialHelper.kt:187`（`chainId = 315` 硬编码 SWTC 链 ID）、`:198`（`chainId = selectedAvatar.chainId ?: 1`）；`EthMiddleware` 的 `"0x5208"`（21000 gas）。应命名常量并引用 `ChainType`。
- **B-25**：`AndroidWalletWebRuntime` 构造器副作用（属性初始化即 `initialize()`+`start()` 启动 WebView，构造失败/耗时不可控、难测试）——改为显式 `start()` 生命周期。
- **B-26**：`WalletSdk` 的 `@Volatile bridge` + `destroy()` 未同步，`initialize`/`destroy` 并发竞态（`@Synchronized` 只包了 initialize 的判空赋值）。
- **B-27**：`AppUpdateApkInstaller.downloadAndVerify` 5 个参数（context/apkUrl/remote/apkNamePrefix/onProgress）仍偏多 → 提取 `DownloadRequest` data class；`AppUpdateCheckThrottle.force: Boolean` 无默认值且语义含糊 → `enum ForcePolicy`。
- **B-28**：`ApkDigest.kt:26` 的 `while (stream.read(buffer).also { bytesRead = it } != -1)` 副作用写法晦涩，建议显式 `var n: Int; while (stream.read(buffer).also { n = it } != -1)` 或 `do/while`。
- **B-29**：敏感 data class 的 `toString`（见 M-W7）：`Keypair`/`Mnemonic`/`SubWallet` 等含私钥字段，必须覆写 `toString` 或 `@Redacted`。
- **B-30**：`WalletSdk` 多处 `.toBoolean()` 掩盖错误语义（JS 返回非 `"true"` 字符串静默变 false，与「校验失败」无法区分）——返回结构化结果。
- **B-31**：`DidRoomDatabase.exportSchema = false` 阻碍未来迁移验证（同 L-14），建议开启并纳入版本控制；`NftRoomDatabase` 无迁移策略（见 M-11N）。
- **B-32**：`@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")` 一揽子压掉安全 lint——缩小抑制范围并注释理由。
- **B-33**：nft 死代码分支：`NftStore.buildCredentialAssetKey`（`:457-484`）的 `resolvedUrl.isNotBlank()` 恒真（调用点 `?: return null` 已保证），后续 `"nft"/"metadata"/"image"` 分支**永远不可达**——给人错误缓存语义暗示，应删除或重构。
- **B-34**：nft 重复实现：`NftStore.looksLikeJson`（`:443-446`）与 `NftRemoteAssetResolver.looksLikeJsonPayload`（`:192-195`）逻辑完全一致；`NftStore.resolveCredentialImage` 与 `resolveRemoteImageUrl` 纯透传——模块内重复应合并（C-14/C-21 一并处理）。
- **B-35**：命名隐藏副作用：`resolveRemoteImageUrl`/`resolveCredentialImage` 名为「解析」，实际会发起网络请求并写入全局缓存——建议改名（如 `fetchResolvedImageUrl`）或文档注明，避免 UI 线程/高频路径无意触发网络。
- **B-36**：顶层函数污染包命名空间：`SwtcNftMetadataParser.extractSwtcMetadataUri` 等顶层函数过于通用，调用方被迫 `import ... as parseSwtcMetadataUri`（`NftStore.kt:29`）——移入类/对象。
- **B-37**：538 行 `NftStore` 混合职责（存储、网络抓取、URL 归一化、VC 解析、缓存键构建、链类型判断）——按 store / resolver / parser 拆分（与第 4 节 core 下沉配套）。
- **B-38**：魔法字符串/JSONPath：`"tokenUri"`、`"image"/"image_url"/"imageUrl"`、`"$.credentialSubject.tokenId"` 等散落（`SwtcNftMetadataParser.kt:14`、`NftRemoteAssetResolver.kt:106`、`NftStore.kt:93-94` 等）——集中为常量或解析器。
- **B-39**：nft 的 `Nft` data class 混入展示态字段 `hasLocal`（语义「本地缓存有图」还是「本地文件」不清晰）；`issuanceDate: String` 非空但可为 `""`——建议文档化或拆分为展示模型。
- **B-40**：account 用异常类型做控制流：`AccountOrchestrator.kt:78,271` `catch (_: IllegalArgumentException)` 判断密码错误——vault 的 `removeAddress/clearAllData` 已用布尔/领域异常，orchestrator 应改领域错误。
- **B-41**：account 错误类型风格不一致：`AccountOperationError.WrongPassword` 与 `Failure` 均为 `data class`、其余为 `data object`（`AccountOperationError.kt:4-19`）；`AccountOperationResult.Error` 与 `kotlin.Error` 同名易混淆。
- **B-42**：魔法数字补充：`Path(chain = 0, ...)`（`AccountOrchestrator.kt:98`）、`?: -1`（`RoomAccountStore.kt:132`）、`CurrentAccountEntity.id = 1`（`CurrentAccountEntity.kt:9`）应具名常量；`getMaxIndexByChain` 的 `-1` 哨兵建议返回 0 或 null。
- **B-43**：`VaultPrivateKeyImport` 覆写 equals/hashCode 会**读取私钥字节做内容比较**（`vault/model/VaultPrivateKeyImport.kt:10-26`），与 wipe 语义冲突且 data class 语义暗示可拷贝——改为普通 class 或移除 equals/hashCode。
- **B-44**：`updateAccountNameByAddress` 按地址批量改名，跨链同地址账户被一并改名（`AccountDao.kt:104-108`、`AccountSdk.kt:44-47`）——语义不透明，建议限定 chain 或文档化。
- **B-45**：测试钩子混入生产 API：`AccountSdk.createForTest/resetForTest`（`AccountSdk.kt:116-120`）建议标注 `@VisibleForTesting`（nft 的 `SsrfGuard.enabled` 同理，见 M-12N）。
- **B-46**：`WebAppInterface.dappOrigin`（`:47`）非 `@Volatile` 但被 JS 线程（`postMessage`）与主线程（`setOrigin`）并发读写——跨线程可见性无保证，应为 `@Volatile` 或统一主线程访问。
- **B-47**：`WebAppInterface.postMessage` 的 `when(method)` 27 个 case 分支（覆盖 28 个 DAppMethod 值）+ else 样板重复（每个 handler 都做「取 params → 校验长度 → 调 middleware → 发响应」）——可收敛为「方法 → handler 注册表」模式（C-23），同时天然获得统一参数校验与错误映射。
- **B-48**：`MiddlewareInterfaces.kt` 多处接口方法返回 `Any`（如 `getBlockNumber` 等）——类型擦除损失，调用方需强转；应返回具体类型或泛型（配合 C-9 统一结果类型）。

---

## 6. 与既有审计文档的对照

| 本文档发现 | 既有审计 | 状态 |
| --- | --- | --- |
| C-01~C-05（高危） | `SECURITY_AUDIT.md` §3.1 | 已修复（`SECURITY_REAUDIT_FIX_PLAN.md` Phase A~D） |
| H-01~H-07 | `SECURITY_AUDIT.md` §3.2 | 已修复（origin 缓存键、origin 传递、evaluateJavascript 注入、internal 门禁等） |
| M-2（私钥明文缓存） | H-01 / C-04 残余 | **部分未修复**：CachingSecretProvider 内存明文缓存仍在（设计取舍，建议 wipe 化） |
| M-4 / H-D4（无逐笔确认） | H-02 残余 | **未完全覆盖**：sendTransaction/signCredentialForDApp 无逐笔确认 |
| H-W1（回调伪造） | C-03（响应通道） | **新暴露面**：`JsPromiseGateway.onPromiseResult` 仍可被页面 JS 伪造 id 触发（C-03 只修了 native→JS 通道） |
| H-DID4 / H-W1（JS 桥密钥） | C-04 | **架构性残余**：私钥以 String 经 WebView 传递，无法 wipe |
| H-A1 / H-A2（账户导入） | 未覆盖 | **新增**（H-A2 为资金可用性缺陷） |
| H-D1（postMessage 边界） | M-05 残余 | **未完全覆盖**：origin 依赖宿主预设，未实时校验调用 frame |
| H-DID2 / H-DID3（DID 缓存一致性） | 未覆盖 | **新增** |
| H-W2 / H-W3 / H-W4（更新链路） | 未覆盖 | **新增**（fail-open 证书校验、HTTPS、自校验信任根） |
| M-W1（JNI 降级） | 未覆盖 | **新增** |
| M-1（取消被吞） | 未覆盖 | **新增**（最佳实践层） |
| M-3（无大小上限） | 未覆盖 | **新增** |
| 第 4 节收敛分析 | 未覆盖 | **新增**（架构建议） |
| 第 5 节最佳实践 | 未覆盖 | **新增** |

---

## 7. 结论与优先行动清单

### P0——立即（资金安全 / 数据不可逆损坏，本周）

1. **H-A2**：`importSubAccount` 空私钥入库——禁止空私钥导入（`require(privateKey.isNotEmpty())`），子账户改为从根助记词派生。
2. **H-A1**：`importHdWallet` 查重提前到清除动作之前。
3. **H-W2 / H-W3**：更新校验链 fail-closed（证书提取失败必须拒绝）+ 强制 HTTPS/同源重定向。
4. **H-W1**：桥接回调双向 nonce + 页面来源校验（联动 M-6）。
5. **H-D2 / H-D1**：批量交易上限；`postMessage` 每次实时校验 origin + 缩短私钥缓存窗。
6. **H-DID2 / H-DID3**：DID 创建/删除的本地缓存一致性（时间宽限期 + 持久化 pending 状态）。

### P1——短期（本周~两周）

1. **M-1**：全库 `catch (e: Exception)` / `runCatching` 先重抛 `CancellationException`（account/did/nft/app-update/dapp-connect）。
2. **M-3 / M-8N / M-9N**：HTTP 响应统一大小上限（core `HttpFetcher`）。（注：原「M-5W」为不存在的编号，实为 M-9N。）
3. **M-W1 / M-W4**：恒定时间比较 + 哈希路径统一走 JNI。
4. **H-DID1**：`signCredentialForDApp` 增加强制确认回调与 issuer 校验。
5. **M-DID2 / M-DID3**：写操作禁止基于陈旧 baseDoc 发布；`uploadInitialDidDoc` 增加链上已存在保护。
6. **M-DID1 / M-DID6**：凭证验证的「网络失败」与「过期解析失败」语义修正（不得默认通过/撤销）。
7. **M-W7 / B-29**：钱包模型 data class `toString` 脱敏。
8. **C-1**：删除 `wallet.model.Path`，统一 `core.model.Path`。
9. **B-4**：全库 `lowercase()` → `lowercase(Locale.ROOT)`。
10. **M-13N**：chainId 格式统一归一化（core `ChainType.toEvmChainIdHex()`）。
11. **M-18A / M-19A**：账户查重统一按地址全量检查；orchestrator Mutex 提升为共享实例。
12. **M-21A**：`runOperation` 透传 `VaultAuthLockedException` 为领域错误。
13. **M-D5 / M-D6**：禁止中间件静默切换全局链状态；响应队列设容量上限 + RPC 速率限制。
14. **M-D7**：gas 估算失败禁止静默回退 21000。

### P2——架构收敛（两周+，需设计评审）

1. **C-2/C-13/C-17**：core 新增 `HttpFetcher` / `Hashing` / `SecureCompare` / `Hex`（消除 6 处 HTTP 样板、2 套 hex、双哈希路径）。
2. ~~**C-14/C-15/C-16/C-21**~~：JSON 点路径、VC ID/凭证查找、NFT 标准常量与元数据解析收敛。**✅ P2-6 已完成**。
3. ~~**C-22**~~：「非根账户」判定 SQL 与 core `isSubHD()` 收敛为单一来源。**✅ P2-7 已完成**（`AccountClassification`）。
4. **M-4 / H-D4**：签名/转账类 RPC 统一逐笔确认回调——**交易类宿主**（ccdao）必须注入 UI；非交易类（jdid）可不注入。
5. **B-9 / M-DID8**：WebAppInterface 协程作用域注入与生命周期绑定；`DidSdk.close()` 生命周期出口。
6. **C-9 / C-12**：统一异常体系与 RPC 错误码。
7. **M-14A / M-DID4 / M-11N / M-20A**：`Path.chain` 持久化；Room 唯一索引、`@Upsert`、`@Transaction` 与迁移策略。
8. **B-37**：NftStore 按 store / resolver / parser 拆分（消除死代码与重复）。
9. ~~**X-1 / X-2 / X-4**~~：统一桥接运行时——合并 wallet/did 双 WebView、消除 `WebviewBridgeEngine`/`JsPromiseGateway` 死公共 API、明确 callbackMap 隔离契约。**✅ P2-8a/8b 已完成**（`4d2311f` / `42a5f32`；含 C-24）。

---

## 8. Pro 复核（二次对抗式分析 + 跨模块补充）

> 第二轮用 deepseek-v4-pro 对首轮报告中**依赖子代理、未亲自逐行核实**的高价值发现做了对抗式核验，并补做模块级审查易遗漏的**跨模块/架构级**分析。结论：**抽样核验的条目全部与源码一致**；并新增 4 项跨模块发现。（注：抽样未覆盖全部条目，完整复核见 `CODE_REVIEW_ANALYSIS_VERIFICATION.md`，其中指出若干计数/参数/命名类细节偏差。）

### 8.1 对抗式核验结果（抽样高价值发现）

| 发现 | 核验结果 |
| --- | --- |
| M-DID1 网络失败默认「已撤销」 | ✅ 一致：`DidSdk.kt:932-938` 返回 `fetchFailed=true`，`:899` 只读 `isUpdate` 忽略 `fetchFailed` |
| M-DID2 陈旧 baseDoc 回退 | ✅ 一致：`DidSdk.kt:1091-1109` 链上 resolve 失败 `runCatching{}.getOrNull()` 后静默回退 `core.getDidDocument(did)?.doc` |
| M-DID6 过期解析失败当「未过期」 | ✅ 一致：`DidSdk.kt:887-892` 解析失败 `getOrNull()` 返回 null → 跳过过期检查 |
| H-DID2 创建保护标志一次性失效 | ✅ 一致：`DidCoreService.kt:77-87` `pendingCreateDids` 首次 miss 即 remove，二次 resolve 走 `store.delete` |
| H-DID3 删除后被旧文档复活 | ✅ 一致：`DidCoreService.kt:36-39` `localDoc==null` 时先 upsert，`:44-48` 的 `pendingDeleteUpdated` 保护无法触达 |
| H-D1 / M-D5 / M-D7 / H-W1 / H-W2 / M-W1 等 | ✅ 首轮已亲自核实（见各条目正文） |

### 8.2 跨模块/架构级补充发现

#### X-1：钱包与 DID 各自独立创建隐藏 WebView——密钥攻击面 ×2、内存 ×2

> **✅ 已修复（P2-8b，`42a5f32`）**：生产路径默认 `SharedWebviewBridge` + `unified-bridge.html`（单 WebView）；`ToolkitBridgeRuntime` 统一 shutdown/reload。legacy 单域页保留供测试注入。

- **位置**（修复前）：`wallet/.../AndroidWalletWebRuntime.kt:94`（`WebviewBridgeClient()` 加载 `wallet-bridge.html`；实例化在 :36）、`did/.../AndroidDidWebRuntime.kt:34,67`（`WebviewBridgeClient()` 实例化在 :34、加载 `did-bridge.html` 在 :67）。
- **问题**：完整集成（account + vault + wallet + did + dapp-connect）时存在 **2 个独立隐藏 WebView**（wallet 一个、did 一个），各占一个 renderer 进程、各加载 ~10MB 级 JS（did 侧 `did-0.3.2.min.js` 9.6MB）。这意味着：(a) H-DID4/C-04 的「私钥进入 WebView 进程」风险被**翻倍**——钱包侧和 DID 侧是两个独立 JS 上下文，攻击面不重叠但均暴露；(b) 内存常驻（两个 renderer 进程 + 两份 JS 堆）；(c) 生命周期管理各自为政（`destroy()` 各调各的）。
- **修复**：评估合并为**单一共享桥接运行时**（wallet 与 did 共用同一个 `WebviewBridgeClient` + 按需加载不同 bridge 页/协议），或至少在宿主层统一生命周期；至少文档化「每 SDK 一个 WebView」的内存/进程成本。

#### X-2：`WebviewBridgeEngine` / `JsPromiseGateway` 是无人调用的死公共 API

> **✅ 已修复（P2-8a，`4d2311f`）**：删除 `WebviewBridgeEngine` / `JsPromiseGateway`；唯一入口 `WebviewBridgeClient`；网关收敛为实例级 `PromiseGateway`。

- **位置**（修复前）：`webview-bridge/.../WebviewBridgeEngine.kt:6-35`（object 门面）、`JsPromiseGateway.kt:83-115`（object 单例）。
- **问题**：全仓库（除 webview-bridge 自身外）**无任何调用**——wallet/did 都绕过 `WebviewBridgeEngine`，直接 `new WebviewBridgeClient()`（`AndroidWalletWebRuntime.kt:36`、`AndroidDidWebRuntime.kt:34`）。`WebviewBridgeEngine` 这个被 README/注释暗示为「隐藏 WebView 运行时」的公共入口实际上是**死代码**，且与 `WebviewBridgeClient` 的实例级 API 并存形成两套心智模型。
- **修复**：要么删除 `WebviewBridgeEngine`/`JsPromiseGateway`（明确唯一入口是 `WebviewBridgeClient`），要么让 wallet/did 统一委托它（配合 X-1 的单一共享运行时）。二选一，消除「哪个才是正确入口」的歧义。

#### X-3：依赖图无环，但「组合根」位于 account 模块，方向性略偏

- **位置**：`account/build.gradle.kts`（`account → vault + wallet + core`）、`AccountOrchestrator`（同时编排 core 模型 + vault 加密 + wallet JS 派生）。
- **问题**：README 把 `:account` 定位为「钱包账户元数据」，但 `AccountOrchestrator` 实际是**跨三模块的组合根**（HD 派生经 wallet JS、助记词经 vault）。依赖方向本身无环（core 叶子、vault 独立、wallet→webview-bridge），但 orchestrator 放 account 使「元数据」模块承载了「业务编排」职责，与模块定位表述不符；未来若拆分核心 SDK 与上层编排，该职责应上移到独立 `:orchestrator` 或宿主层。
- **修复**：文档化 account 的实际职责（元数据 + 账户编排），或把 `AccountOrchestrator` 上移；不影响功能，属架构清晰度问题。

#### X-4：`JsPromiseGateway.callbackMap` 全局单例 + `WebviewBridgeClient` 实例级并存，回调隔离边界模糊

> **✅ 已修复（P2-8a/8b）**：全局 `JsPromiseGateway` 已删；生产共享桥为「1 WebView ↔ 1 `callbackMap`」；契约见 `webview-bridge/README.zh-CN.md`。

- **位置**（修复前）：`JsPromiseGateway.kt:83-115`（`object` 委托 `PromiseGatewayImpl`）、`WebviewBridgeClient.kt:25-30`（构造时若传 `JsPromiseGateway` 则共享其 `callbackMap`）。
- **问题**：`WebviewBridgeEngine` 用 `JsPromiseGateway`（全局单例 callbackMap），而 wallet/did 各自 `new WebviewBridgeClient()` 用默认 `PromiseGatewayImpl`（实例级 callbackMap）。两种回调隔离语义并存——一旦有人误用 `WebviewBridgeEngine` 与实例级 client 混搭，`callbackMap` 的 id（UUID）理论上不冲突但隔离语义不清晰，且全局单例的 `callbackMap` 经 `object` 公开可读（L-24）。
- **修复**：随 X-2 收敛为唯一入口后，明确「每个 WebView 一个 callbackMap」的隔离契约。

### 8.3 复核结论

首轮报告的模块级发现经抽样核验**全部准确**，新增的跨模块发现（X-1 双 WebView、X-2 死公共 API）是模块级审查的盲区，建议纳入架构收敛（第 4 节 P2）一并处理：X-1/X-2/X-4 可合并为「**统一桥接运行时**」这一项架构决策，与 C-2/C-17（HTTP 收敛）、C-9（异常体系）同属 core 化路线的第二步。

> **状态（2026-09-03）**：X-1/X-2/X-4（及 C-24）已由 **P2-8a/8b** 落地收口；细节见 `docs/review/README.md` §16 P2-8 实施记录。

---

## 9. 实施记录与回归复盘（C-2 `HttpFetcher` / NFT 图片加载）

> **记录日期**：2026-09-01  
> **关联条目**：C-2、C-17、M-3、M-9N、P2 §7  
> **触发场景**：按本报告收敛 HTTP 层后，jdid-android / ccdao-connector-android 中 EVM NFT 凭证缩略图全部回退为默认占位图；收敛前同一 VC（如 `did:ethr:…#nft-0x5B5b422A…-4-…`）可正常显示。

### 9.1 已实施的收敛范围

按 C-2 / M-3 / M-9N 将下列调用方从手写 `HttpURLConnection` 迁移到 `core/net/HttpFetcher.kt`：

| 模块 | 原实现 | 迁移后 `HttpFetcher` 配置要点 |
| --- | --- | --- |
| `nft/.../EvmRpcClient.kt` | POST `eth_call`，`instanceFollowRedirects = true` | `postJson`；`httpsOnly = false`；`redirectPolicy = SAME_HOST_HTTPS` |
| `nft/.../NftRemoteAssetResolver.kt` | GET 元数据，`instanceFollowRedirects = false` | `get`；`httpsOnly = false`；`redirectPolicy = NONE`；`ssrfCheck = null`（见 §9.4） |
| `nft/.../NftStore.kt` | `fetchJson` / `fetchText` 同上 | 同上 |
| `nft/.../SwtcChainNftClient.kt` | POST JSON-RPC | 同 EvmRpcClient |
| `app-update/...` | GET / 流式下载 | `httpsOnly = true`；`redirectPolicy = SAME_HOST_HTTPS` |

同时落地：`readTextLimited` 响应体大小上限（M-3/M-9N）、`core/json/JsonPath`（C-14）、`NftStore.resolveCredentialImage(CredentialImageRequest)` 在 `imageUrl`/`metadataUri` 为空时通过 `ethTokenUriResolver` 走链上 `tokenURI` 回退（补齐 EVM VC 缺 metadata 字段场景）。

### 9.2 回归现象

- **用户可见**：首页 / 凭证列表 / 头像选择器中 EVM NFT 图片恒为默认图；SWTC 或已有 `credentialSubject.image` 的 VC 不受影响。
- **链上数据正常**：同一 token 的 `tokenURI(4)` → IPFS metadata → `image` 在网关 `https://ipfs.jccdex.cn/ipfs/…` 可 200 访问。
- **SDK 内部**：`EvmRpcClient.ethCall` → `HttpFetcher.postJson` 失败（日志/单测可见 `HttpException(code=-1, message=connect in progress)` 或 RPC 全节点 fallback 后返回 `null`）→ `resolveEthrTokenUri` 为 `null` → `generateEthrNft` / `resolveCredentialImage` 无图。

### 9.3 根因（`HttpFetcher` 初版实现缺陷）

初版 `HttpFetcher` 在 **`request()` 内统一调用 `openWithRedirects(url)`**，而该函数在**尚未设置 HTTP 方法、尚未写入 POST body** 时就访问了 `connection.responseCode`：

```text
postJson(url, body)
  → request(url) { … set POST … write body … readBody }
       → openWithRedirects(url)   // 此处已触发 responseCode（等价于隐式 GET）
       → block(connection)        // 再改 requestMethod=POST 为时已晚
```

`HttpURLConnection` 语义：**一旦读取 `responseCode`，连接即进入「已连接」状态**；此后修改 `requestMethod` / 补写 body 会失败或产生未定义行为。旧版 `EvmRpcClient` 的正确顺序是：

1. `requestMethod = "POST"`、`doOutput = true`
2. 写入 JSON body
3. 再读 `responseCode` / `inputStream`

因此 **所有 JSON-RPC POST（`eth_call` 拉 `tokenURI`）在收敛后静默失败**，与 M-8N SSRF、https-only 等后续争论无关——是纯粹的 **POST 生命周期顺序 bug**。

GET 路径（拉 IPFS metadata）不受此 bug 直接影响，但 EVM 凭证若依赖链上 `tokenURI`，仍会因 RPC 失败而无图。

### 9.4 修复（`core/net/HttpFetcher.kt`，2026-09-01）

| 方法 | 修复后行为 |
| --- | --- |
| `postJson` | **独立路径** `postJsonRequest`：`openOnce` → 配置 POST / `doOutput` / `doInput` → `setFixedLengthStreamingMode` → 写 body → **再** `readBody(connection)` |
| `get` / `getBytes` | **独立路径** `getBytesFollowingRedirects`：显式 `requestMethod = "GET"` → `responseCode` → 按 `RedirectPolicy` 跟随重定向 |
| ~~`openWithRedirects`~~ | 从 POST/GET 共用入口移除；避免「先连后配方法」 |

**验证**：`./gradlew :core:testDebugUnitTest :nft:testDebugUnitTest :did:testDebugUnitTest :app-update:testDebugUnitTest`；宿主 App（jdid / CCDAO）在 `jccdex.toolkits.mode=local` 下 **clean rebuild** 后 EVM NFT 图片恢复。

### 9.5 与审查条目 M-8N / M-12N 的取舍说明（nft 模块）

收敛过程中曾尝试对 `resolveRemoteImageUrl` 返回值、`fetchMetadataImage` 强制 `SsrfGuard`、以及 `isLoadableRemoteAssetUrl` 仅允许 `https://`，导致：

- 部分 NFT 的 `http://` tokenURI / metadata 无法拉取；
- 与收敛前 `NftRemoteAssetResolver` / `NftStore` 行为不一致。

**当前 nft 模块策略（与收敛前行为对齐，待 C-17 统一设计后再收紧）**：

- `NftRemoteAssetResolver` / `NftStore` 的 `HttpFetcher`：`ssrfCheck = null`（元数据抓取不在 SDK 内做 SSRF，由宿主图片加载器负责）；
- `isLoadableRemoteAssetUrl` 继续允许 `http://` 与限长 `data:`（对应原 L-6 已知风险，未在本次回归中扩大）；
- `resolveRemoteImageUrl` 对可加载 URL **直返**，不在返回路径二次 SSRF（M-8N 的「解析结果直返」在 nft 场景为**有意保留的旧行为**）。

**教训**：安全加固（M-8N、M-12N）与 C-2 收敛应**分 PR、分调用方**验证；nft 的 metadata 抓取与 app-update 的 APK 下载不能共用同一套默认 `HttpFetcher` 参数。

### 9.6 宿主 App 是否需要改动

| 宿主 | 是否必须改 App 代码 | 说明 |
| --- | --- | --- |
| **jdid-android** | **否**（SDK 修复 + rebuild 即可） | 可选增强：`CredentialImageRequest` 带 chainId/contract/tokenId fallback、`DefaultEthTokenUriResolver` 优先 jccdex RPC——属健壮性，非本次根因 |
| **ccdao-connector-android** | **否** | `ProvidesModule` 已注入 `ethTokenUriResolver`；`buildVcDisplayItem` 使用 `nft?.image`，RPC 恢复后即正常。可选：VC 列表调用 `resolveCredentialImage`、URL 走 `toCoilSafeNftImageUrl()` |

发布远程 SDK 时：须将含 §9.4 修复的 `core` 模块一并 bump `toolkitsVersion`；JitPack 消费者仅升版本即可，无需 App 补丁。

### 9.7 后续门禁建议（防止 C-2 再次回归）

1. **`HttpFetcher` 单测**：`postJson` 对 MockWebServer 发送 POST body 并断言服务端收到；禁止在设置 POST 之前读取 `responseCode` 的代码路径。
2. **nft 集成测**：`resolveCredentialImage(CredentialImageRequest)` + mock `EthTokenUriResolver` + MockWebServer metadata（已有 `NftSdkTest` 用例）；可选 `RUN_LIVE_NFT_TESTS=1` 对 `etha.jccdex.cn` 做 opt-in 冒烟。
3. **收敛检查清单**（每次替换 `HttpURLConnection` 时）：方法（GET/POST）、`httpsOnly`、`redirectPolicy`、`ssrfCheck`、`instanceFollowRedirects` 语义是否与**原调用方**一致——C-2 的注释「zero host-visible change」必须以调用方为单位验收，不能只看 API 签名。

---

## 10. 实施记录与回归复盘（M-D3 `NativeResponseChannel` / DApp 钱包连接）

> **记录日期**：2026-09-01  
> **关联条目**：M-D3、H-D1、C-03、M-D5、M-06  
> **触发场景**：jdid-android 切到 `kotlin-toolkits` `fix` 分支（`jccdex.toolkits.mode=local`）后，探索页 DApp（`https://app.jdid.cn`）**无法连接钱包**；v0.3.2 远程 SDK 正常。

### 10.1 现象

| 阶段 | 用户可见 |
| --- | --- |
| M-D3 严格 origin 上线后 | 点「连接钱包」一直转圈 / 连接失败；Logcat 无 `ChainProvider not set` 时仍无账户 |
| 仅补 `ChainProvider` 后 | 仍失败（非主因） |
| 恢复 `"*"` 握手后 | 弹窗可出，但**首次确认失败、第二次成功** |
| 宿主侧：确认前 `installResponseChannel()` + 恢复 `connectOriginPending` 弹窗 | 首次确认即可成功 |

DApp（`app.jdid.cn`）连接路径：`window.ccdao.request({ method: 'swtc_requestAccounts' })` → Native `SwtcMiddleware.requestAccounts` → `RequestAccountsCallback` 弹窗 → 响应经 **WebMessagePort** 回 JS（C-03，无 `window.ccdao.sendResponse`）。

### 10.2 根因链

1. **M-D3（主因）**：§20 将 `NativeResponseChannel.install()` 的 `postWebMessage` 从 `Uri.parse("*")` 改为 `resolveTargetOrigin()`（页面 origin）。在 jdid/ccdao 的 **Android WebView** 上，严格 origin handshake 常 **静默失败**——Native 侧 `responsePort` 已创建，但 JS `window.addEventListener('message')` 收不到 `__CCDAO_NATIVE_PORT__`，`requestQueue` 永不回调 → 连接挂起。

2. **H-D1（次要）**：`WebAppInterfaceWithWebView.getOrigin()` 实时读 `webView.url` 曾导致 `postMessage` origin 与 `setOrigin(dappUrl)` 不一致；jdid 已在导航时 `setOrigin`，**不宜**再覆盖 `getOrigin()`。

3. **M-D5**：`swtc_requestAccounts` 在非 SWTC 链时需 `ChainProvider`——jdid 初版未接线会抛 `IllegalStateException`；**须宿主补 `setChainProvider`**，但与「完全连不上」可并存排查。

4. **首次确认 race（宿主）**：用户点授权时，若 WebMessagePort 未与 in-flight RPC 对齐，Native 已 `sendSuccessResponse` 但 JS port 未绑定 → 首次失败、第二次成功。修复：授权回调内在 `cont.resume(true)` **之前**调用 `webAppInterface.installResponseChannel()`（**不要**重新注入 `ccdao-eip1193-provider.js`——已注入时会 early return）。

### 10.3 与 v0.3.2 的差异对照

| 项 | v0.3.2（可用） | fix 分支 §20 后（断连） | §10.4 当前 |
| --- | --- | --- | --- |
| Handshake `targetOrigin` | `"*"` | 严格 page origin | **`"*"`（恢复）** |
| `getOrigin()` | 仅 `setOrigin()` 预设 | H-D1 实时 `webView.url` | **仅预设（恢复）** |
| 响应通道 | WebMessagePort（C-03） | 同左 | 同左 |
| jdid `ChainProvider` | 无 | 缺则 M-D5 报错 | **宿主已补 SWTC-only provider** |

### 10.4 SDK 修复（`dapp-connect`，2026-09-01）

| 文件 | 改动 |
| --- | --- |
| `NativeResponseChannel.kt` | `handshakeTargetOrigin()` 固定返回 `"*"`；保留 `resolveStrictTargetOrigin()` 供测试与未来 opt-in |
| `WebAppInterfaceWithWebView.kt` | 移除 H-D1 对 `getOrigin()` 的覆盖（WebView 宿主用 `setOrigin`） |
| `WebAppInterface.kt` | `postMessage` 拒绝时 `rejectPostMessage` 回传错误，避免 JS 无声挂起 |
| `EthMiddleware.kt` | `setChainProvider()` 转发至 middleware（`wallet_switchEthereumChain` 可读 provider） |

**验证**：`./gradlew :dapp-connect:testDebugUnitTest`；jdid `:app:testDebugUnitTest` + 探索页手测 `app.jdid.cn` 首次连接。

### 10.5 宿主适配（jdid-android / ccdao-connector-android）

> **2026-09-01 完成度**：两宿主 **A2/A3/A4 均已实现**；A1 仅 ccdao 需要且已完成。详见 **§11 宿主画像**。

| 项 | ccdao | jdid | 说明 |
| --- | --- | --- | --- |
| `wai.setChainProvider(...)` | **是** ✅ | **是** ✅ | jdid SWTC-only 即可；ccdao 含链切换 Dialog |
| `setOrigin(url)` 于 `onPageStarted` / `onPageFinished` | **是** | **是** | origin 门控与 grant 键（`WebOrigin.normalize`） |
| `providerJs` 回调内 `installResponseChannel()` | **是** | **是** | inject 后 install；**勿**在 `onPageStarted` 去掉 install |
| 授权确认前 `installResponseChannel()` | **是** | **推荐** ✅ | 消除首次 `swtc_requestAccounts` 与 port 绑定的 race |
| `TransactionConfirmCallback` | **是** ✅ | **否** ➖ | **jdid 为非交易 App**——探索页 DApp 仅需连接账户；不注入时 sign/send/batch 由 SDK 拒绝，**符合预期** |
| 重新注入 provider JS on 确认 | **否** | **否** | provider 已存在时 IIFE 直接 return，反而打乱时序 |

**提交参考**：ccdao `cecf940`（A1）、`12973cc`（A4）、`8ac598b`（A2）；jdid `87138bab`（A4）、`c03f2b92`（A2）。

### 10.6 后续门禁建议

1. **真机/WebView 矩阵**：在 bump 严格 M-D3 前，对 API 24–34 实测 `postWebMessage(..., strictOrigin)` 是否投递成功；失败则保持 `"*"` 或做多 target 重试（不能假设 throw）。
2. **集成冒烟**：宿主探索页加载 `https://app.jdid.cn` → 首次 `swtc_requestAccounts` → 授权 → 地址展示（非第二次才成功）。
3. **审查文案**：M-D3「零宿主影响」类结论须 **DApp WebView 手测**，不能仅靠 Robolectric 推导 `resolveTargetOrigin()`。

---

## 11. 宿主画像与适配完成度（2026-09-01）

> 审查报告中的「宿主适配」项须按**产品定位**区分，不能默认两 App 需求相同。

### 11.1 宿主分类

| 宿主 | 定位 | DApp 典型用法 | SDK 钩子要求 |
| --- | --- | --- | --- |
| **ccdao-connector-android** | 交易 / 钱包连接器 | 连接 + 签名 + 转账 + SWTC batch | **全部**：`RequestAccountsCallback`、`TransactionConfirmCallback`、`ChainProvider`、`installResponseChannel()` |
| **jdid-android** | 身份 / 探索（**非交易类**） | 探索页 DApp **连接钱包**（`swtc_requestAccounts`） | **连接类**：`ChainProvider`、`setOrigin`、`installResponseChannel()`、连接授权 Dialog；**不需要** `TransactionConfirmCallback` |

### 11.2 为何 jdid 不需要 TransactionConfirmCallback

1. **产品决策**：jdid 不是交易 App，探索页 DApp 场景为身份展示与账户连接，不面向链上 sign/send/batch。
2. **SDK 行为**：未设置 `TransactionConfirmCallback` 时，敏感 RPC fail-closed（`UserRejectedException`）——对 jdid 是**正确默认**，不是适配遗漏。
3. **若未来 jdid 开放交易能力**：再按 ccdao 模式补确认 UI 并注入回调；当前文档不将其列为 open 项。

### 11.3 适配完成度

| # | 项 | ccdao | jdid |
| --- | --- | --- | --- |
| A1 | TransactionConfirmCallback | ✅ `cecf940` | ➖ N/A |
| A2 | VaultLocked 分支 | ✅ `8ac598b` | ✅ `c03f2b92` |
| A3 | verifyApkFile suspend | ✅ | ✅ |
| A4 | ChainProvider + installResponseChannel | ✅ `12973cc` | ✅ `87138bab` |

**结论**：文档层面的跨仓宿主适配 **已全部关闭**（jdid 不含 A1）。后续 SDK 工作见 [README.md §25](README.md) P2 批次与挂账项。

### 11.4 SDK 侧近期回归修复（fix 分支）

| 提交 | 内容 |
| --- | --- |
| `c37c259` | HttpFetcher POST 生命周期修复（EVM NFT 图片） |
| `7b2606d` | WebMessagePort 握手恢复 `"*"`（DApp 连接） |
| `a59e17f` | batch 确认提前于 account 查询 |

---

*本报告基于源码静态审查，未执行动态/模糊测试。建议在实施上述修复后，用既有 JaCoCo 门禁（`./gradlew jacocoAllModulesReport`）与 `./gradlew ktlintCheckAll` 验证回归。*
