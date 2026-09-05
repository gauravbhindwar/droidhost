# DroidHost Architecture

DroidHost is designed around the core principle:

> **"Your Android phone is your own Linux server."**
> - The Android application is the **control panel**.
> - The virtualized Linux VM is the **server**.
> - Docker Engine is the **workload runtime**.
> - Workloads (such as Coolify, Nginx, PostgreSQL) run independently inside Docker.

---

## Detailed Component Diagram

```text
+=============================================================================+
|                           ANDROID USER SPACE (HOST)                         |
|                                                                             |
|  +-----------------------------------------------------------------------+  |
|  |                  Jetpack Compose Material 3 UI Layer                  |  |
|  |  [DashboardScreen]   [ContainersScreen]  [TerminalScreen]             |  |
|  |  [StorageScreen]     [NetworkScreen]     [SettingsScreen]             |  |
|  +-----------------------------------------------------------------------+  |
|                                      |                                       |
|                                      v                                       |
|  +-----------------------------------------------------------------------+  |
|  |                    MainViewModel & State Flows                        |  |
|  |  - DashboardState: live metrics, container list, VM lifecycle state   |  |
|  |  - RemoteAccessMode: LOCAL_WIFI | TAILSCALE | CLOUDFLARE              |  |
|  |  - SharedPreferences (on-device only, zero external telemetry)        |  |
|  +-----------------------------------------------------------------------+  |
|                         |                            |                       |
|                         v                            v                       |
|  +------------------------------+     +-----------------------------------+  |
|  |      AgentRepository         |     |        TerminalRepository         |  |
|  |  - HTTP client (8899)        |     |  - WebSocket PTY stream (8899)    |  |
|  |  - Bearer Token Auth         |     |  - Raw ANSI parser & copier       |  |
|  +------------------------------+     +-----------------------------------+  |
|                 |                                    |                       |
|                 +-----------------+------------------+                       |
|                                   |                                          |
|                                   v                                          |
|  +-----------------------------------------------------------------------+  |
|  |                   Android ServerModeService (Foreground)              |  |
|  |  - Persistent Ongoing Notification                                    |  |
|  |  - CPU WakeLock for 24/7 background operation                         |  |
|  |  - BootReceiver (auto-starts VM on device boot if enabled)            |  |
|  |  - Crash-loop prevention (terminates safely after fatal VM boot error) |  |
|  +-----------------------------------------------------------------------+  |
|                                   |                                          |
|                                   v                                          |
|  +-----------------------------------------------------------------------+  |
|  |                    VmManager & QEMU Process Manager                   |  |
|  |  - Spawns qemu-system-aarch64 in private app sandbox                  |  |
|  |  - Config validation against physical device RAM, CPU, and Disk       |  |
|  |  - User mode SLIRP networking with host port forward mappings        |  |
|  +-----------------------------------------------------------------------+  |
+===================================|=========================================+
                                    | SLIRP localhost tunnel
                                    | (Forward: 8899->8899, 8000->8000, etc.)
                                    v
+=============================================================================+
|                         REAL ARM64 LINUX VIRTUAL MACHINE                    |
|                                                                             |
|  +-----------------------------------------------------------------------+  |
|  |                  vm-agent (Go REST API & PTY Daemon)                  |  |
|  |  - Authenticates requests with SHA256 bearer token                     |  |
|  |  - /v1/metrics: /proc/stat, /proc/meminfo, /proc/uptime, /proc/net/dev |  |
|  |  - /v1/containers: talks directly to Docker UNIX socket               |  |
|  |  - /v1/terminal/ws: forks pty (pty.Start) running /bin/bash           |  |
|  +-----------------------------------------------------------------------+  |
|                                   |                                          |
|                                   v                                          |
|  +-----------------------------------------------------------------------+  |
|  |                          Linux Kernel (ARM64)                         |  |
|  |  - cgroups v2, namespaces, overlayfs, iptables, veth                  |  |
|  +-----------------------------------------------------------------------+  |
|                                   |                                          |
|                                   v                                          |
|  +-----------------------------------------------------------------------+  |
|  |                         Docker Engine & containerd                    |  |
|  |  - dockerd daemon listening on /var/run/docker.sock                   |  |
|  |  - Manages container lifecycles, bridge networks, volumes            |  |
|  +-----------------------------------------------------------------------+  |
|                                   |                                          |
|                                   v                                          |
|  +-----------------------------------------------------------------------+  |
|  |                           User Workloads                              |  |
|  |  - Microservices, Web Servers, Database instances                     |  |
|  |  - Optional Workload: Coolify container (independent runtime)        |  |
|  +-----------------------------------------------------------------------+  |
+=============================================================================+
```

---

## Remote Access Architecture & Mutual Exclusivity

Hosting server applications from an Android device requires remote connectivity. Because mobile operating systems have dynamic IP leases and run behind Carrier-Grade NAT (CGNAT), DroidHost integrates two industry-standard remote access providers:

```text
                        +----------------------+
                        |   Remote Access UI   |
                        +----------------------+
                                   |
                +------------------+------------------+
                |                                     |
                v                                     v
       [ TAILSCALE VPN ⭐ ]                   [ CLOUDFLARE TUNNEL ]
  - Static 100.x.y.z IP                 - Public domain URL
  - *.tailnet.ts.net hostname           - Outbound encrypted tunnel
  - Zero-configuration CGNAT bypass     - Accessible by anyone on the web
  - Stops Cloudflare when chosen        - Stops Tailscale when chosen
```

### Strict Mutual Exclusivity Policy
Running multiple remote network daemons simultaneously creates IP collisions, routing loops, and socket resource exhaustion on mobile chipsets. DroidHost enforces a strict **one-active-provider policy**:
- Switching to **Tailscale** immediately terminates any active Cloudflare tunnel.
- Activating **Cloudflare Tunnel** pauses Tailscale routing mode and begins the outbound tunnel.
- Selecting **Local Wi-Fi** terminates external routing and restricts server binding to LAN interfaces (`192.168.x.x` and `droidhost.local`).

---

## VM Lifecycle State Machine

```text
 [STOPPED] <------------------+
    |                         |
    | start()                 | stopped() / kill()
    v                         |
 [STARTING]                   |
    |      \                  |
    |       \ bootFailed()    |
    |        v                |
    |       [FAILED]          |
    |        /  (safe retry)  |
    |       /                 |
    v      v                  |
 [RUNNING] -------------------+
            stop() -> [STOPPING]
```

- **Crash-Loop Prevention**: If the VM fails to boot, it transitions to `FAILED` with a descriptive user message (`ServerFailure`). It never auto-restarts indefinitely.
- **Resource Validation**: Memory allocation is clamped to `[512 MB, physical_RAM * 3/4]`, CPU allocation is clamped to `[1, physical_cores]`, and disk size is validated against available storage space.
