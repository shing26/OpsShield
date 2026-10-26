"""数据集脱敏审计器的单测（offline/eval/dataset_audit.py）。

audit_text 是纯函数：给定文本 → 命中清单。真数据集的体检由 scripts/benchmark.sh
前置运行（dataset_audit.json 入库）；这里只钉"什么该抓、什么该放行"的判据本身。
"""
from __future__ import annotations

import importlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "offline" / "eval"))
dataset_audit = importlib.import_module("dataset_audit")


def kinds(text: str) -> set[str]:
    return {f["kind"] for f in dataset_audit.audit_text("test", text)}


def test_clean_synthetic_text_passes():
    text = ("# 50012_DB_TIMEOUT 排障手册\n"
            "服务: order-service\n"
            "1. kubectl get pods -n prod | grep order\n"
            "2. 检查 MySQL 慢查询：SELECT * FROM orders WHERE created_at > NOW() - INTERVAL 1 HOUR;\n"
            "内网地址 10.0.3.12 与文档段 192.0.2.10 可用。\n")
    assert kinds(text) == set()


def test_email_flagged_but_example_domain_allowed():
    assert "email" in kinds("联系 ops-lead@corp.example.com 或 a@b.io")
    assert "email" not in kinds("联系 ops@example.com")


def test_private_key_and_long_hex_flagged():
    assert "private_key_block" in kinds("-----BEGIN RSA PRIVATE KEY-----")
    assert "long_hex_or_jwt" in kinds("token= a1b2c3d4" + "e" * 44)
    assert "long_hex_or_jwt" in kinds("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig")


def test_password_assignment_flagged_but_placeholders_allowed():
    assert "credential_assignment" in kinds("password=hunter2-secret")
    # 环境变量引用/占位符/schema 示例：部署契约不是凭据
    assert kinds("api-key: $QDRANT_API_KEY") == set()
    assert kinds('"password": "string"') == set()
    assert kinds("password: <password>") == set()


def test_public_ip_flagged_but_rfc1918_and_doc_ranges_allowed():
    assert "public_ip" in kinds("上游地址 8.8.8.8 不可达")
    assert "public_ip" in kinds("对端 172.32.1.1 不在私网段")
    assert kinds("内网 10.0.3.12 / 172.16.1.1 / 192.168.1.1 / 文档段 203.0.113.5") == set()


def test_cn_phone_flagged():
    assert "phone_cn" in kinds("值班手机 13812345678")


def test_manifest_covers_dataset_and_is_stable_shape():
    m = dataset_audit.build_manifest(Path(__file__).resolve().parents[2])
    for key in ("offline/corpus/chunks.jsonl", "offline/eval/golden_dataset.jsonl",
                "offline/eval/refuse_set.jsonl"):
        assert key in m["files"] and len(m["files"][key]["sha256"]) == 64
    assert m["runbooks"] and m["license"].startswith("CC BY 4.0")
