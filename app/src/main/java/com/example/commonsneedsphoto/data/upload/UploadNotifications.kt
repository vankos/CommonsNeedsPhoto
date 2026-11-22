package com.example.commonsneedsphoto.data.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.example.commonsneedsphoto.MainActivity
import com.example.commonsneedsphoto.R

/**
 * Foreground and result notifications for [UploadWorker].
 *
 * Progress notifications use a low-importance channel and only alert once, so a long
 * upload does not beep on every chunk. Result notifications are separate and dismissible.
 */
class UploadNotifications(context: Context) {

    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.upload_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.upload_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun foregroundInfo(progress: Int, placeLabel: String): ForegroundInfo {
        ensureChannel()
        val notification = progressNotification(
            progress = progress,
            placeLabel = placeLabel,
            text = context.getString(R.string.upload_notification_progress, progress),
            indeterminate = false,
        )
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    fun updateProgress(progress: Int, placeLabel: String, phase: String) {
        val text = when (phase) {
            UploadWork.PHASE_PUBLISH -> context.getString(R.string.upload_notification_publishing)
            UploadWork.PHASE_WIKIDATA -> context.getString(R.string.upload_notification_wikidata)
            else -> context.getString(R.string.upload_notification_progress, progress)
        }
        post(
            FOREGROUND_NOTIFICATION_ID,
            progressNotification(
                progress = progress,
                placeLabel = placeLabel,
                text = text,
                indeterminate = false,
            ),
        )
    }

    fun updateWaiting(placeLabel: String) {
        post(
            FOREGROUND_NOTIFICATION_ID,
            progressNotification(
                progress = 0,
                placeLabel = placeLabel,
                text = context.getString(R.string.upload_notification_waiting),
                indeterminate = true,
            ),
        )
    }

    fun showResult(
        success: Boolean,
        fileName: String,
        p18Failed: Boolean = false,
        sessionExpired: Boolean = false,
        error: String? = null,
    ) {
        val text = when {
            success && p18Failed -> context.getString(
                R.string.upload_notification_success_p18_failed,
                fileName,
            )
            success -> context.getString(R.string.upload_notification_success_text, fileName)
            sessionExpired -> context.getString(R.string.upload_notification_session_expired)
            !error.isNullOrBlank() -> context.getString(R.string.upload_notification_failed_text, error)
            else -> context.getString(R.string.upload_failed_unknown)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(
                context.getString(
                    if (success) {
                        R.string.upload_notification_success_title
                    } else {
                        R.string.upload_notification_failed_title
                    },
                ),
            )
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentIntent())
            .build()
        post(RESULT_NOTIFICATION_ID, notification)
    }

    private fun progressNotification(
        progress: Int,
        placeLabel: String,
        text: String,
        indeterminate: Boolean,
    ): Notification {
        val title = placeLabel.ifBlank {
            context.getString(R.string.upload_notification_generic_title)
        }
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentTitle(context.getString(R.string.upload_notification_title, title))
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(PROGRESS_MAX, progress.coerceIn(0, PROGRESS_MAX), indeterminate)
            .setContentIntent(contentIntent())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(id: Int, notification: Notification) {
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }

    companion object {
        const val CHANNEL_ID = "commons_photo_uploads"
        const val FOREGROUND_NOTIFICATION_ID = 0x1000
        const val RESULT_NOTIFICATION_ID = 0x1001
        private const val PROGRESS_MAX = 100
    }
}
