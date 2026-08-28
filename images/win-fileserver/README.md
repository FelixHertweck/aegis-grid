# Corporate Fileserver

Windows Server 2022 image for a corporate fileserver (`FS01`), built from the shared
`server2k22` OpenStack base the same way `images/win11-workstation/` builds from `client_win11`.

## What this image adds over the base

- The shared Windows baseline (`win-common/base-access.yml` + `base-finalize.yml`).
- Hostname `FS01`.
- **File Server role** (`FS-FileServer`).
- The **`Shared` SMB share** at `C:\Shares\Shared`, published read-only to `Authenticated
  Users` at both the share and NTFS layer.
- A dedicated low-privilege **`fileserver_reader`** local account (`Users` group only).
- `unattend.xml` with a **host-specific** `caveadmin` password, not the fleet-wide one.

## Deploy-time companion

`domain-join.yml` (run once against the live host) joins `corp.local` as `fs01`, replaces
the `Authenticated Users` read grant on `Shared` with `CORP\FS-Readers` only, and disables
local `caveadmin`. A domain join can't be baked into a generalized image — same split as
the DC's `domain-promotion.yml`.
