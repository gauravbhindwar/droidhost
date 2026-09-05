# Development

## Toolchain

Use JDK 17, Android SDK platform 35, Android build tools, Go 1.22, and Docker.
The checked-in Gradle wrapper is the supported Android build entrypoint.

```sh
./gradlew testDebugUnitTest assembleDebug
(cd vm-agent && go test ./...)
```

## Boot the VM

A real guest bundle is required; the runtime deliberately does not create a
fake kernel, fake shell, or empty Docker response. Place these files under the
app-private VM directory:

- `bin/qemu-system-aarch64`
- `boot/Image` (ARM64 Linux Image)
- `boot/initrd.img`
- `data/droidhost.ext4`
- `agent-token`

Run `vm/validate-assets.sh` with `VM_DIR` set to the bundle directory. Set
`MEMORY_MB`, `CPUS`, and `DISK` as needed, then run `vm/run-vm.sh`. The guest
init system must start Docker and vm-agent on port 8899 using the same token.

## Test Docker

From inside the guest, verify:

```sh
docker info
docker run --rm hello-world
curl -H "Authorization: Bearer $VM_AGENT_TOKEN" http://127.0.0.1:8899/health
curl -H "Authorization: Bearer $VM_AGENT_TOKEN" http://127.0.0.1:8899/v1/containers?all=1
```

The Go agent tests do not pretend to boot a guest. They test Docker response
mapping, log framing, and parsing. Integration testing should be run against a
booted ARM64 guest with Docker available.

## Agent development

```sh
cd vm-agent
go test ./...
go run . --token "$VM_AGENT_TOKEN"
```

Never commit a token, VM disk, kernel, initrd, or generated Android SDK files.
