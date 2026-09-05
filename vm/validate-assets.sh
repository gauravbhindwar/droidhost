#!/system/bin/sh
# Validate a VM bundle before launching it; never report a VM as available when
# a kernel, initrd, disk, emulator, or authenticated agent token is absent.
set -eu
VM_DIR=${VM_DIR:-/data/user/0/com.droidhost/files/vm}
for asset in "$VM_DIR/bin/qemu-system-aarch64" "$VM_DIR/boot/Image" "$VM_DIR/boot/initrd.img" "$VM_DIR/data/droidhost.ext4" "$VM_DIR/agent-token"; do
  if [ ! -s "$asset" ]; then echo "missing VM asset: $asset" >&2; exit 78; fi
done
[ -x "$VM_DIR/bin/qemu-system-aarch64" ] || { echo "QEMU is not executable" >&2; exit 78; }
# AArch64 Linux Image files start with a valid header magic at offset 0x38 in
# the kernel image. Avoid parsing arbitrary host files as guest kernels.
MAGIC=$(dd if="$VM_DIR/boot/Image" bs=1 skip=56 count=4 2>/dev/null | od -An -tx1 | tr -d ' \n')
[ "$MAGIC" = "644d5241" ] || { echo "invalid ARM64 Linux Image header" >&2; exit 78; }
echo "VM assets valid"
