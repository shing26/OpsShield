#!/usr/bin/env python3
"""G1 live 并发/隔离采样：单租户 20 并发 + 另一租户对照。

用途：门禁 6（live 单租户压测）与门禁 7（滚动窗口样本）。
不打印 token/答案；只输出状态、TTFT 分位、429 与 Retry-After、租户对照。

**schema 2（2026-11-05）**——门禁 6 的判据此前**无法从产物证明**，这次补上：
- `retry_after_s`：429 响应头，判据「429 必须带 Retry-After」从口头变成字段；
- `metrics.aiops_degradation_state`：此前抓的 `aiops_degradation_level` 在应用里不存在，
  永远是 null（2026-10-23 样本实证），等于"对照租户降级档不亮"这条判据从未被真正验证；
- `metrics_delta`：before/after 差值（`before` 此前抓了但没用），绝对值的相邻样本没法对账；
- `--out` 默认按日期生成，且同名已存在时拒绝覆盖（旧默认是写死的 2026-10-23 路径，
  重跑会覆盖历史证据）。

旧样本（schema 1）仍可读：聚合工具把缺失字段标成"未证明"，而不是当成通过。
"""
import argparse, json, os, sys, threading, time
import urllib.error, urllib.request

BASE = os.environ.get("OPSPILOT_BASE", "http://localhost:8081").rstrip("/")
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "offline"))
import localapi
from localapi import assert_local


def chat(token, query, timeout=60):
    body = json.dumps({"query": query, "source": "manual"}).encode()
    req = urllib.request.Request(
        assert_local(BASE + "/api/v1/copilot/chat/stream"),
        data=body, method="POST",
        headers={"Authorization": "Bearer " + token,
                 "Content-Type": "application/json",
                 "Accept": "text/event-stream"})
    t0 = time.time()
    ttft = None
    status = 0
    error = None
    refs = 0
    retry_after = None
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            status = r.status
            event = None
            for raw in r:
                line = raw.decode("utf-8", "replace").rstrip("\n")
                if line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    if event == "delta" and ttft is None:
                        ttft = round(time.time() - t0, 3)
                    elif event == "done":
                        try:
                            refs = len(json.loads(line[5:].strip()).get("refs") or [])
                        except Exception:
                            pass
    except urllib.error.HTTPError as e:
        status = e.code
        # 门禁 6 的「429 带 Retry-After」判据：只数 429 个数是证明不了它的。
        raw = e.headers.get("Retry-After") if e.headers else None
        if raw is not None:
            try:
                retry_after = int(raw.strip())
            except ValueError:
                retry_after = None
        try:
            error = json.loads(e.read().decode("utf-8", "ignore")).get("code")
        except Exception:
            error = None
    except Exception as e:
        status = 0
        error = type(e).__name__ + ": " + str(e)[:120]
    return {"status": status, "ttft_s": ttft, "total_s": round(time.time() - t0, 3),
            "refs": refs, "error": error, "retry_after_s": retry_after}


def login(user):
    return localapi.login(user)


COUNTERS = ["aiops_requests_total", "aiops_requests_failed_total",
            "aiops_admission_rejected_total", "aiops_llm_calls_total",
            "aiops_llm_rate_limited_total"]


def parse_metrics(text):
    """从 /actuator/prometheus 抓取体里取出关心的序列。

    带标签的计数器（如 `aiops_requests_failed_total{reason=...}`）**按序列求和**：
    只取最后一条会让 metrics_delta 少算其它 reason 桶，而"5xx=0"正是门禁 6 的硬判据。
    仪表（degradation）只该有一条，取最后一条。
    """
    out = {}
    for line in text.splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        name = line.split("{", 1)[0].split(" ", 1)[0]
        if name not in COUNTERS and name != "aiops_degradation_state":
            continue
        try:
            value = float(line.split()[-1])
        except (ValueError, IndexError):
            out.setdefault(name, None)
            continue
        if name == "aiops_degradation_state":
            out[name] = value
        else:
            out[name] = round((out.get(name) or 0.0) + value, 6)
    return out


def snapshot():
    """抓一次 /actuator/prometheus（文本格式）并取出关心的序列。"""
    url = assert_local(BASE + "/actuator/prometheus")
    with urllib.request.urlopen(url, timeout=10) as r:
        return parse_metrics(r.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=10)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--out", default=None,
                    help="产物路径（默认 docs/ops/live-concurrency-<日期>-c<并发>.json；"
                         "同名存在时拒绝覆盖，需 --force）")
    ap.add_argument("--force", action="store_true", help="覆盖同名产物")
    a = ap.parse_args()
    load_user = "sre-full"       # tenant-internal
    control_user = "sre-acme"    # tenant-acme
    load_token = login(load_user)
    control_token = login(control_user)
    before = snapshot()
    load = []
    control = []
    lock = threading.Lock()
    deadline = time.time() + a.seconds

    def worker(i):
        n = 0
        local = []
        while time.time() < deadline:
            n += 1
            r = chat(load_token, f"live-load-{i}-{n} 50012_DB_TIMEOUT 怎么排查")
            local.append(r)
        with lock:
            load.extend(local)

    def probe():
        n = 0
        while time.time() < deadline:
            n += 1
            r = chat(control_token, f"control-{n} 50012_DB_TIMEOUT 怎么排查")
            r["at_s"] = round(time.time() - (deadline - a.seconds), 1)
            with lock:
                control.append(r)
            time.sleep(2)

    threads = [threading.Thread(target=worker, args=(i,)) for i in range(a.concurrency)]
    cp = threading.Thread(target=probe)
    t0 = time.time()
    cp.start()
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    cp.join()
    elapsed = round(time.time() - t0, 2)
    after = snapshot()

    def summary(rows):
        ok = sum(1 for r in rows if r["status"] == 200)
        too = sum(1 for r in rows if r["status"] == 429)
        bad = len(rows) - ok - too
        ttfts = sorted(r["ttft_s"] for r in rows if r.get("ttft_s") is not None)
        def pct(p):
            if not ttfts:
                return None
            k = max(0, min(len(ttfts)-1, int(round((p/100)*(len(ttfts)-1)))))
            return ttfts[k]
        # 「429 必须带 Retry-After」：分开数"带了"和"没带"，而不是只数 429 总数。
        with_retry = sum(1 for r in rows if r["status"] == 429 and r.get("retry_after_s") is not None)
        return {"total": len(rows), "ok": ok, "rejected_429": too, "other": bad,
                "ok_rate": round(ok/len(rows), 4) if rows else 0,
                "retry_after_ok": with_retry, "retry_after_missing": too - with_retry,
                "ttft_p50": pct(50), "ttft_p95": pct(95), "ttft_p99": pct(99),
                "ttft_max": max(ttfts) if ttfts else None}

    delta = {k: (round(after[k] - before[k], 3)
                 if before.get(k) is not None and after.get(k) is not None else None)
             for k in COUNTERS}
    state = after.get("aiops_degradation_state")
    degradation = {"state": state,
                   "level": {0.0: "L0", 1.0: "L1", 2.0: "L2"}.get(state) if state is not None else None}

    out = {"schema": 2,
           "started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "elapsed_s": elapsed,
           "load_user": load_user, "load_tenant": "tenant-internal",
           "control_user": control_user, "control_tenant": "tenant-acme",
           "concurrency": a.concurrency, "seconds": a.seconds,
           "load": summary(load), "control": summary(control),
           "metrics": after, "metrics_before": before, "metrics_delta": delta,
           "degradation": degradation,
           "raw": {"load": load, "control": control}}
    path = a.out or os.path.join(
        os.path.dirname(__file__), "..", "docs", "ops",
        "live-concurrency-%s-c%d.json" % (time.strftime("%Y-%m-%d"), a.concurrency))
    path = os.path.normpath(path)
    if os.path.exists(path) and not a.force:
        raise SystemExit("产物已存在：%s（用 --force 覆盖，或换 --out）" % path)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: out[k] for k in
                      ["elapsed_s", "load", "control", "metrics_delta", "degradation"]},
                     ensure_ascii=False))
    print("已写入：" + path)


if __name__ == "__main__":
    main()
