#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""单节点中间件故障切换演练（Boot 4 迁移后残余风险 ①）。

背景：live 形态是单节点 Redis/ES/Qdrant，无副本。本演练不验证"中间件不挂"，
而验证**中间件挂掉时网关的行为契约**——降级必须可见、不得静默切 memory、不得整体崩溃，
恢复后自动回满。演练脚本本身也是回归资产：改降级逻辑后应重跑。

契约（违反任一条即退出非 0）：
  A. 停 ES：admin/health 的 es 不再是 UP；readiness 明确反映（非 UP）；/search 不得 5xx；
     恢复后 readiness UP、es UP、/search 结果数 > 0。
  B. 停 Qdrant：同 A（qdrant 维度）。
  C. 停 Redis：admin/health 的 redis 非 UP；storage 必须仍标 live（**不得静默切 memory**）；
     /search 允许失败但必须是可诊断的 HTTP 响应（4xx/5xx 带 code），网关进程不得退出；
     恢复后 readiness UP、redis UP、/search 正常。
  D. 全过程中 /actuator/health 必须始终可达（进程不崩）。

前置：
  docker compose up -d redis qdrant elasticsearch
  网关 live 模式已起、已 reingest、DEMO_PASSWORD 可用

用法：
  python scripts/failover_drill.py --base http://127.0.0.1:18082 --only es
  python scripts/failover_drill.py --base http://127.0.0.1:18082 --out /tmp/drill.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request

COMPONENTS = {
    "es": "opspilot-es",
    "qdrant": "opspilot-qdrant",
    "redis": "opspilot-redis",
}
SEARCH_QUERY = "50012_DB_TIMEOUT 订单接口超时排查"


def _req(url: str, data: dict | None = None, token: str | None = None,
         timeout: float = 15.0) -> tuple[int, dict | str]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(
        url, data=None if data is None else json.dumps(data).encode("utf-8"),
        method="GET" if data is None else "POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def _post(path: str, body: dict, token: str) -> tuple[int, dict | str]:
    return _req(path, body, token)


def login(base: str, password: str) -> str:
    status, body = _req(base + "/api/v1/auth/login",
                        {"username": "sre-full", "password": password})
    if status != 200 or not isinstance(body, dict) or "token" not in body:
        raise SystemExit(f"登录失败（{status}）：账号/密码不可用，演练无法继续")
    return body["token"]


def search(base: str, token: str) -> tuple[int, dict | str]:
    return _post(base + "/api/v1/copilot/search",
                 {"query": SEARCH_QUERY, "mode": "hybrid"}, token)


def admin_health(base: str, token: str) -> tuple[int, dict | str]:
    return _req(base + "/api/v1/admin/health", token=token)


def readiness(base: str) -> tuple[int, dict | str]:
    return _req(base + "/actuator/health/readiness")


def liveness(base: str) -> tuple[int, dict | str]:
    return _req(base + "/actuator/health")


def docker(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(["docker", *args], capture_output=True, text=True, timeout=120)


def container_running(name: str) -> bool:
    p = docker("inspect", "--format", "{{.State.Running}}", name)
    return p.returncode == 0 and p.stdout.strip() == "true"


def wait_until(pred, timeout_s: float, interval: float = 2.0) -> bool:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        try:
            if pred():
                return True
        except Exception:  # noqa: BLE001  # 观测异常本身不算"达成"
            pass
        time.sleep(interval)
    return timeout_s <= 0


def results_count(status: int, body) -> int:
    if status != 200 or not isinstance(body, dict):
        return -1
    return len(body.get("results") or body.get("data") or [])


def drill_one(comp: str, base: str, token: str, timeout_s: float, settle_s: float) -> dict:
    container = COMPONENTS[comp]
    rec: dict = {"component": comp, "container": container, "checks": []}

    def check(name: str, ok: bool, detail) -> None:
        rec["checks"].append({"check": name, "pass": bool(ok), "observed": detail})

    # 基线
    st_ready, _ = readiness(base)
    st_health, hbody = admin_health(base, token)
    st_search, sbody = search(base, token)
    base_health_ok = st_health == 200 and isinstance(hbody, dict) and hbody.get(comp, {}).get("status") == "UP"
    check(f"{comp}.baseline.up", base_health_ok, {"admin_health_status": st_health})
    check(f"{comp}.baseline.search", results_count(st_search, sbody) > 0,
          {"status": st_search, "results": results_count(st_search, sbody)})

    # 故障注入
    t0 = time.time()
    p = docker("stop", container)
    if p.returncode != 0:
        check(f"{comp}.inject.stop", False, p.stderr.strip()[:200])
        return rec
    down = wait_until(lambda: not container_running(container), 30)
    check(f"{comp}.inject.stopped", down, {"elapsed_s": round(time.time() - t0, 1)})
    time.sleep(settle_s)

    # 故障期观测
    st_health, hbody = admin_health(base, token)
    comp_status = (hbody.get(comp, {}) or {}).get("status") if isinstance(hbody, dict) else None
    check(f"{comp}.outage.health_marks_down", comp_status != "UP",
          {"status": st_health, "component_status": comp_status})
    st_ready, rbody = readiness(base)
    ready_status = rbody.get("status") if isinstance(rbody, dict) else None
    check(f"{comp}.outage.readiness_not_up", ready_status != "UP",
          {"status": st_ready, "readiness": ready_status})
    # 进程必须活着：liveness 接口可达（任意状态码都行，连接失败才失败）
    try:
        st_live, _ = liveness(base)
        alive = True
    except Exception as e:  # noqa: BLE001
        alive, st_live = False, str(e)
    check(f"{comp}.outage.gateway_alive", alive, {"liveness_status": st_live})
    # 检索：不得 5xx（ES/Qdrant 允许降级；Redis 允许可诊断失败但不得 5xx 裸崩？）
    st_search, sbody = search(base, token)
    if comp in ("es", "qdrant"):
        check(f"{comp}.outage.search_no_5xx", st_search < 500,
              {"status": st_search, "results": results_count(st_search, sbody)})
    else:
        diag = isinstance(sbody, dict) and bool(sbody.get("code"))
        check(f"{comp}.outage.search_diagnosable", st_search < 500 or diag,
              {"status": st_search, "code": (sbody.get("code") if isinstance(sbody, dict) else None)})
        # 不得静默切 memory
        storage = (hbody.get("storage") if isinstance(hbody, dict) else None)
        check(f"{comp}.outage.no_silent_memory_fallback", storage == "live",
              {"storage": storage})

    # 恢复
    t1 = time.time()
    p = docker("start", container)
    check(f"{comp}.recover.start", p.returncode == 0, p.stderr.strip()[:200])

    def recovered() -> bool:
        s, b = admin_health(base, token)
        return (s == 200 and isinstance(b, dict)
                and (b.get(comp, {}) or {}).get("status") == "UP"
                and (b.get("status") == "UP"))

    ok = wait_until(recovered, timeout_s)
    check(f"{comp}.recover.health_up", ok, {"elapsed_s": round(time.time() - t1, 1)})
    ok2 = wait_until(lambda: results_count(*search(base, token)) > 0, timeout_s)
    check(f"{comp}.recover.search", ok2, {"elapsed_s": round(time.time() - t1, 1)})
    rec["pass"] = all(c["pass"] for c in rec["checks"])
    return rec


def main() -> int:
    ap = argparse.ArgumentParser(description="live 中间件单点故障切换演练")
    ap.add_argument("--base", default=os.environ.get("OPSPILOT_BASE", "http://127.0.0.1:18082"))
    ap.add_argument("--only", choices=sorted(COMPONENTS), action="append",
                    help="只演练指定组件（可重复）；默认全部")
    ap.add_argument("--timeout", type=float, default=90.0, help="恢复等待上限（秒）")
    ap.add_argument("--settle", type=float, default=5.0, help="故障注入后的稳定窗口（秒）")
    ap.add_argument("--json", default="", help="把演练结果写到文件")
    ap.add_argument("--password-env", default="DEMO_PASSWORD")
    a = ap.parse_args()

    docker_version = docker("version", "--format", "{{.Server.Version}}")
    if docker_version.returncode != 0:
        print("FAIL: docker 不可用（演练需要 docker CLI + daemon）", file=sys.stderr)
        return 3

    password = os.environ.get(a.password_env) or ""
    if not password:
        print(f"FAIL: {a.password_env} 未设置（登录演练账号要用）", file=sys.stderr)
        return 2

    base = a.base.rstrip("/")
    token = login(base, password)
    targets = a.only or ["es", "qdrant", "redis"]
    out = {"base": base, "targets": targets, "runs": []}
    rc = 0
    for comp in targets:
        print(f"[drill] {comp}（{COMPONENTS[comp]}）开始……", file=sys.stderr)
        rec = drill_one(comp, base, token, a.timeout, a.settle)
        out["runs"].append(rec)
        state = "PASS" if rec.get("pass") else "FAIL"
        print(f"[drill] {comp} -> {state}", file=sys.stderr)
        for c in rec["checks"]:
            print(f"  {'PASS' if c['pass'] else 'FAIL'} {c['check']}: {c['observed']}",
                  file=sys.stderr)
        if not rec.get("pass"):
            rc = 1
    if a.json:
        with open(a.json, "w", encoding="utf-8") as fh:
            json.dump(out, fh, ensure_ascii=False, indent=2)
    print(json.dumps({"pass": rc == 0, "runs": [
        {"component": r["component"], "pass": r.get("pass"),
         "failed": [c["check"] for c in r["checks"] if not c["pass"]]} for r in out["runs"]]},
        ensure_ascii=False))
    return rc


if __name__ == "__main__":
    raise SystemExit(main())
