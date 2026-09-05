package com.droidhost.ui

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

                    Divider()

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
