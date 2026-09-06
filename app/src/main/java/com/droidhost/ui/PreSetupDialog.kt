package com.droidhost.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.droidhost.domain.DeviceResources
import com.droidhost.domain.ServerProfile

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreSetupDialog(
    diskSizeGb: Int = 32,
    deviceResources: DeviceResources = DeviceResources(cpuCores = 8, totalRamMb = 4096, availableStorageGb = 64),
    existingCloudflareToken: String = "",
    onDismiss: () -> Unit,
    onStartPreSetup: (selectedProfile: ServerProfile, cloudflareToken: String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    val presets = remember(deviceResources) { ServerProfile.getPresets(deviceResources) }
    var selectedProfileName by remember { mutableStateOf("Standard") }

    val defaultPreset = presets.find { it.name == "Standard" } ?: presets.first()
    var cpuCores by remember { mutableStateOf(defaultPreset.cpuCores.toFloat()) }
    var ramMb by remember { mutableStateOf(defaultPreset.memoryMb.toFloat()) }
    var diskGb by remember { mutableStateOf(defaultPreset.diskGb.toFloat().coerceAtLeast(diskSizeGb.toFloat())) }

    fun selectPreset(p: ServerProfile) {
        selectedProfileName = p.name
        if (p.name != "Custom") {
            cpuCores = p.cpuCores.toFloat()
            ramMb = p.memoryMb.toFloat()
            diskGb = p.diskGb.toFloat()
        }
    }

    val activeProfile = remember(selectedProfileName, cpuCores, ramMb, diskGb) {
        ServerProfile(
            name = selectedProfileName,
            cpuCores = cpuCores.toInt(),
            memoryMb = ramMb.toInt(),
            diskGb = diskGb.toInt()
        )
    }

    // Permissions
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        )
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotificationPermission = isGranted
    }

    var isBatteryExempt by remember {
        mutableStateOf(
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            } catch (_: Exception) {
                true
            }
        )
    }

    var cfToken by remember { mutableStateOf(existingCloudflareToken) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("CREATE YOUR SERVER", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Choose your phone resource allocation", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    "Select a server profile for your ARM64 Linux personal server appliance:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Profile Selection Chips
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presets.forEach { preset ->
                        val isSelected = selectedProfileName == preset.name
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .clickable { selectPreset(preset) }
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.5.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                        ) {
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(
                                    preset.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                if (preset.name != "Custom") {
                                    Text(
                                        "${preset.cpuCores} CPU · ${preset.memoryMb / 1024}GB RAM · ${preset.diskGb}GB",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        "Custom Sliders",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Custom Sliders if Custom selected
                if (selectedProfileName == "Custom") {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // CPU
                            Column {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("CPU Cores: ${cpuCores.toInt()}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text("Max: ${deviceResources.cpuCores}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = cpuCores,
                                    onValueChange = { cpuCores = it },
                                    valueRange = 1f..deviceResources.cpuCores.toFloat().coerceAtLeast(1f),
                                    steps = (deviceResources.cpuCores - 2).coerceAtLeast(0)
                                )
                            }
                            // RAM
                            Column {
                                val maxRam = (deviceResources.totalRamMb * 3 / 4).coerceAtLeast(1024)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("RAM: ${ramMb.toInt()} MB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text("Max: $maxRam MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                val maxDisk = (deviceResources.availableStorageGb - 2).coerceAtLeast(8)
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Virtual Disk: ${diskGb.toInt()} GB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                    Text("Max: $maxDisk GB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Slider(
                                    value = diskGb,
                                    onValueChange = { diskGb = it },
                                    valueRange = 8f..maxDisk.toFloat(),
                                    steps = (maxDisk - 9).coerceAtLeast(0)
                                )
                            }
                        }
                    }
                }

                // Server Summary Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "SERVER SUMMARY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("CPU Cores", style = MaterialTheme.typography.bodySmall)
                            Text("${activeProfile.cpuCores} cores", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("RAM Memory", style = MaterialTheme.typography.bodySmall)
                            Text("${activeProfile.memoryMb} MB (${activeProfile.memoryMb / 1024} GB)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Virtual Disk Capacity", style = MaterialTheme.typography.bodySmall)
                            Text("${activeProfile.diskGb} GB", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF2A9D8F))
                        }
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Android Storage Available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${deviceResources.availableStorageGb} GB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Estimated Initial Disk Usage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("~500 MB (Sparse)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Permissions & 24/7 background mode
                HorizontalDivider()
                Text(
                    "System Permissions (Android ${Build.VERSION.RELEASE ?: "14+"}):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                if (hasNotificationPermission) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                contentDescription = null,
                                tint = if (hasNotificationPermission) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Notification Status", style = MaterialTheme.typography.bodySmall)
                        }
                        if (!hasNotificationPermission) {
                            OutlinedButton(
                                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Grant", style = MaterialTheme.typography.labelSmall)
                            }
                        } else {
                            Text("Granted ✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2A9D8F))
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(
                            if (isBatteryExempt) Icons.Default.BatteryChargingFull else Icons.Default.BatteryAlert,
                            contentDescription = null,
                            tint = if (isBatteryExempt) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("24/7 Server Mode", style = MaterialTheme.typography.bodySmall)
                    }
                    if (!isBatteryExempt) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Disable Sleep", style = MaterialTheme.typography.labelSmall)
                        }
                    } else {
                        Text("Active ✓", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2A9D8F))
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStartPreSetup(activeProfile, cfToken.trim())
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Create & Start Server")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
