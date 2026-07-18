import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional


def blend_confidence(heuristic_score: float, llm_confidence: Optional[float]) -> float:
    if llm_confidence is None:
        return heuristic_score
    if not isinstance(llm_confidence, (int, float)):
        return heuristic_score
    return round(min(0.99, max(0.0, 0.6 * heuristic_score + 0.4 * float(llm_confidence))), 2)


def validate_llm_payload(payload: Any) -> bool:
    if not isinstance(payload, dict):
        return False
    recommendations = payload.get("recommendations")
    custom_fields = payload.get("custom_fields")
    if not isinstance(recommendations, list) or not isinstance(custom_fields, list):
        return False
    for item in recommendations:
        if not isinstance(item, dict):
            return False
        if not {"spreadsheet_column", "target_entity", "target_attribute", "confidence", "recommendation_type", "notes"}.issubset(item.keys()):
            return False
        if not isinstance(item.get("spreadsheet_column"), str):
            return False
        if not isinstance(item.get("target_entity"), str):
            return False
        if not isinstance(item.get("target_attribute"), str):
            return False
        if not isinstance(item.get("recommendation_type"), str):
            return False
        if not isinstance(item.get("notes"), str):
            return False
        confidence = item.get("confidence")
        if not isinstance(confidence, (int, float)):
            return False
        if not 0.0 <= float(confidence) <= 1.0:
            return False
    for item in custom_fields:
        if not isinstance(item, dict):
            return False
        if not {"spreadsheet_column", "target_entity", "target_attribute", "reason", "recommendation_type", "applies_to", "custom_field_scope"}.issubset(item.keys()):
            return False
        if not isinstance(item.get("spreadsheet_column"), str):
            return False
        if not isinstance(item.get("target_entity"), str):
            return False
        if not isinstance(item.get("target_attribute"), str):
            return False
        if not isinstance(item.get("reason"), str):
            return False
        if not isinstance(item.get("recommendation_type"), str):
            return False
        if not isinstance(item.get("applies_to"), list):
            return False
        if not isinstance(item.get("custom_field_scope"), str):
            return False
    return True

try:
    from openai import OpenAI
except ImportError:  # pragma: no cover - optional dependency guard
    OpenAI = None


class OpenAIAdvisor:
    def __init__(self) -> None:
        self.client: Optional[Any] = None
        self.model = os.getenv("OPENAI_MODEL", "gpt-4.1-mini")
        api_key = os.getenv("OPENAI_API_KEY")
        if api_key and OpenAI is not None:
            self.client = OpenAI(api_key=api_key)
        self.prompt_path = Path(__file__).resolve().parent.parent / "prompt.md"
        self.system_prompt = self._load_prompt()

    def _load_prompt(self) -> str:
        try:
            return self.prompt_path.read_text(encoding="utf-8").strip()
        except FileNotFoundError:
            return (
                "You are an assistant that maps spreadsheet metadata to an influencer CRM schema. "
                "Map each spreadsheet column to the closest CRM entity attribute. "
                "If no strong match exists, label the column as a custom field tied to campaign. "
                "Return JSON with two arrays: recommendations and custom_fields."
            )

    def is_available(self) -> bool:
        return self.client is not None

    def recommend(
        self,
        spreadsheet_columns: List[str],
        metadata_catalog: Dict[str, List[str]],
        retrieved_examples: Optional[List[Dict[str, Any]]] = None,
    ) -> Optional[Dict[str, Any]]:
        if not self.is_available():
            return None

        prompt_payload = {
            "spreadsheet_columns": spreadsheet_columns,
            "metadata_catalog": metadata_catalog,
            "retrieved_examples": retrieved_examples or [],
            "instructions": [
                "Map each spreadsheet column to the closest CRM entity attribute.",
                "If no strong match exists, label the column as a custom field tied to campaign.",
                "Return JSON with two arrays: recommendations and custom_fields.",
                "Each recommendation should include a numeric confidence value between 0 and 1.",
                "Use retrieved_examples as high-signal prior examples when they are semantically similar.",
            ],
        }

        try:
            response = self.client.responses.create(
                model=self.model,
                input=[
                    {
                        "role": "system",
                        "content": self.system_prompt,
                    },
                    {
                        "role": "user",
                        "content": json.dumps(prompt_payload, indent=2),
                    },
                ],
                temperature=0.1,
            )
            content = getattr(response, "output_text", None)
            if not content:
                if hasattr(response, "choices") and response.choices:
                    content = response.choices[0].message.content
            if not content:
                return None
            parsed = json.loads(content)
            if not validate_llm_payload(parsed):
                return None
            recommendations = parsed.get("recommendations", [])
            if isinstance(recommendations, list):
                for item in recommendations:
                    if isinstance(item, dict) and "confidence" in item:
                        item["confidence"] = float(item["confidence"])
            return parsed
        except Exception:
            return None
