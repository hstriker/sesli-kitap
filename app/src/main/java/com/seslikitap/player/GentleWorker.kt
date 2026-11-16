package com.seslikitap.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.seslikitap.MainActivity
import com.seslikitap.R
import com.seslikitap.container
import com.seslikitap.data.CalmStore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Günde bir kez, seçilen saatte: nazik hatırlatıcı ("kendine 20 dakika ayırmak ister misin?") ve
 * açılma zamanı gelen geleceğe mektuplar. Bugün zaten dinlediysen hatırlatma gelmez.
 */
class GentleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = c.prefs
        val today = LocalDate.now()
        // Mektuplar
        val due = CalmStore.list(applicationContext, CalmStore.LETTERS)
            .filter { !it.optBoolean("opened") && !it.optBoolean("notified") && it.optLong("open") <= System.currentTimeMillis() }
        if (due.isNotEmpty()) {
            notify(applicationContext, 2, "Geçmişten bir mektubun var 💌", "Kendine yazdığın mektubun açılma zamanı geldi.")
            val all = CalmStore.list(applicationContext, CalmStore.LETTERS).map { o ->
                if (due.any { it.optString("id") == o.optString("id") }) o.put("notified", true) else o
            }
            CalmStore.save(applicationContext, CalmStore.LETTERS, all)
        }
        // Hatırlatıcı
        if (prefs.reminderOn) {
            val last = prefs.lastReminderDay?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val gap = last?.let { ChronoUnit.DAYS.between(it, today) } ?: 99
            val listenedToday = c.db.listening().all().firstOrNull { it.day == today.toString() }?.ms ?: 0L
            if (gap >= prefs.reminderEvery && listenedToday < 10 * 60_000L) {
                notify(applicationContext, 1, "Kendine biraz zaman 🌿", MESSAGES[(today.dayOfYear) % MESSAGES.size])
                prefs.lastReminderDay = today.toString()
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK = "nazik-gunluk"
        private const val CHANNEL = "nazik"

        private val MESSAGES = listOf(
            "Bugün kendine 20 dakika ayırmak ister misin?",
            "Kitabın seni bekliyor; acele yok, istersen birkaç sayfa.",
            "Günün yorgunluğunu bir hikâyeyle bırakmaya ne dersin?",
            "Biraz nefes, biraz kitap?",
            "Bu akşam kendine küçük bir kaçış hediye et.",
        )

        /** Seçilen saatte günde bir kez çalışır; saat değişince yeniden kurulur. */
        fun schedule(context: Context, replace: Boolean = false) {
            val prefs = context.container.prefs
            val now = LocalDateTime.now()
            var at = now.toLocalDate().atStartOfDay().plusMinutes(prefs.reminderMinute.toLong())
            if (!at.isAfter(now)) at = at.plusDays(1)
            val delay = ChronoUnit.MILLIS.between(now, at)
            val req = PeriodicWorkRequestBuilder<GentleWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK, if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }

        fun notify(context: Context, id: Int, title: String, text: String) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Nazik hatırlatmalar", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(
                context, 10 + id, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_book)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try { NotificationManagerCompat.from(context).notify(500 + id, n) } catch (_: SecurityException) {}
        }
    }
}
