"""Session-id extraction and validation.

The mcp-session-id header (and the REST /report / /report?session_id= query param, which reaches
the same filesystem-path construction) is client-supplied input — see
mcp.server.mcpserver.context.Context.headers' own docstring, which already warns never to treat
a header as an identity assertion. Both paths funnel through validate_session_id() before the
value is ever used to name a log file, so a value like '../../etc/passwd' can't become a
path-traversal primitive.
"""

import re
from collections.abc import Mapping

MCP_SESSION_ID_HEADER = "mcp-session-id"
SESSION_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


class MissingSessionIdError(ValueError):
    """No mcp-session-id header on this request at all."""


class InvalidSessionIdError(ValueError):
    """Session id present but fails the safe-charset check."""


def validate_session_id(session_id: str) -> str:
    if not SESSION_ID_RE.match(session_id):
        raise InvalidSessionIdError(f"Invalid session id {session_id!r} — must match {SESSION_ID_RE.pattern}")
    return session_id


def extract_session_id(headers: Mapping[str, str] | None) -> str:
    """headers: ctx.headers from an MCP tool call."""
    session_id = headers.get(MCP_SESSION_ID_HEADER) if headers else None
    if not session_id:
        raise MissingSessionIdError(
            "No mcp-session-id header on this request — ask_oracle requires an active "
            "Streamable HTTP session (the client must call initialize first)."
        )
    return validate_session_id(session_id)
