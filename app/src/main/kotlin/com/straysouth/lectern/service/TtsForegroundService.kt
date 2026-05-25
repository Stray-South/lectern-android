package com.straysouth.lectern.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import com.straysouth.lectern.MainActivity

class TtsForegroundService : Service() {

    inner class LocalBinder : Binder() {
        fun setCallbacks(callbacks: TtsServiceCallbacks?) {
            this@TtsForegroundService.callbacks = callbacks
        }

        fun updateNowPlaying(bookTitle: String, chapter: String?, isPlaying: Boolean) {
            this@TtsForegroundService.updateNowPlaying(bookTitle, chapter, isPlaying)
        }
    }

    private val binder = LocalBinder()
    @Volatile private var callbacks: TtsServiceCallbacks? = null

    override fun onCreate() {
        super.onCreate()
        val notification = TtsNotificationBuilder.buildDefault(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> callbacks?.onPlayPause()
            ACTION_STOP -> callbacks?.onStop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        callbacks?.onTaskRemoved()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        callbacks = null
        super.onDestroy()
    }

    private fun updateNowPlaying(bookTitle: String, chapter: String?, isPlaying: Boolean) {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            Intent(this, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val playPauseIntent = PendingIntent.getService(
            this,
            REQ_PLAY_PAUSE,
            Intent(this, TtsForegroundService::class.java).setAction(ACTION_PLAY_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, TtsForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rich = TtsNotificationBuilder.buildRich(
            context = this,
            bookTitle = bookTitle,
            chapter = chapter,
            isPlaying = isPlaying,
            contentIntent = contentIntent,
            playPauseIntent = playPauseIntent,
            stopIntent = stopIntent,
        )
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, rich)
    }

    companion object {
        const val CHANNEL_ID = "lectern.tts"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.straysouth.lectern.action.TTS_PLAY_PAUSE"
        const val ACTION_STOP = "com.straysouth.lectern.action.TTS_STOP"
        private const val REQ_CONTENT = 100
        private const val REQ_PLAY_PAUSE = 101
        private const val REQ_STOP = 102
    }
}
