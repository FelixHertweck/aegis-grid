# Engineering Workstation

Windows 11 image for a dual-homed engineering workstation — the only host in the testbed with
legitimate IEC 61850 client access to the OT network, and, in scenarios with a segmented DMZ, the
only host with genuine write/Operate capability toward the relay. Built on the same base as every
other IT-side host (`images/win11-workstation/`); `playbook.yml` here `import_playbook`s that base
and layers the additions below on top.

## What this image adds over the base

**IEC 61850 client tooling** — the real, unmodified upstream `iec61850bean` library, not custom
code:

- Eclipse Temurin 21 **JDK** (not just a JRE) at `C:\Program Files\Eclipse Adoptium\...` —
  installed as a full JDK because an attacker has to write and compile their own Select/Operate
  sequence against the raw `iec61850bean` API; nothing on this image does that for them, so a JRE
  alone (no `javac`/`jshell`) wouldn't be enough.
- `iec61850bean-1.9.0.jar` and its runtime dependencies (`asn1bean`, `slf4j-api`,
  `logback-classic`, `logback-core`) at `C:\Tools\iec61850bean\lib\`, downloaded from Maven
  Central with pinned versions/URLs.
- `C:\Tools\iec61850bean\iec61850bean-console-client.bat` — launches
  `com.beanit.iec61850bean.app.ConsoleClient`, the upstream project's own bundled CLI (already
  compiled into the published jar, verified by inspecting it directly). It's read-only/exploratory
  (model printing, `GetDataValues`, data sets, reporting) with **no Select/Operate support** —
  installing it doesn't pre-solve actuation for an attacker.

Details and the reasoning behind these choices (including the rejected earlier idea of shipping a
thin CLI wrapping `ot-proxy`'s own client code) are in
[`ToDo/VM2-EngineeringWorkstation.md`](../../ToDo/VM2-EngineeringWorkstation.md).

**A dedicated local account for scenarios that need this host to have its own, separate
credentials** — username/password `substation_engineer` / `u8y63mo5iLUYNeOu#`, created in this
image via `win_user` + `win_user_profile`, same pattern `win11-patient-zero` already uses for its
foothold user. Baked into every build regardless of scenario; in the simplest scenario, this host
still uses the shared `caveadmin` account like the rest of the IT fleet (no separate discovery
step needed there), so this account just goes dormant.

- **Access model:** the account is a **local Administrator** (`Administratoren` group), not a
  restricted standard user. That's what makes both WinRM and RDP work with zero extra
  configuration on top of what the base playbook already sets up:
  - **WinRM** — the default WinRM security descriptor (SDDL) trusts `BUILTIN\Administrators`, so an
    admin account can WinRM in without any additional grant.
  - **RDP** — local admins can always log on via RDP once the service and firewall rule are enabled
    (which the base playbook already does); that permission doesn't depend on Remote Desktop Users
    group membership the way it would for a standard user.
  - A standard-user account would need an explicit `Remote Management Users` grant for WinRM and
    `Remotedesktopbenutzer` group membership for RDP — skipped here in favor of matching the
    same access model `caveadmin` already uses everywhere else in the testbed.
- **Not enough on its own for scenarios that require separate credentials here — `caveadmin` must
  be separately disabled.** This image doesn't do that (it's scenario-agnostic, built once). Since
  `caveadmin` still works on this host, a not-yet-built deploy-time config step has to disable it
  specifically when standing this host up for such a scenario — otherwise an attacker could just
  reuse the already-known shared credential from elsewhere in the IT fleet and skip discovering
  this account entirely, defeating the whole point of having it.
- **Where the credentials surface to an attacker:** *not* on this image. They get written into a
  handover-note discovery artifact placed on another IT host by that same deploy-time config
  step — the same delivery mechanism that will upload the engineering-config content below onto
  this host, not a Packer provisioning step. See
  [`ToDo/VM2-EngineeringWorkstation.md`](../../ToDo/VM2-EngineeringWorkstation.md#credentials-scenario-3233--vm2-needs-its-own-separate-from-everything-else)
  for that artifact's content/placement, and keep the two values in sync if either changes.

## What's still missing

- **Engineering config artifacts and supporting documents** (DIGSI-5 stand-in project files, IP
  list, manuals, etc.) — content plan and destination paths tracked in
  [`engineering-files/Todo.md`](../../engineering-files/Todo.md), not yet wired into the playbook.
- **Second NIC into the OT network** — deploy-time (instance networking), not part of this Packer
  image.
- **The deploy-time config step** for the segmented-DMZ scenarios doesn't exist yet — no
  scenario-deploy directory has been created for them (see the existing `config/phase-*/` folders
  for the pattern used by earlier pilots). It needs to (a) disable `caveadmin` on that instance,
  (b) write the handover-note artifact with this account's credentials, and (c) upload the
  engineering-config content onto this host.
