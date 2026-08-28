# Phase 0: Decepticon vs. Aloha Water Treatment

Deploys two VMs in a shared network:

| VM | Image | Role |
|---|---|---|
| `aloha-water-treatment` | `aloha-water-treatment:latest` | Modbus TCP water treatment plant simulator (target) |
| `decepticon` | `decepticon:latest` | AI-driven red team agent (attacker) |

## Prerequisites

Complete **steps 1 and 2** of the [main README](../../README.md) first — OpenStack and `cave-infrastructure-docker` must be set up, and **both** the `aloha-water-treatment` and `decepticon` images must be built and uploaded to OpenStack before continuing.

## 1. Place the Deployment Config

Clone the ocelot repository into `/tmp`, then copy the `config/` tree into the `configs/` directory of your `cave-infrastructure-docker` checkout:

```bash
git clone https://github.com/FelixHertweck/ocelot.git /tmp/ocelot
cp -r /tmp/ocelot/config/* ./configs/
```

## 2. Deploy Infrastructure

Run the interactive wrapper from your `cave-infrastructure-docker` directory:

```bash
docker compose run --rm cave /cave/deploy-wrapper.sh
```

To deploy non-interactively with a custom lab prefix:

```bash
# OpenVPN
docker compose run --rm cave /cave/deploy-wrapper.sh phase-0/decepticon --lab-prefix ocelot-p0

# WireGuard
docker compose run --rm cave /cave/deploy-wrapper.sh phase-0/decepticon --wg --lab-prefix ocelot-p0
```

Both VMs are fully configured automatically during deployment via `postCommand`:

- **aloha-water-treatment**: restarts `aloha-plc.service` and `aloha-hmi.service`
- **decepticon**: runs `set-targets.sh` (auto-detects the shared subnet, verifies Modbus/HMI reachability, patches `~/.decepticon/.env`) and then starts the agent via `decepticon-start`

## 3. Connect and Access

After deployment, retrieve the OpenVPN client config from your `cave-infrastructure-docker` output directory and connect from your local machine:

```bash
sudo openvpn --config out/<your-prefix>/openvpn/admins/admin1.ovpn
```

Once connected, open the Decepticon dashboard in your browser — the engagement is already created. **Open the page once** to trigger the agent, then **confirm the generated plan once** before it starts executing:

| Service | URL |
|---|---|
| Decepticon dashboard | http://10.1.1.20:3000/ |
| Aloha Water Treatment HMI | http://10.1.1.10:8090/ |
