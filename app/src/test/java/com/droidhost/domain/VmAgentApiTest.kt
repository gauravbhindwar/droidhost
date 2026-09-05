package com.droidhost.domain

import org.junit.Assert.*
import org.junit.Test

class VmAgentApiTest {

    @Test
    fun testMetricsCalculations() {
        val metrics = Metrics(
            online = true,
            uptimeSeconds = 3600.0,
            cpuPercent = 25.5,
            memoryTotalBytes = 2048L * 1024 * 1024,
            memoryUsedBytes = 512L * 1024 * 1024,
            storageTotalBytes = 10L * 1024 * 1024 * 1024,
            storageUsedBytes = 2L * 1024 * 1024 * 1024,
            networkRxBytes = 10485760L,
            networkTxBytes = 5242880L
        )

        assertTrue(metrics.online)
        assertEquals(3600.0, metrics.uptimeSeconds, 0.01)
        assertEquals(25.5, metrics.cpuPercent, 0.01)

        val memoryUsedMb = metrics.memoryUsedBytes / (1024 * 1024)
        val memoryTotalMb = metrics.memoryTotalBytes / (1024 * 1024)
        assertEquals(512L, memoryUsedMb)
        assertEquals(2048L, memoryTotalMb)

        val rxMb = metrics.networkRxBytes / (1024 * 1024)
        val txMb = metrics.networkTxBytes / (1024 * 1024)
        assertEquals(10L, rxMb)
        assertEquals(5L, txMb)
    }

    @Test
    fun testServerFailureMessages() {
        val bootFailure = ServerFailure.VmBoot("kernel panic")
        assertTrue(bootFailure.userMessage.contains("kernel panic"))

        assertEquals("Not enough memory is available to start the VM", ServerFailure.InsufficientRam.userMessage)
        assertEquals("Not enough storage is available for the VM disk", ServerFailure.InsufficientStorage.userMessage)
        assertEquals("Docker Engine is unavailable inside the VM", ServerFailure.DockerUnavailable.userMessage)
        assertEquals("The VM agent is not responding", ServerFailure.AgentUnavailable.userMessage)
        assertEquals("VM networking could not be configured", ServerFailure.NetworkingFailure.userMessage)
        assertEquals("The selected Android port is already in use", ServerFailure.PortInUse.userMessage)
        assertEquals("The VM disk is corrupted and could not be mounted", ServerFailure.CorruptedDisk.userMessage)
    }

    @Test
    fun testStorageBreakdownCalculations() {
        val breakdown = StorageBreakdown(
            vmDiskTotalBytes = 4L * 1024 * 1024 * 1024,
            vmDiskUsedBytes = 2L * 1024 * 1024 * 1024,
            dockerContainersBytes = 500L * 1024 * 1024,
            dockerImagesBytes = 1200L * 1024 * 1024,
            dockerVolumesBytes = 300L * 1024 * 1024,
            androidTotalBytes = 64L * 1024 * 1024 * 1024,
            androidAvailableBytes = 32L * 1024 * 1024 * 1024
        )

        val vmUsedGb = breakdown.vmDiskUsedBytes / (1024 * 1024 * 1024)
        assertEquals(2L, vmUsedGb)
        val dockerTotalBytes = breakdown.dockerContainersBytes + breakdown.dockerImagesBytes + breakdown.dockerVolumesBytes
        assertEquals(2000L * 1024 * 1024, dockerTotalBytes)
    }
}
