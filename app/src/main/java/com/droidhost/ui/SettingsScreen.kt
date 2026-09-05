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
                "VM HARDWARE ALLOCATION",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Hardware Sliders Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                            Text("Disk Size: ${diskGb.toInt()} GB", fontWeight = FontWeight.Bold)
                            Text("Max: $maxDisk GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Slider(
                            value = diskGb,
                            onValueChange = { diskGb = it },
                            valueRange = 4f..maxDisk.toFloat(),
                            steps = (maxDisk - 5).coerceAtLeast(0)
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

                    val ctx = androidx.compose.ui.platform.LocalContext.current
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

                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Automated Provisioning:",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelMedium
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Import Bundle (.zip)")
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
private fun DownloadBundleDialog(
    onDismiss: () -> Unit,
    onDownload: (String) -> Unit
) {
    var urlText by remember { mutableStateOf("https://github.com/droidhost/releases/download/v0.1.0/vm-bundle.zip") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download VM Bundle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Enter the direct URL to the ARM64 guest bundle zip archive:",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = urlText,
                    onValueChange = { urlText = it },
                    label = { Text("Bundle Archive URL") },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (urlText.isNotBlank()) onDownload(urlText.trim())
            }) {
                Text("Download & Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
