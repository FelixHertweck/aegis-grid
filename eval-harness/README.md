# OCELOT Eval-Harness

Automated evaluator for OCELOT scenarios. Deploys a scenario with CAVE, runs an OpenHands agent against each knowledge-gradient prompt configuration, collects all artifacts, and generates a structured evaluation document using an LLM.

```
CAVE Deploy → [runs.count ×: [per-prompt: OpenHands run → collect artifacts → reset] → per-run evaluation.md] → combined evaluation.md → CAVE Teardown
```

---

## Prerequisites

- Docker with Compose plugin (`docker compose`)
- The `cave-infrastructure-docker` repository checked out on the host
- An API key for an OpenAI-compatible LLM

---

## Quick Start

```bash
cd eval-harness/docker

# 1. Configure environment
cp .env.example .env
$EDITOR .env                    # set EVAL_LLM_API_KEY, CAVE_WRAPPER_DIR

# 2. Add your prompts
cp /path/to/your/prompts/*.md config/prompts/

# 3. (optional) Customize the run config
cp config/config.yml config/config-myrun.yml
$EDITOR config/config-myrun.yml

# 4. Run
docker compose run --rm eval
CONFIG=config-myrun.yml docker compose run --rm eval

# 4b. Run in background (container keeps running after terminal closes)
docker compose run -d eval && docker compose logs -f eval

# 5. Skip deploy (already-running lab)
docker compose run --rm eval --skip-deploy

# 6. Keep lab running after the eval
docker compose run --rm eval --keep-deployment

# 7. Resume an interrupted run (skips already-completed prompts)
docker compose run --rm eval --resume

# 8. Re-run only the evaluation step on existing results (per run)
docker compose run --rm eval python3 evaluate.py \
  --config /app/config/config.yml \
  --run-dir /app/results/<lab_prefix>/run1

# 8b. Re-run the combination step across existing per-run evaluations
docker compose run --rm eval python3 evaluate.py \
  --config /app/config/config.yml \
  --combine /app/results/<lab_prefix>/run1 /app/results/<lab_prefix>/run2 \
  --output /app/results/<lab_prefix>/evaluation.md
```

---

## run.sh Flags

| Flag | Description |
|---|---|
| `--config FILE` | Explicit config file path; skips the interactive config-selection menu. (`$CONFIG` env var only sets the default offered *within* that menu — it does not skip it.) |
| `--skip-deploy` | Skip CAVE deploy and VPN setup; use with an already-running lab. Also skips teardown. |
| `--keep-deployment` | Run as normal but do not tear down the lab after the eval completes. |
| `--resume` | Skip prompt runs that already have a `status.json` with status `stopped` or `finished`; skips an entire `runN/` folder if it already has an `evaluation.md`. |
| `--lab-prefix NAME` | Override `deploy.lab_prefix` from the config file. Lets you pin the results dir / lab name from the CLI instead of editing the config, e.g. to target a specific run when resuming. |

Flags can be combined, e.g. `--skip-deploy --resume` to continue a partially-run eval on an existing lab.

The container's entrypoint already runs `run.sh` (see `Dockerfile`), so pass these flags directly to `docker compose run --rm eval`, without repeating `run.sh` — e.g. `docker compose run --rm eval --skip-deploy --resume`.

To resume a specific run without touching the config file:

```bash
docker compose run --rm eval --resume --lab-prefix my-run
```

This automatically continues after the last successfully completed prompt: `--resume` walks `results/my-run/runN/prompt-*/status.json` in order and skips every prompt already marked `stopped`/`finished`, picking back up at the first one that isn't — no need to say which prompt to start from.

---

## evaluate.py Flags

Runs the LLM evaluation step standalone on an existing results directory, without re-deploying or re-running OpenHands. Per-prompt `eval_block.json` files are cached — only missing ones trigger a new LLM call.

Has two mutually-exclusive modes: `--run-dir` (evaluate one `runN/` folder into its own `evaluation.md`) and `--combine` (merge several already-evaluated `runN/` folders into one combined `evaluation.md`).

| Flag | Description |
|---|---|
| `--config FILE` | Path to the run config YAML (required) |
| `--run-dir DIR` | Single-run mode: results directory containing `prompt-N/` subdirectories |
| `--combine DIR [DIR ...]` | Combine mode: paths to `runN/` directories that already contain `evaluation.md` |
| `--output FILE` | Output path for `--combine` mode's combined document (required with `--combine`) |
| `--extraction-prompt FILE` | Override the extraction prompt file |
| `--synthesis-prompt FILE` | Override the synthesis (document generation) prompt file |
| `--multi-run-synthesis-prompt FILE` | Override the run-combination prompt file (used with `--combine`) |
| `--template FILE` | Override the evaluation template file |

```bash
docker compose run --rm eval python3 evaluate.py \
  --config /app/config/config.yml \
  --run-dir /app/results/<lab_prefix>/run1
```

---

## Configuration (`config/config.yml`)

All paths inside `config.yml` are **container-internal paths**. Select a non-default config with `CONFIG=filename.yml docker compose run --rm eval`. Multiple config files can coexist in `config/`.

| Section | Key | Default | Description |
|---|---|---|---|
| `scenario` | `cave_config_name` | — | CAVE config name; also used to locate scenario scripts |
| `scenario` | `cave_wrapper_dir` | `/cave-wrapper` | CAVE wrapper dir (mounted from `$CAVE_WRAPPER_DIR` in `.env`) |
| `scenario` | `configs_subpath` | `backend/configs` | Subpath within `cave_wrapper_dir` where scenario configs live |
| `deploy` | `wait_time` | `600` | Seconds to wait after CAVE deploy completes |
| `deploy` | `lab_prefix` | `auto` | Lab name prefix; `auto` generates `<scenario>-<YYYYMMDD>-<HHMM>` |
| `deploy` | `public_vpn_port` | `auto` | VPN port; `auto` picks the first free port from `port_pool` |
| `deploy` | `port_pool` | `51820-51920` | Port range for auto VPN port selection |
| `openhands` | `base_url` | `http://10.1.1.20:3000` | OpenHands URL (reachable via VPN) |
| `openhands` | `poll_interval` | `15` | Seconds between conversation status polls |
| `openhands` | `run_timeout` | `3600` | Max seconds per run before the conversation is force-stopped |
| `prompts` | `source` | — | Prompt file (relative to `config/prompts/` or absolute) |
| `prompts` | `mode` | `cumulative` | `cumulative`: each run appends the next hint; `individual`: each hint runs alone |
| `runs` | `count` | `1` | Number of times to repeat the full prompt sweep. Each repeat gets its own `runN/` results folder; no redeploy between repeats, only `cleanup_script`. When > 1, a combined `evaluation.md` is generated across all runs. |
| `oracle` | `enabled` | `false` | Enable Oracle hint-service integration for the `*-adaptive-oracle` phase configs (which deploy the Oracle VM). When true, the harness resets Oracle before the run loop and between every prompt/run (so tier progression always starts clean), and saves each prompt's hint-usage report to `prompt-N/oracle_report.json`, which the extraction LLM sees alongside `context.txt`. No-op when false — nothing else in the pipeline needs Oracle-awareness. |
| `oracle` | `base_url` | — | Oracle's REST base URL (e.g. `http://10.1.1.11:8080`), reachable from the harness the same way `openhands.base_url` is. Required when `oracle.enabled` is true. |
| `context_script` | `cmd` | `bash eval.sh` | Command run after each OpenHands conversation; stdout → `context.txt` |
| `cleanup_script` | `cmd` | `bash reset.sh` | Command run between prompt runs (and between repeated runs) to reset device state |
| `evaluation` | `extraction_prompt` | built-in | Path to the per-run LLM extraction prompt file |
| `evaluation` | `synthesis_prompt` | built-in | Path to the document synthesis LLM prompt file |
| `evaluation` | `multi_run_synthesis_prompt` | built-in | Path to the LLM prompt that combines per-run `evaluation.md` documents (only used when `runs.count > 1`) |
| `evaluation` | `template` | built-in | Path to the evaluation document template |
| `evaluation.llm` | `model` | `gpt-4o` | LLM model used for evaluation |
| `evaluation.llm` | `api_key` | — | LLM API key (or set `EVAL_LLM_API_KEY` in `.env`) |
| `evaluation.llm` | `base_url` | — | LLM base URL for non-OpenAI providers |

The scenario scripts (`eval.sh`, `reset.sh`) are looked up at:
`{cave_wrapper_dir}/{configs_subpath}/{cave_config_name}/`

---

### Overriding Evaluation Prompts and Templates

Three key components drive the evaluation:

1. **Extraction prompt** — instructs the LLM how to analyze each OpenHands run individually
2. **Synthesis prompt** — instructs the LLM how to synthesize per-run results into the final document
3. **Document template** — defines the structure and sections of the final markdown document

All three can be customized per-scenario. This is useful when the default evaluation criteria don't match your specific attack chain or protocol requirements.

#### How Overrides Work

The harness respects a **priority order** (highest to lowest):

1. **CLI flag** (`--extraction-prompt FILE`, `--synthesis-prompt FILE`, `--template FILE`)
2. **Config file** (`config.yml` → `evaluation.extraction_prompt`, `evaluation.synthesis_prompt`, `evaluation.template`)
3. **Built-in defaults** (`src/prompts/extraction.md`, `src/prompts/synthesis.md`, `src/prompts/template.md`)

#### Using Custom Prompts (Recommended: Config YAML)

Add your custom prompts to `config/` and reference them in `config.yml`:

```yaml
evaluation:
  extraction_prompt: /app/config/extraction-prompt-custom.md
  synthesis_prompt: /app/config/synthesis-prompt-custom.md
  template: /app/config/evaluation-template-custom.md
```

The paths are **container-internal paths** (`/app/config` is mounted from `docker/config/` on the host).

The harness includes built-in defaults for all three components. You can create custom versions for your scenario by copying the built-in files and modifying them:

```bash
cp /path/to/eval-harness/src/prompts/extraction.md config/extraction-custom.md
cp /path/to/eval-harness/src/prompts/synthesis.md config/synthesis-custom.md
cp /path/to/eval-harness/src/prompts/template.md config/template-custom.md
```

#### Using Custom Prompts (CLI Override)

For one-off evaluation runs with different prompts, use CLI flags:

```bash
docker compose run --rm eval python3 evaluate.py \
  --config /app/config/config.yml \
  --run-dir /app/results/<lab_prefix>/run1 \
  --extraction-prompt /app/config/custom-extraction.md \
  --synthesis-prompt /app/config/custom-synthesis.md \
  --template /app/config/custom-template.md
```

#### Caching Behavior

- **Extraction prompt**: per-prompt `eval_block.json` files are cached. Changing the extraction prompt requires deleting these files to re-evaluate.
- **Synthesis prompt** and **Template**: the final `evaluation.md` is re-generated each time `evaluate.py` runs (not cached). This allows you to iterate on synthesis and template without re-extracting.

#### Example: Using a Custom Synthesis Prompt

If you want to customize how results are synthesized into the final document, create a custom synthesis prompt:

```bash
# Get the built-in synthesis prompt as a starting point
docker run --rm -v $(pwd)/docker/config:/tmp ghcr.io/felixhertweck/ocelot-eval-harness:main \
  cat /app/prompts/synthesis.md > docker/config/synthesis-custom.md

# Edit it to match your scenario
$EDITOR docker/config/synthesis-custom.md

# Reference it in config.yml
cat >> docker/config/config.yml <<EOF

evaluation:
  synthesis_prompt: /app/config/synthesis-custom.md
EOF

# Re-run evaluation with the new synthesis prompt (extractions stay cached)
docker compose run --rm eval python3 evaluate.py \
  --config /app/config/config.yml \
  --run-dir /app/results/<lab_prefix>/run1
```

This is much faster than re-extracting — only the final document synthesis runs with the new prompt.

---

## Prompt File Format

Prompt files (`config/prompts/*.md`) use this structure:

```markdown
# Phase X – Title (optional, skipped by parser)

# Hint Overview (optional, skipped)

# Base Prompt
<base prompt text — sent alone in run 0>

# Hint 1:
<hint 1 text — appended to base in run 1>

# Hint 2:
<hint 2 text — appended in run 2>
```

In `cumulative` mode, run N receives `Base + Hint 1 + … + Hint N`. In `individual` mode, each section is sent alone.

---

## Scenario Scripts

Each scenario must provide `eval.sh` and `reset.sh` in its config directory. See [`config/README.md`](../config/README.md) for the full contract. In brief:

- **`eval.sh`** — queries the device for ground-truth state after a run; stdout/stderr → `context.txt`. VPN is active when it runs.
- **`reset.sh`** — restores the device to its initial state between prompt runs. Called between consecutive runs, not after the last.

---

## Building the Image Locally

```bash
cd eval-harness/docker
docker compose build
```

Or pull the published image from GHCR (built automatically on push to `main` when `src/` or `Dockerfile` changes):

```
ghcr.io/felixhertweck/ocelot-eval-harness:main
```

---

## Directory Structure

```
eval-harness/
  Dockerfile              ← builds the harness container (all deps baked in)
  src/                    ← source code copied into the image
    run.sh                ← main orchestration (deploy → run loop → evaluation → teardown)
    evaluate.py           ← standalone evaluation step (reads existing results)
    lib/                  ← Python libraries
    prompts/              ← default evaluation template and extraction prompt
  docker/                 ← deployment directory — work from here
    docker-compose.yml
    .env.example          ← copy to .env and fill in
    config/               ← your scenario configs (mounted read-only into container)
      config.yml          ← example run configuration
      prompts/            ← place your prompt .md files here
    results/              ← run output (gitignored, created by the harness)
      <lab_prefix>/       ← one folder per run.sh invocation (single deploy/teardown)
        run.log
        meta.json
        evaluation.md     ← primary entrypoint: copy of run1/evaluation.md if runs.count == 1,
                            or the combined evaluation across all runs if runs.count > 1
        run1/             ← one full prompt sweep
          evaluation.md   ← evaluation document for this run only
          meta.json
          prompt-0/       ← base prompt run
            prompt.txt, conversation.md, metrics.json, context.txt, status.json
            oracle_report.json  ← only when oracle.enabled is true
          prompt-1/       ← base + hint 1 run
            ...
        run2/             ← present when runs.count > 1: same prompt sweep, repeated
          ...
```

---

## How the Evaluation Document is Generated

The evaluation process runs in **two LLM-powered stages** after all OpenHands runs complete. This ensures that each prompt configuration is evaluated independently, and then synthesized into a coherent report.

### Stage 1: Per-Prompt Extraction

For each prompt configuration (Base, Base + Hint 1, …, Base + Hint 1–5), a dedicated LLM call extracts structured evaluation data:

```
Input to LLM:
  • System prompt: extraction_prompt (default: src/prompts/extraction.md)
  • Prompt name & text (what was sent to OpenHands)
  • Agent conversation (complete transcript: commands, outputs, reasoning)
  • Token metrics (input/output token counts from the model)
  • Device context (output of eval.sh: ground-truth device state after the run)

Output from LLM:
  • JSON object (eval_block.json) with fields:
    - attack_result: "Success", "Partial", or "Failure"
    - completed_goals: structured breakdown of each goal (A, B, C, …)
    - narrative: 2–4 paragraph summary of the approach and outcome
    - failure_mode_category & explanation: root cause if unsuccessful
    - restriction_adherence: did the agent follow the constraints?
    - scope_adherence: was the agent in scope?
    - protocol_correctness: did the agent use correct operations & data paths?
    - key_observations: notable behaviours or edge cases
    - token_efficiency_note: comment on token usage vs. goals achieved
```

**Caching**: The result is cached in `prompt-N/eval_block.json`. If you change the extraction prompt, delete these cached files to re-evaluate.

### Stage 2: Document Synthesis

After all per-prompt blocks are extracted, a final LLM call synthesizes them into the complete evaluation document:

```
Input to LLM:
  • System prompt: synthesis_prompt (default: src/prompts/synthesis.md)
    - Explains the document structure (Sections 1–14)
    - Instructs the LLM to fill [PLACEHOLDER] values with real data
    - Specifies synthesis rules (per-prompt blocks in Section 6, cross-config trends in Sections 7–14)
    - The template content is embedded into this prompt via [TEMPLATE_WILL_BE_INSERTED_HERE] placeholder
  • All eval_blocks: JSON array of the 6 per-prompt evaluation objects
  • Run metadata: timestamps, lab_prefix, config_name, VPN port

Output from LLM:
  • Complete Markdown document (evaluation.md) with:
    - Section 1: Test Setup (devices, targets, verification method)
    - Section 2: Evaluation Overview (attack chain description)
    - Section 3: Success Criteria (specific measurable goals)
    - Section 4: Evaluation Criteria (quality dimensions)
    - Section 5: Results Summary (table of all configurations & goals)
    - Section 6: Per-Configuration Evaluation (detailed narrative per config)
    - Sections 7–14: Cross-configuration analysis (attack-chain progress, token efficiency, failure modes, protocol correctness, scope/safety adherence, knowledge-gradient analysis, conclusions, final benchmark)
```

**No caching**: The final document is re-generated each time you run `evaluate.py`. This allows you to iterate on both the synthesis prompt and template without re-extracting per-prompt blocks.

### Key Design Decisions

- **Independence**: Each prompt configuration is evaluated separately, so information from one test doesn't influence another.
- **Device-grounded**: The LLM has access to the actual device state (`context.txt` from `eval.sh`), allowing it to detect discrepancies between what the agent *claims* to have done and what the device *actually shows*.
- **Two-stage structure**: Per-prompt extraction is cacheable and reusable; document synthesis is fast and iterative (good for template experimentation).
- **Prompt overrides**: Both prompts can be customized per-scenario (see **Overriding Evaluation Prompts and Templates** section above).

### Troubleshooting Evaluation

If the `evaluation.md` output looks incorrect or incomplete:

1. **Check the extraction blocks first**: Review `results/<lab_prefix>/prompt-N/eval_block.json` for each configuration. If these are wrong, the final document will be wrong.
2. **Re-run extraction only**: Delete `eval_block.json` files and re-run `evaluate.py` to re-extract without re-deploying or re-running OpenHands.
3. **Inspect input to LLM**: The `conversation.md` and `context.txt` files in each `prompt-N/` directory are what the LLM sees. If these are incomplete or corrupted, the LLM output will reflect that.
4. **Iterate on synthesis and template**: If extraction looks correct but the final document is wrong, you can experiment with custom `synthesis_prompt.md` and `template.md` files without re-extracting. This is much faster than changing the extraction logic.
5. **Check the synthesis prompt**: If the document structure is wrong or sections are missing, the synthesis prompt may not be correctly instructing the LLM. Review `src/prompts/synthesis.md` or your custom synthesis prompt and re-run `evaluate.py`.

---

## Troubleshooting

### Background Execution

Since the eval-harness prompts for console input during the run, use `screen` to keep the interactive session alive in the background so you can reattach and answer prompts whenever needed.

```bash
screen -S eval
docker compose run eval
```

Detach from the session with `Ctrl+A` then `D`. The container keeps running, and you can safely close your terminal.

To reattach later (e.g. to answer a console prompt or check progress):

```bash
screen -r eval
```

To list running sessions:

```bash
screen -ls
```

---

### Common Issues

| Symptom | Likely cause |
|---|---|
| `Set EVAL_LLM_API_KEY` error | `.env` file missing or `EVAL_LLM_API_KEY` not exported |
| VPN route never appears | `/dev/net/tun` not mounted or `NET_ADMIN` cap missing |
| OpenHands unreachable | VPN tunnel failed or wrong `base_url` |
| `No prompt-N directories` in evaluate | Wrong `--run-dir` path |
| CAVE deploy fails | `CAVE_WRAPPER_DIR` wrong or docker socket not mounted |
| Teardown fails | CAVE container not running; check `docker compose ps` in `CAVE_WRAPPER_DIR` |
