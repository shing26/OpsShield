"""旅程 5：告警入口——alert chat 落台账 → 面板建单 → 再次请求幂等。"""
from __future__ import annotations

from playwright.sync_api import expect

from helpers import (
    TENANT,
    api,
    chat_stream,
    list_incidents,
    login_api,
    new_page,
    ui_login,
    unique,
)


def test_alert_row_creates_one_incident_and_stays_idempotent(server, browser):
    alert_id = unique("e2e-alert")
    token = login_api(server, "e2e-owner", server.owner_password)

    stream = chat_stream(server, token, {
        "query": "50012_DB_TIMEOUT 怎么排查",
        "source": "alert",
        "service": "order-service",
        "env": "prod",
        "alertId": alert_id,
    })
    assert "event:done" in stream or "event: done" in stream, "告警 chat 未收到 done"

    context, page = new_page(browser)
    try:
        ui_login(page, server, "e2e-owner", server.owner_password)
        page.locator("#tab-panel").click()
        expect(page.locator("#v-panel")).to_be_visible()

        create = page.locator(f'[data-alert="{alert_id}"]')
        expect(create).to_be_visible(timeout=30000)
        row = create.locator("xpath=ancestor::tr")
        expect(row).to_contain_text(alert_id)
        with page.expect_response(
            lambda r: f"/api/v1/admin/alerts/{alert_id}/incident" in r.url
            and r.request.method == "POST",
            timeout=30000,
        ) as created:
            create.click()

        assert created.value.ok, f"面板建单失败：HTTP {created.value.status}"
        incident_id = created.value.json()["id"]
        expect(page.locator("#wsHead")).to_contain_text("告警 " + alert_id, timeout=20000)

        status, again = api(
            server, "POST",
            f"/api/v1/admin/alerts/{alert_id}/incident?tenant={TENANT}",
            token, {},
        )
        assert status == 200, f"重复建单应返回 200，实际 {status}"
        assert again["id"] == incident_id, (
            f"幂等失败：首次 {incident_id} / 再次 {again['id']}")

        matches = [x for x in list_incidents(server, token) if x.get("alertId") == alert_id]
        assert len(matches) == 1, f"alertId 对应事故单不是一条：{len(matches)}"
        assert matches[0]["id"] == incident_id

        _, recent = api(server, "GET", "/api/v1/admin/alerts/recent?since=0&limit=50", token)
        rows = [x for x in recent.get("alerts", []) if x.get("alertId") == alert_id]
        assert len(rows) == 1, f"台账里 alertId 行数异常：{len(rows)}"
        assert rows[0].get("incidentId") == incident_id
    finally:
        context.close()
