package com.droidhost.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.domain.PortForwardRule
import com.droidhost.domain.VmState

@Composable
fun NetworkScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    var showAddDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        item {
            Text(
                "NETWORK & INTERFACES",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }

        // Network Status Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("QEMU SLIRP Virtual Network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("virtio-net-pci (user mode)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AssistChip(
                            onClick = {},
                            label = { Text(if (state.metrics.online) "CONNECTED" else "DISCONNECTED") },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = if (state.metrics.online) Color(0xFF2A9D8F).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }

                    HorizontalDivider()

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NetworkStatItem("Host Loopback", "127.0.0.1")
                        NetworkStatItem("Guest VM IP", if (state.vmState == VmState.RUNNING) "10.0.2.15" else "--")
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val rxMb = state.metrics.networkRxBytes / (1024 * 1024)
                        val txMb = state.metrics.networkTxBytes / (1024 * 1024)
                        NetworkStatItem("Traffic Received", if (state.metrics.online) "$rxMb MB" else "--")
                        NetworkStatItem("Traffic Transmitted", if (state.metrics.online) "$txMb MB" else "--")
                    }
                }
            }
        }

        // SSH Remote Access & Dynamic IP Solutions
        item {
            RemoteSshAccessCard(state = state, viewModel = viewModel)
        }

        // Port Forwarding Section
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "PORT FORWARDING",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { showAddDialog = true },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add Port")
                }
            }
        }

        // Built-in vm-agent port row (fixed)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "vm-agent & API (Built-in)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "127.0.0.1:8899  ➔  VM:8899 (tcp)",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    AssistChip(onClick = {}, label = { Text("System", style = MaterialTheme.typography.labelSmall) })
                }
            }
        }

        // User Port Forwarding Rules
        if (state.portForwardRules.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        Text("No custom port forwarding rules configured.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            items(state.portForwardRules, key = { it.id }) { rule ->
                PortRuleCard(
                    rule = rule,
                    onToggle = { viewModel.togglePortForwardRule(rule.id) },
                    onDelete = { viewModel.removePortForwardRule(rule.id) }
                )
            }
        }
    }

    if (showAddDialog) {
        AddPortForwardDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { hostPort, guestPort, proto ->
                viewModel.addPortForwardRule(hostPort, guestPort, proto)
                showAddDialog = false
            }
        )
    }
}

@Composable
private fun RemoteSshAccessCard(
    state: DashboardState,
    viewModel: MainViewModel
) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    var showGuide by remember { mutableStateOf(false) }
    var showCloudflareEdit by remember { mutableStateOf(false) }
    var cfTokenInput by remember(state.cloudflareToken) { mutableStateOf(state.cloudflareToken) }
    var cfTokenVisible by remember { mutableStateOf(false) }

    val sshIpCommand = "ssh root@${state.deviceLanIp} -p ${state.sshPort}"
    val sshMdnsCommand = "ssh root@${state.mdnsHostname} -p ${state.sshPort}"

    val networkTypeLabel = when {
        state.deviceLanIp.startsWith("100.") -> "Tailscale Mesh VPN (Fixed Static IP)"
        state.deviceLanIp.startsWith("192.168.") || state.deviceLanIp.startsWith("10.") || state.deviceLanIp.startsWith("172.") -> "Wi-Fi LAN (DHCP Dynamic)"
        state.deviceLanIp == "127.0.0.1" -> "Local Loopback"
        else -> "Network Interface"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(
                            "SSH REMOTE ACCESS",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            networkTypeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.deviceLanIp.startsWith("100.")) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(onClick = { viewModel.updateDeviceLanIp() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh IP", tint = MaterialTheme.colorScheme.primary)
                }
            }

            // Command 1: Direct IP
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Direct IP SSH Command:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(sshIpCommand))
                            Toast.makeText(context, "Copied SSH command", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = sshIpCommand,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Command 2: mDNS Hostname
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "mDNS Hostname (No IP needed on Wi-Fi):",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(sshMdnsCommand))
                            Toast.makeText(context, "Copied mDNS command", Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Text(
                    text = sshMdnsCommand,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            // ─── Tailscale / WireGuard Mesh VPN Card ───────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            val isTailscaleActive = state.deviceLanIp.startsWith("100.")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, tint = Color(0xFF2A9D8F), modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                "TAILSCALE / MESH VPN",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isTailscaleActive) "Connected — static 100.x.y.z IP active" else "Not connected — install Tailscale for fixed IP",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isTailscaleActive) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Surface(
                        color = if (isTailscaleActive) Color(0xFF2A9D8F).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .background(if (isTailscaleActive) Color(0xFF2A9D8F) else Color.Gray, RoundedCornerShape(4.dp))
                            )
                            Text(
                                if (isTailscaleActive) "ACTIVE" else "OFFLINE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isTailscaleActive) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isTailscaleActive) {
                    val tailscaleSsh = "ssh root@${state.deviceLanIp} -p ${state.sshPort}"
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tailscale Static IP SSH Command:", style = MaterialTheme.typography.labelSmall)
                            OutlinedButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(tailscaleSsh))
                                    Toast.makeText(context, "Copied Tailscale SSH command", Toast.LENGTH_SHORT).show()
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(11.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Copy", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text(
                            text = tailscaleSsh,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            color = Color(0xFF2A9D8F)
                        )
                        Text(
                            "MagicDNS Domain: ssh root@<phone-name>.tailnet.ts.net -p ${state.sshPort}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.tailscale.ipn")
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                } else {
                                    try {
                                        val playStore = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.tailscale.ipn"))
                                        context.startActivity(playStore)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Install Tailscale from Google Play", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Open Tailscale App", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text(
                        "Tailscale assigns your phone a permanent static 100.x.y.z IP and MagicDNS domain (*.tailnet.ts.net) that bypasses CGNAT anywhere worldwide without port forwarding.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            // ─── Cloudflare Tunnel Card ────────────────────────────────────
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Color(0xFFF4A261), modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                "CLOUDFLARE TUNNEL",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (state.cloudflareToken.isNotEmpty()) "Configured — access from anywhere" else "Not configured — local network only",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (state.cloudflareToken.isNotEmpty()) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    TextButton(
                        onClick = { showCloudflareEdit = !showCloudflareEdit },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            if (state.cloudflareToken.isNotEmpty()) Icons.Default.Edit else Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            if (state.cloudflareToken.isNotEmpty()) "Edit" else "Set Token",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                if (showCloudflareEdit) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = cfTokenInput,
                            onValueChange = { cfTokenInput = it },
                            label = { Text("Cloudflare Tunnel Token") },
                            placeholder = { Text("eyJhIjoiY...") },
                            trailingIcon = {
                                IconButton(onClick = { cfTokenVisible = !cfTokenVisible }) {
                                    Icon(
                                        if (cfTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            },
                            visualTransformation = if (cfTokenVisible)
                                androidx.compose.ui.text.input.VisualTransformation.None
                            else
                                androidx.compose.ui.text.input.PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    viewModel.saveCloudflareToken(cfTokenInput)
                                    showCloudflareEdit = false
                                    Toast.makeText(context, "Token saved on device", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Save", style = MaterialTheme.typography.labelMedium)
                            }
                            if (state.cloudflareToken.isNotEmpty()) {
                                OutlinedButton(
                                    onClick = {
                                        viewModel.clearCloudflareToken()
                                        cfTokenInput = ""
                                        showCloudflareEdit = false
                                    }
                                ) {
                                    Text("Clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                // Cloudflare 1-Tap Tunnel Automation (shown when token is set)
                if (state.cloudflareToken.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Tunnel Status indicator
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Tunnel Status",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold
                            )
                            Surface(
                                color = if (state.cloudflareTunnelActive) Color(0xFF2A9D8F).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(
                                                if (state.cloudflareTunnelActive) Color(0xFF2A9D8F) else Color.Gray,
                                                RoundedCornerShape(4.dp)
                                            )
                                    )
                                    Text(
                                        if (state.cloudflareTunnelActive) "ONLINE & ACTIVE" else "INACTIVE / READY",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (state.cloudflareTunnelActive) Color(0xFF2A9D8F) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // 1-Tap Action Button
                        val vmRunning = state.vmState == VmState.RUNNING
                        if (state.cloudflareTunnelActive) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.stopCloudflareTunnel()
                                    Toast.makeText(context, "Cloudflare Tunnel stopped", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Stop Cloudflare Tunnel")
                            }
                        } else {
                            Button(
                                onClick = {
                                    if (vmRunning) {
                                        viewModel.startCloudflareTunnel()
                                        Toast.makeText(context, "Starting Cloudflare Tunnel inside VM...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Start the Linux VM first", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                enabled = vmRunning,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF4A261)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CloudQueue, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (vmRunning) "Start Cloudflare Tunnel" else "Start VM First to Launch Tunnel",
                                    fontWeight = FontWeight.Bold,
                                    color = Color.Black
                                )
                            }
                        }

                        // Auto-start switch
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Auto-start on VM boot",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "Automatically launch tunnel when VM starts",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = state.autoStartCloudflareTunnel,
                                onCheckedChange = { viewModel.setAutoStartCloudflareTunnel(it) }
                            )
                        }

                        Text(
                            "Runs automatically in background inside the Linux VM. No manual terminal typing required.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // ─── Local-Only Security Badge ─────────────────────────────────
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "All credentials, tokens & keys are stored exclusively on this device. Nothing is ever uploaded to any server or cloud.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
            }

            // ─── Expandable Dynamic IP Guide ───────────────────────────────
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showGuide = !showGuide },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                "Phone IP keeps changing? How to fix it",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            if (showGuide) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    if (showGuide) {
                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                        Spacer(Modifier.height(8.dp))

                        Text(
                            "1. Tailscale / WireGuard (Recommended ⭐)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Install Tailscale on this phone and your PC. DroidHost automatically binds to the permanent Tailscale IP (100.x.y.z) and MagicDNS name. It NEVER changes and works from anywhere across Wi-Fi or 4G/5G without port forwarding.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "2. Cloudflare Tunnel (Set above ↑)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Add your Cloudflare tunnel token above and run 'cloudflared' inside the VM. Get a permanent public domain for SSH and web access from any device, anywhere in the world.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "3. mDNS Hostname (droidhost.local)",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "On the same local Wi-Fi, use 'ssh root@droidhost.local -p 2222'. ZeroConf mDNS resolves the phone automatically even when DHCP changes the IP.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(Modifier.height(8.dp))
                        Text(
                            "4. Router DHCP Reservation",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Open your router (192.168.1.1) ➔ DHCP ➔ Address Reservation. Bind your phone's Wi-Fi MAC address to a fixed IP (e.g. 192.168.1.50).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}




@Composable
private fun PortRuleCard(
    rule: PortForwardRule,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "http://127.0.0.1:${rule.hostPort}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Forwards to guest port ${rule.guestPort} (${rule.protocol.uppercase()})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle() }
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun AddPortForwardDialog(
    onDismiss: () -> Unit,
    onAdd: (Int, Int, String) -> Unit
) {
    var hostPortText by remember { mutableStateOf("") }
    var guestPortText by remember { mutableStateOf("") }
    var protocol by remember { mutableStateOf("tcp") }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Port Forwarding") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Map an Android host loopback port to a port inside the Linux VM.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = hostPortText,
                    onValueChange = { hostPortText = it },
                    label = { Text("Android Host Port (e.g. 8000)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                OutlinedTextField(
                    value = guestPortText,
                    onValueChange = { guestPortText = it },
                    label = { Text("Guest VM Port (e.g. 8000)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp)
                )

                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val hp = hostPortText.toIntOrNull()
                val gp = guestPortText.toIntOrNull()
                if (hp == null || hp !in 1024..65535) {
                    errorText = "Host port must be between 1024 and 65535"
                    return@Button
                }
                if (gp == null || gp !in 1..65535) {
                    errorText = "Guest port must be between 1 and 65535"
                    return@Button
                }
                onAdd(hp, gp, protocol)
            }) {
                Text("Add Rule")
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
private fun NetworkStatItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}
