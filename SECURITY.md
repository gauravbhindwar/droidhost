# Security

## Android / VM boundary

Android owns the app data directory, QEMU process, VM disk, and forwarded
sockets. Workloads execute in the real ARM64 Linux guest. The app does not
blindly execute privileged Android shell commands and does not expose an
Android shell through the terminal UI.

## vm-agent authentication

The agent refuses to start without a bearer token. Every non-health request
must include the exact token. Generate a random token per installation and
store it in app-private storage with restrictive permissions; inject it into
the guest bundle at provisioning time. Do not use a shared release token.

## Port forwarding

Only configured local ports are forwarded. Bind local services to loopback by
default, validate both ports as numeric ranges, reject collisions, and do not
allow an arbitrary host interface to be selected without an explicit warning.

## Docker access

The Docker socket is consumed only by vm-agent inside the VM. Docker controls
are narrow operations (list, inspect, start, stop, restart, remove, logs,
stats). Environment values are never returned to the Android UI; only keys are
shown. A workload with Docker socket access can control Docker and is therefore
privileged within the guest.

## Filesystem isolation

The VM disk is a separate ext4 image. Container mounts are displayed for
operator awareness. Android paths are not bind-mounted into workloads by
implicit behavior. Backups and export/import must be explicit user actions.

## Limitations

QEMU user networking is not a hardened network firewall. A VM escape or kernel,
QEMU, Docker, or guest vulnerability can cross the intended boundary. Root in
the guest is not equivalent to Android root, but it is powerful inside the VM.
Keep QEMU, the guest kernel, Docker, and vm-agent updated and do not expose the
agent port to untrusted networks.
