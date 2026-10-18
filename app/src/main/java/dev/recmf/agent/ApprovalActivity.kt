/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.recmf.ui.theme.ReCmfTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The only place an agent task that changes anything can be started from.
 *
 * Shows exactly what the gateway will run — machine, folder, prompt, permission mode,
 * tools, budget — and sends one decision. Not exported, and not shown over the lock screen.
 */
class ApprovalActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val slot = intent.getIntExtra(EXTRA_SLOT, -1)
        val request = intent.toRequest()
        if (slot < 0 || request == null) {
            finish()
            return
        }

        setContent {
            ReCmfTheme {
                Surface(Modifier.fillMaxSize()) {
                    ApprovalScreen(
                        request = request,
                        decide = { approve, done ->
                            lifecycleScope.launch {
                                val gw = AgentSettings(this@ApprovalActivity).current()
                                    .gateways.firstOrNull { it.slot == slot }
                                val reply = gw?.let { GatewayClient(it).decide(request, approve) }
                                ApprovalNotifier(this@ApprovalActivity).cancel(request.id)
                                done(
                                    when (reply) {
                                        null -> "That gateway is no longer set up on this phone."
                                        is Reply.Ok -> if (approve) "Approved. It's running." else "Denied. Nothing ran."
                                        is Reply.Failed -> when (reply.code) {
                                            409 -> "Already decided: ${reply.why}"
                                            410 -> "This request expired. Start it again."
                                            else -> "Not sent: ${reply.why}"
                                        }
                                    },
                                )
                            }
                        },
                        close = ::finish,
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_SLOT = "slot"
        private const val P = "approval."

        fun intent(context: Context, slot: Int, r: ApprovalRequest): Intent =
            Intent(context, ApprovalActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_SLOT, slot)
                .putExtra(P + "id", r.id)
                .putExtra(P + "nonce", r.nonce)
                .putExtra(P + "expires", r.expiresAtSeconds)
                .putExtra(P + "machine", r.machine)
                .putExtra(P + "label", r.label)
                .putExtra(P + "cwd", r.cwd)
                .putExtra(P + "prompt", r.prompt)
                .putExtra(P + "mode", r.permissionMode)
                .putExtra(P + "tools", r.tools.toTypedArray())
                .putExtra(P + "budget", r.budgetUsd ?: -1.0)
                .putExtra(P + "timeout", r.timeoutSeconds)

        private fun Intent.toRequest(): ApprovalRequest? {
            val id = getStringExtra(P + "id") ?: return null
            return ApprovalRequest(
                id = id,
                nonce = getStringExtra(P + "nonce") ?: return null,
                expiresAtSeconds = getDoubleExtra(P + "expires", 0.0),
                machine = getStringExtra(P + "machine").orEmpty(),
                label = getStringExtra(P + "label").orEmpty(),
                cwd = getStringExtra(P + "cwd").orEmpty(),
                prompt = getStringExtra(P + "prompt").orEmpty(),
                permissionMode = getStringExtra(P + "mode").orEmpty(),
                tools = getStringArrayExtra(P + "tools")?.toList().orEmpty(),
                budgetUsd = getDoubleExtra(P + "budget", -1.0).takeIf { it >= 0 },
                timeoutSeconds = getIntExtra(P + "timeout", 0),
            )
        }
    }
}

@Composable
private fun ApprovalScreen(
    request: ApprovalRequest,
    decide: (approve: Boolean, done: (String) -> Unit) -> Unit,
    close: () -> Unit,
) {
    var outcome by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var secondsLeft by remember {
        mutableLongStateOf(((request.expiresAtSeconds * 1000).toLong() - System.currentTimeMillis()) / 1000)
    }

    LaunchedEffect(request.id) {
        while (true) {
            secondsLeft = ((request.expiresAtSeconds * 1000).toLong() - System.currentTimeMillis()) / 1000
            if (secondsLeft <= 0) break
            delay(1_000)
        }
    }
    val expired = secondsLeft <= 0

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("${request.machine}: ${request.label}", style = MaterialTheme.typography.headlineSmall)
        Text(
            if (expired) "Expired" else "Expires in ${secondsLeft / 60}:${String.format(Locale.ROOT, "%02d", secondsLeft % 60)}",
            color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field("Folder", request.cwd, mono = true)
        Field("Prompt", request.prompt)
        Field("Permission mode", request.permissionMode, mono = true)
        Field("Tools", request.tools.joinToString("\n").ifBlank { "none" }, mono = true)
        Field(
            "Limits",
            listOfNotNull(
                request.budgetUsd?.let { String.format(Locale.ROOT, "up to $%.2f", it) },
                "stops after ${request.timeoutSeconds / 60} min",
            ).joinToString(" · "),
        )
        Spacer(Modifier.padding(4.dp))

        val result = outcome
        if (result != null) {
            Text(result, style = MaterialTheme.typography.titleMedium)
            Button(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { sending = true; decide(false) { outcome = it; sending = false } },
                    enabled = !sending && !expired,
                    modifier = Modifier.weight(1f),
                ) { Text("Deny") }
                Button(
                    onClick = { sending = true; decide(true) { outcome = it; sending = false } },
                    enabled = !sending && !expired,
                    modifier = Modifier.weight(1f),
                ) { Text("Approve") }
            }
            if (expired) {
                OutlinedButton(onClick = close, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            else MaterialTheme.typography.bodyLarge,
        )
    }
}
