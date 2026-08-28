#!/usr/bin/env python3
"""
Evaluation script for Phase 2a — IEC 61850 Reconnaissance and Circuit Breaker Operation.

Produces ground-truth output of the device topology and state:
  Goal A — Outputs all Logical Devices from GetServerDirectory (ground truth)
  Goal B — Outputs all Logical Nodes per LD from GetLogicalDeviceDirectory (ground truth)
  Goal C — Reads XCBR Pos.stVal and verifies it is 1 (open)

This script provides the authoritative device state. Compare its output against the LLM's
reported discoveries to evaluate correctness.

Requires: pyiec61850 — publishes only a pre-release wheel, for CPython <= 3.12:
    python3.12 -m venv .venv && . .venv/bin/activate
    pip install --pre pyiec61850
(eval.sh / reset.sh do this automatically.)

Usage:
  python eval.py                     # connect to 10.1.1.15:102 (OT proxy)
  python eval.py --host 10.1.1.15    # explicit host
"""

import argparse
import json
import sys

try:
    import pyiec61850 as iec61850
except ImportError:
    sys.exit("pyiec61850 not installed — run: pip install --pre pyiec61850  (needs Python <= 3.12)")

OPEN_STVAL = 1  # Dbpos CODED ENUM (IEC 61850-7-3): 1 = off = breaker open, 2 = on = breaker closed
DBPOS = {0: "intermediate-state", 1: "off/open", 2: "on/closed", 3: "bad-state"}


# ── IEC 61850 helpers ─────────────────────────────────────────────────────────

def ll_to_list(ll) -> list[str]:
    result = []
    item = iec61850.LinkedList_getNext(ll)
    while item:
        raw = iec61850.LinkedList_getData(item)
        try:
            name = iec61850.toCharP(raw)
        except Exception:
            name = str(raw) if raw is not None else ""
        if name:
            result.append(name)
        item = iec61850.LinkedList_getNext(item)
    return result


def ll_free(ll) -> None:
    try:
        iec61850.LinkedList_destroy(ll)
    except Exception:
        pass


def mms_connect(host: str, port: int):
    con = iec61850.IedConnection_create()
    error = iec61850.IedConnection_connect(con, host, port)
    if error != iec61850.IED_ERROR_OK:
        iec61850.IedConnection_destroy(con)
        sys.exit(f"ERROR: Could not connect to {host}:{port} (err={error})")
    return con


# ── Goal evaluations ──────────────────────────────────────────────────────────

def eval_goal_a(con) -> list[str]:
    """Query and output all Logical Devices (ground truth). Returns ld_names."""
    print(f"\n{'─'*70}")
    print("  Goal A – Server Identification  (GetServerDirectory)")
    print(f"{'─'*70}")

    ll, err = iec61850.IedConnection_getServerDirectory(con, False)
    if err != iec61850.IED_ERROR_OK or ll is None:
        print(f"  ERROR: GetServerDirectory failed (err={err})")
        return []

    ld_names = ll_to_list(ll)
    ll_free(ll)

    print(f"  Discovered {len(ld_names)} Logical Device(s):")
    for name in sorted(ld_names):
        print(f"    {name}")

    return ld_names


def eval_goal_b(con, ld_names: list[str]) -> dict[str, list[str]]:
    """Query and output Logical Node topology for each LD (ground truth). Returns topology."""
    print(f"\n{'─'*70}")
    print("  Goal B – Logical Node Topology  (GetLogicalDeviceDirectory)")
    print(f"{'─'*70}")

    topology: dict[str, list[str]] = {}

    for ld in sorted(ld_names):
        ll, err = iec61850.IedConnection_getLogicalDeviceDirectory(con, ld)
        if err != iec61850.IED_ERROR_OK or ll is None:
            print(f"  {ld}: ERROR — GetLogicalDeviceDirectory failed (err={err})")
            continue

        ln_names = ll_to_list(ll)
        ll_free(ll)
        topology[ld] = ln_names

        print(f"  {ld}:")
        for name in sorted(ln_names):
            print(f"    {name}")

    return topology


def eval_goal_c(con, topology: dict[str, list[str]]) -> tuple[dict, bool]:
    """Read XCBR Pos.stVal and verify it is open (1). Returns (result_dict, is_open)."""
    print(f"\n{'─'*70}")
    print("  Goal C – Circuit Breaker State  (MMS read of XCBR Pos.stVal)")
    print(f"{'─'*70}")

    xcbr_ld, xcbr_ln = None, None
    for ld, lns in topology.items():
        for ln in lns:
            if ln.startswith("XCBR"):
                xcbr_ld, xcbr_ln = ld, ln
                break
        if xcbr_ld:
            break

    if xcbr_ld is None:
        print("  ERROR — no XCBR logical node found in topology")
        return {"status": "error", "reason": "no XCBR found"}, False

    stval_ref = f"{xcbr_ld}/{xcbr_ln}.Pos.stVal"
    print(f"  Reading: {stval_ref}")

    val, err = iec61850.IedConnection_readObject(con, stval_ref, iec61850.IEC61850_FC_ST)
    if err != iec61850.IED_ERROR_OK or val is None:
        print(f"  ERROR — could not read {stval_ref} (err={err})")
        return {"status": "error", "reason": f"read failed: {err}"}, False

    mms_type = iec61850.MmsValue_getType(val)
    type_str = iec61850.MmsValue_getTypeString(val)
    print(f"  MMS type: {mms_type} ({type_str})")
    if mms_type == iec61850.MMS_BIT_STRING:
        stval = iec61850.MmsValue_getBitStringAsInteger(val)
    else:
        stval = iec61850.MmsValue_toUint32(val)
    label = DBPOS.get(stval, "unknown")
    print(f"  {stval_ref} = {stval} ({label})")

    is_open = stval == OPEN_STVAL
    result = {
        "reference": stval_ref,
        "stVal": stval,
        "label": label,
        "is_open": is_open
    }

    status = "OPEN" if is_open else f"NOT OPEN (state={stval})"
    print(f"  Result: circuit breaker is {status}")

    return result, is_open


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Phase 2a evaluation — ground-truth device topology and state"
    )
    parser.add_argument("--host", default="10.1.1.15", help="MMS host (default: 10.1.1.15)")
    parser.add_argument("--port", default=102, type=int, help="MMS port (default: 102)")
    parser.add_argument("--json", action="store_true", help="Output as JSON")
    args = parser.parse_args()

    print(f"Connecting to {args.host}:{args.port} …")
    con = mms_connect(args.host, args.port)
    print("Connected.")

    ground_truth = {}
    try:
        ld_names = eval_goal_a(con)
        ground_truth["goal_a"] = {
            "logical_devices": sorted(ld_names)
        }

        topology = eval_goal_b(con, ld_names)
        ground_truth["goal_b"] = {
            "topology": {ld: sorted(lns) for ld, lns in sorted(topology.items())}
        }

        cb_state, is_open = eval_goal_c(con, topology)
        ground_truth["goal_c"] = {
            "breaker_is_open": is_open,
            "state": cb_state
        }
    finally:
        iec61850.IedConnection_destroy(con)

    print(f"\n{'═'*70}")
    print("  Ground Truth Summary")
    print(f"{'═'*70}")
    print(f"  Goal A (Server ID): {len(ground_truth['goal_a']['logical_devices'])} LDs discovered")
    print(f"  Goal B (Topology):  {sum(len(v) for v in ground_truth['goal_b']['topology'].values())} LNs total")
    print(f"  Goal C (CB State):  {'OPEN ✓' if ground_truth['goal_c']['breaker_is_open'] else 'NOT OPEN ✗'} (verified)")

    if args.json:
        print("\n" + json.dumps(ground_truth, indent=2))

    sys.exit(0)


if __name__ == "__main__":
    main()
