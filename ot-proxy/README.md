# OT Proxy

A protocol-agnostic proxy for OT devices that enforces access rules via a YAML config. Supports
**Modbus TCP** and **IEC 61850 (MMS)**, selected by a top-level `protocol:` classifier. The shared
rule engine keys on a generic *target* — a Modbus register address or an IEC 61850 object reference
— so the same whitelist / value-range / rate-limit rules apply to both.

```
Client → [Proxy Listener] → [Pipeline] → [Upstream Connector] → OT Device
                                 ↑
                           [Rule Engine]
                                 ↑
                           [YAML Config]
```

- **Modbus** is a *transparent byte-level* proxy: it parses raw MBAP+PDU frames and forwards/blocks
  the bytes.
- **IEC 61850** is a *terminating application-layer* proxy (built on `com.beanit:iec61850bean`): it
  acts as an MMS client to the real IED and an MMS server to downstream clients, mirroring the IED's
  data model and intercepting control operations at the service layer. MMS cannot be byte-proxied.

## Requirements

- Java 21+
- Maven 3.9+
- Docker (optional)

## Build

```bash
mvn package
```

The fat JAR lands at `target/otproxy-<version>-jar-with-dependencies.jar`.

To skip tests:

```bash
mvn package -DskipTests
```

## Run

```bash
java -jar target/otproxy-*-jar-with-dependencies.jar src/main/resources/proxy-config.yml
```

Without an argument the proxy looks for `proxy-config.yml` in the working directory.

## Test

```bash
mvn test
```

## Configuration

Edit `src/main/resources/proxy-config.yml` (or supply your own file at startup):

```yaml
proxy:
  listen:
    host: 0.0.0.0
    port: 5020          # port the proxy listens on
  upstream:
    host: 192.168.1.10  # real Modbus device
    port: 502

rules:
  default_action: DENY           # block everything not explicitly listed
  default_on_violation: MODBUS_EXCEPTION  # fallback action when none is set closer to the rule

  # Read/write fallback for registers without their own read/write block.
  default_rate_limit:
    write:
      max_requests: 1
      per_millis: 100
      on_violation: MODBUS_EXCEPTION
    read:
      max_requests: 10
      per_millis: 1000
      on_violation: MODBUS_EXCEPTION

  registers:
    - address: 100
      description: "Pump speed setpoint"
      allow_write: true
      value_range:
        min: 0
        max: 1500
      write:                  # overrides default_rate_limit.write for this register
        max_requests: 10
        per_millis: 60000     # sliding window in milliseconds
      read:                   # overrides default_rate_limit.read for this register
        max_requests: 50
        per_millis: 1000
      on_violation: MODBUS_EXCEPTION   # MODBUS_EXCEPTION | SILENT_DROP | DISCONNECT

    - address: 200
      description: "Emergency stop (read-only)"
      allow_write: false
      on_violation: DISCONNECT
```

## IEC 61850 (MMS) configuration

Set `protocol: iec61850` and list protected objects under `rules.objects`, keyed by their object
reference (the controllable data object, e.g. `RelayIEDPROT/XCBR1.Pos`, without the `.Oper.ctlVal`
suffix):

```yaml
protocol: iec61850

proxy:
  listen:
    host: 0.0.0.0
    port: 10102         # MMS port the proxy listens on
  upstream:
    host: 192.168.1.20  # the real IED
    port: 102

rules:
  default_action: DENY
  default_on_violation: REJECT
  default_rate_limit:
    write:
      max_requests: 1
      per_millis: 1000
      on_violation: REJECT

  objects:
    - reference: "RelayIEDPROT/XCBR1.Pos"   # circuit breaker control
      description: "Circuit breaker"
      allow_write: false                     # block all operate commands
      allow_read: true
      on_violation: REJECT
    - reference: "RelayIEDPROT/MMXU1.TotW"   # measurement: readable, not controllable
      allow_read: true
      allow_write: false
```

The proxy mirrors the upstream IED's data model on startup (`retrieveModel`) and keeps status/
measurement values fresh by polling. A control Operate from a downstream client is intercepted, run
through the rule engine (`allow_write`, `value_range`, write rate limit), and — if allowed —
forwarded to the IED via the IEC control service (direct-operate, or Select-before-Operate when the
object's `ctlModel` is SBO). Denied operations return an MMS `ServiceError`.

### IEC 61850 limitations

`iec61850bean`'s server exposes no read-interception hook, so two behaviours differ from Modbus:

- **No per-request read rate-limiting.** Reads are served from the mirrored model; `read:` limits
  apply only to Modbus.
- **Read allow-list is enforced by model pruning, at Logical-Node granularity.** When
  `default_action: DENY`, only the Logical Nodes referenced by an `allow_read` object rule (plus
  the mandatory system nodes `LLN0`/`LPHD`) are kept in the exposed model; everything else is
  absent and reads return object-not-found. There is no per-data-object read filtering — every
  object under a kept LN is readable. So if an `allow_read` list already names every
  data-carrying LN in the model (as the Phase 2a config does), pruning removes nothing and the
  downstream client sees the full topology; the allow-list only has a visible effect when the
  upstream model carries non-system LNs that no rule references.
- **Deny actions collapse to `REJECT`.** `SILENT_DROP` / `DISCONNECT` are not expressible per request
  on the MMS server side, so every IEC denial returns a `ServiceError`.

### Enhanced-security control normalization

`iec61850bean`'s server implements only the **normal-security** control state machine (`ctlModel`
`direct-with-normal-security` and `sbo-with-normal-security`). To front an IED whose controls use
**enhanced security** (`ctlModel` 3/4 — e.g. the physical SIPROTEC 5's `CSWI1.Pos`), the proxy
rewrites the model it exposes downstream (`Iec61850ControlModelNormalizer`):

- `ctlModel` `3 → 1`, `4 → 2`.
- For `4 → 2`, the control object is also made structurally consistent: a plain `SBO` attribute is
  added (the server starts a Select only when the client reads a child literally named `SBO`, which
  an enhanced-security model lacks — it has `SBOw`), and `SBOw` / `Oper.operTm` are removed.

This is **downstream-only**. The upstream model is untouched, so the forwarder still drives the
physical IED with its real `ctlModel` and the correct `SelectWithValue` + `Operate` sequence.

Not conveyed downstream (accepted limitation):

- The enhanced-security **`LastApplError` AddCause** — `iec61850bean`'s *client* discards the
  `LastApplError` report, so the proxy never sees the IED's structured failure reason. A failed
  forward still returns the IED's MMS error code; the descriptive string is proxy-log only (MMS
  carries just the numeric `DataAccessError` downstream).
- The asynchronous **`CommandTermination`** — the normal-security server does not emit it.

Consequence for reconnaissance: a client sees `CSWI1.Pos` as `sbo-with-normal-security`, not the
physical device's `sbo-with-enhanced-security`. The `protection-relay-emulator` deliberately mirrors
this (`ctlModel` 2) so agent-facing behaviour is identical whether the upstream is the emulator or
the real IED.

### Rate limiting

Every rate limit has the same shape — `max_requests` per sliding window of
`per_millis` milliseconds, plus an optional `on_violation` — and is tracked
**per register address**, separately for reads and writes:

- A register's own `read` / `write` block applies first.
- `rules.default_rate_limit.read` / `.write` is the fallback for registers
  without their own block.

Omit a block to leave that direction unthrottled.

### Violation action resolution

`on_violation` is one of `MODBUS_EXCEPTION` | `SILENT_DROP` | `DISCONNECT`.
**Register-level settings always override global defaults.** The exact chain depends
on whether the register has its own rate-limit block:

| Scenario | Resolution order |
|---|---|
| Register has its own `read`/`write` block | rate-limit `on_violation` → register `on_violation` → `default_on_violation` → `MODBUS_EXCEPTION` |
| Register uses `default_rate_limit` | register `on_violation` → `default_rate_limit` `on_violation` → `default_on_violation` → `MODBUS_EXCEPTION` |
| Unlisted register (default action) | `default_on_violation` → `MODBUS_EXCEPTION` |

### Violation actions

| Action | Behaviour |
|---|---|
| `REJECT` | Rejects the request at the protocol level (Modbus exception frame / IEC MMS `ServiceError`). `MODBUS_EXCEPTION` is an accepted alias. |
| `SILENT_DROP` | Drops the request silently (Modbus only; IEC collapses to `REJECT`) |
| `DISCONNECT` | Closes the client connection (Modbus only; IEC collapses to `REJECT`) |

## Docker

**Build image** (JAR muss vorher gebaut sein):

```bash
mvn package -DskipTests
docker build -t ot-proxy .
```

**Run with the bundled default config:**

```bash
docker run -p 5020:5020 ot-proxy
```

**Run with a custom config mounted from the host:**

```bash
docker run -p 5020:5020 \
  -v /path/to/your/proxy-config.yml:/app/proxy-config.yml \
  ot-proxy
```
