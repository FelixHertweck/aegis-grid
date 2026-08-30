# Oracle

An on-demand hint service for the attacker LLM (VM0/OpenHands). Runs as its own service on VM9,
outside the scenario subnets, reachable only via MCP. Every consultation is logged. The logs are
the raw material for a later reliance analysis — how autonomously the agent completed a kill
chain versus how much it leaned on Oracle — but that analysis is a downstream consumer of the
logs (see "Structured log"); Oracle itself computes no score and enforces no penalty.

This README documents the service that lives in this directory; it does not itself define a
scenario's actual hint content or deployment config — see "Configuration" below for what's
authored here versus authored per-run.

## Mechanism

Mounted-JSON-file-backed fixed hint cells, auto-escalating tier by request count. Deterministic
and fully reproducible: no LLM in the loop, no improvisation, no external backend — every hint is
fixed content authored ahead of time and read straight from a file. Tier progression and
repeat-rejection are scoped **per MCP session** (see "Sessions" below), not per deployment — two
different agent connections asking about the same category each start fresh at tier 1,
independently.

## MCP interface

```
list_hint_categories() -> {
  category: str,
  description: str,
  tier_count: int
}[]

ask_oracle(category: str, context: str) -> {
  category: str,
  tier: int,
  hint: str
}
```

`category` is **not enforced by the service** — the categories are whatever the loaded scenario's
content file defines, and the agent must discover them with `list_hint_categories` before calling
`ask_oracle` (`category` must be one of the values it returns). The OCELOT scenario content
(`config/phase-*/oracle-hints.json`) names each category for the specific piece of scenario
knowledge it covers (`network_endpoint`, `device_identity`, `telemetry_registers`,
`circuit_breaker_control`, …) — scenario-specific, not a fixed taxonomy — so the logs read
directly as "what kind of information was missing". Its `description` and
`tier_count` are metadata only — `tier_count` says how many tiers exist for that category (1-
indexed, no fixed ceiling), it does not disclose hint content itself. Hint text stays gated
behind `ask_oracle`, revealed one tier at a time and logged — `list_hint_categories`
exists so the agent can decide where to spend that budget, not to read the hints for free.

`context` is free text — what the agent already tried and why it believes it's stuck.

**`ask_oracle` always returns the *next* tier, starting at 1, increasing by exactly 1 per
successful call — it never repeats.** Once a category's tiers are exhausted for the calling
session, further calls for it fail with an error rather than replaying the last hint.

### Sessions

Streamable HTTP has a real per-connection session concept: the server assigns an opaque
`Mcp-Session-Id` at `initialize`, and the client echoes it on every subsequent request. Oracle
uses that id — read inside the tool handler via an injected `Context` parameter
(`ctx.headers.get("mcp-session-id")`, see `src/session.py`) — to scope tier progression and
logging: each session gets its own `$ORACLE_LOG_DIR/<session_id>.jsonl` and its own independent
"how many tiers has category X already given me" count. A call to `ask_oracle` with no session
header (e.g. a client that never called `initialize`) fails outright rather than falling back to
some shared default.

**The session id is opaque and server-minted — it has no relationship to anything
human-recognizable**, like an OpenHands conversation id. There's no way to look at a
`<session_id>.jsonl` filename and know which real run it came from beyond its timestamp; see
"Reporting" below for the `GET /sessions` endpoint that at least lists what exists.

### Transport: Streamable HTTP

Oracle is exposed over **Streamable HTTP**, not stdio — unlike every stdio-based MCP entry
already in this repo's `config/phase-*/mcp-servers.json` (e.g. the `neo4j` server, launched
locally via `uvx`), Oracle runs on a separate VM and must be reached over the network.
Streamable HTTP is OpenHands' current, non-deprecated transport for networked MCP servers (SSE
is the older, deprecated one).

Register it in a scenario's `mcp-servers.json` using the same `mcpServers` JSON convention
already used for the stdio entries, just with a `url` instead of `command`/`args`:

```json
{
  "mcpServers": {
    "oracle": {
      "url": "http://10.1.0.11:8080/mcp"
    }
  }
}
```

`10.1.0.11` is VM9's address on the management network; VM0 reaches it, the scenario subnets
never do.

**Not yet smoke-tested against the pinned OpenHands version** — do this once, early, before
relying on it for a real run. This includes the session-header assumption above: confirm the
pinned OpenHands MCP client actually sends/echoes `Mcp-Session-Id` the way the Streamable HTTP
spec assumes.

## Configuration

Everything below is an environment variable read at process startup (`src/config.py`) — the
same convention as `ot-proxy.env` / `openhands.env` elsewhere in this repo. **The actual per-run
values for a real scenario deployment are authored separately** (in the OCELOT testbed:
`config/phase-*/`, wired by the scenario's `*-adaptive-oracle.json5` — see "Content") — this
README documents the schema, not the values.

| Variable | Meaning |
|---|---|
| `ORACLE_SCENARIO` | Scenario key — the wrapper loads exactly `$ORACLE_CONTENT_DIR/$ORACLE_SCENARIO.json`. OCELOT phase deployments set `phase-1a` … `phase-2b`; the bundled local-dev example uses `scenario-3.1`. |
| `ORACLE_HOST` / `ORACLE_PORT` | Bind address for the MCP server (default `0.0.0.0:8080`) |
| `ORACLE_LOG_DIR` | Where per-session structured JSONL logs are written (default `/var/log/oracle`) |
| `ORACLE_CONTENT_DIR` | Directory the `<scenario>.json` file is mounted into (image default `/app/content`; the CAVE VM mounts it under `~/oracle/content`) |

See [`.env.example`](.env.example) for a filled-in local-dev shape of this table.

## Content

Not baked into the image — mounted at deploy time, read once at process startup (a container
restart is needed to pick up a change; content does not reload live per request). The wrapper
reads exactly one file, `$ORACLE_CONTENT_DIR/$ORACLE_SCENARIO.json` (`src/content.py`).

**Where that file is authored:**

- **OCELOT testbed** — the source of truth is `config/phase-<X>/oracle-hints.json`. The
  scenario's `phase-<X>-adaptive-oracle.json5` mounts it into the Oracle VM as
  `~/oracle/content/phase-<X>.json` and starts the wrapper with `ORACLE_SCENARIO=phase-<X>`.
- **Standalone / local dev** — this directory: `content/hints/<scenario>.json` (e.g. the bundled
  `scenario-3.1.json`), mounted to `/app/content` by `docker compose` — see "Local development".

Schema (identical in both cases):
```json
{"categories": [{"name": ..., "description": ..., "hints": ["...", "...", ...]}]}
```
one entry per category. In the OCELOT content the `name` identifies a specific piece of scenario
knowledge (see "MCP interface" above); the service itself accepts any string. `hints` is ordered ascending by tier (`hints[0]`
is tier 1, the shallowest) and can be any length — there's no fixed 3-tier cap. Tiers should be
**graded**: tier 1 a strategic nudge, deeper tiers progressively more specific, the last close to
an operational walkthrough. `description` is what `list_hint_categories` surfaces to the agent
before it spends any hint budget — write it to say what the category covers, not to leak the
hints themselves.

## Local development

```bash
cp .env.example .env
docker compose up --build
```

No external backend needed — just a content file under `./content/hints/<scenario>.json`. This
is **not** how VM9 is actually deployed — see [`../images/oracle/README.md`](../images/oracle/README.md)
for that.

## Structured log

Every granted `ask_oracle` call (rejections — unknown category, tiers exhausted — are never
logged) appends one line to `$ORACLE_LOG_DIR/<session_id>.jsonl`
(`src/logging_store.py`, schema in `src/models.py: OracleLogEntry`) — `session_id, category,
tier, context, hint, timestamp`. That is all Oracle records. Turning this log (together with a
run's scenario outcome) into a reliance or hint-penalty metric is left entirely to whoever
consumes it — e.g. an evaluation study that defines its own weighting over categories and tiers
and computes the metric offline, manually or with an LLM. Oracle neither defines nor computes
such a metric; it only guarantees the log is complete and attributable.

## Reporting: `GET /report`, `GET /sessions`, `POST /reset`

Plain REST routes alongside `/healthz` — not part of the MCP tool surface, so they don't show up
to the attacker agent as callable tools, but they're on the same host:port and therefore
reachable from wherever the MCP endpoint is (VM0 only, see "Transport" above).

```
GET /report[?session_id=<id>] -> {
  session_id: str | null,       // null when aggregated across every session
  timeline: OracleLogEntry[],   // chronologically ordered
  stats: { [category: str]: { [tier: int]: int } },
  total_requests: int
}

GET /sessions -> { session_ids: string[] }

POST /reset -> { status: "ok", sessions_deleted: int }
```

`GET /report?session_id=<id>` reads back just that session's `$ORACLE_LOG_DIR/<id>.jsonl` as
JSON instead of requiring SSH access to VM9. `GET /report` with no query param merges **every**
session's log into one chronologically-sorted timeline + stats — since session ids are opaque
(see "Sessions" above), `GET /sessions` is the only way to discover what exists to query
individually. `timeline` reuses `OracleLogEntry` verbatim rather than a trimmed-down shape —
every field in it is something the agent already received when it made that request, so
returning it here discloses nothing new. `stats` counts granted hints per category and tier.

`POST /reset` deletes every session's log file — destructive, no confirmation step, same trust
boundary as the other routes above (VM0-reachable only). Mainly for resetting a local/test stack
between runs; a real per-run VM9 deployment would more likely get torn down and re-provisioned
than reset in place, but nothing stops using this there too.

## Status

MCP plumbing, the request/response logic, the structured log, and the scenario hint content
(`config/phase-*/oracle-hints.json`, scenario-specific categories, graded tiers per category)
are implemented. **Not yet done:** a pilot to check that the
per-category tier counts and the request-count auto-escalation behave sensibly against a real
agent. Any reliance/penalty scoring over the logs is a downstream concern (see "Structured log")
and is out of scope for this service. The session-header assumption (`Mcp-Session-Id` sent/echoed
by the client per the Streamable HTTP spec) hasn't been verified against the pinned OpenHands MCP
client yet — verify before a real pilot run.
