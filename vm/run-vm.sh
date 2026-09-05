#!/system/bin/sh
# Boot the real ARM64 Linux guest. This script runs on the Android host and is
# invoked by the foreground server service after it has validated resources.
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

[ -e "$QEMU" ] || [ -f "$QEMU" ] || { echo "qemu-system-aarch64 not found: $QEMU" >&2; exit 78; }
[ -r "$KERNEL" ] || { echo "Linux ARM64 kernel not found: $KERNEL" >&2; exit 78; }
[ -r "$INITRD" ] || { echo "Linux initramfs not found: $INITRD" >&2; exit 78; }
[ -f "$DISK" ] || { echo "VM disk not found: $DISK" >&2; exit 78; }
[ -s "$AGENT_TOKEN_FILE" ] || { echo "vm-agent token is missing" >&2; exit 78; }

TOKEN=$(cat "$AGENT_TOKEN_FILE")

# Determine whether QEMU is a shell script wrapper or a native ELF binary.
# On Android 10-18+ (API 29-36+), files in writable app storage cannot be execve'd directly.
# If QEMU is a shell script wrapper or runner, invoke via /system/bin/sh.
# If it is an APK native library in nativeLibraryDir, execute it directly.
IS_SCRIPT="${QEMU_IS_SCRIPT:-0}"
if [ "$IS_SCRIPT" = "0" ] && [ -f "$QEMU" ]; then
  FIRST_LINE=""
  read -r FIRST_LINE < "$QEMU" 2>/dev/null || true
  case "$FIRST_LINE" in
    "#!"*) IS_SCRIPT="1" ;;
  esac
fi

if [ "$IS_SCRIPT" = "1" ]; then
  exec /system/bin/sh "$QEMU" \
    -machine virt,gic-version=3 \
    -cpu max \
    -smp "$CPUS" \
    -m "${MEMORY_MB}M" \
    -kernel "$KERNEL" \
    -initrd "$INITRD" \
    -append "console=ttyAMA0 root=/dev/vda rw vm_agent_token=$TOKEN" \
    -drive "if=none,file=$DISK,format=raw,id=vm-disk" \
    -device virtio-blk-pci,drive=vm-disk \
    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899 \
    -device virtio-net-pci,netdev=net0 \
    -monitor "unix:$SOCKET,server=on,wait=off" \
    -nographic
else
  exec "$QEMU" \
    -machine virt,gic-version=3 \
    -cpu max \
    -smp "$CPUS" \
    -m "${MEMORY_MB}M" \
    -kernel "$KERNEL" \
    -initrd "$INITRD" \
    -append "console=ttyAMA0 root=/dev/vda rw vm_agent_token=$TOKEN" \
    -drive "if=none,file=$DISK,format=raw,id=vm-disk" \
    -device virtio-blk-pci,drive=vm-disk \
    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899 \
    -device virtio-net-pci,netdev=net0 \
    -monitor "unix:$SOCKET,server=on,wait=off" \
    -nographic
fi
