"""Deterministic, mounted-file-backed hint lookup.

Hint categories are whatever the loaded scenario's content defines — not a fixed taxonomy, not a
fixed tier count. Tier progression is tracked per MCP session (see session.py / main.py), read
back from that session's own JSONL log: each call for a category returns the next tier, starting
at 1 and increasing by exactly 1 per successful call. A call past a category's max tier is
rejected outright, never repeated — and a rejected call is never logged, so
`log_store.count_calls(session_id, category)` always equals "how many tiers have actually been
granted so far."
"""

from .logging_store import OracleLogStore
from .models import AskOracleResponse, CategoryInfo, HintContent, OracleLogEntry
from .util import now_iso


class HintService:
    def __init__(self, content: HintContent, log_store: OracleLogStore):
        self._categories = {c.name: c for c in content.categories}
        self._log = log_store

    def list_categories(self) -> list[CategoryInfo]:
        return [
            CategoryInfo(category=c.name, description=c.description, tier_count=len(c.hints))
            for c in self._categories.values()
        ]

    def handle(self, category: str, context: str, session_id: str) -> AskOracleResponse:
        if not category:
            raise ValueError("category is required — call list_hint_categories first")

        cat = self._categories.get(category)
        if cat is None:
            raise ValueError(
                f"Unknown category '{category}' — call list_hint_categories for the current list"
            )

        prior_count = self._log.count_calls(session_id, category)
        if prior_count >= len(cat.hints):
            raise ValueError(
                f"Category '{category}' is already at its maximum tier ({len(cat.hints)}) "
                f"for this session — no more hints available"
            )

        tier = prior_count + 1
        hint = cat.hints[prior_count]

        self._log.append(
            OracleLogEntry(
                session_id=session_id,
                category=category,
                tier=tier,
                context=context,
                hint=hint,
                timestamp=now_iso(),
            )
        )
        return AskOracleResponse(category=category, tier=tier, hint=hint)
