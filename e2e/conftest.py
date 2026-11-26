from __future__ import annotations

import shutil
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

from helpers import PAGE_ERRORS, Server, start_server  # noqa: E402

ARTIFACTS = Path(__file__).resolve().parent / "artifacts"
_FAILED = {"value": False}


@pytest.hookimpl(hookwrapper=True)
def pytest_runtest_makereport(item, call):
    outcome = yield
    report = outcome.get_result()
    if report.failed:
        _FAILED["value"] = True


@pytest.fixture(scope="session")
def browser_context_args(browser_context_args):
    return {
        **browser_context_args,
        "viewport": {"width": 1440, "height": 900},
        "locale": "zh-CN",
        "timezone_id": "Asia/Shanghai",
    }


@pytest.fixture(scope="session")
def server() -> Server:
    instance = start_server()
    try:
        yield instance
    finally:
        if _FAILED["value"]:
            ARTIFACTS.mkdir(parents=True, exist_ok=True)
            shutil.copy2(instance.log_path, ARTIFACTS / "server.log")
        instance.stop()
        shutil.rmtree(instance.workdir, ignore_errors=True)


@pytest.fixture(autouse=True)
def _no_page_errors(page):
    PAGE_ERRORS.clear()
    page.on("pageerror", lambda exc: PAGE_ERRORS.append(f"{page.url}: {exc}"))
    yield
    assert not PAGE_ERRORS, "未捕获的页面异常：\n" + "\n".join(PAGE_ERRORS)
