package com.droidhost.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidhost.domain.validateVmConfiguration
import com.droidhost.service.AssetStatus

@Composable
fun SettingsScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    var cpuCores by remember(state.vmConfig.cpuCores) { mutableFloatStateOf(state.vmConfig.cpuCores.toFloat()) }
    var ramMb by remember(state.vmConfig.ramMb) { mutableFloatStateOf(state.vmConfig.ramMb.toFloat()) }
    var diskGb by remember(state.vmConfig.diskGb) { mutableFloatStateOf(state.vmConfig.diskGb.toFloat()) }
    var autoStart by remember(state.vmConfig.autoStart) { mutableStateOf(state.vmConfig.autoStart) }
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showPreSetupDialog by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBundleZip(it) }
    }

    val currentConfig = remember(cpuCores, ramMb, diskGb, autoStart) {
        com.droidhost.domain.VmConfiguration(
            cpuCores = cpuCores.toInt(),
            ramMb = ramMb.toInt(),
            diskGb = diskGb.toInt(),
            autoStart = autoStart
        )
    }

    val currentValidation = remember(currentConfig, state.deviceResources) {
        validateVmConfiguration(currentConfig, state.deviceResources)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // Provisioning Status Card
        if (state.isProvisioning) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "Installing VM Bundle...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            state.provisioningStatus ?: "Please wait...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        item {
            Text(
                "VM SERVER PROFILE & HARDWARE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Hardware Sliders Card
        item {
            val presets = remember(state.deviceResources) { com.droidhost.domain.ServerProfile.getPresets(state.deviceResources) }
            val isVmRunning = state.vmState == com.droidhost.domain.VmState.RUNNING

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "Resource Preset:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { preset ->
                            val isSelected = (preset.name != "Custom" && cpuCores.toInt() == preset.cpuCores && ramMb.toInt() == preset.memoryMb && diskGb.toInt() == preset.diskGb)
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (preset.name != "Custom") {
                                        cpuCores = preset.cpuCores.toFloat()
                                        ramMb = preset.memoryMb.toFloat()
                                        diskGb = preset.diskGb.toFloat().coerceAtLeast(state.vmConfig.diskGb.toFloat())
                                    }
                                },
                                label = { Text(preset.name) }
                            )
                        }
                    }

                    if (isVmRunning) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "VM is running. CPU and RAM changes will take effect after restarting the VM. Virtual disk grows dynamically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    // CPU
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CPU Cores: ${cpuCores.toInt()}", fontWeight = FontWeight.Bold)
                            Text("Max: ${state.deviceResources.cpuCores} cores", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = cpuCores,
                            onValueChange = { cpuCores = it },
                            valueRange = 1f..state.deviceResources.cpuCores.toFloat().coerceAtLeast(1f),
                            steps = (state.deviceResources.cpuCores - 2).coerceAtLeast(0)
                        )
                    }

                    // RAM
                    Column {
                        val maxRam = (state.deviceResources.totalRamMb * 3 / 4).coerceAtLeast(1024)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RAM Allocation: ${ramMb.toInt()} MB", fontWeight = FontWeight.Bold)
                            Text("Max: $maxRam MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = ramMb,
                            onValueChange = { ramMb = it },
                            valueRange = 512f..maxRam.toFloat(),
                            steps = ((maxRam - 512) / 512).coerceAtLeast(0)
                        )
                    }

                    // Disk
                    Column {
                        val maxDisk = (state.deviceResources.availableStorageGb - 2).coerceAtLeast(4)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Virtual Disk Capacity: ${diskGb.toInt()} GB", fontWeight = FontWeight.Bold)
                            Text("Max: $maxDisk GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = diskGb,
                            onValueChange = { diskGb = it },
                            valueRange = 4f..maxDisk.toFloat(),
                            steps = (maxDisk - 5).coerceAtLeast(0)
                        )
                        Text(
                            "Disks are provisioned sparsely on Android storage and automatically expand up to virtual capacity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (!currentValidation.valid) {
                        Column {
                            currentValidation.errors.forEach { err ->
                                Text("• $err", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Button(
                        onClick = {
                            viewModel.updateVmConfiguration(cpuCores.toInt(), ramMb.toInt(), diskGb.toInt(), autoStart)
                        },
                        enabled = currentValidation.valid,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Apply Hardware Changes")
                    }
                }
            }
        }

        // Server Mode & Startup Settings
        item {
            Text(
                "SERVER MODE & STARTUP",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Auto-start VM on Boot", fontWeight = FontWeight.Bold)
                            Text(
                                "Start Linux VM automatically after device reboot",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoStart,
                            onCheckedChange = {
                                autoStart = it
                                viewModel.updateVmConfiguration(cpuCores.toInt(), ramMb.toInt(), diskGb.toInt(), it)
                            }
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Background Battery Optimization", fontWeight = FontWeight.Bold)
                            Text(
                                if (state.isBatteryOptimized)
                                    "Restricted: Android may pause or kill the VM when screen is off."
                                else
                                    "Unrestricted: App is allowed to run 24/7 in background.",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (state.isBatteryOptimized) MaterialTheme.colorScheme.error else Color(0xFF2A9D8F)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        if (state.isBatteryOptimized) {
                            Button(
                                onClick = { viewModel.showBatteryDialog() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Turn Off")
                            }
                        } else {
                            AssistChip(
                                onClick = {},
                                label = { Text("Unrestricted") },
                                leadingIcon = {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color(0xFF2A9D8F))
                                }
                            )
                        }
                    }
                }
            }
        }

        // VM Asset Diagnostics
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "GUEST VM ASSET DIAGNOSTICS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { viewModel.refreshAssetValidation() }) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Refresh")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Required ARM64 Linux VM bundle files in app storage (/data/user/0/com.droidhost/files/vm):",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val report = state.assetReport
                    if (report != null) {
                        AssetItem("QEMU AArch64 Binary", report.qemu)
                        HorizontalDivider()
                        AssetItem("ARM64 Linux Kernel (Image)", report.kernel)
                        HorizontalDivider()
                        AssetItem("Initramfs (initrd.img)", report.initrd)
                        HorizontalDivider()
                        AssetItem("Ext4 VM Root Disk (droidhost.ext4)", report.disk)
                        HorizontalDivider()
                        AssetItem("Agent Bearer Token (agent-token)", report.token)
                    } else {
                        Text("Diagnostics not available.", style = MaterialTheme.typography.bodySmall)
                    }

                    if (state.isProvisioning) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("Provisioning VM...", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                                val status = state.provisioningStatus ?: "Preparing..."
                                val percentMatch = Regex("(\\d+)%").find(status)
                                val percentValue = percentMatch?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)

                                if (percentValue != null) {
                                    LinearProgressIndicator(
                                        progress = { percentValue },
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                    )
                                }
                                Text(status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Automated Provisioning:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )

                    Button(
                        onClick = { showPreSetupDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pre-Setup VM (Automatic)", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import (.zip)")
                        }
                        OutlinedButton(
                            onClick = { showDownloadDialog = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download URL")
                        }
                    }
                }
            }
        }

        // Cloudflared Binary (ARM64) Card
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Cloudflare Tunnel Binary",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (state.cloudflaredInstalled) Color(0xFF2A9D8F).copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = if (state.cloudflaredInstalled) "INSTALLED" else "NOT INSTALLED",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (state.cloudflaredInstalled) Color(0xFF2A9D8F)
                                else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Text(
                        "Official cloudflared ARM64 Linux binary. Enables outbound reverse tunnels for worldwide SSH and Web UI access without port forwarding.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (state.cloudflaredInstalled && state.cloudflaredVersion != null) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Version:",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    state.cloudflaredVersion ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    if (state.cloudflaredDownloading) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                            )
                            Text(
                                state.cloudflaredDownloadProgress.ifEmpty { "Downloading..." },
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else if (state.cloudflaredDownloadProgress.isNotEmpty()) {
                        Text(
                            state.cloudflaredDownloadProgress,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (state.cloudflaredInstalled) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.downloadCloudflared() },
                            enabled = !state.cloudflaredDownloading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                if (state.cloudflaredInstalled) Icons.Default.Refresh else Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (state.cloudflaredInstalled) "Re-download / Update"
                                else "Download ARM64 Binary"
                            )
                        }
                    }
                }
            }
        }
    }

    if (showPreSetupDialog) {
        PreSetupDialog(
            diskSizeGb = state.vmConfig.diskGb,
            deviceResources = state.deviceResources,
            existingCloudflareToken = state.cloudflareToken,
            onDismiss = { showPreSetupDialog = false },
            onStartPreSetup = { profile, cfToken ->
                showPreSetupDialog = false
                if (cfToken.isNotEmpty()) viewModel.saveCloudflareToken(cfToken)
                viewModel.applyServerProfile(profile, autoStart = false)
                viewModel.runPreSetup()
            }
        )
    }

    if (showDownloadDialog) {
        DownloadBundleDialog(
            onDismiss = { showDownloadDialog = false },
            onDownload = { url ->
                showDownloadDialog = false
                viewModel.downloadBundle(url)
            }
        )
    }
}


@Composable
private fun AssetItem(name: String, status: AssetStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(
                status.details,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.exists) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace
            )
        }
        Icon(
            if (status.exists) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (status.exists) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(22.dp)
        )
    }
}
