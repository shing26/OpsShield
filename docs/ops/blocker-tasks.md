# 剩余卡点任务清单与操作步骤（2026-10-23）

> 目标：把 G1 里仍为黄/未真演的项，按“资源依赖”拆成可执行任务。
> 当前代码侧已无已知死结；剩下的是外部介质、时间窗口和费用授权。
> 相关证据：[g1-live-evidence.md](g1-live-evidence.md)、[production-gate.md](production-gate.md)。

## 0. 当前状态

| 门禁 | 状态 | 还差什么 |
|---|---|---|
| 1 备份调度 | 绿 | — |
| 2 告警到人 | 黄 | 磁盘满真演 |
| 3 部署边界 | 绿 | — |
| 4 失败证据 | 绿 | — |
| 5 发布/回滚 | 黄 | systemd 测试机停服/换 jar/回滚 |
| 6 并发压测 | 黄 | 20–30 条同窗口对照 + 5–30 分钟窗口 |
| 7 SLO | 黄 | 30 天连续流量与月报 |
| 8 SCA | 绿 | 4 条豁免已登记，待 Dependabot PR 记录 |
| 9 WORM | 黄 | 真实 S3 Object Lock/只追加存储 |
| 10 Runbook | 绿 | 生产 systemd 形态复跑 |

## 1. 任务列表

| ID | 任务 | 门禁 | 优先级 | 依赖 | 预计 | 验收 |
|---|---|---|---|---|---|---|
| T1 | 配置 Linux VM + systemd 单元 | 5 | P0 | VM | 30–60 min | systemctl active；readiness UP |
| T2 | systemd 停服/换 jar/回滚演练 | 5 | P0 | T1 | 20 min | 两次切换 readiness UP；/state commit 对齐 manifest |
| T3 | 小容量测试盘 + 磁盘满告警 | 2 | P0 | T1 | 20 min | OpsPilotDiskLow firing；恢复后 resolved |
| T4 | S3 Object Lock/WORM 接入 | 9 | P1 | 对象存储 | 1–2 h | 两次时间戳对象、副本验链、覆盖/删除被拒 |
| T5 | live 长窗口并发 + 同窗口对照 | 6 | P1 | API 预算 | 30–60 min | 20–30 条对照、5–30 min、429/5xx/TTFT 记录 |
| T6 | 30 天 SLO 采集与月报 | 7 | P2 | 持续流量 | 30 天 | 月度 SLI/预算/趋势 |
| T7 | Dependabot PR 合并/关闭记录 | 8 | P3 | CI | 10 min | 至少一条 merged/closed |
| T8 | 语料漂移 reingest/评测 | 债务 | P3 | live 栈 | 1 h | 新 chunks 与报告同代 |

## 2. T1/T2/T3：Linux VM（推荐路径）

> **2026-10-24 进展**：T1/T2/T3 均已完成。T2 注入 OPSPILOT_BUILD_COMMIT 后
> /state.build.commit 与 manifest 对齐（969e137/65cc9b3）；T3 由 Windows Prometheus 抓 VM，
> OpsPilotDiskLow 已 active→resolved，receiver=human-notify。门禁 5 闭环；门禁 2 飞书磁盘告警已由用户确认。
> **2026-10-24 WORM 进展**：VM 上独立 MinIO（9002）已建 Object Lock bucket，两次 audit 副本上传，
> COMPLIANCE 180d、版本删除被拒、副本验链 PASS；还缺异地（MinIO 与网关同机）。

> **如果目标是“做全”，建议直接新建 Ubuntu 24.04 LTS Server VM，不要在 CentOS 7 上勉强做全。**
> CentOS 7 已 EOL、systemd 219、yum 源下线，门禁 5 的证据只能算“旧测试机形态”，不能代表生产基线。
> Ubuntu 24.04 的 systemd 255 能完整支持本仓库 unit 的加固指令，JDK21 也有官方 apt 包。
> 下载入口：https://ubuntu.com/download/server （选 24.04 LTS，Server 版，不要 Desktop）。

### 2.1 VM 规格

- 4 vCPU / 8 GB RAM / 40 GB 磁盘（最低 2C/4G/20G 也能跑）；
- Ubuntu Server 22.04 或 24.04；
- 能 SSH，能访问外网或能拷入 jar。

### 2.2 安装基础依赖

~~~bash
sudo apt update
sudo apt install -y openjdk-21-jdk git curl unzip jq
java -version   # 必须是 21
~~~

不需要在 VM 上重新 Maven 构建；把本机打好的 jar 拷过去：

~~~bash
# 在 Windows 上
scp target/opspilot-gateway-1.0.0.jar opspilot@VM_IP:/tmp/opspilot.jar
~~~

### 2.3 目录与配置

~~~bash
sudo useradd --system --create-home --shell /usr/sbin/nologin opspilot || true
sudo mkdir -p /opt/opspilot/data /opt/opspilot/logs /opt/opspilot/backup /opt/opspilot/scripts /opt/opspilot/release /opt/opspilot/target
sudo chown -R opspilot:opspilot /opt/opspilot
sudo -u opspilot cp /tmp/opspilot.jar /opt/opspilot/app.jar
# 复制仓库 scripts/ 与 ops/ 到 /opt/opspilot
~~~
 
凭据只放 EnvFile，不写 unit：

~~~bash
sudo install -m 600 -o root -g root /dev/null /etc/opspilot/gateway.env
sudo tee /etc/opspilot/gateway.env >/dev/null <<'EOF'
SERVER_ADDRESS=127.0.0.1
SERVER_PORT=8081
OPSPILOT_STRICT=true
OPSPILOT_JAR=/opt/opspilot/app.jar
JWT_SECRET=<32+ 字符>
H2_DB_PASSWORD=<强口令>
DASHSCOPE_API_KEY=<key>
STORAGE_MODE=memory
DASHSCOPE_MODE=mock
EOF
~~~

先 memory+mock 起服务，验证 systemd 本身；live 形态再按需改 storage/mode。

### 2.4 安装 systemd 单元

~~~bash
sudo cp /opt/opspilot/ops/systemd/opspilot-gateway.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now opspilot-gateway.service
systemctl status opspilot-gateway.service --no-pager
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
~~~

unit 已设 Environment=OPSPILOT_JAR=/opt/opspilot/app.jar，与 rollback 的落点一致。

### 2.5 T2：真实停服/换 jar/回滚

~~~bash
cd /opt/opspilot
bash scripts/release.sh v-current
# 把旧版本 jar 也归档为 v-prev
sudo systemctl stop opspilot-gateway
sudo install -m 0644 release/opspilot-v-current.jar /opt/opspilot/app.jar
sudo systemctl start opspilot-gateway
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
curl -fsS http://127.0.0.1:8081/api/v1/admin/state | jq .build
sudo bash scripts/rollback.sh v-prev --apply
sudo systemctl start opspilot-gateway
curl -fsS http://127.0.0.1:8081/actuator/health/readiness
curl -fsS http://127.0.0.1:8081/api/v1/admin/state | jq .build
~~~

验收：两次切换后 readiness UP；/state.build.version/commit 与 manifest 对齐；记录停服到恢复秒数。

### 2.6 T3：小容量测试盘 + 磁盘满

不要填生产根分区；用 loop 盘：

~~~bash
sudo fallocate -l 1G /opt/opspilot-test.img
sudo mkfs.ext4 /opt/opspilot-test.img
sudo mkdir -p /mnt/opspilot-test
sudo mount -o loop /opt/opspilot-test.img /mnt/opspilot-test
sudo mkdir -p /mnt/opspilot-test/data
sudo chown -R opspilot:opspilot /mnt/opspilot-test
sudo systemctl stop opspilot-gateway
sudo mv /opt/opspilot/data /opt/opspilot/data.bak
sudo ln -s /mnt/opspilot-test/data /opt/opspilot/data
sudo systemctl start opspilot-gateway
sudo dd if=/dev/zero of=/mnt/opspilot-test/fill bs=1M status=progress || true
curl -fsS http://127.0.0.1:8081/actuator/prometheus | grep aiops_disk_free_bytes
curl -fsS http://127.0.0.1:9093/api/v2/alerts | grep OpsPilotDiskLow
sudo rm -f /mnt/opspilot-test/fill
~~~

验收：aiops_disk_free_bytes{path="data"} 低于 5GiB；OpsPilotDiskLow firing 且飞书收到；恢复后 resolved。
1G 盘会让规则很快触发；更真实可改用 10G 盘填到 5G 以下。

## 2.7 已有 CentOS 7 VM 的兼容路径

**不需要为了做测试专门下载 Ubuntu 镜像。** CentOS 7 可以完成门禁 5/2 的测试机演练，
但它是 EOL 系统、systemd 219，结论必须标注“CentOS 7 测试机形态”，不能当生产基线。

CentOS 7 的 yum 源已下线，先切 vault：

~~~bash
sudo sed -i 's|^mirrorlist=|#mirrorlist=|g; s|^#baseurl=http://mirror.centos.org|baseurl=http://vault.centos.org|g' /etc/yum.repos.d/CentOS-*.repo
sudo yum clean all
sudo yum makecache
sudo yum install -y git curl unzip iproute gawk
~~~

JDK 21 不在 CentOS 7 基础源里，用 Adoptium tarball：

~~~bash
curl -L -o /tmp/jdk21.tar.gz "https://api.adoptium.net/v3/binary/latest/21/ga/linux/x64/jdk/hotspot/normal/eclipse"
sudo mkdir -p /opt/java/21
sudo tar -xzf /tmp/jdk21.tar.gz -C /opt/java/21 --strip-components=1
/opt/java/21/bin/java -version
~~~

如果 java -version 报 GLIBC 版本不足，说明这台 CentOS 7 的 glibc 太旧，改用 Ubuntu/Rocky 测试机；
不要在生产机上升 glibc。

安装 systemd 单元后，CentOS 7 的 systemd 219 可能不认 RestrictSUIDSGID / LockPersonality。
先检查：

~~~bash
sudo systemd-analyze verify /etc/systemd/system/opspilot-gateway.service
~~~

有 “Unknown lvalue” 时加 drop-in 清空这两项（不会降低其他加固）：

~~~bash
sudo mkdir -p /etc/systemd/system/opspilot-gateway.service.d
sudo tee /etc/systemd/system/opspilot-gateway.service.d/centos7.conf >/dev/null <<'EOF'
[Service]
JAVA_HOME=/opt/java/21
RestrictSUIDSGID=
LockPersonality=
EOF
sudo systemctl daemon-reload
~~~

其余 T1/T2/T3 步骤与 Ubuntu 相同；loop 盘、释放 disk、OpsPilotDiskLow 在 CentOS 7 上同样可用。

**结论**：已有 CentOS 7 就先用它做 T1/T2/T3，不需要下载 Ubuntu；但门禁 5 的证据只能写成
“CentOS 7 测试机通过”，生产仍建议 Ubuntu 22.04/24.04 或 Rocky/Alma 9。

## 3. WSL 方案（可选）

Docker Desktop 自带的 docker-desktop WSL distro 不是通用 Linux 环境，不要在里面找 bash/java。
安装 Ubuntu WSL：

~~~powershell
wsl --install -d Ubuntu-24.04
wsl --list --verbose
~~~

启用 systemd：

~~~bash
sudo tee /etc/wsl.conf >/dev/null <<'EOF'
[boot]
systemd=true
EOF
~~~

然后：

~~~powershell
wsl --shutdown
wsl -d Ubuntu-24.04
~~~

systemctl is-system-running 应返回 running/degraded。之后按 §2.2–§2.6 操作。

注意：
- WSL 的 systemd 是轻量环境，不等同生产机；门禁 5 结论要标注“WSL 本机形态”；
- 磁盘满测试必须用 loop 盘，不要填 WSL 根盘；
- Docker Desktop 的 WSL integration 与这个 Ubuntu 是两件事。

## 4. T4：WORM（S3 Object Lock）

### 4.1 最小方案

1. 建 bucket，创建时开启 Versioning + Object Lock；
2. 创建仅允许 s3:PutObject、不允许删除/覆盖的 IAM 用户；
3. 上传时设置 object-lock-mode=COMPLIANCE，retain-until-date 至少 180 天；
4. 现有 audit_offsite.sh 是目录复制，需要加 S3 上传器（aws cli/rclone/mc）；
5. 不能把普通只读目录当 WORM。

### 4.2 验收

~~~bash
bash scripts/audit_offsite.sh
bash scripts/audit_offsite.sh
# 远端应有两个时间戳前缀对象
python scripts/verify_audit_chain.py <下载副本> --head <副本 head>
# 尝试覆盖/删除一个对象：应被 object lock 拒绝
~~~

没有 S3/MinIO Object Lock 凭据时，只能保持“未真演”。

## 5. T5/T6：live 并发与 SLO

已有样本：[live-concurrency-2026-10-23.json](live-concurrency-2026-10-23.json)、
[slo-sample-2026-10-23.md](slo-sample-2026-10-23.md)。

补长窗口：

~~~bash
OPSPILOT_BASE=http://127.0.0.1:8081 python scripts/live_concurrency.py --seconds 300 --concurrency 20 --out docs/ops/live-concurrency-long.json
~~~

当前脚本对照探针每 2s 一次；长窗口前建议改成 2–3 个并发对照探针，目标 20–30 条成功对照。
验收：对照 20+ 条、5–30 分钟窗口、429/5xx/TTFT P95/P99、L2 未被点亮。

月度 SLO 需要连续 30 天 Prometheus 数据；不能用手工 10s 样本冒充月度。

## 6. T7/T8

- T7：gh pr list --author app/dependabot，选一条通过 CI 的 PR 合并/关闭并记录链接；
- T8：live 栈 --opspilot.ingest=true 重新 reingest，跑评测/报告，确认
  rb-115-h2-corruption-recovery.md 不再写 schema.sql。

## 7. 最小闭环建议

如果只有一台 VM：

1. T1/T2（systemd）→ 门禁 5；
2. 同一 VM 加 loop 盘 T3 → 门禁 2；
3. 有 S3 Object Lock 再做 T4 → 门禁 9；
4. T5 用真实 key 跑长窗口 → 门禁 6；T6 等 30 天 → 门禁 7。

不要把“本机进程级回滚 / 只读目录 / 10s 样本 / 手工填盘”写成门禁通过；它们只能作为部分证据。
