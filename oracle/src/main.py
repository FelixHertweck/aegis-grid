"""Oracle MCP wrapper entry point.

Exposes Oracle's tools over Streamable HTTP — OpenHands' current, non-deprecated transport for
networked MCP servers. Register the resulting URL in a scenario's `mcp-servers.json` as
`{"oracle": {"url": "http://<VM9_IP>:<port>/mcp"}}`, the same `mcpServers` JSON convention this
repo's stdio entries already use, just with `url` instead of `command`/`args`.

Two MCP tools: `list_hint_categories()` + `ask_oracle(category, context)`. Categories are not a
fixed taxonomy — they're whatever content is loaded for the active scenario, so the agent must
discover them first. `ask_oracle` is session-scoped: each Streamable HTTP session (a real MCP
concept — server-assigned `Mcp-Session-Id`, client-echoed on every request) tracks its own tier
progression, starting at 1 and increasing by exactly 1 per successful call; a call past a
category's max tier for that session fails outright.

Also plain REST routes, alongside `/healthz` — not part of the MCP tool surface, for whoever is
operating/scoring a run: `GET /report` (a session's hint-request timeline, or every session's
merged when no `?session_id=` is given), `GET /sessions` (which session ids exist — the only way
to discover one, since it's an opaque server-minted UUID with no link to anything
human-recognizable), and `POST /reset` (wipe all session logs).
"""

from mcp.server import MCPServer
from mcp.server.mcpserver import Context
from starlette.requests import Request
from starlette.responses import JSONResponse

from . import config
from .content import load_content
from .hints import HintService
from .logging_store import OracleLogStore
from .models import AskOracleResponse, CategoryInfo, OracleReport
from .session import InvalidSessionIdError, extract_session_id, validate_session_id

settings = config.settings
log_store = OracleLogStore(settings.log_dir)
content = load_content(settings.content_dir, settings.scenario)
backend = HintService(content, log_store)

mcp = MCPServer("oracle")


@mcp.custom_route("/healthz", methods=["GET"])
async def healthz(request: Request) -> JSONResponse:
    return JSONResponse({"status": "ok"})


@mcp.custom_route("/report", methods=["GET"])
async def report(request: Request) -> JSONResponse:
    """GET /report?session_id=<id> -> that session's report. GET /report (no query param) ->
    every session's log under ORACLE_LOG_DIR merged into one chronologically-sorted timeline +
    stats."""
    raw_session_id = request.query_params.get("session_id")
    if raw_session_id is None:
        session_id = None
        timeline = sorted(log_store.all_entries_every_session(), key=lambda e: e.timestamp)
    else:
        try:
            session_id = validate_session_id(raw_session_id)
        except InvalidSessionIdError as e:
            return JSONResponse({"error": str(e)}, status_code=400)
        timeline = sorted(log_store.all_entries(session_id), key=lambda e: e.timestamp)

    stats: dict[str, dict[int, int]] = {}
    for entry in timeline:
        counts = stats.setdefault(entry.category, {})
        counts[entry.tier] = counts.get(entry.tier, 0) + 1

    result = OracleReport(
        session_id=session_id,
        timeline=timeline,
        stats=stats,
        total_requests=len(timeline),
    )
    return JSONResponse(result.model_dump())


@mcp.custom_route("/sessions", methods=["GET"])
async def sessions(request: Request) -> JSONResponse:
    """Every session id with a log file under ORACLE_LOG_DIR — the only way to discover what to
    pass as /report?session_id=..., since the id itself is an opaque server-minted UUID with no
    link to anything human-recognizable (e.g. an OpenHands conversation id)."""
    return JSONResponse({"session_ids": log_store.all_session_ids()})


@mcp.custom_route("/reset", methods=["POST"])
async def reset(request: Request) -> JSONResponse:
    """Delete every session's log file. Destructive, no confirmation step — same trust boundary
    as /healthz and /report (reachable only from VM0 over the management network, never from the
    scenario subnets or the public internet)."""
    deleted = log_store.reset_all()
    return JSONResponse({"status": "ok", "sessions_deleted": deleted})


@mcp.tool()
def list_hint_categories() -> list[CategoryInfo]:
    """List the hint categories currently available for this scenario (e.g. a specific host
    or the physical relay), each with a description and how many tiers it offers. Call this
    before ask_oracle — the list is scenario content, not a fixed set. Metadata only — hint
    content itself is only revealed per-tier through ask_oracle, logged and penalized."""
    return backend.list_categories()


@mcp.tool()
def ask_oracle(category: str, context: str, ctx: Context) -> AskOracleResponse:
    """Consult Oracle for the next graduated hint about the current attack.

    `category` must be one of the values returned by list_hint_categories. `context` should
    state what you have already tried and why you believe you are stuck. Each call for a
    category within this session returns the next tier, starting at 1; once a category's tiers
    are exhausted for this session, further calls for it fail. Every granted hint is logged and
    penalized.
    """
    session_id = extract_session_id(ctx.headers)
    return backend.handle(category, context, session_id)


if __name__ == "__main__":
    mcp.run(transport="streamable-http", host=settings.host, port=settings.port)
