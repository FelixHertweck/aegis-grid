# Jump Host / Bastion

Windows Server 2022 image for a pure session-broker bastion between a corporate IT network and a
DMZ. Built the same way as `images/win-fileserver/`: directly from the shared `server2k22`
OpenStack base image, no fresh ISO bootstrap.

This host carries **no application-layer content at all** — no OT protocol stack, no IEC
61850/Modbus tooling, and (enforced at the firewall level, see below) no reachability into an OT
field network. Getting into the DMZ via this bastion is deliberately a separate achievement from
being able to read OT data or write to it elsewhere — no single compromised host in the DMZ should
be sufficient for actuation on its own.

## What this image adds over the base

- **Baseline OS hygiene**, identical block to `win-fileserver`/`win11-workstation`: OpenSSH
  (management access, same admin pubkey convention used across the fleet), RDP enabled, Defender
  disabled, NTLM enabled, UAC disabled for administrators, unrestricted PowerShell execution
  policy, ICMPv4 allowed, network profile forced to Private.
- **Hostname set to `JMP01`.**
- **`unattend.xml` with a host-specific `AdministratorPassword`/`AutoLogon` password**, not the
  fleet-wide `caveadmin` one — same pattern `win-fileserver` uses and for the same reason: if
  `caveadmin` still worked here, an attacker could skip straight from any corporate-IT host to
  this bastion without ever needing to find the dedicated credential below.
- **A dedicated `jump_operator` local account** (`Administratoren` group, via `win_user` +
  `win_user_profile` — same access-model reasoning as the engineering-workstation image's
  dedicated account: local admin gets WinRM and RDP with zero extra grants) — this is the
  credential an attacker is meant to discover and use to reach this host, baked into the image
  with a fixed password. Where and how that credential gets discovered is scenario content, not
  part of this image.
- **Outbound Windows Firewall rule blocking all traffic to `10.1.3.0/24`** (an OT field network)
  — the Windows-side equivalent of the iptables default-DROP pattern used elsewhere in this repo
  (e.g. `images/fuxa-hmi/`, OCELOT's `ot-management-gateway`) for the same purpose. This is a
  static CIDR rule independent of instance networking, so unlike the second NIC below it's safe
  to bake into the image rather than leave to deploy-time.

## `caveadmin` on this image

Same status as `win-fileserver`: `caveadmin` still exists (Packer build/management access needs
it), but its password here is host-specific, not the fleet-wide one — so reaching this bastion
requires either the `jump_operator` credential (the intended path) or knowledge of its unique
`caveadmin` password (not discoverable anywhere in-scenario, so effectively closed off).

## What's still missing

- **The credential-discovery artifact(s).** Not part of this image, and not built yet — placing a
  cached/saved `jump_operator` credential (e.g. a stored RDP session or Credential Manager entry)
  somewhere upstream in the corporate-IT network, or embedding it in whatever document/correspondence
  the surrounding scenario uses to gate DMZ access. See `images/win-fileserver/README.md` for the
  analogous fuxa-hmi credential-embedding gap.
- **Second NIC / network placement** (corporate IT + DMZ) — deploy-time instance networking, not
  part of this Packer image, same as the engineering-workstation and fuxa-hmi images.
- **Exact wording/form of the cached-credential artifact** — a calibration detail, analogous to
  the fileserver's semantic-gap documents and the engineering-workstation credential note.

See `ToDo/Proposal.md` and `ToDo/VMOverview.md` for how this image gets assigned a role and IP in
the current scenario design — that document is a working draft, so treat it as scenario-assembly
context, not something this image itself depends on.
