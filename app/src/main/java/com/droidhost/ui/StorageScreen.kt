package com.droidhost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.droidhost.domain.PruneType
import com.droidhost.domain.VmState

@Composable
fun StorageScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    LaunchedEffect(state.vmState) {
        if (state.vmState == VmState.RUNNING) {
            viewModel.loadStorageBreakdown()
        }
    }

    val breakdown = state.storageBreakdown
    val vmDiskTotal = if (breakdown.vmDiskTotalBytes > 0) breakdown.vmDiskTotalBytes else state.vmConfig.diskGb.toLong() * 1024 * 1024 * 1024
    val vmDiskUsed = if (breakdown.vmDiskUsedBytes > 0) breakdown.vmDiskUsedBytes else if (state.metrics.online) state.metrics.storageUsedBytes else 0L
    val androidTotal = if (breakdown.androidTotalBytes > 0) breakdown.androidTotalBytes else state.deviceResources.availableStorageGb.toLong() * 1024 * 1024 * 1024
    val androidAvailable = if (breakdown.androidAvailableBytes > 0) breakdown.androidAvailableBytes else (state.deviceResources.availableStorageGb - state.vmConfig.diskGb).coerceAtLeast(1).toLong() * 1024 * 1024 * 1024

    var confirmPruneType by remember { mutableStateOf<PruneType?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                "STORAGE OVERVIEW",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Pruning banner / result
        if (state.isPruning || state.pruneMessage != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.isPruning) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(state.pruneMessage.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // VM Virtual Disk Card
        item {
            StorageCard(
                title = "ARM64 Linux Virtual Disk (/dev/vda)",
                subtitle = "ext4 persistent rootfs (/data/droidhost.ext4)",
                usedBytes = vmDiskUsed,
                totalBytes = vmDiskTotal,
                icon = Icons.Default.Storage,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Docker Storage Breakdown
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "DOCKER SYSTEM DF",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = { viewModel.loadStorageBreakdown() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh storage")
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DockerItemRow(
                        title = "Container Layers",
                        count = "${formatBytes(breakdown.dockerContainersBytes)} (${state.containers.size} containers)",
                        icon = Icons.Default.Layers
                    )
                    Divider()
                    DockerItemRow(
                        title = "Docker Images",
                        count = "${formatBytes(breakdown.dockerImagesBytes)} (${state.images} images)",
                        icon = Icons.Default.PhotoLibrary
                    )
                    Divider()
                    DockerItemRow(
                        title = "Docker Volumes",
                        count = "${formatBytes(breakdown.dockerVolumesBytes)} (${state.volumes} volumes)",
                        icon = Icons.Default.FolderOpen
                    )
                    Divider()
                    DockerItemRow(
                        title = "Build Cache",
                        count = formatBytes(breakdown.dockerBuildCacheBytes),
                        icon = Icons.Default.Build
                    )
                }
            }
        }

        // Reclaim Storage Actions
        if (state.vmState == VmState.RUNNING) {
            item {
                Text(
                    "RECLAIM STORAGE & PRUNE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { confirmPruneType = PruneType.IMAGES },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Prune Images", style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { confirmPruneType = PruneType.CONTAINERS },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Prune Containers", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { confirmPruneType = PruneType.VOLUMES },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Prune Volumes", style = MaterialTheme.typography.labelSmall)
                            }
                            Button(
                                onClick = { confirmPruneType = PruneType.ALL },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Prune All", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }

        // Android Host Storage
        item {
            Text(
                "ANDROID HOST STORAGE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            StorageCard(
                title = "Device Internal Storage",
                subtitle = "Physical Android device internal flash",
                usedBytes = (androidTotal - androidAvailable).coerceAtLeast(0),
                totalBytes = androidTotal,
                icon = Icons.Default.PhoneAndroid,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }

    // Confirmation Alert Dialog
    confirmPruneType?.let { pType ->
        AlertDialog(
            onDismissRequest = { confirmPruneType = null },
            icon = { Icon(Icons.Default.CleaningServices, contentDescription = null) },
            title = { Text("Prune ${pType.name.lowercase().replaceFirstChar { it.uppercase() }}") },
            text = { Text("Are you sure you want to clean up unused Docker ${pType.name.lowercase()}? This reclaims disk space immediately.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.prune(pType)
                        confirmPruneType = null
                    }
                ) {
                    Text("Prune")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmPruneType = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun StorageCard(
    title: String,
    subtitle: String,
    usedBytes: Long,
    totalBytes: Long,
    icon: ImageVector,
    color: Color
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
            }

            Spacer(Modifier.height(14.dp))

            val progress = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = color
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Used: ${formatBytes(usedBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "Total: ${formatBytes(totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DockerItemRow(title: String, count: String, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Text(count, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.2f GB", gb) else "${bytes / (1024 * 1024)} MB"
}
