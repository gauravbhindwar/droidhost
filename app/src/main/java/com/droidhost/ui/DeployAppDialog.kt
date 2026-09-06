package com.droidhost.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.droidhost.domain.ContainerDeploySpec
import com.droidhost.domain.DeployProgress
import com.droidhost.domain.DeployStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeployAppDialog(
    onDismiss: () -> Unit,
    deployProgress: DeployProgress,
    onDeploySingle: (ContainerDeploySpec) -> Unit,
    onDeployCompose: (String, String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Single, 1: Compose

    // Single container state
    var name by remember { mutableStateOf("") }
    var image by remember { mutableStateOf("") }
    var hostPort by remember { mutableStateOf("") }
    var containerPort by remember { mutableStateOf("") }
    var restartPolicy by remember { mutableStateOf("unless-stopped") }
    var memoryMb by remember { mutableStateOf("") }
    var envList by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var volumeList by remember { mutableStateOf(listOf<String>()) }

    var newEnvKey by remember { mutableStateOf("") }
    var newEnvVal by remember { mutableStateOf("") }
    var newVolume by remember { mutableStateOf("") }

    // Compose state
    var projectName by remember { mutableStateOf("") }
    var composeYaml by remember {
        mutableStateOf(
            """
            services:
              web:
                image: nginx:alpine
                ports:
                  - "8080:80"
                restart: unless-stopped
            """.trimIndent()
        )
    }

    Dialog(
        onDismissRequest = {
            if (deployProgress.step == DeployStep.IDLE || deployProgress.step == DeployStep.RUNNING || deployProgress.step == DeployStep.FAILED) {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f)
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Deploy Application",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Run production Docker workloads on your ARM64 VM",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDismiss, enabled = deployProgress.step != DeployStep.PULLING && deployProgress.step != DeployStep.CREATING && deployProgress.step != DeployStep.STARTING) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Progress Banner (if active)
                if (deployProgress.step != DeployStep.IDLE) {
                    DeployProgressBanner(progress = deployProgress)
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Mode Tabs
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Single Container") },
                        icon = { Icon(Icons.Default.Layers, contentDescription = null) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Docker Compose") },
                        icon = { Icon(Icons.Default.AccountTree, contentDescription = null) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (selectedTab == 0) {
                        // Single Container Form
                        OutlinedTextField(
                            value = image,
                            onValueChange = { image = it },
                            label = { Text("Docker Image *") },
                            placeholder = { Text("e.g. redis:alpine, nginx:alpine, postgres:16-alpine") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) }
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it.trim().replace(" ", "-") },
                            label = { Text("Container Name (Optional)") },
                            placeholder = { Text("e.g. redis-cache, my-nginx") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = hostPort,
                                onValueChange = { hostPort = it.filter { char -> char.isDigit() } },
                                label = { Text("Host Port") },
                                placeholder = { Text("8080") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = containerPort,
                                onValueChange = { containerPort = it.filter { char -> char.isDigit() } },
                                label = { Text("Container Port") },
                                placeholder = { Text("80") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Environment Variables
                        Text("Environment Variables", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newEnvKey,
                                onValueChange = { newEnvKey = it },
                                label = { Text("KEY") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = newEnvVal,
                                onValueChange = { newEnvVal = it },
                                label = { Text("VALUE") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    if (newEnvKey.isNotBlank()) {
                                        envList = envList + Pair(newEnvKey.trim(), newEnvVal.trim())
                                        newEnvKey = ""
                                        newEnvVal = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Add Env", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        envList.forEachIndexed { idx, pair ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${pair.first}=${pair.second}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { envList = envList.filterIndexed { i, _ -> i != idx } }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Volumes
                        Text("Volume Mounts", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newVolume,
                                onValueChange = { newVolume = it },
                                label = { Text("Volume Path (e.g. /data/app:/app/data)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            IconButton(
                                onClick = {
                                    if (newVolume.isNotBlank()) {
                                        volumeList = volumeList + newVolume.trim()
                                        newVolume = ""
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AddCircle, contentDescription = "Add Volume", tint = MaterialTheme.colorScheme.primary)
                            }
                        }

                        volumeList.forEachIndexed { idx, vol ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(vol, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                                IconButton(onClick = { volumeList = volumeList.filterIndexed { i, _ -> i != idx } }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                    } else {
                        // Compose Form
                        OutlinedTextField(
                            value = projectName,
                            onValueChange = { projectName = it.trim().replace(" ", "-") },
                            label = { Text("Project Name *") },
                            placeholder = { Text("e.g. web-stack, monitoring") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text("docker-compose.yaml", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        OutlinedTextField(
                            value = composeYaml,
                            onValueChange = { composeYaml = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp),
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = {
                            if (selectedTab == 0) {
                                val hp = hostPort.toIntOrNull() ?: 0
                                val cp = containerPort.toIntOrNull() ?: 0
                                val mem = (memoryMb.toLongOrNull() ?: 0L) * 1024L * 1024L
                                onDeploySingle(
                                    ContainerDeploySpec(
                                        name = name.trim(),
                                        image = image.trim(),
                                        hostPort = hp,
                                        containerPort = cp,
                                        env = envList.toMap(),
                                        volumes = volumeList,
                                        restartPolicy = restartPolicy,
                                        memoryBytes = mem
                                    )
                                )
                            } else {
                                onDeployCompose(projectName.trim(), composeYaml.trim())
                            }
                        },
                        enabled = if (selectedTab == 0) image.isNotBlank() else projectName.isNotBlank() && composeYaml.isNotBlank()
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Deploy")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeployProgressBanner(progress: DeployProgress) {
    val bgColor = when (progress.step) {
        DeployStep.RUNNING -> Color(0xFF10B981).copy(alpha = 0.15f)
        DeployStep.FAILED -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val contentColor = when (progress.step) {
        DeployStep.RUNNING -> Color(0xFF059669)
        DeployStep.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (progress.step != DeployStep.RUNNING && progress.step != DeployStep.FAILED) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = contentColor)
            } else if (progress.step == DeployStep.RUNNING) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = contentColor)
            } else {
                Icon(Icons.Default.Error, contentDescription = null, tint = contentColor)
            }
            Column {
                Text(
                    text = when (progress.step) {
                        DeployStep.VALIDATING -> "Validating Configuration..."
                        DeployStep.PULLING -> "Pulling Docker Image..."
                        DeployStep.CREATING -> "Creating Container..."
                        DeployStep.STARTING -> "Starting Container..."
                        DeployStep.RUNNING -> "Deployment Successful!"
                        DeployStep.FAILED -> "Deployment Failed"
                        DeployStep.IDLE -> ""
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                if (progress.message.isNotBlank()) {
                    Text(text = progress.message, style = MaterialTheme.typography.bodySmall, color = contentColor)
                }
            }
        }
    }
}
