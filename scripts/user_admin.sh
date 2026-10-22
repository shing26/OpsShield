#!/usr/bin/env bash
# 用户管理入口（P2）：包装 UserAdminCli，经 PropertiesLauncher 复用 fat jar 类路径（H2/bcrypt 内置）。
# 口令只从 --password-env 指定的环境变量或交互 stdin 传入，绝不进 argv（避免 ps 泄露）。
# 用法示例:
#   DEMO_PASSWORD=... bash scripts/user_admin.sh add --user alice --tenant tenant-internal --level 1 --role sre --password-env DEMO_PASSWORD
#   bash scripts/user_admin.sh disable --user alice | rotate --user alice | list
#   FORCE=1 bash scripts/user_admin.sh list --to-db restore-drill   # 演练库：不触主库，可越过网关守卫
set -euo pipefail
cd "$(dirname "$0")/.."
# .env 自动装载（H2_DB_PASSWORD 等），与 run.sh 同构；显式传入的环境变量优先
if [ -f .env ]; then set -a; . ./.env; set +a; fi
JAR=target/opspilot-gateway-1.0.0.jar
[ -f "$JAR" ] || { echo "缺 $JAR（先 mvn package -DskipTests）"; exit 1; }

# 容器形态守卫（事故 51103 的复现路径）：容器网关经 ./data 挂载持有同一个 H2 账号库，
# 而 H2 的锁文件跨 bind mount 不可靠——两个进程同写会损坏库且恢复工具救不回，
# 账号库又是全系统唯一不可再生的数据。OPS §1 已定"容器形态必须串行"，此处把它变成机制。
if command -v docker >/dev/null 2>&1 \
   && [ "$(docker inspect -f '{{.State.Running}}' opspilot-gateway 2>/dev/null || echo false)" = "true" ]; then
  cat >&2 <<'MSG'
拒绝执行：容器 opspilot-gateway 正在运行，它正持有同一个 H2 账号库（./data 挂载）。
容器 bind mount 上的 H2 锁不可靠，两进程同写会损坏账号库（事故 51103：MVStore chunk
损坏，官方恢复工具也救不回）。请选其一：
  1) 串行执行：      docker compose stop gateway  然后重跑本命令（容器形态下这是常态做法）
  2) 走 HTTP 管理面：curl -X POST http://localhost:8081/api/v1/admin/backup -H "Authorization: Bearer <platform JWT>"
  3) 容器内 CLI：   见 scripts/quickstart.sh 的 seed 形态（docker compose exec + PropertiesLauncher）
MSG
  exit 2
fi

# 网关守卫（G0，2026-10-22）：**裸进程形态**下网关与 CLI 争的是同一个 H2 文件锁。
# 旧行为靠 AUTO_SERVER 让两者"并存"，但那条路在 Windows 防火墙下常被拦、在 bind mount 上
# 锁不可靠；账号库损坏的代价不可逆，所以默认拒绝，覆盖必须显式写 FORCE=1。
# 判据用"端口监听 或 health 200"两条：8081 上可能是别的项目（它的 health 也回 UP，
# OPS §7 纪律 #1 的实测教训）——不确定时拒绝是正确方向。
gateway_running() {
  local port="${SERVER_PORT:-8081}"
  if command -v curl >/dev/null 2>&1; then
    local code
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 \
      "http://127.0.0.1:${port}/actuator/health" 2>/dev/null || echo 000)
    [ "$code" = "200" ] && return 0
  fi
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${port}\$" && return 0
  elif command -v netstat >/dev/null 2>&1; then
    netstat -an 2>/dev/null | grep -E "[:.]${port}[[:space:]]" | grep -qi 'LISTEN' && return 0
  elif (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
    return 0
  fi
  return 1
}

if gateway_running && [ "${FORCE:-0}" != "1" ]; then
  PORT="${SERVER_PORT:-8081}"
  cat >&2 <<MSG
拒绝执行：网关疑似在运行（${PORT} 端口在监听，或 /actuator/health 回 200）。
CLI 直连的是网关正持有的同一个 H2 账号库文件；同库双写会损坏账号库，而它是全系统唯一
不可再生的数据（事故 51103 的同类路径）。请选其一：
  1) 停网关后重跑：   停掉 java 进程 / docker compose stop gateway，再执行本命令
  2) 走 HTTP 管理面：  curl -X POST http://localhost:${PORT}/api/v1/admin/backup
                       -H "Authorization: Bearer <platform JWT>" -H "Content-Type: application/json"
                       -d '{"to":"backup/users-$(date +%F).zip"}'
  3) 确认 ${PORT} 上不是本项目网关，或目标是演练库（--to-db）时显式覆盖：
                       FORCE=1 bash scripts/user_admin.sh <cmd> ...
默认拒绝；覆盖必须写出来——这样"我知道自己在做什么"会留在命令历史里。
MSG
  exit 2
fi
if [ "${FORCE:-0}" = "1" ]; then
  echo "warn: FORCE=1 覆盖网关守卫（请确认 ${SERVER_PORT:-8081} 不是本项目网关，或目标库是演练库）" >&2
fi

# G0：默认 URL 去掉 AUTO_SERVER（CLI 嵌入式直连）。需要 AUTO_SERVER 的部署在 H2_DB_URL 显式声明。
export H2_DB_URL="${H2_DB_URL:-jdbc:h2:file:./data/users}"
export H2_DB_USER="${H2_DB_USER:-opspilot}"
JAVA_BIN="${JAVA_HOME:-/e/java/jdk21}/bin/java"
[ -x "$JAVA_BIN" ] || JAVA_BIN=java
exec "$JAVA_BIN" -cp "$JAR" \
  -Dloader.main=com.opspilot.auth.UserAdminCli \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
