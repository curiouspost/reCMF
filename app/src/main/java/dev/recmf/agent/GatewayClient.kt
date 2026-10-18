/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A notification the gateway has already cut to the watch's 20 + 128 bytes. */
data class WatchCard(val title: String, val body: String)

data class GatewayStatus(val statusLine: String, val card: WatchCard, val lastSeq: Long)

/** Everything the phone's confirmation screen must show before anything runs. */
data class ApprovalRequest(
    val id: String,
    val nonce: String,
    val expiresAtSeconds: Double,
    val machine: String,
    val label: String,
    val cwd: String,
    val prompt: String,
    val permissionMode: String,
    val tools: List<String>,
    val budgetUsd: Double?,
    val timeoutSeconds: Int,
)

data class PresetInfo(val id: String, val label: String, val requiresApproval: Boolean)

data class GatewayEvent(
    val seq: Long,
    val type: String,
    val card: WatchCard,
    val approval: ApprovalRequest?,
)

data class EventPage(val events: List<GatewayEvent>, val lastSeq: Long)

sealed interface Reply<out T> {
    data class Ok<T>(val value: T) : Reply<T>

    /** [code] is the HTTP status, or null when the gateway was not reached at all. */
    data class Failed(val why: String, val code: Int? = null) : Reply<Nothing>
}

/**
 * Talks to one agent gateway over HTTPS.
 *
 * Plain [HttpURLConnection] and the platform's `org.json`, the same as reCMF's assistant
 * client, so the feature adds no dependencies. The token is sent only in the header and
 * never logged.
 */
class GatewayClient(private val gateway: GatewayConfig) {

    suspend fun status(): Reply<GatewayStatus> = get("/v1/status") { o ->
        GatewayStatus(
            statusLine = o.getString("status_line"),
            card = card(o.getJSONObject("watch")),
            lastSeq = o.optLong("last_seq", 0),
        )
    }

    suspend fun tasks(limit: Int = 20): Reply<List<TaskCard>> = get("/v1/tasks?limit=$limit") { o ->
        val arr = o.getJSONArray("tasks")
        (0 until arr.length()).map { i ->
            val t = arr.getJSONObject(i)
            val c = card(t.getJSONObject("watch"))
            TaskCard(t.getString("id"), gateway.slot, t.getDouble("created_at"), c.title, c.body)
        }
    }

    suspend fun events(since: Long, waitSeconds: Int): Reply<EventPage> =
        get("/v1/events?since=$since&wait=$waitSeconds", readTimeoutMillis = (waitSeconds + 15) * 1000) { o ->
            val arr = o.getJSONArray("events")
            EventPage(
                events = (0 until arr.length()).map { event(arr.getJSONObject(it)) },
                lastSeq = o.getLong("last_seq"),
            )
        }

    /** A read-only action from the watch. The key makes a retried press run once. */
    suspend fun command(action: AgentAction, idempotencyKey: String): Reply<WatchCard> =
        post(
            "/v1/commands",
            JSONObject().put("action", action.wire).put("source", "watch"),
            idempotencyKey,
        ) { card(it.getJSONObject("watch")) }

    /** The presets this gateway allows, for the phone's Run buttons. */
    suspend fun presets(): Reply<List<PresetInfo>> = get("/v1/agents") { o ->
        val arr = o.getJSONArray("presets")
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            PresetInfo(p.getString("id"), p.getString("label"), p.getBoolean("requires_approval"))
        }
    }

    /**
     * Starts a preset from the phone. Presets that change anything come back as
     * "approve on your phone" and only run after [decide].
     */
    suspend fun runPreset(presetId: String, idempotencyKey: String): Reply<WatchCard> =
        post(
            "/v1/commands",
            JSONObject().put("action", "RUN_PRESET").put("preset", presetId).put("source", "phone"),
            idempotencyKey,
        ) { card(it.getJSONObject("watch")) }

    suspend fun decide(approval: ApprovalRequest, approve: Boolean): Reply<String> =
        post(
            "/v1/approvals/" + URLEncoder.encode(approval.id, "UTF-8"),
            JSONObject().put("decision", if (approve) "approve" else "deny").put("nonce", approval.nonce),
            // One decision per approval, however many times the button is tapped.
            idempotencyKey = "decide-${approval.id}",
        ) { it.getJSONObject("task").getString("status") }

    private fun card(o: JSONObject) = WatchCard(o.getString("title"), o.getString("body"))

    private fun event(o: JSONObject): GatewayEvent {
        val type = o.getString("type")
        val approval = if (EventPolicy.isApprovalRequest(type)) {
            val d = o.getJSONObject("details")
            val tools = d.optJSONArray("allowed_tools") ?: JSONArray()
            ApprovalRequest(
                id = o.getString("approval_id"),
                nonce = o.getString("nonce"),
                expiresAtSeconds = o.getDouble("expires_at"),
                machine = o.optString("machine", gateway.name),
                label = d.getString("label"),
                cwd = d.getString("cwd"),
                prompt = d.getString("prompt"),
                permissionMode = d.getString("permission_mode"),
                tools = (0 until tools.length()).map { tools.getString(it) },
                budgetUsd = if (d.isNull("max_budget_usd")) null else d.getDouble("max_budget_usd"),
                timeoutSeconds = d.getInt("timeout_seconds"),
            )
        } else {
            null
        }
        return GatewayEvent(o.getLong("seq"), type, card(o.getJSONObject("watch")), approval)
    }

    private suspend fun <T> get(
        path: String,
        readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
        parse: (JSONObject) -> T,
    ): Reply<T> = call("GET", path, null, null, readTimeoutMillis, parse)

    private suspend fun <T> post(
        path: String,
        body: JSONObject,
        idempotencyKey: String,
        parse: (JSONObject) -> T,
    ): Reply<T> = call("POST", path, body, idempotencyKey, READ_TIMEOUT_MILLIS, parse)

    private suspend fun <T> call(
        method: String,
        path: String,
        body: JSONObject?,
        idempotencyKey: String?,
        readTimeoutMillis: Int,
        parse: (JSONObject) -> T,
    ): Reply<T> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(gateway.url + path).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = readTimeoutMillis
                requestMethod = method
                instanceFollowRedirects = false // never carry the token to another host
                setRequestProperty("Authorization", "Bearer ${gateway.token}")
                setRequestProperty("Accept", "application/json")
                if (idempotencyKey != null) setRequestProperty("Idempotency-Key", idempotencyKey)
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.let { runCatching { JSONObject(it).optString("detail") }.getOrNull() }
                    ?.takeIf { it.isNotBlank() }
                return@withContext Reply.Failed(detail ?: "HTTP $code", code)
            }
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            Reply.Ok(parse(JSONObject(text)))
        } catch (e: IOException) {
            Log.i(TAG, "${gateway.name} unreachable: ${e.javaClass.simpleName}")
            Reply.Failed("unreachable")
        } catch (e: JSONException) {
            Log.w(TAG, "${gateway.name} sent something unreadable for $path")
            Reply.Failed("unreadable reply")
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val TAG = "GatewayClient"
        const val CONNECT_TIMEOUT_MILLIS = 10_000
        const val READ_TIMEOUT_MILLIS = 20_000
    }
}
