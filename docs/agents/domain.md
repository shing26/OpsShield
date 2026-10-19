# Domain Docs

Engineering skills 探索 codebase 时，应如何消费这个 repo 的 domain documentation。

## Before exploring, read these

- **`docs/CONTEXT.md`** — 领域词汇表。注意本 repo 的布局与默认不同：词汇表在 `docs/` 下，不在 repo root。
- **`docs/adr/`** — 读取与你即将处理区域相关的 ADRs。
- repo root 的 **`CONTEXT.md`** 与 **`CONTEXT-MAP.md`** 在本 repo **均不存在**（single-context + 词汇表置于 `docs/`）。不要去找它们。

如果上述文件不存在，**静默继续**。不要标记缺失；不要提前建议创建。producer skill（`/grill-with-docs`）会在 terms 或 decisions 实际被解决时懒创建它们。

## File structure

本 repo 是 single-context，词汇表位置非默认：

```
/
├── AGENTS.md
├── docs/
│   ├── CONTEXT.md                     ← 领域词汇表（**不在 root**）
│   ├── adr/
│   │   ├── 0001-....md
│   │   └── ...
│   └── agents/                        ← 本目录：engineering skills 的 repo 配置
└── src/
```

## Use the glossary's vocabulary

当你的输出命名某个 domain concept 时（issue title、refactor proposal、hypothesis、test name），使用 `docs/CONTEXT.md` 中定义的 term。不要漂移到 glossary 明确避免的 synonyms。

如果你需要的概念还不在 glossary 中，这是一个信号：要么你正在发明项目没有使用的语言（重新考虑），要么确实存在缺口（为 `/grill-with-docs` 记录）。

## Flag ADR conflicts

如果你的输出与现有 ADR 矛盾，明确指出，而不是静默覆盖：

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
