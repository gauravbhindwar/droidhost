package com.droidhost.domain

import com.droidhost.service.AssetStatus
import com.droidhost.service.AssetValidationReport
import org.junit.Assert.*
import org.junit.Test

class VmAssetValidationTest {

    @Test
    fun testValidReport() {
        val report = AssetValidationReport(
            valid = true,
            qemu = AssetStatus("/path/qemu", true, 30_000_000, "Bundled native binary"),
            kernel = AssetStatus("/path/Image", true, 9_212_416, "ARM64 Linux Kernel (9 MB)"),
            initrd = AssetStatus("/path/initrd.img", true, 25_661_283, "25060 KB"),
            disk = AssetStatus("/path/droidhost.ext4", true, 34_359_738_368L, "32768 MB"),
            token = AssetStatus("/path/agent-token", true, 64, "Generated"),
            errorMessage = null
        )
        assertTrue(report.valid)
        assertNull(report.errorMessage)
        assertEquals("Generated", report.token.details)
    }

    @Test
    fun testInvalidReportWithMissingKernel() {
        val report = AssetValidationReport(
            valid = false,
            qemu = AssetStatus("/path/qemu", true, 30_000_000, "Bundled native binary"),
            kernel = AssetStatus("/path/Image", false, 2048, "Invalid kernel size (2048 B)"),
            initrd = AssetStatus("/path/initrd.img", true, 25_661_283, "25060 KB"),
            disk = AssetStatus("/path/droidhost.ext4", true, 34_359_738_368L, "32768 MB"),
            token = AssetStatus("/path/agent-token", true, 64, "Generated"),
            errorMessage = "VM assets missing from /path: ARM64 Linux Image (Image)"
        )
        assertFalse(report.valid)
        assertNotNull(report.errorMessage)
        assertTrue(report.errorMessage!!.contains("ARM64 Linux Image"))
    }
}
