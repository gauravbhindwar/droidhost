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
}
