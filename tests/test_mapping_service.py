from agent_service.mapping_service import MetadataMapper


def test_recommend_known_columns():
    mapper = MetadataMapper()
    result = mapper.recommend(["Campaign Name", "Follower Count", "Discount Code", "Custom Note"])
    recommendations = result["recommendations"]
    custom_fields = result["custom_fields"]
    assert any(item["spreadsheet_column"] == "Campaign Name" for item in recommendations)
    assert any(item["spreadsheet_column"] == "Follower Count" for item in recommendations)
    assert any(item["spreadsheet_column"] == "Custom Note" for item in custom_fields)


def test_recommend_aliases_with_high_confidence():
    mapper = MetadataMapper()
    result = mapper.recommend(["IG Handle", "Engagement %", "Review Notes", "Post URL"])
    recommendations = result["recommendations"]
    assert any(
        item["spreadsheet_column"] == "IG Handle"
        and item["target_attribute"] == "handle"
        and item["confidence"] >= 0.8
        for item in recommendations
    )
    assert any(
        item["spreadsheet_column"] == "Engagement %"
        and item["target_attribute"] == "engagement_rate"
        and item["confidence"] >= 0.8
        for item in recommendations
    )
    assert any(
        item["spreadsheet_column"] == "Review Notes"
        and item["target_attribute"] == "content_review_notes"
        and item["confidence"] >= 0.8
        for item in recommendations
    )
    assert any(
        item["spreadsheet_column"] == "Post URL"
        and item["target_attribute"] == "post_url"
        and item["confidence"] >= 0.8
        for item in recommendations
    )


def test_recommend_uses_sheet_context_for_higher_confidence():
    mapper = MetadataMapper()
    result = mapper.recommend([
        {"column": "Review Notes", "sheet": "Review Tracker"},
        {"column": "Post URL", "sheet": "Campaign Creator Assignments"},
    ])
    recommendations = {item["spreadsheet_column"]: item for item in result["recommendations"]}
    assert recommendations["Review Notes"]["target_attribute"] == "content_review_notes"
    assert recommendations["Review Notes"]["confidence"] >= 0.9
    assert recommendations["Post URL"]["target_attribute"] == "post_url"
    assert recommendations["Post URL"]["confidence"] >= 0.95


def test_custom_fields_are_entity_scoped_for_unmapped_columns():
    mapper = MetadataMapper()
    result = mapper.recommend(["Influencer Persona", "Contract Clause Notes"])
    custom_fields = {item["spreadsheet_column"]: item for item in result["custom_fields"]}

    assert custom_fields["Influencer Persona"]["target_entity"] == "creator"
    assert custom_fields["Influencer Persona"]["custom_field_scope"] == "creator"
    assert custom_fields["Influencer Persona"]["applies_to"] == ["creator"]

    assert custom_fields["Contract Clause Notes"]["target_entity"] == "campaign_creator"
    assert custom_fields["Contract Clause Notes"]["custom_field_scope"] == "campaign_creator"
    assert custom_fields["Contract Clause Notes"]["applies_to"] == ["campaign_creator"]
