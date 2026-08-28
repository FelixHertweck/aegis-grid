# Phase 0: OpenHands vs. Aloha Water Treatment

Deploys two VMs in a shared network:

| VM | Image | Role |
|---|---|---|
| `aloha-water-treatment` | `aloha-water-treatment:latest` | Modbus TCP water treatment plant simulator (target) |
| `openhands` | `openhands:latest` | AI-driven autonomous agent (attacker) |

## Prerequisites

Complete **steps 1 and 2** of the [main README](../../README.md) first — OpenStack and `cave-infrastructure-docker` must be set up, and **both** the `aloha-water-treatment` and `openhands` images must be built and uploaded to OpenStack before continuing.

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

## 3. Deploy Infrastructure

Run the interactive wrapper from your `cave-infrastructure-docker` directory:

```bash
docker compose run --rm cave /cave/deploy-wrapper.sh
```

To deploy non-interactively with a custom lab prefix:

```bash
# OpenVPN
docker compose run --rm cave /cave/deploy-wrapper.sh phase-0/openhands --lab-prefix ocelot-p0oh

# WireGuard
docker compose run --rm cave /cave/deploy-wrapper.sh phase-0/openhands --wg --lab-prefix ocelot-p0oh
```

Both VMs are fully configured automatically during deployment via `postCommand`:

- **aloha-water-treatment**: restarts `aloha-plc.service` and `aloha-hmi.service`
- **openhands**: runs `run.sh` which reads `OPENHANDS_TASK` from `~/.env` and starts the agent headlessly — no interactive session required

## 4. Connect and Access

After deployment, retrieve the OpenVPN client config from your `cave-infrastructure-docker` output directory and connect from your local machine:

```bash
sudo openvpn --config out/<your-prefix>/openvpn/admins/admin1.ovpn
```

**OpenHands runs fully automatically** — no further action required. You can follow progress via the dashboard:

| Service | URL |
|---|---|
| OpenHands dashboard | http://10.1.1.20:3000/ |
| Aloha Water Treatment HMI | http://10.1.1.10:8090/ |
