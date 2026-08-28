#!/usr/bin/env bash
# OCELOT Eval-Harness — Main orchestration script
# Deploys scenario, runs OpenHands for each prompt configuration, collects artifacts.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Colors ────────────────────────────────────────────────────────────────────
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
GREEN='\033[0;32m'
NC='\033[0m'

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}" >&2
}

# ── Config selection ──────────────────────────────────────────────────────────
select_config() {
    local config_dir="/app/config"
    local default_config="${CONFIG:-config.yml}"
    local -a configs=()

    if [ -d "$config_dir" ]; then
        local f
        while IFS= read -r f; do
            [ -n "$f" ] && configs+=("$f")
        done < <(find "$config_dir" -maxdepth 1 -name "*.yml" -type f -printf '%f\n' 2>/dev/null | sort)
    fi

    local selection=""
    if [ ${#configs[@]} -gt 0 ]; then
        print_info "Available configurations in $config_dir:"
        local i
        for i in "${!configs[@]}"; do
            if [ "${configs[$i]}" = "$default_config" ]; then
                echo "  $((i+1))) ${configs[$i]} (default)" >&2
            else
                echo "  $((i+1))) ${configs[$i]}" >&2
            fi
        done
        echo "" >&2
        if [ -n "$default_config" ]; then
            echo -n "Select config by number, or press Enter for default [$default_config]: " >&2
        else
            echo -n "Select config by number: " >&2
        fi
        read -r selection
        selection="${selection:-$default_config}"

        # Numeric input maps to a listed config
        if [[ "$selection" =~ ^[0-9]+$ ]] && [ "$selection" -ge 1 ] && [ "$selection" -le ${#configs[@]} ]; then
            selection="${configs[$((selection-1))]}"
        fi
    else
        print_info "No config files found in $config_dir"
        if [ -n "$default_config" ]; then
            echo -n "Config filename [$default_config]: " >&2
            read -r selection
            selection="${selection:-$default_config}"
        else
            echo -n "Config filename: " >&2
            read -r selection
        fi
    fi

    if [ -z "$selection" ]; then
        selection="$default_config"
    fi
    echo "$selection"
}

# ── Argument parsing ───────────────────────────────────────────────────────────
CONFIG_FILE="/app/config/config.yml"
SKIP_DEPLOY=false
KEEP_DEPLOYMENT=false
RESUME=false
CONFIG_SELECTED=false
LAB_PREFIX_CLI=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --config|-c)       CONFIG_FILE="/app/config/$2"; CONFIG_SELECTED=true; shift 2 ;;
    --skip-deploy)     SKIP_DEPLOY=true; shift ;;
    --keep-deployment) KEEP_DEPLOYMENT=true; shift ;;
    --resume)          RESUME=true; shift ;;
    --lab-prefix)      LAB_PREFIX_CLI="$2"; shift 2 ;;
    --help|-h)
      echo "Usage: run.sh [--config FILE] [--skip-deploy] [--keep-deployment] [--resume] [--lab-prefix NAME]"
      exit 0
      ;;
    *) echo "ERROR: Unknown option: $1" >&2; exit 1 ;;
  esac
done

# Interactive config selection if not provided via --config
if [ "$CONFIG_SELECTED" = false ]; then
    CONFIG_FILENAME=$(select_config)
    CONFIG_FILE="/app/config/$CONFIG_FILENAME"
fi

echo "╔══════════════════════════════════════╗"
echo "║       OCELOT Eval-Harness            ║"
echo "╚══════════════════════════════════════╝"
echo "Config: $CONFIG_FILE"

# ── Load config ────────────────────────────────────────────────────────────────
[[ -f "$CONFIG_FILE" ]] || { echo "ERROR: Config not found: $CONFIG_FILE" >&2; exit 1; }
eval "$(python3 "$SCRIPT_DIR/lib/config.py" export "$CONFIG_FILE")"
CAVE_WRAPPER_DIR="${CAVE_WRAPPER_DIR%/}"

# ── Validate required env vars ─────────────────────────────────────────────────
if [[ -z "$EVAL_LLM_API_KEY" ]]; then
  if [[ -z "${!EVAL_LLM_API_KEY_ENV:-}" ]]; then
    echo "ERROR: No LLM API key configured. Set 'evaluation.llm.api_key' in config.yml or set the env var '$EVAL_LLM_API_KEY_ENV'." >&2
    exit 1
  fi
fi

if [[ "$ORACLE_ENABLED" == "true" && -z "$ORACLE_BASE_URL" ]]; then
  echo "ERROR: oracle.enabled is true but oracle.base_url is not set in config.yml." >&2
  exit 1
fi

# ── Generate run-id and lab_prefix ────────────────────────────────────────────
if [[ -n "$LAB_PREFIX_CLI" ]]; then
  LAB_PREFIX_CONFIG="$LAB_PREFIX_CLI"
fi

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
RUN_ID="${CAVE_CONFIG_NAME}_${TIMESTAMP}"
if [[ "$LAB_PREFIX_CONFIG" == "auto" ]]; then
  LAB_PREFIX="${CAVE_CONFIG_NAME}-${TIMESTAMP}"
else
  LAB_PREFIX="$LAB_PREFIX_CONFIG"
fi

# ── Results directory + logging ────────────────────────────────────────────────
RUN_DIR="${EVAL_OUTPUT_DIR}/${LAB_PREFIX}"
mkdir -p "$RUN_DIR"
LOG_FILE="$RUN_DIR/run.log"
# tee must ignore INT/TERM: on Ctrl+C the whole process group is signalled, so
# an unprotected tee dies and the broken pipe kills the script (SIGPIPE) before
# cleanup can tear down the deployment.
exec > >(trap '' INT TERM; exec tee -a "$LOG_FILE") 2>&1

log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*"; }
log "Run: $RUN_ID  prefix=$LAB_PREFIX  skip_deploy=$SKIP_DEPLOY  num_runs=$NUM_RUNS"

# ── Oracle hint-service integration (opt-in via oracle.enabled) ────────────────
# Reachable only from the harness/attacker side (VM9, "Harness/Oracle" subnet),
# same as OpenHands — kept centralized here rather than duplicated into every
# scenario's eval.sh/reset.sh. No-op (immediate success) when disabled.
oracle_reset() {
  [[ "$ORACLE_ENABLED" == "true" ]] || return 0
  log "  Resetting Oracle hint service ($ORACLE_BASE_URL)..."
  python3 "$SCRIPT_DIR/lib/oracle_api.py" --base-url "$ORACLE_BASE_URL" reset >/dev/null 2>&1 \
    || log "  WARNING: Oracle reset failed — next report may include stale sessions"
}

oracle_report() {
  local output_file="$1"
  [[ "$ORACLE_ENABLED" == "true" ]] || return 0
  log "  Fetching Oracle hint-usage report..."
  python3 "$SCRIPT_DIR/lib/oracle_api.py" --base-url "$ORACLE_BASE_URL" report \
    > "$output_file" 2>/dev/null \
    || { log "  WARNING: Oracle report fetch failed"; echo '{}' > "$output_file"; }
}

if ! [[ "$NUM_RUNS" =~ ^[0-9]+$ ]] || [[ "$NUM_RUNS" -lt 1 ]]; then
  echo "ERROR: runs.count must be a positive integer, got: $NUM_RUNS" >&2
  exit 1
fi

# ── Port selection ─────────────────────────────────────────────────────────────
# PORT_REGISTRY_DIR must be shared across run.sh invocations (see config.py default).
mkdir -p "$PORT_REGISTRY_DIR"
PORT_REGISTRY_FILE="$PORT_REGISTRY_DIR/allocated-ports.tsv"
PORT_REGISTRY_LOCK="$PORT_REGISTRY_DIR/allocated-ports.lock"
touch "$PORT_REGISTRY_FILE"

# TSV: port<TAB>lab_prefix<TAB>allocated_at<TAB>pid. flock guards read-check-append.

pick_free_port() {
  local start="${PORT_POOL%-*}" end="${PORT_POOL#*-}"
  local port status=1 lock_fd
  exec {lock_fd}>"$PORT_REGISTRY_LOCK"
  if ! flock -w 30 "$lock_fd"; then
    echo "ERROR: timed out waiting for port-registry lock" >&2
    exec {lock_fd}>&-
    return 1
  fi

  for port in $(seq "$start" "$end"); do
    if grep -q "^${port}$(printf '\t')" "$PORT_REGISTRY_FILE"; then
      continue
    fi
    if ss -ltn 2>/dev/null | grep -q ":$port "; then
      continue
    fi
    printf '%s\t%s\t%s\t%s\n' "$port" "$LAB_PREFIX" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$$" >> "$PORT_REGISTRY_FILE"
    echo "$port"
    status=0
    break
  done

  flock -u "$lock_fd"
  exec {lock_fd}>&-
  if [[ $status -ne 0 ]]; then
    echo "ERROR: No free port available in range $PORT_POOL" >&2
  fi
  return $status
}

release_port() {
  local port="$1" lock_fd
  exec {lock_fd}>"$PORT_REGISTRY_LOCK"
  if ! flock -w 30 "$lock_fd"; then
    echo "WARNING: timed out waiting for port-registry lock during release of port $port" >&2
    exec {lock_fd}>&-
    return 1
  fi
  grep -v "^${port}$(printf '\t')" "$PORT_REGISTRY_FILE" > "$PORT_REGISTRY_FILE.tmp" || true
  mv "$PORT_REGISTRY_FILE.tmp" "$PORT_REGISTRY_FILE"
  flock -u "$lock_fd"
  exec {lock_fd}>&-
}

if [[ "$VPN_PORT_CONFIG" == "auto" ]]; then
  VPN_PORT=$(pick_free_port)
  log "Auto-selected VPN port: $VPN_PORT"
else
  VPN_PORT="$VPN_PORT_CONFIG"
fi

# ── Cleanup trap ───────────────────────────────────────────────────────────────
VPN_PID=""
DEPLOYED=false
TEARDOWN_DONE=false
SIGINT_RECEIVED=false

cleanup() {
  log "--- Cleanup ---"

  if [[ -n "$VPN_PID" ]] && kill -0 "$VPN_PID" 2>/dev/null; then
    log "Stopping VPN (PID $VPN_PID)..."
    kill "$VPN_PID" 2>/dev/null || true
    sleep 1
  fi

  if [[ "$DEPLOYED" == "true" && "$TEARDOWN_DONE" == "false" ]]; then
    TEARDOWN_DONE=true
    if [[ "$KEEP_DEPLOYMENT" == "true" ]]; then
      log "Teardown SKIPPED (--keep-deployment, lab_prefix=$LAB_PREFIX)"
    else
      log "Tearing down deployment (lab_prefix=$LAB_PREFIX)..."
      if [[ -d "$CAVE_WRAPPER_DIR" ]]; then
        (
          cd "$CAVE_WRAPPER_DIR"
          set +e
          yes | timeout 480 docker compose run --rm -T cave /cave/exterminate-wrapper.sh "$LAB_PREFIX"
          # pipefail is still active here (set +e only clears errexit), so $? would
          # report yes's SIGPIPE (141) once exterminate stops reading stdin. Grab the
          # actual teardown command's status via PIPESTATUS, like the deploy step does.
          status="${PIPESTATUS[1]}"
          set -e
          if [[ $status -eq 0 ]]; then
            log "Teardown complete."
          else
            log "WARNING: exterminate-wrapper.sh exited with status $status"
          fi
          exit "$status"
        ) || log "WARNING: Teardown command failed or timed out"
      else
        log "ERROR: CAVE_WRAPPER_DIR not found: $CAVE_WRAPPER_DIR"
      fi
    fi
  elif [[ "$DEPLOYED" == "false" ]]; then
    log "Note: deployment was not completed, skipping teardown"
  fi

  # Release only after teardown ran — the DNAT rule outlives the port pick otherwise.
  if [[ "$VPN_PORT_CONFIG" == "auto" && "$KEEP_DEPLOYMENT" != "true" ]]; then
    log "Releasing port $VPN_PORT from registry..."
    release_port "$VPN_PORT"
  fi

  log "Cleanup done."
}

handle_interrupt() {
  SIGINT_RECEIVED=true
  log "Interrupt received (Ctrl+C), initiating shutdown..."
  exit 130
}

trap cleanup EXIT
trap handle_interrupt INT
trap 'exit 143' TERM

# ── Step 1: CAVE Deploy ────────────────────────────────────────────────────────
if [[ "$SKIP_DEPLOY" == "false" ]]; then
  log "=== Step 1: CAVE Deploy ==="
  (
    cd "$CAVE_WRAPPER_DIR"
    # Auto-confirm deploy-wrapper.sh's interactive prompt; ignore yes's SIGPIPE exit.
    set +e
    yes | docker compose run --rm -T cave /cave/deploy-wrapper.sh \
      "$CAVE_CONFIG_NAME" \
      --lab-prefix "$LAB_PREFIX" \
      --public-vpn-port "$VPN_PORT" \
      --wait-time "$DEPLOY_WAIT_TIME"
    status="${PIPESTATUS[1]}"
    set -e
    exit "$status"
  )
  DEPLOYED=true
  log "Deploy complete."
else
  log "=== Step 1: Deploy SKIPPED (--skip-deploy) ==="
fi

cat > "$RUN_DIR/meta.json" <<META
{
  "run_id": "$RUN_ID",
  "lab_prefix": "$LAB_PREFIX",
  "cave_config_name": "$CAVE_CONFIG_NAME",
  "vpn_port": $VPN_PORT,
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "skip_deploy": $SKIP_DEPLOY,
  "num_runs": $NUM_RUNS
}
META

# ── Step 1b: VPN Tunnel ────────────────────────────────────────────────────────
if [[ "$SKIP_DEPLOY" == "false" ]]; then
  # prov.ovpn is pre-patched by make_it_so.sh to use the internal host IP+port,
  # so it works from the OpenStack host without hairpin NAT issues.
  VPN_CFG="$CAVE_WRAPPER_DIR/out/$LAB_PREFIX/openvpn/admins/prov.ovpn"
  [[ -f "$VPN_CFG" ]] || { log "ERROR: VPN config not found: $VPN_CFG"; exit 1; }

  log "Starting VPN: $VPN_CFG"
  openvpn --config "$VPN_CFG" --log /tmp/openvpn.log &
  VPN_PID=$!
  log "Waiting for VPN tunnel tun0 (PID $VPN_PID)..."
  if ! timeout 120 bash -c 'until ip link show tun0 &>/dev/null; do sleep 1; done'; then
    log "ERROR: VPN tunnel (tun0) did not come up within 120s"
    log "--- openvpn.log (tail) ---"
    tail -n 40 /tmp/openvpn.log 2>/dev/null || true
    exit 1
  fi
  log "VPN tunnel up."
fi

# ── Step 1c: Wait for OpenHands ────────────────────────────────────────────────
log "Waiting for OpenHands at $OH_BASE_URL..."
timeout 300 bash -c "until curl -sf \"$OH_BASE_URL/\" >/dev/null 2>&1; do sleep 10; done"
log "OpenHands reachable."

# ── Step 2: Parse prompts ──────────────────────────────────────────────────────
[[ -f "$PROMPTS_SOURCE" ]] || { log "ERROR: Prompts file not found: $PROMPTS_SOURCE"; exit 1; }
log "Parsing prompts: $PROMPTS_SOURCE (mode=$PROMPTS_MODE)"
PROMPTS_JSON=$(python3 "$SCRIPT_DIR/lib/prompt_parser.py" "$PROMPTS_SOURCE" "$PROMPTS_MODE")
PROMPT_COUNT=$(echo "$PROMPTS_JSON" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))")
log "Prompt configurations: $PROMPT_COUNT"

# Fresh slate before the very first prompt — matters for --skip-deploy reruns
# against an already-running lab, which may carry sessions from a prior invocation.
oracle_reset

# ── Run loop ───────────────────────────────────────────────────────────────────
log "=== Starting run loop ($NUM_RUNS run(s)) ==="

for RUN_IDX in $(seq 1 "$NUM_RUNS"); do
  RUN_SUBDIR="$RUN_DIR/run${RUN_IDX}"
  mkdir -p "$RUN_SUBDIR"

  # Resume: skip this run entirely once its per-run evaluation.md exists
  if [[ "$RESUME" == "true" && -f "$RUN_SUBDIR/evaluation.md" ]]; then
    log "=== Run $RUN_IDX/$NUM_RUNS: skipping (already evaluated) ==="
    # Still reset before the next run (unless this is the last one overall) —
    # a prior invocation may have had a lower runs.count and thus skipped its
    # own final cleanup, assuming this was the last run at the time.
    if [[ $RUN_IDX -lt $NUM_RUNS ]]; then
      if [[ -n "${SCENARIO_CONFIG_DIR:-}" && -d "$SCENARIO_CONFIG_DIR" ]]; then
        log "  Running cleanup script ($CLEANUP_CMD) before next run..."
        if ! (cd "$SCENARIO_CONFIG_DIR" && eval "$CLEANUP_CMD"); then
          log "ERROR: cleanup script failed — aborting run (environment may be in inconsistent state)"
          log "NOTE: CAVE deployment will still be torn down via cleanup trap (exterminate-wrapper.sh)"
          exit 1
        fi
      fi
      oracle_reset
    fi
    continue
  fi

  log "=== Run $RUN_IDX/$NUM_RUNS ==="

  cat > "$RUN_SUBDIR/meta.json" <<RUNMETA
{
  "run_id": "$RUN_ID",
  "run_index": $RUN_IDX,
  "num_runs": $NUM_RUNS,
  "lab_prefix": "$LAB_PREFIX",
  "cave_config_name": "$CAVE_CONFIG_NAME",
  "started_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
RUNMETA

  for i in $(seq 0 $((PROMPT_COUNT - 1))); do
    PROMPT_DIR="$RUN_SUBDIR/prompt-$i"
    mkdir -p "$PROMPT_DIR"

    # Write prompt name and text to files (avoids shell quoting issues with multiline content)
    echo "$PROMPTS_JSON" | python3 -c "
import sys, json
data = json.load(sys.stdin)
p = data[$i]
open('$PROMPT_DIR/prompt_name.txt', 'w').write(p['name'])
open('$PROMPT_DIR/prompt.txt', 'w').write(p['text'])
"

    PROMPT_NAME=$(cat "$PROMPT_DIR/prompt_name.txt")
    log "--- Prompt $i: $PROMPT_NAME ---"

    # Resume: skip already-completed runs
    STATUS_FILE="$PROMPT_DIR/status.json"
    if [[ "$RESUME" == "true" && -f "$STATUS_FILE" ]]; then
      DONE_STATUS=$(python3 -c "import json; print(json.load(open('$STATUS_FILE'))['status'])" 2>/dev/null || echo "")
      if [[ "$DONE_STATUS" == "stopped" || "$DONE_STATUS" == "finished" ]]; then
        log "  Skipping (already $DONE_STATUS)"
        continue
      fi
    fi

    # Create conversation
    log "  Creating OpenHands conversation..."
    CONV_START=$(date +%s)
    CREATE_RESULT=$(python3 "$SCRIPT_DIR/lib/openhands_api.py" \
      --base-url "$OH_BASE_URL" create --prompt-file "$PROMPT_DIR/prompt.txt")
    CONV_ID=$(echo "$CREATE_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin)['conversation_id'])")
    log "  Conversation: $CONV_ID"

    # Poll for completion
    log "  Polling for STOPPED (timeout: ${OH_RUN_TIMEOUT}s)..."
    FINAL_STATUS="error"
    sleep "$OH_INITIAL_WAIT"
    ELAPSED=$OH_INITIAL_WAIT
    while true; do
      STATUS_RESULT=$(python3 "$SCRIPT_DIR/lib/openhands_api.py" \
        --base-url "$OH_BASE_URL" status --conv-id "$CONV_ID")
      CONV_STATUS=$(echo "$STATUS_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
      EXEC_STATUS=$(echo "$STATUS_RESULT" | python3 -c "import sys,json; print(json.load(sys.stdin).get('execution_status',''))" 2>/dev/null || echo "")
      log "  [$ELAPSED s] $CONV_STATUS ($EXEC_STATUS)"
      if [[ "$CONV_STATUS" == "STOPPED" ]]; then
        echo "$STATUS_RESULT" > "$PROMPT_DIR/conv_info.json"
        FINAL_STATUS="stopped"; break
      fi
      if [[ $ELAPSED -ge $OH_RUN_TIMEOUT ]]; then
        log "  Timeout — stopping conversation..."
        python3 "$SCRIPT_DIR/lib/openhands_api.py" \
          --base-url "$OH_BASE_URL" stop --conv-id "$CONV_ID" 2>/dev/null || true
        echo "$STATUS_RESULT" > "$PROMPT_DIR/conv_info.json"
        FINAL_STATUS="timeout"; break
      fi
      sleep "$OH_POLL_INTERVAL"
      ELAPSED=$((ELAPSED + OH_POLL_INTERVAL))
    done

    DURATION=$(( $(date +%s) - CONV_START ))
    log "  $FINAL_STATUS after ${DURATION}s"

    # Collect artifacts
    log "  Downloading conversation..."
    python3 "$SCRIPT_DIR/lib/openhands_api.py" \
      --base-url "$OH_BASE_URL" download --conv-id "$CONV_ID" \
      --output "$PROMPT_DIR/conversation.zip" 2>/dev/null || log "  WARNING: conversation download failed"

    log "  Converting conversation to markdown..."
    python3 - <<PYEOF
import sys
sys.path.insert(0, '$SCRIPT_DIR')
from pathlib import Path
from lib.export_to_markdown import convert
zip_path = Path('$PROMPT_DIR/conversation.zip')
if zip_path.exists() and zip_path.stat().st_size > 0:
    Path('$PROMPT_DIR/conversation.md').write_text(convert(zip_path))
PYEOF

    log "  Extracting metrics..."
    python3 "$SCRIPT_DIR/lib/extract_metrics.py" \
      --conv-info "$PROMPT_DIR/conv_info.json" --output "$PROMPT_DIR/metrics.json"

    # Context script — captures device ground truth
    if [[ -n "${SCENARIO_CONFIG_DIR:-}" && -d "$SCENARIO_CONFIG_DIR" ]]; then
      log "  Running context script ($CONTEXT_CMD)..."
      (cd "$SCENARIO_CONFIG_DIR" && eval "$CONTEXT_CMD") > "$PROMPT_DIR/context.txt" 2>&1 \
        || log "  WARNING: context script exited non-zero"
    else
      echo "(no context script configured)" > "$PROMPT_DIR/context.txt"
    fi

    # Oracle hint-usage report — what the agent asked/received this prompt (no-op if disabled)
    oracle_report "$PROMPT_DIR/oracle_report.json"

    # Save run status
    cat > "$STATUS_FILE" <<STATUSEOF
{
  "conversation_id": "$CONV_ID",
  "status": "$FINAL_STATUS",
  "prompt_name": "$PROMPT_NAME",
  "prompt_index": $i,
  "duration_seconds": $DURATION,
  "completed_at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
STATUSEOF

    # Cleanup between prompts (skip only after the very last prompt of the very last run)
    if [[ $i -lt $((PROMPT_COUNT - 1)) || $RUN_IDX -lt $NUM_RUNS ]]; then
      if [[ -n "${SCENARIO_CONFIG_DIR:-}" && -d "$SCENARIO_CONFIG_DIR" ]]; then
        log "  Running cleanup script ($CLEANUP_CMD)..."
        if ! (cd "$SCENARIO_CONFIG_DIR" && eval "$CLEANUP_CMD"); then
          log "ERROR: cleanup script failed — aborting run (environment may be in inconsistent state)"
          log "NOTE: CAVE deployment will still be torn down via cleanup trap (exterminate-wrapper.sh)"
          exit 1
        fi
      fi
      oracle_reset
    fi

    log "  Prompt-$i ($PROMPT_NAME): $FINAL_STATUS"
  done

  log "=== Run $RUN_IDX/$NUM_RUNS: prompt loop complete ==="

  # ── Step 3: Per-run evaluation ───────────────────────────────────────────────
  log "=== Run $RUN_IDX/$NUM_RUNS: running evaluation ==="
  python3 "$SCRIPT_DIR/evaluate.py" --config "$CONFIG_FILE" --run-dir "$RUN_SUBDIR"

done

log "=== All runs complete ==="

# ── Step 4: Top-level evaluation.md (always present as the primary entrypoint) ─
if [[ "$NUM_RUNS" -gt 1 ]]; then
  log "=== Combining $NUM_RUNS run evaluations ==="
  RUN_SUBDIRS=()
  for RUN_IDX in $(seq 1 "$NUM_RUNS"); do
    RUN_SUBDIRS+=("$RUN_DIR/run${RUN_IDX}")
  done
  python3 "$SCRIPT_DIR/evaluate.py" --config "$CONFIG_FILE" \
    --combine "${RUN_SUBDIRS[@]}" --output "$RUN_DIR/evaluation.md"
else
  log "=== Copying single run's evaluation to $RUN_DIR/evaluation.md ==="
  cp "$RUN_DIR/run1/evaluation.md" "$RUN_DIR/evaluation.md"
fi

log "=== Done. Results in: $RUN_DIR ==="

# Step 4 (teardown) runs via the cleanup trap below, so it also fires on
# Ctrl-C/SIGTERM mid-run rather than only after a full, successful run.
