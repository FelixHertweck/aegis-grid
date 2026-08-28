#!/usr/bin/env python3
"""
Reset Phase 2a — close the circuit breaker via IEC 61850 MMS.

Discovers the XCBR (status) and CSWI (control) logical nodes automatically via
GetServerDirectory and GetLogicalDeviceDirectory, reads the control model
(direct vs. SBO) from CSWI.Pos — XCBR.Pos is status-only on the physical
SIPROTEC — then issues Control.Operate(ctlVal=true) against CSWI.Pos and
verifies via XCBR.Pos.stVal that the breaker is closed.

Connects to the OT proxy, which forwards the Operate call upstream to the IED
per the write rules in proxy-config.yml.

Requires: pyiec61850 — publishes only a pre-release wheel, for CPython <= 3.12:
    python3.12 -m venv .venv && . .venv/bin/activate
    pip install --pre pyiec61850
(eval.sh / reset.sh do this automatically.)

Usage:
  python reset.py                    # OT proxy at 10.1.1.15:102
  python reset.py --host 10.1.1.15   # explicit host
"""

import argparse
import sys

try:
    import pyiec61850 as iec61850
except ImportError:
    sys.exit("pyiec61850 not installed — run: pip install --pre pyiec61850  (needs Python <= 3.12)")

# ctlModel values per IEC 61850-7-2
# 0 = status-only, 1 = direct-with-normal-security, 2 = sbo-with-normal-security
# 3 = direct-with-enhanced-security, 4 = sbo-with-enhanced-security
CTL_DIRECT       = {1, 3}
CTL_SBO          = {2, 4}
CTL_SBO_ENHANCED = {4}  # needs SelectWithValue, not a bare Select

CLOSED_STVAL = 2  # Dbpos: 1 = off/open, 2 = on/closed
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


# ── Discovery ─────────────────────────────────────────────────────────────────

def find_breaker_lns(con) -> tuple[str, str, str] | tuple[None, None, None]:
    """Traverse server directory to find the XCBR (status) and CSWI (control)
    logical nodes of the first logical device that has both.

    XCBR.Pos is status-only on the physical SIPROTEC (ctlModel=0) — the
    actual control path is via CSWI.Pos, per proxy-config.yml.
    """
    ll, err = iec61850.IedConnection_getServerDirectory(con, False)
    if err != iec61850.IED_ERROR_OK or ll is None:
        return None, None, None
    ld_names = ll_to_list(ll)
    ll_free(ll)

    for ld in ld_names:
        ll, err = iec61850.IedConnection_getLogicalDeviceDirectory(con, ld)
        if err != iec61850.IED_ERROR_OK or ll is None:
            continue
        ln_names = ll_to_list(ll)
        ll_free(ll)
        xcbr_ln = next((ln for ln in ln_names if ln.startswith("XCBR")), None)
        cswi_ln = next((ln for ln in ln_names if ln.startswith("CSWI")), None)
        if xcbr_ln and cswi_ln:
            return ld, xcbr_ln, cswi_ln
    return None, None, None


def read_ctl_model(con, ld: str, ln: str) -> int | None:
    """Read ctlModel from XCBR.Pos (FC=CF) to determine control type."""
    ref = f"{ld}/{ln}.Pos.ctlModel"
    val, err = iec61850.IedConnection_readObject(con, ref, iec61850.IEC61850_FC_CF)
    if err != iec61850.IED_ERROR_OK or val is None:
        return None
    return iec61850.MmsValue_toUint32(val)


def read_stval(con, ld: str, ln: str) -> int | None:
    ref = f"{ld}/{ln}.Pos.stVal"
    val, err = iec61850.IedConnection_readObject(con, ref, iec61850.IEC61850_FC_ST)
    if err != iec61850.IED_ERROR_OK or val is None:
        return None
    return iec61850.MmsValue_getBitStringAsInteger(val)


# ── Operate ───────────────────────────────────────────────────────────────────

def print_last_appl_error(ctl) -> None:
    """Print the device's LastApplError (error code + AddCause) if one is set."""
    try:
        e = iec61850.ControlObjectClient_getLastApplError(ctl)
    except Exception:
        return
    if e is None or getattr(e, "error", 0) == 0:
        return
    print(f"  LastApplError: error={e.error} addCause={e.addCause} "
          f"ctlNum={getattr(e, 'ctlNum', '?')}  "
          f"(AddCause: see IEC 61850-7-2 — e.g. 3=Select-failed, "
          f"8=Object-not-selected, 20=Blocked-by-interlocking, "
          f"22=Blocked-by-synchrocheck, 26=Blocked-by-Mode)")


def operate_pos(con, ld: str, ln: str, ctl_model: int, ctl_val: bool) -> bool:
    """Issue Control.Operate on {ld}/{ln}.Pos with the given boolean ctlVal,
    using the select/operate flavour required by ctl_model.

    ctlVal semantics: True = on/close, False = off/open.
    SBO enhanced-security (ctlModel 4) needs SelectWithValue; normal (2) a bare Select.
    """
    pos_ref = f"{ld}/{ln}.Pos"
    ctl = iec61850.ControlObjectClient_create(pos_ref, con)
    if ctl is None:
        print(f"  ERROR — could not create control object for {pos_ref}")
        return False

    val_str = "true" if ctl_val else "false"

    if ctl_model in CTL_SBO:
        if ctl_model in CTL_SBO_ENHANCED:
            print(f"  Control model: SBO enhanced (ctlModel={ctl_model}) — SelectWithValue(ctlVal={val_str}) …")
            ok = iec61850.ControlObjectClient_selectWithValue(ctl, iec61850.MmsValue_newBoolean(ctl_val))
        else:
            print(f"  Control model: SBO normal (ctlModel={ctl_model}) — Select …")
            ok = iec61850.ControlObjectClient_select(ctl)
        if not ok:
            print("  ERROR — Select failed")
            print_last_appl_error(ctl)
            return False
        print("  Select OK")

    print(f"  Issuing Operate(ctlVal={val_str}) …")
    ok = iec61850.ControlObjectClient_operate(ctl, iec61850.MmsValue_newBoolean(ctl_val), 0)
    if not ok:
        print("  ERROR — Operate failed")
        print_last_appl_error(ctl)
        return False

    return True


def close_breaker(con, ld: str, ln: str, ctl_model: int) -> bool:
    """Issue Control.Operate(ctlVal=true) — close the breaker."""
    return operate_pos(con, ld, ln, ctl_model, True)


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Phase 2a reset — close circuit breaker via IEC 61850 MMS"
    )
    parser.add_argument("--host", default="10.1.1.15", help="MMS host, the OT proxy (default: 10.1.1.15)")
    parser.add_argument("--port", default=102, type=int, help="MMS port (default: 102)")
    args = parser.parse_args()

    print(f"Connecting to {args.host}:{args.port} …")
    con = mms_connect(args.host, args.port)
    print("Connected.")

    try:
        print("\nDiscovering XCBR (status) / CSWI (control) logical nodes …")
        ld, xcbr_ln, cswi_ln = find_breaker_lns(con)
        if ld is None:
            sys.exit("ERROR — no logical device with both XCBR and CSWI logical nodes found")
        print(f"  Found: {ld}/{xcbr_ln} (status), {ld}/{cswi_ln} (control)")

        before = read_stval(con, ld, xcbr_ln)
        print(f"  Current state: Pos.stVal = {before}  ({DBPOS.get(before, 'unknown')})")

        if before == CLOSED_STVAL:
            print("\n  Breaker is already CLOSED — nothing to do.")
            return

        ctl_model = read_ctl_model(con, ld, cswi_ln)
        if ctl_model is None:
            print("  WARNING — could not read ctlModel, assuming direct-with-normal-security")
            ctl_model = 1
        else:
            print(f"  ctlModel = {ctl_model}  ({'direct' if ctl_model in CTL_DIRECT else 'SBO'})")

        if ctl_model == 0:
            sys.exit("ERROR — ctlModel is status-only (0), cannot operate")

        print()
        ok = close_breaker(con, ld, cswi_ln, ctl_model)
        if not ok:
            print("\n  NOTE — operate command was not accepted; leaving it at that.")
            return

        after = read_stval(con, ld, xcbr_ln)
        print(f"  After:  Pos.stVal = {after}  ({DBPOS.get(after, 'unknown')})")

        if after == CLOSED_STVAL:
            print("\n  PASS — circuit breaker is CLOSED, ready for next run.")
        else:
            print(f"\n  FAIL — expected stVal=2, got {after}")
            sys.exit(1)

    finally:
        iec61850.IedConnection_destroy(con)


if __name__ == "__main__":
    main()
