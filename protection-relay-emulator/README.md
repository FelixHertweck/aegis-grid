# IEC 61850 Protection Relay Emulator

A software-emulated IEC 61850 protection relay built with Java and [iec61850bean](https://github.com/beanit/iec61850bean). It exposes a realistic IED data model (PTOC, XCBR, CSWI, MMXU) over MMS and runs a background simulation that generates dynamic telemetry. Modelled after the physical Siemens SIPROTEC 5 used in Phase 2a — control path, control model and data model match the real device. Designed as a deterministic, observable target for attack scenario deployments in the CAVE testbed.

## IED Data Model

IED name: `RelayIED` — Logical Device: `PROT` — full reference prefix: `RelayIEDPROT`

| LN Class | Instance | Description |
|----------|----------|-------------|
| `LLN0`   | `LLN0`   | Logical node zero (LD-level administration) |
| `LPHD`   | `LPHD1`  | Physical device information |
| `PTOC`   | `PTOC1`  | Time overcurrent protection function |
| `XCBR`   | `XCBR1`  | Circuit breaker — status only |
| `CSWI`   | `CSWI1`  | Switch controller — the actual control path |
| `MMXU`   | `MMXU1`  | Measurements (voltage, current, power, frequency) |

### Key Data Attributes

| Object Reference | FC | Description |
|------------------|----|-------------|
| `RelayIEDPROT/XCBR1.Pos.stVal` | ST | Breaker position (Dbpos: 1=open, 2=closed) — status mirror |
| `RelayIEDPROT/XCBR1.Pos.ctlModel` | CF | Control model — `0` (status-only): XCBR1 cannot be operated directly |
| `RelayIEDPROT/CSWI1.Pos.ctlModel` | CF | Control model — `2` (sbo-with-normal-security) |
| `RelayIEDPROT/MMXU1.Hz.mag.f` | MX | Grid frequency (Hz) |
| `RelayIEDPROT/MMXU1.TotW.mag.f` | MX | Total active power (W) |
| `RelayIEDPROT/MMXU1.PPV.phsAB.cVal.mag.f` | MX | Phase AB voltage (V) |
| `RelayIEDPROT/PTOC1.Str.general` | ST | Overcurrent pickup signal |
| `RelayIEDPROT/PTOC1.Op.general` | ST | Overcurrent operate signal — trips the breaker |

## Circuit Breaker Behaviour

The breaker starts **closed** on every container start.

`XCBR1.Pos` is **status-only** (`ctlModel=0`) — it mirrors the breaker position but cannot be
operated directly; a write attempt is rejected (matching the physical SIPROTEC 5, where XCBR1
is not directly controllable). The actual control path is `CSWI1.Pos`, using
`sbo-with-normal-security` (`ctlModel=2`) — the same control model the Phase 2a OT proxy exposes
to downstream clients for the physical device.

**To open:** `Control.Select` on `RelayIEDPROT/CSWI1.Pos`, then `Control.Operate(ctlVal=false)`.  
**To close:** `Control.Select` on `RelayIEDPROT/CSWI1.Pos`, then `Control.Operate(ctlVal=true)`.

Select/Operate reservation (including the select timeout) is handled by the `iec61850bean`
library itself based on `ctlModel` — no custom select-tracking logic is needed.

## Protection Function

PTOC (definite-time overcurrent) is coupled to the actual measured current, not an independent
timer:

- **Pickup (`Str.general`):** occurs periodically (see below) and, while active, drives the
  simulated phase currents to ~7x nominal — the overcurrent condition that caused the pickup.
- **Operate (`Op.general`):** fires after a 1s delay if the pickup is still active, and **trips
  the breaker** — opening it directly (the protection function has authority over its own
  breaker; it does not go through the CSWI1 Select/Operate gate, which is for external MMS
  clients only).
- The breaker does **not** auto-reclose after a trip — it stays open until a client closes it via
  `CSWI1.Pos` or `POST /reset` is called.
- Pickup/operate indicators clear after 4s; the breaker position is unaffected by the clear.

Pickup occurs with ~15% probability every 120s (average: one event every ~13 minutes) — now that
`Op.general` has a real effect on the breaker, this is deliberately infrequent so a typical agent
evaluation run isn't confounded by an unrelated auto-trip racing the agent's own actions.

## Dynamic Simulation

- **Measurements** update every 2 seconds with realistic random values around nominal (50 Hz, ~1000 W, ~400 V). Power and current drop to near zero when the breaker is open, and rise to ~7x nominal while PTOC has picked up.

## REST API

A lightweight HTTP server runs alongside the MMS server to support test lifecycle management.

### `GET /status`

Returns the current emulator state.

```bash
curl http://localhost:8080/status
```

```json
{
  "breakerClosed": true,
  "ptocStart": false,
  "ptocOperate": false,
  "frequencyHz": 50.02,
  "totalPowerW": 1023.5,
  "currentA": {"phsA": 100.1, "phsB": 99.3, "phsC": 101.2},
  "voltageV": {"phsAB": 400.1, "phsBC": 401.0, "phsCA": 399.5}
}
```

### `POST /reset`

Resets the emulator to its initial state:
- Cancels any in-flight protection fault cycle (pending PTOC operate/clear)
- Closes the circuit breaker (`XCBR1.Pos.stVal` → 2/closed)
- Clears PTOC indicators (`Str.general`, `Op.general` → false)

```bash
curl -X POST http://localhost:8080/reset
# {"status":"ok"}
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `IEC61850_PORT` | `102` | MMS TCP port |
| `REST_PORT` | `8080` | REST API port |

## Running

```bash
docker run --rm -p 102:102 -p 8080:8080 \
  ghcr.io/felixhertweck/ocelot-protection-relay-emulator:main
```

Or via Docker Compose:

```bash
docker compose up
```

## Building

```bash
mvn package
```

## Breaking Changes

**Control path changed** (previously the emulator accepted direct control on `XCBR1.Pos`, which
does not match the physical SIPROTEC 5):

- Circuit breaker control **moved from `XCBR1.Pos` (`ctlModel=1`, direct-with-normal-security) to
  `CSWI1.Pos` (`ctlModel=2`, sbo-with-normal-security, requires Select before Operate)** — matching
  the real device (`config/phase-2a/proxy-config.yml`, `config/phase-2a/reset.py`). `XCBR1.Pos` is
  now status-only and rejects operate attempts.
- PTOC `Op.general` now trips the breaker instead of being purely cosmetic; pickup now elevates
  the simulated current instead of firing independently of it.
- The REST `/status` JSON is unchanged.
