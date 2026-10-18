/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.content.Context
import android.util.Log
import dev.recmf.media.NowPlaying
import dev.recmf.notifications.NotificationIcon
import dev.recmf.notifications.OutgoingNotifications
import dev.recmf.notifications.WatchNotification
import dev.recmf.protocol.MusicButton
import dev.recmf.protocol.MusicPlaybackState
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Agent Control Mode, as the watch service sees it: three hooks and nothing else.
 *
 *  - [onMusicButton] — a press on the watch's music screen. Returns true when the press
 *    was taken for an agent action, so the service does not also press play on the phone.
 *  - [nowPlaying] — what the music screen should show. With the mode on, it is the
 *    gateways' status lines instead of a track.
 *  - [start]/[stop] — follow the gateways' events while the service runs.
 *
 * Every word this sends to the wrist goes through [OutgoingNotifications], the same queue
 * phone notifications use, or through [nowPlaying], which the service sends as it always
 * has. Nothing here writes to the watch connection.
 */
class AgentBridge(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Called when the status lines change, so the service resends the music screen. */
    private val onStatusChanged: () -> Unit,
) {
    private val settings = AgentSettings(context)
    private val approvals = ApprovalNotifier(context)
    private val cursor = TaskCursor()
    private val debouncer = PressDebouncer()
    private val statusLines = ConcurrentHashMap<Int, String>()

    @Volatile private var prefs = AgentPrefs(enabled = false, gateways = emptyList())
    private var settingsJob: Job? = null
    private var pollJob: Job? = null

    private val active get() = prefs.enabled && prefs.gateways.isNotEmpty()

    fun start() {
        settingsJob?.cancel()
        settingsJob = scope.launch {
            settings.prefs.distinctUntilChanged().collect { latest ->
                prefs = latest
                pollJob?.cancel()
                statusLines.clear()
                cursor.reset()
                if (active) {
                    pollJob = scope.launch {
                        latest.gateways.forEach { gw -> launch { follow(gw) } }
                    }
                }
                onStatusChanged()
            }
        }
    }

    fun stop() {
        settingsJob?.cancel()
        pollJob?.cancel()
    }

    fun onMusicButton(button: MusicButton): Boolean {
        val action = AgentControl.actionFor(button, active) ?: return false
        // A double press is still a press we own: swallowing it keeps music from starting.
        if (debouncer.accept(action)) scope.launch { perform(action) }
        return true
    }

    /**
     * The music screen's contents. Paused, so the watch draws a play button to press, and
     * with the status lines as the two rows of text.
     */
    fun nowPlaying(real: NowPlaying): NowPlaying {
        if (!active) return real
        val (track, artist) = StatusLine.rows(prefs.gateways.map { statusLines[it.slot] ?: "${it.name}: …" })
        return real.copy(state = MusicPlaybackState.PAUSED, track = track, artist = artist)
    }

    // ---- presses -----------------------------------------------------------------------

    private suspend fun perform(action: AgentAction) {
        val gateways = prefs.gateways
        when (action) {
            AgentAction.GET_STATUS -> {
                cursor.reset()
                val key = UUID.randomUUID().toString()
                val replies = gateways.map { gw ->
                    scope.async { gw to GatewayClient(gw).command(action, "$key-${gw.slot}") }
                }.awaitAll()
                val lines = replies.map { (gw, reply) ->
                    when (reply) {
                        is Reply.Ok -> "${gw.name}: ${reply.value.body}".also { statusLines[gw.slot] = it }
                        is Reply.Failed -> "${gw.name}: ${reply.why}"
                    }
                }
                tell("Agents", lines.joinToString("\n"))
                onStatusChanged()
            }

            AgentAction.NEXT_TASK, AgentAction.PREVIOUS_TASK -> {
                val lists = gateways.map { gw -> scope.async { gw.slot to GatewayClient(gw).tasks() } }.awaitAll()
                cursor.update(lists.mapNotNull { (slot, r) -> (r as? Reply.Ok)?.let { slot to it.value } }.toMap())
                val stepped = if (action == AgentAction.NEXT_TASK) cursor.next() else cursor.previous()
                if (stepped == null) {
                    val offline = lists.count { it.second is Reply.Failed }
                    tell("Tasks", if (offline > 0) "No tasks ($offline gateway offline)" else "No tasks yet")
                } else {
                    val (card, position) = stepped
                    tell(card.title, "$position/${cursor.size()} ${card.body}")
                }
            }
        }
    }

    // ---- events -------------------------------------------------------------------------

    /**
     * Long-polls one gateway for as long as Agent Control Mode is on.
     *
     * The cursor is saved after every batch, so a restarted service picks up where it
     * left off instead of buzzing the wrist with everything again. A gateway seen for the
     * first time starts at "now".
     */
    private suspend fun follow(gw: GatewayConfig) {
        val client = GatewayClient(gw)
        var since = settings.lastSeq(gw.slot)
        var backoffMillis = 2_000L
        var needStatus = true

        while (scope.isActive) {
            if (needStatus) {
                when (val s = client.status()) {
                    is Reply.Ok -> {
                        statusLines[gw.slot] = s.value.statusLine
                        // A gateway whose history was wiped counts from zero again; waiting
                        // for it to pass our old number would mean never hearing from it.
                        if (since == null || s.value.lastSeq < since) {
                            since = s.value.lastSeq
                            settings.setLastSeq(gw.slot, since)
                        }
                        needStatus = false
                    }
                    is Reply.Failed -> statusLines[gw.slot] = "${gw.name}: ${s.why}"
                }
                onStatusChanged()
                if (needStatus) {
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                    continue
                }
            }

            when (val page = client.events(since ?: 0, waitSeconds = LONG_POLL_SECONDS)) {
                is Reply.Ok -> {
                    backoffMillis = 2_000L
                    for (event in page.value.events) handle(gw, event)
                    if (page.value.events.isNotEmpty()) {
                        since = page.value.lastSeq
                        settings.setLastSeq(gw.slot, page.value.lastSeq)
                        needStatus = page.value.events.any { EventPolicy.refreshesStatus(it.type) }
                    }
                }
                is Reply.Failed -> {
                    Log.i(TAG, "${gw.name}: events failed (${page.why})")
                    if (page.code == 401) {
                        statusLines[gw.slot] = "${gw.name}: token rejected"
                        onStatusChanged()
                        return // a revoked token will not start working by retrying
                    }
                    statusLines[gw.slot] = "${gw.name}: ${page.why}"
                    onStatusChanged()
                    needStatus = true
                    delay(backoffMillis)
                    backoffMillis = (backoffMillis * 2).coerceAtMost(MAX_BACKOFF_MILLIS)
                }
            }
        }
    }

    private fun handle(gw: GatewayConfig, event: GatewayEvent) {
        event.approval?.let { approvals.post(gw.slot, it) }
        if (EventPolicy.buzzes(event.type)) {
            OutgoingNotifications.offer(
                WatchNotification(
                    icon = NotificationIcon.UNKNOWN,
                    title = event.card.title,
                    body = event.card.body,
                    whenEpochSeconds = System.currentTimeMillis() / 1000,
                ),
            )
        }
    }

    /** A reply to a press: the wearer is looking at the watch, so it goes regardless. */
    private fun tell(title: String, body: String) {
        OutgoingNotifications.offer(
            WatchNotification(
                icon = NotificationIcon.UNKNOWN,
                title = title,
                body = body,
                whenEpochSeconds = System.currentTimeMillis() / 1000,
                answersWrist = true,
            ),
        )
    }

    private companion object {
        const val TAG = "AgentBridge"
        const val LONG_POLL_SECONDS = 25
        const val MAX_BACKOFF_MILLIS = 60_000L
    }
}
