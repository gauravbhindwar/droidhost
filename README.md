# DroidHost

DroidHost turns an Android phone into a self-hosted Linux server. The Android app is the control panel; a real ARM64 Linux VM is the server; Docker Engine inside that VM runs user workloads. Coolify can be installed by the user as an ordinary workload. DroidHost does not implement Coolify-specific management.

## Architecture

Android app -> authenticated vm-agent -> real ARM64 Linux VM -> Linux kernel -> Docker Engine -> user workloads

The app shows live state from the VM. It never reports a VM or Docker service as healthy when the agent cannot prove it. Terminal sessions use a PTY inside the Linux guest, never Android's shell.

## Build

Install JDK 17, Android SDK platform 35/build tools, Go 1.22, and Docker. Then:

```sh
./gradlew testDebugUnitTest assembleDebug
cd vm-agent && go test ./...
```

The Android app expects the vm-agent at `127.0.0.1:8899` after QEMU forwards that port. Production builds must provision the per-installation agent token securely; do not ship the development token from `MainActivity`.

## Runtime

The guest bundle must contain QEMU, an ARM64 Linux `Image`, initrd, persistent ext4 disk, and a token file. Validate it with `vm/validate-assets.sh` and launch using `vm/run-vm.sh`. See [DEVELOPMENT.md](DEVELOPMENT.md) for image and Docker testing details.
