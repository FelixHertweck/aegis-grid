# fuxa-hmi

Ubuntu 24.04 image for an OT recon / tag-database layer meant to sit in a DMZ. Runs
[FUXA](https://github.com/frangoteam/FUXA) (`frangoteam/fuxa`) via Docker Compose — a real,
open-source web-based SCADA/HMI/dashboard product, not a bespoke stand-in. This host is read-only:
an attacker can browse FUXA's tag database to resolve which of the testbed's genuine, protocol-
identical feeder relays is the actual target — a semantic mapping IEC 61850's own logical-node
structure can't supply on its own — but nothing on this host can issue an Operate command; that
capability belongs exclusively to a separate engineering-workstation host.

The `hmi/` project in this workspace already has a working FUXA deployment (single-line substation
diagram, Docker Compose, `mysettings.json`) that this image reuses almost verbatim. The
scenario-specific copy of that diagram lives at `config/shared/hmi/scenario3-hmi.svg` in this
repo — the deploy-time asset actually used here, distinct from the `hmi/` project's own working
copy.

## What this image does

- Installs Docker (same boilerplate as every other Linux-based image in this repo).
- Ships `docker-compose.yml` (`frangoteam/fuxa:latest`, port 1881) and a generic
  `mysettings.json` (language/daq-store/token defaults — no scenario-specific secrets).
- Pre-pulls the FUXA image so `run.sh` doesn't block on a cold download.
- `run.sh` starts the container and waits for the web UI to answer at `http://localhost:1881/`.

## What's still open (not in this image)

Deliberately **not** baked into this generic, role-agnostic image — they're deploy-time content,
same pattern as the engineering-workstation image's `engineering-files/` and the fileserver
image's semantic-gap artifacts:

- **Fixed, non-default admin credential.** FUXA ships with a seed password on first boot (see
  `hmi/config/admin-credentials.txt` for how the existing `hmi/` deployment set the FUXA login via
  the `/api/signin` + settings API). This image does not yet automate repointing it to a specific,
  deliberately configured value — someone (or a follow-up script) has to do that once, and the
  same fixed value then needs to be surfaced somewhere else in the scenario for an attacker to
  find.
- **Dashboard / tag content.** `config/shared/hmi/scenario3-hmi.svg` already shows the three
  feeders and their connected loads, but with static values and no live tag bindings. Wiring
  FUXA's actual tag database to these descriptions — so browsing it resolves the intended target
  and surfaces which feeder is off-limits — is still open, as is adding realistic control-style
  widgets (breaker switches, setpoints) for visual fidelity. Per design decision, these must stay
  purely visual; no widget may be wired to a live write path, since this host's recon role and the
  engineering workstation's execution role must stay cleanly separated.
- **Dual-homed network configuration** (two NICs, into the DMZ and the OT field network) —
  deploy-time instance networking, not part of this Packer image, same as the
  engineering-workstation image's second NIC.
- **The deploy-time config step itself** — no scenario-deploy directory exists yet for this (see
  `ToDo/VM2-EngineeringWorkstation.md`'s "Still open" section for the equivalent gap on the
  engineering-workstation image). It will need to (a) set the fixed FUXA admin credential, (b)
  embed it somewhere an attacker can find it, and (c) upload the finished dashboard content onto
  this host.

See `ToDo/Proposal.md` and `ToDo/VMOverview.md` for how this image gets assigned a role and IP in
the current scenario design — that document is a working draft, so treat it as scenario-assembly
context, not something this image itself depends on.
