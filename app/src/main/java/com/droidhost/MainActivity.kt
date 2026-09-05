package com.droidhost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.droidhost.data.HttpAgentRepository
import com.droidhost.data.WebSocketTerminalRepository
import com.droidhost.service.ServerModeService
import com.droidhost.service.VmManager
import com.droidhost.ui.*

enum class ScreenTab {
    HOME,
    CONTAINERS,
    TERMINAL,
    STORAGE,
    NETWORK,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(
            this,
            Intent(this, ServerModeService::class.java).setAction(ServerModeService.ACTION_START)
        )
        setContent {
            val agentToken = getSharedPreferences("agent", MODE_PRIVATE).getString("token", "").orEmpty()
            DroidHostApp(agentToken = agentToken)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroidHostApp(agentToken: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { HttpAgentRepository("http://127.0.0.1:8899", agentToken) }
    val vmManager = remember { VmManager.getInstance(context, repository) }
    val terminalRepository = remember { WebSocketTerminalRepository("ws://127.0.0.1:8899/v1/terminal", agentToken) }

    val vm = remember {
        MainViewModel(
            repository = repository,
            vmManager = vmManager,
            terminalRepository = terminalRepository,
            context = context
        )
    }

    // Automatically re-check battery optimization whenever user returns to the app
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.checkBatteryOptimization()
    }

    val state by vm.state.collectAsState()
    var currentTab by remember { mutableStateOf(ScreenTab.HOME) }
    var showSplash by remember { mutableStateOf(true) }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF0D5C63),
            secondary = Color(0xFFE07A5F),
            background = Color(0xFFF4F2EC),
            surface = Color(0xFFFAF9F6)
        )
    ) {
        if (state.showBatteryOptimizationDialog) {
            AlertDialog(
                onDismissRequest = { vm.dismissBatteryDialog(proceedWithPendingStart = false) },
                icon = {
                    Icon(
                        Icons.Default.BatteryAlert,
                        contentDescription = "Battery Alert",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        "Disable Battery Optimization",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "DroidHost runs an ARM64 Linux VM and server in the background.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "Android's battery optimization will pause or kill the VM when the screen is turned off or when switching apps.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "To keep your server and containers running 24/7 without interruption, please turn off battery optimization (set to 'Unrestricted').",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            vm.requestDisableBatteryOptimization(context)
                            vm.dismissBatteryDialog(proceedWithPendingStart = true)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Turn Off Optimization")
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            vm.dismissBatteryDialog(proceedWithPendingStart = true)
                        }
                    ) {
                        Text("Later")
                    }
                }
            )
        }

        if (showSplash) {
            SplashScreen(onSplashComplete = { showSplash = false })
        } else {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Image(
                                        painter = painterResource(id = R.drawable.droidhost_logo),
                                        contentDescription = "DroidHost Logo",
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column {
                                        val title = when (currentTab) {
                                            ScreenTab.HOME -> "DROIDHOST"
                                            ScreenTab.CONTAINERS -> "CONTAINERS"
                                            ScreenTab.TERMINAL -> "TERMINAL"
                                            ScreenTab.STORAGE -> "STORAGE"
                                            ScreenTab.NETWORK -> "NETWORK"
                                            ScreenTab.SETTINGS -> "SETTINGS"
                                        }
                                        Text(title, fontWeight = FontWeight.Bold)
                                        if (currentTab == ScreenTab.HOME) {
                                            Text("your phone. your server.", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            },
                            navigationIcon = {
                                if (currentTab == ScreenTab.STORAGE || currentTab == ScreenTab.NETWORK) {
                                    IconButton(onClick = { currentTab = ScreenTab.HOME }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { vm.refresh() }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                }
                            }
                        )
                    },
                bottomBar = {
                    val isImeOpen = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
                    if (!isImeOpen || currentTab != ScreenTab.TERMINAL) {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == ScreenTab.HOME,
                                onClick = { currentTab = ScreenTab.HOME },
                                icon = { Icon(Icons.Default.Dashboard, contentDescription = "Dashboard") },
                                label = { Text("Home") }
                            )
                            NavigationBarItem(
                                selected = currentTab == ScreenTab.CONTAINERS,
                                onClick = { currentTab = ScreenTab.CONTAINERS },
                                icon = { Icon(Icons.AutoMirrored.Filled.ViewList, contentDescription = "Containers") },
                                label = { Text("Containers") }
                            )
                            NavigationBarItem(
                                selected = currentTab == ScreenTab.TERMINAL,
                                onClick = { currentTab = ScreenTab.TERMINAL },
                                icon = { Icon(Icons.Default.Terminal, contentDescription = "Terminal") },
                                label = { Text("Terminal") }
                            )
                            NavigationBarItem(
                                selected = currentTab == ScreenTab.SETTINGS,
                                onClick = { currentTab = ScreenTab.SETTINGS },
                                icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                                label = { Text("Settings") }
                            )
                        }
                    }
                }
            ) { padding ->
                Box(modifier = Modifier.padding(padding)) {
                    when (currentTab) {
                        ScreenTab.HOME -> DashboardScreen(
                            state = state,
                            viewModel = vm,
                            onNavigateToContainers = { currentTab = ScreenTab.CONTAINERS },
                            onNavigateToTerminal = { currentTab = ScreenTab.TERMINAL },
                            onNavigateToStorage = { currentTab = ScreenTab.STORAGE },
                            onNavigateToNetwork = { currentTab = ScreenTab.NETWORK },
                            onNavigateToSettings = { currentTab = ScreenTab.SETTINGS }
                        )
                        ScreenTab.CONTAINERS -> ContainersScreen(
                            state = state,
                            viewModel = vm,
                            onOpenTerminal = { currentTab = ScreenTab.TERMINAL }
                        )
                        ScreenTab.TERMINAL -> TerminalScreen(
                            state = state,
                            viewModel = vm
                        )
                        ScreenTab.STORAGE -> StorageScreen(
                            state = state,
                            viewModel = vm
                        )
                        ScreenTab.NETWORK -> NetworkScreen(
                            state = state,
                            viewModel = vm
                        )
                        ScreenTab.SETTINGS -> SettingsScreen(
                            state = state,
                            viewModel = vm
                        )
                    }
                }
            }
        }
    }
}
}
