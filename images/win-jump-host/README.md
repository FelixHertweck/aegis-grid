# Jump Host / Bastion

Windows Server 2022 image for a session-broker bastion, built from the shared `server2k22`
OpenStack base like `images/win-fileserver/`. No application-layer content — no OT protocol
stack, and (firewall-enforced, below) no route into an OT field network.

## What this image adds over the base

- The shared Windows baseline (`win-common/base-access.yml` + `base-finalize.yml`).
- Hostname `JMP01`.
- `unattend.xml` with a **host-specific** `caveadmin` password, not the fleet-wide one.
- A dedicated **`jump_operator`** local admin account, fixed password, baked in.
- An outbound Windows Firewall rule blocking all traffic to `10.1.3.0/24` — the Windows
  equivalent of the iptables default-DROP used elsewhere in this repo. A static CIDR rule,
  independent of instance networking, so safe to bake in (unlike a second NIC).
