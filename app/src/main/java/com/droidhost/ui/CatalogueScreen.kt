package com.droidhost.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droidhost.domain.*

@Composable
fun CatalogueScreen(
    state: DashboardState,
    viewModel: MainViewModel,
    onOpenTerminal: () -> Unit = {}
) {
    val items = remember(state.catalogueFilter, state.catalogueSearchQuery) {
        CatalogueRepository.items.filter { item ->
            val matchesCategory = state.catalogueFilter == CatalogueCategory.ALL || item.category == state.catalogueFilter
            val matchesSearch = state.catalogueSearchQuery.isBlank() ||
                    item.name.contains(state.catalogueSearchQuery, ignoreCase = true) ||
                    item.description.contains(state.catalogueSearchQuery, ignoreCase = true) ||
                    item.image.contains(state.catalogueSearchQuery, ignoreCase = true) ||
                    item.id.contains(state.catalogueSearchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 14.dp)
    ) {
        // Header
        item {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    "MARKETPLACE & 1-CLICK APPS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Deploy control panels (Dokploy, Coolify, Portainer), web apps, and databases directly to your ARM64 server.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Search Bar
        item {
            OutlinedTextField(
                value = state.catalogueSearchQuery,
                onValueChange = { viewModel.setCatalogueSearchQuery(it) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search Dokploy, Coolify, WordPress, databases...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                trailingIcon = {
                    if (state.catalogueSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setCatalogueSearchQuery("") }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
        }

        // Category Filter Chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CatalogueCategory.entries.forEach { category ->
                    FilterChip(
                        selected = state.catalogueFilter == category,
                        onClick = { viewModel.setCatalogueFilter(category) },
                        label = { Text(category.displayName, style = MaterialTheme.typography.labelSmall) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }
        }

        // Workload Cards
        items(items, key = { it.id }) { item ->
            CatalogueCard(
                item = item,
                isInstalled = viewModel.isCatalogueItemInstalled(item),
                runningContainer = viewModel.getRunningContainerForCatalogueItem(item),
                isDeploying = state.installingCatalogueId == item.id,
                onInstall = { viewModel.installCatalogueItem(item, onNavigateToTerminal = onOpenTerminal) },
                onOpenTerminal = onOpenTerminal
            )
        }
    }
}

@Composable
private fun CatalogueCard(
    item: CatalogueItem,
    isInstalled: Boolean,
    runningContainer: Container?,
    isDeploying: Boolean,
    onInstall: () -> Unit,
    onOpenTerminal: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var showScript by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isInstalled) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(getCategoryColor(item.category).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            getCategoryIcon(item.category),
                            contentDescription = null,
                            tint = getCategoryColor(item.category),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (item.isPanel) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        "PANEL",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            item.image,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Port badge
                if (item.port != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            ":${item.port}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            // Description
            Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            // Direct Script Accordion
            if (showScript) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.installScript,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(item.installScript))
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }

            // Action row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { showScript = !showScript },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        if (showScript) "Hide Command" else "View Script",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isInstalled && item.port != null) {
                        FilledTonalButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://localhost:${item.port}"))
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open :${item.port}", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (isInstalled) {
                        Surface(
                            color = Color(0xFF2A9D8F).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF2A9D8F))
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "ACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF2A9D8F),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                onInstall()
                                onOpenTerminal()
                            },
                            enabled = !isDeploying,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(34.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (isDeploying) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Deploying...", style = MaterialTheme.typography.labelSmall)
                            } else {
                                Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("1-Click Deploy", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getCategoryIcon(category: CatalogueCategory): ImageVector {
    return when (category) {
        CatalogueCategory.PANELS -> Icons.Default.DashboardCustomize
        CatalogueCategory.WEB -> Icons.Default.Language
        CatalogueCategory.DATABASE -> Icons.Default.Storage
        CatalogueCategory.MONITORING -> Icons.Default.Speed
        CatalogueCategory.GIT_COMPOSE -> Icons.Default.Source
        CatalogueCategory.ALL -> Icons.Default.Apps
    }
}

private fun getCategoryColor(category: CatalogueCategory): Color {
    return when (category) {
        CatalogueCategory.PANELS -> Color(0xFFE07A5F)
        CatalogueCategory.WEB -> Color(0xFF3D5A80)
        CatalogueCategory.DATABASE -> Color(0xFF2A9D8F)
        CatalogueCategory.MONITORING -> Color(0xFFE76F51)
        CatalogueCategory.GIT_COMPOSE -> Color(0xFF8338EC)
        CatalogueCategory.ALL -> Color(0xFF0D5C63)
    }
}
