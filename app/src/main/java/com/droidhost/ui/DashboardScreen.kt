package com.droidhost.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.droidhost.domain.Container
import com.droidhost.domain.ContainerState
import com.droidhost.domain.VmState

@Composable
fun DashboardScreen(
    state: DashboardState,
    viewModel: MainViewModel,
    onNavigateToContainers: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCatalogue: () -> Unit = {}
) {
    var showPreSetupDialog by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBundleZip(it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // Live VM Setup & Download Progress Card
        if (state.isProvisioning) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.95f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.5.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "Preparing VM Environment...",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        val statusText = state.provisioningStatus ?: "Initializing..."
                        val percentMatch = Regex("(\\d+)%").find(statusText)
                        val percentValue = percentMatch?.groupValues?.get(1)?.toFloatOrNull()?.div(100f)

                        if (percentValue != null) {
                            LinearProgressIndicator(
                                progress = { percentValue },
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f)
                            )
                        }

                        Text(
                            statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }

        // Initial Setup Required Card (only when assets missing)
        val assetsMissing = state.assetReport != null && !state.assetReport.valid && !state.isProvisioning
        if (assetsMissing) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                Icons.Default.SettingsSuggest,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Initial VM Setup Required",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "ARM64 Linux runtime needs to be initialized on this phone.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Button(
                            onClick = { showPreSetupDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("1-Tap Quick Setup (Automatic)")
                        }
                    }
                }
            }
        }

        // Compact Error / Warning Banner (only show when not actively running/online and not missing assets card)
        val activeError = state.vmError ?: state.error
        if (activeError != null && !state.isProvisioning && !assetsMissing && (state.vmState != VmState.RUNNING || !state.metrics.online)) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Text(
                                activeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                maxLines = 2
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = { showPreSetupDialog = true },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Resolve", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            }
                            IconButton(
                                onClick = { viewModel.dismissError() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }

        // Compact Battery Warning Pill
        if (state.isBatteryOptimized) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.size(16.dp))
                            Text("Battery optimization active (background VM may sleep)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                        TextButton(
                            onClick = { viewModel.showBatteryDialog() },
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                        ) {
                            Text("Turn Off", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }

        // 1. Unified Server & Hypervisor Hero Card
        item {
            UnifiedServerHero(state = state, viewModel = viewModel, onNavigateToNetwork = onNavigateToNetwork)
        }

        // 2. Compact 4-Cell Telemetry Row
        item {
            TelemetryGrid(state = state)
        }

        // 3. Quick Action Shortcuts Bar
        item {
            QuickShortcutsBar(
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToNetwork = onNavigateToNetwork,
                onNavigateToStorage = onNavigateToStorage,
                onNavigateToSettings = onNavigateToSettings
            )
        }

        // 4. Cloud Marketplace & 1-Click Apps Banner
        item {
            MarketplacePromoCard(onNavigateToCatalogue = onNavigateToCatalogue)
        }

        // 5. Active Workloads / Containers Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ACTIVE WORKLOADS (${state.containers.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (state.containers.isNotEmpty()) {
                    TextButton(onClick = onNavigateToContainers, contentPadding = PaddingValues(0.dp)) {
                        Text("Manage All", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        if (state.containers.isEmpty() && state.vmState == VmState.RUNNING) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        Text("No containers running yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Install Dokploy, Coolify, WordPress, or databases from the 1-Click Marketplace.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(
                            onClick = onNavigateToCatalogue,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Storefront, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Marketplace", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } else if (state.vmState != VmState.RUNNING) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Linux VM is Offline", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("Start server to activate Docker and web services", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { viewModel.startVm() },
                            enabled = state.vmState != VmState.STARTING,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (state.vmState == VmState.STARTING) "Starting..." else "Start VM", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        } else {
            items(state.containers.take(6), key = { it.id }) { container ->
                CleanContainerRow(container = container, onInspect = onNavigateToContainers)
            }
        }
    }

    if (showPreSetupDialog) {
        PreSetupDialog(
            diskSizeGb = state.vmConfig.diskGb,
            deviceResources = state.deviceResources,
            existingCloudflareToken = state.cloudflareToken,
            onDismiss = { showPreSetupDialog = false },
            onStartPreSetup = { profile, token ->
                showPreSetupDialog = false
                if (token.isNotBlank()) viewModel.saveCloudflareToken(token)
                viewModel.runPreSetupAndStart(profile)
            }
        )
    }
}

@Composable
private fun UnifiedServerHero(
    state: DashboardState,
    viewModel: MainViewModel,
    onNavigateToNetwork: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Status & IP Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.metrics.online) Color(0xFF2A9D8F)
                                else if (state.vmState == VmState.STARTING) Color(0xFFE9C46A)
                                else Color(0xFFE76F51)
                            )
                    )
                    Text(
                        if (state.metrics.online) "SERVER ONLINE"
                        else if (state.vmState == VmState.STARTING) "BOOTING VM..."
                        else "SERVER OFFLINE",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (state.metrics.online) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onNavigateToNetwork() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Public, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(state.deviceLanIp, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Specs & Uptime Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ARM64 Linux · ${state.vmConfig.cpuCores} Cores · ${state.vmConfig.ramMb} MB · ${state.vmConfig.diskGb} GB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    formatUptime(state.metrics.uptimeSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))

            // Control Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when (state.vmState) {
                    VmState.RUNNING -> {
                        FilledTonalButton(
                            onClick = { viewModel.restartVm() },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Restart", style = MaterialTheme.typography.labelSmall)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.stopVm() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Stop", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    VmState.STARTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    VmState.STOPPING -> {
                        Text("Stopping...", style = MaterialTheme.typography.bodySmall)
                    }
                    VmState.STOPPED, VmState.FAILED -> {
                        Button(
                            onClick = { viewModel.startVm() },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Start Server", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TelemetryGrid(state: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val cpuVal = if (state.metrics.online) "${state.metrics.cpuPercent.toInt()}%" else "--"
        val ramVal = if (state.metrics.online) percent(state.metrics.memoryUsedBytes, state.metrics.memoryTotalBytes) else "--"
        val netMb = if (state.metrics.online) "${(state.metrics.networkRxBytes + state.metrics.networkTxBytes) / (1024 * 1024)}M" else "--"
        val containersRunning = if (state.metrics.online) state.containers.count { it.mappedState == ContainerState.RUNNING }.toString() else "0"

        TelemetryItem(title = "CPU", value = cpuVal, icon = Icons.Default.Memory, modifier = Modifier.weight(1f))
        TelemetryItem(title = "RAM", value = ramVal, icon = Icons.Default.DataUsage, modifier = Modifier.weight(1f))
        TelemetryItem(title = "DOCKER", value = containersRunning, icon = Icons.Default.Layers, modifier = Modifier.weight(1f))
        TelemetryItem(title = "NET", value = netMb, icon = Icons.Default.Wifi, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TelemetryItem(title: String, value: String, icon: ImageVector, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
            }
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun QuickShortcutsBar(
    onNavigateToTerminal: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ShortcutChip(label = "Terminal", icon = Icons.Default.Terminal, onClick = onNavigateToTerminal, modifier = Modifier.weight(1f))
        ShortcutChip(label = "Network", icon = Icons.Default.Wifi, onClick = onNavigateToNetwork, modifier = Modifier.weight(1f))
        ShortcutChip(label = "Storage", icon = Icons.Default.Storage, onClick = onNavigateToStorage, modifier = Modifier.weight(1f))
        ShortcutChip(label = "Settings", icon = Icons.Default.Settings, onClick = onNavigateToSettings, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ShortcutChip(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun MarketplacePromoCard(onNavigateToCatalogue: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onNavigateToCatalogue() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                Column {
                    Text("1-Click Cloud Marketplace", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("Deploy Dokploy, Coolify, WordPress & databases", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun CleanContainerRow(container: Container, onInspect: () -> Unit) {
    val context = LocalContext.current
    val isRunning = container.mappedState == ContainerState.RUNNING
    val webPort = container.ports.firstOrNull { it.publicPort > 0 }?.publicPort

    Card(
        modifier = Modifier.fillMaxWidth().clickable { onInspect() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isRunning) Color(0xFF2A9D8F) else Color.Gray)
                )
                Column {
                    Text(
                        container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        container.image,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (isRunning && webPort != null) {
                FilledTonalButton(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:$webPort"))
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(30.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(":$webPort", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    container.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatUptime(seconds: Double): String {
    val totalSec = seconds.toLong()
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun percent(used: Long, total: Long): String {
    if (total <= 0L) return "0%"
    val p = ((used.toDouble() / total.toDouble()) * 100).toInt()
    return "$p%"
}
