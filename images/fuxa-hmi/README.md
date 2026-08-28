# fuxa-hmi

Ubuntu 24.04 image running [FUXA](https://github.com/frangoteam/FUXA) (`frangoteam/fuxa`)
via Docker Compose — an open-source web SCADA/HMI/dashboard. Used as a read-only tag
database; nothing on this host issues control commands.

## What this image does

- Installs Docker (same boilerplate as the other Linux images here).
- Ships `docker-compose.yml` (`frangoteam/fuxa:latest`, port 1881) and a generic
  `mysettings.json` (`secureEnabled: true`, a non-default `secretCode`, no secrets).
- Pre-pulls the FUXA image; `run.sh` starts the container and waits for
  `http://localhost:1881/`.

## Admin credential

FUXA's factory default is `admin` / `123456`. Repointing it to a fixed non-default value is
a deploy-time step, not baked in: `POST /api/signin` with the default to get a token, then
`POST /api/users` (header `x-access-token`, `username: "admin"`) to overwrite the password.
The change persists in the `fuxa_db` / `fuxa_appdata` volumes.
