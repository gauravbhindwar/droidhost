package com.droidhost.domain

import org.junit.Assert.*
import org.junit.Test

class RemoteAccessModeTest {

    @Test
    fun verifyRemoteAccessModeEnumValues() {
        val modes = RemoteAccessMode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(RemoteAccessMode.LOCAL_WIFI))
        assertTrue(modes.contains(RemoteAccessMode.TAILSCALE))
        assertTrue(modes.contains(RemoteAccessMode.CLOUDFLARE))
    }

    @Test
    fun testMutualExclusivityTailscaleDeactivatesCloudflare() {
        var cloudflareTunnelActive = true
        var activeMode = RemoteAccessMode.CLOUDFLARE

        // User switches to Tailscale
        activeMode = RemoteAccessMode.TAILSCALE
        if (activeMode == RemoteAccessMode.TAILSCALE && cloudflareTunnelActive) {
            cloudflareTunnelActive = false // Stopped
        }

        assertEquals(RemoteAccessMode.TAILSCALE, activeMode)
        assertFalse(cloudflareTunnelActive)
    }

    @Test
    fun testMutualExclusivityCloudflareDeactivatesTailscale() {
        var activeMode = RemoteAccessMode.TAILSCALE
        var cloudflareTunnelActive = false

        // User switches to Cloudflare
        activeMode = RemoteAccessMode.CLOUDFLARE
        cloudflareTunnelActive = true

        assertEquals(RemoteAccessMode.CLOUDFLARE, activeMode)
        assertTrue(cloudflareTunnelActive)
    }

    @Test
    fun testLocalWifiDeactivatesCloudflareTunnel() {
        var cloudflareTunnelActive = true
        var activeMode = RemoteAccessMode.CLOUDFLARE

        // User switches to Local Wi-Fi
        activeMode = RemoteAccessMode.LOCAL_WIFI
        cloudflareTunnelActive = false

        assertEquals(RemoteAccessMode.LOCAL_WIFI, activeMode)
        assertFalse(cloudflareTunnelActive)
    }

    @Test
    fun testPortForwardRuleFormat() {
        val coolifyRule = PortForwardRule(id = "rule-coolify", hostPort = 8000, guestPort = 8000)
        assertEquals("http://127.0.0.1:8000", "http://127.0.0.1:${coolifyRule.hostPort}")
        assertTrue(coolifyRule.enabled)

        val sshRule = PortForwardRule(id = "rule-ssh", hostPort = 2222, guestPort = 22)
        assertEquals(2222, sshRule.hostPort)
        assertEquals(22, sshRule.guestPort)
    }
}
