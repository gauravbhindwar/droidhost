# DroidHost Engineering Handover

## 1. Product Overview & Architecture

DroidHost turns an Android device into a genuine self-hosted ARM64 Linux server without root privileges or chroot translation hacks (PRoot).

### The Execution Pipeline

```text
Android Phone (Host)
    │
    ▼
ServerModeService (Foreground Android Service, 24/7 uptime)
    │
    ▼
VmManager & SlirpNetworkBackend
    │
    ▼
QEMU AArch64 Hypervisor (qemu-system-aarch64)
    │
    ▼
Real ARM64 Linux Guest VM (Alpine Linux v3.20, Linux 6.6+ kernel)
    │
    ├─► vm-agent (Go REST API + Multi-PTY WebSocket server)
    ├─► Docker Engine (v26.1+, native cgroups and overlay2)
    ├─► OpenSSH Server (sshd on guest :22, forwarded to host :2222)
    ├─► DoH Fallback Proxy (doh-proxy on 127.0.0.1:53)
    └─► User Workloads (Coolify 4.3+, databases, custom containers)
```

---

## 2. Directory Structure & Key Files

```text
droidhost/
├── app/
│   ├── src/main/java/com/droidhost/
│   │   ├── MainActivity.kt               # Entrypoint, Compose navigation, permission handlers
│   │   ├── domain/
│   │   │   └── Models.kt                 # VmNetworkConfig, PortForward, DnsConfig, NetworkDiagnostics, TerminalSessionTab
│   │   ├── data/
│   │   │   ├── AgentRepository.kt        # Ktor HTTP client to vm-agent REST API (60s timeout, auth token)
│   │   │   └── TerminalRepository.kt     # WebSocket client supporting ?session=$sessionId query param
│   │   ├── service/
│   │   │   └── VmManager.kt              # Asset extraction, QEMU process execution, SLIRP arguments, watchdog
│   │   └── ui/
│   │       ├── DashboardScreen.kt        # Live CPU/RAM/Net metrics, workload cards, shortcuts
│   │       ├── ContainersScreen.kt       # Docker container inspector, live logs, start/stop/restart/delete
│   │       ├── TerminalScreen.kt         # Multi-session PTY tab row, ANSI console, virtual keyboard accessories
│   │       ├── NetworkScreen.kt          # Interface stats, Network Diagnostics self-test, Tailscale & Cloudflare tunnels
│   │       ├── StorageScreen.kt          # Sparse virtual disk breakdown, Docker storage, prune actions
│   │       └── MainViewModel.kt          # UDF state management, terminal sessions, diagnostics orchestration
│   ├── src/main/jniLibs/arm64-v8a/
│   │   └── libvmagent.so                 # Prebuilt Go vm-agent binary for aarch64
│   └── src/main/assets/vm/
│       └── qemu-libs.dh                  # Compressed bundle of QEMU binaries and shared libraries
├── vm-agent/
│   ├── main.go                           # Entrypoint, token authentication middleware, route registration
│   └── internal/
│       ├── api/
│       │   ├── server.go                 # REST endpoints: metrics, containers, network diagnostics (/v1/network/diagnostics)
│       │   └── server_test.go            # Unit tests for diagnostics, token validation, session lifecycle
│       ├── terminal/
│       │   └── terminal.go               # Multi-PTY session manager, openpty, 64KB ring buffer, idle reaper
│       ├── dockerapi/                    # Native Docker engine client over /var/run/docker.sock
│       └── dns/
│           └── doh.go                    # DNS-over-HTTPS fallback provider
├── scripts/
│   ├── build-arm64-guest.sh              # Rootfs preparation, OpenSSH configuration, bash compatibility, /dev/fd
│   └── provision-vm.sh                   # In-guest initialization script
├── vm/
│   └── run-vm.sh                         # Canonical QEMU launch script with SLIRP port forwards
└── docs/assets/screenshots/              # Physical device verification screenshots
```

---

## 3. Network Architecture & Abstraction

### Subnet Layout
* **QEMU User SLIRP Network**: `10.0.2.0/24`
  * Guest Address: `10.0.2.15`
  * Virtual Gateway: `10.0.2.2`
  * Virtual DNS: `10.0.2.3`
* **Docker Network Pool**: `172.18.0.0/16` (no collision with host or QEMU SLIRP).

### Port Forwarding Contract
Managed dynamically via `SlirpNetworkBackend.kt`:
* `hostPort = 8899 -> guestPort = 8899` (vm-agent REST API + Terminal WebSockets)
* `hostPort = 8000 -> guestPort = 8000` (Coolify web UI / general web workloads)
* `hostPort = 2222 -> guestPort = 22`   (Guest OpenSSH daemon)

### Android Active Network & DNS Discovery
In `VmManager.kt`, Android's `ConnectivityManager` inspects `activeNetwork -> LinkProperties -> dnsServers`.
* Upstream DNS addresses are discovered and passed to QEMU's upstream resolver.
* Private RFC1918 addresses (e.g. `192.168.1.1`) are filtered out and **never** exposed as the guest DNS.
* Guest `/etc/resolv.conf`:
  ```text
  nameserver 10.0.2.3
  nameserver 127.0.0.1
  ```
* If primary DNS fails or is intercepted by carrier CGNAT, in-VM DoH (`127.0.0.1:53`) queries Cloudflare/Google over HTTPS.

---

## 4. Network Diagnostics API

Endpoint: `GET /v1/network/diagnostics` (Authenticated with Bearer token)

Returns 8 real hardware and transport checks:
```json
{
  "interfaceUp": true,
  "guestAddress": "10.0.2.15",
  "defaultRoute": true,
  "gatewayReachable": true,
  "dnsReachable": true,
  "dnsResolution": true,
  "httpsReachable": true,
  "dockerRegistryReachable": true,
  "dockerPullTest": true,
  "latencyMs": 174
}
```
* **No mocks**: Gateway reachability tests route existence, DNS tests UDP `10.0.2.3:53` and public resolution of `cloudflare.com`, HTTPS tests TLS handshake to `1.1.1.1` without following redirect loops, Docker registry tests `https://registry-1.docker.io/v2/`, and Docker pull verifies actual container execution.

---

## 5. Multi-Session PTY Terminal Implementation

### vm-agent Architecture (`internal/terminal/terminal.go`)
* Supports concurrent, independent terminal sessions:
  * `POST /v1/terminal/sessions`: Creates a new PTY session with specified or auto-generated ID.
  * `GET /v1/terminal/sessions`: Lists active sessions.
  * `DELETE /v1/terminal/sessions/{id}`: Terminates the PTY process and frees resources.
  * `GET /v1/terminal?session={id}`: WebSocket connection attached to the specific session's PTY.
* **History Buffer**: Each session maintains a 64KB ring buffer storing recent ANSI output. When the user navigates between screens or tabs, the WebSocket reconnects and replays the ring buffer so no terminal state is lost.
* **Idle Reaper**: Disconnected sessions are preserved for 10 minutes, after which an idle worker terminates the child process to avoid resource leaks.

### Android Client Architecture
* `MainViewModel.kt` maintains `terminalSessions: List<TerminalSessionTab>`.
* Dedicated `TerminalRepository` instances per tab ensuring WebSocket messages never cross-talk between sessions.
* Compose tab row in `TerminalScreen.kt` allows adding (`+`), closing (`×`), and switching between sessions.

---

## 6. Remote Access & SSH

### Local SSH Access
From the host computer:
```bash
adb forward tcp:2222 tcp:2222
ssh root@127.0.0.1 -p 2222
```
* Once connected, commands like `uname -m`, `docker ps`, and `cat /etc/os-release` operate directly inside the ARM64 Linux VM.

### Tailscale Remote SSH
* Runs on Android host or via WireGuard mesh.
* Android forwards or routes traffic to `127.0.0.1:2222`.
* Displayed in DroidHost **Network** tab with 1-tap command copying.

---

## 7. Known Issues & Troubleshooting

1. **OpenSSH Key Authentication Rejection**:
   * *Symptom*: `Permission denied (publickey)` when attempting to SSH into root.
   * *Root Cause*: OpenSSH `StrictModes` rejects `/root` or `/root/.ssh` if permissions are not strictly `0700` owned by `root:root`.
   * *Fix*: In `vm-token.start`: `chown -R root:root /root && chmod 700 /root`.

2. **Ktor Client SocketTimeoutException on Network Diagnostics**:
   * *Symptom*: Network diagnostics self-test reports `FAILED` on mobile network.
   * *Root Cause*: Ktor's OkHttp engine has a default 10s read timeout. Pulling Docker layers over cellular can take 12-15s.
   * *Fix*: `AgentRepository.kt` configures OkHttp `readTimeout` to 60s.

3. **Bash /dev/fd Errors on Alpine**:
   * *Symptom*: `bash: line 241: /dev/fd/63: No such file or directory`.
   * *Root Cause*: Alpine Linux does not create `/dev/fd` by default.
   * *Fix*: In `/etc/local.d/vm-token.start`: `if [ ! -e /dev/fd ]; then ln -s /proc/self/fd /dev/fd; fi`.

4. **Entry-Level Phone Memory Limits (4GB Devices)**:
   * When running Coolify (5 containers: Traefik, Coolify, Redis, PostgreSQL, Realtime), RAM usage in a 2GB VM reaches 95%.
   * Advise users to use 1.5GB to 2GB allocations and close memory-heavy background Android apps.

---

## 8. Verification & Build Commands

```bash
# 1. Run Android Unit Tests
./gradlew testDebugUnitTest

# 2. Run Go vm-agent Unit Tests
cd vm-agent && go test ./... && cd ..

# 3. Assemble Debug APK
./gradlew assembleDebug

# 4. Install & Launch on Device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.droidhost/.MainActivity

# 5. Verify SSH over ADB
adb forward tcp:2222 tcp:2222
ssh root@127.0.0.1 -p 2222 "uname -m && docker ps"
```
