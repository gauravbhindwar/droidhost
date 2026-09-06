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
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
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
    private var continuousWatchdogJob: Job? = null
    private var consecutiveCrashCount = 0
    private var lastCrashTimestamp = 0L

    val tokenFile: File
        get() = File(vmDir, "agent-token")

    init {
        scope.launch(Dispatchers.IO) {
            getOrGenerateToken()
            if (agentRepository.checkHealth()) {
                _vmState.value = VmState.RUNNING
                startContinuousWatchdog()
            }
        }
    }

    fun getVmDirectory(): File = vmDir

    fun loadServerProfile(): com.droidhost.domain.ServerProfile {
        val profileFile = File(context.filesDir, "server-profile.json")
        if (profileFile.exists()) {
            try {
                val json = profileFile.readText()
                return kotlinx.serialization.json.Json.decodeFromString(com.droidhost.domain.ServerProfile.serializer(), json)
            } catch (_: Exception) {}
        }
        return com.droidhost.domain.ServerProfile(name = "Standard", cpuCores = 2, memoryMb = 2048, diskGb = 32, autoStart = false)
    }

    fun saveServerProfile(profile: com.droidhost.domain.ServerProfile) {
        val profileFile = File(context.filesDir, "server-profile.json")
        try {
            val json = kotlinx.serialization.json.Json.encodeToString(com.droidhost.domain.ServerProfile.serializer(), profile)
            profileFile.writeText(json)
        } catch (_: Exception) {}
    }

    fun growDisk(newSizeGb: Int): Result<Unit> {
        val diskFile = File(vmDir, "data/droidhost.ext4")
        if (!diskFile.exists()) {
            return Result.failure(IllegalStateException("VM disk does not exist"))
        }
        val currentBytes = diskFile.length()
        val targetBytes = newSizeGb.toLong() * 1024L * 1024L * 1024L
        if (targetBytes < currentBytes) {
            val currentGb = (currentBytes / (1024L * 1024L * 1024L)).toInt()
            return Result.failure(IllegalStateException("Cannot shrink virtual disk from ${currentGb} GB to ${newSizeGb} GB. Disks can only grow."))
        }
        if (targetBytes == currentBytes) {
            return Result.success(Unit)
        }
        return try {
            RandomAccessFile(diskFile, "rw").use { raf ->
                raf.setLength(targetBytes)
            }
            val profile = loadServerProfile()
            saveServerProfile(profile.copy(diskGb = newSizeGb))
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getOrGenerateToken(): String {
        val tokenFileInFiles = File(context.filesDir, "agent-token")
        var token = if (tokenFileInFiles.exists()) tokenFileInFiles.readText().trim() else ""
        if (token.isEmpty()) {
            val random = SecureRandom()
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            token = bytes.joinToString("") { "%02x".format(it) }
            try {
                tokenFileInFiles.writeText(token)
                tokenFileInFiles.setReadable(true, true)
                tokenFileInFiles.setWritable(true, true)
            } catch (_: Exception) {}
        }

        try {
            if (!vmDir.exists()) vmDir.mkdirs()
            tokenFile.writeText(token)
            tokenFile.setReadable(true, true)
            tokenFile.setWritable(true, true)
        } catch (_: Exception) {}
        return token
    }

    fun getQemuExecutable(): File {
        val runnerScript = File(vmDir, "bin/qemu-system-aarch64")
        installQemuRunnerScript(runnerScript)
        return runnerScript
    }

    /**
     * Resolves the best DNS server for QEMU SLIRP's -netdev dns= parameter.
     * Priority: WiFi gateway > router DNS > 8.8.8.8 fallback.
     * Android carrier networks block outbound UDP:53 to external IPs (e.g. 1.1.1.1)
     * so we always prefer the local router which relays DNS correctly.
     */
    /**
     * Converts a raw Android DhcpInfo int IP (little-endian) to dotted-decimal string.
     */
    private fun dhcpIntToIp(ip: Int): String {
        return "${ip and 0xFF}.${(ip shr 8) and 0xFF}.${(ip shr 16) and 0xFF}.${(ip shr 24) and 0xFF}"
    }

    /**
     * Resolves the best DNS server for QEMU SLIRP's -netdev dns= parameter.
     * Must be a public routable IP (e.g. 8.8.8.8) because QEMU SLIRP user-mode
     * networking cannot route to private RFC1918 LAN IPs (192.168.x.x, 10.x.x.x).
     */
    private fun isPrivateIp(ip: String): Boolean {
        return ip.startsWith("192.168.") || ip.startsWith("10.") ||
               ip.startsWith("127.") || ip.startsWith("169.254.") ||
               (ip.startsWith("172.") && ip.split(".").getOrNull(1)?.toIntOrNull() in 16..31)
    }

    fun discoverDnsConfig(): com.droidhost.domain.DnsConfig {
        val upstreams = mutableListOf<String>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = cm?.activeNetwork
            val lp = activeNet?.let { cm.getLinkProperties(it) }
            if (lp != null) {
                for (dns in lp.dnsServers) {
                    val host = dns.hostAddress ?: continue
                    if (!host.contains(":") && !isPrivateIp(host)) {
                        upstreams.add(host)
                    }
                }
            }
        } catch (_: Exception) {}
        if (upstreams.isEmpty()) {
            upstreams.add("8.8.8.8")
            upstreams.add("1.1.1.1")
        }
        return com.droidhost.domain.DnsConfig(
            guestDns = "10.0.2.3",
            dohFallbackAddress = "127.0.0.1",
            upstreamDns = upstreams
        )
    }

    fun cleanupStaleProcesses() {
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/killall", "-9", "libld-musl-aarch64.so", "qemu-system-aarch64")).waitFor()
        } catch (_: Exception) {}
        try {
            Runtime.getRuntime().exec(arrayOf("/system/bin/pkill", "-9", "-f", "qemu-system-aarch64")).waitFor()
        } catch (_: Exception) {}
    }

    fun installQemuRunnerScript(target: File) {
        try {
            target.parentFile?.mkdirs()
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val template = """
                #!/system/bin/sh
                # DroidHost QEMU ARM64 Runner
                VM_DIR="${vmDir.absolutePath}"
                NATIVE_LIB_DIR="${nativeDir}"
                if [ ! -f "${'$'}NATIVE_LIB_DIR/libld-musl-aarch64.so" ]; then
                    for cand in /data/app/*/com.droidhost*/lib/arm64 /data/app/~~*/com.droidhost*/lib/arm64; do
                        if [ -f "${'$'}cand/libld-musl-aarch64.so" ]; then
                            NATIVE_LIB_DIR="${'$'}cand"
                            break
                        fi
                    done
                fi

                LD_SO="${'$'}{NATIVE_LIB_DIR}/libld-musl-aarch64.so"
                QEMU_BIN="${'$'}{NATIVE_LIB_DIR}/libqemu-system-aarch64.so"
                LIB_PATH="${'$'}NATIVE_LIB_DIR:${'$'}VM_DIR/qemu/usr/lib:${'$'}VM_DIR/qemu/lib"

                if [ -f "${'$'}LD_SO" ] && [ -f "${'$'}QEMU_BIN" ]; then
                    exec "${'$'}LD_SO" --library-path "${'$'}LIB_PATH" "${'$'}QEMU_BIN" "${'$'}@"
                else
                    echo "QEMU runner error: missing ${'$'}LD_SO or ${'$'}QEMU_BIN" >&2
                    exit 127
                fi
            """.trimIndent()
            target.writeText(template)
            target.setExecutable(true, false)
            target.setReadable(true, false)
        } catch (_: Exception) {}
    }

    fun getRunVmScript(): File {
        val script = File(vmDir, "bin/run-vm.sh")
        installRunVmScript(script)
        return script
    }

    fun installRunVmScript(target: File) {
        try {
            target.parentFile?.mkdirs()
            installQemuRunnerScript(File(vmDir, "bin/qemu-system-aarch64"))
            val dnsConfig = discoverDnsConfig()
            val netConfig = com.droidhost.domain.VmNetworkConfig(
                guestAddress = "10.0.2.15",
                gateway = "10.0.2.2",
                dns = dnsConfig,
                portForwards = com.droidhost.domain.VmNetworkConfig.defaultPortForwards
            )
            val backend: com.droidhost.domain.VmNetworkBackend = com.droidhost.domain.SlirpNetworkBackend()
            val qemuNetArgs = backend.buildQemuArguments(netConfig).joinToString(" \\\n                  ")
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

                exec /system/bin/sh "${'$'}QEMU" \
                  -machine virt,gic-version=3 \
                  -cpu cortex-a57 \
                  -smp "${'$'}CPUS" \
                  -m "${'$'}{MEMORY_MB}M" \
                  -kernel "${'$'}KERNEL" \
                  -initrd "${'$'}INITRD" \
                  -append "console=ttyAMA0 root=/dev/vda rootflags=rw rw rootwait modules=virtio_pci,virtio_blk,virtio_net,ext4 droidhost.token=${'$'}TOKEN vm_agent_token=${'$'}TOKEN" \
                  -drive "if=none,file=${'$'}DISK,format=raw,id=vm-disk" \
                  -device virtio-blk-pci,drive=vm-disk,romfile="" \
                  $qemuNetArgs \
                  -fw_cfg name=opt/droidhost/token,string="${'$'}TOKEN" \
                  -monitor "unix:${'$'}SOCKET,server=on,wait=off" \
                  -nographic
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
            qemuFile.exists() -> "QEMU ARM64 runner (${qemuFile.length()} B)"
            else -> "Not found"
        }

        val qemuStatus = AssetStatus(
            path = if (nativeLib.exists() && nativeLib.canExecute()) nativeLib.absolutePath else qemuFile.absolutePath,
            exists = qemuExists,
            sizeBytes = if (nativeLib.exists()) nativeLib.length() else if (qemuFile.exists()) qemuFile.length() else 0,
            details = qemuDetails
        )

        val kernelValid = kernelFile.exists() && kernelFile.length() > 512 * 1024
        val kernelDetails = if (kernelValid) {
            "ARM64 Linux Kernel (${kernelFile.length() / 1024 / 1024} MB)"
        } else if (kernelFile.exists()) {
            "Invalid kernel size (${kernelFile.length()} B)"
        } else {
            "Not found"
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
                cleanupStaleProcesses()
                val command = listOf(
                    "/system/bin/sh",
                    runVmScript.absolutePath
                )

                val pb = ProcessBuilder(command)
                pb.directory(vmDir)
                pb.environment()["VM_DIR"] = vmDir.absolutePath
                pb.environment()["QEMU"] = qemuBinary.absolutePath
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
                    qemuProcess = null

                    if (_vmState.value != VmState.STOPPING && _vmState.value != VmState.STOPPED) {
                        val fullOutput = outputLines.joinToString("\n").trim()
                        handleUnexpectedVmTermination(exitCode, fullOutput)
                    } else {
                        _vmState.value = VmState.STOPPED
                    }
                }

                // Poll for guest agent health
                pollForAgentReadiness(maxAttempts = 40)

            } catch (e: Exception) {
                _lastError.value = "Failed to launch VM process: ${e.message}"
                _vmState.value = VmState.FAILED
            }
        }
    }

    private fun handleUnexpectedVmTermination(exitCode: Int, fullOutput: String) {
        continuousWatchdogJob?.cancel()
        val now = System.currentTimeMillis()
        if (now - lastCrashTimestamp < 60_000L) {
            consecutiveCrashCount++
        } else {
            consecutiveCrashCount = 1
        }
        lastCrashTimestamp = now

        val errorDetail = when {
            exitCode == 78 -> "VM asset check failed (code 78): $fullOutput"
            fullOutput.isNotBlank() -> "VM exited ($exitCode): $fullOutput"
            else -> "VM terminated unexpectedly with exit code $exitCode"
        }

        if (consecutiveCrashCount >= 3) {
            _vmState.value = VmState.FAILED
            _lastError.value = "Crash-loop detected (3 crashes in 60s): $errorDetail"
        } else {
            val backoffSec = consecutiveCrashCount * 2
            _lastError.value = "VM crashed ($errorDetail). Auto-recovering in ${backoffSec}s (attempt $consecutiveCrashCount/3)..."
            scope.launch(Dispatchers.IO) {
                delay(backoffSec * 1000L)
                if (_vmState.value != VmState.STOPPING && _vmState.value != VmState.STOPPED) {
                    start()
                }
            }
        }
    }

    private fun startContinuousWatchdog() {
        continuousWatchdogJob?.cancel()
        continuousWatchdogJob = scope.launch(Dispatchers.IO) {
            var consecutiveHealthFailures = 0
            while (_vmState.value == VmState.RUNNING) {
                delay(5000)
                if (agentRepository.checkHealth()) {
                    consecutiveHealthFailures = 0
                    consecutiveCrashCount = 0
                } else {
                    consecutiveHealthFailures++
                    if (consecutiveHealthFailures >= 6) {
                        android.util.Log.w("VmManager", "vm-agent unresponsive for 30s during active run")
                    }
                }
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
                    consecutiveCrashCount = 0
                    startContinuousWatchdog()
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
                qemuProcess?.destroy()
                delay(1000)
                if (qemuProcess?.isAlive == true) {
                    qemuProcess?.destroyForcibly()
                }
            } catch (_: Exception) {
            } finally {
                cleanupStaleProcesses()
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

    suspend fun generatePreSetup(diskSizeGb: Int = 32, onProgress: (String) -> Unit): AssetValidationReport = withContext(Dispatchers.IO) {
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
        installRunVmScript(File(binDir, "run-vm.sh"))

        // 3. Extract built-in APK assets (kernel Image, companion initrd.img, etc.)
        onProgress("Checking embedded VM assets...")
        extractAssetsRecursively("vm", vmDir, onProgress)

        // 3b. Extract QEMU shared libraries if packaged
        val qemuUsrLib = File(vmDir, "qemu/usr/lib")
        if (!qemuUsrLib.exists() || (qemuUsrLib.list()?.size ?: 0) < 10) {
            try {
                val hasQemuTar = context.assets.list("vm")?.contains("qemu-libs.dh") == true
                if (hasQemuTar) {
                    extractTarGzAsset("vm/qemu-libs.dh", vmDir, onProgress)
                }
            } catch (_: Exception) {}
        }

        // 4. Extract base rootfs disk if present in assets as split parts or .gz
        val diskFile = File(dataDir, "droidhost.ext4")
        if (!diskFile.exists() || diskFile.length() < 1024L * 1024L) {
            val baseFile = File(dataDir, "droidhost-base.ext4")
            if (baseFile.exists() && baseFile.length() > 1024L * 1024L) {
                onProgress("Copying base disk...")
                baseFile.copyTo(diskFile, overwrite = true)
            } else {
                val splitSuccess = extractSplitGzAssets("vm/data", diskFile, onProgress)
                if (!splitSuccess) {
                    try {
                        val hasGzAsset = context.assets.list("vm/data")?.contains("droidhost-base.ext4.gz") == true
                        if (hasGzAsset) {
                            extractGzAsset("vm/data/droidhost-base.ext4.gz", diskFile, onProgress)
                        }
                    } catch (_: Exception) {}
                }
            }
        }

        // 5. Check local Download folder fallback if still missing
        val kernelFile = File(bootDir, "Image")
        val initrdFile = File(bootDir, "initrd.img")
        if (!kernelFile.exists() || kernelFile.length() < 512 * 1024 ||
            !initrdFile.exists() || initrdFile.length() < 100 * 1024 ||
            !diskFile.exists() || diskFile.length() < 1024L * 1024L
        ) {
            val downloadCandidates = listOf(
                File("/storage/emulated/0/Download/vm-bundle.zip"),
                File("/sdcard/Download/vm-bundle.zip"),
                File(context.getExternalFilesDir(null), "vm-bundle.zip")
            )
            val foundZip = downloadCandidates.firstOrNull { it.exists() && it.length() > 1024 * 1024 }
            if (foundZip != null) {
                onProgress("Found local bundle at ${foundZip.name}, extracting...")
                foundZip.inputStream().use { stream ->
                    extractBundleZip(stream, onProgress)
                }
            }
        }

        // 6. If still missing, download the official release bundle
        if (!kernelFile.exists() || kernelFile.length() < 512 * 1024 ||
            !initrdFile.exists() || initrdFile.length() < 100 * 1024 ||
            !diskFile.exists() || diskFile.length() < 1024L * 1024L
        ) {
            try {
                onProgress("Downloading guest runtime package...")
                downloadAndExtractBundle(DEFAULT_BUNDLE_URL, onProgress)
            } catch (e: Exception) {
                android.util.Log.w("VmManager", "Online bundle download skipped or failed: ${e.message}")
            }
        }

        // 7. Initialize/Expand virtual ext4 disk capacity (sparse)
        if (diskFile.exists() && diskFile.length() > 0) {
            val totalBytes = diskSizeGb.coerceAtLeast(4).toLong() * 1024L * 1024L * 1024L
            if (diskFile.length() < totalBytes) {
                onProgress("Expanding virtual disk capacity to ${diskSizeGb} GB (sparse)...")
                try {
                    RandomAccessFile(diskFile, "rw").use { raf ->
                        raf.setLength(totalBytes)
                    }
                } catch (_: Exception) {}
            }
        }

        // 8. Set permissions
        val qemuFile = File(binDir, "qemu-system-aarch64")
        if (qemuFile.exists()) {
            qemuFile.setExecutable(true, false)
            qemuFile.setReadable(true, false)
        }
        val runScript = File(binDir, "run-vm.sh")
        if (runScript.exists()) {
            runScript.setExecutable(true, false)
            runScript.setReadable(true, false)
        }

        onProgress("Validating VM environment...")
        validateAssets()
    }

    private fun extractSplitGzAssets(baseAssetDir: String, targetFile: File, onProgress: (String) -> Unit): Boolean {
        try {
            val allAssets = context.assets.list(baseAssetDir) ?: return false
            val list = allAssets.filter { it.startsWith("rootfs_part") || it.startsWith("rootfs.ext4.gz.part") }.sorted()
            if (list.isEmpty()) {
                android.util.Log.w("VmManager", "No split rootfs parts found in $baseAssetDir (found: ${allAssets.joinToString()})")
                return false
            }

            android.util.Log.i("VmManager", "Found ${list.size} split parts in $baseAssetDir: $list")
            targetFile.parentFile?.mkdirs()
            onProgress("Extracting Ext4 Linux VM root disk from app package...")

            val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
            if (tempFile.exists()) tempFile.delete()

            val iterator = list.iterator()
            val lazyEnumeration = object : java.util.Enumeration<InputStream> {
                override fun hasMoreElements(): Boolean = iterator.hasNext()
                override fun nextElement(): InputStream {
                    val partName = iterator.next()
                    android.util.Log.i("VmManager", "Streaming asset part: $baseAssetDir/$partName")
                    return context.assets.open("$baseAssetDir/$partName")
                }
            }
            val combinedStream = java.io.SequenceInputStream(lazyEnumeration)

            val buffer = ByteArray(64 * 1024)
            var totalBytesWritten = 0L
            var lastTime = 0L

            combinedStream.use { rawIn ->
                GZIPInputStream(rawIn.buffered(64 * 1024)).use { gzIn ->
                    FileOutputStream(tempFile).use { out ->
                        var read: Int
                        while (gzIn.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            totalBytesWritten += read
                            val now = System.currentTimeMillis()
                            if (now - lastTime > 300) {
                                lastTime = now
                                val mb = totalBytesWritten / (1024 * 1024)
                                onProgress("Extracting Ext4 VM disk: $mb MB...")
                            }
                        }
                        out.flush()
                    }
                }
            }

            if (tempFile.exists() && tempFile.length() > 10 * 1024L * 1024L) {
                if (targetFile.exists()) targetFile.delete()
                val renamed = tempFile.renameTo(targetFile)
                if (!renamed) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
                android.util.Log.i("VmManager", "Successfully extracted rootfs to ${targetFile.absolutePath} (${targetFile.length() / 1024 / 1024} MB)")
                return true
            } else {
                android.util.Log.e("VmManager", "Extracted temp file is too small or missing: ${tempFile.length()} bytes")
                return false
            }
        } catch (e: Exception) {
            android.util.Log.e("VmManager", "Failed to extract split GZ assets: ${e.message}", e)
            return false
        }
    }

    private fun extractAssetsRecursively(assetPath: String, targetDir: File, onProgress: (String) -> Unit) {
        try {
            val list = context.assets.list(assetPath) ?: return
            if (list.isEmpty()) {
                // Leaf file
                if (assetPath.endsWith(".gz") || assetPath.endsWith(".tar") || assetPath.contains(".part") || assetPath.endsWith(".dh")) return
                val destFile = resolveBundleDestFile(assetPath, targetDir)
                if (destFile != null) {
                    destFile.parentFile?.mkdirs()
                    onProgress("Installing ${destFile.name} from app package...")
                    context.assets.open(assetPath).use { input ->
                        FileOutputStream(destFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (destFile.name == "qemu-system-aarch64" || destFile.name.endsWith(".sh") || destFile.parentFile?.name == "bin") {
                        destFile.setExecutable(true, false)
                        destFile.setReadable(true, false)
                    }
                }
            } else {
                for (child in list) {
                    val subPath = if (assetPath.isEmpty()) child else "$assetPath/$child"
                    extractAssetsRecursively(subPath, targetDir, onProgress)
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractGzAsset(assetPath: String, targetFile: File, onProgress: (String) -> Unit) {
        try {
            targetFile.parentFile?.mkdirs()
            onProgress("Extracting ${targetFile.name} from app package...")
            val buffer = ByteArray(64 * 1024)
            var totalBytesWritten = 0L
            var lastTime = 0L
            context.assets.open(assetPath).use { rawIn ->
                GZIPInputStream(rawIn.buffered()).use { gzIn ->
                    FileOutputStream(targetFile).use { out ->
                        var read: Int
                        while (gzIn.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            totalBytesWritten += read
                            val now = System.currentTimeMillis()
                            if (now - lastTime > 300) {
                                lastTime = now
                                val mb = totalBytesWritten / (1024 * 1024)
                                onProgress("Extracting Linux disk: $mb MB...")
                            }
                        }
                        out.flush()
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("VmManager", "Failed to extract GZ asset $assetPath: ${e.message}")
        }
    }

    private fun extractTarGzAsset(assetPath: String, targetDir: File, onProgress: (String) -> Unit): Boolean {
        try {
            targetDir.mkdirs()
            onProgress("Installing QEMU runtime libraries...")
            context.assets.open(assetPath).use { rawIn ->
                GZIPInputStream(rawIn.buffered(64 * 1024)).use { tarIn ->
                    val header = ByteArray(512)
                    while (true) {
                        var bytesRead = 0
                        while (bytesRead < 512) {
                            val r = tarIn.read(header, bytesRead, 512 - bytesRead)
                            if (r == -1) break
                            bytesRead += r
                        }
                        if (bytesRead < 512) break

                        // Check for empty block (end of archive)
                        if (header.all { it == 0.toByte() }) break

                        val nameRaw = String(header, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                        val prefixRaw = String(header, 345, 155, Charsets.UTF_8).trimEnd('\u0000', ' ')
                        val entryName = if (prefixRaw.isNotEmpty()) "$prefixRaw/$nameRaw" else nameRaw
                        if (entryName.isEmpty() || entryName.contains("..")) continue

                        val sizeStr = String(header, 124, 12, Charsets.UTF_8).trimEnd('\u0000', ' ').trim()
                        val size = sizeStr.toLongOrNull(8) ?: 0L
                        val typeFlag = header[156] // '0' or 0 = file, '2' = symlink, '5' = dir

                        val destFile = File(targetDir, entryName)

                        if (typeFlag == '5'.toByte() || entryName.endsWith("/")) {
                            destFile.mkdirs()
                        } else if (typeFlag == '2'.toByte()) {
                            val linkTarget = String(header, 157, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                            destFile.parentFile?.mkdirs()
                            try {
                                val targetPath = java.nio.file.Paths.get(linkTarget)
                                java.nio.file.Files.deleteIfExists(destFile.toPath())
                                java.nio.file.Files.createSymbolicLink(destFile.toPath(), targetPath)
                            } catch (_: Exception) {
                                val src = File(destFile.parentFile, linkTarget)
                                if (src.exists()) src.copyTo(destFile, overwrite = true)
                            }
                        } else {
                            destFile.parentFile?.mkdirs()
                            FileOutputStream(destFile).use { out ->
                                val buffer = ByteArray(32 * 1024)
                                var remaining = size
                                while (remaining > 0) {
                                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                    val r = tarIn.read(buffer, 0, toRead)
                                    if (r == -1) break
                                    out.write(buffer, 0, r)
                                    remaining -= r
                                }
                            }
                            val pad = (512 - (size % 512)) % 512
                            if (pad > 0) {
                                var skipped = 0L
                                while (skipped < pad) {
                                    val r = tarIn.read(header, 0, (pad - skipped).toInt())
                                    if (r == -1) break
                                    skipped += r
                                }
                            }
                            if (destFile.name.endsWith(".so") || destFile.name.contains(".so.") || destFile.parentFile?.name == "bin") {
                                destFile.setExecutable(true, false)
                                destFile.setReadable(true, false)
                            }
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            android.util.Log.e("VmManager", "Failed to extract tar.gz asset $assetPath: ${e.message}", e)
            return false
        }
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
        const val DEFAULT_BUNDLE_URL = "https://github.com/droidhost/droidhost/releases/download/v1.0.0/vm-bundle.zip"

        @Volatile
        private var instance: VmManager? = null

        fun getInstance(context: Context, agentRepository: AgentRepository): VmManager {
            return instance ?: synchronized(this) {
                instance ?: VmManager(context.applicationContext, agentRepository).also { instance = it }
            }
        }
    }
}
