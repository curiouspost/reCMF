package dev.recmf.agent

import dev.recmf.protocol.MusicButton
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentCoreTest {

    @Test
    fun `mode off leaves every button to music`() {
        MusicButton.entries.forEach { assertNull(AgentControl.actionFor(it, enabled = false)) }
    }

    @Test
    fun `mode on maps play pause next previous and never volume`() {
        assertEquals(AgentAction.GET_STATUS, AgentControl.actionFor(MusicButton.PLAY, true))
        assertEquals(AgentAction.GET_STATUS, AgentControl.actionFor(MusicButton.PAUSE, true))
        assertEquals(AgentAction.NEXT_TASK, AgentControl.actionFor(MusicButton.NEXT, true))
        assertEquals(AgentAction.PREVIOUS_TASK, AgentControl.actionFor(MusicButton.PREVIOUS, true))
        assertNull(AgentControl.actionFor(MusicButton.VOLUME_UP, true))
        assertNull(AgentControl.actionFor(MusicButton.VOLUME_DOWN, true))
    }

    @Test
    fun `debouncer drops a repeat inside the window only`() {
        var now = 1_000L
        val d = PressDebouncer(windowMillis = 700, clock = { now })
        assertTrue(d.accept(AgentAction.GET_STATUS))
        now += 300
        assertFalse(d.accept(AgentAction.GET_STATUS))
        assertTrue(d.accept(AgentAction.NEXT_TASK)) // a different press is not a repeat
        now += 800
        assertTrue(d.accept(AgentAction.NEXT_TASK))
    }

    @Test
    fun `gateway urls must be plain https`() {
        assertEquals("https://srv.tail1234.ts.net", GatewayUrl.normalize(" https://srv.tail1234.ts.net/ "))
        assertEquals("https://h:8443/gw", GatewayUrl.normalize("https://h:8443/gw"))
        assertNull(GatewayUrl.normalize("http://srv.tail1234.ts.net"))
        assertNull(GatewayUrl.normalize("https://user:pw@srv.ts.net"))
        assertNull(GatewayUrl.normalize("https://srv.ts.net/?x=1"))
        assertNull(GatewayUrl.normalize("srv.ts.net"))
        assertNull(GatewayUrl.normalize(""))
    }

    @Test
    fun `cursor walks across machines newest first and clamps`() {
        val c = TaskCursor()
        c.update(
            mapOf(
                0 to listOf(TaskCard("s1", 0, 30.0, "a", ""), TaskCard("s2", 0, 10.0, "b", "")),
                1 to listOf(TaskCard("m1", 1, 20.0, "c", "")),
            ),
        )
        assertEquals("s1" to 1, c.next()!!.let { it.first.id to it.second })
        assertEquals("m1", c.next()!!.first.id)
        assertEquals("s2", c.next()!!.first.id)
        assertEquals("s2", c.next()!!.first.id) // oldest: stays
        assertEquals("m1", c.previous()!!.first.id)

        // A new task arriving does not move what "previous" means.
        c.update(mapOf(0 to listOf(TaskCard("s0", 0, 40.0, "new", ""), TaskCard("s1", 0, 30.0, "a", "")),
            1 to listOf(TaskCard("m1", 1, 20.0, "c", ""))))
        assertEquals("s1", c.previous()!!.first.id)

        c.reset()
        assertEquals("s0", c.next()!!.first.id)
        assertNull(TaskCursor().next())
    }

    @Test
    fun `status rows`() {
        assertEquals("srv: idle" to "Agent mode", StatusLine.rows(listOf("srv: idle")))
        assertEquals("srv: idle" to "mac: 1 running", StatusLine.rows(listOf("srv: idle", "mac: 1 running")))
        assertEquals("Agents: no gateway" to "", StatusLine.rows(emptyList()))
    }

    @Test
    fun `only finished work and approvals buzz`() {
        assertTrue(EventPolicy.buzzes("task.completed"))
        assertTrue(EventPolicy.buzzes("approval.requested"))
        assertFalse(EventPolicy.buzzes("task.queued"))
        assertFalse(EventPolicy.buzzes("task.started"))
    }
}
