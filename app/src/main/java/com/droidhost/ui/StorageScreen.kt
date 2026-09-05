package com.droidhost.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun StorageScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    val vmDiskTotal = state.vmConfig.diskGb.toLong() * 1024 * 1024 * 1024
    val vmDiskUsed = if (state.metrics.online) state.metrics.storageUsedBytes.coerceAtLeast(1024L * 1024 * 1024 * 2) else 0L
    val androidTotal = state.deviceResources.availableStorageGb.toLong() * 1024 * 1024 * 1024
    val androidAvailable = (state.deviceResources.availableStorageGb - state.vmConfig.diskGb).coerceAtLeast(1).toLong() * 1024 * 1024 * 1024

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

        // VM Virtual Disk Card
        item {
            StorageCard(
                title = "ARM64 Linux Virtual Disk",
                subtitle = "ext4 persistent image (/data/droidhost.ext4)",
                usedBytes = vmDiskUsed,
                totalBytes = vmDiskTotal,
                icon = Icons.Default.Storage,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // Docker Storage Breakdown
        item {
            Text(
                "DOCKER DISK USAGE",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DockerItemRow(
                        title = "Container Writable Layers",
                        count = "${state.containers.size} containers",
                        icon = Icons.Default.Layers
                    )
                    Divider()
                    DockerItemRow(
                        title = "Docker Images",
                        count = "${state.images} images",
                        icon = Icons.Default.PhotoLibrary
                    )
                    Divider()
                    DockerItemRow(
                        title = "Docker Volumes",
                        count = "${state.volumes} persistent volumes",
                        icon = Icons.Default.FolderOpen
                    )
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
                subtitle = "Physical Android device memory",
                usedBytes = (androidTotal - androidAvailable).coerceAtLeast(0),
                totalBytes = androidTotal,
                icon = Icons.Default.PhoneAndroid,
                color = MaterialTheme.colorScheme.secondary
            )
        }
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
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB", gb) else "${bytes / (1024 * 1024)} MB"
}
