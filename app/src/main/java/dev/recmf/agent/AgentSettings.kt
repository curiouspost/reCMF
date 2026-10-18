/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.recmf.data.SecretVault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** One machine running the agent gateway. */
data class GatewayConfig(
    val slot: Int,
    val name: String,
    val url: String,
    val token: String,
)

data class AgentPrefs(
    /** Agent Control Mode. Off by default: until it is on, the music screen is music. */
    val enabled: Boolean,
    val gateways: List<GatewayConfig>,
)

/**
 * Kept in a store of its own rather than added to reCMF's settings, so the agent feature
 * can be taken out by deleting one package and nothing about the watch settings moves.
 */
private val Context.agentStore by preferencesDataStore(name = "recmf-agent")

class AgentSettings(private val context: Context) {

    val prefs: Flow<AgentPrefs> = context.agentStore.data.map(::read)

    suspend fun current(): AgentPrefs = prefs.first()

    private fun read(p: Preferences): AgentPrefs = AgentPrefs(
        enabled = p[KEY_ENABLED] ?: false,
        gateways = SLOTS.mapNotNull { slot ->
            val url = p[url(slot)] ?: return@mapNotNull null
            // A token the Keystore can no longer open (a restore onto a new phone) reads
            // as no gateway, and the settings screen asks for it again.
            val token = p[token(slot)]?.let(SecretVault::unseal)?.decodeToString()
                ?: return@mapNotNull null
            GatewayConfig(slot, p[name(slot)] ?: "gw${slot + 1}", url, token)
        },
    )

    suspend fun setEnabled(enabled: Boolean) {
        context.agentStore.edit { it[KEY_ENABLED] = enabled }
    }

    /**
     * Saves a gateway. A blank [newToken] keeps the token already stored.
     *
     * @return null when saved, or what was wrong in words for the screen.
     */
    suspend fun setGateway(slot: Int, name: String, rawUrl: String, newToken: String?): String? {
        require(slot in SLOTS)
        val url = GatewayUrl.normalize(rawUrl) ?: return "Use the https:// address Tailscale gives the machine"
        val label = name.trim().take(12).ifBlank { "gw${slot + 1}" }
        val sealed = newToken?.trim()?.takeIf { it.isNotEmpty() }?.let {
            SecretVault.seal(it.toByteArray()) ?: return "The phone's keystore refused to store the token"
        }
        var missingToken = false
        context.agentStore.edit { p ->
            if (sealed == null && p[token(slot)] == null) {
                missingToken = true
                return@edit
            }
            if (p[url(slot)] != url) p.remove(seq(slot)) // a different machine: start fresh
            p[name(slot)] = label
            p[url(slot)] = url
            if (sealed != null) p[token(slot)] = sealed
        }
        return if (missingToken) "Paste the token from `add-device`" else null
    }

    suspend fun clearGateway(slot: Int) {
        context.agentStore.edit { p ->
            p.remove(name(slot)); p.remove(url(slot)); p.remove(token(slot)); p.remove(seq(slot))
        }
    }

    suspend fun lastSeq(slot: Int): Long? = context.agentStore.data.first()[seq(slot)]

    suspend fun setLastSeq(slot: Int, value: Long) {
        context.agentStore.edit { it[seq(slot)] = value }
    }

    companion object {
        /** Two machines: the server and the Mac. */
        val SLOTS = 0..1

        private val KEY_ENABLED = booleanPreferencesKey("agent_mode")
        private fun name(slot: Int) = stringPreferencesKey("gw${slot}_name")
        private fun url(slot: Int) = stringPreferencesKey("gw${slot}_url")
        private fun token(slot: Int) = stringPreferencesKey("gw${slot}_token_sealed")
        private fun seq(slot: Int) = longPreferencesKey("gw${slot}_seq")
    }
}
