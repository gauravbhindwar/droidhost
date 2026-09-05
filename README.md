# DroidHost

**"Your Android phone is your own Linux server."**

DroidHost is a polished self-hosted ARM64 Linux server management platform for Android devices. The Android application acts as the control panel, managing a real virtualized ARM64 Linux VM running the Linux kernel, Docker Engine, and user workloads.

---

## System Architecture

```text
+-------------------------------------------------------------------------+
|                          ANDROID PHONE (HOST)                           |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   |         Jetpack Compose Material 3 Server Control Panel         |   |
|   |  - Server Dashboard: CPU, RAM, Storage, Uptime, Live Metrics    |   |
|   |  - Docker Management: Containers, Images, Volumes, Networks     |   |
|   |  - Compose Terminal: Linux PTY shell (never Android shell)      |   |
|   |  - Static IP / Remote Access: Tailscale VPN or Cloudflare Tunnel|   |
|   |  - Port Forwarding: Configure ports, 1-tap browser launcher     |   |
|   +-----------------------------------------------------------------+   |
|                                  |                                      |
|                 Foreground ServerModeService (24/7 Uptime)              |
|                                  |                                      |
|              QEMU System Emulator (ARM64 AArch64 / SLIRP)               |
+----------------------------------|--------------------------------------+
                                   | Localhost port forward (8899)
                                   v
+-------------------------------------------------------------------------+
|                        REAL ARM64 LINUX GUEST VM                        |
|                                                                         |
|   +-----------------------------------------------------------------+   |
|   |                  vm-agent (Go REST API + PTY)                   |   |
|   |  - Bearer token authenticated                                   |   |
|   |  - Native Linux PTY execution                                   |   |
|   |  - Docker Engine UNIX socket communication                      |   |
|   +-----------------------------------------------------------------+   |
|                                  |                                      |
|                             Linux Kernel                                |
|                                  |                                      |
|                            Docker Engine                                |
|                                  |                                      |
|   +-----------------------------------------------------------------+   |
|   |                        User Workloads                           |   |
|   |  - Web apps, databases, microservices                          |   |
|   |  - Optional user-installed workloads (e.g., Coolify, Nginx)     |   |
|   +-----------------------------------------------------------------+   |
+-------------------------------------------------------------------------+
```

---

## Key Features

### 1. Polished Server Dashboard
- **Server State**: Live online/offline status, uptime timer, CPU utilization, RAM usage, storage breakdown, and network throughput.
- **VM Controls**: Start, stop, restart, and resource allocation inspection (CPU cores, RAM size, disk capacity).
- **Docker Overview**: Live counts of running containers, stopped containers, pulled images, persistent volumes, and bridge networks.
- **Quick Actions**: 1-tap navigation to Terminal, Containers, Storage, Network, and Settings.

### 2. Complete Docker Container Management
- **Container Operations**: List, search by name/image/ID, filter by status (All, Running, Stopped).
- **Lifecycle Actions**: Start, stop, restart, delete, inspect, stream live logs, and view container resource stats.
- **Container Detail Screen**: Ports mapping, memory limits, network endpoints, mounted volumes, and safe environment keys summary (secrets values are never exposed).

### 3. Native Linux PTY Terminal
- Connects directly to a Linux pseudo-terminal (PTY) inside the ARM64 guest via `vm-agent`.
- Commands execute exclusively within the Linux VM security boundary, never in the Android host shell.
- Full ANSI color rendering, text drag-selection viewer dialog, and 1-tap clipboard copying.

### 4. Static Internet IP & Remote Access
Hosting from a phone requires a reliable static address. DroidHost supports two premier remote access technologies with **strict mutual exclusivity** (only one can be active at a time to prevent routing and socket conflicts):

1. **Tailscale / WireGuard Mesh VPN ⭐**
   - Assigns a permanent, fixed static `100.x.y.z` IP and MagicDNS hostname (`<phone>.tailnet.ts.net`).
   - Bypasses CGNAT and firewalls seamlessly across Wi-Fi and 4G/5G mobile data with zero router port forwarding.
2. **Cloudflare Tunnels (`cloudflared`)**
   - Outbound encrypted reverse tunnel routing traffic to a public custom domain (e.g., `https://nothing3aproserver.animastuff.fun`).
   - Direct 1-tap browser launch and token management directly on-device.
3. **Local Wi-Fi (mDNS / Direct IP)**
   - Local access via `droidhost.local` or direct LAN DHCP IP when remote internet access is not required.

### 5. 24/7 Server Mode (Foreground Service)
- Powered by `ServerModeService` with persistent ongoing notification and quick actions ("Stop VM", "Start VM").
- Auto-restart on device boot when configured.
- Graceful crash loop prevention: stops server mode on unrecoverable VM failure instead of aggressive infinite restart loops.

---

## Getting Started

### Prerequisites
- Android 10+ (ARM64 device recommended with 6GB+ RAM)
- JDK 17 & Android SDK Platform 35
- Go 1.22+ (for compiling `vm-agent`)

### Building the Application

```bash
# Run full unit tests
./gradlew test

# Assemble debug APK
./gradlew assembleDebug

# Build and test the Go vm-agent
cd vm-agent && go test ./...
```

### Installing and Running
Install the generated APK onto your connected physical device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.droidhost/.MainActivity
```
