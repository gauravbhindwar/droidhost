# DroidHost Handover

## Product Goal

DroidHost turns an Android phone into a self-hosted server:

```text
Android Compose control panel
        -> authenticated vm-agent
        -> real ARM64 Linux VM
        -> Linux kernel and Docker Engine
        -> user workloads
```

Coolify is an optional user workload. DroidHost does not contain Coolify-specific management logic.

## Current Repository State

### Android app

- Jetpack Compose Material 3 dashboard.
- Live metrics and container data are loaded from `http://127.0.0.1:8899`.
- Ktor HTTP repository uses a bearer token read from private app preferences.
- `MainViewModel` polls metrics and container data every three seconds.
- Container start, stop, and restart actions are wired to the vm-agent API.
- Foreground server service and boot receiver are present.
- Dashboard cards, Quick Actions, and bottom navigation have responsive sizing and bounded text.
- Android configuration currently uses `minSdk = 28`, `targetSdk = 35`, and `compileSdk = 35`.
- Java and Kotlin bytecode target JVM 17.

### vm-agent

The Go service provides authenticated endpoints for:

- Health and `/v1/metrics`.
- Container listing, inspect, stats, logs, and actions.
- Images, volumes, and networks.
- PTY terminal WebSocket access.

The agent talks to the Docker Engine API and reads host metrics from `/proc` inside the Linux guest.

### VM runtime

`vm/run-vm.sh` defines the real AArch64 VM launch contract. It expects:

```text
VM_DIR/bin/qemu-system-aarch64
VM_DIR/boot/Image
VM_DIR/boot/initrd.img
VM_DIR/data/droidhost.ext4
VM_DIR/agent-token
```

QEMU forwards Android loopback port `127.0.0.1:8899` to port `8899` in the guest.

## Important Runtime Limitation

The repository currently does not contain the QEMU binary, ARM64 kernel, initramfs, or VM disk in Android assets. As a result, the installed app cannot start a real guest yet, and `127.0.0.1:8899` will report connection refused until a valid guest bundle is provisioned into the app-private VM directory.

Do not replace this with Android shell, PRoot, a fake agent, fake metrics, or fake Docker responses. The product requirement is a real ARM64 Linux VM with Docker Engine.

The Android foreground service currently owns server mode and notification state. It must be completed so it copies or locates the provisioned runtime bundle, writes the per-installation token, validates resources, launches `run-vm.sh`, monitors QEMU, and reports failure without claiming that the VM is running.

## Build and Test

Use JDK 17, Android SDK platform 35, Android build tools, Gradle, and Go.

From the repository root:

```sh
./gradlew testDebugUnitTest assembleDebug

cd vm-agent
go test ./...
cd ..

sh -n vm/run-vm.sh vm/validate-assets.sh
```

For the local toolchain used during development:

```sh
JAVA_HOME=$PWD/.toolchain/jdk-17.0.12+7 \
  .toolchain/gradle-8.8/bin/gradle \
  :app:testDebugUnitTest :app:assembleDebug --no-daemon
```

The Android build has been validated successfully with `:app:assembleDebug` and `:app:testDebugUnitTest`. Go package tests also pass.

## Known Build and Device Messages

- Android Gradle Plugin 8.5.2 is older than the version officially tested with compileSdk 35. The warning is suppressed in `gradle.properties`; upgrading AGP should be considered later.
- Kotlin daemon cache-registration failures are local build-cache/process issues. The build falls back to in-process compilation and succeeds, but stale `app/build/kotlin` caches may need to be removed.
- `libandroidx.graphics.path.so` may be packaged without stripping because it is a prebuilt native library.
- Device logs such as AdrenoGLES, HWUI, CompatChangeReporter, Nothing vendor performance logs, and ProfileInstaller are platform/vendor output rather than DroidHost failures.
- SLF4J provider warnings mean no logging backend is configured for the Ktor client; add a backend only if client logging is required.
- A skipped-frame message indicates slow first-frame rendering on the test device and should be profiled separately if it persists.

## Security Requirements

- Never hardcode or commit bearer tokens.
- Generate a per-installation token and store it in app-private storage.
- Keep the agent bound to the guest interface and expose only authenticated routes.
- Validate VM resources before launch.
- Do not ship guest disks, kernels, initrds, QEMU binaries, SDK output, or generated build files in Git.
- Keep user port forwarding explicit and reject collisions.

## Implemented Functionality & Current Status

### Android App
- **Jetpack Compose Material 3 Control Panel:**
  - **Dashboard:** Server online/offline status, uptime, CPU/RAM/Network metrics, ARM64 VM controls card, Docker Engine overview, quick actions, and active containers preview.
  - **Containers:** Full container list, instant search, state filter (All, Running, Stopped), container inspect sheet with ports/mounts/safe env keys, real-time CPU/RAM/Network stats, scrollable logs viewer, and actions (start, stop, restart, delete).
  - **Terminal:** Monospace Linux PTY shell connected over authenticated WebSocket (`/v1/terminal`) with virtual accessory buttons (`Ctrl+C`, `Ctrl+D`, `Tab`, `Esc`, arrow keys, command input).
  - **Storage:** Visual breakdown of VM virtual disk, Docker writable layers, images, volumes, and Android internal storage.
  - **Network:** QEMU network status, VM internal IP, network I/O stats, and user-configurable port forwarding (`127.0.0.1:<hostPort> -> <guestPort>`).
  - **Settings:** Hardware allocation sliders (CPU cores, RAM MB, Disk GB) validated against physical Android device hardware, auto-start on boot toggle, and real-time guest VM bundle asset diagnostics.
- **Graceful Offline & Error Handling:**
  - When the VM is stopped/offline, polling is safely paused and raw socket exceptions (`Failed to connect to /127.0.0.1:8899`) are suppressed.
  - VM boot failures and missing asset reports are displayed in a clean, user-friendly diagnostic banner.
- **VM Process & Lifecycle Management:**
  - `VmManager` validates guest bundle assets (`qemu-system-aarch64`, ARM64 Linux kernel `Image`, `initrd.img`, `droidhost.ext4`, and `agent-token`).
  - `ServerModeService` manages foreground notification, VM execution, and handles start, stop, and restart requests.

### vm-agent
- Authenticated HTTP API (`/health`, `/v1/metrics`, `/v1/containers`, `/v1/containers/{id}`, `/v1/images`, `/v1/volumes`, `/v1/networks`).
- Real Linux PTY terminal bridge over WebSocket (`/v1/terminal`).

### Verification & Testing
- Unit tests pass with Gradle: `:app:testDebugUnitTest` (configuration validation, VM lifecycle, container state mapping, port forwarding).
- Go tests pass: `go test ./...` in `vm-agent`.
- Debug APK successfully compiled and packaged: `:app:assembleDebug`.

## Provisioning the ARM64 Guest Bundle
To run live workloads on an actual physical Android device, copy or extract the following ARM64 assets into `/data/user/0/com.droidhost/files/vm/`:
1. `bin/qemu-system-aarch64` (executable)
2. `boot/Image` (ARM64 kernel with `ARMd` header magic)
3. `boot/initrd.img` (initramfs with virtio drivers)
4. `data/droidhost.ext4` (rootfs with Docker daemon and `vm-agent`)
5. `agent-token` (managed automatically by DroidHost)

The diagnostics tab in Settings will automatically confirm when all 5 assets are in place.
