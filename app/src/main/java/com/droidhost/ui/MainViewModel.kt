package com.droidhost.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.droidhost.data.AgentRepository
import com.droidhost.data.TerminalConnectionState
import com.droidhost.data.TerminalRepository
import com.droidhost.domain.*
import com.droidhost.service.AssetValidationReport
import com.droidhost.service.VmManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

enum class ContainerFilter { ALL, RUNNING, STOPPED }

data class DashboardState(
    val metrics: Metrics = Metrics(),
    val vmState: VmState = VmState.STOPPED,
    val containers: List<Container> = emptyList(),
    val images: Int = 0,
    val volumes: Int = 0,
    val networks: Int = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val vmError: String? = null,
    // Container management
    val containerSearchQuery: String = "",
    val containerFilter: ContainerFilter = ContainerFilter.ALL,
    val selectedContainerDetail: ContainerDetail? = null,
    val selectedContainerStats: ContainerStats? = null,
    val selectedContainerLogs: String? = null,
    val isLoadingContainerDetail: Boolean = false,
    // Terminal
    val terminalOutput: String = "",
    val terminalState: TerminalConnectionState = TerminalConnectionState.DISCONNECTED,
    // Port forwarding
    val portForwardRules: List<PortForwardRule> = listOf(
        PortForwardRule(id = "1", hostPort = 8000, guestPort = 8000, protocol = "tcp", enabled = true),
        PortForwardRule(id = "2", hostPort = 8080, guestPort = 8080, protocol = "tcp", enabled = false)
    ),
    // Settings & Hardware
    val vmConfig: VmConfiguration = VmConfiguration(cpuCores = 2, ramMb = 2048, diskGb = 4, autoStart = false),
    val deviceResources: DeviceResources = DeviceResources(cpuCores = 8, totalRamMb = 8192, availableStorageGb = 64),
    val configValidation: ConfigValidation = ConfigValidation(true, emptyList()),
    val assetReport: AssetValidationReport? = null,
    // Provisioning
    val isProvisioning: Boolean = false,
    val provisioningStatus: String? = null,
    // Battery optimization
    val isBatteryOptimized: Boolean = false,
    val showBatteryOptimizationDialog: Boolean = false,
    val hasDismissedBatteryDialog: Boolean = false
)

class MainViewModel(
    private val repository: AgentRepository,
    private val vmManager: VmManager? = null,
    private val terminalRepository: TerminalRepository? = null,
    private val context: Context? = null
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private var pollingJob: Job? = null
    private var statsJob: Job? = null
    private var pendingVmStart = false

    init {
        detectDeviceResources()
        loadSavedSettings()
        checkAssets()
        checkBatteryOptimization(autoPrompt = true)
        observeVmManager()
        observeTerminal()
        startPolling()
    }

    private fun detectDeviceResources() {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        var ramMb = 4096
        var storageGb = 32

        if (context != null) {
            try {
                val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                if (actManager != null) {
                    val memInfo = ActivityManager.MemoryInfo()
                    actManager.getMemoryInfo(memInfo)
                    ramMb = (memInfo.totalMem / (1024 * 1024)).toInt()
                }

                val stat = StatFs(Environment.getDataDirectory().path)
                storageGb = ((stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024 * 1024)).toInt().coerceAtLeast(4)
            } catch (_: Exception) {
            }
        }

        val resources = DeviceResources(cpuCores = cores, totalRamMb = ramMb, availableStorageGb = storageGb)
        val maxDisk = (resources.availableStorageGb - 2).coerceAtLeast(4)
        val maxRam = (resources.totalRamMb * 3 / 4).coerceAtLeast(512)
        val current = _state.value.vmConfig
        val clampedConfig = current.copy(
            cpuCores = current.cpuCores.coerceIn(1, resources.cpuCores),
            ramMb = current.ramMb.coerceIn(512, maxRam),
            diskGb = current.diskGb.coerceIn(4, maxDisk)
        )
        val validation = validateVmConfiguration(clampedConfig, resources)
        _state.value = _state.value.copy(
            deviceResources = resources,
            vmConfig = clampedConfig,
            configValidation = validation
        )
    }

    private fun loadSavedSettings() {
        if (context == null) return
        val prefs = context.getSharedPreferences("droidhost_settings", Context.MODE_PRIVATE)
        val cores = prefs.getInt("vm_cpu", _state.value.vmConfig.cpuCores)
        val ram = prefs.getInt("vm_ram", _state.value.vmConfig.ramMb)
        val disk = prefs.getInt("vm_disk", _state.value.vmConfig.diskGb)
        val autoStart = prefs.getBoolean("vm_auto_start", _state.value.vmConfig.autoStart)

        val resources = _state.value.deviceResources
        val maxDisk = (resources.availableStorageGb - 2).coerceAtLeast(4)
        val maxRam = (resources.totalRamMb * 3 / 4).coerceAtLeast(512)
        val clampedConfig = VmConfiguration(
            cpuCores = cores.coerceIn(1, resources.cpuCores),
            ramMb = ram.coerceIn(512, maxRam),
            diskGb = disk.coerceIn(4, maxDisk),
            autoStart = autoStart
        )
        val validation = validateVmConfiguration(clampedConfig, resources)
        _state.value = _state.value.copy(
            vmConfig = clampedConfig,
            configValidation = validation
        )
    }

    private fun checkAssets() {
        vmManager?.let { vm ->
            val report = vm.validateAssets()
            _state.value = _state.value.copy(assetReport = report)
        }
    }

    fun refreshAssetValidation() {
        checkAssets()
    }

    private fun observeVmManager() {
        if (vmManager == null) return
        viewModelScope.launch {
            vmManager.vmState.collect { vState ->
                _state.value = _state.value.copy(vmState = vState)
                if (vState != VmState.RUNNING) {
                    _state.value = _state.value.copy(
                        metrics = Metrics(online = false),
                        containers = emptyList(),
                        images = 0,
                        volumes = 0,
                        networks = 0,
                        error = null,
                        loading = false
                    )
                } else {
                    refresh()
                }
            }
        }

        viewModelScope.launch {
            vmManager.lastError.collect { error ->
                _state.value = _state.value.copy(vmError = error)
            }
        }
    }

    private fun observeTerminal() {
        if (terminalRepository == null) return
        viewModelScope.launch {
            terminalRepository.connectionState.collect { tState ->
                _state.value = _state.value.copy(terminalState = tState)
            }
        }

        viewModelScope.launch {
            terminalRepository.output.collect { chunk ->
                val current = _state.value.terminalOutput
                val next = if (current.length > 50000) current.takeLast(30000) + chunk else current + chunk
                _state.value = _state.value.copy(terminalOutput = next)
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                if (_state.value.vmState == VmState.RUNNING) {
                    refresh()
                }
                delay(3000)
            }
        }
    }

    fun refresh() = viewModelScope.launch {
        if (_state.value.vmState != VmState.RUNNING && vmManager != null) {
            // When VM is offline, do not spam loopback network calls
            _state.value = _state.value.copy(
                metrics = Metrics(online = false),
                containers = emptyList(),
                loading = false,
                error = null
            )
            return@launch
        }

        try {
            val m = repository.metrics()
            val c = repository.containers()
            val img = runCatching { repository.images().size }.getOrDefault(0)
            val vol = runCatching { repository.volumes().size }.getOrDefault(0)
            val net = runCatching { repository.networks().size }.getOrDefault(0)
            _state.value = _state.value.copy(
                metrics = m,
                containers = c,
                images = img,
                volumes = vol,
                networks = net,
                loading = false,
                error = null
            )
        } catch (e: Exception) {
            // Never expose raw ConnectException
            val safeMessage = if (e.message?.contains("Failed to connect") == true || e.message?.contains("Connection refused") == true) {
                ServerFailure.AgentUnavailable.userMessage
            } else {
                e.message ?: ServerFailure.AgentUnavailable.userMessage
            }
            _state.value = _state.value.copy(
                loading = false,
                error = if (_state.value.vmState == VmState.RUNNING) safeMessage else null
            )
        }
    }

    fun startVm(force: Boolean = false) {
        if (!force && _state.value.isBatteryOptimized && !_state.value.hasDismissedBatteryDialog) {
            pendingVmStart = true
            _state.value = _state.value.copy(showBatteryOptimizationDialog = true)
            return
        }
        pendingVmStart = false
        if (vmManager != null) {
            vmManager.start(_state.value.vmConfig)
        } else {
            _state.value = _state.value.copy(vmState = VmState.STARTING)
        }
    }

    fun stopVm() {
        if (vmManager != null) {
            vmManager.stop()
        } else {
            _state.value = _state.value.copy(vmState = VmState.STOPPED)
        }
    }

    fun restartVm() {
        if (vmManager != null) {
            vmManager.restart(_state.value.vmConfig)
        } else {
            _state.value = _state.value.copy(vmState = VmState.STARTING)
        }
    }

    fun action(id: String, action: String) = viewModelScope.launch {
        runCatching {
            repository.action(id, action)
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "Failed to perform $action on container")
        }
    }

    fun removeContainer(id: String, force: Boolean = false) = viewModelScope.launch {
        runCatching {
            repository.removeContainer(id, force)
            if (_state.value.selectedContainerDetail?.id == id) {
                _state.value = _state.value.copy(selectedContainerDetail = null)
            }
            refresh()
        }.onFailure {
            _state.value = _state.value.copy(error = it.message ?: "Failed to remove container")
        }
    }

    fun setContainerSearch(query: String) {
        _state.value = _state.value.copy(containerSearchQuery = query)
    }

    fun setContainerFilter(filter: ContainerFilter) {
        _state.value = _state.value.copy(containerFilter = filter)
    }

    fun selectContainerForInspect(id: String) = viewModelScope.launch {
        _state.value = _state.value.copy(
            isLoadingContainerDetail = true,
            selectedContainerDetail = null,
            selectedContainerStats = null,
            selectedContainerLogs = null
        )
        try {
            val detail = repository.container(id)
            val logs = runCatching { repository.logs(id) }.getOrDefault("")
            _state.value = _state.value.copy(
                selectedContainerDetail = detail,
                selectedContainerLogs = logs,
                isLoadingContainerDetail = false
            )
            startContainerStatsPolling(id)
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                isLoadingContainerDetail = false,
                error = e.message ?: "Failed to inspect container"
            )
        }
    }

    private fun startContainerStatsPolling(id: String) {
        statsJob?.cancel()
        statsJob = viewModelScope.launch {
            while (_state.value.selectedContainerDetail?.id == id) {
                runCatching {
                    val st = repository.stats(id)
                    _state.value = _state.value.copy(selectedContainerStats = st)
                }
                delay(2000)
            }
        }
    }

    fun closeContainerDetail() {
        statsJob?.cancel()
        _state.value = _state.value.copy(
            selectedContainerDetail = null,
            selectedContainerStats = null,
            selectedContainerLogs = null
        )
    }

    // Terminal
    fun terminalConnect() {
        terminalRepository?.connect()
    }

    fun terminalDisconnect() {
        terminalRepository?.disconnect()
    }

    fun terminalSend(command: String) {
        terminalRepository?.send(command)
    }

    fun terminalSendBytes(bytes: ByteArray) {
        terminalRepository?.sendBytes(bytes)
    }

    fun terminalClear() {
        _state.value = _state.value.copy(terminalOutput = "")
    }

    // Port Forwarding
    fun addPortForwardRule(hostPort: Int, guestPort: Int, protocol: String = "tcp") {
        val newRule = PortForwardRule(
            id = UUID.randomUUID().toString(),
            hostPort = hostPort,
            guestPort = guestPort,
            protocol = protocol,
            enabled = true
        )
        _state.value = _state.value.copy(portForwardRules = _state.value.portForwardRules + newRule)
    }

    fun removePortForwardRule(id: String) {
        _state.value = _state.value.copy(
            portForwardRules = _state.value.portForwardRules.filterNot { it.id == id }
        )
    }

    fun togglePortForwardRule(id: String) {
        _state.value = _state.value.copy(
            portForwardRules = _state.value.portForwardRules.map {
                if (it.id == id) it.copy(enabled = !it.enabled) else it
            }
        )
    }

    // Settings
    fun updateVmConfiguration(cpu: Int, ramMb: Int, diskGb: Int, autoStart: Boolean) {
        val newConfig = VmConfiguration(cpuCores = cpu, ramMb = ramMb, diskGb = diskGb, autoStart = autoStart)
        val validation = validateVmConfiguration(newConfig, _state.value.deviceResources)
        _state.value = _state.value.copy(
            vmConfig = newConfig,
            configValidation = validation
        )

        if (context != null) {
            context.getSharedPreferences("droidhost_settings", Context.MODE_PRIVATE)
                .edit()
                .putInt("vm_cpu", cpu)
                .putInt("vm_ram", ramMb)
                .putInt("vm_disk", diskGb)
                .putBoolean("vm_auto_start", autoStart)
                .apply()
        }
    }

    // Automated Bundle Provisioning & Pre-Setup
    fun runPreSetup() {
        if (vmManager == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isProvisioning = true,
                provisioningStatus = "Initializing VM environment...",
                error = null,
                vmError = null
            )
            try {
                val report = vmManager.generatePreSetup(_state.value.vmConfig.diskGb) { status ->
                    _state.value = _state.value.copy(provisioningStatus = status)
                }
                _state.value = _state.value.copy(
                    assetReport = report,
                    vmError = if (report.valid) null else report.errorMessage,
                    error = null,
                    isProvisioning = false,
                    provisioningStatus = if (report.valid) "VM environment ready!" else null
                )
                if (report.valid && _state.value.vmState == VmState.FAILED) {
                    _state.value = _state.value.copy(vmState = VmState.STOPPED)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Pre-setup failed"
                _state.value = _state.value.copy(
                    isProvisioning = false,
                    provisioningStatus = null,
                    error = msg,
                    vmError = msg
                )
            }
        }
    }

    fun importBundleZip(uri: Uri) {
        if (context == null || vmManager == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isProvisioning = true, provisioningStatus = "Opening bundle file...")
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("Could not read selected file")
                val report = vmManager.extractBundleZip(inputStream) { status ->
                    _state.value = _state.value.copy(provisioningStatus = status)
                }
                _state.value = _state.value.copy(
                    assetReport = report,
                    vmError = if (report.valid) null else report.errorMessage,
                    isProvisioning = false,
                    provisioningStatus = if (report.valid) "Bundle successfully installed!" else null
                )
                if (report.valid && _state.value.vmState == VmState.FAILED) {
                    _state.value = _state.value.copy(vmState = VmState.STOPPED)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Failed to import bundle"
                _state.value = _state.value.copy(
                    isProvisioning = false,
                    provisioningStatus = null,
                    error = msg,
                    vmError = msg
                )
            }
        }
    }

    fun downloadBundle(url: String) {
        if (vmManager == null) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isProvisioning = true,
                provisioningStatus = "Downloading bundle...",
                error = null,
                vmError = null
            )
            try {
                val report = vmManager.downloadAndExtractBundle(url) { status ->
                    _state.value = _state.value.copy(provisioningStatus = status)
                }
                _state.value = _state.value.copy(
                    assetReport = report,
                    vmError = if (report.valid) null else report.errorMessage,
                    isProvisioning = false,
                    provisioningStatus = if (report.valid) "Bundle successfully installed!" else null,
                    error = null
                )
                if (report.valid && _state.value.vmState == VmState.FAILED) {
                    _state.value = _state.value.copy(vmState = VmState.STOPPED)
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Download failed"
                _state.value = _state.value.copy(
                    isProvisioning = false,
                    provisioningStatus = null,
                    error = msg,
                    vmError = msg
                )
            }
        }
    }

    // Battery Optimization
    fun checkBatteryOptimization(autoPrompt: Boolean = false) {
        if (context == null) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isIgnoring = pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            val isOptimized = !isIgnoring
            _state.value = _state.value.copy(
                isBatteryOptimized = isOptimized,
                showBatteryOptimizationDialog = if (isOptimized && autoPrompt && !_state.value.hasDismissedBatteryDialog) true
                else (_state.value.showBatteryOptimizationDialog && isOptimized)
            )
        } catch (_: Exception) {
        }
    }

    fun showBatteryDialog() {
        _state.value = _state.value.copy(showBatteryOptimizationDialog = true)
    }

    fun dismissBatteryDialog(proceedWithPendingStart: Boolean = false) {
        val shouldStart = proceedWithPendingStart && pendingVmStart
        pendingVmStart = false
        _state.value = _state.value.copy(
            showBatteryOptimizationDialog = false,
            hasDismissedBatteryDialog = true
        )
        if (shouldStart) {
            startVm(force = true)
        }
    }

    fun requestDisableBatteryOptimization(targetContext: Context) {
        // 1. Direct system prompt for this package
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${targetContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(intent)
            return
        } catch (_: Exception) {
        }

        // 2. Battery optimization system settings list
        try {
            val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(fallback)
            return
        } catch (_: Exception) {
        }

        // 3. App details settings page where user can tap Battery -> Unrestricted
        try {
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${targetContext.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            targetContext.startActivity(appDetails)
        } catch (_: Exception) {
        }
    }
}
