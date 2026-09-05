package com.droidhost

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.droidhost.data.HttpAgentRepository
import com.droidhost.domain.*
import com.droidhost.service.ServerModeService
import com.droidhost.ui.MainViewModel
import androidx.compose.material3.ExperimentalMaterial3Api

class MainActivity:ComponentActivity(){ override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState); ContextCompat.startForegroundService(this,Intent(this,ServerModeService::class.java).setAction(ServerModeService.ACTION_START)); setContent{DroidHostApp(getSharedPreferences("agent",MODE_PRIVATE).getString("token","").orEmpty())}} }
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DroidHostApp(agentToken:String){ val vm=remember{MainViewModel(HttpAgentRepository("http://127.0.0.1:8899", agentToken))}; val state by vm.state.collectAsState(); MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF0D5C63),secondary=Color(0xFFE07A5F),background=Color(0xFFF4F2EC))){ Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background){ Dashboard(state,vm) } } }
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Dashboard(state:com.droidhost.ui.DashboardState,vm:MainViewModel){ Scaffold(topBar={TopAppBar(title={Column{Text("DROIDHOST",fontWeight=FontWeight.Bold);Text("your phone. your server.",style=MaterialTheme.typography.labelSmall) }},actions={IconButton({vm.refresh()}){Icon(Icons.Default.Refresh,"Refresh")}})},bottomBar={NavigationBar{NavigationBarItem(true,{},{Icon(Icons.Default.Dashboard,"Dashboard");Text("Home")});NavigationBarItem(false,{},{Icon(Icons.AutoMirrored.Filled.ViewList,"Containers");Text("Containers")});NavigationBarItem(false,{},{Icon(Icons.Default.Terminal,"Terminal")});NavigationBarItem(false,{},{Icon(Icons.Default.Settings,"Settings")})}}){ p-> LazyColumn(Modifier.padding(p).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(vertical=16.dp)){ item{Header(state)};item{MetricRow(state)};item{SectionTitle("DOCKER")};item{DockerCard(state)};item{SectionTitle("CONTAINERS")}; if(state.loading)item{LinearProgressIndicator(Modifier.fillMaxWidth())}; if(state.error!=null)item{Text(state.error!!,color=MaterialTheme.colorScheme.error)}; items(state.containers.take(8)){ContainerRow(it,vm)};item{SectionTitle("QUICK ACTIONS")};item{QuickActions()}} } }
@Composable private fun Header(s:com.droidhost.ui.DashboardState){ Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("SERVER",style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.primary);Text(if(s.metrics.online)"Online" else "Offline",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold);Text(formatUptime(s.metrics.uptimeSeconds),style=MaterialTheme.typography.bodyMedium)};AssistChip(onClick={},label={Text("VM ${s.vmState.name.lowercase()}",maxLines=1)},leadingIcon={Icon(Icons.Default.Circle,"State",Modifier.size(12.dp),tint=if(s.metrics.online)Color(0xFF2A9D8F) else Color.Gray)})} }
@Composable private fun MetricRow(s:com.droidhost.ui.DashboardState){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){Metric("CPU","${s.metrics.cpuPercent.toInt()}%",Modifier.weight(1f));Metric("RAM",percent(s.metrics.memoryUsedBytes,s.metrics.memoryTotalBytes),Modifier.weight(1f));Metric("NETWORK",bytes(s.metrics.networkRxBytes+s.metrics.networkTxBytes),Modifier.weight(1f))}}
@Composable private fun Metric(label:String,value:String,modifier:Modifier){Card(modifier){Column(Modifier.padding(12.dp)){Text(label,style=MaterialTheme.typography.labelSmall);Text(value,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)}}}
@Composable private fun DockerCard(s:com.droidhost.ui.DashboardState){Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text("Docker Engine",fontWeight=FontWeight.Bold);Text("Connected to the Linux VM",style=MaterialTheme.typography.bodySmall)};Column(Modifier.weight(1f),horizontalAlignment=Alignment.End){Text("${s.containers.count{it.mappedState==ContainerState.RUNNING} } running",fontWeight=FontWeight.Bold);Text("${s.images} images · ${s.volumes} volumes",style=MaterialTheme.typography.bodySmall,maxLines=1)}}}}
@Composable private fun SectionTitle(t:String){Text(t,style=MaterialTheme.typography.labelLarge,color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Bold)}
@Composable private fun ContainerRow(c:Container,vm:MainViewModel){Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(c.names.firstOrNull()?:c.id.take(12),fontWeight=FontWeight.Bold,maxLines=1);Text(c.image,style=MaterialTheme.typography.bodySmall,maxLines=1);Text(c.status,style=MaterialTheme.typography.labelSmall,color=if(c.mappedState==ContainerState.RUNNING)Color(0xFF2A9D8F) else Color.Gray,maxLines=1)};if(c.mappedState==ContainerState.RUNNING)IconButton({vm.action(c.id,"stop")}){Icon(Icons.Default.Stop,"Stop")}else IconButton({vm.action(c.id,"start")}){Icon(Icons.Default.PlayArrow,"Start")};IconButton({vm.action(c.id,"restart")}){Icon(Icons.Default.Refresh,"Restart")}}}}
@Composable private fun QuickActions(){Column(verticalArrangement=Arrangement.spacedBy(8.dp)){Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){QuickAction("Start VM",Icons.Default.PlayArrow,Modifier.weight(1f));QuickAction("Terminal",Icons.Default.Terminal,Modifier.weight(1f))};Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){QuickAction("Storage",Icons.Default.Storage,Modifier.weight(1f));QuickAction("Network",Icons.Default.Wifi,Modifier.weight(1f))}}}
@Composable private fun QuickAction(label:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier){OutlinedButton({},modifier.height(52.dp)){Icon(icon,label);Spacer(Modifier.width(6.dp));Text(label,maxLines=1)}}
private fun formatUptime(s:Double)="Uptime ${s.toLong()/3600}h ${(s.toLong()%3600)/60}m"
private fun percent(used:Long,total:Long)=if(total==0L)"--" else "${(used*100/total)}%"
private fun bytes(n:Long)="${n/1024/1024} MB"
