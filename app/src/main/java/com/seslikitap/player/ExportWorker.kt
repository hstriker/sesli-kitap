package com.seslikitap.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.seslikitap.Kayit
import com.seslikitap.R
import com.seslikitap.container
import com.seslikitap.core.ExtractOptions
import com.seslikitap.data.BookOpener

/**
 * Kitabı doğal sesle (Google WaveNet) MP3 sesli kitap olarak kaydeder: her bölüm bir dosya,
 * seçilen klasörde kitap adıyla açılan alt klasöre. Zaten kaydedilmiş bölümler atlanır;
 * kota dolarsa durur ve bir sonraki çalıştırmada kaldığı yerden devam eder.
 */
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val c = applicationContext.container
        val prefs = c.prefs
        val bookId = inputData.getLong(KEY_BOOK, -1L)
        val tree = Uri.parse(inputData.getString(KEY_TREE) ?: return Result.failure())
        val entity = c.db.books().get(bookId) ?: return Result.failure()
        val synth = CloudSynth(applicationContext, c.cloudAuth, prefs)
        val resolver = applicationContext.contentResolver
        try { setForeground(info(entity.title, "Hazırlanıyor…", 0, 0)) } catch (_: Exception) {}

        var done = 0
        var total = 0
        try {
            // Kitap klasörü (varsa onu kullan)
            val rootDoc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val folderName = safeName(entity.title)
            val bookDir = findChild(tree, DocumentsContract.getTreeDocumentId(tree), folderName)
                ?: DocumentsContract.createDocument(resolver, rootDoc, DocumentsContract.Document.MIME_TYPE_DIR, folderName)
                ?: throw IllegalStateException("Klasör oluşturulamadı")
            val bookDirId = DocumentsContract.getDocumentId(bookDir)
            val existing = listChildren(tree, bookDirId)

            val opened = BookOpener.open(applicationContext, entity.uri, entity.fileName)
            BookContent(bookId, opened.book, opened.source, ExtractOptions(prefs.skipFootnotes, prefs.skipPageNumbers)).use { content ->
                total = content.chapterCount
                var number = 0
                for (ci in 0 until content.chapterCount) {
                    if (isStopped) return Result.success()
                    val ch = content.chapter(ci)
                    if (ch.isEmpty) continue
                    number++
                    val name = "%02d - %s.mp3".format(number, safeName(ch.title).take(60))
                    if (name in existing) { done++; continue }
                    setForeground(info(entity.title, "$number. bölüm seslendiriliyor: ${ch.title}", ci, total))

                    // Bölümün bütün cümlelerini seslendir (önbellekte olanlar tekrar ücretlendirilmez)
                    val parts = ArrayList<java.io.File>()
                    for (i in ch.sentences.indices) {
                        if (isStopped) return Result.success()
                        val text = spokenTextOf(ch, i, prefs.normalizeText)
                        val voice = if (prefs.dialogVoiceEnabled && ch.dialog.getOrElse(i) { false })
                            prefs.cloudDialogVoice ?: prefs.cloudVoice else prefs.cloudVoice
                        val f = synth.cached(text, voice, prefs.speechRate, prefs.speechPitch)
                            ?: if (synth.hasQuotaFor(text)) synth.synthesize(text, voice, prefs.speechRate, prefs.speechPitch)
                            else {
                                notify(entity.title, "Aylık doğal ses kotası doldu. $done/$total bölüm kaydedildi; gelecek ay kaldığı yerden devam edebilirsiniz.")
                                return Result.success()
                            }
                        parts.add(f)
                    }
                    // MP3 parçalarını tek dosyada birleştir
                    val out = DocumentsContract.createDocument(resolver, bookDir, "audio/mpeg", name)
                        ?: throw IllegalStateException("Dosya oluşturulamadı: $name")
                    resolver.openOutputStream(out)?.use { os -> parts.forEach { p -> p.inputStream().use { it.copyTo(os) } } }
                    done++
                }
            }
            notify(entity.title, "Sesli kitap hazır: $done bölüm, \"$folderName\" klasöründe.")
            Kayit.log("MP3 dışa aktarma bitti: ${entity.title}, $done bölüm")
            return Result.success()
        } catch (e: Exception) {
            Kayit.error("MP3 dışa aktarma durdu", e)
            notify(entity.title, "Kaydetme durdu ($done/$total bölüm): ${e.message}")
            return Result.failure()
        }
    }

    private fun listChildren(tree: Uri, parentId: String): Set<String> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val names = HashSet<String>()
        applicationContext.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { names.add(it) } }
        return names
    }

    private fun findChild(tree: Uri, parentId: String, name: String): Uri? {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        applicationContext.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name && c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                }
            }
        }
        return null
    }

    private fun safeName(s: String) = s.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifBlank { "Kitap" }

    private fun channel() {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Sesli kitap kaydetme", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun info(title: String, text: String, done: Int, total: Int): ForegroundInfo {
        channel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle("MP3 kaydediliyor: $title")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .apply { if (total > 0) setProgress(total, done, false) else setProgress(0, 0, true) }
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, n)
    }

    private fun notify(title: String, text: String) {
        channel()
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_book)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .build()
        try {
            applicationContext.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n)
        } catch (_: SecurityException) {}
    }

    companion object {
        private const val KEY_BOOK = "book"
        private const val KEY_TREE = "tree"
        private const val CHANNEL = "export"
        private const val NOTIFICATION_ID = 3

        fun enqueue(context: Context, bookId: Long, tree: Uri) {
            val req = OneTimeWorkRequestBuilder<ExportWorker>()
                .setInputData(workDataOf(KEY_BOOK to bookId, KEY_TREE to tree.toString()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("mp3-$bookId", ExistingWorkPolicy.KEEP, req)
        }
    }
}
