# Scenario Configuration Scripts

Each scenario directory (e.g. `phase-2a/`, `phase-2b/`) must contain two shell scripts that the eval-harness calls during a run: **`eval.sh`** and **`reset.sh`**. This document explains when they are called, what they must do, and what output format is expected.

---

## Overview: When the Harness Calls These Scripts

The eval-harness (`eval-harness/src/run.sh`) runs the following loop for each prompt configuration:

```
for each prompt:
  1. Start OpenHands conversation with the prompt
  2. Wait for OpenHands to finish (STOPPED or timeout)
  3. Download conversation and workspace artifacts
  4. Run eval.sh   → output captured to prompt-N/context.txt
  5. If more prompts remain: run reset.sh
```

After all prompts have run, `evaluate.py` reads `context.txt` from each `prompt-N/` directory and passes it to an LLM evaluator alongside the conversation transcript.

The scripts are invoked from inside `$SCENARIO_CONFIG_DIR` — the scenario's own directory within the CAVE wrapper's config tree (e.g. `…/backend/configs/phase-2a/`). All relative paths in the scripts resolve against that directory.

For an `*-adaptive-oracle` config (which deploys the Oracle hint service), `eval.sh`/`reset.sh` do **not** need to query or reset Oracle themselves — that's handled centrally by the harness (see `oracle.enabled`/`oracle.base_url` in `eval-harness/README.md`), the same way OpenHands itself is orchestrated outside these scripts.

The default commands used are:
- `bash eval.sh` (configurable via `context_script.cmd` in `config.yml`)
- `bash reset.sh` (configurable via `cleanup_script.cmd` in `config.yml`)

---

## `eval.sh` — Ground-Truth Context Script

### Purpose

Captures the **real device state** after the OpenHands agent has run. The output is the authoritative ground truth that the LLM evaluator compares against what the agent claims to have done.

### When it is called

Once per prompt, **after** OpenHands has stopped and artifacts have been collected. It must not modify device state — it is read-only.

### What it must output

All stdout and stderr is captured to `prompt-N/context.txt`. The evaluator receives this verbatim and uses it to assess whether the agent actually achieved its goals.

The output should make each evaluation goal verifiable without ambiguity. Recommended structure:

```
Connecting to <host>:<port> …
Connected.

──────────────────────────────────────────────────────────────────────
  Goal A – <goal description>
──────────────────────────────────────────────────────────────────────
  <structured results for Goal A>

──────────────────────────────────────────────────────────────────────
  Goal B – <goal description>
──────────────────────────────────────────────────────────────────────
  <structured results for Goal B>

══════════════════════════════════════════════════════════════════════
  Ground Truth Summary
══════════════════════════════════════════════════════════════════════
  Goal A: <pass/fail indicator>
  Goal B: <pass/fail indicator>
  …

{"goal_a": {...}, "goal_b": {...}, …}   ← optional JSON block at end
```

The JSON block (when present) must be valid and appear at the end of stdout so it can be extracted programmatically if needed in the future. The LLM evaluator currently reads the full text output, so human-readable labels are important.

### Exit code

The harness tolerates a non-zero exit code (logs a warning and continues), but a non-zero exit means `context.txt` may be empty or incomplete, which will degrade evaluation quality. Scripts should use `set -euo pipefail` and exit non-zero only on genuine failure.

### Example skeleton

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

# Activate venv if needed (e.g. pyiec61850 requires Python <= 3.12)
if [[ -d ".venv" ]]; then
    source .venv/bin/activate
fi

python eval.py --host <device-host> --port <port> --json
```

Where `eval.py` connects to the target device, queries the state relevant to each goal, prints human-readable output including a summary, and optionally prints a JSON block at the end.

---

## `reset.sh` — Cleanup Script

### Purpose

Restores the scenario to its **initial state** so that the next prompt configuration starts from a clean baseline. It should undo whatever the agent may have done.

### When it is called

Once between each consecutive pair of prompt configurations — **not** after the last prompt. If there is only one prompt configuration, `reset.sh` is never called.

### What it must do

Bring the device/environment back to the expected start state for the scenario. Examples:
- Close a circuit breaker that the agent may have opened
- Restore a configuration file the agent may have changed
- Delete files the agent may have created in the workspace

It should connect **directly to the target device**, bypassing any OT proxy that may restrict writes.

### Output

stdout and stderr are shown in the harness log (`run.log`) but are not captured to a results file. Log progress clearly so failures are diagnosable.

### Exit code

Non-zero exit logs a warning but does not abort the run. However, a failed reset means subsequent runs start in an undefined state, so scripts must be reliable. Use `set -euo pipefail`.

### Example skeleton

```bash
#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [[ -d ".venv" ]]; then
    source .venv/bin/activate
fi

echo "=== Resetting scenario to initial state ==="
python reset.py --host <device-host-direct> --port <port>
echo "=== Reset complete ==="
```

---

## Configuration in `config.yml`

The script names and commands can be overridden in the harness `config.yml`:

```yaml
context_script:
  cmd: "bash eval.sh"      # default

cleanup_script:
  cmd: "bash reset.sh"     # default
```

Both commands are evaluated with `eval` in bash, so you can pass additional arguments or chain commands if needed.

---

## File Layout per Scenario

```
config/
  phase-2a/
    eval.sh          ← ground-truth context script (required)
    eval.py          ← Python helper called by eval.sh (recommended)
    reset.sh         ← cleanup/reset script (required if multi-prompt)
    reset.py         ← Python helper called by reset.sh (recommended)
    .venv/           ← optional Python venv (e.g. for pyiec61850)
    config.yml       ← optional harness config override
```

The `.venv/` directory is optional. If present, both `eval.sh` and `reset.sh` should activate it before running Python helpers, since some device libraries (e.g. `pyiec61850`) require a specific Python version.
