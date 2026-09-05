package com.droidhost.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.data.TerminalConnectionState
import com.droidhost.domain.VmState
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    var inputCommand by remember { mutableStateOf("") }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom on new output
    LaunchedEffect(state.terminalOutput) {
        if (state.terminalOutput.isNotEmpty()) {
            scope.launch {
                verticalScroll.animateScrollTo(verticalScroll.maxValue)
            }
        }
    }

    // Auto connect when VM is running and terminal is disconnected
    LaunchedEffect(state.vmState) {
        if (state.vmState == VmState.RUNNING && state.terminalState == TerminalConnectionState.DISCONNECTED) {
            viewModel.terminalConnect()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        // Status & Control Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (statusText, statusColor) = when (state.terminalState) {
                    TerminalConnectionState.CONNECTED -> "CONNECTED" to Color(0xFF2A9D8F)
                    TerminalConnectionState.CONNECTING -> "CONNECTING" to Color(0xFFE9C46A)
                    TerminalConnectionState.DISCONNECTED -> "DISCONNECTED" to Color.Gray
                    TerminalConnectionState.ERROR -> "ERROR" to Color(0xFFE76F51)
                }

                AssistChip(
                    onClick = {},
                    label = { Text(statusText, style = MaterialTheme.typography.labelSmall) },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = statusColor
                        )
                    }
                )

                Spacer(Modifier.width(8.dp))
                Text(
                    "ARM64 Linux PTY",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row {
                IconButton(onClick = {
                    if (state.terminalState == TerminalConnectionState.CONNECTED) {
                        viewModel.terminalDisconnect()
                    } else {
                        viewModel.terminalConnect()
                    }
                }) {
                    Icon(
                        if (state.terminalState == TerminalConnectionState.CONNECTED) Icons.Default.PowerOff else Icons.Default.Refresh,
                        contentDescription = "Reconnect"
                    )
                }
                IconButton(onClick = { viewModel.terminalClear() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // Monospace Console Screen
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F141C))
                .padding(10.dp)
        ) {
            if (state.vmState != VmState.RUNNING) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Linux VM is not running",
                            color = Color(0xFF8E99A8),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { viewModel.startVm() }) {
                            Text("Start VM to Open Terminal")
                        }
                    }
                }
            } else {
                Text(
                    text = state.terminalOutput.ifEmpty { "[DroidHost ARM64 Linux VM Shell Ready]\n$ " },
                    color = Color(0xFF4AF626), // Classic terminal phosphor green
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(verticalScroll)
                        .horizontalScroll(horizontalScroll)
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Virtual Accessory Keybar (Ctrl+C, Ctrl+D, Tab, Esc, Arrows)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TerminalKeyButton("Ctrl+C") { viewModel.terminalSend("\u0003") }
            TerminalKeyButton("Ctrl+D") { viewModel.terminalSend("\u0004") }
            TerminalKeyButton("Ctrl+Z") { viewModel.terminalSend("\u001A") }
            TerminalKeyButton("Tab") { viewModel.terminalSend("\t") }
            TerminalKeyButton("Esc") { viewModel.terminalSend("\u001B") }
            TerminalKeyButton("▲") { viewModel.terminalSend("\u001B[A") }
            TerminalKeyButton("▼") { viewModel.terminalSend("\u001B[B") }
            TerminalKeyButton("Clear") { viewModel.terminalSend("clear\n") }
        }

        Spacer(Modifier.height(8.dp))

        // Input Line
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Enter shell command...", fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (inputCommand.isNotBlank()) {
                        viewModel.terminalSend(inputCommand + "\n")
                        inputCommand = ""
                    }
                })
            )

            Spacer(Modifier.width(8.dp))

            Button(
                onClick = {
                    if (inputCommand.isNotBlank()) {
                        viewModel.terminalSend(inputCommand + "\n")
                        inputCommand = ""
                    }
                },
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Default.Send, contentDescription = "Send", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun TerminalKeyButton(label: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
        modifier = Modifier.height(34.dp)
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
