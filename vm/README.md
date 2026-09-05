# ARM64 guest bundle

The Android application bundles a real `qemu-system-aarch64`, an ARM64 Linux
kernel (`Image`), an initramfs containing the Docker-capable guest userspace,
and a persistent ext4 disk. The Android host never substitutes its own shell
for the guest.

`run-vm.sh` is the launch contract used by the foreground service. It refuses
to start if any required asset or the per-installation vm-agent token is absent.
QEMU user networking forwards `127.0.0.1:8899` on Android to the authenticated
vm-agent on port 8899 in the guest.

The guest image must start `vm-agent` with the same token passed in the kernel
command line. Build/release tooling is intentionally separate from the runtime
script so a release cannot silently create an empty or fake disk.
