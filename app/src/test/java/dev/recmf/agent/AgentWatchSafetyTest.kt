package dev.recmf.agent

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Agent Control Mode must never be able to send anything to the watch beyond what reCMF
 * already sends every day (a notification, the now-playing text).
 *
 * This reads the package's source and fails if it names a protocol command, writes to the
 * Bluetooth connection, or mentions any of the transfers that can leave a watch unusable.
 * Crude on purpose: a grep cannot be argued with, and a change that needs one of these
 * words in this package is a change that deserves a person reading it.
 */
class AgentWatchSafetyTest {

    private val forbidden = listOf(
        "CmfCommand",
        "CmfConnection",
        "connection.send",
        "sendOnDataChannel",
        "FACTORY_RESET",
        "FIRMWARE",
        "WATCHFACE",
        "Watchface",
        "AGPS",
        "Agps",
        "Almanac",
        "QUICK_CARD",
        "UNBIND",
        "GETSECRET",
        "AT SET",
        "BluetoothGatt",
    )

    private val packageDir = listOf(
        File("src/main/java/dev/recmf/agent"),
        File("app/src/main/java/dev/recmf/agent"),
    ).first { it.isDirectory }

    @Test
    fun `agent package never touches the watch connection`() {
        val files = packageDir.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
        assertTrue(files.size >= 6, "expected the agent package's files, found ${files.map { it.name }}")

        val hits = files.flatMap { f ->
            val text = f.readText()
            forbidden.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        assertEquals(emptyList<String>(), hits)
    }
}
