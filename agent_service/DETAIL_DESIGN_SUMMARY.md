# InfluencerCRM Mapping Agent - One Page Summary

## Goal

Map marketer spreadsheets in arbitrary formats to the InfluencerCRM data model with high confidence, safe fallbacks, and clear review signals.

## What Is Exposed

- GET /health
- POST /map-columns
- POST /map-upload
- GET /mappings/examples
- POST /mappings/review
- POST /mappings/approve

Service entrypoint: agent_service/app.py

## Endpoint Design

- All endpoints are synchronous and stateless.
- `/map-columns` is the core contract; `/map-upload` is a convenience wrapper that extracts the first-row headers and delegates to it.
- Retrieval and LLM enrichment are optional. The heuristic path is the baseline contract and remains available when those dependencies are missing.
- `/mappings/examples` and `/mappings/review` are designed to degrade gracefully with structured `status: error` payloads instead of always surfacing hard transport failures.
- The current service has no auth boundary and should be treated as an internal API until authentication and tenant scoping are added.

## Core Pipeline

1. Heuristic mapping (always available)
   - LangGraph invokes MetadataMapper
   - Produces recommendations, custom_fields, and metadata_catalog

2. Retrieval augmentation (optional)
   - Uses pgvector to fetch top-k similar historical mappings
   - Driven by embedding similarity over spreadsheet signatures

3. LLM enrichment (optional)
   - Injects retrieved examples into prompt payload
   - Validates strict JSON output shape
   - Blends confidence with heuristic score

4. Safe fallback
   - If retrieval or LLM fails, heuristic output is preserved
   - Never hard-fails due to optional enhancement layers

## Confidence and Review

- Final confidence blend:
  - 0.6 * heuristic + 0.4 * llm
- Review threshold:
  - REVIEW_THRESHOLD env var (default 0.7)
- Low-confidence mappings are surfaced in debug.review_trace

## Provenance and Debug

Recommendation source tags:

- llm_enhanced
- heuristic
- fallback

Debug block includes:

- llm_available
- retrieval_available
- retrieved_examples_count
- llm_enhanced
- fallback_used
- recommendation_count
- review_candidates
- review_trace

## Data Model Dependencies

Required in PostgreSQL:

- pgvector extension (vector)
- mapping_examples table with embedding column
- vector similarity index (ivfflat)

Schema files:

- schema/influencer_crm_schema.sql
- schema/mapping_examples_vector.sql

## Configuration

Environment variables:

- OPENAI_API_KEY
- OPENAI_MODEL (default gpt-4.1-mini)
- OPENAI_EMBEDDING_MODEL (default text-embedding-3-small)
- DATABASE_URL
- RETRIEVAL_TOP_K (default 3)
- REVIEW_THRESHOLD (default 0.7)

## Current Strengths

- Deterministic baseline mapping
- Retrieval-first architecture for repeated templates
- Strict LLM payload validation
- Robust fallback behavior
- Good observability for manual review
- Clear split between inference endpoints and review/persistence endpoints

## Next Priorities

1. Persist approved mappings back to mapping_examples automatically
2. Add endpoint authentication and tenant scoping for review/example APIs
3. Add integration tests for retrieval + LLM merge path
4. Add tenant-scoped retrieval filtering by user_id
