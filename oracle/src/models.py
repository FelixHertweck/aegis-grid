"""Shared schema for the Oracle wrapper: the mounted content-file shape, the MCP response
shapes, and the structured log entry written on every granted `ask_oracle` call.

Categories are not a fixed taxonomy hardcoded here: they're whatever the loaded scenario's
content file defines, listable via the `list_hint_categories` tool. Tiers are a plain 1-indexed
int — a category can define as many as its content has, no fixed ceiling.
"""

from pydantic import BaseModel


class HintCategory(BaseModel):
    """One category's full content — name, description, and its hint ladder (hints[0] is tier
    1, the shallowest; ascending index = deeper disclosure)."""

    name: str
    description: str = ""
    hints: list[str]


class HintContent(BaseModel):
    """Root schema of content/hints/<scenario>.json."""

    categories: list[HintCategory]


class CategoryInfo(BaseModel):
    """One entry in `list_hint_categories`'s response — metadata only, so the agent can decide
    where to spend its hint budget before it has revealed anything. `tier_count` is how many
    tiers this category has; hint content itself stays gated behind `ask_oracle`."""

    category: str
    description: str
    tier_count: int


class AskOracleResponse(BaseModel):
    """`ask_oracle`'s response — category is always caller-supplied and echoed back."""

    category: str
    tier: int
    hint: str


class OracleLogEntry(BaseModel):
    """One line in a session's structured log. Only successful, granted hints are logged — a
    call rejected for an unknown category or an already-exhausted tier ladder never reaches
    here."""

    session_id: str
    category: str
    tier: int
    context: str
    hint: str
    timestamp: str


class OracleReport(BaseModel):
    """Response for the `GET /report` REST endpoint. `session_id` is `None` when the report
    aggregates every session's log (no `?session_id=` given)."""

    session_id: str | None
    timeline: list[OracleLogEntry]
    stats: dict[str, dict[int, int]]
    total_requests: int
