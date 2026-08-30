# Phase 1c: OpenHands vs. SMA Inverter Emulator

Deploys two VMs in a shared network. OpenHands attacks a software-emulated SMA solar inverter that exposes unauthenticated Modbus TCP.

| VM | Image | Role |
|---|---|---|
| `inverter-emulator` | `inverter-emulator:latest` | SMA solar inverter emulator (target) |
| `openhands` | `openhands:latest` | AI-driven autonomous agent (attacker) |

The emulator runs a background simulation loop generating dynamic telemetry (fluctuating AC power, DC/AC voltage and current, accumulating daily yield) and implements an Emergency Stop via holding register 40018 (`Inverter.FstStop`).

## Prerequisites

Complete **steps 1 and 2** of the [main README](../../README.md) first — OpenStack and `cave-infrastructure-docker` must be set up, and **both** the `inverter-emulator` and `openhands` images must be built and uploaded to OpenStack before continuing.

## 1. Place the Deployment Config

Clone the ocelot repository into `/tmp`, then copy the `config/` tree into the `configs/` directory of your `cave-infrastructure-docker` checkout:

```bash
git clone https://github.com/FelixHertweck/ocelot.git /tmp/ocelot
cp -r /tmp/ocelot/config/* ./configs/
```

## 2. Configure the Task

Edit `configs/shared/openhands/openhands.env` and fill in your LLM credentials and the task prompt:

```bash
nano configs/shared/openhands/openhands.env
```

| Variable | Description |
|---|---|
| `LLM_MODEL` | Model in provider-prefix format (e.g. `anthropic/claude-opus-4-7`) |
| `LLM_API_KEY` | API key for the LLM provider |
| `OPENHANDS_TASK` | The prompt OpenHands executes headlessly on VM start |

Use one of the prompts from [`docs/prompts/Phase-1c.md`](../../docs/prompts/Phase-1c.md) as the task value.

## 3. Deploy Infrastructure

Run the interactive wrapper from your `cave-infrastructure-docker` directory:

```bash
docker compose run --rm cave /cave/deploy-wrapper.sh
```

To deploy non-interactively with a custom lab prefix. Pick the config for the hinting mode you
want: `-cumulative` (pre-staged prompt hints only) or `-adaptive-oracle` (adds the Oracle hint
service VM on `10.1.1.11`, reached over MCP) — see [Methodology.md → Instruments](../../docs/evaluation/Methodology.md#instruments):

```bash
# cumulative-hinting
docker compose run --rm cave /cave/deploy-wrapper.sh phase-1c/phase-1c-cumulative --lab-prefix ocelot-p1c

# ...or adaptive-hinting
docker compose run --rm cave /cave/deploy-wrapper.sh phase-1c/phase-1c-adaptive-oracle --lab-prefix ocelot-p1c

# append --wg for WireGuard instead of OpenVPN
```

Both VMs are fully configured automatically during deployment via `postCommand`:

- **inverter-emulator**: starts the Docker container exposing Modbus TCP on port 502
- **openhands**: runs `run.sh` which reads `OPENHANDS_TASK` from `~/.env` and starts the agent headlessly — no interactive session required

## 4. Connect and Access

After deployment, retrieve the OpenVPN client config from your `cave-infrastructure-docker` output directory and connect from your local machine:

```bash
sudo openvpn --config out/<your-prefix>/openvpn/admins/admin1.ovpn
```

**OpenHands runs fully automatically** — no further action required. You can follow progress via the dashboard:

| Service | URL / Address |
|---|---|
| OpenHands dashboard | http://10.1.1.20:3000/ |
| Inverter Emulator (Modbus TCP) | 10.1.1.10:502 |
