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
