package com.smartcart.app.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.smartcart.app.MainActivity
import com.smartcart.app.R
import com.smartcart.model.CartTask
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** User notification preferences (S-SET-05). Kept locally in Phase 1. */
class NotificationPrefs {
    data class Values(
        val cartReady: Boolean = true,
        val needsUserAction: Boolean = true,
        val importComplete: Boolean = true,
        val monthlySummary: Boolean = true,
        val lockScreenPrivate: Boolean = true,
    )

    val values = MutableStateFlow(Values())
    fun update(f: (Values) -> Values) = values.update(f)
}

/**
 * Local notifications (contracts/schemas/notification-payload.schema.json). Lock-screen content is
 * generic when lock-screen privacy is on; detail text never includes items, addresses or prices.
 */
class Notifier(private val context: Context, private val prefs: NotificationPrefs) {

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            Triple(CH_TASKS, R.string.channel_tasks, R.string.channel_tasks_desc),
            Triple(CH_IMPORTS, R.string.channel_imports, R.string.channel_imports_desc),
            Triple(CH_SUMMARIES, R.string.channel_summaries, R.string.channel_summaries_desc),
        ).forEach { (id, name, desc) ->
            nm.createNotificationChannel(
                NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(desc)
                    lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                },
            )
        }
    }

    fun cartReady(task: CartTask) {
        if (!prefs.values.value.cartReady) return
        post(
            id = task.taskId.hashCode(),
            channel = CH_TASKS,
            genericTitle = "Your cart is ready",
            genericBody = "Tap to review and pay in the app.",
            detailTitle = "${task.provider.displayName} cart is ready",
            detailBody = "Review it and pay in ${task.provider.displayName}. SmartCart never pays for you.",
            uri = "smartcart://task/${task.taskId}",
        )
    }

    fun needsUserAction(task: CartTask) {
        if (!prefs.values.value.needsUserAction) return
        post(
            id = task.taskId.hashCode() + 1,
            channel = CH_TASKS,
            genericTitle = "A task needs you",
            genericBody = "Tap to continue.",
            detailTitle = "${task.provider.displayName} needs you",
            detailBody = task.needsUserAction?.reason?.title ?: "Open the app to continue.",
            uri = "smartcart://task/${task.taskId}",
        )
    }

    fun importComplete(sessionId: String) {
        if (!prefs.values.value.importComplete) return
        post(sessionId.hashCode(), CH_IMPORTS, "Order history imported", "Tap to review.", null, null, "smartcart://history/import/$sessionId")
    }

    private fun post(id: Int, channel: String, genericTitle: String, genericBody: String, detailTitle: String?, detailBody: String?, uri: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(genericTitle)
            .setContentText(genericBody)
            .build()
        val private = prefs.values.value.lockScreenPrivate
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(detailTitle ?: genericTitle)
            .setContentText(detailBody ?: genericBody)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setVisibility(if (private) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_PUBLIC)
            .setPublicVersion(public)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
    }

    companion object {
        const val CH_TASKS = "tasks"
        const val CH_IMPORTS = "imports"
        const val CH_SUMMARIES = "summaries"
    }
}
