"""
/health reports whether the model is reachable (roadmap OP-27).

Every LLM path in this service falls back to a deterministic matcher and returns a result, so a
missing key produces no error, no 500 and no log line at request time. Creator vetting ran on
substring matching in production for three weeks and was found only by measuring `source` on
stored rows. These tests pin the signal that makes that state observable instead.
"""

from agent_service.app import health


class _Advisor:
    def __init__(self, available):
        self._available = available

    def is_available(self):
        return self._available


def test_reports_unavailable_when_there_is_no_client(monkeypatch):
    monkeypatch.setattr("agent_service.app.advisor", _Advisor(False))

    assert health()["llm"] == "unavailable"


def test_reports_available_when_the_client_exists(monkeypatch):
    monkeypatch.setattr("agent_service.app.advisor", _Advisor(True))

    assert health()["llm"] == "available"


def test_status_stays_ok_even_with_no_model(monkeypatch):
    # The container healthcheck and the ASG read `status`. Degrading it over a missing key would
    # take the whole platform down for a feature explicitly designed to survive without one --
    # which would be a far worse outage than the degraded classification it reports.
    monkeypatch.setattr("agent_service.app.advisor", _Advisor(False))

    assert health()["status"] == "ok"
