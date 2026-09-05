# Security Architecture & Boundary Model

DroidHost treats the Linux Virtual Machine as the primary security and isolation boundary for all self-hosted user applications.

---

## 1. Android / VM Boundary

- **Host Containment**: The Android application executes within standard Android application sandboxing (SELinux domains, unprivileged UID/GID).
- **No Arbitrary Android Commands**: The Android UI **never** executes privileged host commands or exposes Android's shell (`/system/bin/sh`).
- **VM as the Workload Boundary**: All user workloads, compilers, scripts, and third-party containers run exclusively inside the virtualized ARM64 Linux guest VM.
- **Escape Mitigation**: Any vulnerability or exploit within a Docker container is bounded by the Linux kernel inside the VM and cannot access Android host memory, SMS, contacts, camera, or external storage.

---

## 2. vm-agent Authentication

- **Bearer Token Verification**: `vm-agent` enforces SHA-256 bearer token authentication on all endpoints (except the unauthenticated `/health` probe).
- **Per-Device Generation**: Tokens are generated on a per-installation basis, stored in Android private app storage (`Context.MODE_PRIVATE`), and never transmitted over external networks.
- **PTY Session Protection**: The WebSocket terminal stream requires bearer authentication during the WebSocket handshake before spawning a Linux pseudo-terminal.

---

## 3. Port Forwarding & Network Isolation

- **Selective Loopback Forwarding**: QEMU's user-mode network stack (SLIRP) only forwards explicitly configured ports.
- **Port Conflict Detection**: Before updating forwarding rules or restarting QEMU, ports are validated to prevent socket collision with Android system services.
- **Direct Browser Integration**: When opening forwarded web apps (e.g. `http://127.0.0.1:8000`), traffic stays within localhost and the VM virtual interface.

---

## 4. Remote Access & Credential Privacy

- **On-Device Storage Only**: Cloudflare tunnel tokens, Tailscale credentials, and SSH keys are stored exclusively in local Android `SharedPreferences` (`droidhost_settings`).
- **Zero Cloud Leakage**: DroidHost communicates directly with the local VM and configured tunnel providers; zero telemetry or credentials are sent to external third-party analytics servers.
- **Mutual Exclusivity Enforcement**: Running multiple remote networking tools simultaneously causes packet sniffing, routing collisions, and DNS leakage. DroidHost guarantees only one remote access provider is active at any given time.

---

## 5. Docker Access & Secret Sanitization

- **UNIX Socket Boundary**: Docker Engine is only accessible via `/var/run/docker.sock` inside the Linux guest.
- **Safe Environment Masking**: When inspecting container configurations, DroidHost summarizes environment variable **keys** (e.g., `DATABASE_URL`, `API_KEY`, `PORT`) but strips out sensitive **values** to prevent accidental screen captures or shoulder surfing.
- **Restricted Privileged Flags**: Default container templates run without `--privileged` unless explicitly specified by the operator.

---

## 6. Filesystem Isolation

- **Virtual Disk Image**: The guest operating system and all container storage live inside a single virtual ext4 disk image (`droidhost.ext4`).
- **No Implicit Host Mounts**: Android internal storage directories (`/sdcard`, `/data/data`) are never implicitly bind-mounted into Docker containers.
- **Integrity Verification**: `VmManager` validates disk image consistency and filesystem signatures before booting.

---

## 7. Known Limitations

- **QEMU User Networking**: QEMU SLIRP provides user-mode NAT, not a hardware-enforced firewall. Ensure workloads are kept updated.
- **Host Resource Starvation**: High CPU or RAM workloads inside the VM can affect host battery life and thermals. DroidHost clamps resource allocation to safe physical thresholds (`<= 75% RAM`, `<= available physical cores`).
