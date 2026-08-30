# Phase 1a: OpenHands vs. OT-Proxy

Deploys two VMs in a shared network. OpenHands attacks a Modbus TCP device through an OT security proxy that enforces configurable allow/deny rules.

| VM | Image | Role |
|---|---|---|
| `ot-proxy` | `ot-proxy:latest` | Modbus TCP security proxy (defender) |
| `openhands` | `openhands:latest` | AI-driven autonomous agent (attacker) |

The OT proxy sits between OpenHands and the upstream Modbus target. It forwards permitted register operations and blocks the rest according to `proxy-config.yml`.

## Prerequisites

Complete **steps 1 and 2** of the [main README](../../README.md) first — OpenStack and `cave-infrastructure-docker` must be set up, and **both** the `ot-proxy` and `openhands` images must be built and uploaded to OpenStack before continuing.

The upstream Modbus TCP device (e.g. an Aloha Water Treatment simulator or physical OT hardware) must also be reachable at the address configured in `proxy-config.yml`.

## 1. Place the Deployment Config

Clone the ocelot repository into `/tmp`, then copy the `config/` tree into the `configs/` directory of your `cave-infrastructure-docker` checkout:

```bash
git clone https://github.com/FelixHertweck/ocelot.git /tmp/ocelot
cp -r /tmp/ocelot/config/* ./configs/
```

## 2. Configure the Proxy

> **Important:** Before deploying, set `proxy.upstream.host` to the IP address or hostname of your
> upstream Modbus TCP device. The file ships with `modbus-device` as a placeholder — traffic will
> not reach your device until this is updated.

Edit `configs/phase-1a/proxy-config.yml` and set the upstream Modbus target that the proxy should forward to:

```bash
nano configs/phase-1a/proxy-config.yml
```

| Field | Description |
|---|---|
| `proxy.listen.host` / `proxy.listen.port` | Address the proxy binds to on the VM (default: `0.0.0.0:5020`) |
| `proxy.upstream.host` | IP of the upstream Modbus TCP device |
| `proxy.upstream.port` | Port of the upstream device (default: `502`) |
| `rules.default_action` | Fallback for unmatched requests (`ALLOW` or `DENY`) |
| `rules.registers` | Per-register allow/deny rules |

Optionally edit `configs/phase-1a/ot-proxy.env` to override the proxy VM's hostname:

```bash
nano configs/phase-1a/ot-proxy.env
```

## 3. Configure the Task

Edit `configs/shared/openhands/openhands.env` and fill in your LLM credentials and the task prompt:

```bash
nano configs/shared/openhands/openhands.env
```

| Variable | Description |
|---|---|
| `LLM_MODEL` | Model in provider-prefix format (e.g. `anthropic/claude-opus-4-7`) |
| `LLM_API_KEY` | API key for the LLM provider |
| `OPENHANDS_TASK` | The prompt OpenHands executes headlessly on VM start |

## 4. Deploy Infrastructure

Run the interactive wrapper from your `cave-infrastructure-docker` directory:

```bash
docker compose run --rm cave /cave/deploy-wrapper.sh
```

To deploy non-interactively with a custom lab prefix. Pick the config for the hinting mode you
want: `-cumulative` (pre-staged prompt hints only) or `-adaptive-oracle` (adds the Oracle hint
service VM on `10.1.1.11`, reached over MCP) — see [Methodology.md → Instruments](../../docs/evaluation/Methodology.md#instruments):

```bash
# cumulative-hinting
docker compose run --rm cave /cave/deploy-wrapper.sh phase-1a/phase-1a-cumulative --lab-prefix ocelot-p1a

# ...or adaptive-hinting
docker compose run --rm cave /cave/deploy-wrapper.sh phase-1a/phase-1a-adaptive-oracle --lab-prefix ocelot-p1a

# append --wg for WireGuard instead of OpenVPN
```

Both VMs are fully configured automatically during deployment via `postCommand`:

- **ot-proxy**: reads `~/.env` and `proxy-config.yml` and starts the proxy service
- **openhands**: runs `run.sh` which reads `OPENHANDS_TASK` from `~/.env` and starts the agent headlessly — no interactive session required

## 5. Connect and Access

After deployment, retrieve the OpenVPN client config from your `cave-infrastructure-docker` output directory and connect from your local machine:

```bash
sudo openvpn --config out/<your-prefix>/openvpn/admins/admin1.ovpn
```

**OpenHands runs fully automatically** — no further action required. You can follow progress via the dashboard:

| Service | URL |
|---|---|
| OpenHands dashboard | http://10.1.1.20:3000/ |
| OT Proxy (Modbus TCP) | 10.1.1.15:5020 |

## 6. Test the Proxy

`test-proxy.py` reads all allowed registers from the proxy and optionally verifies that the default DENY rule rejects unlisted registers.

> **Note:** The register list in `test-proxy.py` is tailored to the **SMA Tripower inverter** described in `proxy-config.yml`. If you use a different device or a different `proxy-config.yml`, update the `ALLOWED_REGISTERS` list accordingly.

**On the `ot-proxy` VM** (SSH in or run locally on the VM):

```bash
python3 -m venv .venv
source .venv/bin/activate
pip install pymodbus
python test-proxy.py --host localhost --port 5020
```

**From your local machine** (VPN must be connected):

```bash
source .venv/bin/activate
python test-proxy.py --host 10.1.1.15 --port 5020
```

**Also verify the DENY rule:**

```bash
python test-proxy.py --host 10.1.1.15 --port 5020 --test-blocked
```

If the upstream Modbus device uses a slave ID other than `3`, pass `--slave <id>`.

## 7. Assign a MAC Address to the OT-Proxy VM

By default the `ot-proxy` VM is deployed without a fixed MAC address (`macAddress: null` in the phase config). Some physical OT networks require a known MAC address — e.g. for DHCP reservations or switch-port ACLs.

To assign a fixed MAC address, edit whichever config you deploy (`configs/phase-1a/phase-1a-cumulative.json5` and/or `-adaptive-oracle.json5`) and replace `null` with the desired address:

```json5
{
  name: "ot-proxy",
  ...
  macAddress: "52:54:00:ab:cd:ef",   // set your desired MAC here
  ...
}
```

The value is passed directly to the OpenStack VM, so any unicast MAC address in the format `XX:XX:XX:XX:XX:XX` is valid.
