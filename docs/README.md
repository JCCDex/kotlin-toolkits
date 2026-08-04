# kotlin-toolkits 文档

只保留仍需要查阅的文档。已落地的单点修复方案（C0x / H* / M* 实施说明等）已删除，状态以审计矩阵为准。

## 该看什么

| 场景 | 文档 |
|------|------|
| **安全现状 / 问题是否关闭** | [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) §1.1 |
| **还剩什么要做、怎么排期** | [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) §8 / §13 |
| **为何 C-04 / M-09 / M-16 暂缓** | [SECURITY_REAUDIT_FIX_PLAN.md §8.1](./SECURITY_REAUDIT_FIX_PLAN.md) |
| **Vault 密钥怎么工作（现状）** | [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) |
| **C-01 删 proto field 4（已落地）** | [C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md](./C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md) |
| **测试体系与覆盖边界** | [TEST_AUDIT.md](./TEST_AUDIT.md) |

## 文档清单

| 文档 | 说明 |
|------|------|
| [SECURITY_AUDIT.md](./SECURITY_AUDIT.md) | 安全审计报告 + 闭合矩阵（权威状态表） |
| [SECURITY_REAUDIT_FIX_PLAN.md](./SECURITY_REAUDIT_FIX_PLAN.md) | 复审残留实施方案；**§13 为实施后唯一维护入口** |
| [C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md](./C01_REMOVE_DERIVED_KEY_FIELD_PLAN.md) | ✅ 已落地：删除 proto `derivedKey` field 4（`reserved`） |
| [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md) | Vault：session key / HMAC proof / AES / 解锁后内存 |
| [TEST_AUDIT.md](./TEST_AUDIT.md) | 测试体系审计（含实测基线） |

## 建议阅读顺序

1. [SECURITY_AUDIT.md §1.1](./SECURITY_AUDIT.md) — 各 ID 当前状态  
2. [SECURITY_REAUDIT_FIX_PLAN.md §13](./SECURITY_REAUDIT_FIX_PLAN.md) — 仍打开项与优先级  
3. 涉及密钥时再看 [VAULT_KEY_MODEL.md](./VAULT_KEY_MODEL.md)

## 工程约定

- **既有单元测试默认锁定**：实现功能时一般不得改已有测试；若必须改，需人工确认。详见 `.cursor/rules/protect-existing-unit-tests.mdc`。
