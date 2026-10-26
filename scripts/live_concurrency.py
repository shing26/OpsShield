#!/usr/bin/env python3
"""G1 live 并发/隔离采样：单租户 20 并发 + 另一租户对照。

用途：门禁 6（live 单租户压测）与门禁 7（滚动窗口样本）。
不打印 token/答案；只输出状态、TTFT 分位、429、租户对照。
"""
import argparse, json, os, statistics, sys, threading, time
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
        try:
            error = json.loads(e.read().decode("utf-8", "ignore")).get("code")
        except Exception:
            error = None
    except Exception as e:
        status = 0
        error = type(e).__name__ + ": " + str(e)[:120]
    return {"status": status, "ttft_s": ttft, "total_s": round(time.time() - t0, 3),
            "refs": refs, "error": error}


def login(user):
    return localapi.login(user)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=10)
    ap.add_argument("--concurrency", type=int, default=20)
    ap.add_argument("--out", default="docs/ops/live-concurrency-2026-10-23.json")
    a = ap.parse_args()
    load_user = "sre-full"       # tenant-internal
    control_user = "sre-acme"    # tenant-acme
    load_token = login(load_user)
    control_token = login(control_user)
    # /actuator/prometheus 是文本格式，不是 JSON；只需要最终快照。
    before = urllib.request.urlopen(BASE + "/actuator/prometheus").read().decode()
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
    after = urllib.request.urlopen(BASE + "/actuator/prometheus").read().decode()

    def summary(rows):
        ok = sum(1 for r in rows if r["status"] == 200)
        too = sum(1 for r in rows if r["status"] == 429)
        bad = len(rows) - ok - too
        ttfts = [r["ttft_s"] for r in rows if r.get("ttft_s") is not None]
        ttfts.sort()
        def pct(p):
            if not ttfts:
                return None
            k = max(0, min(len(ttfts)-1, int(round((p/100)*(len(ttfts)-1)))))
            return ttfts[k]
        return {"total": len(rows), "ok": ok, "rejected_429": too, "other": bad,
                "ok_rate": round(ok/len(rows), 4) if rows else 0,
                "ttft_p50": pct(50), "ttft_p95": pct(95), "ttft_p99": pct(99),
                "ttft_max": max(ttfts) if ttfts else None}

    def metric(name):
        for line in after.splitlines():
            if line.startswith(name + " ") or line.startswith(name + "{"):
                try: return float(line.split()[-1])
                except Exception: pass
        return None

    out = {"started_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "elapsed_s": elapsed,
           "load_user": load_user, "load_tenant": "tenant-internal",
           "control_user": control_user, "control_tenant": "tenant-acme",
           "concurrency": a.concurrency, "seconds": a.seconds,
           "load": summary(load), "control": summary(control),
           "metrics": {k: metric(k) for k in [
               "aiops_requests_total", "aiops_requests_failed_total",
               "aiops_admission_rejected_total", "aiops_llm_calls_total",
               "aiops_llm_rate_limited_total", "aiops_degradation_level"]},
           "raw": {"load": load, "control": control}}
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print(json.dumps({k: out[k] for k in ["elapsed_s", "load", "control", "metrics"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
