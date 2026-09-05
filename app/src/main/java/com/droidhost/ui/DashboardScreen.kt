package com.droidhost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    onNavigateToSettings: () -> Unit
) {
    var showDownloadDialog by remember { mutableStateOf(false) }
    var showPreSetupDialog by remember { mutableStateOf(false) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { viewModel.importBundleZip(it) }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Provisioning in progress banner
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
                            state.provisioningStatus ?: "Please wait while assets are extracted...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // Active VM Error / Setup Error Banner
        val activeError = state.vmError ?: state.error
        if (activeError != null && !state.isProvisioning) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = "Error",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "VM Setup / Runtime Error",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                            TextButton(
                                onClick = onNavigateToSettings,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Diagnostics",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        Surface(
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = activeError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showPreSetupDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pre-Setup VM", fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import (.zip)", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        TextButton(
                            onClick = { showDownloadDialog = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Or enter download URL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        } else if (state.assetReport != null && !state.assetReport.valid && !state.isProvisioning) {
            // Guest VM Assets Required Banner (shown when assets are missing and not in error state)
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Assets Required",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    "Guest VM Assets Required",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = onNavigateToSettings,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    "Details",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "The ARM64 Linux VM bundle (kernel, initrd, QEMU binary, root disk) must be installed to run containers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = { showPreSetupDialog = true },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Pre-Setup VM", fontWeight = FontWeight.Bold, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Import (.zip)", fontWeight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        TextButton(
                            onClick = { showDownloadDialog = true },
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Or download from URL", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        // Battery Optimization Warning Banner
        if (state.isBatteryOptimized) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.BatteryAlert,
                            contentDescription = "Battery Alert",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Battery Optimization Active",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                "Android may freeze or kill the Linux VM when the screen turns off. Turn off optimization for 24/7 background uptime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.showBatteryDialog() },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Turn Off")
                        }
                    }
                }
            }
        }

        // Server Status & Uptime Header
        item {
            ServerHeader(state = state, viewModel = viewModel)
        }

        // VM Specs & Control Card
        item {
            VmControlCard(state = state, viewModel = viewModel)
        }

        // Live Metrics Row (CPU, RAM, Network)
        item {
            MetricRow(state = state)
        }

        // Docker Engine Overview Card
        item {
            SectionTitle("DOCKER ENGINE")
        }
        item {
            DockerOverviewCard(state = state)
        }

        // Quick Actions
        item {
            SectionTitle("QUICK ACTIONS")
        }
        item {
            QuickActionsGrid(
                state = state,
                viewModel = viewModel,
                onNavigateToContainers = onNavigateToContainers,
                onNavigateToTerminal = onNavigateToTerminal,
                onNavigateToStorage = onNavigateToStorage,
                onNavigateToNetwork = onNavigateToNetwork,
                onNavigateToSettings = onNavigateToSettings
            )
        }

        // Containers Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionTitle("ACTIVE CONTAINERS")
                if (state.containers.isNotEmpty()) {
                    TextButton(onClick = onNavigateToContainers) {
                        Text("View All (${state.containers.size})")
                    }
                }
            }
        }

        if (state.loading) {
            item {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }

        if (state.vmState != VmState.RUNNING) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Offline",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Linux VM is Offline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Start the ARM64 Linux VM to run and monitor Docker containers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        if (state.assetReport?.valid == false) {
                            Text(
                                "Guest VM assets not yet installed.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { showPreSetupDialog = true }) {
                                    Icon(Icons.Default.SettingsSuggest, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Pre-Setup VM")
                                }
                                OutlinedButton(onClick = { filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }) {
                                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Import (.zip)")
                                }
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startVm() },
                                enabled = state.vmState != VmState.STARTING
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.vmState == VmState.STARTING) "Starting VM..." else "Start VM")
                            }
                        }
                    }
                }
            }
        } else if (state.containers.isEmpty() && !state.loading) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No containers running. Pull or run an image to get started.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(state.containers.take(5)) { container ->
                RecentContainerRow(container = container, viewModel = viewModel, onInspect = onNavigateToContainers)
            }
        }
    }

    if (showPreSetupDialog) {
        PreSetupDialog(
            diskSizeGb = state.vmConfig.diskGb,
            onDismiss = { showPreSetupDialog = false },
            onStartPreSetup = {
                showPreSetupDialog = false
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
private fun ServerHeader(state: DashboardState, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "SERVER STATUS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (state.metrics.online) "Online"
                        else if (state.vmState == VmState.STARTING) "Booting..."
                        else "Offline",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    formatUptime(state.metrics.uptimeSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AssistChip(
                onClick = {},
                label = { Text("VM ${state.vmState.name.lowercase()}", maxLines = 1) },
                leadingIcon = {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = null,
                        modifier = Modifier.size(10.dp),
                        tint = when (state.vmState) {
                            VmState.RUNNING -> Color(0xFF2A9D8F)
                            VmState.STARTING, VmState.STOPPING -> Color(0xFFE9C46A)
                            VmState.FAILED -> Color(0xFFE76F51)
                            VmState.STOPPED -> Color.Gray
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun VmControlCard(state: DashboardState, viewModel: MainViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "ARM64 Linux VM",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${state.vmConfig.cpuCores} Cores · ${state.vmConfig.ramMb} MB RAM · ${state.vmConfig.diskGb} GB Disk",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (state.vmState) {
                        VmState.RUNNING -> {
                            FilledTonalButton(
                                onClick = { viewModel.restartVm() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Restart")
                            }
                            Button(
                                onClick = { viewModel.stopVm() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                        VmState.STARTING -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                        VmState.STOPPING -> {
                            Text("Stopping...", style = MaterialTheme.typography.bodySmall)
                        }
                        VmState.STOPPED, VmState.FAILED -> {
                            Button(
                                onClick = { viewModel.startVm() },
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start", modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Start VM")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricRow(state: DashboardState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            label = "CPU",
            value = if (state.metrics.online) "${state.metrics.cpuPercent.toInt()}%" else "--",
            icon = Icons.Default.Memory,
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            label = "RAM",
            value = if (state.metrics.online) percent(state.metrics.memoryUsedBytes, state.metrics.memoryTotalBytes) else "--",
            icon = Icons.Default.DataUsage,
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            label = "NETWORK",
            value = if (state.metrics.online) bytes(state.metrics.networkRxBytes + state.metrics.networkTxBytes) else "--",
            icon = Icons.Default.Wifi,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, icon: ImageVector, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DockerOverviewCard(state: DashboardState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Docker Engine", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.metrics.online) "Connected to Linux guest daemon" else "Disconnected (VM offline)",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.metrics.online) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val runningCount = state.containers.count { it.mappedState == ContainerState.RUNNING }
                    Text(
                        if (state.metrics.online) "$runningCount running" else "--",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        if (state.metrics.online) "${state.images} images · ${state.volumes} volumes" else "0 images · 0 volumes",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionsGrid(
    state: DashboardState,
    viewModel: MainViewModel,
    onNavigateToContainers: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToStorage: () -> Unit,
    onNavigateToNetwork: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.vmState == VmState.RUNNING) {
                ActionBtn("Stop VM", Icons.Default.Stop, Modifier.weight(1f)) { viewModel.stopVm() }
            } else {
                ActionBtn("Start VM", Icons.Default.PlayArrow, Modifier.weight(1f)) { viewModel.startVm() }
            }
            ActionBtn("Terminal", Icons.Default.Terminal, Modifier.weight(1f), onClick = onNavigateToTerminal)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionBtn("Containers", Icons.AutoMirrored.Filled.ViewList, Modifier.weight(1f), onClick = onNavigateToContainers)
            ActionBtn("Storage", Icons.Default.Storage, Modifier.weight(1f), onClick = onNavigateToStorage)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionBtn("Network", Icons.Default.Wifi, Modifier.weight(1f), onClick = onNavigateToNetwork)
            ActionBtn("Settings", Icons.Default.Settings, Modifier.weight(1f), onClick = onNavigateToSettings)
        }
    }
}

@Composable
private fun ActionBtn(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RecentContainerRow(
    container: Container,
    viewModel: MainViewModel,
    onInspect: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (container.mappedState == ContainerState.RUNNING) Color(0xFF2A9D8F) else Color.Gray
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(container.image, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                Text(
                    container.status,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (container.mappedState == ContainerState.RUNNING) Color(0xFF2A9D8F) else Color.Gray,
                    maxLines = 1
                )
            }

            if (container.mappedState == ContainerState.RUNNING) {
                IconButton(onClick = { viewModel.action(container.id, "stop") }) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                IconButton(onClick = { viewModel.action(container.id, "start") }) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                }
            }
            IconButton(onClick = { viewModel.action(container.id, "restart") }) {
                Icon(Icons.Default.Refresh, contentDescription = "Restart")
            }
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

private fun formatUptime(s: Double): String {
    val totalSec = s.toLong()
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    return "Uptime ${hours}h ${minutes}m"
}

private fun percent(used: Long, total: Long): String {
    if (total <= 0L) return "--"
    return "${(used * 100 / total)}%"
}

private fun bytes(n: Long): String {
    val mb = n / (1024 * 1024)
    return if (mb > 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"
}
