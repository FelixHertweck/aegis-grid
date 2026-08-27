# Corporate Fileserver

Windows Server 2022 image for a corporate fileserver — hosts the plant documentation (SIPROTEC
manual, network diagram, internal correspondence) that an attacker needs to discover to close the
OT semantic gap. Built directly from the shared `server2k22` OpenStack base image, the same way
`images/win11-workstation/` builds from `client_win11` — no fresh ISO bootstrap needed, since a
Windows Server 2022 base already exists on the OpenStack cluster (see
`day4/golden_image/image.pkr.hcl` in `cave-images` for the same pattern).

## What this image adds over the base

- **Baseline OS hygiene** mirrored from `win11-workstation`: OpenSSH (management access, same admin
  pubkey convention used across the fleet), RDP enabled, Defender disabled, NTLM enabled, UAC
  disabled for administrators, unrestricted PowerShell execution policy, ICMPv4 allowed, network
  profile forced to Private.
- **Hostname set to `FS01`**, matching the `\\FS01\Shared` share path below.
- **File Server role** (`FS-FileServer` Windows feature).
- **The `Shared` SMB share** at `C:\Shares\Shared`, published read-only to `Authentifizierte
  Benutzer` (Authenticated Users) at both the share and NTFS layer — readable by any authenticated
  user, no admin needed, out of the box, since workgroup logons that match a local account land in
  that group too.
- **`unattend.xml`** based on `win11-workstation`'s (same sysprep pass, already proven to work
  against `server2k22` — `day4/golden_image` uses an identical template), **but with a
  host-specific `AdministratorPassword`/`AutoLogon` password**, not the fleet-wide one.
- **A dedicated `fileserver_reader` local account** (`Benutzer` group only, not `Administratoren`),
  created via `win_user` in `playbook.yml` — the credential a mapped drive elsewhere in the
  network (see below) actually authenticates as. Already covered by the share's `Authentifizierte
  Benutzer` ACL, no separate grant needed.

## `caveadmin` on this image

Every other Windows image in this pipeline reuses the same `unattend.xml`, whose `oobeSystem` pass
resets `caveadmin` to the same fixed password (`UzKDtoM0yaCIjUDG`) fleet-wide — that shared
credential is the deliberate Valid-Accounts/T0859 lateral-movement mechanic `win11-patient-zero`'s
LSASS-exposure task feeds into. **This image deliberately breaks from that pattern**: it runs its
**own, separate local admin password** (`unattend.xml`), distinct from the fleet credential — a
tiered-administration setup — with actual share access coming from the `fileserver_reader` account
above, discovered via a mapped drive on some other workstation instead (see `RUNTIME-MOUNT.md`),
not from reusing `caveadmin`. `caveadmin` still exists here (build/management access needs it,
same as every image), it just no longer shares its password with the rest of the fleet.

## The mapped drive elsewhere — the actual discovery path onto this share

Not part of this image, and not built yet. Reaching `\\FS01\Shared` is no longer credential reuse
— it's via a persistent, low-privilege mapped drive that's supposed to already exist on some other
workstation in the network. Has to be deploy-time: mapping a drive with `/persistent:yes` needs to
actually connect at the moment it runs, and this fileserver doesn't exist yet in that workstation's
isolated Packer build network (same reason a domain-join can't be baked in either). Belongs in a
scenario-specific deploy layer, same not-yet-built category as a cached bastion credential and an
engineering-workstation account note living on other hosts — none of the three exist yet.

Mechanism, once built: cache `fileserver_reader`'s credential for the host, then map the drive
without embedding credentials in that call —

```
cmdkey /add:FS01 /user:FS01\fileserver_reader /pass:<reader-password>
net use Z: \\FS01\Shared /persistent:yes
```

— run as `caveadmin` (the account an attacker actually lands on), since a `/persistent:yes`
mapping reconnects only for the Windows user session that created it. Ansible-idiomatic
equivalent, matching this repo's preference for dedicated modules over raw `win_shell`:

```yaml
- name: Map a persistent low-privilege drive to this fileserver's share
  community.windows.win_mapped_drive:
    letter: Z
    path: \\FS01\Shared
    username: FS01\fileserver_reader
    password: "<reader-password>"
    state: present
  become: yes
  become_method: runas
  become_user: caveadmin
```

Still needs, on top of that bare example: a reachability wait before attempting the mapping (same
`retries`/`delay`/`until` pattern `day4/MKT-FS` uses for its DC dependency, e.g.
`Test-NetConnection -ComputerName FS01 -Port 445`), and correct ordering — this fileserver must be
provisioned before this runs against the other workstation.

## What's still missing

- **The deploy playbook for the workstation-side mapped drive above.**
- **The semantic-gap content itself** — the SIPROTEC manual (PDF), network diagram, and `.eml`
  correspondence embedding the jump host's credentials, and in some scenarios the FUXA-HMI host's
  credentials too. Deliberately left out of this pass — exact wording/placement is an open
  calibration detail, same status as the engineering-workstation image's `engineering-files/`.
- **A domain-joined, gated variant.** One scenario needs this host domain-joined and the share
  ACL narrowed from `Authentifizierte Benutzer` down to just the AD group backing a cracked
  Kerberoastable service account. Domain join has to happen against a live DC after boot — it
  can't be baked into a sysprep'd/generalized image (see `day4/MKT-FS`, `FIN-SRV`, `RPT-SRV` in
  `cave-images`, which are deploy-time-only playbooks for the same reason) — so this is a
  deploy-time layer on top of this image, not a separate Packer build. Blocked on a domain
  controller existing to join against and test the gating.
- **Second NIC / network placement** — deploy-time (instance networking), not part of this image,
  same as the engineering-workstation image.
