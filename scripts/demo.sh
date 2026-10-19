#!/usr/bin/env bash
# 演示预检：只读检查，零 LLM/embedding 成本。全 PASS 后按 docs/DEMO.md 开演。
set -u
cd "$(dirname "$0")/.."
fails=0
ok()  { echo "PASS  $1"; }
bad() { echo "FAIL  $1"; fails=$((fails+1)); }

# 1) 中间件容器
for c in opspilot-redis opspilot-qdrant opspilot-es; do
  st=$(docker ps --format '{{.Names}} {{.Status}}' | grep "^$c " || true)
  [ -n "$st" ] && ok "$c 运行中" || bad "$c 未运行（docker compose up -d）"
done

# 2) 服务健康
h=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/actuator/health || echo 000)
[ "$h" = "200" ] && ok "网关 UP (8081)" || bad "网关未响应（见 docs/DEMO.md §0 启动）"

# 3) 凭据就绪：口令在环境（P2 login 体系）+ 红队畸形样本文件可再生
[ -f .env ] && { set -a; . ./.env; set +a; }
PY=$(bash scripts/py.sh 2>/dev/null || true)   # 跨平台 Python 探测单点（py.sh）
[ -n "${DEMO_PASSWORD:-}" ] && ok "DEMO_PASSWORD 已设置" || bad "DEMO_PASSWORD 未设置（.env，seed/login 依赖）"
t=scripts/redteam_tokens.txt
if [ -f "$t" ]; then
  have=0; for k in sre_l0 sre_neg tenant_none tenant_blank tenant_long; do grep -q "^$k=" "$t" && have=$((have+1)); done
  [ "$have" = "5" ] && ok "红队畸形样本 5/5" || bad "红队样本不全（python scripts/gen_tokens.py > $t）"
else
  bad "缺 scripts/redteam_tokens.txt（gen_tokens.py 生成，不入库）"
fi
# 告警主体：DEMO 幕⑧ 与 alert_producer.py 默认用 sre-watcher——它不在预检里的话，
# 会出现"预检全绿但幕⑧在第一次登录就挂"（容器冷启动曾漏 seed 该账号）
if [ -n "${DEMO_PASSWORD:-}" ] && [ -n "$PY" ]; then
  "$PY" -c "import sys;sys.path.insert(0,'offline');import localapi;localapi.login('sre-watcher')" >/dev/null 2>&1 \
    && ok "告警主体 sre-watcher 可登录（幕⑧依赖）" \
    || bad "sre-watcher 无法登录：幕⑧跑不了（bash scripts/seed_demo_users.sh；容器形态见 quickstart.sh）"
else
  echo "SKIP  告警主体登录（口令或 Python 缺失）"
fi

# 4) 鉴权面：无 token 的 admin 必须 401（裸 URL 不泄漏指标）
a=$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8081/api/v1/admin/metrics || echo 000)
[ "$a" = "401" ] && ok "admin 匿名 401" || bad "admin 匿名返回 $a（期望 401）"

# 5) 依赖健康（探针聚合：redis/qdrant/es 全 UP 且别名可解析）
if [ -n "${DEMO_PASSWORD:-}" ] && [ -n "$PY" ]; then
  H=$("$PY" -c "
import sys, json, urllib.request
sys.path.insert(0, 'offline')
import localapi
t = localapi.login('sre-full')
req = urllib.request.Request('http://localhost:8081/api/v1/admin/health',
    headers={'Authorization': 'Bearer ' + t})
d = json.load(urllib.request.urlopen(req, timeout=15))
print(d['status'])" 2>/dev/null)
  [ "$H" = "UP" ] && ok "依赖健康 UP (redis/qdrant/es)" || bad "依赖健康状态: ${H:-获取失败}"
else
  echo "SKIP  依赖健康（DEMO_PASSWORD 或 Python 缺失，py.sh 三档探测）"
fi

# 6) 构建一致性（近似判据；git checkout 会批量刷新 mtime 可能误报——以验收实跑为准）
#    宿主模式：源码任一比 jar 新即提醒重建；容器模式：镜像构建于 build 上下文（源码即真相），
#    宿主无 target jar 属预期，SKIP 改由 quickstart/CI 的 --build 保证（S3 Linux 客串实测暴露此分支缺失）
if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx opspilot-gateway; then
  echo "SKIP  jar 一致性（容器模式：改源码后 docker compose --profile full up -d --build gateway 重建）"
elif [ -f target/opspilot-gateway-1.0.0.jar ]; then
  src=$(find src/main pom.xml -type f \( -name '*.java' -o -name '*.yml' -o -name 'pom.xml' \) \
        -newer target/opspilot-gateway-1.0.0.jar | head -1)
  [ -z "$src" ] && ok "jar 不早于源码/配置" || bad "jar 可能落后（$src 更新过）：mvn package -DskipTests 后重启"
else
  bad "缺 target jar（mvn package -DskipTests）"
fi

# 7) Ops Console：HTML 壳就位 + 契约脚本 + live /state 顶层键可达
#    键集的**权威**是 scripts/check_panel_contract.sh（CI 同一份，静态四层）；
#    这里只做两件它做不到的事：①在真栈上跑一遍它 ②确认 live 服务确实返回面板要读的顶层键。
[ -f src/main/resources/static/index.html ] && ok "面板 HTML 就位（localhost:8081/）" \
  || bad "缺 src/main/resources/static/index.html"
bash scripts/check_panel_contract.sh >/dev/null 2>&1 && ok "面板契约脚本四层一致（与 CI 同源）" \
  || bad "面板契约脚本报红：跑 bash scripts/check_panel_contract.sh 看详情"
if [ -n "${DEMO_PASSWORD:-}" ] && [ -n "$PY" ]; then
  S=$("$PY" -c "
import sys, json, urllib.request
sys.path.insert(0, 'offline')
import localapi
t = localapi.login('sre-full')
d = localapi.get_json('/api/v1/admin/state', t)
# 只断言顶层四块存在（精确键集归契约脚本管，别在这里抄第二份）
missing = [k for k in ('build','metrics','health','runtime') if k not in d]
print('missing:' + ','.join(missing) if missing else 'UP')" 2>/dev/null)
  [ "$S" = "UP" ] && ok "/state 顶层键齐全（面板可接入）" || bad "/state 键缺失: ${S:-获取失败}"
else
  echo "SKIP  /state 键契约（口令或 Python 缺失）"
fi

echo "---"
[ "$fails" = "0" ] && echo "READY：按 docs/DEMO.md 开演（底幕=统一工作台 http://localhost:8081/，默认视图=排障台）" \
  || echo "预检 $fails 项失败，先修复"
exit "$fails"
