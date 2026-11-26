"""旅程 3：事故协作闭环——建单 → 分配 → 通知铃 → 评论 @ → 未读 → 结单。"""
from __future__ import annotations

import re

from playwright.sync_api import expect

from helpers import new_page, ui_login, unique


def test_incident_collaboration_roundtrip(server, browser):
    title = unique("E2E 协作单")
    owner_context, owner = new_page(browser)
    peer_context, peer = new_page(browser)
    try:
        ui_login(owner, server, "e2e-owner", server.owner_password)

        owner.locator('[data-act="newinc"]').first.click()
        expect(owner.locator("#incDialog")).to_be_visible()
        owner.locator("#ni-title").fill(title)
        owner.locator("#ni-query").fill("50012_DB_TIMEOUT 怎么排查")
        owner.locator("#ni-service").fill("order-service")
        owner.locator("#niOnly").click()
        expect(owner.locator("#incDialog")).to_be_hidden()

        incident_id = owner.locator("#topInc").inner_text().strip()
        assert incident_id and incident_id != "—"
        row = owner.locator(f'button.inc[data-inc="{incident_id}"]')
        expect(row).to_be_visible(timeout=15000)
        expect(row).to_have_class(re.compile(r"\bon\b"))
        expect(owner.locator("#wsHead h1")).to_contain_text(title)

        owner.locator('[data-act="assign"]').click()
        expect(owner.locator("#assignpop")).to_be_visible()
        owner.locator('#assignpop [data-assign="e2e-peer"]').click()
        expect(owner.locator("#assignpop")).to_be_hidden()
        expect(owner.locator('[data-act="assign"]')).to_contain_text("e2e-peer", timeout=15000)

        ui_login(peer, server, "e2e-peer", server.peer_password)
        badge = peer.locator("#notifybadge")
        expect(badge).to_be_visible(timeout=20000)
        expect(badge).to_have_text(re.compile(r"^[1-9]\d*$"))
        peer.locator("#notifybtn").click()
        notice = peer.locator(f'#notifypop [data-incident="{incident_id}"]')
        expect(notice).to_be_visible(timeout=15000)
        notice.click()
        expect(peer.locator("#wsHead h1")).to_contain_text(title, timeout=20000)
        expect(peer.locator("#topInc")).to_have_text(incident_id)

        comment = f"@e2e-peer 请复核 {title}"
        owner.locator("#commentBox").fill(comment)
        owner.locator('[data-act="sendcomment"]').click()
        expect(owner.locator(".cm .cm-bd").filter(has_text=comment)).to_be_visible(timeout=15000)

        peer.reload()
        unread = peer.locator(f'button.inc[data-inc="{incident_id}"] .inc-unread')
        expect(unread).to_be_visible(timeout=25000)
        peer.locator(f'button.inc[data-inc="{incident_id}"]').click()
        expect(unread).to_have_count(0, timeout=15000)

        owner.locator('#wsHead [data-act="closeinc"]').click()
        expect(owner.locator("#closeDialog")).to_be_visible()
        owner.locator('#closeForm button[type="submit"]').click()
        expect(owner.locator("#closeMsg")).to_have_text("请填写处置结论")
        owner.locator("#closeNote").fill("E2E 处置结论：回滚后确认恢复")
        owner.locator('#closeForm button[type="submit"]').click()
        expect(owner.locator("#closeDialog")).to_be_hidden()
        expect(owner.locator("#wsHead .wk-title .pill")).to_have_text("已结单", timeout=15000)

        with owner.expect_response(
            lambda r: "/api/v1/copilot/incidents" in r.url
            and r.request.method == "GET" and "status=closed" in r.url,
            timeout=20000,
        ):
            owner.locator('#incFilter [data-filter="closed"]').click()
        expect(owner.locator(f'button.inc[data-inc="{incident_id}"]')).to_be_visible(timeout=15000)
    finally:
        peer_context.close()
        owner_context.close()
