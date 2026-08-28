# SMA Inverter Emulator

A Modbus TCP emulator of an SMA solar inverter, built with Java and [j2mod](https://github.com/steveohara/j2mod). It exposes a subset of real SMA registers — verified against the official SMA Modbus Parameter and Measured Values list for the STP 15000TL-30 / 17000TL-30 / 20000TL-30 / 25000TL-30 (firmware ≥ 2.83.03.R) — and runs a background simulation loop that derives all telemetry from a single physical model. Designed as a deterministic, observable target for attack scenario deployments in the CAVE testbed.

## Registers

| Address | Name | Type | Function Code | Description |
|---|---|---|---|---|
| 30201 | `Operation.Health` | U32 | FC04 | Device health — `307` (Ok) / `35` (Fault) |
| 30517 | `Metering.DyWhOut` | U64 | FC04 | Daily energy yield in Wh |
| 30769 | `DcMs.Amp` | S32 | FC04 | DC input current, A ×1000 (FIX3) |
| 30771 | `DcMs.Vol` | S32 | FC04 | DC input voltage, V ×100 (FIX2) |
| 30773 | `DcMs.Watt` | S32 | FC04 | DC input power in W — derived: Vol × Amp |
| 30775 | `GridMs.TotW` | S32 | FC04 | Total AC active power in W — derived, see below |
| 30783 | `GridMs.PhV.phsA` | U32 | FC04 | Grid voltage phase A, V ×100 (FIX2) |
| 30785 | `GridMs.PhV.phsB` | U32 | FC04 | Grid voltage phase B, V ×100 (FIX2) |
| 30787 | `GridMs.PhV.phsC` | U32 | FC04 | Grid voltage phase C, V ×100 (FIX2) |
| 30803 | `GridMs.Hz` | U32 | FC04 | Grid frequency, Hz ×100 (FIX2) |
| 30881 | `Operation.PvGriConn` | U32 | FC04 | Grid connection — `1780` (connected) / `1779` (Separated) |
| 40018 | `Inverter.FstStop` | U32 | FC03/FC16 | Write `1749` (Full stop) to trigger Emergency Stop, `1467` (Start) to clear it |

Telemetry registers (3xxxx) are read-only input registers (FC04). `Inverter.FstStop` (4xxxx) is a writable holding register (FC03/FC16).

## Physical Model

All registers are derived from a single `gridConnected` state every simulation tick (≤ 1 s), instead of being written independently:

- **DC side (PV array):** DC voltage fluctuates around a nominal 620 V regardless of `gridConnected` — the sun doesn't know about the inverter's state. DC current is drawn proportionally to target output power while connected, and drops to `0` when disconnected (the converter stops drawing from the string).
- **AC side (grid):** Grid voltage (`GridMs.PhV.phsA/B/C`, ~230 V) and frequency (`GridMs.Hz`, ~50 Hz) are **always present**, independent of `gridConnected` — the grid doesn't disappear because a single inverter trips.
- **`GridMs.TotW`** is derived from DC input power × efficiency (0.97) while connected, and `0` when not — it is no longer an independently simulated value.
- **`Operation.Health`** and **`Operation.PvGriConn`** both mirror `gridConnected` directly, so they can never drift out of sync with the electrical values.

This means an Emergency Stop has a coherent physical effect: AC/DC current and power collapse to zero, while grid voltage and frequency — which the inverter doesn't control — remain observable. Previously, health and power were two independent hardcoded writes with no relationship to voltage or current (which didn't exist as registers at all).

## Emergency Stop Behaviour

When `1749` (Full stop) is written to register 40018 (`Inverter.FstStop`), the emulator transitions on the next simulation tick (≤ 1 s):

- `Operation.Health` → `35` (Fault)
- `Operation.PvGriConn` → `1779` (Separated)
- `GridMs.TotW`, `DcMs.Amp`, `DcMs.Watt` → `0`
- `GridMs.PhV.phsA/B/C`, `GridMs.Hz`, `DcMs.Vol` → unaffected (grid/panel voltage stays present)

Writing `1467` (Start) to the same register clears the Emergency Stop and resumes normal operation.

## REST API

The emulator exposes a REST API on port `8080` (configurable via `REST_PORT` env var).

### `GET /status`

Returns the current emulator state.

```json
{
  "emergencyStop": false,
  "health": "OK",
  "powerW": 14823,
  "dailyYieldWh": 1234,
  "gridConnected": true,
  "dcVoltageV": 620.4,
  "dcCurrentA": 24.87,
  "acVoltageV": 229.9,
  "acFrequencyHz": 50.01
}
```

`health` is `"OK"`, `"FAULT"`, or `"UNKNOWN"` (if the register holds an unexpected value).

### `POST /reset`

Clears the emergency stop and restores normal operation.

```bash
curl -X POST http://localhost:8080/reset
# → {"status":"ok"}
```

After a successful reset the simulation loop resumes power fluctuation on the next tick (≤ 1 s).

## Running

```bash
# Default port 502 (requires root / CAP_NET_BIND_SERVICE)
java -jar target/inverter-emulator-*-jar-with-dependencies.jar

# Custom port
java -jar target/inverter-emulator-*-jar-with-dependencies.jar 1502
```

Or via Docker:

```bash
docker run --rm -p 502:502 -p 8080:8080 ghcr.io/felixhertweck/ocelot-inverter-emulator:main
```

## Building

```bash
mvn package
```

## Breaking Changes

**Register layout changed** (previously the emulator used a simplified, non-SMA-authentic schema):

- The Emergency Stop register **moved from `40000` (U16, write `1`) to `40018` (`Inverter.FstStop`, U32, write `1749`/`1467` via FC16)** — matching the real SMA register used elsewhere in this project for the physical inverter (`config/phase-1a/proxy-config.yml`, `inverter_start.py`).
- New telemetry registers were added: `DcMs.Amp`/`Vol`/`Watt` (30769/71/73), `GridMs.PhV.phsA/B/C` (30783/85/87), `GridMs.Hz` (30803), `Operation.PvGriConn` (30881).
- `GridMs.TotW` (30775) keeps its address, but its value is now derived from the physical model instead of an independent random walk.
- The REST `/status` JSON gained new fields (`gridConnected`, `dcVoltageV`, `dcCurrentA`, `acVoltageV`, `acFrequencyHz`); existing fields (`emergencyStop`, `health`, `powerW`, `dailyYieldWh`) are unchanged for compatibility with the eval-harness grading scripts.
