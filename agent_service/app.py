import csv
import io
import os
from typing import List, Dict, Any, Optional
from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
from dotenv import load_dotenv
from agent_service.mapping_service import MetadataMapper
from agent_service.langgraph_workflow import build_graph
from agent_service.llm_service import OpenAIAdvisor, blend_confidence
from agent_service.retrieval_service import MappingExampleRetriever

load_dotenv()

app = FastAPI(title="Influencer CRM Metadata Mapping Agent")
mapper = MetadataMapper()
workflow = build_graph(mapper)
advisor = OpenAIAdvisor()
retriever = MappingExampleRetriever()
REVIEW_THRESHOLD = float(os.getenv("REVIEW_THRESHOLD", "0.7"))


class MappingRequest(BaseModel):
    spreadsheet_columns: List[str]


class ReviewRecommendation(BaseModel):
    spreadsheet_column: str
    target_entity: str
    target_attribute: str
    confidence: float
    recommendation_type: str = "mapped"
    notes: str = ""
    source: Optional[str] = None


class MappingReviewRequest(BaseModel):
    spreadsheet_columns: List[str]
    recommendations: List[ReviewRecommendation]
    approved: bool = True
    approved_by: str = "system"
    template_name: Optional[str] = None
    source_tab_names: List[str] = []
    sample_values_json: Dict[str, Any] = {}
    quality_score: Optional[float] = None


class MappingApproveRequest(BaseModel):
    spreadsheet_columns: List[str]
    recommendations: List[ReviewRecommendation]
    approved_by: str = "system"
    template_name: Optional[str] = None
    source_tab_names: List[str] = []
    sample_values_json: Dict[str, Any] = {}
    quality_score: Optional[float] = None


def extract_columns_from_upload(file_name: str, contents: bytes) -> List[str]:
    if file_name.lower().endswith(".csv"):
        rows = list(csv.reader(io.StringIO(contents.decode("utf-8-sig"))))
        if not rows:
            return []
        return [value for value in rows[0] if value]

    if file_name.lower().endswith(".xlsx"):
        try:
            from openpyxl import load_workbook
        except ImportError as exc:  # pragma: no cover - depends on installed package
            raise HTTPException(status_code=500, detail="openpyxl is required for .xlsx uploads") from exc

        workbook = load_workbook(filename=io.BytesIO(contents), read_only=True, data_only=True)
        worksheet = workbook.active
        first_row = next(worksheet.iter_rows(min_row=1, max_row=1, values_only=True), ())
        workbook.close()
        return [value for value in first_row if value]

    if file_name.lower().endswith(".xls"):
        raise HTTPException(status_code=400, detail=".xls files are not supported; please convert to .xlsx")

    raise HTTPException(status_code=400, detail="Only CSV/XLSX/XLS files are supported")


@app.get("/health")
def health() -> Dict[str, str]:
    return {"status": "ok"}


@app.get("/mappings/examples")
def list_mapping_examples(
    limit: int = 20,
    active_only: bool = True,
    template_name: Optional[str] = None,
) -> Dict[str, Any]:
    response = retriever.list_examples(limit=limit, active_only=active_only, template_name=template_name)
    if not response.get("ok"):
        return {
            "status": "error",
            "reason": response.get("reason", "unknown"),
            "error": response.get("error"),
            "items": [],
        }

    return {
        "status": "ok",
        "count": response.get("count", 0),
        "items": response.get("items", []),
    }


@app.post("/mappings/review")
def review_mapping(request: MappingReviewRequest) -> Dict[str, Any]:
    if not request.spreadsheet_columns:
        raise HTTPException(status_code=400, detail="spreadsheet_columns cannot be empty")

    persistence = retriever.save_review_decision(
        spreadsheet_columns=request.spreadsheet_columns,
        recommendations=[item.model_dump() for item in request.recommendations],
        approved=request.approved,
        approved_by=request.approved_by,
        template_name=request.template_name,
        source_tab_names=request.source_tab_names,
        sample_values_json=request.sample_values_json,
        quality_score=request.quality_score,
    )

    if not persistence.get("saved"):
        return {
            "status": "error",
            "decision": "approved" if request.approved else "rejected",
            "persistence": persistence,
        }

    return {
        "status": "ok",
        "decision": "approved" if request.approved else "rejected",
        "persistence": persistence,
    }


@app.post("/mappings/approve")
def approve_mapping(request: MappingApproveRequest) -> Dict[str, Any]:
    review_request = MappingReviewRequest(
        spreadsheet_columns=request.spreadsheet_columns,
        recommendations=request.recommendations,
        approved=True,
        approved_by=request.approved_by,
        template_name=request.template_name,
        source_tab_names=request.source_tab_names,
        sample_values_json=request.sample_values_json,
        quality_score=request.quality_score,
    )
    return review_mapping(review_request)


@app.post("/map-columns")
def map_columns(request: MappingRequest) -> Dict[str, Any]:
    if not request.spreadsheet_columns:
        raise HTTPException(status_code=400, detail="spreadsheet_columns cannot be empty")

    state = workflow.invoke({"spreadsheet_columns": request.spreadsheet_columns})
    result = state.get("recommendations", {})

    llm_available = advisor.is_available()
    retrieval_available = retriever.is_available()
    llm_enhanced = False
    fallback_used = False
    review_candidates = []
    review_trace = []
    retrieved_examples = retriever.retrieve_examples(request.spreadsheet_columns) if retrieval_available else []

    if llm_available:
        enriched = advisor.recommend(
            request.spreadsheet_columns,
            result.get("metadata_catalog", {}),
            retrieved_examples=retrieved_examples,
        )
        if enriched:
            if "recommendations" in enriched:
                heuristic_recommendations = result.get("recommendations", [])
                llm_recommendations = enriched.get("recommendations", [])
                merged = []
                by_column = {item.get("spreadsheet_column"): item for item in llm_recommendations if isinstance(item, dict) and item.get("spreadsheet_column")}
                for item in heuristic_recommendations:
                    if isinstance(item, dict):
                        column_name = item.get("spreadsheet_column")
                        llm_item = by_column.get(column_name)
                        if llm_item and "confidence" in llm_item:
                            item = dict(item)
                            item["confidence"] = blend_confidence(item.get("confidence", 0.0), llm_item.get("confidence"))
                            item["recommendation_type"] = "mapped"
                            item["notes"] = f"{item.get('notes', '')} LLM confidence blended with heuristic score.".strip()
                            item["source"] = "llm_enhanced"
                        else:
                            item = dict(item)
                            item["source"] = "heuristic"
                        if item.get("confidence", 0.0) < REVIEW_THRESHOLD:
                            review_candidates.append(column_name)
                            review_trace.append({
                                "spreadsheet_column": column_name,
                                "reason": "low_confidence",
                                "confidence": item.get("confidence", 0.0),
                                "target_attribute": item.get("target_attribute"),
                            })
                        merged.append(item)
                result["recommendations"] = merged
            if "custom_fields" in enriched:
                result["custom_fields"] = enriched["custom_fields"]
            llm_enhanced = True
        else:
            llm_enhanced = False
    else:
        llm_enhanced = False

    if not result.get("recommendations"):
        fallback_recommendations = state.get("recommendations", {}).get("recommendations", [])
        result["recommendations"] = [
            {**item, "source": "fallback"} if isinstance(item, dict) else item
            for item in fallback_recommendations
        ]
        fallback_used = True
    if not result.get("custom_fields"):
        result["custom_fields"] = state.get("recommendations", {}).get("custom_fields", [])

    result["debug"] = {
        "llm_available": llm_available,
        "retrieval_available": retrieval_available,
        "retrieved_examples_count": len(retrieved_examples),
        "llm_enhanced": llm_enhanced,
        "fallback_used": fallback_used,
        "recommendation_count": len(result.get("recommendations", [])),
        "review_candidates": review_candidates,
        "review_trace": review_trace,
    }

    return result


@app.post("/map-upload")
async def map_upload(file: UploadFile = File(...)) -> Dict[str, Any]:
    if not file.filename or not file.filename.lower().endswith((".csv", ".xlsx", ".xls")):
        raise HTTPException(status_code=400, detail="Only CSV/XLSX/XLS files are supported")

    contents = await file.read()
    if not contents:
        raise HTTPException(status_code=400, detail="Uploaded file is empty")

    columns = extract_columns_from_upload(file.filename, contents)
    if not columns:
        raise HTTPException(status_code=400, detail="No columns were found in the uploaded file")
    return map_columns(MappingRequest(spreadsheet_columns=columns))
