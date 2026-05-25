package com.straysouth.lectern.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.straysouth.lectern.R

internal object TtsNotificationBuilder {

    fun buildDefault(context: Context): Notification =
        NotificationCompat.Builder(context, TtsForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tts_notification_default_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

    @Suppress("LongParameterList")
    fun buildRich(
        context: Context,
        bookTitle: String,
        chapter: String?,
        isPlaying: Boolean,
        contentIntent: PendingIntent,
        playPauseIntent: PendingIntent,
        stopIntent: PendingIntent,
    ): Notification {
        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseLabel = context.getString(
            if (isPlaying) R.string.tts_notification_action_pause else R.string.tts_notification_action_play,
        )
        return NotificationCompat.Builder(context, TtsForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(bookTitle)
            .setContentText(chapter ?: context.getString(R.string.tts_notification_default_title))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                playPauseIcon,
                playPauseLabel,
                playPauseIntent,
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.tts_notification_action_stop),
                stopIntent,
            )
            .build()
    }
}
