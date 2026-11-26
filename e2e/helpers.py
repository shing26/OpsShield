"""共享夹具：真 fat jar + 全新 H2 + memory/mock，业务数据只走公开 API。"""
from __future__ import annotations

import json
import os
import re
import secrets
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from playwright.sync_api import Browser, BrowserContext, Page, expect

ROOT = Path(__file__).resolve().parents[1]
# 语料 metadata.tenant 只有 tenant-internal / tenant-acme 两个域；身份过滤按
# (tenant, auth_level) 硬匹配，所以两个 E2E 账号必须落在 tenant-internal 才能检索到 runbook。
TENANT = "tenant-internal"
OWNER = "e2e-owner"
PEER = "e2e-peer"
JAR_NAME = "opspilot-gateway-1.0.0.jar"

# conftest 的结束断言读这个列表；任何新开的 page 都必须经 new_page() 创建。
PAGE_ERRORS: list[str] = []


@dataclass
class Server:
    base_url: str
    workdir: Path
    log_path: Path
    chunks_path: Path
    owner_password: str
    peer_password: str
    proc: subprocess.Popen = field(repr=False)

    def stop(self) -> None:
        if self.proc.poll() is not None:
            return
        self.proc.terminate()
        try:
            self.proc.wait(timeout=15)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            self.proc.wait(timeout=10)


def _java_bin() -> str:
    """按 JAVA_BIN → JAVA_HOME → 本机 JDK 21 → PATH 顺序找，并拒绝 <21 的运行时。"""
    candidates: list[str] = []
    if os.environ.get("JAVA_BIN"):
        candidates.append(os.environ["JAVA_BIN"])
    if os.environ.get("JAVA_HOME"):
        home = Path(os.environ["JAVA_HOME"])
        candidates.extend([str(home / "bin" / "java"), str(home / "bin" / "java.exe")])
    candidates.extend([r"E:\java\jdk21\bin\java.exe", shutil.which("java") or ""])

    tried: list[str] = []
    for candidate in candidates:
        if not candidate or not Path(candidate).exists():
            continue
        tried.append(candidate)
        proc = subprocess.run([candidate, "-version"], capture_output=True, text=True, timeout=30)
        version_text = (proc.stderr or "") + (proc.stdout or "")
        match = re.search(r'version "(\d+)', version_text)
        if match and int(match.group(1)) >= 21:
            return candidate
    raise RuntimeError(
        "找不到 JDK 21+：设置 JAVA_BIN 或 JAVA_HOME。已尝试：" + (", ".join(tried) or "无")
    )


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def _seed_user(work: Path, java: str, db_url: str, db_password: str,
               user: str, password_env: str, password: str,
               level: int, role: str) -> None:
    env = dict(os.environ)
    env.update({
        "H2_DB_URL": db_url,
        "H2_DB_PASSWORD": db_password,
        password_env: password,
    })
    cmd = [
        java, "-cp", "app.jar",
        "-Dloader.main=com.opspilot.auth.UserAdminCli",
        "org.springframework.boot.loader.launch.PropertiesLauncher",
        "add", "--user", user, "--tenant", TENANT,
        "--level", str(level), "--role", role,
        "--password-env", password_env,
    ]
    proc = subprocess.run(cmd, cwd=work, env=env, capture_output=True, text=True, timeout=120)
    if proc.returncode != 0:
        raise RuntimeError(
            f"seed {user} 失败（exit {proc.returncode}）\n"
            f"stdout:\n{proc.stdout[-2000:]}\nstderr:\n{proc.stderr[-4000:]}"
        )


def start_server() -> Server:
    jar = ROOT / "target" / JAR_NAME
    if not jar.exists():
        raise RuntimeError(
            f"缺少 fat jar：{jar}\n先跑：mvn -o -DskipTests package"
        )

    work = Path(tempfile.mkdtemp(prefix="opspilot-e2e-"))
    for name in ("data", "logs", "backup"):
        (work / name).mkdir()
    (work / "offline" / "corpus").mkdir(parents=True)
    shutil.copy2(jar, work / "app.jar")
    shutil.copy2(ROOT / "offline" / "corpus" / "chunks.jsonl",
                 work / "offline" / "corpus" / "chunks.jsonl")

    java = _java_bin()
    db_url = f"jdbc:h2:file:{(work / 'data' / 'users').as_posix()}"
    db_password = secrets.token_urlsafe(24)
    owner_password = secrets.token_urlsafe(18)
    peer_password = secrets.token_urlsafe(18)

    _seed_user(work, java, db_url, db_password, OWNER, "E2E_OWNER_PASSWORD",
               owner_password, 3, "platform")
    _seed_user(work, java, db_url, db_password, PEER, "E2E_PEER_PASSWORD",
               peer_password, 2, "sre")

    port = _free_port()
    base_url = f"http://127.0.0.1:{port}"
    log_path = work / "app.log"
    env = dict(os.environ)
    env.update({
        "H2_DB_URL": db_url,
        "H2_DB_PASSWORD": db_password,
        "STORAGE_MODE": "memory",
        "DASHSCOPE_MODE": "mock",
        "DASHSCOPE_API_KEY": "",
        "SERVER_PORT": str(port),
        "SERVER_ADDRESS": "127.0.0.1",
        "JWT_SECRET": secrets.token_urlsafe(64),
    })

    log = log_path.open("w", encoding="utf-8")
    proc = subprocess.Popen(
        [java, "-jar", "app.jar"],
        cwd=work, env=env, stdout=log, stderr=subprocess.STDOUT,
    )
    # 子进程已复制句柄；父进程不再持有写端，避免 Windows 上日志文件被锁。
    log.close()
    server = Server(base_url, work, log_path, work / "offline" / "corpus" / "chunks.jsonl",
                    owner_password, peer_password, proc)

    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            log.close()
            tail = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
            shutil.rmtree(work, ignore_errors=True)
            raise RuntimeError(f"网关启动即退出（exit {proc.returncode}）\n{tail}")
        try:
            with urllib.request.urlopen(base_url + "/actuator/health", timeout=2) as resp:
                body = resp.read().decode("utf-8")
            if '"status":"UP"' in body:
                return server
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.5)  # 契约允许的例外：仅启动探活的有界轮询，不用于 UI 等待

    server.stop()
    log.close()
    tail = log_path.read_text(encoding="utf-8", errors="replace")[-4000:]
    shutil.rmtree(work, ignore_errors=True)
    raise RuntimeError(f"/actuator/health 60s 内未 UP\n{tail}")


def api(server: Server, method: str, path: str, token: str | None = None,
        body: Any | None = None, timeout: int = 60) -> tuple[int, Any]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(server.base_url + path, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8", errors="replace")
        raise AssertionError(f"{method} {path} -> HTTP {exc.code}: {raw}") from None


def login_api(server: Server, user: str, password: str) -> str:
    _, data = api(server, "POST", "/api/v1/auth/login",
                  body={"username": user, "password": password})
    token = data.get("token") if data else None
    if not token:
        raise AssertionError(f"登录 {user} 未返回 token：{data}")
    return token


def new_page(browser: Browser) -> tuple[BrowserContext, Page]:
    context = browser.new_context(
        viewport={"width": 1440, "height": 900},
        locale="zh-CN",
        timezone_id="Asia/Shanghai",
    )
    page = context.new_page()
    page.on("pageerror", lambda exc: PAGE_ERRORS.append(f"{page.url}: {exc}"))
    return context, page


def ui_login(page: Page, server: Server, user: str, password: str,
             expect_incidents: bool = True) -> None:
    page.goto(server.base_url + "/")
    page.locator("#acct").click()
    expect(page.locator("#acctpop")).to_be_visible()
    page.locator("#a-user").fill(user)
    page.locator("#a-pass").fill(password)
    if expect_incidents:
        with page.expect_response(
            lambda r: "/api/v1/copilot/incidents" in r.url and r.request.method == "GET",
            timeout=20000,
        ) as response:
            page.locator('[data-act="login:a"]').click()
        assert response.value.ok, f"事故列表首屏失败：HTTP {response.value.status}"
    else:
        page.locator('[data-act="login:a"]').click()
    expect(page.locator("#acct")).to_contain_text(user, timeout=20000)
    token = page.evaluate("sessionStorage.getItem('opspilot.session.jwt.a')")
    assert token, "登录后 sessionStorage 里没有 JWT"


def chunk_meta(chunks_path: Path) -> dict[str, tuple[str, str]]:
    out: dict[str, tuple[str, str]] = {}
    with chunks_path.open(encoding="utf-8") as fh:
        for line in fh:
            row = json.loads(line)
            meta = row.get("metadata") or {}
            doc_id = row.get("doc_id")
            digest = meta.get("doc_digest")
            if doc_id and digest:
                out[row["chunk_id"]] = (doc_id, digest)
    return out


def create_incident(server: Server, token: str, title: str, query: str,
                    **fields: Any) -> dict:
    body = {
        "clientId": "e2e-" + uuid.uuid4().hex,
        "title": title,
        "query": query,
        "source": fields.pop("source", "manual"),
        **fields,
    }
    _, data = api(server, "POST", "/api/v1/copilot/incidents", token, body)
    return data


def seed_open_incidents(server: Server, token: str, count: int, prefix: str) -> list[str]:
    ids = []
    for i in range(count):
        row = create_incident(
            server, token,
            title=f"{prefix}-{i:03d}-{uuid.uuid4().hex[:6]}",
            query="50012_DB_TIMEOUT 怎么排查",
        )
        ids.append(row["id"])
    return ids


def open_count(server: Server, token: str) -> int:
    _, data = api(server, "GET", "/api/v1/copilot/incidents?status=open&limit=1", token)
    return int(data.get("openCount") or 0)


def list_incidents(server: Server, token: str, status: str | None = None,
                   limit: int = 100) -> list[dict]:
    rows: list[dict] = []
    cursor = None
    while len(rows) < 1000:
        params = [f"limit={limit}"]
        if status:
            params.append("status=" + status)
        if cursor:
            params.append("cursor=" + urllib.parse.quote(cursor, safe=""))
        _, data = api(server, "GET", "/api/v1/copilot/incidents?" + "&".join(params), token)
        rows.extend(data.get("incidents") or [])
        if not data.get("hasMore"):
            break
        cursor = data.get("nextCursor")
        if not cursor:
            break
    return rows


def chat_stream(server: Server, token: str, body: dict, timeout: int = 90) -> str:
    req = urllib.request.Request(
        server.base_url + "/api/v1/copilot/chat/stream",
        data=json.dumps(body).encode("utf-8"),
        method="POST",
    )
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + token)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read().decode("utf-8", errors="replace")


def unique(prefix: str) -> str:
    return f"{prefix}-{uuid.uuid4().hex[:10]}"


