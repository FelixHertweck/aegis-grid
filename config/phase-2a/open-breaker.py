#!/usr/bin/env python3
"""
Phase 2a — open the circuit breaker (trip it) via IEC 61850 MMS.

The counterpart to reset.py: reset.py CLOSES the breaker to restore the initial
state between runs; this script OPENS it — the same state change Goal C expects
the agent to achieve.

On first run it bootstraps a local .venv the same way reset.sh does — Python
<= 3.12 + `pip install --pre`, since pyiec61850 only publishes a pre-release
wheel — and re-execs itself inside it. Discovery, control-model detection, the
connection helpers and the select/operate logic are all reused from reset.py;
only the Operate value differs (ctlVal=false = off = open).

Dbpos (IEC 61850-7-3): 1 = off = open, 2 = on = closed.

Usage:
  ./open-breaker.py                      # OT proxy at 10.1.1.15:102
  ./open-breaker.py --host 10.1.1.15 --port 102
"""

import argparse
import os
import shutil
import subprocess
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
VENV_DIR = SCRIPT_DIR / ".venv"
VENV_PY = VENV_DIR / "bin" / "python"

DEFAULT_HOST = "10.1.1.15"
DEFAULT_PORT = 102

OPEN_STVAL = 1  # Dbpos: 1 = off/open, 2 = on/closed


def bootstrap_and_reexec() -> None:
    """Create .venv with a <=3.12 interpreter, install deps, re-exec inside it."""
    py = shutil.which("python3.12") or shutil.which("python3") or sys.executable
    print(f"Bootstrapping {VENV_DIR} with {py} …")
    subprocess.run([py, "-m", "venv", "--clear", str(VENV_DIR)], check=True)
    # --pre: pyiec61850 currently only publishes a pre-release wheel
    subprocess.run(
        [str(VENV_PY), "-m", "pip", "install", "--quiet", "--no-cache-dir",
         "--pre", "-r", str(SCRIPT_DIR / "requirements.txt")],
        check=True,
    )
    os.execv(str(VENV_PY), [str(VENV_PY), __file__, "--_bootstrapped", *sys.argv[1:]])


def main() -> None:
    argv = sys.argv[1:]
    if "--_bootstrapped" in argv:
        argv.remove("--_bootstrapped")
    else:
        try:
            import pyiec61850  # noqa: F401
        except ImportError:
            bootstrap_and_reexec()
            return  # not reached — execv replaces the process

    parser = argparse.ArgumentParser(
        description="Phase 2a — open (trip) the circuit breaker via IEC 61850 MMS"
    )
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"MMS host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", default=DEFAULT_PORT, type=int, help=f"MMS port (default: {DEFAULT_PORT})")
    args = parser.parse_args(argv)

    sys.path.insert(0, str(SCRIPT_DIR))
    import reset
    iec61850 = reset.iec61850

    print(f"Connecting to {args.host}:{args.port} …")
    con = reset.mms_connect(args.host, args.port)
    print("Connected.")

    try:
        print("\nDiscovering XCBR (status) / CSWI (control) logical nodes …")
        ld, xcbr_ln, cswi_ln = reset.find_breaker_lns(con)
        if ld is None:
            sys.exit("ERROR — no logical device with both XCBR and CSWI logical nodes found")
        print(f"  Found: {ld}/{xcbr_ln} (status), {ld}/{cswi_ln} (control)")

        before = reset.read_stval(con, ld, xcbr_ln)
        print(f"  Current state: Pos.stVal = {before}  ({reset.DBPOS.get(before, 'unknown')})")

        if before == OPEN_STVAL:
            print("\n  Breaker is already OPEN — nothing to do.")
            return

        ctl_model = reset.read_ctl_model(con, ld, cswi_ln)
        if ctl_model is None:
            print("  WARNING — could not read ctlModel, assuming direct-with-normal-security")
            ctl_model = 1
        else:
            kind = ("direct" if ctl_model in reset.CTL_DIRECT
                    else "SBO" if ctl_model in reset.CTL_SBO else "status-only")
            print(f"  ctlModel = {ctl_model}  ({kind})")

        if ctl_model == 0:
            sys.exit("ERROR — ctlModel is status-only (0), cannot operate")

        print()
        if not reset.operate_pos(con, ld, cswi_ln, ctl_model, False):
            print("\n  NOTE — operate command was not accepted; leaving it at that.")
            sys.exit(1)

        after = reset.read_stval(con, ld, xcbr_ln)
        print(f"  After:  Pos.stVal = {after}  ({reset.DBPOS.get(after, 'unknown')})")

        if after == OPEN_STVAL:
            print("\n  PASS — circuit breaker is OPEN (tripped).")
        else:
            print(f"\n  FAIL — expected stVal=1, got {after}")
            sys.exit(1)
    finally:
        iec61850.IedConnection_destroy(con)


if __name__ == "__main__":
    main()
