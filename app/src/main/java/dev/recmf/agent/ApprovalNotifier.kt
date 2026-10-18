/*
 * reCMF — Copyright (C) 2026 reCMF contributors. AGPL-3.0-or-later.
 */
package dev.recmf.agent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import dev.recmf.R

/**
 * Puts an approval request on the phone, where it can be read in full.
 *
 * The watch only ever says "approve on your phone". Approving is a decision about what
 * runs on a computer, and that deserves the whole prompt, the folder and the tools on a
 * screen — which also means an unlocked phone, since the confirmation screen does not
 * show over the lock screen.
 */
class ApprovalNotifier(private val context: Context) {

    private val manager: NotificationManager? = context.getSystemService()

    fun post(slot: Int, request: ApprovalRequest) {
        val manager = manager ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A task on one of your machines is waiting for your OK"
            },
        )

        val open = PendingIntent.getActivity(
            context,
            request.id.hashCode(),
            ApprovalActivity.intent(context, slot, request),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_watch)
            .setContentTitle("${request.machine}: ${request.label}")
            .setContentText("Tap to review and approve or deny")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // Contents hidden on the lock screen; the prompt can name private paths.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(((request.expiresAtSeconds * 1000).toLong() - System.currentTimeMillis()).coerceAtLeast(1_000))
            .build()

        manager.notify(request.id.hashCode(), notification)
    }

    fun cancel(approvalId: String) {
        manager?.cancel(approvalId.hashCode())
    }

    private companion object {
        const val CHANNEL_ID = "agent-approvals"
    }
}
