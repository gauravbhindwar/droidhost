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

    fun validateAssets(): AssetValidationReport {
        val qemuFile = File(vmDir, "bin/qemu-system-aarch64")
        val kernelFile = File(vmDir, "boot/Image")
        val initrdFile = File(vmDir, "boot/initrd.img")
        val diskFile = File(vmDir, "data/droidhost.ext4")
        val token = tokenFile

        val qemuStatus = AssetStatus(
            path = qemuFile.absolutePath,
            exists = qemuFile.canExecute(),
            sizeBytes = if (qemuFile.exists()) qemuFile.length() else 0,
            details = if (qemuFile.canExecute()) "Executable" else if (qemuFile.exists()) "Not executable" else "Not found"
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
                        kernelDetails = "Valid ARM64 Linux Image header"
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

        val initrdStatus = AssetStatus(
            path = initrdFile.absolutePath,
            exists = initrdFile.exists() && initrdFile.length() > 0,
            sizeBytes = if (initrdFile.exists()) initrdFile.length() else 0,
            details = if (initrdFile.exists()) "${initrdFile.length() / 1024} KB" else "Not found"
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
        if (!qemuStatus.exists) missing.add("QEMU binary (${qemuFile.name})")
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

            // 3. Launch run-vm.sh or direct QEMU
            val qemuBinary = File(vmDir, "bin/qemu-system-aarch64")
            val kernel = File(vmDir, "boot/Image")
            val initrd = File(vmDir, "boot/initrd.img")
            val disk = File(vmDir, "data/droidhost.ext4")
            val socket = File(vmDir, "qemu-monitor.sock")

            try {
                val command = listOf(
                    qemuBinary.absolutePath,
                    "-machine", "virt,gic-version=3",
                    "-cpu", "max",
                    "-smp", config.cpuCores.toString(),
                    "-m", "${config.ramMb}M",
                    "-kernel", kernel.absolutePath,
                    "-initrd", initrd.absolutePath,
                    "-append", "console=ttyAMA0 root=/dev/vda rw vm_agent_token=$token",
                    "-drive", "if=none,file=${disk.absolutePath},format=raw,id=vm-disk",
                    "-device", "virtio-blk-pci,drive=vm-disk",
                    "-netdev", "user,id=net0,hostfwd=tcp:127.0.0.1:8899-:8899",
                    "-device", "virtio-net-pci,netdev=net0",
                    "-monitor", "unix:${socket.absolutePath},server=on,wait=off",
                    "-nographic"
                )

                val pb = ProcessBuilder(command)
                pb.directory(vmDir)
                pb.environment()["VM_DIR"] = vmDir.absolutePath
                pb.redirectErrorStream(true)

                val process = pb.start()
                qemuProcess = process

                // Monitor process lifecycle
                launch {
                    val exitCode = process.waitFor()
                    if (_vmState.value != VmState.STOPPING && _vmState.value != VmState.STOPPED) {
                        _vmState.value = VmState.FAILED
                        _lastError.value = "QEMU terminated unexpectedly with exit code $exitCode"
                    } else {
                        _vmState.value = VmState.STOPPED
                    }
                    qemuProcess = null
                }

                // Poll for guest agent health
                pollForAgentReadiness(maxAttempts = 30)

            } catch (e: Exception) {
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
            throw IllegalStateException("Download failed (HTTP ${response.code}): ${response.message.ifBlank { "Server returned error" }}")
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
