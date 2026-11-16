# -*- coding: utf-8 -*-
"""领域词词法门闩（ADR-0029 R1）——防止 SRE 领域词重新焊回信任底座。

ADR-0029 决策 1 的可静态判定版：底座模块的**判据与逻辑**不含领域词汇（产品命令、
领域服务名、SRE 专属主体/文案）。R1 把落点收窄到当前干净底座包：

    storm/ auth/ cache/ store/ metrics/ llm/VerbatimGuard.java
    resilience/（排除 SopFallbackService.java）

`action/` 按 R1 **不纳入**：命令家族策略接口提取前，kubectl 旗标 / systemctl 宾语这类
家族尾部校验仍含领域词，立闸只会对合法机制假红，或逼出"假装无领域词"的空抽象。

三条刻意的判据设计：

1. **标识符边界，不是子串**。匹配用 `(?<![A-Za-z0-9_])word(?![A-Za-z0-9_])`：
   `redis` 不会命中 `redistribute`，`kubectl` 不会命中 `kubectlRunner`，`sre` 不会命中
   `CommandsRejected` 里的 `sRe`。前后允许 `/`、`.`、`-`、空格等非标识符字符，故
   `/usr/bin/kubectl`、`nginx.conf`、`systemctl restart` 都能命中。

2. **注释命中只告警，代码/字符串字面量命中才退 1**。ADR-0029 的判据词是"判据与逻辑"，
   注释是文档不是逻辑；ADR-0023 已经否决过"全仓禁用词表"，理由正是精确术语需要合法栖息地
   （ADR/OPS/代码注释）。把注释也做成硬闸会逼出为过闸而改写说明文字的失效方式。因此：
     - 代码面（含字符串、文本块）命中 → stdout `file:line: word`，exit 1；
     - 注释面命中 → stderr `WARN file:line: word`，只报不闸；
     - `--strict-comments` 可把注释命中同样当违规（留给更严的将来）。
   注释与代码的切分由 `_mask_comments` 做最小 Java 词法扫描：`//`、`/* */`、字符串、
   字符字面量、文本块；行号在两侧都保留。

3. **词表校准，不按路径静默豁免**。以下两类别刻意不入词表，理由写在词表旁边：
     - 裸 `redis`：它是 ADR-0022 的**存储后端**，`store/` 整包以它命名；底座对后端的
       称呼不是 SRE 领域命令。词表收命令/配置形态 `redis-cli` / `redis-server` /
       `redis.conf`。
     - `SOP`：它是底座兜底机制的键族/指标名；R1 排除的是 `SopFallbackService` 的
       **领域内容**，不是机制名。
   这两项是词表校准（书面例外），不是把某个包从扫描面里拿掉。

退出码：0 = 无代码面违规（可能有注释告警）；1 = 有代码面违规；2 = 路径/IO 错误
或扫描面为空（fail-closed——悄悄失效的门闩比没有门闩更危险）。

只用 stdlib，不触网；默认根是仓库根，可用 `--root` 指向镜像/临时树；`--json` 给
CI/工具消费。`offline/tests/test_check_domain_words.py` 用 tmp_path 钉住判据极性。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# ADR-0029 R1 的干净底座包。文件项（llm/VerbatimGuard.java）与目录项同表登记。
SCAN_ROOTS = (
    "src/main/java/com/opspilot/storm",
    "src/main/java/com/opspilot/auth",
    "src/main/java/com/opspilot/cache",
    "src/main/java/com/opspilot/store",
    "src/main/java/com/opspilot/metrics",
    "src/main/java/com/opspilot/llm/VerbatimGuard.java",
    "src/main/java/com/opspilot/resilience",
)

# R1 的白名单例外：SopFallbackService 的兜底 Runbook 是领域内容，等模板契约提取后再纳入。
EXCLUDED_FILES = frozenset(
    {"src/main/java/com/opspilot/resilience/SopFallbackService.java"}
)

# 领域命令/产品：语料 runbook 里真实使用的运维命令形态。
# 裸 `redis` 刻意不在内（见模块 docstring 的书面例外）。
DOMAIN_COMMANDS = (
    "kubectl",
    "systemctl",
    "nginx",
    "mysql",
    "mariadb",
    "postgres",
    "postgresql",
    "mysqladmin",
    "mysqldump",
    "redis-cli",
    "redis-server",
    "redis.conf",
)

# 领域服务/产品副本：语料里出现过的业务服务名。
DOMAIN_SERVICES = (
    "order-api",
    "inventory-api",
    "payment-service",
    "order-service",
    "inventory-service",
    "coupon-service",
)

# SRE 专属主体：演示/告警账号的 sre-* 前缀是领域身份，不应出现在底座 auth 包的示例里。
SRE_SUBJECTS = (
    "sre-full",
    "sre-limited",
    "sre-acme",
    "sre-watcher",
)

# SRE 专属文案/产物名：与语料目录同名的领域概念。
DOMAIN_COPY = ("runbook", "postmortem")


class GateError(Exception):
    """门闩自身的问题（路径缺失 / IO 失败 / 扫描面为空），区别于"发现领域词"。"""


def _token(literal: str) -> re.Pattern[str]:
    """标识符边界匹配：不命中更长标识符里的子串，但允许命令路径/后缀/空格。

    `redistribute` 不命中 `redis`；`/usr/bin/kubectl`、`nginx.conf`、`systemctl restart`
    都命中。大小写不敏感——领域词在注释里常被首字母大写。
    """
    return re.compile(
        r"(?i)(?<![A-Za-z0-9_])" + re.escape(literal) + r"(?![A-Za-z0-9_])"
    )


# 错误码形状：语料里的 50081_K8S_POD_OOMKILLED。只认 5 位数字 + 下划线 + 大写段，
# 不误伤 OP-R3 / OP-A5 这类项目任务号（它们没有下划线大写段）。
_ERROR_CODE = re.compile(
    r"(?<![A-Za-z0-9_])\d{5}_[A-Z][A-Z0-9_]+(?![A-Za-z0-9_])"
)

WORD_PATTERNS = tuple(
    _token(word)
    for word in (*DOMAIN_COMMANDS, *DOMAIN_SERVICES, *SRE_SUBJECTS, *DOMAIN_COPY)
) + (_ERROR_CODE,)


@dataclass(frozen=True)
class Finding:
    file: str
    line: int
    word: str
    severity: str  # "error"（代码/字符串字面量）| "warning"（注释）


@dataclass(frozen=True)
class ScanResult:
    files: tuple[str, ...]
    findings: tuple[Finding, ...]

    @property
    def violations(self) -> tuple[Finding, ...]:
        return tuple(f for f in self.findings if f.severity == "error")

    @property
    def warnings(self) -> tuple[Finding, ...]:
        return tuple(f for f in self.findings if f.severity == "warning")


def _rel(path: Path, root: Path) -> str:
    """输出统一用相对 root 的 POSIX 路径，跨平台可复现。"""
    try:
        return path.relative_to(root).as_posix()
    except ValueError:
        return path.as_posix()


# ---------------------------------------------------------------- Java 词法遮罩

_NORMAL, _LINE, _BLOCK, _STRING, _CHAR, _TEXT_BLOCK = range(6)


def _mask_comments(text: str) -> tuple[str, str]:
    """把源码拆成"代码面"和"注释面"两份等长文本，换行位置严格一致。

    代码面：注释字符替换为空格，字符串/字符/文本块内容**保留**（它们是逻辑的一部分）。
    注释面：反过来的遮罩。两份都保留所有 `\\n`，故 `splitlines()` 的行号与原文件一致。

    这是最小 Java 词法扫描，不追求完整语法：目标只是把 `//`、`/* */` 与字符串里的
    `//`、`/*` 区分开，避免把 URL / 正则字符串误判成注释。
    """
    code: list[str] = []
    comment: list[str] = []
    i, n = 0, len(text)
    state = _NORMAL

    def put(ch: str, *, to_code: bool) -> None:
        # 换行必须在两侧都保留，否则行号会漂移。
        if ch == "\n":
            code.append("\n")
            comment.append("\n")
        elif to_code:
            code.append(ch)
            comment.append(" ")
        else:
            code.append(" ")
            comment.append(ch)

    while i < n:
        ch = text[i]
        if state == _NORMAL:
            if text.startswith("//", i):
                put("/", to_code=False)
                put("/", to_code=False)
                i += 2
                state = _LINE
            elif text.startswith("/*", i):
                put("/", to_code=False)
                put("*", to_code=False)
                i += 2
                state = _BLOCK
            elif text.startswith('"""', i) and re.match(
                r'"""[ \t]*\r?\n', text[i:]
            ):
                # Java 文本块的开定界符后必须跟行终止符；这能避免把
                # `"" + "x"` 这类连续字符串误判成文本块。
                for _ in range(3):
                    put('"', to_code=True)
                i += 3
                state = _TEXT_BLOCK
            elif ch == '"':
                put(ch, to_code=True)
                i += 1
                state = _STRING
            elif ch == "'":
                put(ch, to_code=True)
                i += 1
                state = _CHAR
            else:
                put(ch, to_code=True)
                i += 1
        elif state == _LINE:
            put(ch, to_code=False)
            i += 1
            if ch == "\n":
                state = _NORMAL
        elif state == _BLOCK:
            if text.startswith("*/", i):
                put("*", to_code=False)
                put("/", to_code=False)
                i += 2
                state = _NORMAL
            else:
                put(ch, to_code=False)
                i += 1
        elif state in (_STRING, _CHAR):
            quote = '"' if state == _STRING else "'"
            if ch == "\\":
                put(ch, to_code=True)
                i += 1
                if i < n:
                    put(text[i], to_code=True)
                    i += 1
            elif ch == quote:
                put(ch, to_code=True)
                i += 1
                state = _NORMAL
            elif ch == "\n":
                # 未闭合字面量：保留新行后回到普通态，避免把余下文件全遮罩。
                put(ch, to_code=True)
                i += 1
                state = _NORMAL
            else:
                put(ch, to_code=True)
                i += 1
        elif state == _TEXT_BLOCK:
            if text.startswith('"""', i):
                for _ in range(3):
                    put('"', to_code=True)
                i += 3
                state = _NORMAL
            elif ch == "\\":
                put(ch, to_code=True)
                i += 1
                if i < n:
                    put(text[i], to_code=True)
                    i += 1
            else:
                put(ch, to_code=True)
                i += 1

    return "".join(code), "".join(comment)


def _matches(buffer: str) -> list[tuple[int, str]]:
    """返回 [(行号, 命中词)]；同一行同一词只报一次。"""
    found: list[tuple[int, str]] = []
    for lineno, line in enumerate(buffer.splitlines(), 1):
        seen: set[str] = set()
        for pattern in WORD_PATTERNS:
            for match in pattern.finditer(line):
                word = match.group(0)
                if word not in seen:
                    seen.add(word)
                    found.append((lineno, word))
    return found


# ---------------------------------------------------------------- 扫描与裁决


def _iter_files(root: Path) -> list[Path]:
    """按 R1 扫描面收集文件；任何缺失/空扫描面都 fail-closed。"""
    if not root.exists():
        raise GateError(f"--root 不存在：{root}")
    if not root.is_dir():
        raise GateError(f"--root 不是目录：{root}")

    files: list[Path] = []
    for rel in SCAN_ROOTS:
        path = root / rel
        if not path.exists():
            raise GateError(f"扫描根缺失：{rel}")
        if path.is_file():
            if _rel(path, root) not in EXCLUDED_FILES:
                files.append(path)
            continue
        if not path.is_dir():
            raise GateError(f"扫描根既不是文件也不是目录：{rel}")
        found = [
            p
            for p in sorted(path.rglob("*.java"))
            if p.is_file() and _rel(p, root) not in EXCLUDED_FILES
        ]
        if not found:
            raise GateError(f"扫描根下没有 .java 文件：{rel}")
        files.extend(found)

    if not files:
        raise GateError("扫描面为空——空转的门闩比没有门闩更危险")
    # rglob 已排序；set 去重后再按相对路径排一次，输出稳定。
    return sorted(set(files), key=lambda p: _rel(p, root))


def scan(root: Path = ROOT, *, strict_comments: bool = False) -> ScanResult:
    """扫描并返回结果；路径/IO 问题抛 GateError（调用方负责 exit 2）。"""
    files = _iter_files(root)
    findings: dict[tuple[str, int, str], Finding] = {}
    for path in files:
        rel = _rel(path, root)
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as exc:
            raise GateError(f"{rel} 读取失败：{exc}") from exc

        code, comment = _mask_comments(text)
        for buffer, severity in (
            (code, "error"),
            (comment, "error" if strict_comments else "warning"),
        ):
            for lineno, word in _matches(buffer):
                key = (rel, lineno, word)
                previous = findings.get(key)
                # 同一行同一词若代码面命中，error 优先于 comment 的 warning。
                if previous is None or severity == "error":
                    findings[key] = Finding(rel, lineno, word, severity)

    ordered = tuple(
        sorted(findings.values(), key=lambda f: (f.file, f.line, f.word, f.severity))
    )
    return ScanResult(tuple(_rel(p, root) for p in files), ordered)


def check(
    root: Path = ROOT,
    *,
    strict_comments: bool = False,
    out=None,
    err=None,
) -> int:
    """人读输出 + 退出码；stdout 只放 `file:line: word` 违规行，告警走 stderr。"""
    out = out or sys.stdout
    err = err or sys.stderr
    try:
        result = scan(root, strict_comments=strict_comments)
    except GateError as exc:
        print(f"ERROR: {exc}", file=err)
        return 2

    for finding in result.violations:
        print(f"{finding.file}:{finding.line}: {finding.word}", file=out)
    for finding in result.warnings:
        print(f"WARN {finding.file}:{finding.line}: {finding.word}", file=err)

    if result.violations:
        print(
            f"FAIL: {len(result.violations)} 处领域词渗透（ADR-0029 R1；"
            f"扫描 {len(result.files)} 个文件）",
            file=err,
        )
        return 1

    extra = f"；{len(result.warnings)} 处注释命中仅告警" if result.warnings else ""
    print(f"OK: 领域词门闩通过（{len(result.files)} 个文件{extra}）", file=out)
    return 0


def _json_payload(result: ScanResult) -> dict:
    return {
        "ok": not result.violations,
        "scanned_files": len(result.files),
        "count": len(result.violations),
        "violations": [
            {"file": f.file, "line": f.line, "word": f.word, "severity": f.severity}
            for f in result.violations
        ],
        "warnings": [
            {"file": f.file, "line": f.line, "word": f.word, "severity": f.severity}
            for f in result.warnings
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="ADR-0029 R1 领域词词法门闩：干净底座包不得含 SRE 领域命令/服务/主体词。",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=ROOT,
        help=f"仓库根（默认 {ROOT}）；测试/镜像可用临时树",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="输出 JSON（violations + warnings），便于 CI/工具消费",
    )
    parser.add_argument(
        "--strict-comments",
        action="store_true",
        help="注释面命中同样按违规计（默认只告警，见模块 docstring）",
    )
    args = parser.parse_args(argv)

    if args.json:
        try:
            result = scan(args.root, strict_comments=args.strict_comments)
        except GateError as exc:
            print(
                json.dumps({"ok": False, "error": str(exc)}, ensure_ascii=False),
                file=sys.stdout,
            )
            return 2
        print(json.dumps(_json_payload(result), ensure_ascii=False, indent=2))
        return 0 if not result.violations else 1

    return check(args.root, strict_comments=args.strict_comments)


if __name__ == "__main__":
    sys.exit(main())
