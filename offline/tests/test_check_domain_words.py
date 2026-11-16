# -*- coding: utf-8 -*-
"""领域词词法门闩的回归锁（`scripts/check_domain_words.py`，ADR-0029 R1）。

这道门闩有一类静默失效：扫描根改名/包被移走，脚本仍"在跑"但文件集空了，从此恒绿。
本文件用 tmp_path 构造仓库镜像，钉住六件事：

  1. **clean 通过**：七个 R1 扫描根齐全且无领域词时退 0。
  2. **代码面命中失败**：字符串字面量/文本块里的 kubectl 退 1，并给出 `file:line: word`。
  3. **注释不是自动豁免**：未登记的注释命中退 1；只有 `EXEMPTIONS` 里 (file, word,
     line_contains) 三项都命中的既有举例才降为 `EXEMPT`，且注释一改例外即失效；
     `--strict` 可忽略例外做审计。
  4. **fail-closed**：根缺失、扫描子树缺失、文件不可读（非 UTF-8）一律退 2——空转/带病
     扫描不得静默通过。
  5. **排除文件生效**：`resilience/SopFallbackService.java` 里的领域词不参与扫描。
  6. **误报边界**：标识符边界让 `redistribute` 不命中 `redis`、`kubectlRunner` 不命中
     `kubectl`，但 `/usr/bin/kubectl`、`nginx.conf`、`redis-cli` 仍命中。

测试不依赖仓库真实文件（除导入脚本外），镜像可随意改坏。变异测试只改 tmp 树，仓库本体
永不被测试改脏。
"""
from __future__ import annotations

import contextlib
import io
import json
import shutil
import sys
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "scripts"))

# 经 sys.path 正常导入（与其它用例一致）：importlib 按路径各自加载会产生新模块对象，
# 于是"把判据改坏再跑用例"这种变异验证传导不进去，回归锁等于没被验证过。
import check_domain_words as cdw  # noqa: E402

_JAVA_CLEAN = 'class Clean { String mode = "memory"; }\n'
_EXCLUDED_REL = next(iter(cdw.EXCLUDED_FILES))


def _mirror(base: Path) -> Path:
    """构造一份七个 R1 扫描根齐全的仓库镜像；排除文件里故意放一个领域词。"""
    root = base / "repo"
    for rel in cdw.SCAN_ROOTS:
        path = root / rel
        if path.suffix == ".java":
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("class VerbatimGuard {}\n", encoding="utf-8")
        else:
            path.mkdir(parents=True, exist_ok=True)
            (path / "Clean.java").write_text(_JAVA_CLEAN, encoding="utf-8")

    excluded = root / _EXCLUDED_REL
    excluded.parent.mkdir(parents=True, exist_ok=True)
    excluded.write_text(
        'class SopFallbackService { String cmd = "kubectl get pods"; }\n',
        encoding="utf-8",
    )
    return root


def _write(root: Path, rel: str, text: str) -> Path:
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path


def _run(root: Path, **kwargs) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    rc = cdw.check(root, out=out, err=err, **kwargs)
    return rc, out.getvalue(), err.getvalue()


def _run_json(root: Path, **kwargs) -> tuple[int, dict]:
    buf = io.StringIO()
    with contextlib.redirect_stdout(buf):
        rc = cdw.main(["--root", str(root), "--json", *kwargs.pop("argv", [])], **kwargs)
    return rc, json.loads(buf.getvalue())


# ---------------------------------------------------------------- 1. clean 通过

def test_clean_tree_passes(tmp_path: Path) -> None:
    """七个扫描根齐全且无领域词：退 0，stdout 打 OK，排除文件里的 kubectl 不出现在输出。"""
    root = _mirror(tmp_path)
    rc, out, err = _run(root)
    assert rc == 0, err
    assert "OK:" in out
    assert "kubectl" not in out
    assert "kubectl" not in err


def test_scan_roots_match_adr_r1_scope() -> None:
    """扫描面 = R1 的干净底座包；action/ 与领域混合包不得混入。"""
    joined = "\n".join(cdw.SCAN_ROOTS)
    for rel in ("storm", "auth", "cache", "store", "metrics", "resilience"):
        assert f"com/opspilot/{rel}" in joined
    assert "llm/VerbatimGuard.java" in joined
    assert "action/" not in joined
    assert "llm/PromptAssembler.java" not in joined
    assert "gateway/" not in joined


def test_action_package_is_not_scanned(tmp_path: Path) -> None:
    """R1 明确延后 action/：那里放 kubectl 不得让门闩变红。"""
    root = _mirror(tmp_path)
    _write(
        root,
        "src/main/java/com/opspilot/action/ControlledCommandPolicy.java",
        'class ControlledCommandPolicy { String[] TOOLS = {"kubectl"}; }\n',
    )
    rc, out, err = _run(root)
    assert rc == 0, err
    assert "kubectl" not in out


# ---------------------------------------------------------------- 2. 代码面命中

def test_string_literal_hit_fails_and_reports_file_line(tmp_path: Path) -> None:
    """字符串字面量是逻辑的一部分：kubectl 命中必须退 1，并给 `file:line: word`。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/storm/SingleFlight.java"
    _write(root, rel, 'class SingleFlight {\n  String cmd = "kubectl get pods";\n}\n')

    rc, out, err = _run(root)
    assert rc == 1
    assert f"{rel}:2: kubectl" in out
    assert "FAIL:" in err


def test_text_block_hit_fails(tmp_path: Path) -> None:
    """Java 文本块里的领域词属于代码面，不得被当成注释放行。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/auth/AuthService.java"
    _write(
        root,
        rel,
        'class AuthService {\n  String s = """\n    systemctl restart nginx\n  """;\n}\n',
    )
    rc, out, _ = _run(root)
    assert rc == 1
    assert f"{rel}:3: systemctl" in out
    assert f"{rel}:3: nginx" in out


def test_double_slash_inside_string_is_not_a_comment(tmp_path: Path) -> None:
    """`http://` 在字符串里，不能把后面的 kubectl 误判成注释告警。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/cache/L1CacheService.java"
    _write(root, rel, 'class L1CacheService { String s = "http://kubectl"; }\n')
    rc, out, _ = _run(root)
    assert rc == 1
    assert f"{rel}:1: kubectl" in out


@pytest.mark.parametrize(
    "word",
    ["order-api", "inventory-api", "sre-full", "redis-cli", "50081_K8S_POD_OOMKILLED"],
)
def test_wordlist_entries_are_live(tmp_path: Path, word: str) -> None:
    """逐个词表项反向验证：漏掉一个 = 那条词表是死的。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/store/KvStore.java"
    _write(root, rel, f'class KvStore {{ String s = "{word}"; }}\n')
    rc, out, _ = _run(root)
    assert rc == 1
    assert f"{rel}:1: {word}" in out


# ---------------------------------------------------------------- 3. 注释与书面例外

def test_comment_hit_without_exemption_fails(tmp_path: Path) -> None:
    """注释不是自动豁免：没有登记例外的 kubectl 同样退 1。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/storm/SingleFlight.java"
    _write(root, rel, 'class SingleFlight {\n  // kubectl get pods\n}\n')

    rc, out, err = _run(root)
    assert rc == 1
    assert f"{rel}:2: kubectl" in out
    assert "FAIL:" in err


def test_written_exemption_applies_to_known_comment(tmp_path: Path) -> None:
    """登记过的既有注释举例降为 EXEMPT：退 0，但 stderr 仍报 file:line。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/metrics/AuditService.java"
    _write(root, rel, 'class AuditService {\n  // 200 会截断长 kubectl\n}\n')

    rc, out, err = _run(root)
    assert rc == 0, err
    assert "kubectl" not in out
    assert f"EXEMPT {rel}:2: kubectl" in err


def test_block_comment_exemption_applies(tmp_path: Path) -> None:
    """Javadoc 里的 sre-full 举例同样按登记例外处理。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/auth/UserAdminCli.java"
    _write(root, rel, 'class UserAdminCli {\n  /** 如 sre-full → platform。 */\n}\n')

    rc, out, err = _run(root)
    assert rc == 0, err
    assert "sre-full" not in out
    assert f"EXEMPT {rel}:2: sre-full" in err


def test_exemption_expires_when_comment_changes(tmp_path: Path) -> None:
    """line_contains 是失效条件：注释改写后旧例外不再适用，门闩重新变红。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/metrics/AuditService.java"
    _write(root, rel, 'class AuditService {\n  // 200 会截断长命令；kubectl\n}\n')

    rc, out, err = _run(root)
    assert rc == 1
    assert f"{rel}:2: kubectl" in out
    assert "EXEMPT" not in err


def test_exemption_does_not_apply_to_code(tmp_path: Path) -> None:
    """代码/字符串字面量永不豁免：即使同一文件同一行文本命中了 line_contains。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/metrics/AuditService.java"
    _write(root, rel, 'class AuditService {\n  String s = "200 会截断长 kubectl";\n}\n')

    rc, out, err = _run(root)
    assert rc == 1
    assert f"{rel}:2: kubectl" in out
    assert "EXEMPT" not in err


def test_strict_ignores_written_exemptions(tmp_path: Path) -> None:
    """--strict 用于审计例外：登记过的注释命中同样退 1。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/metrics/AuditService.java"
    _write(root, rel, 'class AuditService {\n  // 200 会截断长 kubectl\n}\n')
    rc, out, _ = _run(root, strict=True)
    assert rc == 1
    assert f"{rel}:2: kubectl" in out


# ---------------------------------------------------------------- 4. fail-closed

def test_missing_root_fails_closed(tmp_path: Path) -> None:
    """--root 指向不存在的路径：退 2，不得静默 0。"""
    rc, out, err = _run(tmp_path / "does-not-exist")
    assert rc == 2
    assert "ERROR:" in err
    assert out == ""


def test_missing_scan_subtree_fails_closed(tmp_path: Path) -> None:
    """包被改名/移走导致某个 R1 扫描根消失：退 2，不得只扫剩下的。"""
    root = _mirror(tmp_path)
    shutil.rmtree(root / "src/main/java/com/opspilot/store")
    rc, _, err = _run(root)
    assert rc == 2
    assert "store" in err


def test_empty_scan_subtree_fails_closed(tmp_path: Path) -> None:
    """扫描根还在但 .java 文件被搬空：退 2，不得把空包当"干净"。"""
    root = _mirror(tmp_path)
    (root / "src/main/java/com/opspilot/metrics/Clean.java").unlink()
    rc, _, err = _run(root)
    assert rc == 2
    assert "没有 .java 文件" in err


def test_non_utf8_source_fails_closed(tmp_path: Path) -> None:
    """不可读（非 UTF-8）文件是 IO 错误：退 2，不能当作"没命中"。"""
    root = _mirror(tmp_path)
    (root / "src/main/java/com/opspilot/cache/Clean.java").write_bytes(
        b"\xff\xfe\x00kubectl"
    )
    rc, _, err = _run(root)
    assert rc == 2
    assert "读取失败" in err


# ---------------------------------------------------------------- 5. 排除生效

def test_excluded_file_is_skipped(tmp_path: Path) -> None:
    """SopFallbackService 的领域内容按 R1 排除；它里面放代码面 kubectl 也不得变红。"""
    root = _mirror(tmp_path)
    # _mirror 已在排除文件里放了字符串 "kubectl get pods"；这里再确认一遍。
    text = (root / _EXCLUDED_REL).read_text(encoding="utf-8")
    assert "kubectl" in text
    rc, out, err = _run(root)
    assert rc == 0, err
    assert "kubectl" not in out and "kubectl" not in err


# ---------------------------------------------------------------- 6. 误报边界

def test_redistribute_does_not_match_redis(tmp_path: Path) -> None:
    """标识符边界：`redistribute` 里的 `redis` 不是领域词命中。"""
    assert cdw._token("redis").search("we redistribute shards") is None
    # 命令形态仍命中（边界允许 `-`）。
    assert cdw._token("redis").search("redis-cli --latency") is not None

    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/storm/SlidingWindow.java"
    _write(root, rel, 'class SlidingWindow { String s = "redistribute the load"; }\n')
    rc, out, err = _run(root)
    assert rc == 0, err
    assert "redis" not in out.lower()


def test_longer_identifiers_do_not_match(tmp_path: Path) -> None:
    """`mykubectl` / `kubectlRunner` 不是命令；`/usr/bin/kubectl` 是。"""
    pattern = cdw._token("kubectl")
    assert pattern.search("mykubectl") is None
    assert pattern.search("kubectlRunner") is None
    assert pattern.search("/usr/bin/kubectl") is not None

    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/storm/SingleFlight.java"
    _write(root, rel, 'class SingleFlight { String s = "mykubectl kubectlRunner"; }\n')
    rc, _, err = _run(root)
    assert rc == 0, err


# ---------------------------------------------------------------- JSON 面

def test_json_output_lists_violations_and_exemptions(tmp_path: Path) -> None:
    """--json 同时给违规与书面例外，字段可被 CI/工具直接消费。"""
    root = _mirror(tmp_path)
    rel = "src/main/java/com/opspilot/store/KvStore.java"
    _write(root, rel, 'class KvStore {\n  String s = "mysql";\n}\n')
    exempt_rel = "src/main/java/com/opspilot/metrics/AuditService.java"
    _write(root, exempt_rel, 'class AuditService {\n  // 200 会截断长 kubectl\n}\n')

    rc, payload = _run_json(root)
    assert rc == 1
    assert payload["ok"] is False
    assert payload["count"] == 1
    assert payload["violations"][0] == {
        "file": rel,
        "line": 2,
        "word": "mysql",
        "severity": "error",
    }
    assert payload["exemptions"][0]["file"] == exempt_rel
    assert payload["exemptions"][0]["word"] == "kubectl"
    assert payload["exemptions"][0]["severity"] == "exempt"
