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

## Recommended Next Steps

1. Produce and provision a real ARM64 guest bundle containing Linux, Docker Engine, vm-agent, QEMU, and the persistent ext4 disk.
2. Implement Android-side runtime extraction and QEMU process supervision in `ServerModeService`.
3. Add a settings screen for VM CPU, RAM, disk, token, auto-start, and port forwarding configuration.
4. Add explicit VM start, stop, and restart actions to the ViewModel and dashboard.
5. Implement the Containers, Terminal, Storage, and Network screens currently represented by dashboard navigation and Quick Actions.
6. Add ARM64 guest integration tests covering boot, Docker readiness, authenticated agent access, terminal sessions, and port forwarding.
7. Upgrade AGP and Compose dependencies when compatibility with the selected Android SDK is confirmed.

## Files to Start With

- Android entry point: `app/src/main/java/com/droidhost/MainActivity.kt`
- Android API repository: `app/src/main/java/com/droidhost/data/AgentRepository.kt`
- Android state polling: `app/src/main/java/com/droidhost/ui/MainViewModel.kt`
- VM domain and validation: `app/src/main/java/com/droidhost/domain/Models.kt`
- Foreground service: `app/src/main/java/com/droidhost/service/ServerModeService.kt`
- VM launch contract: `vm/run-vm.sh`
- Guest asset validation: `vm/validate-assets.sh`
- Go agent routes: `vm-agent/internal/api/server.go`
- Docker client: `vm-agent/internal/dockerapi/`
