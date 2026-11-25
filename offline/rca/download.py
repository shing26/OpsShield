"""RCAEval 数据集下载器（M0）。

只从 HuggingFace 公共地址拉取；网络层用 stdlib，索引解析用 pandas。
产物落 ``offline/rca/data/``（gitignored），并写 ``PROVENANCE.json`` 记录逐文件 sha256。

用法::

    python offline/rca/download.py --suite re1-ob
    python offline/rca/download.py --suite re1-ob --verify
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
INDEX_FILE = "cases.parquet"
DEFAULT_DATA = Path(__file__).resolve().parent / "data"
UA = "opspilot-rca/0.1 (+offline research slice)"

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


def fetch(url: str, dest: Path, force: bool = False, retries: int = 4,
          timeout: int = 90) -> None:
    if dest.exists() and not force:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    last_error: Exception | None = None
    for attempt in range(retries):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(request, timeout=timeout) as response:
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


def write_provenance(data_dir: Path, suite: str, names: list[str]) -> None:
    files = {}
    for rel in [INDEX_FILE] + [f"{name}/{f}" for name in names for f in ("metrics.parquet", "inject_time.txt")]:
        path = data_dir / rel
        files[rel] = {"sha256": sha256_file(path), "bytes": path.stat().st_size}
    payload = {
        "source": BASE,
        "dataset": "phamquiluan/RCAEval",
        "suite": suite,
        "generated_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "n_cases": len(names),
        "files": files,
    }
    (data_dir / "PROVENANCE.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )


def verify(data_dir: Path) -> int:
    manifest_path = data_dir / "PROVENANCE.json"
    if not manifest_path.exists():
        print("FAIL: PROVENANCE.json missing", file=sys.stderr)
        return 2
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
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
    fetch(f"{BASE}/{INDEX_FILE}", data_dir / INDEX_FILE, force=force)

    frame = load_index(data_dir)
    rows = frame[frame["dataset"] == label].sort_values("case")
    if rows.empty:
        print(f"FAIL: no cases for {label}", file=sys.stderr)
        return 2

    jobs = []
    for _, row in rows.iterrows():
        for name in case_files(row):
            jobs.append((f"{BASE}/{row['case']}/{name}", data_dir / row["case"] / name))

    failed = []
    with ThreadPoolExecutor(max_workers=workers) as pool:
        futures = {pool.submit(fetch, url, dest, force): dest for url, dest in jobs}
        for future in as_completed(futures):
            try:
                future.result()
            except Exception as exc:  # noqa: BLE001 - 汇总后统一报错
                failed.append(f"{futures[future]}: {exc}")

    if failed:
        print("FAIL:\n  " + "\n  ".join(failed), file=sys.stderr)
        return 2

    names = rows["case"].tolist()
    write_provenance(data_dir, suite, names)
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
        return verify(args.data_dir)
    return download_suite(args.suite, args.data_dir, args.force, args.workers)


if __name__ == "__main__":
    raise SystemExit(main())
