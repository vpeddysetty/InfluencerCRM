from typing import Dict, Any, List
from langgraph.graph import StateGraph, END
from typing_extensions import TypedDict


class AgentState(TypedDict, total=False):
    spreadsheet_columns: List[str]
    recommendations: Dict[str, Any]


def build_graph(mapper):
    def parse_columns(state: AgentState) -> AgentState:
        spreadsheet_columns = state.get("spreadsheet_columns", [])
        result = mapper.recommend(spreadsheet_columns)
        state["recommendations"] = result
        return state

    graph = StateGraph(AgentState)
    graph.add_node("parse_columns", parse_columns)
    graph.add_edge("parse_columns", END)
    graph.set_entry_point("parse_columns")
    return graph.compile()
