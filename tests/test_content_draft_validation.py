from agent_service.app import _coerce_draft


def test_a_well_formed_brief_passes_through():
    raw = {
        "summary": "Spring campaign for the new blend.",
        "goals": "Awareness and coupon-attributed sales.",
        "dos": "Show the product in use.",
        "donts": "No unsupported health claims.",
        "talkingPoints": "Origin story; fair-trade sourcing.",
        "hashtags": ["#ad", "#partner"],
    }
    out = _coerce_draft("brief", raw)
    assert out == raw


def test_extra_keys_are_dropped():
    # Models add fields that were never asked for. They must not reach the UI, which would
    # otherwise carry an unvalidated value into a form.
    out = _coerce_draft("brief", {"summary": "ok", "colorScheme": "dark", "sections": [1, 2]})
    assert out is not None
    assert set(out) == {"summary", "goals", "dos", "donts", "talkingPoints", "hashtags"}


def test_a_number_in_a_text_field_is_stringified():
    # A formatting slip, not a misunderstanding: the value is usable once it is a string.
    out = _coerce_draft("brief", {"summary": 42, "goals": "Drive sales."})
    assert out["summary"] == "42"
    assert out["goals"] == "Drive sales."


def test_a_structural_value_in_a_text_field_is_dropped_not_stringified():
    # Rendering "['a', 'b']" into a form field is worse than leaving it empty -- the user has to
    # recognise it as junk and clear it.
    out = _coerce_draft("brief", {"summary": ["a", "b"], "goals": "Drive sales."})
    assert out["summary"] == ""
    assert out["goals"] == "Drive sales."


def test_a_single_hashtag_string_becomes_a_list():
    # The UI joins hashtags with a space. A bare string would render as its characters.
    out = _coerce_draft("brief", {"summary": "ok", "hashtags": "#ad, #partner"})
    assert out["hashtags"] == ["#ad", "#partner"]


def test_non_string_hashtag_entries_are_stringified():
    out = _coerce_draft("brief", {"summary": "ok", "hashtags": ["#ad", 7, "  ", None]})
    assert out["hashtags"] == ["#ad", "7"]


def test_missing_fields_default_to_empty_rather_than_failing():
    # A partial draft is still worth showing; the user edits before saving either way.
    out = _coerce_draft("brief", {"summary": "Just the summary."})
    assert out["summary"] == "Just the summary."
    assert out["dos"] == ""
    assert out["hashtags"] == []


def test_an_entirely_unusable_response_is_rejected():
    # Falls back to the heuristic draft rather than showing a form of empty strings.
    assert _coerce_draft("brief", {"unrelated": "value"}) is None
    assert _coerce_draft("brief", {"summary": {"nested": "object"}}) is None
    assert _coerce_draft("brief", {}) is None


def test_non_dict_responses_are_rejected():
    assert _coerce_draft("brief", ["a", "list"]) is None
    assert _coerce_draft("brief", "a string") is None
    assert _coerce_draft("brief", None) is None


def test_landing_keeps_its_own_shape():
    out = _coerce_draft(
        "landing",
        {"hero": "{{creator.name}} loves it", "body": "Copy.", "cta": "Shop now", "summary": "x"},
    )
    assert set(out) == {"hero", "body", "cta"}
    # Tokens are ordinary text at this stage; the renderer substitutes them later.
    assert out["hero"] == "{{creator.name}} loves it"


def test_an_unknown_kind_is_rejected():
    assert _coerce_draft("unknown", {"summary": "ok"}) is None
