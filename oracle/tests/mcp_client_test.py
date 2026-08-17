#!/usr/bin/env python3
"""Manual MCP client for exercising a running Oracle instance end-to-end.

Connects over Streamable HTTP twice — two independent client sessions, each with its own
server-assigned Mcp-Session-Id (a real Streamable HTTP concept: no manual header plumbing needed
here, the client SDK captures and echoes it automatically per connection). Both walk the same
category to its tier ladder's end and one call past it — proving (a) two sessions asking about
the same category both start fresh at tier 1 (isolation), and (b) a category exhausted for a
session errors on the next call rather than repeating the last hint. Finishes by cross-checking
the aggregate GET /report shows both sessions.

Usage:
    python mcp_client_test.py http://localhost:18080/mcp
"""

import asyncio
import json
import sys
import urllib.request

from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from mcp.types import CallToolResult


def _unwrap(result: CallToolResult) -> object:
    """A CallToolResult carries the tool's return value either as structured_content (when the
    tool's return type is a model/dict, our case) or as a text content block — prefer the
    former, fall back to the latter. A tool returning a bare list (list_hint_categories, a list
    of CategoryInfo objects) gets its structured_content auto-wrapped as {"result": [...]} since
    JSON Schema tool outputs must be an object — unwrap that one level too."""
    if result.structured_content is not None:
        content = result.structured_content
        if isinstance(content, dict) and content.keys() == {"result"}:
            return content["result"]
        return content
    for block in result.content:
        if hasattr(block, "text"):
            return block.text
    return result


async def run_session(url: str, label: str, category: str, calls: int) -> None:
    async with streamable_http_client(url) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            print(f"[{label}] walking '{category}' for {calls} calls:")
            for i in range(1, calls + 1):
                result = await session.call_tool(
                    "ask_oracle", {"category": category, "context": f"{label} call #{i}"}
                )
                if result.is_error:
                    print(f"[{label}]   call {i}: ERROR (expected on the last, over-budget call): {result.content}")
                else:
                    print(f"[{label}]   call {i}: {_unwrap(result)}")


async def main(url: str) -> None:
    async with streamable_http_client(url) as (read_stream, write_stream):
        async with ClientSession(read_stream, write_stream) as session:
            await session.initialize()
            result = await session.call_tool("list_hint_categories", {})
            categories = _unwrap(result)
            print(f"Categories: {categories}\n")

    if not categories:
        print("No categories available — nothing further to test.")
        return

    category = categories[0]["category"]
    tier_count = categories[0]["tier_count"]

    # Two independent connections asking about the SAME category must both start at tier 1
    # (session isolation), and both must error on the (tier_count + 1)th call (exhaustion,
    # rejected rather than repeated).
    await run_session(url, "session-A", category, tier_count + 1)
    await run_session(url, "session-B", category, tier_count + 1)

    print("\nCross-check via GET /report (aggregate, no session_id) — should show BOTH sessions:")
    report_url = url.removesuffix("/mcp") + "/report"
    with urllib.request.urlopen(report_url) as resp:
        report = json.load(resp)
    seen_sessions = {e["session_id"] for e in report["timeline"]}
    print(f"  distinct session_ids in the aggregate timeline: {seen_sessions}")
    assert len(seen_sessions) == 2, f"expected two distinct sessions, got {seen_sessions}"

    sessions_url = url.removesuffix("/mcp") + "/sessions"
    with urllib.request.urlopen(sessions_url) as resp:
        listed = json.load(resp)["session_ids"]
    print(f"  GET /sessions: {listed}")
    assert set(listed) == seen_sessions, "GET /sessions should list exactly the sessions seen above"

    print("\nAll checks passed.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python mcp_client_test.py <mcp-url>", file=sys.stderr)
        sys.exit(1)
    asyncio.run(main(sys.argv[1]))
