# -*- coding: utf-8 -*-
"""外部存活探测——补 OPS §5.1 登记的已知盲区「网关整体不可用时生产者无法自证」。

**为什么要有这个（触发线已到）**：面板、告警生产者、审计流全都长在网关**进程内**，
所以"网关整个挂了"这件事没有任何组件能通知人——唯一信号是"面板打不开"，
而那需要人正好去看。本脚本把这件事移出进程：轮询 `/actuator/health`，
连续 N 次不健康才判死（单次网络抖动不误报），并留下可复核的 JSONL。

**为什么必须先验明正身再判死活**（本机实测踩到）：今晚 8091 被另一个项目的
`biz-mock` 进程占着，而**任何 Spring Boot 应用的 `/actuator/health` 都返回
`{"status":"UP"}`**——只看 HTTP 码或只看这个 JSON，等于在给别人的进程做体检：
"UP 了所以我活着"是最危险的假绿（反过来它回 503 时又会被误读成"我半死"）。
因此本脚本用两个信号：
  1. **身份**：GET `/` 的 HTML 里有 `OpsShield`（面板壳是匿名可开的静态页，零凭据；
     OpsShield 是对外项目名，`opspilot` 是内部工程代号——页面品牌随对外名走）；
  2. **存活**：`/actuator/health` 的 `status == "UP"`。
两者都满足才算健康。身份不符 → 报 `WRONG_SERVICE`，与" DOWN"区分开，
因为这两个的处置动作完全不同（前者去查端口被谁占了，后者去查中间件）。

**它做什么 / 不做什么**：只探测 + 记录 + 以退出码表态，**不重启、不告警投递**——
重启网关属于人工决策（OPS §7 的三条纪律都指向"别在诊断前先动手"）；
投递渠道要选，仓内目前没有。退出码给 cron / Windows 任务计划程序用。

用法：
    python scripts/liveness.py --once                 # 探一次，退出码表态（给计划任务）
    python scripts/liveness.py --interval 30          # 常驻轮询
    python scripts/liveness.py --once --base http://localhost:8081
"""
from __future__ import annotations

import argparse
import json
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

DEFAULT_BASE = "http://localhost:8081"
DEFAULT_INTERVAL = 30
DEFAULT_THRESHOLD = 2
# 身份锚点：面板静态页的标题。它是**匿名可开**的（ADR-0009 的"零信息壳"设计），
# 所以拿来做身份校验不需要任何凭据，也不会因为拿不到 token 而失去探测能力。
IDENTITY_MARK = "OpsShield"

# 状态取值：
HEALTHY = "healthy"          # 身份对 + UP
DOWN = "down"                # 身份对 + 非 UP（查中间件/降级）
WRONG = "wrong_service"      # 身份不符（端口被别的项目占了——去查端口，不是查本项目）
UNREACHABLE = "unreachable"  # 连不上
ERRORS = (DOWN, WRONG, UNREACHABLE)


def _http_get(url: str, timeout: float, opener=urllib.request.urlopen):
    """返回 (status_code, body_text)；HTTP 4xx/5xx 也返回码而不抛，便于归因。"""
    try:
        with opener(url, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        # 5xx 仍是"服务在应答"——要读 body 才能区分 DOWN 与别的东西
        try:
            body = e.read().decode("utf-8", "replace")
        except Exception:  # noqa: BLE001 - body 读不出来不该盖掉 HTTP 码这个信号
            body = ""
        return e.code, body


def probe(base: str = DEFAULT_BASE, timeout: float = 5.0, opener=urllib.request.urlopen) -> tuple[str, str]:
    """探一次 → (state, detail)。detail 必须能指到下一步动作，不能只说"挂了"。"""
    base = base.rstrip("/")
    # ① 身份
    try:
        code, body = _http_get(base + "/", timeout, opener)
    except Exception as e:  # noqa: BLE001 - 连不上根路径就是不可达，按钮怎么点都一样
        return UNREACHABLE, f"{type(e).__name__}: {e}"
    if code != 200 or IDENTITY_MARK not in body:
        return WRONG, f"{base}/ 返回 {code} 且无 {IDENTITY_MARK!r} 锚点——不是 OpsShield，端口多半被别的项目占了"
    # ② 存活
    try:
        code, body = _http_get(base + "/actuator/health", timeout, opener)
    except Exception as e:  # noqa: BLE001
        return UNREACHABLE, f"{type(e).__name__}: {e}"
    try:
        status = json.loads(body).get("status")
    except Exception:  # noqa: BLE001 - 解析不了就按 DOWN 记，别当健康
        return DOWN, f"health 返回 {code} 但 body 不是 JSON"
    if code == 200 and status == "UP":
        return HEALTHY, "UP"
    return DOWN, f"health status={status} http={code}"


class Watch:
    """连续失败计数。

    两条语义是有意选成这样的，别当成可有可无的实现细节：
      - **阈值而非单次**：网络抖一下就报警的探针，人会很快学会忽略它；
      - **恢复即清零**：不清零的话一次抖动会累积成永久告警，同样会被忽略。
    """

    def __init__(self, threshold: int = DEFAULT_THRESHOLD) -> None:
        self.threshold = threshold
        self.consecutive = 0

    def record(self, state: str) -> bool:
        """喂一次探测结果 → 是否处于「判死」状态。"""
        if state == HEALTHY:
            self.consecutive = 0
            return False
        self.consecutive += 1
        return self.consecutive >= self.threshold

    @property
    def down(self) -> bool:
        return self.consecutive >= self.threshold


def append_log(path: Path, state: str, detail: str, base: str) -> None:
    """追加一条事件。JSONL 与审计流同构——可 grep、可 diff，不引新格式。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    rec = {"ts": int(time.time() * 1000), "base": base, "state": state, "detail": detail}
    with path.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps(rec, ensure_ascii=False) + "\n")


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="OpsShield 外部存活探测（只探不修）")
    ap.add_argument("--base", default=DEFAULT_BASE, help="网关根 URL（默认 %(default)s）")
    ap.add_argument("--interval", type=float, default=DEFAULT_INTERVAL, help="轮询间隔秒（默认 %(default)s）")
    ap.add_argument("--threshold", type=int, default=DEFAULT_THRESHOLD,
                    help="连续几次不健康才判死（默认 %(default)s；设 1 即禁用去抖）")
    ap.add_argument("--timeout", type=float, default=5.0, help="单次请求超时秒（默认 %(default)s）")
    ap.add_argument("--once", action="store_true", help="探一次就退出（给计划任务用）")
    ap.add_argument("--log", default=str(Path(__file__).resolve().parent.parent / "logs" / "liveness.jsonl"),
                    help="事件落盘位置（默认 logs/liveness.jsonl）")
    args = ap.parse_args(argv)

    # 环回白名单：与 localapi.assert_local 同一口径。本脚本只该探本机服务——
    # 若有人把 --base 指向外网，它就该拒绝而不是去探（SSRF 面）。
    u = urlparse(args.base)
    if u.scheme != "http" or u.hostname not in ("localhost", "127.0.0.1") or u.username or u.password:
        print(f"FAIL: --base 只允许环回明文 http（当前 {args.base}）", flush=True)
        return 2

    log_path = Path(args.log)
    w = Watch(args.threshold)

    if args.once:
        state, detail = probe(args.base, args.timeout)
        append_log(log_path, state, detail, args.base)
        print(f"{state}: {detail}")
        # --once 且 threshold>1 时单次探测不可能判死（未累积），故按本次结果表态
        return 1 if state in ERRORS else 0

    print(f"探测 {args.base} 每 {args.interval}s，连续 {args.threshold} 次不健康判死；Ctrl-C 停")
    while True:
        state, detail = probe(args.base, args.timeout)
        dead = w.record(state)
        append_log(log_path, state, detail, args.base)
        if dead:
            print(f"[{time.strftime('%H:%M:%S')}] 判死（连续 {w.consecutive} 次）：{state} — {detail}", flush=True)
        elif state in ERRORS:
            print(f"[{time.strftime('%H:%M:%S')}] 异常但未达阈值（{w.consecutive}/{args.threshold}）：{state} — {detail}",
                  flush=True)
        else:
            print(f"[{time.strftime('%H:%M:%S')}] 健康", flush=True)
        time.sleep(args.interval)


if __name__ == "__main__":
    raise SystemExit(main())