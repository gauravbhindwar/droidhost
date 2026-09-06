#!/bin/sh
# Automated VM bundle provisioning script for DroidHost over ADB
# Usage: ./scripts/provision-vm.sh <path-to-vm-bundle-directory-or-zip>

set -eu

BUNDLE_PATH="${1:-}"

if [ -z "$BUNDLE_PATH" ]; then
  echo "Usage: $0 <path-to-vm-bundle-directory-or-zip>"
  echo "Example: $0 ./vm-bundle.zip"
  echo "         $0 ./my-arm64-assets/"
  exit 1
fi

echo "==> Checking connected ADB devices..."
adb get-state >/dev/null 2>&1 || { echo "Error: No Android device detected via ADB" >&2; exit 1; }

echo "==> Preparing target directory on device..."
adb shell "run-as com.droidhost mkdir -p files/vm/bin files/vm/boot files/vm/data files/vm/qemu"

if [ -f "$BUNDLE_PATH" ] && [ "${BUNDLE_PATH##*.}" = "zip" ]; then
  echo "==> Pushing zip bundle to device temporary storage..."
  adb push "$BUNDLE_PATH" /data/local/tmp/vm-bundle.zip
  echo "==> Extracting bundle on device inside app container..."
  adb shell "run-as com.droidhost unzip -o /data/local/tmp/vm-bundle.zip -d files/vm/"
  adb shell "rm -f /data/local/tmp/vm-bundle.zip"
elif [ -d "$BUNDLE_PATH" ]; then
  echo "==> Pushing bundle directory to device staging..."
  adb push "$BUNDLE_PATH" /data/local/tmp/vm-staging
  echo "==> Copying assets into app-private storage..."
  adb shell "run-as com.droidhost cp -r /data/local/tmp/vm-staging/* files/vm/"
  adb shell "rm -rf /data/local/tmp/vm-staging"
else
  echo "Error: Bundle path '$BUNDLE_PATH' does not exist or is invalid." >&2
  exit 1
fi

echo "==> Setting executable permissions on emulator and runners..."
adb shell "run-as com.droidhost chmod 755 files/vm/bin/qemu-system-aarch64 files/vm/bin/run-vm.sh files/vm/qemu/lib/ld-musl-aarch64.so.1 files/vm/qemu/bin/qemu-system-aarch64 2>/dev/null || true"

echo "==> Verifying provisioned assets..."
adb shell "run-as com.droidhost ls -la files/vm/bin files/vm/boot files/vm/data"

echo "==> Done! Open DroidHost or tap 'Refresh' in Settings -> Guest VM Asset Diagnostics."
