#!/usr/bin/env python3
"""
Manual test script for the phase-1-openhands OT-Proxy deployment.

Reads all allowed registers from the proxy and verifies that blocked registers
are rejected.

Requires: pip install pymodbus>=3.0

Usage:
  python test-proxy.py                        # read all allowed registers
  python test-proxy.py --host 10.1.1.15       # against deployed VM (VPN required)
  python test-proxy.py --test-blocked         # also verify that DENY works
  python test-proxy.py --host HOST --port PORT --slave SLAVE
"""

import argparse
import sys

import inspect

try:
    try:
        from pymodbus.client import ModbusTcpClient  # pymodbus 3.x
    except ImportError:
        from pymodbus.client.sync import ModbusTcpClient  # pymodbus 2.x
    from pymodbus.exceptions import ModbusException
except ImportError:
    sys.exit("pymodbus not installed — run: pip install pymodbus")

# Detect which keyword name this pymodbus version uses for the slave/unit ID.
_sig = inspect.signature(ModbusTcpClient.read_holding_registers)
_SLAVE_KW = next((k for k in ("slave", "unit", "unit_id", "dev_id") if k in _sig.parameters), None)

# ---------------------------------------------------------------------------
# Register definitions (from proxy-config.yml)
# Addresses are SMA Modbus addresses, which equal the PDU starting address
# directly (no offset). SMA does NOT follow the IEC 61131-3 -1 convention.
# All reads use FC3 (read holding registers).
# U32 values span 2 consecutive registers → count=2.
# ---------------------------------------------------------------------------

ALLOWED_REGISTERS = [
    # Nameplate / Device Identification
    (30003, 2, "Nameplate.SusyId",    "SUSyID of the module"),
    (30005, 2, "Nameplate.SerNum",    "Serial number"),
    (30051, 2, "Nameplate.MainModel", "Device class (8001 = Solar Inverters)"),
    (30053, 2, "Nameplate.Model",     "Exact device type (9336 = STP 15000TL-30)"),
    (30055, 2, "Nameplate.Vendor",    "Manufacturer"),
    (30059, 2, "Nameplate.PkgRev",    "Firmware version"),
    # Live Measurements
    (30769, 2, "DcMs.Amp[1]",         "DC input current string 1 [mA]"),
    (30771, 2, "DcMs.Vol[1]",         "DC input voltage string 1 [0.01 V]"),
    (30773, 2, "DcMs.Watt[1]",        "DC input power string 1 [W]"),
    (30775, 2, "GridMs.TotW",         "Total AC active power output [W]"),
    (30783, 2, "GridMs.PhV.phsA",     "Grid voltage phase A [0.01 V]"),
    (30785, 2, "GridMs.PhV.phsB",     "Grid voltage phase B [0.01 V]"),
    (30787, 2, "GridMs.PhV.phsC",     "Grid voltage phase C [0.01 V]"),
    (30803, 2, "GridMs.Hz",           "Grid frequency [0.01 Hz]"),
    (30953, 2, "Coolsys.Cab.TmpVal",  "Internal cabinet/heat-sink temperature [0.1 °C]"),
    # State
    (40029, 2, "Operation.OpStt",     "Operating state (381=Stop, 1469=ShutDown)"),
    (30881, 2, "Operation.PvGriConn", "PV mains connection (1779=Separated, 1780=Public grid)"),
    (30217, 2, "Operation.GriSwStt",  "Grid relay/contactor state (51=Closed, 311=Open)"),
]

# A register not in the allow-list → proxy should DENY it.
BLOCKED_REGISTER = 40001


def decode_u32(regs: list[int]) -> int:
    """Combine two 16-bit Modbus words into one U32 (big-endian word order)."""
    return (regs[0] << 16) | regs[1]


def read_registers(client: ModbusTcpClient, slave: int) -> None:
    print(f"\n{'─'*70}")
    print(f"  Reading {len(ALLOWED_REGISTERS)} allowed registers (FC3)")
    print(f"{'─'*70}")
    ok = 0
    for reg_num, count, name, desc in ALLOWED_REGISTERS:
        addr = reg_num  # SMA PDU address == register number (no offset)
        try:
            kw = {_SLAVE_KW: slave} if _SLAVE_KW else {}
            result = client.read_holding_registers(addr, count=count, **kw)
        except ModbusException as exc:
            print(f"  [{reg_num:>5}] {name:<22}  ERROR (Modbus exception): {exc}")
            continue

        if result.isError():
            print(f"  [{reg_num:>5}] {name:<22}  PROXY DENIED / error: {result}")
        else:
            raw = decode_u32(result.registers) if count == 2 else result.registers[0]
            print(f"  [{reg_num:>5}] {name:<22}  {raw:>12}   # {desc}")
            ok += 1

    print(f"\n  {ok}/{len(ALLOWED_REGISTERS)} registers read successfully.")


def test_blocked(client: ModbusTcpClient, slave: int) -> None:
    print(f"\n{'─'*70}")
    print(f"  Testing DENY on blocked register {BLOCKED_REGISTER} (FC3)")
    print(f"{'─'*70}")
    addr = BLOCKED_REGISTER
    try:
        kw = {_SLAVE_KW: slave} if _SLAVE_KW else {}
        result = client.read_holding_registers(addr, count=2, **kw)
    except ModbusException as exc:
        print(f"  PASS — Modbus exception received (proxy blocked): {exc}")
        return

    if result.isError():
        print(f"  PASS — Proxy returned an error response: {result}")
    else:
        regs = result.registers
        print(f"  FAIL — Proxy allowed the read! Registers: {regs}")
        print("         Check proxy-config.yml default_action is DENY.")


def main() -> None:
    parser = argparse.ArgumentParser(description="Test script for ocelot phase-1-openhands OT-Proxy")
    parser.add_argument("--host",         default="localhost",  help="Proxy host (default: localhost)")
    parser.add_argument("--port",         default=5020, type=int, help="Proxy port (default: 5020)")
    parser.add_argument("--slave",        default=3,    type=int, help="Modbus slave/unit ID (default: 3)")
    parser.add_argument("--timeout",      default=5,    type=int, help="TCP timeout in seconds")
    parser.add_argument("--test-blocked", action="store_true",   help="Verify that DENY rules work")
    args = parser.parse_args()

    print(f"Connecting to OT-Proxy at {args.host}:{args.port} (slave={args.slave}) …")
    client = ModbusTcpClient(args.host, port=args.port, timeout=args.timeout)
    if not client.connect():
        sys.exit(f"ERROR: Could not connect to {args.host}:{args.port}")
    print("Connected.")

    try:
        read_registers(client, args.slave)
        if args.test_blocked:
            test_blocked(client, args.slave)
    finally:
        client.close()

    print()


if __name__ == "__main__":
    main()
