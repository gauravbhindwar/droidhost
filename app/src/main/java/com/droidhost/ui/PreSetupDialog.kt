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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

@Composable
fun PreSetupDialog(
    diskSizeGb: Int,
    existingCloudflareToken: String = "",
    onDismiss: () -> Unit,
    onStartPreSetup: (cloudflareToken: String) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Notification Permission State (Android 13+ / API 33 through Android 18+)
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

    // Battery Optimization State
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

    // Cloudflare Tunnel token
    var cfToken by remember { mutableStateOf(existingCloudflareToken) }
    var cfTokenVisible by remember { mutableStateOf(false) }
    var cfTokenExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.SettingsSuggest,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("VM Environment Pre-Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Auto-configure on-device files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "DroidHost will automatically create and initialize the Linux server environment in secure app storage (/data/user/0/com.droidhost/files/vm):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Environment Components Card
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2A9D8F), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Ext4 Virtual Disk: ${diskSizeGb} GB persistent storage", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2A9D8F), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("VM Startup Script: bin/run-vm.sh (executable)", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2A9D8F), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Authentication Token: 32-byte secure bearer key", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2A9D8F), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("ARM64 Guest Tree: bin, boot, data, logs", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ─── Cloudflare Tunnel Section ─────────────────────────────
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFFF4A261), modifier = Modifier.size(18.dp))
                        Text(
                            "Remote Access via Cloudflare Tunnel",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(
                        onClick = { cfTokenExpanded = !cfTokenExpanded },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            if (cfToken.isNotEmpty()) "Configured ✓" else if (cfTokenExpanded) "Collapse" else "Set Up",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (cfToken.isNotEmpty()) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (cfTokenExpanded || cfToken.isNotEmpty()) {
                    // Why Cloudflare?
                    Surface(
                        color = Color(0xFFF4A261).copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Why Cloudflare Tunnel?",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF4A261)
                            )
                            Text(
                                "Your phone's IP changes constantly (DHCP) and mobile networks (4G/5G) use CGNAT which blocks direct inbound connections. Cloudflare Tunnel creates a permanent public route to your server without port forwarding — accessible from anywhere in the world.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "How to get your token:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "1. Go to one.dash.cloudflare.com\n2. Networks → Tunnels → Create a Tunnel\n3. Name it \"droidhost\" → Save\n4. Copy the tunnel token shown",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Default,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Token Input
                    OutlinedTextField(
                        value = cfToken,
                        onValueChange = { cfToken = it },
                        label = { Text("Cloudflare Tunnel Token") },
                        placeholder = { Text("eyJhIjoiY...") },
                        supportingText = {
                            Text(
                                if (cfToken.isNotEmpty()) "✓ Token saved locally on device only" else "Optional — you can set this later from the Network screen",
                                color = if (cfToken.isNotEmpty()) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = { cfTokenVisible = !cfTokenVisible }) {
                                Icon(
                                    if (cfTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
                        visualTransformation = if (cfTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Security badge
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(
                                "Stored 100% locally in encrypted app storage on this device — never uploaded to any server or cloud.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // ─── Android Permissions ───────────────────────────────────
                HorizontalDivider()

                Text(
                    "System Permissions (Android ${Build.VERSION.RELEASE ?: "14+"}):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                // Notification Permission Row (Android 13+)
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
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("Notifications", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (hasNotificationPermission) "Allowed" else "Required for background server",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hasNotificationPermission) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        if (!hasNotificationPermission) {
                            OutlinedButton(
                                onClick = { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text("Grant", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // Battery Optimization Row
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
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Battery Optimization", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                if (isBatteryExempt) "Unrestricted (24/7 Server)" else "Restricted (May pause in background)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isBatteryExempt) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.error
                            )
                        }
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
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                        ) {
                            Text("Disable", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onStartPreSetup(cfToken.trim())
                    onDismiss()
                },
                shape = RoundedCornerShape(10.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Initialize Setup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
