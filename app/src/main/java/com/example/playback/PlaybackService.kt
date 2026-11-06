package com.example.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import com.example.data.Track

class PlaybackService : Service() {
    companion object {
        const val ACTION_START = "com.example.playback.action.START"
        const val ACTION_STOP = "com.example.playback.action.STOP"

        private var wakeLock: PowerManager.WakeLock? = null

        fun startService(context: Context, track: Track, isPlaying: Boolean) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_START
                putExtra("track_name", track.name)
                putExtra("track_artist", track.artist)
                putExtra("track_album", track.album)
                putExtra("track_duration", track.durationSec)
                putExtra("track_image", track.image)
                putExtra("is_playing", isPlaying)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error starting foreground service: ${e.message}")
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e("PlaybackService", "Error stopping foreground service: ${e.message}")
            }
        }
    }

    private lateinit var notificationManager: PlaybackNotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = PlaybackNotificationManager(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                notificationManager.cancelNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val name = intent.getStringExtra("track_name") ?: "Reproduciendo"
                val artist = intent.getStringExtra("track_artist") ?: ""
                val album = intent.getStringExtra("track_album") ?: ""
                val duration = intent.getIntExtra("track_duration", 0)
                val image = intent.getStringExtra("track_image")
                val isPlaying = intent.getBooleanExtra("is_playing", true)

                val track = Track(
                    id = "current",
                    name = name,
                    artist = artist,
                    album = album,
                    durationSec = duration,
                    image = image
                )

                val notification = notificationManager.buildNotification(track, isPlaying)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ServiceCompat.startForeground(
                            this,
                            PlaybackNotificationManager.NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                        )
                    } else {
                        startForeground(PlaybackNotificationManager.NOTIFICATION_ID, notification)
                    }
                } catch (e: Exception) {
                    Log.e("PlaybackService", "Failed to startForeground: ${e.message}")
                }
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "MMAC::PlaybackWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(2 * 60 * 60 * 1000L) // 2 hours max
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Error releasing WakeLock: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
