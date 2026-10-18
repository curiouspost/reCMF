/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.recmf.ui.theme.ReCmfTheme
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Where Agent Control Mode is switched on and the gateways are entered.
 *
 * Its own launcher entry ("reCMF Agents") so the feature needs no changes to reCMF's
 * home screen, and can be removed by deleting this package and two manifest entries.
 */
class AgentSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = AgentSettings(applicationContext)
        setContent {
            ReCmfTheme {
                Surface(Modifier.fillMaxSize()) { AgentSettingsScreen(settings) }
            }
        }
    }
}

@Composable
private fun AgentSettingsScreen(settings: AgentSettings) {
    val prefs by settings.prefs.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val current = prefs ?: return

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Agent Control Mode", style = MaterialTheme.typography.headlineSmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use the watch's music screen for agents")
                Text(
                    if (current.enabled) "Play: status · Next/Previous: browse tasks · Volume: still volume"
                    else "Off: the music screen controls music",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = current.enabled,
                onCheckedChange = { on -> scope.launch { settings.setEnabled(on) } },
                enabled = current.gateways.isNotEmpty() || current.enabled,
            )
        }

        AgentSettings.SLOTS.forEach { slot ->
            GatewayCard(slot, current.gateways.firstOrNull { it.slot == slot }, settings)
        }

        Text(
            "Safety: this mode only shows notifications and changes the music screen's text, " +
                "the same things reCMF already does. It never sends firmware, watch faces or " +
                "settings to the watch. Watch presses can only read status; anything that " +
                "changes files needs an Approve on this phone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GatewayCard(slot: Int, saved: GatewayConfig?, settings: AgentSettings) {
    val scope = rememberCoroutineScope()
    var name by remember(saved) { mutableStateOf(saved?.name ?: if (slot == 0) "server" else "mac") }
    var url by remember(saved) { mutableStateOf(saved?.url.orEmpty()) }
    var token by remember(saved) { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var presets by remember { mutableStateOf<List<PresetInfo>>(emptyList()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Gateway ${slot + 1}", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("Short name (shown on watch)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("https://machine.your-tailnet.ts.net") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it },
                label = { Text(if (saved != null) "Token (saved; paste to replace)" else "Token from add-device") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        message = settings.setGateway(slot, name, url, token) ?: "Saved"
                        token = ""
                    }
                }) { Text("Save") }
                OutlinedButton(enabled = saved != null, onClick = {
                    val gw = saved ?: return@OutlinedButton
                    message = "Testing…"
                    scope.launch {
                        val client = GatewayClient(gw)
                        message = when (val r = client.status()) {
                            is Reply.Ok -> "Connected: ${r.value.statusLine}"
                            is Reply.Failed -> "Failed: ${r.why}"
                        }
                        presets = when (val listed = client.presets()) {
                            is Reply.Ok -> listed.value
                            is Reply.Failed -> emptyList()
                        }
                    }
                }) { Text("Test") }
                OutlinedButton(enabled = saved != null, onClick = {
                    scope.launch { settings.clearGateway(slot); message = "Removed" }
                }) { Text("Remove") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            // Shown after Test. Presets that change files ask for approval before running.
            presets.forEach { preset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.label)
                        Text(
                            if (preset.requiresApproval) "Asks for approval first" else "Runs now",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        val gw = saved ?: return@OutlinedButton
                        scope.launch {
                            message = when (val r = GatewayClient(gw).runPreset(preset.id, UUID.randomUUID().toString())) {
                                is Reply.Ok -> "${preset.label}: ${r.value.body}"
                                is Reply.Failed -> "${preset.label}: ${r.why}"
                            }
                        }
                    }) { Text("Run") }
                }
            }
        }
    }
}
