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
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import androidx.media.MediaBrowserServiceCompat
import android.os.Bundle
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
class PlaybackService : MediaBrowserServiceCompat() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player get() = container.player
    private lateinit var session: MediaSessionCompat
    private var cover: Bitmap? = null
    private var coverPath: String? = null
    private var noisyRegistered = false
    private var foreground = false
    private var lastWidget = ""
    private val container get() = applicationContext.container

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
                // Kulaklık: çift basış = 30 sn ileri, üçlü basış = 30 sn geri
                override fun onSkipToNext() = player.skipSeconds(SKIP_SECONDS)
                override fun onSkipToPrevious() = player.skipSeconds(-SKIP_SECONDS)
                override fun onFastForward() = player.skipSeconds(SKIP_SECONDS)
                override fun onRewind() = player.skipSeconds(-SKIP_SECONDS)
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    when (action) {
                        CUSTOM_NEXT_CHAPTER -> player.nextChapter()
                        CUSTOM_PREV_CHAPTER -> player.previousChapter()
                    }
                }

                // Android Auto: listeden seçilen kitap
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    mediaId?.removePrefix("book:")?.toLongOrNull()?.let { player.open(it, autoPlay = true) }
                }

                // "Hey Google, Sesli Kitap'ta Dune'u çal"
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    scope.launch {
                        val q = com.seslikitap.core.TurkishText.searchKey(query.orEmpty())
                        val books = withContext(Dispatchers.IO) { container.db.books().all() }
                        val hit = if (q.isBlank()) null else books.firstOrNull { it.searchKey.contains(q) }
                        if (hit != null) player.open(hit.id, autoPlay = true) else player.resumeLast()
                    }
                }
            })
            setSessionActivity(contentIntent())
            isActive = true
        }
        sessionToken = session.sessionToken
        scope.launch {
            player.state.collect { update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        started = true
        // Uygulama kapalıyken widget'tan "oynat": son kitabı kaldığı yerden aç.
        if (!player.state.value.active && intent?.action == ACTION_TOGGLE) {
            startForegroundPlaceholder()
            scope.launch {
                if (!player.resumeLast()) {
                    ServiceCompat.stopForeground(this@PlaybackService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    foreground = false
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }
        // startForegroundService'ten sonra 5 sn içinde startForeground çağrılmalı.
        update(player.state.value)
        when (intent?.action) {
            ACTION_TOGGLE -> player.toggle()
            ACTION_NEXT -> player.skipSeconds(SKIP_SECONDS)
            ACTION_PREV -> player.skipSeconds(-SKIP_SECONDS)
            ACTION_STOP -> player.stop()
        }
        return START_NOT_STICKY
    }

    private var started = false

    private fun startForegroundPlaceholder() {
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("Sesli Kitap")
            .setContentText("Kaldığın yerden açılıyor…")
            .setSilent(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, n,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
            foreground = true
        } catch (e: Exception) {
            Log.w(TAG, "Ön plana alınamadı", e)
        }
    }

    // ------------------------------------------------------------------ Android Auto: kitap listeleri

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT, null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        scope.launch {
            val items = try { withContext(Dispatchers.IO) { browseItems(parentId) } } catch (e: Exception) { mutableListOf() }
            result.sendResult(items)
        }
    }

    private suspend fun browseItems(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val books = container.db.books().all().filter { it.status == com.seslikitap.data.BookStatus.OK }
        fun folder(id: String, title: String) = MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).build(),
            MediaBrowserCompat.MediaItem.FLAG_BROWSABLE,
        )
        fun book(b: com.seslikitap.data.BookEntity) = MediaBrowserCompat.MediaItem(
            MediaDescriptionCompat.Builder()
                .setMediaId("book:${b.id}")
                .setTitle(b.title)
                .setSubtitle(b.author.ifBlank { b.relativePath.trimEnd('/') })
                .setIconBitmap(b.coverPath?.let { p -> runCatching { BitmapFactory.decodeFile(p, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull() })
                .build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE,
        )
        return when (parentId) {
            ROOT -> mutableListOf(
                folder("devam", "Kaldığın yerden devam"),
                folder("yarim", "Yarım kalanlar"),
                folder("favori", "Favoriler"),
                folder("yeni", "Son eklenenler"),
            )
            "devam" -> books.filter { it.lastOpenedAt != null && !it.finished }.sortedByDescending { it.lastOpenedAt }.take(1).map(::book).toMutableList()
            "yarim" -> books.filter { it.progress > 0f && !it.finished }.sortedByDescending { it.lastOpenedAt ?: 0L }.take(30).map(::book).toMutableList()
            "favori" -> books.filter { it.favorite }.take(50).map(::book).toMutableList()
            "yeni" -> books.sortedByDescending { it.addedAt }.take(30).map(::book).toMutableList()
            else -> mutableListOf()
        }
    }

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

        val widgetKey = "${st.bookTitle}|${st.chapterTitle}|${st.coverPath}|${st.playing}"
        if (widgetKey != lastWidget) {
            lastWidget = widgetKey
            PlayerWidget.update(this, st.bookTitle, st.chapterTitle, st.coverPath, st.playing)
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
                .addCustomAction(CUSTOM_PREV_CHAPTER, "Önceki bölüm", android.R.drawable.ic_media_previous)
                .addCustomAction(CUSTOM_NEXT_CHAPTER, "Sonraki bölüm", android.R.drawable.ic_media_next)
                .setState(
                    if (st.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    if (st.playing) 1f else 0f,
                )
                .build()
        )

        val notification = buildNotification(st)
        try {
            if (!foreground && !started) {
                // Yalnızca bağlı (ör. Android Auto) iken çalma başladıysa önce servisi başlat
                try { startService(Intent(this, PlaybackService::class.java)); started = true } catch (_: Exception) {}
            }
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
            .addAction(android.R.drawable.ic_media_rew, "30 sn geri", action(ACTION_PREV, 1))
            .addAction(
                if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (st.playing) "Duraklat" else "Oynat",
                action(ACTION_TOGGLE, 2),
            )
            .addAction(android.R.drawable.ic_media_ff, "30 sn ileri", action(ACTION_NEXT, 3))
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
        const val ACTION_TOGGLE = "com.seslikitap.TOGGLE"
        private const val ROOT = "kok"
        private const val ACTION_NEXT = "com.seslikitap.NEXT"
        private const val ACTION_PREV = "com.seslikitap.PREV"
        private const val ACTION_STOP = "com.seslikitap.STOP"
        private const val CUSTOM_NEXT_CHAPTER = "sonraki_bolum"
        private const val CUSTOM_PREV_CHAPTER = "onceki_bolum"
        const val SKIP_SECONDS = 30

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
