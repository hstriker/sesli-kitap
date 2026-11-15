package com.seslikitap.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.seslikitap.MainActivity
import com.seslikitap.R
import com.seslikitap.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Seslendirme sürerken uygulamayı ön planda tutar; bildirim, kilit ekranı ve kulaklık
 * tuşlarını oynatıcıya bağlar.
 */
class PlaybackService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player get() = container.player
    private lateinit var session: MediaSessionCompat
    private var cover: Bitmap? = null
    private var coverPath: String? = null
    private var noisyRegistered = false
    private var foreground = false

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) player.pause()
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        session = MediaSessionCompat(this, "SesliKitap").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = player.play()
                override fun onPause() = player.pause()
                override fun onStop() = player.stop()
                override fun onSkipToNext() = player.nextSentence()
                override fun onSkipToPrevious() = player.previousSentence()
                override fun onFastForward() = player.nextSentence()
                override fun onRewind() = player.previousSentence()
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        scope.launch {
            player.state.collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService'ten sonra 5 sn içinde startForeground çağrılmalı.
        update(player.state.value)
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.nextSentence()
            ACTION_PREV -> player.previousSentence()
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Uygulama son kullanılanlardan kaydırılınca: çalmıyorsa kapat.
        if (!player.state.value.playing) player.stop()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        unregisterNoisy()
        session.isActive = false
        session.release()
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------

    private fun update(st: PlayerState) {
        if (!st.active) {
            unregisterNoisy()
            if (foreground) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                foreground = false
            }
            stopSelf()
            return
        }
        if (st.playing) registerNoisy() else unregisterNoisy()

        if (st.coverPath != coverPath) {
            coverPath = st.coverPath
            cover = null
            val path = st.coverPath
            if (path != null) {
                scope.launch {
                    cover = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
                    update(player.state.value)
                }
            }
        }

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, st.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, st.author)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, st.chapterTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, st.bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, st.chapterTitle)
                .apply { cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build()
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_FAST_FORWARD or PlaybackStateCompat.ACTION_REWIND
                )
                .setState(
                    if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (st.playing) 1f else 0f,
                )
                .build()
        )

        val notification = buildNotification(st)
        try {
            if (!foreground) {
                ServiceCompat.startForeground(
                    this, NOTIFICATION_ID, notification,
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
                )
                foreground = true
            } else {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ön plana alınamadı", e)
        }
    }

    private fun buildNotification(st: PlayerState): Notification {
        val chapterInfo = if (st.chapterCount > 0) " (${st.chapterIndex + 1}/${st.chapterCount})" else ""
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(st.bookTitle)
            .setContentText(st.chapterTitle + chapterInfo)
            .setSubText(sleepText(st))
            .setLargeIcon(cover)
            .setContentIntent(contentIntent())
            .setDeleteIntent(action(ACTION_STOP, 4))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(st.playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .addAction(android.R.drawable.ic_media_previous, "Önceki cümle", action(ACTION_PREV, 1))
            .addAction(
                if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.playing) "Duraklat" else "Oynat",
                action(ACTION_TOGGLE, 2),
            )
            .addAction(android.R.drawable.ic_media_next, "Sonraki cümle", action(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Kapat", action(ACTION_STOP, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun sleepText(st: PlayerState): String? = when {
        st.sleepAtChapterEnd -> "Bölüm sonunda duracak"
        st.sleepAt != null -> {
            val min = ((st.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0) + 1
            "$min dk sonra duracak"
        }
        else -> null
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun action(name: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, PlaybackService::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Seslendirme", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Okunan kitap ve oynatma kontrolleri"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            this, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try { unregisterReceiver(noisyReceiver) } catch (_: Exception) {}
        noisyRegistered = false
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_TOGGLE = "com.seslikitap.TOGGLE"
        private const val ACTION_NEXT = "com.seslikitap.NEXT"
        private const val ACTION_PREV = "com.seslikitap.PREV"
        private const val ACTION_STOP = "com.seslikitap.STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            if (running) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Servis başlatılamadı", e)
            }
        }
    }
}
