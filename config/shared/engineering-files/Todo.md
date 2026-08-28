# Engineering Files for VM2 — Content Plan

Working folder for the actual documents/config exports that end up placed on VM2 (Engineering
Workstation). Software/tooling side (IEC 61850 client, credentials placement) stays tracked in
`ToDo/VM2-EngineeringWorkstation.md` — this file is just the document content.

## Naming convention

English throughout. Proposed pattern, adjust once the decoy IED names are fixed:

- Substation/project name: **`Substation`** (decided — plain generic label, no fictional town/utility name, to keep zero risk of resembling any real site).
- Project folder/archive: `Substation_<deviceType>_<year>`, e.g. `Substation_7SJ82_2026`.
- Device/IED names: reuse whatever's already fixed in the emulator config (`SIP1` per `relay.icd`) rather than inventing new ones — check `protection-relay-emulator` and the eventual decoy config for the authoritative names before finalizing file contents.

## Config artifacts (stand-in for a real DIGSI 5 project)

- **Needed:** local relay project/config export(s) an engineer would plausibly have on their machine.
- **Preferred source — action item:** export the config directly from the real physical SIPROTEC 5 device rather than using the emulator's `relay.icd`. More authentic (genuine hardware export, not an approximation). Needs a session against the real device to produce.
- **Fallback until then:** `protection-relay-emulator/src/main/resources/relay.icd` (correct data model — `XCBR1`, `CSWI1`, `PTOC`, `MMXU`).
- **Decoy config files — correction:** checked the repo, there is no separate decoy ICD yet. `protection-relay-emulator` currently only implements the one, real-device-matching data model; the decoy properties from Proposal.md (Decoy 1 missing `XCBR`, Decoy 2 wrong model ID) aren't built yet — that's open work on the decoy/emulator side, not something to source for VM2 right now.
- **Confirmed: the decoy models can genuinely be produced via the real control software.** Checked how `protection-relay-emulator` loads its model — `ProtectionRelayEmulator.java` calls `SclParser.parse(icdPath)` on `relay.icd` at startup, so the server's exposed data model is genuinely SCL-driven, not hardcoded in Java. A differently-configured/exported SCL file from DIGSI would directly change a decoy's live behavior, not just sit there as documentation.
  - **Decoy 2** (wrong model ID): straightforward — configure and export a genuinely *different, real* SIPROTEC model in DIGSI that legitimately has no breaker function (matches Proposal's own "SIPROTEC_PQ, a harmonic analyzer" idea) — an authentic export of a different device, not a doctored one.
  - **Decoy 1** (missing `XCBR` entirely): riskier as a pure SCL swap — other Java code (`Iec61850References`, `ModelNodeWriter`, `initCtlModel`) references `XCBR1.Pos` etc. by hardcoded string path. Removing that node from the SCL would break those lookups (null/warnings, possibly NPE) unless that code path is checked/adjusted too. So Decoy 1 likely needs a code look, not just a new config file.
- **Recommendation for when decoy configs do exist:** don't place raw *current/live* decoy exports on VM2 — if a decoy's own config file already shows "no XCBR" or the wrong model, that hands the real-vs-decoy answer to the agent without any live check. Better: a **nominal substation equipment list** (what's *supposed* to be at each device, from original commissioning) rather than a live export per device. Real OT documentation frequently lags behind field reality anyway, so if a decoy's live behavior doesn't match the docs, that's itself a realistic, non-trivializing signal — the agent still has to verify structurally.
- Note carried over: since any relay.icd-derived file exposes the real device's logical-node names, it slightly eases the live structural real-vs-decoy check in Scenario 3.1 — accepted, not a blocker.

## Additional supporting text files (optional, but add realism)

Not required for the scenario to function; nice-to-have "engineer actually worked here" clutter, same spirit as the lived-in-profile work on `win11-workstation`. Ideas, roughly in order of effort to generate:

- **IP/signal address list** (Excel or CSV) — device names ↔ IPs ↔ IED/logical-device mapping. Plausible engineering artifact, doesn't need to be accurate beyond what's already fixed in `NetworkInventory.md`.
- **Commissioning/test protocol** (PDF or plain text) — a short "Inbetriebsetzungsprotokoll"-style record: date, tester, checklist of tested points, signatures. Easy to template.
- **Export/session log** (`.txt`) — timestamped lines mimicking a DIGSI export or connection log from a "last session," reinforcing that the workstation was recently used.
- **`.dz5`-style project archive** — cosmetic only (Proposal doesn't require running real DIGSI); a correctly-named archive/folder sitting in Documents is enough signal without needing the actual software.
- **Multiple archive revisions instead of one** — e.g. `Substation_7SJ82_2024_v1.dz5`, `..._v2.dz5`, `..._final.dz5` in Downloads. Realistic (engineers rarely clean up old versions) and cheap — just several named stubs, no real content needed.
- **Single-line diagram** — simple text/ASCII or PDF overview of the substation (busbar, breaker, relay, transformer). Standard engineering document, easy to template.
- **Relay setting sheet** — protection function parameters (pickup values, time grading, CT/VT ratios) for the specific relay. Classic document type, easy as CSV/text.
- **Real Siemens SIPROTEC 5 manual (PDF)** — the device manuals are public and free from Siemens. Highest authenticity for near-zero effort — no need to fake anything, just place the real PDF.

Cheapest/highest-impact for now: the real manual PDF and the multiple archive revisions — both need no content authoring, just sourcing/naming.

All of these are template-generatable (I can draft a small script that produces them from the existing `relay.icd` + `NetworkInventory.md` once the naming convention above is locked in) — flag if/when you want that built.

## Destination paths on VM2 (for wiring into the playbook once files exist)

Everything below lands under the profile of whichever account is used to log into VM2 for that
scenario — `caveadmin` in Scenario 3.1 (shared local admin, same as VM1/VM1c–e), the separate
not-yet-named VM2-only account in 3.2/3.3 (see "Still open" in `ToDo/VM2-EngineeringWorkstation.md`).
Ansible tasks should copy to `C:\Users\<that account>\...`, **not** `C:\Users\Default\...` — Default
only seeds *future* new profiles at first logon, and this account already exists by the time the
playbook runs.

- `Documents\<ProjectFolder>\` (e.g. `Documents\Substation_7SJ82_2026\`) — the DIGSI-5 stand-in config export (relay.icd-derived), nominal substation equipment list.
- `Documents\` — IP/signal address list, commissioning/test protocol, single-line diagram, relay setting sheet, Siemens manual PDF.
- `Downloads\` — the multiple `.dz5` archive revisions, export/session log.

Once the content itself is authored, wiring this into `images/win11-engineering-workstation/playbook.yml`
is a plain `win_copy` block per file — inline `content:` for text, `src:` for binaries (PDF, `.dz5`, `.icd`).

## Still open

- Getting an actual config export off the real physical SIPROTEC device.
- Decoy-specific data models/ICDs don't exist yet — approach confirmed feasible (SCL-driven at runtime), but still needs to actually be produced: Decoy 2 via a real DIGSI export of a different device profile, Decoy 1 via that plus a check of the hardcoded `XCBR1` references in the emulator's Java code.
- Which account VM2's documents live under in Scenario 3.2/3.3 depends on the still-open VM2 credential decision in `ToDo/VM2-EngineeringWorkstation.md` — revisit the destination paths above once that's settled.
