# Domain Controller (VM7 — Scenario 3.3 only)

Windows Server 2022 image for `corp.local`'s domain controller, `DC01`. Built the same way as
`images/win-fileserver/` and `images/win-jump-host/`: directly from the shared `server2k22`
OpenStack base image, no fresh ISO bootstrap. Only used by Scenario 3.3 (Active Directory
Hardening) — Scenarios 3.1/3.2 have no AD at all.

## Two-phase design — this is not a single sysprep'd end state

Unlike every other Windows image in this repo, a domain controller **cannot** be fully baked
into a generalized image: Windows refuses `sysprep /generalize` on a host that has already been
promoted to a DC (`Install-ADDSForest`/`dcpromo` locks in machine-specific domain/SID state that
generalization is incompatible with). `images/win-fileserver/README.md` already flags the softer
version of this constraint for domain-*joining* a server image; promoting one to a DC is the same
problem, just non-negotiable rather than "easier to do live."

So this image is split into two phases, matching how `cave-images` builds its own domain
controllers (`day4/DC01`, `day5/01-dc` — both are deploy-time-only Ansible runs against a plain
golden image, never a dedicated Packer build):

1. **`image.pkr.hcl` + `playbook.yml` (Packer build, generalizable).** Baseline OS hygiene
   identical to `win-fileserver`/`win-jump-host`: OpenSSH (management access, same admin pubkey
   convention used across the fleet), RDP enabled, Defender disabled, NTLM enabled, UAC disabled
   for administrators, unrestricted PowerShell execution policy, ICMPv4 allowed, network profile
   forced to Private, hostname set to `DC01`. Also pre-installs the `AD-Domain-Services` Windows
   feature (binaries only, no promotion) so the actual forest install at deploy-time is faster —
   installing the feature itself doesn't touch domain/SID state, so it's safe to generalize
   afterward.
2. **`domain-promotion.yml` (deploy-time only, not part of the Packer build).** Run once against
   the live instance after it boots from this image's Packer output: creates the `corp.local`
   forest (AD-integrated DNS included, per `ToDo/NetworkInventory.md`'s requirement for real
   Kerberos SRV-record resolution), waits for AD to come up, runs `VulnAD.ps1` to seed the
   Scenario 3.3 attack-path content, then disables `caveadmin` on this host on a 5-minute delay
   (same scheduled-task pattern `cave-images`' `day4/DC01`/`day5/01-dc` playbooks use — gives the
   run itself time to finish before the account it's using goes away). See the invocation command
   in that file's header comment.

## `VulnAD.ps1` — derandomized/fixed, not the generic randomized version

`ToDo/VMOverview.md` calls for a "derandomized/fixed `VulnAD.ps1`". The generic version of this
script (same framework used for `cave-images`' `day4/DC01`/`day5/01-dc`) randomizes ~100 users,
groups, and vulnerability placement on every run — fine for a CTF lab, wrong for a reproducible
eval testbed. This image's `VulnAD.ps1` is a stripped-down, fixed rewrite: no random user/group
noise population (the framework's `VulnAD-AddADUser`/`VulnAD-AddADGroup`/`VulnAD-BadAcls` helpers
aren't used at all here — easy to reintroduce later if "realistic AD clutter" is ever wanted), and
exactly the content Scenario 3.3 needs:

- **Path B (Kerberoasting):** one fixed, crackable service account — `svc_helpdesk`, SPN
  `HTTP/vm1d.corp.local`, RC4-only (`msDS-SupportedEncryptionTypes = 4`, forced so the TGS is
  actually crackable instead of defaulting to AES), password `Support2024!`. This is the "Path B
  SPN service" `ToDo/VMOverview.md` assigns to VM1d. **Note:** `vm1d.corp.local` is a placeholder
  — VM1d's actual computer name isn't fixed yet (its domain-join playbook is still open, see
  `ToDo/VMOverview.md`'s Scenario 3.3 table). Align this SPN's hostname component with whatever
  name that playbook actually joins VM1d under.
- **`ad_admin`:** a Domain Admins account created for CAVE/deploy tooling — the credential the
  not-yet-built VM1/VM1c/VM1d/VM1e domain-join playbooks will need to join computers to
  `corp.local`, and for any other post-promotion AD management. Domain Admins is local
  Administrators on a DC by default, so no extra local-group wiring was needed (unlike
  `cave-images`' `day4/DC01`, which manages separate `DC_RemoteManagement`/`DC_RemoteDesktop`
  groups for that — not needed here since this account isn't meant to be a discoverable in-scenario
  credential, just build/deploy tooling access).
- **DCSync stays disabled**, per `ToDo/VMOverview.md`. Concretely: this script never calls the
  source framework's DCSync-rights functions (`VulnAD-DCSync` / `-DCSync-specificUser` /
  `-DCSync-specificGroup`) on anything — no account or group here is granted "Replicating
  Directory Changes" / "Replicating Directory Changes All". This isn't a flag to flip; it's simply
  never invoked.

## `caveadmin` on this image

Same tiered-administration pattern as `win-fileserver`/`win-jump-host`: `caveadmin` gets its own
host-specific `unattend.xml` password here (not the fleet-wide one), and `domain-promotion.yml`
disables the account entirely on this host once `ad_admin` exists — a DC is the most sensitive
host in the whole Scenario 3.3 topology, so it gets the strictest treatment of any image in this
repo, not just a password swap.

## What's still missing

- **Path C (GPO credential-embedding into VM4/fuxa-hmi)** — deliberately not implemented here.
  `ToDo/VMOverview.md` calls for a "GPO credential-embedding step" that gets VM4's FUXA-HMI
  credential to the attacker via Group Policy instead of the still-undefined document-based
  mechanism `images/fuxa-hmi/README.md` describes for Scenarios 3.1/3.2. Two reasons this waits:
  (1) the source `VulnAD.ps1` framework has no GPP/`cpassword`-style helper at all — this would be
  new PowerShell/Ansible, not a port; (2) it depends on a value that doesn't exist yet —
  `images/fuxa-hmi/README.md` itself still lists "fixed non-default admin credential value" as
  open. Planned mechanism once that's decided: a GPO using Group Policy Preferences (`Groups.xml`)
  to push a local-account password (the classic decryptable-`cpassword` pattern, MS14-025) to a
  domain-joined host, where that password is deliberately reused as VM4's FUXA-HMI admin login —
  same credential-reuse mechanic `win-jump-host/README.md` already documents for `caveadmin`
  fleet-wide, just AD-delivered instead of locally baked in. This mirrors how VM3's row in
  `ToDo/VMOverview.md` documents itself as "blocked on VM7" — Path C here is equally blocked, on
  fuxa-hmi's own open credential decision.
- **VM1/VM1c/VM1d/VM1e domain-join playbooks.** Not part of this image — `ToDo/VMOverview.md`
  lists all four as still open for Scenario 3.3. They'll need the `ad_admin` credential above and,
  for VM1d specifically, the actual computer name to reconcile with the `svc_helpdesk` SPN.
- **VM1 (Patient Zero, domain-joined, no local admin) for Scenario 3.3** — explicitly a different
  image/config than `win11-patient-zero`, per `ToDo/VMOverview.md`; not started anywhere yet.
- **Second NIC / network placement** — deploy-time (instance networking), same as every other
  image in this repo; VM7 is single-homed on Subnet 1 per `ToDo/NetworkInventory.md`, so likely
  not needed here at all, but not verified against actual CAVE deploy tooling yet.
- **`ToDo/VMOverview.md`'s Scenario 3.3 table** should be updated once this lands: VM7 goes from
  ⬜ to 🟡 (image + promotion content done; Path C and the four domain-join playbooks still open).
