# Developer Guide: Building & Testing DroidHost

This guide outlines how developers can boot the ARM64 Linux VM, verify Docker Engine, test the Android control panel, and validate remote access.

---

## 1. Toolchain Requirements

- **JDK**: Java 17 (OpenJDK or Android Studio JBR)
- **Android SDK**: Build tools 35.0.0, API Level 35
- **Go**: 1.22+ (for building and testing `vm-agent`)
- **Docker Engine**: Installed locally for verifying container templates
- **ADB**: Android Debug Bridge for physical device and emulator workflows

---

## 2. Compiling the Project

### Run Android Unit Test Suite
```bash
./gradlew test
```
All unit tests in `app/src/test` must pass. This includes:
- `RemoteAccessModeTest`: Verifies mutual exclusivity between Tailscale and Cloudflare.
- `ConfigurationTest`: Validates hardware resource constraints.
- `VmLifecycleTest`: Verifies the VM lifecycle state machine.
- `ContainerTest`: Validates Docker container state mapping.
- `VmAgentApiTest`: Verifies metrics calculation and error states.

### Build Android APK
```bash
./gradlew assembleDebug
```
The output APK will be placed in `app/build/outputs/apk/debug/app-debug.apk`.

### Test the Go VM Agent
```bash
cd vm-agent
go test -v ./...
```

---

## 3. Booting the VM Manually (Host or Shell)

DroidHost utilizes real ARM64 Linux VM assets. Place the bundle assets in the VM directory:
- `bin/qemu-system-aarch64`
- `boot/Image` (ARM64 Linux Kernel)
- `boot/initrd.img` (Initial ramdisk)
- `data/droidhost.ext4` (Persistent ext4 filesystem with Docker and systemd)
- `agent-token` (Bearer authentication token)

Validate the assets using the validation script:
```bash
export VM_DIR="/path/to/vm/assets"
bash vm/validate-assets.sh
```

Boot the VM with standard port forward flags:
```bash
MEMORY_MB=2048 CPUS=2 bash vm/run-vm.sh
```

The script configures QEMU with:
```bash
-netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899,hostfwd=tcp:127.0.0.1:8000-:8000,hostfwd=tcp:127.0.0.1:2222-:22 \
-device virtio-net-pci,netdev=net0
```

---

## 4. Verifying Docker Engine Inside the VM

Once the VM is running, verify Docker Engine and the agent:

```bash
# 1. Health probe
curl http://127.0.0.1:8899/health

# 2. Authenticated metrics endpoint
export TOKEN=$(cat agent-token)
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8899/v1/metrics

# 3. Authenticated container listing
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8899/v1/containers?all=1

# 4. Run a container inside the VM
docker run -d --name test-web -p 8000:80 nginx:alpine
```

Now open `http://127.0.0.1:8000` from the host browser or Android phone to confirm workload connectivity.

---

## 5. Testing Remote Access Modes

1. **Local Wi-Fi Mode**:
   - In `NetworkScreen`, select **Local Wi-Fi**.
   - Test connecting via `ssh root@droidhost.local -p 2222` or `ssh root@<phone-ip> -p 2222`.
2. **Tailscale Mode**:
   - In `NetworkScreen`, select **Tailscale ⭐**.
   - Confirm that Cloudflare Tunnel is automatically stopped.
   - Verify that the static `100.x.y.z` IP and MagicDNS hostname appear in the UI.
3. **Cloudflare Tunnel Mode**:
   - In `NetworkScreen`, select **Cloudflare**.
   - Input your tunnel token and domain (e.g. `nothing3aproserver.animastuff.fun`).
   - Tap **Start Cloudflare Tunnel**.
   - Verify the tunnel starts and routes to port 8000.
