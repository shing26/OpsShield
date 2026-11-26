"""旅程 4：历史分页——首屏 60 → 加载更多不重复 → 切筛选重置。"""
from __future__ import annotations

from playwright.sync_api import expect

from helpers import (
    list_incidents,
    login_api,
    new_page,
    open_count,
    seed_open_incidents,
    ui_login,
)


def test_load_more_dedup_and_filter_reset(server, browser):
    token = login_api(server, "e2e-owner", server.owner_password)
    target_open = 130
    missing = target_open - open_count(server, token)
    if missing > 0:
        seed_open_incidents(server, token, missing, "E2E 分页")

    total_open = open_count(server, token)
    assert total_open >= target_open, f"open 事故不足：{total_open}"

    context, page = new_page(browser)
    try:
        ui_login(page, server, "e2e-owner", server.owner_password)
        rows = page.locator("#incList button.inc")
        expect(rows).to_have_count(min(60, total_open), timeout=25000)

        more = page.locator("#incMore")
        expect(more).to_be_visible()
        more.click()
        expect(rows).to_have_count(min(120, total_open), timeout=25000)

        ids = rows.evaluate_all("els => els.map(el => el.dataset.inc)")
        assert len(ids) == len(set(ids)), "加载更多后出现重复事故单"

        closed = list_incidents(server, token, status="closed")
        with page.expect_response(
            lambda r: "/api/v1/copilot/incidents" in r.url
            and r.request.method == "GET" and "status=closed" in r.url,
            timeout=20000,
        ):
            page.locator('#incFilter [data-filter="closed"]').click()
        expect(rows).to_have_count(min(60, len(closed)), timeout=20000)
        if len(closed) <= 60:
            expect(more).to_be_hidden()
        else:
            expect(more).to_be_visible()

        every = list_incidents(server, token)
        page.locator('#incFilter [data-filter="all"]').click()
        expect(rows).to_have_count(min(60, len(every)), timeout=20000)
        if len(every) <= 60:
            expect(more).to_be_hidden()
        else:
            expect(more).to_be_visible()
    finally:
        context.close()
