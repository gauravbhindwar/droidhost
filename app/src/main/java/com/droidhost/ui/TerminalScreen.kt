package com.droidhost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.data.TerminalConnectionState
import com.droidhost.domain.VmState

/**
 * Strips ANSI escape codes from terminal output.
 */
fun stripAnsi(rawText: String): String {
    return rawText
        .replace(Regex("\u001B\\[[;\\d]*[ -/]*[@-~]"), "")
        .replace(Regex("\u001B\\[\\?[\\d;]*[lh]"), "")
        .replace(Regex("\u001Bc"), "")
        .replace(Regex("\\[[0-9;]+m"), "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .trimEnd()
}

/**
 * Strips ANSI escape codes from terminal output and copies clean text
 * to the Android system clipboard with a user feedback toast.
 */
fun stripAnsiAndCopy(context: Context, rawText: String, label: String = "Terminal output"): String {
    val clean = stripAnsi(rawText)
    try {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, clean))
        val lines = clean.lines().count { it.isNotBlank() }
        Toast.makeText(context, "Copied $lines lines to clipboard", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
    return clean
}

/**
 * Parses ANSI color escape sequences into Compose AnnotatedString with colored spans.
 */
fun parseAnsiToAnnotatedString(raw: String): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val ansiRegex = Regex("\u001B\\[([0-9;]*)m|\\[([0-9;]+)m|\u001B\\[[;\\d]*[ -/]*[@-~]|\u001Bc|\u001B\\[\\?[\\d;]*[lh]")

    var lastIndex = 0
    var currentColor = Color(0xFF4AF626)
    var currentWeight = FontWeight.Normal

    for (match in ansiRegex.findAll(raw)) {
        if (match.range.first > lastIndex) {
            val textPart = raw.substring(lastIndex, match.range.first)
                .replace("\r\n", "\n")
                .replace("\r", "\n")
            builder.pushStyle(SpanStyle(color = currentColor, fontWeight = currentWeight))
            builder.append(textPart)
            builder.pop()
        }

        val codeGroup = match.groups[1]?.value ?: match.groups[2]?.value
        if (codeGroup != null) {
            val codes = if (codeGroup.isEmpty()) listOf(0) else codeGroup.split(";").mapNotNull { it.toIntOrNull() }
            for (code in codes) {
                when (code) {
                    0 -> {
                        currentColor = Color(0xFF4AF626)
                        currentWeight = FontWeight.Normal
                    }
                    1 -> currentWeight = FontWeight.Bold
                    30 -> currentColor = Color(0xFF6C757D)
                    31 -> currentColor = Color(0xFFE76F51)
                    32 -> currentColor = Color(0xFF2A9D8F)
                    33 -> currentColor = Color(0xFFE9C46A)
                    34 -> currentColor = Color(0xFF457B9D)
                    35 -> currentColor = Color(0xFFBB86FC)
                    36 -> currentColor = Color(0xFF2EC4B6)
                    37 -> currentColor = Color(0xFFF1FAEE)
                    90 -> currentColor = Color(0xFF8D99AE)
                    91 -> currentColor = Color(0xFFFF6B6B)
                    92 -> currentColor = Color(0xFF51CF66)
                    93 -> currentColor = Color(0xFFFCC419)
                    94 -> currentColor = Color(0xFF339AF0)
                    95 -> currentColor = Color(0xFFCC5DE8)
                    96 -> currentColor = Color(0xFF20C997)
                    97 -> currentColor = Color(0xFFFFFFFF)
                }
            }
        }
        lastIndex = match.range.last + 1
    }

    if (lastIndex < raw.length) {
        val textPart = raw.substring(lastIndex)
            .replace("\r\n", "\n")
            .replace("\r", "\n")
        builder.pushStyle(SpanStyle(color = currentColor, fontWeight = currentWeight))
        builder.append(textPart)
        builder.pop()
    }

    return builder.toAnnotatedString()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    state: DashboardState,
    viewModel: MainViewModel
) {
    var inputCommand by remember { mutableStateOf("") }
    var showTextViewer by remember { mutableStateOf(false) }
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val isImeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Auto-scroll to bottom whenever output updates OR keyboard opens
    LaunchedEffect(state.terminalOutput, verticalScroll.maxValue, isImeOpen) {
        if (state.terminalOutput.isNotEmpty()) {
            verticalScroll.scrollTo(verticalScroll.maxValue)
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
            .imePadding()
            .padding(12.dp)
    ) {
        // ── Status & Control Bar ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: connection status chip
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (statusText, statusColor) = when (state.terminalState) {
                    TerminalConnectionState.CONNECTED    -> "CONNECTED"    to Color(0xFF2A9D8F)
                    TerminalConnectionState.CONNECTING   -> "CONNECTING"   to Color(0xFFE9C46A)
                    TerminalConnectionState.DISCONNECTED -> "DISCONNECTED" to Color.Gray
                    TerminalConnectionState.ERROR        -> "ERROR"        to Color(0xFFE76F51)
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

            // Right: action buttons
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Prominent COPY ALL button
                FilledTonalButton(
                    onClick = {
                        val clean = stripAnsiAndCopy(context, state.terminalOutput, "DroidHost Terminal")
                        clipboardManager.setText(AnnotatedString(clean))
                    },
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(36.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Copy", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.width(2.dp))

                // Text viewer / inspector button
                IconButton(onClick = { showTextViewer = true }) {
                    Icon(Icons.Default.TextSnippet, contentDescription = "View & Select Text")
                }

                // Connect / disconnect toggle
                IconButton(onClick = {
                    if (state.terminalState == TerminalConnectionState.CONNECTED) {
                        viewModel.terminalDisconnect()
                    } else {
                        viewModel.terminalConnect()
                    }
                }) {
                    Icon(
                        if (state.terminalState == TerminalConnectionState.CONNECTED)
                            Icons.Default.PowerOff else Icons.Default.Refresh,
                        contentDescription = "Reconnect"
                    )
                }

                // Clear
                IconButton(onClick = { viewModel.terminalClear() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "Clear")
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Monospace Console Area ────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF0F141C))
                // Long-press anywhere on the console to copy all output
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clean = stripAnsiAndCopy(context, state.terminalOutput, "DroidHost Terminal")
                        clipboardManager.setText(AnnotatedString(clean))
                    }
                )
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
                Column(Modifier.fillMaxSize()) {
                    // Scrollable, selectable terminal text
                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        Text(
                            text = parseAnsiToAnnotatedString(
                                state.terminalOutput.ifEmpty { "[DroidHost ARM64 Linux VM Shell Ready]\n$ " }
                            ),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(verticalScroll)
                                .horizontalScroll(horizontalScroll)
                        )
                    }
                    // Hint text at the bottom of the console
                    Text(
                        text = "Tap 'Copy' to copy · Long-press console to copy all · Drag to select",
                        color = Color(0xFF3A4A5A),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Virtual Accessory Keybar ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TerminalKeyButton("Ctrl+C")   { viewModel.terminalSend("\u0003") }
            TerminalKeyButton("Ctrl+D")   { viewModel.terminalSend("\u0004") }
            TerminalKeyButton("Ctrl+Z")   { viewModel.terminalSend("\u001A") }
            TerminalKeyButton("Tab")      { viewModel.terminalSend("\t") }
            TerminalKeyButton("Esc")      { viewModel.terminalSend("\u001B") }
            TerminalKeyButton("▲")        { viewModel.terminalSend("\u001B[A") }
            TerminalKeyButton("▼")        { viewModel.terminalSend("\u001B[B") }
            TerminalKeyButton("📋 Copy") {
                val clean = stripAnsiAndCopy(context, state.terminalOutput, "DroidHost Terminal")
                clipboardManager.setText(AnnotatedString(clean))
            }
            TerminalKeyButton("Clear") {
                viewModel.terminalClear()
                viewModel.terminalSend("clear\n")
            }
        }

        Spacer(Modifier.height(6.dp))

        // ── Command Input Row ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = inputCommand,
                onValueChange = { inputCommand = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Enter shell command...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                },
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

    // ── Text Viewer Dialog ────────────────────────────────────────────────────
    if (showTextViewer) {
        val cleanText = stripAnsi(state.terminalOutput).ifBlank { "(No output yet)" }

        AlertDialog(
            onDismissRequest = { showTextViewer = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.TextSnippet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Terminal Output", style = MaterialTheme.typography.titleMedium)
                }
            },
            text = {
                Column {
                    Text(
                        "Select any text with drag handles, or use 'Copy All' to copy the full session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 200.dp, max = 360.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF0D1117))
                            .padding(10.dp)
                    ) {
                        SelectionContainer {
                            Text(
                                text = cleanText,
                                color = Color(0xFF4AF626),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(cleanText))
                        Toast.makeText(context, "Copied ${cleanText.lines().count { it.isNotBlank() }} lines", Toast.LENGTH_SHORT).show()
                        showTextViewer = false
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Copy All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextViewer = false }) {
                    Text("Close")
                }
            }
        )
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
