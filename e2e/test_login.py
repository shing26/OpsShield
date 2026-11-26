"""旅程 1：登录与会话——错口令 / 正口令 / 刷新保持 / 新 context 未接入。"""
from __future__ import annotations

from playwright.sync_api import expect

from helpers import new_page


def test_wrong_password_then_login_and_session_scope(server, browser):
    context, page = new_page(browser)
    try:
        page.goto(server.base_url + "/")
        expect(page.locator("#acct")).to_contain_text("未接入")

        page.locator("#acct").click()
        expect(page.locator("#acctpop")).to_be_visible()
        page.locator("#a-user").fill("e2e-owner")
        page.locator("#a-pass").fill("definitely-wrong-password")
        page.locator('[data-act="login:a"]').click()

        error = page.locator('#acctpop [data-testid="login-error"]')
        expect(error).to_be_visible()
        expect(error).to_contain_text("401")
        expect(page.locator("#acct")).to_contain_text("未接入")
        assert page.evaluate("sessionStorage.getItem('opspilot.session.jwt.a')") is None

        page.locator("#a-pass").fill(server.owner_password)
        with page.expect_response(
            lambda r: "/api/v1/copilot/incidents" in r.url and r.request.method == "GET",
            timeout=20000,
        ) as response:
            page.locator('[data-act="login:a"]').click()
        assert response.value.ok, f"事故列表首屏失败：HTTP {response.value.status}"

        expect(page.locator("#acct")).to_contain_text("e2e-owner")
        token = page.evaluate("sessionStorage.getItem('opspilot.session.jwt.a')")
        assert token, "正口令登录后 sessionStorage 里没有 JWT"

        page.reload()
        expect(page.locator("#acct")).to_contain_text("e2e-owner")
        assert page.evaluate("sessionStorage.getItem('opspilot.session.jwt.a')") == token

        fresh_context, fresh_page = new_page(browser)
        try:
            fresh_page.goto(server.base_url + "/")
            expect(fresh_page.locator("#acct")).to_contain_text("未接入")
            assert fresh_page.evaluate(
                "sessionStorage.getItem('opspilot.session.jwt.a')") is None
        finally:
            fresh_context.close()
    finally:
        context.close()
