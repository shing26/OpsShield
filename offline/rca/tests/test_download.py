import json
from pathlib import Path

from download import provenance_path, sha256_file, suite_label, verify


def test_suite_label():
    assert suite_label("re1-ob") == "RE1-OB"
    assert suite_label("re2-tt") == "RE2-TT"


def test_provenance_is_per_suite(tmp_path: Path):
    assert provenance_path(tmp_path, "re1-ob") != provenance_path(tmp_path, "re1-ss")
    assert provenance_path(tmp_path, "re1-ss").name == "PROVENANCE-re1-ss.json"


def test_sha256_file(tmp_path: Path):
    path = tmp_path / "a.txt"
    path.write_text("hello", encoding="utf-8")
    assert sha256_file(path) == "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"


def test_verify_detects_tampering(tmp_path: Path):
    path = tmp_path / "x.bin"
    path.write_bytes(b"original")
    manifest = {
        "suite": "re1-ob",
        "n_cases": 1,
        "files": {"x.bin": {"sha256": sha256_file(path), "bytes": 8}},
    }
    (tmp_path / "PROVENANCE.json").write_text(json.dumps(manifest), encoding="utf-8")
    assert verify(tmp_path) == 0
    path.write_bytes(b"tampered")
    assert verify(tmp_path) == 2
