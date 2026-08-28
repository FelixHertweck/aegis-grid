# Shared Config Templates

Files that are identical (or nearly identical) across multiple `config/phase-*/` scenario
directories, kept here once instead of duplicated in every phase folder.

- `openhands/openhands.env` — baseline OpenHands env file (LLM provider, Neo4j MCP, host IP). No
  task is pre-filled.
- `openhands/mcp-servers-only-neo4j.json` — MCP server definitions with just Neo4j (Cypher +
  memory server). Used by the `*-cumulative.json5` (cumulative-hinting) phase configs.
- `openhands/mcp-servers-oracle.json` — the same Neo4j servers **plus** `oracle` (the Streamable
  HTTP hint service on `10.1.1.11`, see `oracle/README.md` on the `feat/oracle-hint-service`
  branch). Used by the `*-adaptive-oracle.json5` (adaptive-hinting) phase configs.

  Two complete files rather than one with the `oracle` entry commented out: the openhands image
  runs the file through `envsubst | jq` (`images/openhands/assets/run.sh`), so a `//` comment
  would break `jq` and take down MCP configuration for every server, `neo4j` included. Each phase
  config's `openhands` instance points its `mcp-servers.json` `configFiles` source at whichever
  of the two it needs — no post-staging edit is required.

## How this is referenced in a deployment

Deployment maps the `configs/` directory inside `cave-infrastructure-docker` (`configFiles` in each `phase-*.json5` reads from `/cave/backend/configs/...`). Shared configuration files (`mcp-servers-only-neo4j.json`, `mcp-servers-oracle.json`, and default `openhands.env`) are referenced directly in the `.json5` configs via `/cave/backend/configs/shared/openhands/…`.

No copying or file merging between folders is needed. You simply stage the `config/` tree into your `cave-infrastructure-docker/configs/` folder:

```bash
cp -r /tmp/ocelot/config/* ./configs/
```

Most phases (e.g. `phase-1a` through `phase-1d`) do not carry a local `openhands.env` and reference `shared/openhands/openhands.env` directly. `phase-2a` and `phase-2b` keep their own scenario-specific `openhands.env` in their respective phase directories. All phases reference `mcp-servers-*.json` directly from `shared/openhands/`.

Each phase ships two `.json5` configs — `phase-X-cumulative.json5` sources `/cave/backend/configs/shared/openhands/mcp-servers-only-neo4j.json`, while `phase-X-adaptive-oracle.json5` sources `/cave/backend/configs/shared/openhands/mcp-servers-oracle.json`.

If you edit a shared file in `shared/openhands/`, all phases referencing it use the updated version directly at deploy time.

Other subdirectories here (`engineering-files/`, `hmi/`) are unrelated shared assets for the
Windows engineering-workstation scenario, not OpenHands/MCP config — don't copy them into a
phase's `configs/` directory.
