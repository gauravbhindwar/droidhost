# Architecture

```text
+---------------------------------------------------------------+
| Android phone                                                  |
|                                                               |
|  Jetpack Compose control panel                                |
|       |                                                        |
|  ViewModel -> Repository -> authenticated HTTP/WebSocket      |
|       |                                                        |
|  Foreground server service / lifecycle / port forwarding       |
|       |                                                        |
|  QEMU system emulator (AArch64, persistent ext4 disk)         |
+-------|-------------------------------------------------------+
        | localhost forwarded vm-agent port
        v
+---------------------------------------------------------------+
| Real ARM64 Linux guest                                        |
|                                                               |
|  vm-agent: HTTP API + PTY WebSocket + metrics                 |
|       |                                                        |
|  Linux kernel -> Docker Engine -> containerd -> runc           |
|       |                                                        |
|  user workloads (including optional Coolify installation)      |
+---------------------------------------------------------------+
```

The Android layer controls the VM but is not the workload runtime. The VM is
the boundary in which Docker and the PTY shell run. The agent exposes only
explicit management operations and requires a per-installation bearer token.

## State

The Android lifecycle state machine distinguishes stopped, starting, running,
stopping, and failed. A VM is only shown as running after the agent is reachable
and metrics have been read. Foreground service recovery is conservative: a
failure stops server mode rather than creating an uncontrolled restart loop.

## Networking

QEMU user networking forwards selected Android loopback ports into the guest.
The agent port is forwarded for control; user port forwards are explicit config
records and are checked for collisions before QEMU is restarted.
