package com.seslikitap.player

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.seslikitap.Kayit
import com.seslikitap.container
import com.seslikitap.core.ExtractOptions
import com.seslikitap.data.BookOpener

/**
 * Wi-Fi'dayken (ve pil düşük değilken) dinlenen kitabın sıradaki bölümlerini doğal sesle
 * önceden seslendirip telefona kaydeder. Dışarıda mobil veri harcanmaz, internetsiz de doğal ses çalar.
 * Kotayı aşmaz: sınır dolunca durur.
 */
class PrefetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = c.prefs
        if (prefs.voiceMode != "cloud" || !prefs.prefetchEnabled) return Result.success()
        val bookId = inputData.getLong(KEY_BOOK, -1L)
        val entity = c.db.books().get(bookId) ?: return Result.success()
        val pos = c.db.positions().get(bookId)
        val synth = CloudSynth(applicationContext, c.cloudAuth, prefs)
        var made = 0
        var cached = 0
        try {
            val opened = BookOpener.open(applicationContext, entity.uri, entity.fileName)
            BookContent(bookId, opened.book, opened.source, ExtractOptions(prefs.skipFootnotes, prefs.skipPageNumbers)).use { content ->
                val start = (pos?.chapterIndex ?: 0).coerceIn(0, content.chapterCount - 1)
                val end = minOf(content.chapterCount, start + prefs.prefetchChapters)
                for (ci in start until end) {
                    val ch = content.chapter(ci)
                    val from = if (ci == start && pos != null) ch.sentenceIndexOf(pos.blockIndex, pos.sentenceIndex) else 0
                    for (i in from until ch.sentences.size) {
                        if (isStopped) return Result.success()
                        val text = spokenTextOf(ch, i, prefs.normalizeText)
                        val voice = if (prefs.dialogVoiceEnabled && ch.dialog.getOrElse(i) { false })
                            prefs.cloudDialogVoice ?: prefs.cloudVoice else prefs.cloudVoice
                        if (synth.cached(text, voice, prefs.speechRate, prefs.speechPitch) != null) { cached++; continue }
                        if (!synth.hasQuotaFor(text)) {
                            Kayit.log("Önceden hazırlama: kota doldu ($made yeni)")
                            return Result.success()
                        }
                        synth.synthesize(text, voice, prefs.speechRate, prefs.speechPitch)
                        made++
                    }
                }
            }
        } catch (e: Exception) {
            Kayit.error("Önceden hazırlama durdu ($made yeni)", e)
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        Kayit.log("Önceden hazırlama bitti: $made yeni, $cached zaten hazır")
        return Result.success()
    }

    companion object {
        private const val KEY_BOOK = "book"
        private const val WORK = "onceden-hazirla"

        fun enqueue(context: Context, bookId: Long) {
            val req = OneTimeWorkRequestBuilder<PrefetchWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
