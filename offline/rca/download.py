"""RCAEval 数据集下载器。

主源 HuggingFace；失败可回落 ``hf-mirror.com``，实际来源写进 per-suite provenance。
产物落 ``offline/rca/data/``（gitignored），每套数据写 ``PROVENANCE-<suite>.json``，
两套互不覆盖。

用法::

    python offline/rca/download.py --suite re1-ob
    python offline/rca/download.py --suite re1-ss
    python offline/rca/download.py --suite re1-ss --verify
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

BASE = "https://huggingface.co/datasets/phamquiluan/RCAEval/resolve/main"
MIRROR = "https://hf-mirror.com/datasets/phamquiluan/RCAEval/resolve/main"
SOURCES = (BASE, MIRROR)
INDEX_FILE = "cases.parquet"
DEFAULT_DATA = Path(__file__).resolve().parent / "data"
UA = "opspilot-rca/0.2 (+offline research slice)"
# 代理的 DNS 解析对 hf-mirror 不稳定；代理失败时用直连兜底（顺序仍是主源优先）。
DIRECT_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

SUITES = {
    "re1-ob": "RE1-OB",
    "re1-ss": "RE1-SS",
    "re1-tt": "RE1-TT",
    "re2-ob": "RE2-OB",
    "re2-ss": "RE2-SS",
    "re2-tt": "RE2-TT",
    "re3-ob": "RE3-OB",
    "re3-ss": "RE3-SS",
    "re3-tt": "RE3-TT",
}


def sha256_file(path: Path, chunk: int = 1 << 20) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(chunk), b""):
            digest.update(block)
    return digest.hexdigest()


def provenance_path(data_dir: Path, suite: str) -> Path:
    return data_dir / f"PROVENANCE-{suite}.json"


def _open(url: str, timeout: int):
    request = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        return urllib.request.urlopen(request, timeout=timeout)
    except (urllib.error.URLError, OSError):
        return DIRECT_OPENER.open(request, timeout=timeout)


def probe(urls: tuple[str, ...], timeout: int = 25) -> tuple[list[str], dict[str, str]]:
    """逐个探测源是否可用；只读前 1KB，避免把整套数据拉一遍。"""
    live: list[str] = []
    report: dict[str, str] = {}
    for base in urls:
        try:
            response = _open(f"{base}/{INDEX_FILE}", timeout)
            with response:
                response.read(1024)
            live.append(base)
            report[base] = "ok"
        except Exception as exc:  # noqa: BLE001 - 探测失败只记录，不中断
            report[base] = f"fail: {type(exc).__name__}: {str(exc)[:160]}"
    return live, report


def fetch(url: str, dest: Path, force: bool = False, retries: int = 3,
          timeout: int = 90) -> None:
    if dest.exists() and not force:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            with _open(url, timeout) as response:
                tmp.write_bytes(response.read())
            if tmp.stat().st_size == 0:
                raise OSError(f"empty response: {url}")
            os.replace(tmp, dest)
            return
        except (urllib.error.URLError, OSError, TimeoutError) as exc:
            last_error = exc
            tmp.unlink(missing_ok=True)
            if attempt + 1 < retries:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"download failed after {retries} attempts: {url}") from last_error


def fetch_any(urls: tuple[str, ...], dest: Path, force: bool = False) -> str:
    """按顺序尝试镜像；返回实际来源（命中缓存时返回 ``cached``）。"""
    if dest.exists() and not force:
        return "cached"
    errors = []
    for url in urls:
        try:
            fetch(url, dest, force=True)
            return url
        except Exception as exc:  # noqa: BLE001 - 逐个镜像尝试
            errors.append(f"{url}: {exc}")
    raise RuntimeError("all mirrors failed:\n" + "\n".join(errors))


def load_index(data_dir: Path) -> pd.DataFrame:
    index_path = data_dir / INDEX_FILE
    if not index_path.exists():
        raise SystemExit(f"missing index: {index_path} (run download first)")
    frame = pd.read_parquet(index_path)
    required = {"case", "dataset", "root_cause_service", "fault", "repetition"}
    missing = required - set(frame.columns)
    if missing:
        raise SystemExit(f"index missing columns: {sorted(missing)}")
    return frame


def suite_label(suite: str) -> str:
    try:
        return SUITES[suite]
    except KeyError:
        raise SystemExit(f"unknown suite {suite!r}; choose from {sorted(SUITES)}") from None


def case_files(row: pd.Series) -> list[str]:
    files = ["metrics.parquet", "inject_time.txt"]
    if bool(row.get("has_logs", False)):
        files.append("logs.parquet")
    if bool(row.get("has_traces", False)):
        files.append("traces.parquet")
    return files


def _previous_sources(data_dir: Path, suite: str) -> dict[str, str]:
    path = provenance_path(data_dir, suite)
    if not path.exists():
        legacy = data_dir / "PROVENANCE.json"
        if not legacy.exists():
            return {}
        try:
            payload = json.loads(legacy.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return {}
        if payload.get("suite") != suite:
            return {}
        fallback = payload.get("source", "unknown")
        return {rel: meta.get("source", fallback)
                for rel, meta in payload.get("files", {}).items()}
    try:
        manifest = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return {rel: meta.get("source", "unknown")
            for rel, meta in manifest.get("files", {}).items()}


def write_provenance(data_dir: Path, suite: str, names: list[str],
                     sources: dict[str, str],
                     probed: dict[str, str] | None = None) -> None:
    previous = _previous_sources(data_dir, suite)
    files = {}
    for rel in [INDEX_FILE] + [f"{name}/{f}" for name in names
                               for f in ("metrics.parquet", "inject_time.txt")]:
        path = data_dir / rel
        source = sources.get(rel)
        if not source or source == "cached":
            source = previous.get(rel, "unknown")
        files[rel] = {
            "sha256": sha256_file(path),
            "bytes": path.stat().st_size,
            "source": source,
        }
    payload = {
        "source": BASE,
        "mirrors": list(SOURCES),
        "dataset": "phamquiluan/RCAEval",
        "suite": suite,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "n_cases": len(names),
        "probed_sources": probed or {},
        "live_sources": [base for base, status in (probed or {}).items() if status == "ok"],
        "sources_used": sorted({meta["source"] for meta in files.values()
                                if meta["source"] != "unknown"}),
        "files": files,
    }
    provenance_path(data_dir, suite).write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def _manifest(data_dir: Path, suite: str) -> dict | None:
    path = provenance_path(data_dir, suite)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    legacy = data_dir / "PROVENANCE.json"
    if legacy.exists():
        payload = json.loads(legacy.read_text(encoding="utf-8"))
        if payload.get("suite") == suite:
            return payload
    return None


def verify(data_dir: Path, suite: str = "re1-ob") -> int:
    manifest = _manifest(data_dir, suite)
    if manifest is None:
        print(f"FAIL: provenance for {suite} missing", file=sys.stderr)
        return 2
    bad = []
    for rel, meta in manifest["files"].items():
        path = data_dir / rel
        if not path.exists():
            bad.append(f"missing {rel}")
        elif sha256_file(path) != meta["sha256"]:
            bad.append(f"sha256 mismatch {rel}")
    if bad:
        print("FAIL:\n  " + "\n  ".join(bad), file=sys.stderr)
        return 2
    print(f"OK: {len(manifest['files'])} files, {manifest['n_cases']} cases, suite {manifest['suite']}")
    return 0


def download_suite(suite: str, data_dir: Path, force: bool, workers: int) -> int:
    label = suite_label(suite)
    data_dir.mkdir(parents=True, exist_ok=True)
    live, probed = probe(SOURCES)
    if not live:
        print("FAIL: no source reachable\n  " + "\n  ".join(f"{k}: {v}"
                                                           for k, v in probed.items()),
              file=sys.stderr)
        return 2
    print(f"sources: live={[b.split('/')[2] for b in live]} "
          f"probe={ {b.split('/')[2]: v for b, v in probed.items()} }")
    sources: dict[str, str] = {}
    sources[INDEX_FILE] = fetch_any(tuple(f"{base}/{INDEX_FILE}" for base in live),
                                    data_dir / INDEX_FILE, force=force)

    frame = load_index(data_dir)
    rows = frame[frame["dataset"] == label].sort_values("case")
    if rows.empty:
        print(f"FAIL: no cases for {label}", file=sys.stderr)
        return 2

    jobs = []
    for _, row in rows.iterrows():
        for name in case_files(row):
            rel = f"{row['case']}/{name}"
            urls = tuple(f"{base}/{rel}" for base in live)
            jobs.append((rel, urls, data_dir / rel))

    failed = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch_any, urls, dest, force): rel
                   for rel, urls, dest in jobs}
        for future in as_completed(futures):
            rel = futures[future]
            try:
                sources[rel] = future.result()
            except Exception as exc:  # noqa: BLE001 - 汇总后统一报错
                failed.append(f"{rel}: {exc}")

    if failed:
        print("FAIL:\n  " + "\n  ".join(failed), file=sys.stderr)
        return 2

    names = rows["case"].tolist()
    write_provenance(data_dir, suite, names, sources, probed)
    print(f"OK: downloaded {len(names)} cases ({suite}) -> {data_dir}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Download RCAEval telemetry cases")
    parser.add_argument("--suite", default="re1-ob", choices=sorted(SUITES))
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--force", action="store_true", help="re-download existing files")
    parser.add_argument("--verify", action="store_true", help="verify manifest only")
    parser.add_argument("--workers", type=int, default=6)
    args = parser.parse_args(argv)

    if args.verify:
        return verify(args.data_dir, args.suite)
    return download_suite(args.suite, args.data_dir, args.force, args.workers)


if __name__ == "__main__":
    raise SystemExit(main())
