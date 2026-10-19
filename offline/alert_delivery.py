"""Shared alert delivery seam for Bridge and Producer (ADR-0024)."""
from __future__ import annotations

import json
import sys
import threading
import time
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Any

import localapi


class LoginFailed(RuntimeError):
    """Credential or login endpoint failure that needs operator attention."""


class Gate(str, Enum):
    EMPTY_QUERY = "empty_query"
    DRY_RUN = "dry_run"
    HOUR_BUDGET = "hour_budget"
    LIVE_QUOTA = "live_quota"
    SNAPSHOT_QUOTA = "snapshot_quota"
    COOLDOWN = "cooldown"


BRIDGE_GATES = (
    Gate.EMPTY_QUERY,
    Gate.DRY_RUN,
    Gate.HOUR_BUDGET,
    Gate.LIVE_QUOTA,
)

PRODUCER_GATES = (
    Gate.HOUR_BUDGET,
    Gate.SNAPSHOT_QUOTA,
    Gate.COOLDOWN,
    Gate.DRY_RUN,
)


class HourAccounting(str, Enum):
    ATTEMPT = "attempt"
    RESPONSE = "response"


@dataclass(frozen=True)
class QuotaSnapshot:
    limit: int
    used: int

    @property
    def remaining(self) -> int:
        return self.limit - self.used


@dataclass(frozen=True)
class DeliveryAttempt:
    body: dict[str, Any]
    ledger: dict[str, Any] = field(default_factory=dict)
    trace_id: str | None = None
    collect_deltas: bool = False


@dataclass(frozen=True)
class DeliveryPolicy:
    gates: tuple[Gate, ...]
    max_per_hour: int
    quota_reserve: int
    hour_accounting: HourAccounting
    login_before_send: bool
    ledger_transport_failure: bool
    response_code_key: str
    dry_run: bool = False
    quota: QuotaSnapshot | None = None
    cooldown_key: str | None = None
    cooldown_seconds: float = 0


@dataclass(frozen=True)
class DeliveryOutcome:
    record: dict[str, Any]


@dataclass(frozen=True)
class Skipped(DeliveryOutcome):
    reason: str = ""


@dataclass(frozen=True)
class Delivered(DeliveryOutcome):
    status: int = 200


@dataclass(frozen=True)
class Rejected(DeliveryOutcome):
    status: int = 0


@dataclass(frozen=True)
class TransportFailed(DeliveryOutcome):
    error: str = ""
    exception: BaseException | None = None


class AlertSession:
    """Authenticated gateway session shared by state reads and delivery."""

    def __init__(self, user: str, *, api=localapi):
        self.user = user
        self.api = api
        self.token = ""

    def login(self) -> None:
        try:
            self.token = self.api.login(self.user)
        except Exception as exc:  # noqa: BLE001 - normalize only actionable auth failures
            code = getattr(exc, "code", None)
            if code in (401, 403):
                raise LoginFailed(f"HTTP {code}") from exc
            raise

    def stream_chat(self, attempt: DeliveryAttempt) -> dict[str, Any]:
        kwargs = {"collect_deltas": attempt.collect_deltas}
        if attempt.trace_id:
            kwargs["trace_id"] = attempt.trace_id
        return self.api.stream_chat(attempt.body, self.token, **kwargs)

    def get_json(self, path: str) -> dict[str, Any]:
        return self.api.get_json(path, self.token)


class AlertLedger:
    """Append-only JSONL evidence writer for alert sources."""

    def __init__(self, path: Path, user: str):
        self.path = path
        self.user = user

    def append(self, record: dict[str, Any]) -> dict[str, Any]:
        row = {"ts": int(time.time() * 1000), "user": self.user, **record}
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            with open(self.path, "a", encoding="utf-8") as fh:
                fh.write(json.dumps(row, ensure_ascii=False) + "\n")
        except OSError as exc:
            print(f"[warn] 台账写入失败（不阻断告警）: {exc}", file=sys.stderr)
        return row


class AlertDelivery:
    """One deep delivery operation: gates, auth, request, projection, ledger."""

    def __init__(
        self,
        session: AlertSession,
        *,
        ledger_path: Path,
        clock=time.time,
    ):
        self.session = session
        self.ledger = AlertLedger(ledger_path, session.user)
        self.clock = clock
        self.emits: list[float] = []
        self.last_emit: dict[str, float] = {}
        self._gate_lock = threading.Lock()

    def deliver(self, attempt: DeliveryAttempt, policy: DeliveryPolicy) -> DeliveryOutcome:
        with self._gate_lock:
            skip_reason = self._gate_reason(attempt, policy)
            if skip_reason:
                return self._skip(attempt, skip_reason)

            if policy.hour_accounting is HourAccounting.ATTEMPT:
                self.emits.append(self.clock())

        if policy.login_before_send and not self.session.token:
            try:
                self.session.login()
            except LoginFailed as exc:
                return self._skip(attempt, "login_failed", note=str(exc))
            except Exception as exc:  # noqa: BLE001 - login transport is a delivery failure
                return self._transport_failed(attempt, policy, exc)

        try:
            response = self.session.stream_chat(attempt)
        except Exception as exc:  # noqa: BLE001 - transport failures are delivery outcomes
            return self._transport_failed(attempt, policy, exc)
        status = int(response.get("status") or 0)
        if status == 401:
            self.session.token = ""

        meta = response.get("meta") or {}
        done = response.get("done") or {}
        refs = [x.get("chunkId") or "" for x in (done.get("refs") or [])]
        record = {
            **attempt.ledger,
            "emit": True,
            "http": status,
            "sent_ok": 200 <= status < 300,
            policy.response_code_key: response.get("code"),
            "fp": meta.get("fingerprint"),
            "cache_hit": meta.get("cache_hit"),
            "deduplicated": meta.get("deduplicated"),
            "degradation_level": meta.get("degradation_level"),
            "refs": refs,
            "ref_docs": sorted({c.split("::")[0] for c in refs if c}),
            "ttft_s": response.get("ttft_s"),
            "total_s": response.get("total_s"),
            "server_error": response.get("error"),
        }
        outcome: DeliveryOutcome
        if 200 <= status < 300:
            outcome = Delivered(record=record, status=status)
        else:
            outcome = Rejected(record=record, status=status)
        if policy.hour_accounting is HourAccounting.RESPONSE:
            self.emits.append(self.clock())
        if policy.cooldown_key:
            self.last_emit[policy.cooldown_key] = self.clock()
        self.ledger.append(record)
        return outcome

    def _transport_failed(
        self,
        attempt: DeliveryAttempt,
        policy: DeliveryPolicy,
        exc: BaseException,
    ) -> TransportFailed:
        self.session.token = ""
        error = f"{type(exc).__name__}: {exc}"
        record = {
            **attempt.ledger,
            "emit": True,
            "sent_ok": False,
            "error": error,
            "refs": [],
            "ref_docs": [],
        }
        if policy.ledger_transport_failure:
            self.ledger.append(record)
        return TransportFailed(record=record, error=error, exception=exc)

    def observe_state(self) -> dict[str, Any]:
        """Read Producer's truth surface through the shared authenticated session."""
        try:
            return self.session.get_json("/api/v1/admin/state")
        except Exception as exc:  # noqa: BLE001 - classify the two auth statuses
            code = getattr(exc, "code", None)
            if code == 401:
                self.session.login()
                return self.session.get_json("/api/v1/admin/state")
            if code == 403:
                raise PermissionError(
                    f"主体 {self.session.user} 无权读 /admin/state"
                    "（需 role=platform 且 auth_level>=3）"
                ) from exc
            raise

    def _gate_reason(self, attempt: DeliveryAttempt, policy: DeliveryPolicy) -> str | None:
        for gate in policy.gates:
            if gate is Gate.EMPTY_QUERY and not attempt.body.get("query"):
                return "empty_query"
            if gate is Gate.DRY_RUN and policy.dry_run:
                return "dry_run"
            if gate is Gate.HOUR_BUDGET and self._hour_budget_exhausted(policy):
                return "hour_budget"
            if gate is Gate.LIVE_QUOTA:
                remaining = self._live_quota_remaining()
                if remaining is not None and remaining < policy.quota_reserve:
                    return "quota_reserve"
            if gate is Gate.SNAPSHOT_QUOTA and policy.quota is not None:
                if (
                    policy.quota.limit > 0
                    and policy.quota.remaining < policy.quota_reserve
                ):
                    return "quota_reserve"
            if gate is Gate.COOLDOWN and policy.cooldown_key:
                last = self.last_emit.get(policy.cooldown_key, 0.0)
                if self.clock() - last < policy.cooldown_seconds:
                    return "cooldown"
        return None

    def _live_quota_remaining(self) -> int | None:
        try:
            if not self.session.token:
                self.session.login()
            state = self.session.get_json("/api/v1/admin/state")
        except Exception:  # noqa: BLE001 - quota is a gate, not a health prerequisite
            return None
        quota = (state.get("runtime") or {}).get("quota") or state.get("metrics") or {}
        try:
            limit, used = int(quota.get("limit") or 0), int(quota.get("used") or 0)
        except (TypeError, ValueError):
            return None
        return None if limit <= 0 else limit - used

    def _hour_budget_exhausted(self, policy: DeliveryPolicy) -> bool:
        now = self.clock()
        self.emits = [t for t in self.emits if now - t < 3600]
        return len(self.emits) >= policy.max_per_hour

    def _skip(
        self,
        attempt: DeliveryAttempt,
        reason: str,
        *,
        note: str | None = None,
    ) -> Skipped:
        record = {**attempt.ledger, "emit": False, "skip": reason}
        if note is not None:
            record["note"] = note
        self.ledger.append(record)
        return Skipped(record=record, reason=reason)
