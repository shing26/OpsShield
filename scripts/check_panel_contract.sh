#!/usr/bin/env bash
# Ops Console 契约检查（CI 零服务依赖，ADR-0009 / 外审④修正版）：
# 面板是纯静态 HTML 消费 /admin/state 与 /admin/audit/recent——后端改名会让面板静默失明。
# 本脚本用字面量比对钉死六层契约，改名/漏转义即 CI 红；demo.sh 第 7 检（live 键集合）是第二道闸。
# 用法: bash scripts/check_panel_contract.sh
set -euo pipefail
cd "$(dirname "$0")/.."
HTML=src/main/resources/static/index.html
CTRL=src/main/java/com/opspilot/gateway/AdminController.java
MET=src/main/java/com/opspilot/metrics/OpsMetrics.java
fail=0

# 层 1：HTML fetch 的 admin 路径必须存在于 Controller 映射（前缀 /api/v1/admin + @*Mapping 字面量）
mappings=$(grep -oE '@(Get|Post)Mapping\("[^"]+"\)' "$CTRL" | sed -E 's/.*\("([^"]+)"\)/\/api\/v1\/admin\1/' | sort -u)
html_paths=$(grep -oE "/api/v1/admin/[a-z/]+" "$HTML" | sort -u)
for p in $html_paths; do
  echo "$mappings" | grep -qx "$p" || { echo "FAIL: 面板 fetch 的路径无后端映射: $p"; fail=1; }
done

# 层 2：OpsMetrics 的累计计数器键必须全部被面板渲染（tiles 声明含键名字符串）
metric_keys=$(grep -oE 'put\("[a-z_]+"' "$MET" | sed -E 's/put\("([a-z_]+)"/\1/' | sort -u)
for k in $metric_keys; do
  grep -qF "'$k'" "$HTML" || { echo "FAIL: 指标键未进面板 tiles: $k"; fail=1; }
done

# 层 3：面板 tiles 引用的键必须真实存在（OpsMetrics ∪ metricsView 追加键）——
# Java 侧改名（如 total_requests）而 HTML 未同步时，本层点名致红（W11 演练即打这）
tile_keys=$(sed -n '/const keys = \[/,/];/p' "$HTML" | grep -oE "'[a-z_]+'" | tr -d "'" | grep -vE '^(请求|LLM)$' | sort -u)
valid=$( { echo "$metric_keys"; grep -oE 'm\.put\("[a-z_]+"' "$CTRL" | sed -E 's/m\.put\("([a-z_]+)"/\1/'; } | sort -u)
for k in $tile_keys; do
  echo "$valid" | grep -qx "$k" || { echo "FAIL: 面板引用了后端不存在的键: $k（改名漂移？）"; fail=1; }
done

# 层 4：/state 与 recent 的顶层契约键（面板 renderState 消费面 ⊆ Java 装配面）
for k in ts build metrics health runtime; do
  grep -qE "out\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state 顶层装配缺失: $k"; fail=1; }
done
for k in version time jvm uptime_s pid; do
  grep -qE "b\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state build 指纹键缺失: $k"; fail=1; }
done
for k in inflight sf_groups sf_keys_top degradation quota reingest; do
  grep -qE "runtime\.put\(\"$k\"" "$CTRL" || { echo "FAIL: /state runtime 键缺失: $k"; fail=1; }
done

# 面板侧消费声明（与 Java 对照的反向字面量存在性）
# 两个阈值键名各自单列：删掉任一个面板消费点即红——它们是"离 L1/L2 还有多远"的唯一可见面。
for k in sf_groups sf_keys_top cooldown_s manual failures llm_failure_threshold inflight_threshold used limit busy last; do
  grep -qF "$k" "$HTML" || { echo "FAIL: 面板缺少对运行态键的消费: $k"; fail=1; }
done

# 层 5：actuator 暴露面不得收敛掉 health（2026-10-10 实测回归后补）。
# `management.endpoints.web.exposure.include` 是**覆盖**不是追加：加 prometheus 时漏写 health，
# /actuator/health 当场 404，而它是 demo.sh 预检 / backup.sh / quickstart.sh / liveness.py 的
# 存活判据——静态 grep 在这里能挡住整类"顺手改配置把探测面改没"。
YML=src/main/resources/application.yml
EXPOSURE=$(grep -A1 "exposure:" "$YML" | grep "include:" | head -1)
echo "$EXPOSURE" | grep -q "health" || {
  echo "FAIL: actuator 暴露面缺 health（会被 4 个脚本当作存活判据）: $EXPOSURE"; fail=1; }
echo "$EXPOSURE" | grep -q "prometheus" || {
  echo "FAIL: actuator 暴露面缺 prometheus（ADRs 0016 的抓取端）: $EXPOSURE"; fail=1; }

# 层 6：事件流的 ev.sub 必须走 esc()（G0-W0.1，2026-10-22 审查）。
# 根因：renderEvents 用 insertAdjacentHTML 拼字符串，同函数其余字段都转义了，只有 who 里的
# ev.sub（token 主体/账号名）直接进 HTML——这是面板唯一的注入点。后端即使把 sub 约束住，
# 前端也不能靠上游保证：转义是输出编码的职责，不是输入校验的替代品。
grep -qF "esc(ev.sub)" "$HTML" || {
  echo "FAIL: 面板事件流的 ev.sub 未经 esc() 转义（innerHTML 拼接 → 注入面）"; fail=1; }


# 层 7：copilot / auth 路径字面量必须存在于对应 Controller 的映射上。
# 层 1 只查了 admin：/chat/stream 或 /coverage 改名时面板不会报错、只会永远 404——
# 这是「面板静默失明」的另一半（2026-10-26 审查记为门闩盲区，#33）。
# 每个 Controller 各自读类级前缀（/api/v1/copilot、/api/v1/copilot/actions、/api/v1/auth…），
# 再拼方法级映射；类根上有裸 @GetMapping/@PostMapping 的（无参映射）把前缀本身也计入。
api_paths_for() {
  local f=$1 prefix subs s
  prefix=$(grep -oE '@RequestMapping\("[^"]+"\)' "$f" | head -1 | sed -E 's/.*"([^"]+)"/\1/' || true)
  [ -z "$prefix" ] && return 0
  subs=$(grep -oE '@(Get|Post)Mapping\((value = )?"[^"]+"' "$f" | sed -E 's/.*"([^"]+)"/\1/' || true)
  for s in $subs; do echo "${prefix}${s}"; done
  if grep -qE '^[[:space:]]*@(Get|Post)Mapping$' "$f"; then echo "$prefix"; fi   # 类根上的无参映射
}
copilot_map=$( { api_paths_for src/main/java/com/opspilot/gateway/CopilotController.java
  api_paths_for src/main/java/com/opspilot/gateway/CoverageController.java
  api_paths_for src/main/java/com/opspilot/gateway/FeedbackController.java
  api_paths_for src/main/java/com/opspilot/gateway/SearchController.java
  api_paths_for src/main/java/com/opspilot/action/ActionOpsController.java; } | sort -u || true)
echo "DEBUG copilot_map=[$copilot_map]" >&2   # TEMP
copilot_paths=$(grep -oE "/api/v1/copilot/[a-z/-]+" "$HTML" | sort -u || true)
for p in $copilot_paths; do
  echo "$copilot_map" | grep -qx "$p" || { echo "FAIL: 面板 fetch 的 copilot 路径无后端映射: $p"; fail=1; }
done
auth_map=$(api_paths_for src/main/java/com/opspilot/gateway/AuthController.java | sort -u || true)
auth_paths=$(grep -oE "/api/v1/auth/[a-z/-]+" "$HTML" | sort -u || true)
for p in $auth_paths; do
  echo "$auth_map" | grep -qx "$p" || { echo "FAIL: 面板 fetch 的 auth 路径无后端映射: $p"; fail=1; }
done

# 层 8：SSE 帧名与 done 帧键必须两端一致（SseEvents 发 / 面板读）。
# 帧键漂移不会 404，也不会抛错——面板只是永远拿不到那一段（例如 near_miss 改名后拒答导航消失）。
SSE=src/main/java/com/opspilot/gateway/SseEvents.java
sse_events=$(grep -oE 'trySend\(emitter, "[a-z_]+"' "$SSE" | sed -E 's/.*"([a-z_]+)"/\1/' | sort -u || true)
for e in $sse_events; do
  grep -qF "ev === '$e'" "$HTML" || { echo "FAIL: 面板未处理 SSE 事件: $e"; fail=1; }
done
done_keys=$(grep -oE 'done\.put\("[a-z_]+"' "$SSE" | sed -E 's/.*"([a-z_]+)"/\1/' | sort -u || true)
for k in $done_keys; do
  grep -qF "d.$k" "$HTML" || { echo "FAIL: done 帧键未被面板消费: $k"; fail=1; }
done

# 层 9：面板发出的 /chat/stream 请求体字段 ⊆ ChatRequest 字段。
# 发一个后端已删的键不会 400（Jackson 默认忽略未知字段），只会静默丢掉那个语义（如 source）。
DTO=src/main/java/com/opspilot/gateway/dto/ChatRequest.java
dto_fields=$(sed -n '/public record ChatRequest(/,/^) *{/p' "$DTO" | grep -oE 'String [a-zA-Z0-9]+' | sed 's/String //' | sort -u || true)
body_json=$(grep -oE 'JSON.stringify\(\{[^}]*\}\)' "$HTML" | grep -F 'query' | head -1 || true)
body_keys=$(echo "$body_json" | grep -oE '[a-zA-Z_]+:' | tr -d ':' | sort -u || true)
for k in $body_keys; do
  echo "$dto_fields" | grep -qx "$k" || { echo "FAIL: 面板请求体字段不在 ChatRequest 里: $k"; fail=1; }
done

if [ $fail -eq 0 ]; then echo "OK：面板契约九层一致（路径 admin+copilot+auth / 指标双向 / state 键集 / actuator 暴露面 / ev.sub 转义 / SSE 帧 / 请求体字段）"; fi
exit $fail
