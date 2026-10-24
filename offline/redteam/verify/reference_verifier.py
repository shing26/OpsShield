#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""L1 reference verifier——签名派发 payload 的参考验签器（ADR-0028 / 规格 §6）。

**对外资产 + 红队靶子**：任何自动化系统侧可嵌入的约 50 行参考实现；
offline/redteam/cases/verify/ 的 4 条靶子用例打的就是它。
它自己也在 offline/redteam/verify/MANIFEST.json 里有 sha256 清单——
防"改 verifier 让红队变绿"（靶子必须可验篡改）。

校验顺序固定（规格 §6，任一失败返回可区分状态码）：
  1. HMAC 签名不符        -> 401（constant-time 比较，绝不用 ==）
  2. payload 哈希 ≠ 内容   -> 422（随附的 payload_sha256 与实际内容不符）
  3. 过期                  -> 410
  4. op_id 已签发（幂等）  -> 409

幂等存储：参考实现用 --state-file（本地 JSON，记录已签发 op_id）；
生产侧换成共享存储（规格 §4），接口不变。
"""
from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import sys
from datetime import datetime, timezone
from pathlib import Path


def payload_sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def expected_signature(key: bytes, op_id: str, declared_sha: str, expires_at: str) -> str:
    """签名材料与规格一致：HMAC-SHA256(key, op_id + "." + payload_sha256 + "." + expires_at)。"""
    msg = f"{op_id}.{declared_sha}.{expires_at}".encode("utf-8")
    return hmac.new(key, msg, hashlib.sha256).hexdigest()


def verify(op_id: str, payload: bytes, declared_sha: str, signature: str,
           expires_at: str, key: bytes, state_file: Path,
           now: datetime | None = None) -> tuple[int, str]:
    """→ (http_status, reason)。顺序固定：401 -> 422 -> 410 -> 409。"""
    # 1) 签名（constant-time）
    expected = expected_signature(key, op_id, declared_sha, expires_at)
    if not hmac.compare_digest(signature, expected):
        return 401, "signature mismatch"
    # 2) 内容哈希：随附声明必须等于实际 payload
    actual = payload_sha256(payload)
    if not hmac.compare_digest(declared_sha, actual):
        return 422, "payload hash mismatch (content tampered)"
    # 3) 过期
    exp = datetime.fromisoformat(expires_at)
    if (now or datetime.now(timezone.utc)) > exp:
        return 410, "dispatch expired"
    # 4) 幂等：op_id 只允许签发一次（参考实现：本地 state 文件）
    state: dict = {}
    if state_file.exists():
        state = json.loads(state_file.read_text(encoding="utf-8"))
    if op_id in state:
        return 409, "op_id already dispatched"
    state[op_id] = {"payload_sha256": actual, "dispatched_at": datetime.now(timezone.utc).isoformat()}
    state_file.parent.mkdir(parents=True, exist_ok=True)
    state_file.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 200, "verified & dispatched"


def main() -> int:
    ap = argparse.ArgumentParser(description="L1 dispatch payload reference verifier")
    ap.add_argument("--op-id", required=True)
    ap.add_argument("--payload-file", required=True)
    ap.add_argument("--declared-sha256", required=True)
    ap.add_argument("--signature", required=True)
    ap.add_argument("--expires-at", required=True)
    ap.add_argument("--key-hex", required=True, help="HMAC key (hex)；生产从密管注入")
    ap.add_argument("--state-file", default="dispatch_state.json")
    a = ap.parse_args()
    payload = Path(a.payload_file).read_bytes()
    status, reason = verify(a.op_id, payload, a.declared_sha256, a.signature,
                            a.expires_at, bytes.fromhex(a.key_hex), Path(a.state_file))
    print(json.dumps({"status": status, "reason": reason}))
    return 0 if status == 200 else 1


if __name__ == "__main__":
    raise SystemExit(main())
