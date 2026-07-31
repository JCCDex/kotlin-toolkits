# kotlin-toolkits 文档

| 文档 | 说明 |
|------|------|
| [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) | 全库静态安全审计报告（2026-07-22） |
| [TEST_AUDIT.md](./TEST_AUDIT.md) | 测试体系分析与审计报告（含实测） |
| [VAULT_SESSION_REDESIGN.md](./VAULT_SESSION_REDESIGN.md) | VaultSession 重构（C-01） |
| [H04_VAULT_INTERNAL_SESSION_FIX.md](./H04_VAULT_INTERNAL_SESSION_FIX.md) | H-04 Internal 读密钥 + 会话解锁 / App 解锁页接入 |
| [H_ISSUES_FIX_PLAN.md](./H_ISSUES_FIX_PLAN.md) | H 级问题修复方案汇总 |

## 工程约定

- **既有单元测试默认锁定**：实现功能时一般不得改已有测试；若必须改，需人工确认。详见 `.cursor/rules/protect-existing-unit-tests.mdc`。
