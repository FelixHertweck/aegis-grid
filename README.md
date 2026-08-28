# OCELOT: OT Cyber Exploitation via LLM Orchestration Testbed

This project evaluates the capability of AI-driven agents to execute multi-step attacks against Industrial Control Systems (ICS). It provides an Infrastructure-as-Code (IaC) testbed that deploys attack scenarios against standard base images, targeting both physical OT hardware and software emulations.

Scenarios use [OpenHands](https://github.com/All-Hands-AI/OpenHands) as the autonomous attacker agent. The testbed is protocol-agnostic: current scenarios cover Modbus TCP (Phases 0–1) and IEC 61850 MMS (Phase 2).

**Important:** This project is designed for **OpenStack** environments and utilizes the [**CAVE**](https://gitlab.opencode.de/BSI-Bund/cave) infrastructure for deployment.

**Note:** This is a research project. Detailed documentation, including Architectural Decision Records (ADRs) and the project proposal, can be found in the `docs/` folder.

## Project Structure

* **`.github/`**: GitHub Actions workflows for CI/CD.
* **`config/`**: Deployment configurations for each test scenario (one subdirectory per phase).
* **`docs/`**: Project documentation.
  * **`adrs/`**: Architecture Decision Records.
  * **`evaluation/`**: Evaluation criteria and phase result logs.
  * **`prompts/`**: Reference prompts for each phase.
* **`eval-harness/`**: Automated evaluation harness — deploys a scenario, runs OpenHands against a set of prompts, and generates an LLM-scored evaluation document. See [eval-harness/README.md](eval-harness/README.md).
* **`images/`**: Packer configurations to build the VM images.
  * **`aloha-water-treatment/`**: Modbus TCP water treatment plant simulator.
  * **`decepticon/`**: Decepticon red-team agent image.
  * **`inverter-emulator/`**: SMA solar inverter emulator image.
  * **`openhands/`**: OpenHands AI agent image.
  * **`oracle/`**: Oracle hint-service image (oracle wrapper container, mounted-JSON content). See [images/oracle/README.md](images/oracle/README.md).
  * **`ot-management-gateway/`**: OT management gateway image (HTTP admin + SSH).
  * **`ot-proxy/`**: Modbus TCP and IEC 61850 security proxy image.
  * **`protection-relay-emulator/`**: IEC 61850 protection relay emulator image.
  * **`win-domain-controller/`**: Windows Server 2022 Active Directory domain controller image (Scenario 3.3).
* **`inverter-emulator/`**: Source code for the SMA solar inverter emulator (Java/Maven).
* **`openhands_exporter/`**: Script to convert OpenHands conversation export ZIPs into readable Markdown files.
* **`oracle/`**: Source code for the Oracle hint service (Python/FastMCP) — on-demand, per-category, tiered hints for the attacker LLM. See [oracle/README.md](oracle/README.md).
* **`ot-management-gateway/`**: Source code for the OT management gateway (nginx + web app).
* **`ot-proxy/`**: Source code for the Modbus TCP / IEC 61850 security proxy (Java/Maven).
* **`protection-relay-emulator/`**: Source code for the IEC 61850 protection relay emulator (Java/Maven).

## Getting Started

Deployment is managed using the [CAVE Infrastructure Docker Wrapper](https://github.com/FelixHertweck/cave-infrastructure-docker). Ensure you have a running OpenStack environment and the necessary credentials. For more details see [README of CAVE Infrastructure Docker](https://github.com/FelixHertweck/cave-infrastructure-docker/blob/main/README.md).

### 1. Prepare Environment
Ensure you have a running OpenStack environment (e.g., by installing MicroStack). 
For full details on these steps, see the [CAVE Infrastructure Docker README](https://github.com/FelixHertweck/cave-infrastructure-docker/blob/main/README.md).

```bash
git clone https://github.com/FelixHertweck/cave-infrastructure-docker.git
cd cave-infrastructure-docker
cp .env.sample .env
# /!\ Configure .env and place your SSH keys in ./ssh-keys/ before continuing /!\

# Start the docker container in the background
docker compose up -d

# Run the host initialization script (sets up NAT, flavors, VPN users, etc.)
sudo ./scripts/post-openstack-init.sh

# Run the pre-build setup (downloads base images and sets up security groups)
docker compose run --rm cave /cave/pre-build-images.sh
```

### 2. Build the Base Images
Build the OCELOT base images using Packer by providing this repository's URL. Make sure to build **all** required images during this interactive step.

```bash
docker compose run --rm cave /cave/build-images.sh https://github.com/FelixHertweck/ocelot.git
```

### 3. Deploy Infrastructure

For deployment guides see the specific scenario documentation:

| Phase | Scenario | Protocol |
|---|---|---|
| [Phase 0 — Decepticon](config/phase-0/Decepticon.md) | Decepticon vs. Aloha Water Treatment | Modbus TCP |
| [Phase 0 — OpenHands](config/phase-0/Openhands.md) | OpenHands vs. Aloha Water Treatment | Modbus TCP |
| [Phase 1a](config/phase-1a/README.md) | OpenHands vs. OT-Proxy | Modbus TCP |
| [Phase 1b](config/phase-1b/README.md) | OpenHands: OT Gateway Exploitation and Register Mapping | Modbus TCP |
| [Phase 1c](config/phase-1c/README.md) | OpenHands vs. SMA Inverter Emulator | Modbus TCP |
| [Phase 1d](config/phase-1d/README.md) | OpenHands: Two-Stage Gateway Attack on SMA Inverter Emulator | Modbus TCP |
| [Phase 2a](config/phase-2a/README.md) | OpenHands vs. OT-Proxy (IEC 61850) | IEC 61850 MMS |
| [Phase 2b](config/phase-2b/README.md) | OpenHands vs. Protection Relay Emulator (IEC 61850) | IEC 61850 MMS |

### 4. Automated Evaluation

Instead of running a scenario manually, the [`eval-harness/`](eval-harness/README.md) can deploy the lab via CAVE, run OpenHands against a full sweep of knowledge-gradient prompts, collect all conversation/device artifacts, and generate a structured, LLM-scored `evaluation.md` — all in one command:

```bash
cd eval-harness/docker
cp .env.example .env
$EDITOR .env   # set EVAL_LLM_API_KEY, CAVE_WRAPPER_DIR

docker compose run --rm eval
```

See [eval-harness/README.md](eval-harness/README.md) for configuration, prompt file format, and troubleshooting.
