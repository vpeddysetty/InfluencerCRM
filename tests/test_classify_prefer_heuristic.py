"""
`prefer_heuristic` on /creators/classify (roadmap OP-25).

The public landing-page sign-up form is unauthenticated by design, and the enrichment behind
it is a billed model call. Past a per-page hourly ceiling the BFF asks for the deterministic
classifier instead. What matters here is that the flag SKIPS the model rather than discarding
its answer afterwards -- discarding would still have paid for it, which is the whole thing the
ceiling exists to prevent.
"""

from agent_service.app import CreatorClassifyRequest, creators_classify


def _request(**overrides):
    payload = {
        "handle": "mayawears",
        "platform": "instagram",
        "display_name": "Maya",
        "recent_captions": "my gym workout routine, protein and training reps",
    }
    payload.update(overrides)
    return CreatorClassifyRequest(**payload)


def test_prefer_heuristic_never_calls_the_model(monkeypatch):
    # If the flag were honoured by discarding the model's answer, the money would already be
    # spent. Blowing up on any call is the only way to assert it was never made.
    def explode(_request):
        raise AssertionError("the model was called despite prefer_heuristic")

    monkeypatch.setattr("agent_service.app._llm_classify", explode)

    result = creators_classify(_request(prefer_heuristic=True))["classification"]

    assert result["source"] == "heuristic"


def test_prefer_heuristic_still_returns_a_usable_classification():
    # The lead is never the thing dropped -- it keeps a real niche, from keyword matching.
    result = creators_classify(_request(prefer_heuristic=True))["classification"]

    assert result["niche"] == "fitness"
    assert result["content_themes"]
    assert "summary" in result


def test_the_default_is_unchanged(monkeypatch):
    # Authenticated callers are bounded by their own session, so they keep the model. A default
    # that quietly became heuristic would degrade every classification in the product.
    monkeypatch.setattr(
        "agent_service.app._llm_classify",
        lambda _request: {
            "source": "llm",
            "niche": "fitness",
            "content_themes": ["training"],
            "risk_flags": [],
            "summary": "A fitness creator.",
        },
    )

    result = creators_classify(_request())["classification"]

    assert result["source"] == "llm"


def test_heuristic_is_still_the_fallback_when_the_model_is_unavailable(monkeypatch):
    # Unchanged behaviour, asserted so the new branch cannot be mistaken for the only path
    # that reaches the keyword matcher.
    monkeypatch.setattr("agent_service.app._llm_classify", lambda _request: None)

    result = creators_classify(_request())["classification"]

    assert result["source"] == "heuristic"
