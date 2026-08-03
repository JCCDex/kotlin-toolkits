# kotlin-toolkits 文档

| 文档 | 说明 |
|------|------|
| [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) | 安全审计 + **2026-07-31 修复复审**（闭合矩阵 / R-01 / 剩余路线图） |
| [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) | **复审残留单一方案文档**：Phase A–F 怎么修 + **§13 实施后跨仓残留与优先级** |
| [TEST_AUDIT.md](./TEST_AUDIT.md) | 测试体系分析与审计报告（含实测） |
| [VAULT_SESSION_REDESIGN.md](./VAULT_SESSION_REDESIGN.md) | VaultSession 重构（C-01） |
| [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) | Vault 密钥模型：derivedKey / HMAC / AES / 解锁后内存 |
| [H04_VAULT_INTERNAL_SESSION_FIX.md](./H04_VAULT_INTERNAL_SESSION_FIX.md) | H-04 Internal 读密钥 + 会话解锁 |
| [H_ISSUES_FIX_PLAN.md](./H_ISSUES_FIX_PLAN.md) | H 级问题修复方案汇总 |
| [M_ISSUES_FIX_PLAN.md](./M_ISSUES_FIX_PLAN.md) | M 级问题修复方案汇总 |
| [C02_PASSWORD_PROOF_FIX.md](./C02_PASSWORD_PROOF_FIX.md) | C-02 HMAC proof |
| [C03_REQUEST_NONCE_FIX.md](./C03_REQUEST_NONCE_FIX.md) | C-03 请求 nonce |
| [C04_WEBVIEW_KEY_LEAK_FIX.md](./C04_WEBVIEW_KEY_LEAK_FIX.md) | C-04 WebView 密钥短期缓解 |
| [C05_CLEAR_WITHOUT_PASSWORD_FIX.md](./C05_CLEAR_WITHOUT_PASSWORD_FIX.md) | C-05 擦除（含兼容说明） |
| [TEST_FIX_PLAN.md](./TEST_FIX_PLAN.md) | 测试补强计划 |

## 建议阅读顺序（复审后）

1. [SECURITY_AUDIT.md §1.1](./SECURITY_AUDIT.md) — 闭合状态总表  
2. [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) — **怎么修（A–F）**；落地后看同文档 **§13** 残留与优先级  
3. 已关闭项对照对应 `C0x` / `H_ISSUES` / `M_ISSUES` 方案文档加深理解  

## 工程约定

- **既有单元测试默认锁定**：实现功能时一般不得改已有测试；若必须改，需人工确认。详见 `.cursor/rules/protect-existing-unit-tests.mdc`。
