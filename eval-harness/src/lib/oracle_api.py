#!/usr/bin/env python3
"""Oracle hint-service REST client (report/reset). Usable as a library or CLI.

Talks to the plain REST routes documented in oracle/README.md (GET /report,
POST /reset) — not the MCP tool surface itself, which the agent calls directly.
"""
import argparse
import json

import requests


class OracleClient:
    def __init__(self, base_url: str, timeout: int = 30):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout

    def report(self, session_id: str | None = None) -> dict:
        """Fetch the hint-usage report (aggregated across sessions if session_id is None)."""
        params = {"session_id": session_id} if session_id else {}
        resp = requests.get(f"{self.base_url}/report", params=params, timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()

    def reset(self) -> dict:
        """Delete every session's log so the next run starts every category at tier 1."""
        resp = requests.post(f"{self.base_url}/reset", timeout=self.timeout)
        resp.raise_for_status()
        return resp.json()


def _cli() -> None:
    parser = argparse.ArgumentParser(description="Oracle hint-service REST client")
    parser.add_argument("--base-url", required=True)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("report")
    p.add_argument("--session-id", default=None)

    sub.add_parser("reset")

    args = parser.parse_args()
    client = OracleClient(args.base_url)

    if args.command == "report":
        print(json.dumps(client.report(args.session_id)))
    elif args.command == "reset":
        print(json.dumps(client.reset()))


if __name__ == "__main__":
    _cli()
