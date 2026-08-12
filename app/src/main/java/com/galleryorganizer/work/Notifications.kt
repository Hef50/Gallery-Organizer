package com.galleryorganizer.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.galleryorganizer.R

/**
 * The indexing progress notification.
 *
 * This is posted directly rather than through `setForeground`, which would drag in
 * `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_DATA_SYNC` permissions on Android 14+ for
 * work that is already fully resumable — being killed mid-pass costs one 500-row chunk.
 * See DECISIONS.md.
 */
class IndexNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_indexing),
            // LOW: this is ambient progress, it must never make a sound or peek.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_indexing_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun showProgress(scanned: Int, indeterminate: Boolean = true) {
        post(
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_index)
                .setContentTitle(context.getString(R.string.notification_indexing_title))
                .setContentText(
                    context.resources.getQuantityString(
                        R.plurals.notification_indexing_progress,
                        scanned,
                        scanned,
                    ),
                )
                // The total is unknown until the pass finishes — MediaStore will not give a
                // count without doing the query — so the bar stays indeterminate rather
                // than inventing a denominator that jumps around.
                .setProgress(0, 0, indeterminate)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build(),
        )
    }

    fun clear() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    private fun post(notification: Notification) {
        // POST_NOTIFICATIONS is optional: indexing must work perfectly well without it.
        // The check is inline rather than behind `canPost` so lint can see it, and the
        // catch covers the OEM builds that throw anyway after granting.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    companion object {
        const val CHANNEL_ID = "indexing"
        const val NOTIFICATION_ID = 1001
    }
}
