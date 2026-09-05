package com.droidhost.domain

import org.junit.Assert.*
import org.junit.Test

class ContainerTest {

    @Test
    fun mapsContainerStatesCorrectly() {
        val running = Container(id = "1", names = listOf("/web"), image = "nginx", state = "running", status = "Up 2 hours")
        assertEquals(ContainerState.RUNNING, running.mappedState)

        val exited = Container(id = "2", names = listOf("/db"), image = "postgres", state = "exited", status = "Exited (0) 5 minutes ago")
        assertEquals(ContainerState.EXITED, exited.mappedState)

        val paused = Container(id = "3", names = listOf("/redis"), image = "redis", state = "paused", status = "Paused")
        assertEquals(ContainerState.PAUSED, paused.mappedState)

        val dead = Container(id = "4", names = listOf("/bad"), image = "test", state = "dead", status = "Dead")
        assertEquals(ContainerState.DEAD, dead.mappedState)

        val unknown = Container(id = "5", names = listOf("/custom"), image = "custom", state = "other", status = "Unknown")
        assertEquals(ContainerState.UNKNOWN, unknown.mappedState)
    }

    @Test
    fun portForwardRuleDefaults() {
        val rule = PortForwardRule(id = "rule-1", hostPort = 8080, guestPort = 80)
        assertEquals(8080, rule.hostPort)
        assertEquals(80, rule.guestPort)
        assertEquals("tcp", rule.protocol)
        assertTrue(rule.enabled)
    }

    @Test
    fun containerDetailWithSafeEnvironment() {
        val detail = ContainerDetail(
            id = "c123",
            name = "/app",
            image = "node:20",
            state = "running",
            status = "Up 10m",
            created = "2026-09-01",
            startedAt = "2026-09-01",
            envKeys = listOf("PORT", "NODE_ENV")
        )
        assertEquals("/app", detail.name)
        assertEquals(2, detail.envKeys.size)
        assertTrue(detail.envKeys.contains("PORT"))
    }

    @Test
    fun testCatalogueHasPanelsAndWebWorkloads() {
        val items = CatalogueRepository.items
        assertTrue("Catalogue must not be empty", items.isNotEmpty())

        // Dokploy & Coolify must be present as PaaS panels
        val dokploy = items.find { it.id == "dokploy" }
        assertNotNull(dokploy)
        assertTrue(dokploy!!.isPanel)
        assertEquals(3000, dokploy.port)

        val coolify = items.find { it.id == "coolify" }
        assertNotNull(coolify)
        assertTrue(coolify!!.isPanel)
        assertEquals(8000, coolify.port)

        val portainer = items.find { it.id == "portainer" }
        assertNotNull(portainer)
        assertTrue(portainer!!.isPanel)

        // Web, Databases & Monitoring
        assertNotNull(items.find { it.id == "wordpress" })
        assertNotNull(items.find { it.id == "nginx" })
        assertNotNull(items.find { it.id == "postgres" })
        assertNotNull(items.find { it.id == "redis" })
        assertNotNull(items.find { it.id == "uptime-kuma" })
        assertNotNull(items.find { it.id == "cloudflared" })
    }

    @Test
    fun testCatalogueCategoriesCoverage() {
        val categories = CatalogueRepository.items.map { it.category }.toSet()
        assertTrue(categories.contains(CatalogueCategory.PANELS))
        assertTrue(categories.contains(CatalogueCategory.WEB))
        assertTrue(categories.contains(CatalogueCategory.DATABASE))
        assertTrue(categories.contains(CatalogueCategory.MONITORING))
        assertTrue(categories.contains(CatalogueCategory.GIT_COMPOSE))
    }
}
