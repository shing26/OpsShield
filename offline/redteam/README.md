# OpsShield 红队对抗套件（内部）

对外叙事见 [docs/redteam.md](../../docs/redteam.md)。本目录是执行面：

- `cases/<类别>/<id>.json`：用例（attack = 攻击载荷，expect = 确定性判据类型）；
- `runner.py`：独立 runner（不依赖 pytest）——manifest 校验 → 逐用例执行 → 报告 + 退出码；
- `manifest.json`：每用例 sha256。**删/改用例必须 `--update-manifest` 显式重建**，
  CI 里 manifest 与 cases 的 diff 人审（防悄悄删难过的用例、也防塞只会过的用例刷分）；
- `report/`：runner 产物（json + md），数字进 doc_numbers 门禁（泄漏硬 0）。

判据纪律：fail-closed 布尔（泄漏=FAIL，0 容忍）；判定确定性（无 LLM-as-judge）；
MCP 用例端到端（真 stdio 进程真 JSON-RPC）。顺序裁决：**L1/L2 受控行动契约开闸前，
本套件必须先扩写操作用例**。

运行：`bash scripts/redteam.sh`（起活体栈 + 播种 + 全量）；
单重建清单：`python offline/redteam/runner.py --update-manifest`。