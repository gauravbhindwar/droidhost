package com.droidhost.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.sp
import com.droidhost.domain.Container
import com.droidhost.domain.ContainerDetail
import com.droidhost.domain.ContainerState
import com.droidhost.domain.VmState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainersScreen(
    state: DashboardState,
    viewModel: MainViewModel,
    onOpenTerminal: () -> Unit = {}
) {
    var containerToDelete by remember { mutableStateOf<Container?>(null) }

    val filteredContainers = remember(state.containers, state.containerSearchQuery, state.containerFilter) {
        state.containers.filter { c ->
            val matchesSearch = state.containerSearchQuery.isBlank() ||
                c.names.any { it.contains(state.containerSearchQuery, ignoreCase = true) } ||
                c.image.contains(state.containerSearchQuery, ignoreCase = true) ||
                c.id.contains(state.containerSearchQuery, ignoreCase = true)

            val matchesFilter = when (state.containerFilter) {
                ContainerFilter.ALL -> true
                ContainerFilter.RUNNING -> c.mappedState == ContainerState.RUNNING
                ContainerFilter.STOPPED -> c.mappedState != ContainerState.RUNNING
            }

            matchesSearch && matchesFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search bar
        OutlinedTextField(
            value = state.containerSearchQuery,
            onValueChange = { viewModel.setContainerSearch(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search by name, image, or ID...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
            trailingIcon = {
                if (state.containerSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setContainerSearch("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(10.dp))

        // Filter chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.containerFilter == ContainerFilter.ALL,
                onClick = { viewModel.setContainerFilter(ContainerFilter.ALL) },
                label = { Text("All (${state.containers.size})") }
            )
            FilterChip(
                selected = state.containerFilter == ContainerFilter.RUNNING,
                onClick = { viewModel.setContainerFilter(ContainerFilter.RUNNING) },
                label = {
                    Text("Running (${state.containers.count { it.mappedState == ContainerState.RUNNING }})")
                }
            )
            FilterChip(
                selected = state.containerFilter == ContainerFilter.STOPPED,
                onClick = { viewModel.setContainerFilter(ContainerFilter.STOPPED) },
                label = {
                    Text("Stopped (${state.containers.count { it.mappedState != ContainerState.RUNNING }})")
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        if (state.vmState != VmState.RUNNING) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "VM is Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Start the ARM64 Linux VM to view and manage containers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { viewModel.startVm() }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Start VM")
                    }
                }
            }
        } else if (filteredContainers.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (state.containerSearchQuery.isNotBlank()) "No containers match your search"
                    else "No containers found on Docker Engine",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(filteredContainers, key = { it.id }) { container ->
                    ContainerCard(
                        container = container,
                        onInspect = { viewModel.selectContainerForInspect(container.id) },
                        onStart = { viewModel.action(container.id, "start") },
                        onStop = { viewModel.action(container.id, "stop") },
                        onRestart = { viewModel.action(container.id, "restart") },
                        onDelete = { containerToDelete = container }
                    )
                }
            }
        }
    }

    // Delete Confirmation Dialog
    containerToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { containerToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Remove Container") },
            text = {
                Text("Are you sure you want to remove container '${target.names.firstOrNull()?.removePrefix("/") ?: target.id.take(12)}'? This action cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removeContainer(target.id, force = true)
                        containerToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Remove (Force)")
                }
            },
            dismissButton = {
                TextButton(onClick = { containerToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Container Detail Modal Bottom Sheet
    if (state.selectedContainerDetail != null || state.isLoadingContainerDetail) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeContainerDetail() },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ContainerDetailSheetContent(
                detail = state.selectedContainerDetail,
                stats = state.selectedContainerStats,
                logs = state.selectedContainerLogs,
                isLoading = state.isLoadingContainerDetail,
                onClose = { viewModel.closeContainerDetail() },
                onAction = { id, act -> viewModel.action(id, act) },
                onDelete = { id ->
                    viewModel.removeContainer(id, force = true)
                },
                onOpenTerminal = onOpenTerminal
            )
        }
    }
}

@Composable
private fun ContainerCard(
    container: Container,
    onInspect: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onInspect,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (container.mappedState == ContainerState.RUNNING) Color(0xFF2A9D8F) else Color.Gray
                        )
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    container.names.firstOrNull()?.removePrefix("/") ?: container.id.take(12),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                AssistChip(
                    onClick = onInspect,
                    label = { Text(container.state.uppercase(), style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (container.mappedState == ContainerState.RUNNING)
                            Color(0xFF2A9D8F).copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Image: ${container.image}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            if (container.ports.isNotEmpty()) {
                val portsFormatted = container.ports.joinToString(", ") { p ->
                    if (p.publicPort > 0) "${p.publicPort}->${p.privatePort}/${p.type}" else "${p.privatePort}/${p.type}"
                }
                Text(
                    "Ports: $portsFormatted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            Text(
                container.status,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            val context = LocalContext.current
            val webPort = container.ports.firstOrNull { it.publicPort in listOf(80, 8080, 8000, 3000, 5000) }?.publicPort
                ?: container.ports.firstOrNull { it.publicPort > 0 }?.publicPort

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (container.mappedState == ContainerState.RUNNING && webPort != null) {
                    FilledTonalButton(
                        onClick = {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:$webPort"))
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = "Open Web", modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Open :$webPort", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(6.dp))
                }

                if (container.mappedState == ContainerState.RUNNING) {
                    FilledTonalIconButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop")
                    }
                } else {
                    FilledTonalIconButton(onClick = onStart) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Start")
                    }
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(onClick = onRestart) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart")
                }
                Spacer(Modifier.width(6.dp))
                FilledTonalIconButton(
                    onClick = onDelete,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

@Composable
private fun ContainerDetailSheetContent(
    detail: ContainerDetail?,
    stats: com.droidhost.domain.ContainerStats?,
    logs: String?,
    isLoading: Boolean,
    onClose: () -> Unit,
    onAction: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onOpenTerminal: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Overview", "Live Stats", "Logs")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
            .padding(16.dp)
    ) {
        if (isLoading || detail == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }

        // Title and actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    detail.name.removePrefix("/"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    detail.id.take(12),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }

        Spacer(Modifier.height(8.dp))

        // Quick action row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (detail.state.equals("running", ignoreCase = true)) {
                Button(
                    onClick = { onAction(detail.id, "stop") },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Stop")
                }
            } else {
                Button(
                    onClick = { onAction(detail.id, "start") },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Start")
                }
            }
            FilledTonalButton(
                onClick = { onAction(detail.id, "restart") },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Restart")
            }
            OutlinedButton(
                onClick = { onDelete(detail.id) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete")
            }
        }

        Spacer(Modifier.height(12.dp))

        // Tab Row
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when (selectedTab) {
            0 -> OverviewTab(detail = detail)
            1 -> LiveStatsTab(stats = stats)
            2 -> LogsTab(logs = logs)
        }
    }
}

@Composable
private fun OverviewTab(detail: ContainerDetail) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        DetailItem("Image", detail.image)
        DetailItem("Status", detail.status)
        DetailItem("Created", detail.created)
        DetailItem("Restart Count", "${detail.restartCount}")

        if (detail.command.isNotEmpty()) {
            DetailItem("Command", detail.command.joinToString(" "))
        }

        if (detail.ports.isNotEmpty()) {
            Text("Port Mappings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            detail.ports.forEach { p ->
                Text(
                    "• ${p.publicPort} -> ${p.privatePort}/${p.type}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (detail.mounts.isNotEmpty()) {
            Text("Mounts & Volumes", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            detail.mounts.forEach { m ->
                Text(
                    "• ${m.source} ➔ ${m.destination} (${m.type})",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        if (detail.envKeys.isNotEmpty()) {
            Text("Environment Variables (Keys)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            Text(
                detail.envKeys.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LiveStatsTab(stats: com.droidhost.domain.ContainerStats?) {
    if (stats == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("CPU Usage", style = MaterialTheme.typography.labelMedium)
                Text(
                    "${String.format("%.1f", stats.cpuPercent)}%",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (stats.cpuPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Memory Usage", style = MaterialTheme.typography.labelMedium)
                val usedMb = stats.memoryUsedBytes / (1024 * 1024)
                val limitMb = stats.memoryLimitBytes / (1024 * 1024)
                Text(
                    "$usedMb MB / $limitMb MB (${String.format("%.1f", stats.memoryPercent)}%)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { (stats.memoryPercent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Network I/O", style = MaterialTheme.typography.labelMedium)
                val rxMb = stats.networkRxBytes / (1024 * 1024)
                val txMb = stats.networkTxBytes / (1024 * 1024)
                Text(
                    "↓ $rxMb MB (Rx)  ·  ↑ $txMb MB (Tx)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun LogsTab(logs: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1E1E1E))
            .padding(12.dp)
    ) {
        val scrollState = rememberScrollState()
        Text(
            text = logs?.ifBlank { "No logs output yet." } ?: "Loading logs...",
            color = Color(0xFFD4D4D4),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .horizontalScroll(rememberScrollState())
        )
    }
}

@Composable
private fun DetailItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
