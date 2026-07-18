import re
from dataclasses import dataclass, asdict
from typing import List, Dict, Any, Union


@dataclass
class MappingRecommendation:
    spreadsheet_column: str
    target_entity: str
    target_attribute: str
    confidence: float
    recommendation_type: str
    notes: str


class MetadataMapper:
    """Heuristic mapper between spreadsheet columns and CRM metadata."""

    def __init__(self) -> None:
        self.metadata_catalog = {
            "campaign": [
                "name",
                "goal",
                "product",
                "budget",
                "start_date",
                "end_date",
                "status",
                "brand_owner",
            ],
            "creator": [
                "handle",
                "name",
                "email",
                "platform",
                "follower_count",
                "engagement_rate",
                "tags",
                "notes",
                "content_review_notes",
            ],
            "campaign_creator": [
                "stage",
                "discount_code",
                "link",
                "agreed_fee",
                "post_url",
                "content_review_status",
                "content_review_notes",
                "content_reviewed_by",
            ],
            "brand_owner": ["brand_name", "email", "role", "plan"],
        }
        self.alias_map = {
            "campaignname": "name",
            "campaign": "name",
            "campaigngoal": "goal",
            "productname": "product",
            "budgetamount": "budget",
            "start": "start_date",
            "end": "end_date",
            "status": "status",
            "brandowner": "campaign_owner",
            "ighandle": "handle",
            "instagramhandle": "handle",
            "username": "handle",
            "creatorhandle": "handle",
            "fullname": "name",
            "creatorname": "name",
            "emailaddress": "email",
            "email": "email",
            "platform": "platform",
            "followers": "follower_count",
            "followercount": "follower_count",
            "engagement": "engagement_rate",
            "engagementpercent": "engagement_rate",
            "engagementrate": "engagement_rate",
            "notes": "notes",
            "reviewnotes": "content_review_notes",
            "reviewnote": "content_review_notes",
            "reviewcomments": "content_review_notes",
            "reviewstatus": "content_review_status",
            "reviewedby": "content_reviewed_by",
            "reviewer": "content_reviewed_by",
            "posturl": "post_url",
            "contenturl": "post_url",
            "publishedurl": "post_url",
            "discountcode": "discount_code",
            "dealcode": "discount_code",
            "fee": "agreed_fee",
            "agreedfee": "agreed_fee",
            "pipeline": "stage",
            "stage": "stage",
            "link": "link",
        }

    def _normalize(self, value: str) -> str:
        return re.sub(r"[^a-z0-9]+", "", value.lower())

    def _tokenize(self, value: str) -> List[str]:
        tokens = re.findall(r"[a-z0-9]+", value.lower())
        return [token for token in tokens if token]

    def _score(self, column_name: str, target_attr: str, sheet_name: str = "") -> float:
        normalized_column = self._normalize(column_name)
        normalized_attr = self._normalize(target_attr)
        sheet_tokens = set(self._tokenize(sheet_name)) if sheet_name else set()
        normalized_sheet = self._normalize(sheet_name)
        if normalized_column == normalized_attr:
            return 1.0

        alias = self.alias_map.get(normalized_column)
        if alias == target_attr:
            return 0.97

        if normalized_attr in normalized_column and len(normalized_column) - len(normalized_attr) <= 4:
            return 0.9

        column_tokens = set(self._tokenize(column_name))
        attr_tokens = set(self._tokenize(target_attr))
        overlap = len(column_tokens & attr_tokens)
        if overlap:
            base_score = 0.45 + 0.1 * overlap
            if "review" in column_tokens and "review" in attr_tokens:
                base_score += 0.12
            if "post" in column_tokens and "post" in attr_tokens:
                base_score += 0.05
            if "url" in column_tokens and "url" in attr_tokens:
                base_score += 0.05
            if "note" in column_tokens and "review" in attr_tokens:
                base_score += 0.08
            if "review" in column_tokens and target_attr == "content_review_notes":
                base_score += 0.08
            if sheet_name:
                if "review" in normalized_sheet and target_attr == "content_review_notes":
                    base_score += 0.05
                if "campaign" in normalized_sheet and target_attr == "post_url":
                    base_score += 0.05
                if "creator" in normalized_sheet and target_attr in {"handle", "engagement_rate", "follower_count"}:
                    base_score += 0.03
            return min(0.98, base_score)
        return 0.0

    def _pick_entity(self, target_attr: str) -> str:
        attr_to_entity = {
            "name": "campaign",
            "goal": "campaign",
            "product": "campaign",
            "budget": "campaign",
            "start_date": "campaign",
            "end_date": "campaign",
            "status": "campaign",
            "campaign_owner": "campaign",
            "handle": "creator",
            "email": "creator",
            "platform": "creator",
            "follower_count": "creator",
            "engagement_rate": "creator",
            "tags": "creator",
            "notes": "creator",
            "content_review_notes": "creator",
            "stage": "campaign_creator",
            "discount_code": "campaign_creator",
            "link": "campaign_creator",
            "agreed_fee": "campaign_creator",
            "post_url": "campaign_creator",
            "content_review_status": "campaign_creator",
            "content_review_notes": "campaign_creator",
            "content_reviewed_by": "campaign_creator",
        }
        return attr_to_entity.get(target_attr, "campaign")

    def _infer_custom_entity(self, column_name: str) -> str:
        """Infer likely entity for unmapped columns using keyword and token overlap hints."""
        normalized = self._normalize(column_name)
        tokens = set(self._tokenize(column_name))

        keyword_hints = {
            "creator": ["creator", "influencer", "handle", "followers", "engagement", "audience", "niche"],
            "campaign": ["campaign", "brief", "budget", "goal", "kpi", "product", "objective"],
            "campaign_creator": ["stage", "deliverable", "contract", "payment", "post", "review", "discount", "fee"],
            "brand_owner": ["owner", "brand", "plan", "role"],
        }

        for entity, hints in keyword_hints.items():
            if any(hint in normalized for hint in hints):
                return entity

        best_entity = "campaign"
        best_overlap = 0
        for entity, attrs in self.metadata_catalog.items():
            attr_tokens = set()
            for attr in attrs:
                attr_tokens.update(self._tokenize(attr))
            overlap = len(tokens & attr_tokens)
            if overlap > best_overlap:
                best_overlap = overlap
                best_entity = entity
        return best_entity

    def recommend(self, spreadsheet_columns: List[Union[str, Dict[str, Any]]]) -> Dict[str, Any]:
        recommendations: List[Dict[str, Any]] = []
        custom_fields: List[Dict[str, Any]] = []
        for item in spreadsheet_columns:
            if isinstance(item, dict):
                column_name = item.get("column", "")
                sheet_name = item.get("sheet", "")
            else:
                column_name = item
                sheet_name = ""
            best_match = None
            best_score = 0.0
            best_entity = None
            best_attr = None
            for entity, attrs in self.metadata_catalog.items():
                for attr in attrs:
                    score = self._score(column_name, attr, sheet_name)
                    if score > best_score:
                        best_match = attr
                        best_score = score
                        best_entity = entity
                        best_attr = attr
            if best_score >= 0.7:
                recommendations.append(
                    asdict(
                        MappingRecommendation(
                            spreadsheet_column=column_name,
                            target_entity=best_entity,
                            target_attribute=best_attr,
                            confidence=round(best_score, 2),
                            recommendation_type="mapped",
                            notes="Matched using normalized similarity, alias heuristics, and sheet context against the CRM metadata catalog.",
                        )
                    )
                )
            else:
                keyword_entity = self._infer_custom_entity(column_name)
                if keyword_entity != "campaign":
                    inferred_entity = keyword_entity
                else:
                    inferred_entity = best_entity if (best_entity and best_score >= 0.5) else keyword_entity
                custom_fields.append(
                    {
                        "spreadsheet_column": column_name,
                        "target_entity": inferred_entity,
                        "target_attribute": column_name,
                        "reason": "No strong match found in the known CRM target attributes.",
                        "recommendation_type": "custom",
                        "applies_to": [inferred_entity],
                        "custom_field_scope": inferred_entity,
                    }
                )
        return {
            "recommendations": recommendations,
            "custom_fields": custom_fields,
            "metadata_catalog": self.metadata_catalog,
        }
