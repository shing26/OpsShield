"""告警投递 seam 的行为回归锁（ADR-0024 / C2）。"""
from __future__ import annotations

import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
sys.path.insert(0, str(REPO / "offline"))

import alert_delivery as ad  # noqa: E402


class FakeApi:
    def __init__(self, response=None, state=None):
        self.response = response or {}
        self.state = state or {}
        self.login_calls = 0
        self.stream_calls = []
        self.state_calls = 0

    def login(self, username):
        self.login_calls += 1
        return "token"

    def stream_chat(self, body, token, timeout=120, collect_deltas=True, trace_id=None):
        self.stream_calls.append({
            "body": body,
            "token": token,
            "timeout": timeout,
            "collect_deltas": collect_deltas,
            "trace_id": trace_id,
        })
        return self.response

    def get_json(self, path, token, timeout=10):
        self.state_calls += 1
        return self.state


def _bridge_policy(**kw) -> ad.DeliveryPolicy:
    values = {
        "gates": ad.BRIDGE_GATES,
        "max_per_hour": 20,
        "quota_reserve": 50,
        "hour_accounting": ad.HourAccounting.ATTEMPT,
        "login_before_send": True,
        "ledger_transport_failure": True,
        "response_code_key": "error_code_resp",
    }
    values.update(kw)
    return ad.DeliveryPolicy(**values)


def _producer_policy(**kw) -> ad.DeliveryPolicy:
    values = {
        "gates": ad.PRODUCER_GATES,
        "max_per_hour": 20,
        "quota_reserve": 50,
        "hour_accounting": ad.HourAccounting.RESPONSE,
        "login_before_send": False,
        "ledger_transport_failure": False,
        "response_code_key": "error_code",
    }
    values.update(kw)
    return ad.DeliveryPolicy(**values)


def _delivery(tmp_path: Path, api: FakeApi) -> ad.AlertDelivery:
    session = ad.AlertSession("sre-watcher", api=api)
    return ad.AlertDelivery(session, ledger_path=tmp_path / "alert.jsonl")


def test_delivered_projects_response_and_appends_one_ledger_row(tmp_path):
    api = FakeApi(response={
        "status": 200,
        "meta": {
            "fingerprint": "fp-1",
            "cache_hit": "L1",
            "deduplicated": True,
            "degradation_level": 0,
        },
        "done": {"refs": [
            {"chunkId": "rb-201::排查步骤 > 第一步"},
            {"chunkId": "rb-201::止损操作"},
            {"chunkId": "pm-101::现象"},
        ]},
        "error": None,
        "ttft_s": 0.1,
        "total_s": 0.5,
    })
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert", "service": "opspilot", "env": "local"},
        trace_id="am-12345678",
        ledger={"alert_id": "a-1"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy())

    assert isinstance(outcome, ad.Delivered)
    assert outcome.record["http"] == 200
    assert outcome.record["fp"] == "fp-1"
    assert outcome.record["refs"] == [
        "rb-201::排查步骤 > 第一步",
        "rb-201::止损操作",
        "pm-101::现象",
    ]
    assert outcome.record["ref_docs"] == ["pm-101", "rb-201"]
    assert outcome.record["sent_ok"] is True
    assert api.login_calls == 1
    assert api.stream_calls[0]["token"] == "token"
    assert api.stream_calls[0]["trace_id"] == "am-12345678"
    rows = [
        json.loads(line)
        for line in (tmp_path / "alert.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert len(rows) == 1
    assert rows[0]["alert_id"] == "a-1"
    assert rows[0]["fp"] == "fp-1"


def test_hour_budget_skips_before_login_and_records_one_row(tmp_path):
    api = FakeApi()
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"rule": "dep_down", "query": "q"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy(max_per_hour=0))

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "hour_budget"
    assert outcome.record["emit"] is False
    assert api.login_calls == 0
    assert api.stream_calls == []
    rows = [
        json.loads(line)
        for line in (tmp_path / "alert.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert len(rows) == 1
    assert rows[0]["skip"] == "hour_budget"
    assert rows[0]["rule"] == "dep_down"


def test_bridge_dry_run_precedes_hour_budget(tmp_path):
    api = FakeApi()
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"query": "q"},
    )

    outcome = delivery.deliver(
        attempt,
        _bridge_policy(max_per_hour=0, dry_run=True),
    )

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "dry_run"
    assert api.login_calls == 0
    assert api.stream_calls == []


def test_bridge_reads_live_quota_and_skips_below_reserve(tmp_path):
    api = FakeApi(state={"runtime": {"quota": {"limit": 100, "used": 60}}})
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"query": "q"},
    )

    outcome = delivery.deliver(
        attempt,
        _bridge_policy(quota_reserve=50),
    )

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "quota_reserve"
    assert api.login_calls == 1
    assert api.state_calls == 1
    assert api.stream_calls == []


def test_producer_snapshot_quota_precedes_dry_run_without_network(tmp_path):
    api = FakeApi()
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"rule": "dep_down", "query": "q"},
    )

    outcome = delivery.deliver(
        attempt,
        _producer_policy(
            quota=ad.QuotaSnapshot(limit=100, used=60),
            dry_run=True,
        ),
    )

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "quota_reserve"
    assert api.login_calls == 0
    assert api.state_calls == 0
    assert api.stream_calls == []


def test_producer_cooldown_precedes_dry_run(tmp_path):
    api = FakeApi()
    delivery = _delivery(tmp_path, api)
    delivery.clock = lambda: 1000.0
    delivery.last_emit["dep_down:qdrant"] = 900.0
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"rule": "dep_down", "query": "q"},
    )

    outcome = delivery.deliver(
        attempt,
        _producer_policy(
            quota=ad.QuotaSnapshot(limit=100, used=0),
            cooldown_key="dep_down:qdrant",
            cooldown_seconds=300,
            dry_run=True,
        ),
    )

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "cooldown"
    assert api.stream_calls == []


def test_bridge_transport_failure_is_recorded_and_clears_token(tmp_path):
    class BrokenApi(FakeApi):
        def stream_chat(self, body, token, timeout=120, collect_deltas=True, trace_id=None):
            self.stream_calls.append({"body": body, "token": token})
            raise ConnectionRefusedError("refused")

    api = BrokenApi()
    delivery = _delivery(tmp_path, api)
    delivery.session.token = "stale"
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"alert_id": "a-1", "query": "q"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy())

    assert isinstance(outcome, ad.TransportFailed)
    assert outcome.record["emit"] is True
    assert outcome.record["sent_ok"] is False
    assert outcome.record["refs"] == []
    assert "ConnectionRefusedError" in outcome.record["error"]
    assert delivery.session.token == ""
    rows = [
        json.loads(line)
        for line in (tmp_path / "alert.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert len(rows) == 1
    assert rows[0]["sent_ok"] is False


def test_bridge_login_failure_is_a_skipped_outcome(tmp_path):
    class LoginBrokenApi(FakeApi):
        def login(self, username):
            self.login_calls += 1
            error = RuntimeError("locked")
            error.code = 401
            raise error

    api = LoginBrokenApi()
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"alert_id": "a-1", "query": "q"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy())

    assert isinstance(outcome, ad.Skipped)
    assert outcome.reason == "login_failed"
    assert outcome.record["emit"] is False
    assert outcome.record["note"] == "HTTP 401"
    assert api.stream_calls == []
    rows = [
        json.loads(line)
        for line in (tmp_path / "alert.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert len(rows) == 1
    assert rows[0]["skip"] == "login_failed"


def test_bridge_non_auth_login_failure_is_a_transport_outcome(tmp_path):
    class LoginUnavailableApi(FakeApi):
        def login(self, username):
            self.login_calls += 1
            raise ConnectionRefusedError("login endpoint down")

    api = LoginUnavailableApi()
    delivery = _delivery(tmp_path, api)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"alert_id": "a-1", "query": "q"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy())

    assert isinstance(outcome, ad.TransportFailed)
    assert outcome.record["emit"] is True
    assert outcome.record["sent_ok"] is False
    assert "ConnectionRefusedError" in outcome.record["error"]
    assert api.login_calls == 2
    assert api.stream_calls == []
    rows = [
        json.loads(line)
        for line in (tmp_path / "alert.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert len(rows) == 1
    assert rows[0]["sent_ok"] is False


def test_producer_response_starts_cooldown(tmp_path):
    api = FakeApi(response={"status": 200, "meta": {}, "done": {}})
    delivery = _delivery(tmp_path, api)
    delivery.clock = lambda: 1000.0
    delivery.session.token = "token"
    policy = _producer_policy(
        quota=ad.QuotaSnapshot(limit=100, used=0),
        cooldown_key="dep_down:qdrant",
        cooldown_seconds=300,
    )
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"rule": "dep_down", "query": "q"},
    )

    first = delivery.deliver(attempt, policy)
    second = delivery.deliver(attempt, policy)

    assert isinstance(first, ad.Delivered)
    assert isinstance(second, ad.Skipped)
    assert second.reason == "cooldown"
    assert len(api.stream_calls) == 1


def test_bridge_counts_hour_budget_at_attempt_start(tmp_path):
    api = FakeApi(response={"status": 200, "meta": {}, "done": {}})
    delivery = _delivery(tmp_path, api)
    delivery.clock = lambda: 1000.0
    delivery.session.token = "token"
    policy = _bridge_policy(max_per_hour=1)
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"alert_id": "a-1", "query": "q"},
    )

    first = delivery.deliver(attempt, policy)
    second = delivery.deliver(attempt, policy)

    assert isinstance(first, ad.Delivered)
    assert isinstance(second, ad.Skipped)
    assert second.reason == "hour_budget"
    assert len(api.stream_calls) == 1


def test_rejected_401_clears_token_without_retrying(tmp_path):
    api = FakeApi(response={
        "status": 401,
        "code": "HTTP_401",
        "meta": {},
        "done": {},
        "error": None,
    })
    delivery = _delivery(tmp_path, api)
    delivery.session.token = "stale"
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"alert_id": "a-1", "query": "q"},
    )

    outcome = delivery.deliver(attempt, _bridge_policy())

    assert isinstance(outcome, ad.Rejected)
    assert outcome.status == 401
    assert outcome.record["sent_ok"] is False
    assert delivery.session.token == ""
    assert api.login_calls == 0
    assert len(api.stream_calls) == 1


def test_producer_transport_failure_does_not_append_candidate_row(tmp_path):
    class BrokenApi(FakeApi):
        def stream_chat(self, body, token, timeout=120, collect_deltas=True, trace_id=None):
            self.stream_calls.append({"body": body, "token": token})
            raise TimeoutError("slow stream")

    api = BrokenApi()
    delivery = _delivery(tmp_path, api)
    delivery.session.token = "token"
    attempt = ad.DeliveryAttempt(
        body={"query": "q", "source": "alert"},
        ledger={"rule": "dep_down", "query": "q"},
    )

    outcome = delivery.deliver(
        attempt,
        _producer_policy(quota=ad.QuotaSnapshot(limit=100, used=0)),
    )

    assert isinstance(outcome, ad.TransportFailed)
    assert outcome.record["emit"] is True
    assert delivery.session.token == ""
    assert not (tmp_path / "alert.jsonl").exists()
    assert delivery.emits == []


def test_observe_state_relogins_once_on_401(tmp_path):
    class ExpiringStateApi(FakeApi):
        def __init__(self):
            super().__init__()
            self.calls = 0

        def get_json(self, path, token, timeout=10):
            self.calls += 1
            if self.calls == 1:
                error = RuntimeError("expired")
                error.code = 401
                raise error
            return {"metrics": {}, "runtime": {}}

        def login(self, username):
            self.login_calls += 1
            return "fresh-token"

    api = ExpiringStateApi()
    delivery = _delivery(tmp_path, api)
    delivery.session.token = "stale"

    state = delivery.observe_state()

    assert state == {"metrics": {}, "runtime": {}}
    assert api.calls == 2
    assert api.login_calls == 1
    assert delivery.session.token == "fresh-token"


def test_observe_state_turns_403_into_fatal_permission_error(tmp_path):
    class ForbiddenStateApi(FakeApi):
        def get_json(self, path, token, timeout=10):
            self.state_calls += 1
            error = RuntimeError("forbidden")
            error.code = 403
            raise error

    api = ForbiddenStateApi()
    delivery = _delivery(tmp_path, api)
    delivery.session.token = "token"

    try:
        delivery.observe_state()
    except PermissionError as exc:
        assert "sre-watcher" in str(exc)
    else:
        raise AssertionError("403 must become a fatal PermissionError")
    assert api.state_calls == 1
    assert api.login_calls == 0
