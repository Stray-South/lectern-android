package com.straysouth.lectern.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.straysouth.lectern.R

internal object TtsNotificationBuilder {

    // ADR-AND-W §Threat model — notification content
    // The notification shade is screenshot-capturable independently from the app
    // (ADR-AND-R reconsideration trigger 5) AND it renders on the lockscreen.
    // The lockscreen surface has a wider attacker model than the library screen
    // (anyone with physical access, no auth required); "exposure proportionate to
    // library screen" applies on the unlocked notification shade only. On the
    // lockscreen we redact title/chapter and show only "<app name> · Reading".
    //
    // Implementation: every notification we post sets VISIBILITY_PRIVATE so the
    // system substitutes the public (redacted) version when the device is locked.
    // The rich notification provides its own public version via setPublicVersion;
    // the default notification is already redacted-equivalent (app name + "Reading")
    // so it serves as its own public version on lockscreen.

    fun buildDefault(context: Context): Notification =
        NotificationCompat.Builder(context, TtsForegroundService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.tts_notification_default_title))
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()

    /**
     * Lockscreen-safe public version of the rich notification. Shows only the
     * app name and the generic "Reading" label — no book title, no chapter.
     * Attached via [NotificationCompat.Builder.setPublicVersion] on the rich
     * notification; the system renders this in place of the private one when
     * the device is locked.
     */
    private fun buildPublicRedacted(context: Context): Notification =
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
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(buildPublicRedacted(context))
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
