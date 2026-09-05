package com.droidhost.domain

import kotlinx.serialization.Serializable

enum class VmState { STOPPED, STARTING, RUNNING, STOPPING, FAILED }
enum class ContainerState { RUNNING, PAUSED, RESTARTING, EXITED, DEAD, CREATED, UNKNOWN }

@Serializable data class Metrics(val online:Boolean=false,val uptimeSeconds:Double=0.0,val cpuPercent:Double=0.0,val memoryTotalBytes:Long=0,val memoryUsedBytes:Long=0,val storageTotalBytes:Long=0,val storageUsedBytes:Long=0,val networkRxBytes:Long=0,val networkTxBytes:Long=0)
@Serializable data class Port(val ip:String="",val privatePort:Int=0,val publicPort:Int=0,val type:String="tcp")
@Serializable data class Container(val id:String,val names:List<String>,val image:String,val imageId:String="",val command:String="",val created:Long=0,val state:String,val status:String,val ports:List<Port> = emptyList(),val labels:Map<String,String> = emptyMap()) { val mappedState:ContainerState get()=when(state.lowercase()){"running"->ContainerState.RUNNING;"paused"->ContainerState.PAUSED;"restarting"->ContainerState.RESTARTING;"exited"->ContainerState.EXITED;"dead"->ContainerState.DEAD;"created"->ContainerState.CREATED;else->ContainerState.UNKNOWN} }
@Serializable data class Mount(val type:String,val source:String,val destination:String,val mode:String="",val rw:Boolean=false)
@Serializable data class NetworkEndpoint(val ipAddress:String="",val gateway:String="",val macAddress:String="")
@Serializable data class ContainerDetail(val id:String,val name:String,val image:String,val state:String,val status:String,val created:String,val startedAt:String,val restartCount:Int=0,val command:List<String> = emptyList(),val ports:List<Port> = emptyList(),val mounts:List<Mount> = emptyList(),val networks:Map<String,NetworkEndpoint> = emptyMap(),val envKeys:List<String> = emptyList(),val labels:Map<String,String> = emptyMap())
@Serializable data class ContainerStats(val cpuPercent:Double=0.0,val memoryUsedBytes:Long=0,val memoryLimitBytes:Long=0,val memoryPercent:Double=0.0,val networkRxBytes:Long=0,val networkTxBytes:Long=0)
@Serializable data class ImageInfo(val id:String,val repoTags:List<String> = emptyList(),val size:Long=0,val created:Long=0)
@Serializable data class VolumeInfo(val name:String,val driver:String,val mountpoint:String)
@Serializable data class NetworkInfo(val id:String,val name:String,val driver:String,val scope:String)

data class VmConfiguration(val cpuCores:Int,val ramMb:Int,val diskGb:Int,val autoStart:Boolean)
data class DeviceResources(val cpuCores:Int,val totalRamMb:Int,val availableStorageGb:Int)
data class ConfigValidation(val valid:Boolean,val errors:List<String>)
fun validateVmConfiguration(config:VmConfiguration, device:DeviceResources):ConfigValidation { val e=mutableListOf<String>(); if(config.cpuCores !in 1..device.cpuCores)e += "CPU allocation must be between 1 and ${device.cpuCores} cores"; val maxRam=(device.totalRamMb*3/4).coerceAtLeast(512); if(config.ramMb !in 512..maxRam)e += "RAM allocation must be between 512 MB and $maxRam MB"; val maxDisk=(device.availableStorageGb-2).coerceAtLeast(4); if(config.diskGb !in 4..maxDisk)e += "Disk size must be between 4 GB and $maxDisk GB"; return ConfigValidation(e.isEmpty(),e) }

sealed class ServerFailure(val userMessage:String) { class VmBoot(reason:String):ServerFailure("VM failed to boot: $reason"); data object InsufficientRam:ServerFailure("Not enough memory is available to start the VM"); data object InsufficientStorage:ServerFailure("Not enough storage is available for the VM disk"); data object DockerUnavailable:ServerFailure("Docker Engine is unavailable inside the VM"); data object AgentUnavailable:ServerFailure("The VM agent is not responding"); data object NetworkingFailure:ServerFailure("VM networking could not be configured"); data object PortInUse:ServerFailure("The selected Android port is already in use"); data object CorruptedDisk:ServerFailure("The VM disk is corrupted and could not be mounted") }

enum class RemoteAccessMode { LOCAL_WIFI, TAILSCALE, CLOUDFLARE }

@Serializable data class PortForwardRule(val id:String, val hostPort:Int, val guestPort:Int, val protocol:String="tcp", val enabled:Boolean=true)
data class StorageBreakdown(val vmDiskTotalBytes:Long=0, val vmDiskUsedBytes:Long=0, val dockerContainersBytes:Long=0, val dockerImagesBytes:Long=0, val dockerVolumesBytes:Long=0, val androidTotalBytes:Long=0, val androidAvailableBytes:Long=0)
