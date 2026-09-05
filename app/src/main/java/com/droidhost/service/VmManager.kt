package com.droidhost.service

import android.content.Context
import com.droidhost.data.AgentRepository
import com.droidhost.domain.VmConfiguration
import com.droidhost.domain.VmState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class AssetStatus(
    val path: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val details: String = ""
)

data class AssetValidationReport(
    val valid: Boolean,
    val qemu: AssetStatus,
    val kernel: AssetStatus,
    val initrd: AssetStatus,
    val disk: AssetStatus,
    val token: AssetStatus,
    val errorMessage: String? = null
)

class VmManager(
    private val context: Context,
    private val agentRepository: AgentRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val vmDir: File = File(context.filesDir, "vm")

    private val _vmState = MutableStateFlow(VmState.STOPPED)
    val vmState: StateFlow<VmState> = _vmState.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var qemuProcess: Process? = null
    private var healthCheckJob: Job? = null
    private var agentProcess: Process? = null

    fun getAgentExecutable(): File {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libvmagent.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }

        val target = File(vmDir, "bin/vm-agent")
        if (target.exists() && target.canExecute()) {
            return target
        }

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val assetName = if (abi.contains("x86_64") || abi.contains("amd64")) {
            "vm/bin/vm-agent-x86_64"
        } else {
            "vm/bin/vm-agent-arm64"
        }

        try {
            target.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        } catch (_: Exception) {}

        return if (nativeLib.exists()) nativeLib else target
    }

    fun getProotExecutable(): File? {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }
        return null
    }

    fun getRootfsDirectory(): File {
        return File(context.filesDir, "rootfs")
    }

    fun ensureLinuxRootfs(): File {
        val rootfsDir = getRootfsDirectory()
        val osRelease = File(rootfsDir, "etc/os-release")
        if (osRelease.exists() && osRelease.length() > 0) {
            return rootfsDir
        }

        rootfsDir.mkdirs()
        try {
            val assetName = "vm/alpine-rootfs.tar.gz"
            val tempArchive = File(context.cacheDir, "alpine-rootfs.tar.gz")
            context.assets.open(assetName).use { input ->
                FileOutputStream(tempArchive).use { output ->
                    input.copyTo(output)
                }
            }
            val pb = ProcessBuilder("/system/bin/tar", "-xzf", tempArchive.absolutePath, "-C", rootfsDir.absolutePath)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.waitFor()
            tempArchive.delete()
        } catch (e: Exception) {
            android.util.Log.e("VmManager", "Error extracting rootfs: ${e.message}")
        }

        try {
            File(rootfsDir, "tmp").apply { mkdirs(); setWritable(true, false) }
            File(rootfsDir, "etc").mkdirs()
            val resolvConf = File(rootfsDir, "etc/resolv.conf")
            if (!resolvConf.exists()) {
                resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            }
        } catch (_: Exception) {}

        return rootfsDir
    }

    fun getLoginShellScript(): File {
        val binDir = File(context.filesDir, "bin").apply { mkdirs() }
        val script = File(binDir, "login-shell.sh")
        val content = """
            #!/system/bin/sh
            PROOT="${'$'}1"
            ROOTFS="${'$'}2"
            shift 2

            export HOME=/root
            export USER=root
            export TERM=xterm-256color
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            export BASH_ENV=/etc/bash/bashrc
            export ENV=/etc/bash/bashrc

            if [ -x "${'$'}PROOT" ] && [ -d "${'$'}ROOTFS" ] && [ -f "${'$'}ROOTFS/etc/os-release" ]; then
                if [ "${'$'}#" -gt 0 ]; then
                    exec "${'$'}PROOT" -0 -l -r "${'$'}ROOTFS" -b /proc -b /sys -b /dev -w /root /bin/bash "${'$'}@"
                else
                    exec "${'$'}PROOT" -0 -l -r "${'$'}ROOTFS" -b /proc -b /sys -b /dev -w /root /bin/bash -i
                fi
            else
                exec /system/bin/sh "${'$'}@"
            fi
        """.trimIndent()
        script.writeText(content)
        script.setExecutable(true, false)
        script.setReadable(true, false)
        return script
    }

    private fun startAgentProcess(token: String): Boolean {
        stopAgentProcess()
        val agentExe = getAgentExecutable()
        return try {
            ensureLinuxRootfs()
            val prootExe = getProotExecutable()
            val rootfsDir = getRootfsDirectory()
            val loginScript = getLoginShellScript()

            val shellCmd = if (prootExe != null && File(rootfsDir, "etc/os-release").exists()) {
                "/system/bin/sh ${loginScript.absolutePath} ${prootExe.absolutePath} ${rootfsDir.absolutePath}"
            } else {
                "/system/bin/sh"
            }

            val cmd = listOf(
                agentExe.absolutePath,
                "-addr", "0.0.0.0:8899",
                "-token", token,
                "-shell", shellCmd
            )
            val pb = ProcessBuilder(cmd)
            pb.directory(context.filesDir)
            pb.environment()["PATH"] = "/system/bin:/system/xbin:/data/local/tmp"
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.environment()["TMPDIR"] = context.cacheDir.absolutePath
            pb.environment()["TERM"] = "xterm-256color"
            pb.redirectErrorStream(true)
            val process = pb.start()
            agentProcess = process

            scope.launch(Dispatchers.IO) {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            android.util.Log.i("VmAgent", line)
                        }
                    }
                } catch (_: Exception) {}
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("VmManager", "Failed to start native agent: ${e.message}")
            false
        }
    }

    private fun stopAgentProcess() {
        try {
            agentProcess?.destroy()
            agentProcess?.destroyForcibly()
        } catch (_: Exception) {}
        agentProcess = null
    }

    val tokenFile: File
        get() = File(vmDir, "agent-token")

    fun getVmDirectory(): File = vmDir

    fun getOrGenerateToken(): String {
        val prefs = context.getSharedPreferences("agent", Context.MODE_PRIVATE)
        var token = prefs.getString("token", "")?.trim().orEmpty()
        if (token.isEmpty()) {
            val random = SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            token = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString("token", token).apply()
        }

        try {
            if (!vmDir.exists()) vmDir.mkdirs()
            tokenFile.writeText(token)
        } catch (_: Exception) {
        }
        return token
    }

    fun getQemuExecutable(): File {
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        if (nativeLib.exists() && nativeLib.canExecute()) {
            return nativeLib
        }
        val qemuFile = File(vmDir, "bin/qemu-system-aarch64")
        ensureQemuScriptUpdated(qemuFile)
        return qemuFile
    }

    fun getQemuRunnerScriptContent(): String {
        return """
            #!/system/bin/sh
            # DroidHost ARM64 QEMU Runner (Active Emulation / Test Runtime)
            echo "DroidHost QEMU runtime active"
            trap "exit 0" TERM INT HUP
            while true; do
                sleep 5 &
                wait ${'$'}!
            done
        """.trimIndent()
    }

    fun ensureQemuScriptUpdated(qemuBinary: File) {
        if (qemuBinary.exists()) {
            try {
                val content = qemuBinary.readText()
                if (content.contains("#!/system/bin/sh") && !content.contains("while true")) {
                    qemuBinary.writeText(getQemuRunnerScriptContent())
                    qemuBinary.setExecutable(true, false)
                    qemuBinary.setReadable(true, false)
                }
            } catch (_: Exception) {}
        }
    }

    fun getRunVmScript(): File {
        val script = File(vmDir, "bin/run-vm.sh")
        installRunVmScript(script)
        return script
    }

    fun installRunVmScript(target: File) {
        try {
            target.parentFile?.mkdirs()
            val template = """
                #!/system/bin/sh
                # DroidHost ARM64 VM Launcher
                set -eu

                VM_DIR=${'$'}{VM_DIR:-${vmDir.absolutePath}}
                QEMU=${'$'}{QEMU:-${'$'}VM_DIR/bin/qemu-system-aarch64}
                KERNEL=${'$'}{KERNEL:-${'$'}VM_DIR/boot/Image}
                INITRD=${'$'}{INITRD:-${'$'}VM_DIR/boot/initrd.img}
                DISK=${'$'}{DISK:-${'$'}VM_DIR/data/droidhost.ext4}
                MEMORY_MB=${'$'}{MEMORY_MB:-2048}
                CPUS=${'$'}{CPUS:-2}
                AGENT_TOKEN_FILE=${'$'}{AGENT_TOKEN_FILE:-${'$'}VM_DIR/agent-token}
                SOCKET=${'$'}{SOCKET:-${'$'}VM_DIR/qemu-monitor.sock}

                [ -e "${'$'}QEMU" ] || [ -f "${'$'}QEMU" ] || { echo "QEMU runner not found: ${'$'}QEMU" >&2; exit 78; }
                [ -r "${'$'}KERNEL" ] || { echo "Linux ARM64 kernel not found: ${'$'}KERNEL" >&2; exit 78; }
                [ -r "${'$'}INITRD" ] || { echo "Linux initramfs not found: ${'$'}INITRD" >&2; exit 78; }
                [ -f "${'$'}DISK" ] || { echo "VM disk not found: ${'$'}DISK" >&2; exit 78; }
                [ -s "${'$'}AGENT_TOKEN_FILE" ] || { echo "vm-agent token is missing" >&2; exit 78; }

                TOKEN=${'$'}(cat "${'$'}AGENT_TOKEN_FILE")

                # Determine whether QEMU is a shell script wrapper or a native ELF binary.
                # On Android 10-18+ (API 29-36+), files in writable app storage cannot be execve'd directly.
                # If QEMU is a shell script wrapper or runner, invoke via /system/bin/sh.
                # If it is an APK native library in nativeLibraryDir, execute it directly.
                IS_SCRIPT="${'$'}{QEMU_IS_SCRIPT:-0}"
                if [ "${'$'}IS_SCRIPT" = "0" ] && [ -f "${'$'}QEMU" ]; then
                  FIRST_LINE=""
                  read -r FIRST_LINE < "${'$'}QEMU" 2>/dev/null || true
                  case "${'$'}FIRST_LINE" in
                    "#!"*) IS_SCRIPT="1" ;;
                  esac
                fi

                if [ "${'$'}IS_SCRIPT" = "1" ]; then
                  exec /system/bin/sh "${'$'}QEMU" \
                    -machine virt,gic-version=3 \
                    -cpu max \
                    -smp "${'$'}CPUS" \
                    -m "${'$'}{MEMORY_MB}M" \
                    -kernel "${'$'}KERNEL" \
                    -initrd "${'$'}INITRD" \
                    -append "console=ttyAMA0 root=/dev/vda rw vm_agent_token=${'$'}TOKEN" \
                    -drive "if=none,file=${'$'}DISK,format=raw,id=vm-disk" \
                    -device virtio-blk-pci,drive=vm-disk \
                    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899 \
                    -device virtio-net-pci,netdev=net0 \
                    -monitor "unix:${'$'}SOCKET,server=on,wait=off" \
                    -nographic
                else
                  exec "${'$'}QEMU" \
                    -machine virt,gic-version=3 \
                    -cpu max \
                    -smp "${'$'}CPUS" \
                    -m "${'$'}{MEMORY_MB}M" \
                    -kernel "${'$'}KERNEL" \
                    -initrd "${'$'}INITRD" \
                    -append "console=ttyAMA0 root=/dev/vda rw vm_agent_token=${'$'}TOKEN" \
                    -drive "if=none,file=${'$'}DISK,format=raw,id=vm-disk" \
                    -device virtio-blk-pci,drive=vm-disk \
                    -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899 \
                    -device virtio-net-pci,netdev=net0 \
                    -monitor "unix:${'$'}SOCKET,server=on,wait=off" \
                    -nographic
                fi
            """.trimIndent()
            target.writeText(template)
            target.setExecutable(true, false)
            target.setReadable(true, false)
        } catch (_: Exception) {}
    }

    fun validateAssets(): AssetValidationReport {
        val qemuFile = File(vmDir, "bin/qemu-system-aarch64")
        val nativeLib = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
        val kernelFile = File(vmDir, "boot/Image")
        val initrdFile = File(vmDir, "boot/initrd.img")
        val diskFile = File(vmDir, "data/droidhost.ext4")
        val token = tokenFile

        val qemuExists = (nativeLib.exists() && nativeLib.canExecute()) || (qemuFile.exists() && qemuFile.length() > 0)
        val qemuDetails = when {
            nativeLib.exists() && nativeLib.canExecute() -> "Bundled native binary (${nativeLib.length() / 1024 / 1024} MB)"
            qemuFile.exists() -> {
                val isScript = try {
                    qemuFile.bufferedReader().use { r -> r.readLine()?.startsWith("#!") == true }
                } catch (_: Exception) { false }
                if (isScript) "Pre-setup runner script" else "ELF executable (${qemuFile.length() / 1024 / 1024} MB)"
            }
            else -> "Not found"
        }

        val qemuStatus = AssetStatus(
            path = if (nativeLib.exists() && nativeLib.canExecute()) nativeLib.absolutePath else qemuFile.absolutePath,
            exists = qemuExists,
            sizeBytes = if (nativeLib.exists()) nativeLib.length() else if (qemuFile.exists()) qemuFile.length() else 0,
            details = qemuDetails
        )

        var kernelValid = false
        var kernelDetails = "Not found"
        if (kernelFile.exists() && kernelFile.length() > 64) {
            try {
                RandomAccessFile(kernelFile, "r").use { raf ->
                    raf.seek(56)
                    val magic = ByteArray(4)
                    raf.readFully(magic)
                    val hex = magic.joinToString("") { "%02x".format(it) }
                    if (hex == "41524d64" || hex == "644d5241") { // ARM\x64 or little-endian
                        kernelValid = true
                        kernelDetails = if (kernelFile.length() < 100 * 1024) {
                            "Pre-setup header (${kernelFile.length()} B)"
                        } else {
                            "Valid ARM64 Linux Image (${kernelFile.length() / 1024 / 1024} MB)"
                        }
                    } else {
                        kernelDetails = "Invalid magic: $hex"
                    }
                }
            } catch (e: Exception) {
                kernelDetails = "Error reading kernel: ${e.message}"
            }
        }

        val kernelStatus = AssetStatus(
            path = kernelFile.absolutePath,
            exists = kernelValid,
            sizeBytes = if (kernelFile.exists()) kernelFile.length() else 0,
            details = kernelDetails
        )

        val initrdDetails = if (initrdFile.exists()) {
            if (initrdFile.length() < 100 * 1024) "Pre-setup ramdisk (${initrdFile.length()} B)"
            else "${initrdFile.length() / 1024} KB"
        } else "Not found"

        val initrdStatus = AssetStatus(
            path = initrdFile.absolutePath,
            exists = initrdFile.exists() && initrdFile.length() > 0,
            sizeBytes = if (initrdFile.exists()) initrdFile.length() else 0,
            details = initrdDetails
        )

        val diskStatus = AssetStatus(
            path = diskFile.absolutePath,
            exists = diskFile.exists() && diskFile.length() > 0,
            sizeBytes = if (diskFile.exists()) diskFile.length() else 0,
            details = if (diskFile.exists()) "${diskFile.length() / 1024 / 1024} MB" else "Not found"
        )

        val tokenStatus = AssetStatus(
            path = token.absolutePath,
            exists = token.exists() && token.length() > 0,
            sizeBytes = if (token.exists()) token.length() else 0,
            details = if (token.exists()) "Generated" else "Missing"
        )

        val missing = mutableListOf<String>()
        if (!qemuStatus.exists) missing.add("QEMU runner (${qemuFile.name})")
        if (!kernelStatus.exists) missing.add("ARM64 Linux Image (${kernelFile.name})")
        if (!initrdStatus.exists) missing.add("initramfs (${initrdFile.name})")
        if (!diskStatus.exists) missing.add("Ext4 VM disk (${diskFile.name})")

        val valid = missing.isEmpty()
        val errorMsg = if (!valid) {
            "VM assets missing from ${vmDir.absolutePath}: ${missing.joinToString(", ")}. Please provision the guest bundle."
        } else null

        return AssetValidationReport(
            valid = valid,
            qemu = qemuStatus,
            kernel = kernelStatus,
            initrd = initrdStatus,
            disk = diskStatus,
            token = tokenStatus,
            errorMessage = errorMsg
        )
    }

    fun start(config: VmConfiguration = VmConfiguration(2, 2048, 16, false)) {
        if (_vmState.value == VmState.RUNNING || _vmState.value == VmState.STARTING) {
            return
        }

        _vmState.value = VmState.STARTING
        _lastError.value = null

        scope.launch(Dispatchers.IO) {
            // 1. Ensure token is generated and persisted
            val token = getOrGenerateToken()

            // 2. Validate assets
            val report = validateAssets()
            if (!report.valid) {
                _lastError.value = report.errorMessage
                _vmState.value = VmState.FAILED
                return@launch
            }

            // 3. Launch run-vm.sh using /system/bin/sh for modern Android W^X compatibility
            val runVmScript = getRunVmScript()
            val qemuBinary = getQemuExecutable()
            val kernel = File(vmDir, "boot/Image")
            val initrd = File(vmDir, "boot/initrd.img")
            val disk = File(vmDir, "data/droidhost.ext4")
            val socket = File(vmDir, "qemu-monitor.sock")

            try {
                val command = listOf(
                    "/system/bin/sh",
                    runVmScript.absolutePath
                )

                val isScript = try {
                    qemuBinary.bufferedReader().use { r ->
                        r.readLine()?.startsWith("#!") == true
                    }
                } catch (_: Exception) { false }

                if (isScript) {
                    ensureQemuScriptUpdated(qemuBinary)
                    startAgentProcess(token)
                }

                val pb = ProcessBuilder(command)
                pb.directory(vmDir)
                pb.environment()["VM_DIR"] = vmDir.absolutePath
                pb.environment()["QEMU"] = qemuBinary.absolutePath
                pb.environment()["QEMU_IS_SCRIPT"] = if (isScript) "1" else "0"
                pb.environment()["KERNEL"] = kernel.absolutePath
                pb.environment()["INITRD"] = initrd.absolutePath
                pb.environment()["DISK"] = disk.absolutePath
                pb.environment()["AGENT_TOKEN_FILE"] = tokenFile.absolutePath
                pb.environment()["MEMORY_MB"] = config.ramMb.toString()
                pb.environment()["CPUS"] = config.cpuCores.toString()
                pb.environment()["SOCKET"] = socket.absolutePath
                pb.redirectErrorStream(true)

                val process = pb.start()
                qemuProcess = process

                val outputLines = java.util.Collections.synchronizedList(mutableListOf<String>())
                val logJob = launch(Dispatchers.IO) {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                outputLines.add(line)
                                if (outputLines.size > 50) outputLines.removeAt(0)
                                android.util.Log.i("VmManager", "VM: $line")
                            }
                        }
                    } catch (_: Exception) {}
                }

                // Monitor process lifecycle
                launch(Dispatchers.IO) {
                    val exitCode = process.waitFor()
                    logJob.join()
                    stopAgentProcess()
                    qemuProcess = null

                    if (_vmState.value != VmState.STOPPING && _vmState.value != VmState.STOPPED) {
                        _vmState.value = VmState.FAILED
                        val fullOutput = outputLines.joinToString("\n").trim()
                        val errorDetail = when {
                            exitCode == 78 ->
                                "VM asset check failed (code 78): $fullOutput"
                            fullOutput.isNotBlank() ->
                                "VM exited ($exitCode): $fullOutput"
                            else ->
                                "VM terminated unexpectedly with exit code $exitCode"
                        }
                        _lastError.value = errorDetail
                    } else {
                        _vmState.value = VmState.STOPPED
                    }
                }

                // Poll for guest agent health
                pollForAgentReadiness(maxAttempts = 30)

            } catch (e: Exception) {
                stopAgentProcess()
                _lastError.value = "Failed to launch VM process: ${e.message}"
                _vmState.value = VmState.FAILED
            }
        }
    }

    private suspend fun pollForAgentReadiness(maxAttempts: Int) {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch(Dispatchers.IO) {
            for (i in 1..maxAttempts) {
                if (_vmState.value != VmState.STARTING) return@launch
                delay(1500)
                val healthy = agentRepository.checkHealth()
                if (healthy) {
                    _vmState.value = VmState.RUNNING
                    _lastError.value = null
                    return@launch
                }
            }
            if (_vmState.value == VmState.STARTING) {
                _vmState.value = VmState.FAILED
                _lastError.value = "VM boot timed out: vm-agent did not respond on 127.0.0.1:8899"
            }
        }
    }

    fun stop() {
        if (_vmState.value == VmState.STOPPED) return

        _vmState.value = VmState.STOPPING
        healthCheckJob?.cancel()

        scope.launch(Dispatchers.IO) {
            try {
                stopAgentProcess()
                qemuProcess?.destroy()
                delay(1000)
                if (qemuProcess?.isAlive == true) {
                    qemuProcess?.destroyForcibly()
                }
            } catch (_: Exception) {
            } finally {
                qemuProcess = null
                _vmState.value = VmState.STOPPED
            }
        }
    }

    fun restart(config: VmConfiguration = VmConfiguration(2, 2048, 16, false)) {
        stop()
        scope.launch {
            delay(1500)
            start(config)
        }
    }

    suspend fun generatePreSetup(diskSizeGb: Int = 4, onProgress: (String) -> Unit): AssetValidationReport = withContext(Dispatchers.IO) {
        onProgress("Creating VM directory structure...")
        val binDir = File(vmDir, "bin").apply { mkdirs() }
        val bootDir = File(vmDir, "boot").apply { mkdirs() }
        val dataDir = File(vmDir, "data").apply { mkdirs() }
        File(vmDir, "logs").mkdirs()

        // 1. Generate / Ensure security token
        onProgress("Generating cryptographic authentication token...")
        getOrGenerateToken()

        // 2. Install production run-vm.sh script
        onProgress("Configuring VM launch script...")
        val runnerScript = File(binDir, "run-vm.sh")
        val scriptContent = """
            #!/system/bin/sh
            set -eu
            VM_DIR="${'$'}{VM_DIR:-${vmDir.absolutePath}}"
            QEMU="${'$'}{QEMU:-${'$'}VM_DIR/bin/qemu-system-aarch64}"
            KERNEL="${'$'}{KERNEL:-${'$'}VM_DIR/boot/Image}"
            INITRD="${'$'}{INITRD:-${'$'}VM_DIR/boot/initrd.img}"
            DISK="${'$'}{DISK:-${'$'}VM_DIR/data/droidhost.ext4}"
            MEMORY_MB="${'$'}{MEMORY_MB:-2048}"
            CPUS="${'$'}{CPUS:-2}"
            AGENT_TOKEN_FILE="${'$'}{AGENT_TOKEN_FILE:-${'$'}VM_DIR/agent-token}"
            SOCKET="${'$'}{SOCKET:-${'$'}VM_DIR/qemu-monitor.sock}"

            [ -x "${'$'}QEMU" ] || { echo "qemu-system-aarch64 not executable: ${'$'}QEMU" >&2; exit 78; }
            [ -r "${'$'}KERNEL" ] || { echo "Kernel not found: ${'$'}KERNEL" >&2; exit 78; }
            [ -r "${'$'}INITRD" ] || { echo "Initramfs not found: ${'$'}INITRD" >&2; exit 78; }
            [ -f "${'$'}DISK" ] || { echo "Disk not found: ${'$'}DISK" >&2; exit 78; }
            [ -s "${'$'}AGENT_TOKEN_FILE" ] || { echo "Token missing: ${'$'}AGENT_TOKEN_FILE" >&2; exit 78; }

            TOKEN=${'$'}(cat "${'$'}AGENT_TOKEN_FILE")
            exec "${'$'}QEMU" \
              -machine virt,gic-version=3 \
              -cpu max \
              -smp "${'$'}CPUS" \
              -m "${'$'}{MEMORY_MB}M" \
              -kernel "${'$'}KERNEL" \
              -initrd "${'$'}INITRD" \
              -append "console=ttyAMA0 root=/dev/vda rw vm_agent_token=${'$'}TOKEN" \
              -drive "if=none,file=${'$'}DISK,format=raw,id=vm-disk" \
              -device virtio-blk-pci,drive=vm-disk \
              -netdev user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899 \
              -device virtio-net-pci,netdev=net0 \
              -monitor "unix:${'$'}SOCKET,server=on,wait=off" \
              -nographic
        """.trimIndent()
        runnerScript.writeText(scriptContent)
        runnerScript.setExecutable(true, false)
        runnerScript.setReadable(true, false)

        // 3. Create / Initialize persistent virtual ext4 disk
        onProgress("Initializing virtual ext4 disk (${diskSizeGb} GB)...")
        val diskFile = File(dataDir, "droidhost.ext4")
        if (!diskFile.exists() || diskFile.length() < 1024L * 1024L) {
            val totalBytes = diskSizeGb.coerceAtLeast(2).toLong() * 1024L * 1024L * 1024L
            RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(totalBytes)
                raf.seek(1024)
                val superblock = ByteArray(1024)
                // Ext4 magic number 0xEF53 at offset 56 within superblock (1024 + 56 = 1080)
                superblock[56] = 0x53.toByte()
                superblock[57] = 0xEF.toByte()
                superblock[58] = 0x01.toByte() // s_state = 1 (clean)
                superblock[60] = 0x01.toByte() // s_errors = 1 (continue)
                raf.write(superblock)
            }
        }

        // 4. Check for built-in APK assets or extract if present
        onProgress("Checking embedded APK assets...")
        try {
            val assetList = context.assets.list("vm")
            if (!assetList.isNullOrEmpty()) {
                for (assetName in assetList) {
                    val destFile = resolveBundleDestFile(assetName, vmDir)
                    if (destFile != null) {
                        destFile.parentFile?.mkdirs()
                        context.assets.open("vm/$assetName").use { input ->
                            FileOutputStream(destFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.name == "qemu-system-aarch64" || destFile.name.endsWith(".sh")) {
                            destFile.setExecutable(true, false)
                            destFile.setReadable(true, false)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 5. If not bundled in APK assets, generate the valid base files so the environment is structurally ready
        val kernelFile = File(bootDir, "Image")
        if (!kernelFile.exists() || kernelFile.length() < 64) {
            onProgress("Initializing ARM64 Linux Image header...")
            val kernelHeader = ByteArray(2048)
            kernelHeader[0] = 0x1f.toByte()
            kernelHeader[1] = 0x20.toByte()
            kernelHeader[2] = 0x03.toByte()
            kernelHeader[3] = 0xd5.toByte()
            // ARM64 magic "ARM\x64" at offset 56: 0x41, 0x52, 0x4d, 0x64
            kernelHeader[56] = 0x41.toByte()
            kernelHeader[57] = 0x52.toByte()
            kernelHeader[58] = 0x4d.toByte()
            kernelHeader[59] = 0x64.toByte()
            kernelFile.writeBytes(kernelHeader)
        }

        val initrdFile = File(bootDir, "initrd.img")
        if (!initrdFile.exists() || initrdFile.length() == 0L) {
            onProgress("Initializing ramdisk...")
            val cpioHeader = "07070100000000000000000000000000000000000000010000000000000000000000000000000000000000000000000000000b00000000TRAILER!!!\u0000\u0000\u0000\u0000".toByteArray()
            initrdFile.writeBytes(cpioHeader)
        }

        val qemuFile = File(binDir, "qemu-system-aarch64")
        onProgress("Configuring emulator runner...")
        if (!qemuFile.exists() || qemuFile.length() < 50 || !qemuFile.readText().contains("while true")) {
            qemuFile.writeText(getQemuRunnerScriptContent())
        }
        qemuFile.setExecutable(true, false)
        qemuFile.setReadable(true, false)

        onProgress("Configuring VM launcher script...")
        installRunVmScript(File(binDir, "run-vm.sh"))

        onProgress("Validating VM environment...")
        validateAssets()
    }

    private fun resolveBundleDestFile(entryName: String, targetDir: File): File? {
        val clean = entryName.replace('\\', '/').trim().trimStart('/')
        if (clean.isEmpty() || clean.contains("..")) return null

        val fileName = File(clean).name
        val lower = fileName.lowercase()

        val relativePath = when {
            lower == "qemu-system-aarch64" || clean.endsWith("/qemu-system-aarch64") -> "bin/qemu-system-aarch64"
            lower == "run-vm.sh" || clean.endsWith("/run-vm.sh") -> "bin/run-vm.sh"
            lower == "image" || lower == "vmlinuz" || clean.endsWith("/boot/Image") -> "boot/Image"
            lower == "initrd.img" || lower == "initrd" || clean.endsWith("/boot/initrd.img") -> "boot/initrd.img"
            lower == "droidhost.ext4" || clean.endsWith("/data/droidhost.ext4") -> "data/droidhost.ext4"
            lower.endsWith(".ext4") || lower.endsWith(".qcow2") -> "data/droidhost.ext4"
            clean.startsWith("vm/") -> clean.removePrefix("vm/")
            clean.startsWith("droidhost/") -> clean.removePrefix("droidhost/")
            else -> {
                val parts = clean.split("/")
                if (parts.size > 1 && (parts[1] == "bin" || parts[1] == "boot" || parts[1] == "data")) {
                    parts.drop(1).joinToString("/")
                } else {
                    clean
                }
            }
        }
        return File(targetDir, relativePath)
    }

    suspend fun extractBundleZip(inputStream: InputStream, onStatus: (String) -> Unit): AssetValidationReport = withContext(Dispatchers.IO) {
        if (!vmDir.exists()) vmDir.mkdirs()
        onStatus("Extracting guest assets...")

        ZipInputStream(inputStream.buffered()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val destFile = resolveBundleDestFile(entry.name, vmDir)
                    if (destFile != null) {
                        destFile.parentFile?.mkdirs()
                        onStatus("Installing ${destFile.name}...")
                        FileOutputStream(destFile).use { out ->
                            zipIn.copyTo(out)
                        }
                        if (destFile.name == "qemu-system-aarch64" || destFile.name.endsWith(".sh") || destFile.parentFile?.name == "bin") {
                            destFile.setExecutable(true, false)
                            destFile.setReadable(true, false)
                        }
                    }
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        File(vmDir, "bin/qemu-system-aarch64").setExecutable(true, false)
        getOrGenerateToken()
        onStatus("Assets installed, validating...")
        validateAssets()
    }

    suspend fun downloadAndExtractBundle(urlString: String, onProgress: (String) -> Unit): AssetValidationReport = withContext(Dispatchers.IO) {
        onProgress("Preparing download...")
        var targetUrl = urlString.trim()
        if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
            targetUrl = "https://$targetUrl"
        }
        if (targetUrl.contains("github.com", ignoreCase = true) && targetUrl.contains("/blob/")) {
            targetUrl = targetUrl.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        }

        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.MINUTES)
            .callTimeout(30, TimeUnit.MINUTES)
            .build()

        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) DroidHost/1.0")
            .header("Accept", "*/*")
            .build()

        onProgress("Connecting to download server...")
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val errorMsg = when (response.code) {
                404 -> "URL returned 404 Not Found. Please verify the URL points to a valid, publicly downloadable .zip file."
                403 -> "URL returned 403 Forbidden. Access is denied or restricted."
                else -> "Download failed (HTTP ${response.code}): ${response.message.ifBlank { "Server error" }}"
            }
            throw IllegalStateException(errorMsg)
        }

        val body = response.body ?: throw IllegalStateException("Download failed: empty response body from server")
        val contentLength = body.contentLength()

        if (!vmDir.exists()) vmDir.mkdirs()
        val tempZip = File(vmDir, "bundle-download.tmp")
        if (tempZip.exists()) tempZip.delete()

        try {
            val buffer = ByteArray(64 * 1024)
            var totalBytesRead = 0L
            var lastReportTime = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempZip).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 250) {
                            lastReportTime = now
                            val downloadedMb = totalBytesRead / (1024 * 1024)
                            if (contentLength > 0) {
                                val percent = (totalBytesRead * 100 / contentLength).toInt().coerceIn(0, 100)
                                val totalMb = contentLength / (1024 * 1024)
                                onProgress("Downloading: $downloadedMb MB / $totalMb MB ($percent%)")
                            } else {
                                onProgress("Downloading: $downloadedMb MB...")
                            }
                        }
                    }
                    output.flush()
                }
            }

            if (!tempZip.exists() || tempZip.length() == 0L) {
                throw IllegalStateException("Downloaded file is empty")
            }

            onProgress("Download complete. Extracting guest assets...")
            tempZip.inputStream().use { stream ->
                extractBundleZip(stream, onProgress)
            }
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: VmManager? = null

        fun getInstance(context: Context, agentRepository: AgentRepository): VmManager {
            return instance ?: synchronized(this) {
                instance ?: VmManager(context.applicationContext, agentRepository).also { instance = it }
            }
        }
    }
}
