#!/bin/bash
# DroidHost ARM64 Guest Build Pipeline
# Builds a bootable ARM64 Linux guest VM bundle with Docker Engine, in-guest vm-agent,
# and compatible QEMU ARM64 user-space runner.
# Usage: ./scripts/build-arm64-guest.sh [output-directory]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
OUTPUT_DIR="${1:-$REPO_ROOT/dist/vm-bundle}"
BUILD_TMP="/tmp/droidhost-guest-build-$$"

ALPINE_VERSION="v3.20"
ALPINE_FULL_VER="3.20.0"
ALPINE_MIRROR="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_VERSION}"

echo "============================================================"
echo "   DroidHost ARM64 Linux Guest VM Build Pipeline"
echo "============================================================"
echo "Target directory: $OUTPUT_DIR"
echo "Build workspace:  $BUILD_TMP"
echo ""

cleanup() {
    rm -rf "$BUILD_TMP"
}
trap cleanup EXIT

# 1. Check prerequisites
for tool in curl tar gzip mke2fs go zip; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Error: Required build tool '$tool' is not installed." >&2
        exit 1
    fi
done

mkdir -p "$OUTPUT_DIR/bin" "$OUTPUT_DIR/boot" "$OUTPUT_DIR/data" "$OUTPUT_DIR/qemu"
mkdir -p "$BUILD_TMP/staging" "$BUILD_TMP/qemu-staging" "$BUILD_TMP/dl"

# 2. Compile in-guest vm-agent for ARM64 Linux
echo "==> Compiling native ARM64 in-guest vm-agent..."
(
    cd "$REPO_ROOT/vm-agent"
    CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -ldflags="-s -w" -o "$BUILD_TMP/vm-agent-arm64" .
)
echo "    vm-agent compiled successfully ($(du -h "$BUILD_TMP/vm-agent-arm64" | cut -f1))"

# 3. Fetch Real ARM64 Linux Kernel, companion Initramfs, and Modloop
echo "==> Fetching ARM64 Linux kernel and companion components..."
KERNEL_URL="${ALPINE_MIRROR}/releases/aarch64/netboot/vmlinuz-virt"
INITRD_URL="${ALPINE_MIRROR}/releases/aarch64/netboot/initramfs-virt"
MODLOOP_URL="${ALPINE_MIRROR}/releases/aarch64/netboot/modloop-virt"

if [ ! -f "$OUTPUT_DIR/boot/Image" ] || [ ! -s "$OUTPUT_DIR/boot/Image" ]; then
    echo "    Downloading ARM64 Linux kernel (vmlinuz-virt)..."
    curl -fsSL "$KERNEL_URL" -o "$OUTPUT_DIR/boot/Image"
fi

echo "    Downloading companion initramfs and kernel modules..."
curl -fsSL "$INITRD_URL" -o "$BUILD_TMP/dl/initramfs-virt"
curl -fsSL "$MODLOOP_URL" -o "$BUILD_TMP/dl/modloop-virt"

mkdir -p "$BUILD_TMP/initrd_work" "$BUILD_TMP/modloop_work"
(
    cd "$BUILD_TMP/initrd_work"
    gzip -dc "$BUILD_TMP/dl/initramfs-virt" | cpio -idmv 2>/dev/null
)
unsquashfs -f -d "$BUILD_TMP/modloop_work" "$BUILD_TMP/dl/modloop-virt"

# Inject full kernel modules (ext4, overlay, veth, bridge) and dependency maps into initramfs
echo "    Injecting storage and network modules into initrd.img..."
cp -rf "$BUILD_TMP/modloop_work/modules"/* "$BUILD_TMP/initrd_work/lib/modules/"
(
    cd "$BUILD_TMP/initrd_work"
    find . | cpio -H newc -o 2>/dev/null | gzip -9 > "$OUTPUT_DIR/boot/initrd.img"
)
echo "    Kernel and bootable companion initramfs ready."

# 4. Fetch apk.static for reproducible multi-arch package staging
echo "==> Setting up Alpine apk.static..."
APK_STATIC="/tmp/apk.static"
if [ ! -x "$APK_STATIC" ]; then
    echo "    Downloading apk.static (x86_64)..."
    curl -fsSL "${ALPINE_MIRROR}/main/x86_64/apk-tools-static-2.14.4-r1.apk" | tar -xzO sbin/apk.static > "$APK_STATIC"
    chmod +x "$APK_STATIC"
fi

# 5. Populate ARM64 Guest Filesystem with Alpine base, Docker Engine, and OpenRC
echo "==> Staging Alpine minirootfs with full applet symlinks..."
MINIROOTFS_URL="${ALPINE_MIRROR}/releases/aarch64/alpine-minirootfs-${ALPINE_FULL_VER}-aarch64.tar.gz"
curl -fsSL "$MINIROOTFS_URL" -o "$BUILD_TMP/dl/alpine-minirootfs.tar.gz"
tar -xzf "$BUILD_TMP/dl/alpine-minirootfs.tar.gz" -C "$BUILD_TMP/staging"

echo "==> Adding Docker Engine and OpenRC packages via apk.static..."
"$APK_STATIC" --arch aarch64 \
    -X "${ALPINE_MIRROR}/main" \
    -X "${ALPINE_MIRROR}/community" \
    -U --allow-untrusted \
    --root "$BUILD_TMP/staging" \
    add \
    openrc \
    docker \
    docker-cli \
    docker-cli-compose \
    containerd \
    runc \
    iptables \
    ca-certificates \
    bash \
    curl \
    openssh \
    openssh-server \
    e2fsprogs \
    e2fsprogs-extra 2>/dev/null || true

# Copy all kernel modules into the guest rootfs so Docker can use overlay, bridge, veth
echo "==> Installing kernel modules into guest rootfs for Docker..."
mkdir -p "$BUILD_TMP/staging/lib/modules"
cp -rf "$BUILD_TMP/modloop_work/modules"/* "$BUILD_TMP/staging/lib/modules/"

# Ensure critical init symlinks exist
ln -sf /bin/busybox "$BUILD_TMP/staging/sbin/init"
ln -sf /bin/busybox "$BUILD_TMP/staging/bin/sh"

# 6. Install in-guest vm-agent
echo "==> Installing vm-agent into guest rootfs..."
mkdir -p "$BUILD_TMP/staging/usr/local/bin"
cp "$BUILD_TMP/vm-agent-arm64" "$BUILD_TMP/staging/usr/local/bin/vm-agent"
chmod 755 "$BUILD_TMP/staging/usr/local/bin/vm-agent"

# Also keep a copy in app native libs and assets
mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$BUILD_TMP/vm-agent-arm64" "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libvmagent.so"

# 7. Configure in-guest system services and init
echo "==> Configuring guest init system and services..."
mkdir -p "$BUILD_TMP/staging/etc/init.d"
mkdir -p "$BUILD_TMP/staging/etc/runlevels/sysinit"
mkdir -p "$BUILD_TMP/staging/etc/runlevels/boot"
mkdir -p "$BUILD_TMP/staging/etc/runlevels/default"
mkdir -p "$BUILD_TMP/staging/var/lib/docker"
mkdir -p "$BUILD_TMP/staging/var/droidhost/projects"
mkdir -p "$BUILD_TMP/staging/etc/droidhost"
mkdir -p "$BUILD_TMP/staging/data"
mkdir -p "$BUILD_TMP/staging/root"

# Ensure docker group exists in /etc/group
mkdir -p "$BUILD_TMP/staging/etc"
grep -q '^docker:' "$BUILD_TMP/staging/etc/group" 2>/dev/null || echo "docker:x:101:" >> "$BUILD_TMP/staging/etc/group"

# In-guest vm-agent OpenRC service
cat << 'EOF' > "$BUILD_TMP/staging/etc/init.d/vm-agent"
#!/sbin/openrc-run
name="vm-agent"
description="DroidHost VM Management Daemon"

command="/usr/local/bin/vm-agent"
command_background="yes"
pidfile="/run/vm-agent.pid"

start_pre() {
    mkdir -p /etc/droidhost /var/droidhost/projects
    if [ ! -s /etc/droidhost/agent-token ]; then
        if [ -s /sys/firmware/qemu_fw_cfg/by_name/opt/droidhost/token/raw ]; then
            cat /sys/firmware/qemu_fw_cfg/by_name/opt/droidhost/token/raw > /etc/droidhost/agent-token
            chmod 600 /etc/droidhost/agent-token
        else
            for arg in $(cat /proc/cmdline 2>/dev/null); do
                case "$arg" in
                    droidhost.token=*)
                        echo "${arg#droidhost.token=}" > /etc/droidhost/agent-token
                        chmod 600 /etc/droidhost/agent-token
                        ;;
                    vm_agent_token=*)
                        echo "${arg#vm_agent_token=}" > /etc/droidhost/agent-token
                        chmod 600 /etc/droidhost/agent-token
                        ;;
                esac
            done
        fi
    fi
    TOKEN=$(cat /etc/droidhost/agent-token 2>/dev/null || cat /etc/droidhost-token 2>/dev/null || cat /agent-token 2>/dev/null || echo "")
    if [ -n "$TOKEN" ]; then
        command_args="-addr 0.0.0.0:8899 -token $TOKEN"
    else
        command_args="-addr 0.0.0.0:8899"
    fi
}

depend() {
    need net
    after docker
}
EOF
chmod 755 "$BUILD_TMP/staging/etc/init.d/vm-agent"

# Extract token from kernel command line at boot (/etc/local.d/vm-token.start)
mkdir -p "$BUILD_TMP/staging/etc/local.d"
cat << 'EOF' > "$BUILD_TMP/staging/etc/local.d/vm-token.start"
#!/bin/sh
mkdir -p /etc/droidhost /var/droidhost/projects
if [ -s /sys/firmware/qemu_fw_cfg/by_name/opt/droidhost/token/raw ]; then
    cat /sys/firmware/qemu_fw_cfg/by_name/opt/droidhost/token/raw > /etc/droidhost/agent-token
    chmod 600 /etc/droidhost/agent-token
fi
for arg in $(cat /proc/cmdline 2>/dev/null); do
    case "$arg" in
        droidhost.token=*)
            echo "${arg#droidhost.token=}" > /etc/droidhost/agent-token
            chmod 600 /etc/droidhost/agent-token
            ;;
        vm_agent_token=*)
            echo "${arg#vm_agent_token=}" > /etc/droidhost/agent-token
            chmod 600 /etc/droidhost/agent-token
            ;;
    esac
done
if [ ! -e /dev/fd ]; then
    ln -s /proc/self/fd /dev/fd
fi
chown -R root:root /root 2>/dev/null || true
chmod 700 /root 2>/dev/null || true
mkdir -p /root/.ssh
chmod 700 /root/.ssh
EOF
chmod 755 "$BUILD_TMP/staging/etc/local.d/vm-token.start"

# Dynamic ext4 disk resize on boot
cat << 'EOF' > "$BUILD_TMP/staging/etc/local.d/disk-resize.start"
#!/bin/sh
# Automatically expands root ext4 filesystem to fill the virtual disk allocation
resize2fs /dev/vda 2>/dev/null || true
EOF
chmod 755 "$BUILD_TMP/staging/etc/local.d/disk-resize.start"

# Enable OpenRC services
ln -sf /etc/init.d/cgroups "$BUILD_TMP/staging/etc/runlevels/sysinit/cgroups" 2>/dev/null || true
ln -sf /etc/init.d/networking "$BUILD_TMP/staging/etc/runlevels/boot/networking" 2>/dev/null || true
ln -sf /etc/init.d/local "$BUILD_TMP/staging/etc/runlevels/default/local" 2>/dev/null || true
ln -sf /etc/init.d/docker "$BUILD_TMP/staging/etc/runlevels/default/docker" 2>/dev/null || true
ln -sf /etc/init.d/vm-agent "$BUILD_TMP/staging/etc/runlevels/default/vm-agent" 2>/dev/null || true
ln -sf /etc/init.d/sshd "$BUILD_TMP/staging/etc/runlevels/default/sshd" 2>/dev/null || true

# Configure /etc/inittab for serial console (ttyAMA0) autologin as root
cat << 'EOF' > "$BUILD_TMP/staging/etc/inittab"
::sysinit:/sbin/openrc sysinit
::sysinit:/sbin/openrc boot
::wait:/sbin/openrc default

# Start an autologin root shell on serial console
ttyAMA0::respawn:/sbin/getty -n -l /bin/sh 115200 ttyAMA0 vt100

::ctrlaltdel:/sbin/reboot
::shutdown:/sbin/openrc shutdown
EOF

# Network interfaces
mkdir -p "$BUILD_TMP/staging/etc/network"
cat << 'EOF' > "$BUILD_TMP/staging/etc/network/interfaces"
auto lo
iface lo inet loopback

auto eth0
iface eth0 inet dhcp
EOF

# DNS configuration: 10.0.2.3 is primary QEMU SLIRP gateway DNS; 127.0.0.1 is fallback in-guest DoH resolver
cat << 'EOF' > "$BUILD_TMP/staging/etc/resolv.conf"
nameserver 10.0.2.3
nameserver 127.0.0.1
EOF

# /etc/fstab for root on /dev/vda
cat << 'EOF' > "$BUILD_TMP/staging/etc/fstab"
/dev/vda        /               ext4    defaults,noatime        0 1
devtmpfs        /dev            devtmpfs defaults               0 0
proc            /proc           proc    defaults                0 0
sysfs           /sys            sysfs   defaults                0 0
tmpfs           /tmp            tmpfs   defaults,nosuid,nodev   0 0
EOF

# Default MOTD
cat << 'EOF' > "$BUILD_TMP/staging/etc/motd"

   ___           _     _ _   _           _   
  / _ \_ __ ___ (_) __| | |_| | ___  ___| |_ 
 / /_)/ '__/ _ \| |/ _` |  _  |/ _ \/ __| __|
/ ___/| | | (_) | | (_| | | | | (_) \__ \ |_ 
\/    |_|  \___/|_|\__,_\_| |_/\___/|___/\__|

DroidHost ARM64 Linux VM Server (Physical Device Guest)
Docker Engine is active. vm-agent listening on 0.0.0.0:8899.

EOF

# 8. Create and populate virtual disk image (droidhost.ext4)
DISK_SIZE_MB="${DISK_SIZE_MB:-2048}"
echo "==> Creating virtual ext4 disk (${DISK_SIZE_MB}MB)..."
DISK_FILE="$OUTPUT_DIR/data/droidhost.ext4"
truncate -s "${DISK_SIZE_MB}M" "$DISK_FILE"
chmod -R u+rwX "$BUILD_TMP/staging" 2>/dev/null || true
/usr/sbin/mke2fs -t ext4 -F -L droidhost-root -d "$BUILD_TMP/staging" "$DISK_FILE" "${DISK_SIZE_MB}M"
echo "    Virtual disk created successfully at $DISK_FILE"

# 9. Populate QEMU ARM64 binaries and runtime libraries
echo "==> Staging QEMU ARM64 emulator..."
"$APK_STATIC" --arch aarch64 \
    -X "${ALPINE_MIRROR}/main" \
    -X "${ALPINE_MIRROR}/community" \
    -U --allow-untrusted \
    --root "$BUILD_TMP/qemu-staging" --initdb \
    add qemu-system-aarch64 2>/dev/null || true

mkdir -p "$OUTPUT_DIR/qemu/bin" "$OUTPUT_DIR/qemu/lib" "$OUTPUT_DIR/qemu/usr/lib"
cp "$BUILD_TMP/qemu-staging/usr/bin/qemu-system-aarch64" "$OUTPUT_DIR/qemu/bin/"
cp "$BUILD_TMP/qemu-staging/lib/ld-musl-aarch64.so.1" "$OUTPUT_DIR/qemu/lib/"
cp -r "$BUILD_TMP/qemu-staging/lib"/* "$OUTPUT_DIR/qemu/lib/" 2>/dev/null || true
cp -r "$BUILD_TMP/qemu-staging/usr/lib"/* "$OUTPUT_DIR/qemu/usr/lib/" 2>/dev/null || true

# Copy ELF binaries to APK jniLibs as .so to satisfy Android 10-18+ W^X execution permissions
mkdir -p "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
cp "$OUTPUT_DIR/qemu/lib/ld-musl-aarch64.so.1" "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libld-musl-aarch64.so"
cp "$OUTPUT_DIR/qemu/bin/qemu-system-aarch64" "$REPO_ROOT/app/src/main/jniLibs/arm64-v8a/libqemu-system-aarch64.so"

# 10. Generate QEMU runner script for Android
cat << 'EOF' > "$OUTPUT_DIR/bin/qemu-system-aarch64"
#!/system/bin/sh
# DroidHost QEMU ARM64 Runner
VM_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NATIVE_LIB_DIR=${NATIVE_LIB_DIR:-}
if [ -z "$NATIVE_LIB_DIR" ]; then
    for cand in /data/app/*/com.droidhost*/lib/arm64 /data/app/~~*/com.droidhost*/lib/arm64; do
        if [ -f "$cand/libld-musl-aarch64.so" ]; then
            NATIVE_LIB_DIR="$cand"
            break
        fi
    done
fi

LD_SO="${NATIVE_LIB_DIR:+$NATIVE_LIB_DIR/libld-musl-aarch64.so}"
[ -f "$LD_SO" ] || LD_SO="$VM_DIR/qemu/lib/ld-musl-aarch64.so.1"

QEMU_BIN="${NATIVE_LIB_DIR:+$NATIVE_LIB_DIR/libqemu-system-aarch64.so}"
[ -f "$QEMU_BIN" ] || QEMU_BIN="$VM_DIR/qemu/bin/qemu-system-aarch64"

LIB_PATH="${NATIVE_LIB_DIR:+$NATIVE_LIB_DIR:}$VM_DIR/qemu/usr/lib:$VM_DIR/qemu/lib"

if [ -f "$LD_SO" ] && [ -f "$QEMU_BIN" ]; then
    exec "$LD_SO" --library-path "$LIB_PATH" "$QEMU_BIN" "$@"
else
    echo "QEMU runner error: missing $LD_SO or $QEMU_BIN" >&2
    exit 127
fi
EOF
chmod 755 "$OUTPUT_DIR/bin/qemu-system-aarch64"

# 11. Generate run-vm.sh launcher with ports 8899 and 8080 forwarded
cat << 'EOF' > "$OUTPUT_DIR/bin/run-vm.sh"
#!/system/bin/sh
# DroidHost ARM64 VM Launcher
set -eu

VM_DIR=${VM_DIR:-/data/user/0/com.droidhost/files/vm}
QEMU=${QEMU:-$VM_DIR/bin/qemu-system-aarch64}
KERNEL=${KERNEL:-$VM_DIR/boot/Image}
INITRD=${INITRD:-$VM_DIR/boot/initrd.img}
DISK=${DISK:-$VM_DIR/data/droidhost.ext4}
MEMORY_MB=${MEMORY_MB:-2048}
CPUS=${CPUS:-2}
AGENT_TOKEN_FILE=${AGENT_TOKEN_FILE:-$VM_DIR/agent-token}
SOCKET=${SOCKET:-$VM_DIR/qemu-monitor.sock}

[ -e "$QEMU" ] || [ -f "$QEMU" ] || { echo "QEMU runner not found: $QEMU" >&2; exit 78; }
[ -r "$KERNEL" ] || { echo "Linux ARM64 kernel not found: $KERNEL" >&2; exit 78; }
[ -r "$INITRD" ] || { echo "Linux initramfs not found: $INITRD" >&2; exit 78; }
[ -f "$DISK" ] || { echo "VM disk not found: $DISK" >&2; exit 78; }
[ -s "$AGENT_TOKEN_FILE" ] || { echo "vm-agent token is missing" >&2; exit 78; }

TOKEN=$(cat "$AGENT_TOKEN_FILE")

# Launch QEMU with real Linux kernel, virtio-blk rootfs, virtio-net, and port forwardings:
# - 8899: in-guest vm-agent REST and WebSocket API
# - 8080: guest container web workloads (Nginx, Coolify, Dokploy, etc.)
exec /system/bin/sh "$QEMU" \
  -machine virt,gic-version=3 \
  -cpu cortex-a57 \
  -smp "$CPUS" \
  -m "${MEMORY_MB}M" \
  -kernel "$KERNEL" \
  -initrd "$INITRD" \
  -append "console=ttyAMA0 root=/dev/vda rootflags=rw rw rootwait modules=virtio_pci,virtio_blk,virtio_net,ext4 vm_agent_token=$TOKEN" \
  -drive "if=none,file=$DISK,format=raw,id=vm-disk" \
  -device virtio-blk-pci,drive=vm-disk,romfile="" \
  -netdev user,id=net0,dns=8.8.8.8,hostfwd=tcp:127.0.0.1:8899-:8899,hostfwd=tcp:127.0.0.1:8080-:8080 \
  -device virtio-net-pci,netdev=net0,romfile="" \
  -monitor "unix:$SOCKET,server=on,wait=off" \
  -nographic
EOF
chmod 755 "$OUTPUT_DIR/bin/run-vm.sh"

# Copy run-vm.sh into repository vm/ folder as well
cp "$OUTPUT_DIR/bin/run-vm.sh" "$REPO_ROOT/vm/run-vm.sh"

# 12. Generate or preserve agent token
TOKEN_FILE="$OUTPUT_DIR/agent-token"
if [ ! -f "$TOKEN_FILE" ] || [ ! -s "$TOKEN_FILE" ]; then
    openssl rand -hex 32 > "$TOKEN_FILE"
fi

# 13. Summary and bundle creation
echo "==> Creating distributable guest bundle zip..."
BUNDLE_ZIP="$OUTPUT_DIR/vm-bundle.zip"
(
    cd "$OUTPUT_DIR"
    zip -q -r "$BUNDLE_ZIP" bin boot data qemu agent-token -x "*.zip"
)

echo ""
echo "============================================================"
echo "   Build Completed Successfully!"
echo "============================================================"
echo "Output files in $OUTPUT_DIR:"
ls -lh "$OUTPUT_DIR/boot/Image" "$OUTPUT_DIR/boot/initrd.img" "$OUTPUT_DIR/data/droidhost.ext4" "$OUTPUT_DIR/agent-token" "$BUNDLE_ZIP"
echo ""
echo "To provision to connected Android device over ADB:"
echo "  ./scripts/provision-vm.sh $BUNDLE_ZIP"
echo "============================================================"
