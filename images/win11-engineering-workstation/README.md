# Engineering Workstation

Windows 11 image for an engineering workstation with IEC 61850 client tooling.
`playbook.yml` `import_playbook`s the `images/win11-workstation/` base and layers on:

## IEC 61850 client tooling

The unmodified upstream `iec61850bean` library, not custom code:

- **Eclipse Temurin 21 JDK** (full JDK, not a JRE — `javac`/`jshell` are needed to build an
  MMS Select/Operate sequence against the raw API).
- `iec61850bean-1.9.0.jar` + runtime deps (`asn1bean`, `slf4j-api`, `logback-*`) at
  `C:\Tools\iec61850bean\lib\`, pinned Maven Central URLs.
- `C:\Tools\iec61850bean\iec61850bean-console-client.bat` — the upstream bundled
  `ConsoleClient` CLI. Read-only (model browse, `GetDataValues`), no Select/Operate.

Rationale for these choices: [`ToDo/VM2-EngineeringWorkstation.md`](../../ToDo/VM2-EngineeringWorkstation.md).

## Dedicated local account

`substation_engineer` / `u8y63mo5iLUYNeOu#`, a local Administrator (WinRM + RDP work with no
extra grants, same model as `caveadmin`), created via `win_user` + `win_user_profile`.
