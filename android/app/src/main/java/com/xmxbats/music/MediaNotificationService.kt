package com.xmxbats.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import java.net.URL

/**
 * Service ini yang bikin notifikasi player.
 * Karena Service ini bagian dari proses app kita sendiri (bukan Chrome), sistem Android
 * otomatis pakai ic_notification (ikon app kita) di status bar & MediaStyle notification,
 * bukan ikon Chrome. Ini inti dari solusinya.
 */
class MediaNotificationService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.xmxbats.music.action.UPDATE"
        const val ACTION_PLAY_PAUSE = "com.xmxbats.music.action.PLAY_PAUSE"
        const val ACTION_NEXT = "com.xmxbats.music.action.NEXT"
        const val ACTION_PREV = "com.xmxbats.music.action.PREV"
        const val ACTION_STOP = "com.xmxbats.music.action.STOP"
        const val CHANNEL_ID = "xmxbats_playback"
        const val NOTIF_ID = 1001

        @Volatile private var artworkBitmap: Bitmap? = null
        @Volatile private var artworkUrlLoaded: String? = null

        fun resetArtwork() {
            artworkBitmap = null
            artworkUrlLoaded = null
        }
    }

    private lateinit var mediaSession: MediaSessionCompat

    override fun onCreate() {
        super.onCreate()
        createChannel()

        mediaSession = MediaSessionCompat(this, "XMXbatsMusicSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { MediaBridge.onNativeAction?.invoke("toggle", null) }
            override fun onPause() { MediaBridge.onNativeAction?.invoke("toggle", null) }
            override fun onSkipToNext() { MediaBridge.onNativeAction?.invoke("next", null) }
            override fun onSkipToPrevious() { MediaBridge.onNativeAction?.invoke("prev", null) }
            override fun onSeekTo(pos: Long) { MediaBridge.onNativeAction?.invoke("seek", pos / 1000.0) }
            override fun onStop() { MediaBridge.onNativeAction?.invoke("toggle", null) }
        })
        mediaSession.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> MediaBridge.onNativeAction?.invoke("toggle", null)
            ACTION_NEXT -> MediaBridge.onNativeAction?.invoke("next", null)
            ACTION_PREV -> MediaBridge.onNativeAction?.invoke("prev", null)
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateNotification()
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Pemutaran Musik", NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            channel.description = "Kontrol pemutaran lagu XM Xbats Music"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun updateNotification() {
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, MediaBridge.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, MediaBridge.artist)
                .putLong(
                    MediaMetadataCompat.METADATA_KEY_DURATION,
                    (MediaBridge.duration * 1000).toLong()
                )
                .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, artworkBitmap)
                .build()
        )

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(
                    if (MediaBridge.isPlaying) PlaybackStateCompat.STATE_PLAYING
                    else PlaybackStateCompat.STATE_PAUSED,
                    (MediaBridge.position * 1000).toLong(),
                    1f
                )
                .build()
        )

        val playPauseIcon = if (MediaBridge.isPlaying)
            android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(MediaBridge.title)
            .setContentText(MediaBridge.artist)
            .setLargeIcon(artworkBitmap)
            .addAction(android.R.drawable.ic_media_previous, "Previous", servicePendingIntent(ACTION_PREV))
            .addAction(playPauseIcon, "Play/Pause", servicePendingIntent(ACTION_PLAY_PAUSE))
            .addAction(android.R.drawable.ic_media_next, "Next", servicePendingIntent(ACTION_NEXT))
            .setStyle(
                MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .setOngoing(MediaBridge.isPlaying)
            .setOnlyAlertOnce(true)
            .setContentIntent(activityPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIF_ID, notification)

        val url = MediaBridge.artwork
        if (url.isNotBlank() && url != artworkUrlLoaded) {
            loadArtworkAsync(url)
        }
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MediaNotificationService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun activityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun loadArtworkAsync(url: String) {
        artworkUrlLoaded = url
        Thread {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                artworkBitmap = bmp
                Handler(Looper.getMainLooper()).post { updateNotification() }
            } catch (_: Exception) {
                // gagal load artwork -> biarin tanpa cover, ga fatal
            }
        }.start()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        mediaSession.release()
        super.onDestroy()
    }
}
