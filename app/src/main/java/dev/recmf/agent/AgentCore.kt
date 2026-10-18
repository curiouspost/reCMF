/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import dev.recmf.protocol.MusicButton
import java.net.URI
import java.net.URISyntaxException

/*
 * The parts of Agent Control Mode that decide things, kept free of Android and of JSON so
 * they can be unit-tested on a bare JVM. Everything that talks to the network or to the
 * system lives in the other files of this package.
 */

/** What a press on the watch's music screen asks the gateways for. All three only read. */
enum class AgentAction(val wire: String) {
    GET_STATUS("GET_STATUS"),
    NEXT_TASK("NEXT_TASK"),
    PREVIOUS_TASK("PREVIOUS_TASK"),
}

object AgentControl {

    /**
     * The agent action a music-screen press stands for, or null to let it control music
     * as it always has.
     *
     * Play and pause both mean "status": the watch decides which of the two to send from
     * what it last displayed, which is not something the wearer chooses. Volume is never
     * taken — it stays a volume button, so the wrist can always turn the phone down.
     */
    fun actionFor(button: MusicButton, enabled: Boolean): AgentAction? {
        if (!enabled) return null
        return when (button) {
            MusicButton.PLAY, MusicButton.PAUSE -> AgentAction.GET_STATUS
            MusicButton.NEXT -> AgentAction.NEXT_TASK
            MusicButton.PREVIOUS -> AgentAction.PREVIOUS_TASK
            MusicButton.VOLUME_UP, MusicButton.VOLUME_DOWN -> null
        }
    }
}

/**
 * Drops a second identical press that arrives within [windowMillis] of the first.
 *
 * A BLE write that is retried, or a thumb that lands twice, should cost the gateway one
 * request and the wrist one buzz.
 */
class PressDebouncer(
    private val windowMillis: Long = 700,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var last: AgentAction? = null
    private var lastAt = Long.MIN_VALUE / 2

    @Synchronized
    fun accept(action: AgentAction): Boolean {
        val now = clock()
        if (action == last && now - lastAt < windowMillis) return false
        last = action
        lastAt = now
        return true
    }
}

/** Which gateway events are worth buzzing the wrist for. */
object EventPolicy {
    private val BUZZ = setOf(
        "task.completed",
        "task.failed",
        "task.cancelled",
        "approval.requested",
        "approval.expired",
        "approval.denied",
    )

    fun buzzes(type: String): Boolean = type in BUZZ

    /** Every event moves some count, so every event refreshes the status line. */
    fun refreshesStatus(type: String): Boolean = type.startsWith("task.") || type.startsWith("approval.")

    fun isApprovalRequest(type: String): Boolean = type == "approval.requested"
}

/** The address of a gateway as somebody typed it, made usable — or refused. */
object GatewayUrl {

    /**
     * @return `https://host[:port][/path]` without a trailing slash, or null.
     *
     * HTTPS only: the bearer token rides in a header, and Android blocks cleartext for
     * this app anyway. Tailscale's `serve` gives each machine a real certificate, which is
     * the intended setup. Credentials in the URL are refused rather than silently dropped.
     */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.userInfo != null) return null
        if (uri.query != null || uri.fragment != null) return null
        return trimmed
    }
}

/** One task as the watch shows it, with enough to order it against other machines' tasks. */
data class TaskCard(
    val id: String,
    val gateway: Int,
    val createdAt: Double,
    val title: String,
    val body: String,
)

/**
 * Walks the recent tasks of every gateway as one list, newest first.
 *
 * The phone keeps its own position rather than asking each gateway to, because "next"
 * has to cross from the server's tasks into the Mac's. The position follows a task's id
 * across refreshes, so a new task arriving does not shift what "previous" means.
 */
class TaskCursor {
    private var cards: List<TaskCard> = emptyList()
    private var currentId: String? = null

    @Synchronized
    fun update(byGateway: Map<Int, List<TaskCard>>) {
        cards = byGateway.values.flatten().sortedByDescending { it.createdAt }
    }

    @Synchronized
    fun reset() {
        currentId = null
    }

    /** Toward older tasks. The first press shows the newest. */
    @Synchronized
    fun next(): Pair<TaskCard, Int>? = step(+1)

    /** Toward newer tasks. */
    @Synchronized
    fun previous(): Pair<TaskCard, Int>? = step(-1)

    private fun step(direction: Int): Pair<TaskCard, Int>? {
        if (cards.isEmpty()) return null
        val at = cards.indexOfFirst { it.id == currentId }
        val target = if (at < 0) 0 else (at + direction).coerceIn(0, cards.lastIndex)
        currentId = cards[target].id
        return cards[target] to target + 1
    }

    @Synchronized
    fun size(): Int = cards.size
}

/** Puts one status line per gateway on the two text rows of the music screen. */
object StatusLine {
    fun rows(lines: List<String>): Pair<String, String> {
        val shown = lines.filter { it.isNotBlank() }
        return when (shown.size) {
            0 -> "Agents: no gateway" to ""
            1 -> shown[0] to "Agent mode"
            else -> shown[0] to shown.drop(1).joinToString(" · ")
        }
    }
}

/*
 * What this package may say to the watch: nothing directly.
 *
 * Agent Control Mode reaches the watch only through two paths reCMF already uses all day:
 * the notification queue, and the now-playing text. It never names a protocol command,
 * never writes to the Bluetooth connection, and never touches the bulk transfers that could
 * leave a watch unusable. AgentWatchSafetyTest scans every file in this package and fails
 * the build if that ever stops being true.
 */
