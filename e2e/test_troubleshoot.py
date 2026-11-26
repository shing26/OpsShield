"""旅程 2：排障主链路——真实 SSE 完成 → 结论 + 引用版本 + 只读命令。"""
from __future__ import annotations

from playwright.sync_api import expect

from helpers import chunk_meta, new_page, ui_login, unique


def test_diagnosis_stream_renders_versioned_refs_and_commands(server, browser):
    context, page = new_page(browser)
    try:
        ui_login(page, server, "e2e-owner", server.owner_password)
        # 不依赖"当前没有事故单"的欢迎态：分页 spec 可能先 seed 了上百条，
        # 登录后会自动选中第一条。统一走左栏新建对话框的「创建并诊断」。
        page.locator(".rail .newbtn").click()
        expect(page.locator("#incDialog")).to_be_visible()
        page.locator("#ni-title").fill(unique("E2E 排障"))
        page.locator("#ni-query").fill("50012_DB_TIMEOUT 怎么排查")
        page.locator("#niGo").click()
        expect(page.locator("#incDialog")).to_be_hidden()

        diagnosis = page.locator('[data-testid="diagnosis"]')
        expect(diagnosis).to_be_visible(timeout=60000)
        expect(diagnosis.locator('[data-act="copymd"]')).to_be_visible(timeout=90000)

        # 引用与命令是 verdict 的同级卡片；#canvas 才是这次诊断结果的共同作用域。
        refs = page.locator("#canvas [data-ref-item]")
        expect(refs.first).to_be_visible(timeout=30000)
        assert refs.count() >= 1, "本轮没有引用"

        meta = chunk_meta(server.chunks_path)
        for i in range(refs.count()):
            item = refs.nth(i)
            chunk_id = item.locator('[data-testid="ref-id"]').inner_text().strip()
            version = item.locator('[data-testid="ref-version"]').inner_text().strip()
            staleness = item.locator('[data-testid="ref-staleness"]').inner_text().strip()

            assert chunk_id in meta, f"引用 {chunk_id} 不在 chunks.jsonl 里"
            doc_id, digest = meta[chunk_id]
            assert version == f"{doc_id}@{digest}", (
                f"引用 {chunk_id} 的版本身份与语料不一致：页面 {version!r} / 语料 {doc_id}@{digest}")
            assert staleness in {"未过期", "已过期", "未标注"}, staleness

        evidence_text = "\n".join(refs.all_inner_texts())
        assert "undefined" not in evidence_text
        assert "null" not in evidence_text

        commands = page.locator("#canvas .contract .cmd")
        assert commands.count() >= 1, "只读命令清单为空"
        for i in range(commands.count()):
            assert commands.nth(i).locator("pre").inner_text().strip(), "命令正文为空"
    finally:
        context.close()
