package com.seslikitap.player

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.seslikitap.Kayit
import com.seslikitap.container
import com.seslikitap.core.ExtractOptions
import com.seslikitap.data.BookOpener
import com.seslikitap.data.DriveUri
import java.util.concurrent.TimeUnit

/**
 * Gemini hakkı biriktirme ve tatil modu.
 *
 * Her gün hak sıfırlanmadan önce (Türkiye saatiyle sabah ~8:45-9:45, Wi-Fi'da, pil düşük değilken)
 * o gün kullanılmayan Gemini hakları, önce tatil listesindeki kitapların, sonra en son dinlenen kitabın
 * sıradaki parçalarına harcanır. Hazırlanan ses telefonda saklanır: o kısımlar dinlenirken hak yemez,
 * internet de gerekmez. Yoğun günlerde günlük sınırdan fazlası dinlenebilir.
 */
class GeminiBankWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = c.prefs
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        try {
            if (prefs.voiceMode != "gemini" || prefs.geminiKey.isNullOrBlank()) return Result.success()
            if (!manual && !prefs.geminiBank && prefs.offlineBooks.isEmpty()) return Result.success()
            if (Battery.saving(applicationContext, prefs)) {
                Kayit.log("Biriktirme: pil düşük, atlandı")
                return Result.success()
            }
            // Zamanında çalışamadıysa (Wi-Fi yoktu) ertesi günün hakkını harcamamak için sıfırlanmaya
            // 3 saatten az kalmış olmalı; elle başlatıldıysa hemen.
            val untilReset = GeminiTts.nextReset().toInstant().toEpochMilli() - System.currentTimeMillis()
            if (!manual && untilReset > 3 * 3_600_000L) {
                Kayit.log("Biriktirme: zamanı değil (${untilReset / 60_000} dk var)")
                return Result.success()
            }
            run(manual)
        } catch (e: Exception) {
            Kayit.error("Biriktirme durdu", e)
        }
        return Result.success()
    }

    private suspend fun run(manual: Boolean) {
        val c = applicationContext.container
        val prefs = c.prefs
        val books = buildList {
            addAll(prefs.offlineBooks)
            if (prefs.geminiBank || manual) c.db.books().lastOpened()?.id?.let { if (it !in this) add(it) }
        }
        var made = 0
        var ready = 0
        val voice = prefs.geminiVoice
        val narrator = prefs.geminiNarrator
        val opts = ExtractOptions(prefs.skipFootnotes, prefs.skipPageNumbers)
        outer@ for (bookId in books) {
            val entity = c.db.books().get(bookId) ?: continue
            if (DriveUri.isDrive(entity.uri) && bookId in prefs.offlineBooks) {
                prefs.pinnedDriveIds = prefs.pinnedDriveIds + DriveUri.id(entity.uri)
            }
            val pos = c.db.positions().get(bookId)
            val opened = try {
                BookOpener.open(applicationContext, entity.uri, entity.fileName)
            } catch (e: Exception) {
                Kayit.error("Biriktirme: ${entity.title} açılamadı", e)
                continue
            }
            val content = BookContent(bookId, opened.book, opened.source, opts)
            try {
                if (content.chapterCount == 0) continue@outer
                var ci = (pos?.chapterIndex ?: 0).coerceIn(0, content.chapterCount - 1)
                var first = true
                while (ci < content.chapterCount) {
                    if (isStopped) return
                    val ch = content.chapter(ci)
                    val ends = GeminiAudio.groupEnds(ch, prefs.geminiGroupMax)
                    val from = if (first && pos != null) ch.sentenceIndexOf(pos.blockIndex, pos.sentenceIndex) else 0
                    first = false
                    var start = 0
                    for (end in ends) {
                        if (end >= from) {
                            val text = GeminiAudio.groupText(ch, start, end, prefs.normalizeText)
                            if (GeminiAudio.cached(applicationContext, text, voice, narrator) != null) {
                                ready++
                            } else {
                                try {
                                    GeminiAudio.synthesize(applicationContext, prefs, text, voice, narrator)
                                    made++
                                } catch (e: GeminiTts.QuotaException) {
                                    GeminiTts.recordQuota(prefs, e)
                                    break@outer
                                } catch (e: GeminiAudio.BlockedException) {
                                    break@outer
                                } catch (e: GeminiAudio.TruncatedException) {
                                    // parça boyu küçüldü; bu parçayı geç, sonrakiler yeni boyla
                                }
                            }
                        }
                        start = end + 1
                    }
                    ci++
                }
            } finally {
                content.close()
            }
        }
        val mb = GeminiAudio.cacheBytes(applicationContext) / (1024 * 1024)
        val time = java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("tr")).format(java.util.Date())
        prefs.geminiBankLast = "$time: $made yeni parça hazırlandı (telefonda toplam $mb MB Gemini sesi)"
        Kayit.log("Biriktirme bitti: $made yeni, $ready zaten hazır")
    }

    companion object {
        private const val WORK = "gemini-biriktir"
        private const val WORK_NOW = "gemini-biriktir-simdi"
        private const val KEY_MANUAL = "elle"

        /** Her gün, hak sıfırlanmadan ~75 dk önce çalışır (kurulu değilse kurar). */
        fun schedule(context: Context) {
            val target = GeminiTts.nextReset().toInstant().toEpochMilli() - 75 * 60_000L
            var delay = target - System.currentTimeMillis()
            if (delay < 0) delay += 24 * 3_600_000L
            val req = PeriodicWorkRequestBuilder<GeminiBankWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        /** Tatil modu: "Şimdi hazırla" (bugünün kalan hakkıyla, Wi-Fi beklenmez). */
        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<GeminiBankWorker>()
                .setInputData(workDataOf(KEY_MANUAL to true))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, req)
        }
    }
}
