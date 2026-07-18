import json
import os
from typing import Any, Dict, List, Optional

try:
    import psycopg
except ImportError:  # pragma: no cover - optional dependency guard
    psycopg = None

try:
    from openai import OpenAI
except ImportError:  # pragma: no cover - optional dependency guard
    OpenAI = None


class MappingExampleRetriever:
    """Retrieves similar historical mapping examples from pgvector."""

    def __init__(self) -> None:
        self.database_url = os.getenv("DATABASE_URL")
        self.embedding_model = os.getenv("OPENAI_EMBEDDING_MODEL", "text-embedding-3-small")
        self.top_k = int(os.getenv("RETRIEVAL_TOP_K", "3"))
        self.client: Optional[Any] = None
        api_key = os.getenv("OPENAI_API_KEY")
        if api_key and OpenAI is not None:
            self.client = OpenAI(api_key=api_key)

    def is_available(self) -> bool:
        return bool(self.database_url and self.client is not None and psycopg is not None)

    def _build_signature(self, spreadsheet_columns: List[str]) -> str:
        normalized = [str(value).strip() for value in spreadsheet_columns if str(value).strip()]
        return " | ".join(normalized)

    def _embed(self, text: str) -> Optional[List[float]]:
        if not self.client:
            return None
        try:
            response = self.client.embeddings.create(model=self.embedding_model, input=text)
            return response.data[0].embedding
        except Exception:
            return None

    def retrieve_examples(self, spreadsheet_columns: List[str]) -> List[Dict[str, Any]]:
        if not self.is_available():
            return []

        signature = self._build_signature(spreadsheet_columns)
        if not signature:
            return []

        embedding = self._embed(signature)
        if not embedding:
            return []

        query = """
            select
                id,
                template_name,
                source_signature,
                mappings_json,
                quality_score,
                1 - (signature_embedding <=> %s::vector) as similarity
            from mapping_examples
            where is_active = true
            order by signature_embedding <=> %s::vector
            limit %s
        """

        try:
            with psycopg.connect(self.database_url) as conn:
                with conn.cursor() as cur:
                    vector_literal = "[" + ",".join(str(x) for x in embedding) + "]"
                    cur.execute(query, (vector_literal, vector_literal, self.top_k))
                    rows = cur.fetchall()
        except Exception:
            return []

        examples: List[Dict[str, Any]] = []
        for row in rows:
            mappings_json = row[3]
            if isinstance(mappings_json, str):
                try:
                    mappings_json = json.loads(mappings_json)
                except Exception:
                    mappings_json = {}
            examples.append(
                {
                    "id": str(row[0]),
                    "template_name": row[1],
                    "source_signature": row[2],
                    "mappings": mappings_json,
                    "quality_score": float(row[4] or 0.0),
                    "similarity": float(row[5] or 0.0),
                }
            )
        return examples

    def list_examples(
        self,
        limit: int = 20,
        active_only: bool = True,
        template_name: Optional[str] = None,
    ) -> Dict[str, Any]:
        if not self.database_url or psycopg is None:
            return {"ok": False, "reason": "database_unavailable", "items": []}

        limit = max(1, min(limit, 200))
        where_clauses = []
        params: List[Any] = []

        if active_only:
            where_clauses.append("is_active = true")
        if template_name:
            where_clauses.append("template_name = %s")
            params.append(template_name)

        where_sql = ""
        if where_clauses:
            where_sql = "where " + " and ".join(where_clauses)

        query = f"""
            select
                id,
                template_name,
                source_signature,
                quality_score,
                usage_count,
                is_active,
                created_at,
                updated_at,
                mappings_json
            from mapping_examples
            {where_sql}
            order by updated_at desc
            limit %s
        """
        params.append(limit)

        try:
            with psycopg.connect(self.database_url) as conn:
                with conn.cursor() as cur:
                    cur.execute(query, tuple(params))
                    rows = cur.fetchall()
        except Exception as exc:
            return {"ok": False, "reason": "database_error", "error": str(exc), "items": []}

        items: List[Dict[str, Any]] = []
        for row in rows:
            mappings_json = row[8]
            if isinstance(mappings_json, str):
                try:
                    mappings_json = json.loads(mappings_json)
                except Exception:
                    mappings_json = {}
            items.append(
                {
                    "id": str(row[0]),
                    "template_name": row[1],
                    "source_signature": row[2],
                    "quality_score": float(row[3] or 0.0),
                    "usage_count": int(row[4] or 0),
                    "is_active": bool(row[5]),
                    "created_at": row[6].isoformat() if row[6] else None,
                    "updated_at": row[7].isoformat() if row[7] else None,
                    "mappings": mappings_json,
                }
            )

        return {"ok": True, "count": len(items), "items": items}

    def save_review_decision(
        self,
        spreadsheet_columns: List[str],
        recommendations: List[Dict[str, Any]],
        approved: bool,
        approved_by: str,
        template_name: Optional[str] = None,
        source_tab_names: Optional[List[str]] = None,
        sample_values_json: Optional[Dict[str, Any]] = None,
        quality_score: Optional[float] = None,
    ) -> Dict[str, Any]:
        if not self.database_url or psycopg is None:
            return {"saved": False, "reason": "database_unavailable"}

        signature = self._build_signature(spreadsheet_columns)
        if not signature:
            return {"saved": False, "reason": "empty_signature"}

        source_tab_names = source_tab_names or []
        sample_values_json = sample_values_json or {}

        if quality_score is None:
            if recommendations:
                total = 0.0
                count = 0
                for item in recommendations:
                    try:
                        total += float(item.get("confidence", 0.0))
                        count += 1
                    except Exception:
                        continue
                quality_score = (total / count) if count else (0.9 if approved else 0.3)
            else:
                quality_score = 0.9 if approved else 0.3

        quality_score = max(0.0, min(1.0, float(quality_score)))

        embedding_literal: Optional[str] = None
        if approved:
            embedding = self._embed(signature)
            if embedding:
                embedding_literal = "[" + ",".join(str(x) for x in embedding) + "]"

        find_query = """
            select id
            from mapping_examples
            where source_signature = %s
            order by created_at desc
            limit 1
        """

        try:
            with psycopg.connect(self.database_url) as conn:
                with conn.cursor() as cur:
                    cur.execute(find_query, (signature,))
                    row = cur.fetchone()
                    existing_id = row[0] if row else None

                    if approved:
                        mappings_json = {
                            "review": {
                                "approved": True,
                                "approved_by": approved_by,
                            },
                            "recommendations": recommendations,
                        }
                        if existing_id:
                            update_query = """
                                update mapping_examples
                                set
                                    template_name = coalesce(%s, template_name),
                                    source_tab_names = %s,
                                    source_columns = %s,
                                    sample_values_json = %s::jsonb,
                                    mappings_json = %s::jsonb,
                                    quality_score = %s,
                                    is_active = true,
                                    signature_embedding = coalesce(%s::vector, signature_embedding),
                                    updated_at = now()
                                where id = %s
                                returning id
                            """
                            cur.execute(
                                update_query,
                                (
                                    template_name,
                                    source_tab_names,
                                    spreadsheet_columns,
                                    json.dumps(sample_values_json),
                                    json.dumps(mappings_json),
                                    quality_score,
                                    embedding_literal,
                                    existing_id,
                                ),
                            )
                            saved_id = cur.fetchone()[0]
                            return {"saved": True, "action": "updated", "id": str(saved_id)}

                        insert_query = """
                            insert into mapping_examples (
                                template_name,
                                source_signature,
                                source_tab_names,
                                source_columns,
                                sample_values_json,
                                mappings_json,
                                quality_score,
                                is_active,
                                signature_embedding
                            )
                            values (%s, %s, %s, %s, %s::jsonb, %s::jsonb, %s, true, %s::vector)
                            returning id
                        """
                        cur.execute(
                            insert_query,
                            (
                                template_name,
                                signature,
                                source_tab_names,
                                spreadsheet_columns,
                                json.dumps(sample_values_json),
                                json.dumps(mappings_json),
                                quality_score,
                                embedding_literal,
                            ),
                        )
                        saved_id = cur.fetchone()[0]
                        return {"saved": True, "action": "inserted", "id": str(saved_id)}

                    if not existing_id:
                        return {"saved": True, "action": "no_record_to_reject"}

                    reject_query = """
                        update mapping_examples
                        set
                            is_active = false,
                            quality_score = least(quality_score, %s),
                            updated_at = now()
                        where id = %s
                        returning id
                    """
                    cur.execute(reject_query, (quality_score, existing_id))
                    saved_id = cur.fetchone()[0]
                    return {"saved": True, "action": "rejected", "id": str(saved_id)}
        except Exception as exc:
            return {"saved": False, "reason": "database_error", "error": str(exc)}
