# Domain Controller

Windows Server 2022 image for a `corp.local` domain controller (`DC01`), built from the
shared `server2k22` OpenStack base like `images/win-fileserver/` and `images/win-jump-host/`.

## Build vs. deploy

A promoted DC cannot be `sysprep /generalize`d, so the image is split:

- **`image.pkr.hcl` + `playbook.yml`** — Packer build, generalizable. The shared Windows
  baseline (`win-common/base-access.yml` + `base-finalize.yml`), plus the
  `AD-Domain-Services` feature *binaries* — installed, not promoted, which doesn't touch
  domain/SID state.
- **`domain-promotion.yml`** — deploy-time, run once against the live instance. Installs the
  `corp.local` forest with AD-integrated DNS, ensures GPMC, runs `VulnAD.ps1`, then disables
  `caveadmin` on a short delay once `ad_admin` exists. Invocation is in the file header.

## `VulnAD.ps1`

A fixed, non-random rewrite of the VulnAD framework script (the generic version scatters
~100 users/groups/ACLs randomly per run, which a reproducible testbed can't use). It creates
a fixed set of AD objects and misconfigurations:

- `user1` — a plain domain user; no privileged group, no SPN.
- `svc_helpdesk` — a Kerberoastable service account with an HTTP SPN and
  `msDS-SupportedEncryptionTypes = 4` (forces a crackable RC4 ticket); member of `FS-Readers`.
- `FS-Readers` — the group `win-fileserver/domain-join.yml` grants read on `\\FS01\Shared`.
- `svc_fuxa` + a GPO (`FUXA HMI Provisioning`) whose SYSVOL GPP Scheduled-Task item stores
  `svc_fuxa`'s password as a `cpassword` (MS14-025 — decryptable with the published AES key).
  The referenced `provision-fuxa.ps1` holds no plaintext password.
- `ad_admin` — a Domain Admin for deploy tooling; the `domain-join.yml` playbooks
  authenticate as this.

`$Global:FuxaAdminPassword` (top of the script) is `svc_fuxa`'s password and must equal the
FUXA admin login set on the fuxa-hmi host. DCSync rights are never granted — the framework's
DCSync functions are not called.

`validate-ad-paths.ps1`, run on the promoted DC, checks these objects are correct; with
`-FuxaUrl` it also confirms the `cpassword` authenticates to FUXA.

## `caveadmin`

Host-specific `unattend.xml` password, not the fleet-wide one; `domain-promotion.yml`
disables the account once `ad_admin` exists.
