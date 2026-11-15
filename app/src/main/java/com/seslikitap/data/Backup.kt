package com.seslikitap.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.seslikitap.Kayit
import com.seslikitap.container
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Drive'daki gizli uygulama klasörüne (appDataFolder) yedek: kaldığın yerler, yer imleri,
 * alıntılar, favoriler, raflar, dinleme istatistikleri ve ayarlar. Kitap dosyalarına dokunmaz.
 * Kitaplar Drive dosya kimliğiyle (telefondakiler dosya adı + boyutla) eşleştirilir.
 */
class Backup(private val context: Context) {

    private val db get() = context.container.db
    private val prefs get() = context.container.prefs
    val auth get() = context.container.backupAuth

    // ------------------------------------------------------------------ Yedek içeriği

    private fun key(b: BookEntity) = b.uri
    private fun altKey(b: BookEntity) = "${b.fileName}|${b.sizeBytes}"

    suspend fun build(): JSONObject = withContext(Dispatchers.IO) {
        val books = db.books().all()
        val byId = books.associateBy { it.id }
        fun ref(id: Long) = byId[id]?.let { JSONObject().put("uri", key(it)).put("alt", altKey(it)) }

        JSONObject().apply {
            put("surum", 1)
            put("zaman", System.currentTimeMillis())
            put("kitaplar", JSONArray().apply {
                books.filter { it.favorite || it.progress > 0f || it.finished || it.listenedMs > 0 || it.lastOpenedAt != null }
                    .forEach { b ->
                        put(JSONObject().put("uri", key(b)).put("alt", altKey(b))
                            .put("title", b.title).put("author", b.author).put("path", b.relativePath)
                            .put("favorite", b.favorite).put("progress", b.progress.toDouble()).put("finished", b.finished)
                            .put("finishedAt", b.finishedAt ?: 0L).put("listenedMs", b.listenedMs)
                            .put("lastOpenedAt", b.lastOpenedAt ?: 0L))
                    }
            })
            put("konumlar", JSONArray().apply {
                db.positions().all().forEach { p ->
                    ref(p.bookId)?.let {
                        put(it.put("ch", p.chapterIndex).put("bl", p.blockIndex).put("se", p.sentenceIndex).put("t", p.updatedAt)
                            .put("excerpt", prefs.positionExcerpt(p.bookId) ?: ""))
                    }
                }
            })
            put("yerimleri", JSONArray().apply {
                db.bookmarks().all().forEach { m ->
                    ref(m.bookId)?.let {
                        put(it.put("ch", m.chapterIndex).put("bl", m.blockIndex).put("se", m.sentenceIndex)
                            .put("excerpt", m.excerpt).put("note", m.note ?: "").put("t", m.createdAt))
                    }
                }
            })
            put("alintilar", JSONArray().apply {
                db.quotes().all().forEach { q ->
                    val r = ref(q.bookId) ?: JSONObject()
                    put(r.put("ch", q.chapterIndex).put("bl", q.blockIndex).put("se", q.sentenceIndex).put("text", q.text)
                        .put("title", q.bookTitle).put("author", q.author).put("t", q.createdAt))
                }
            })
            val shelves = db.shelves().allShelves()
            put("raflar", JSONArray().apply {
                shelves.forEach { sh ->
                    put(JSONObject().put("name", sh.name).put("t", sh.createdAt).put("kitaplar", JSONArray().apply {
                        db.shelves().allLinks().filter { it.shelfId == sh.id }.forEach { l -> ref(l.bookId)?.let { put(it) } }
                    }))
                }
            })
            put("klasorler", JSONArray().apply {
                db.folders().all().filter { DriveUri.isDrive(it.uri) }.forEach {
                    put(JSONObject().put("id", DriveUri.id(it.uri)).put("name", it.name))
                }
            })
            put("dinleme", JSONArray().apply { db.listening().all().forEach { put(JSONObject().put("day", it.day).put("ms", it.ms)) } })
            put("ayarlar", JSONObject()
                .put("speechRate", prefs.speechRate.toDouble()).put("speechPitch", prefs.speechPitch.toDouble())
                .put("voiceMode", prefs.voiceMode).put("cloudVoice", prefs.cloudVoice)
                .put("dailyGoalMin", prefs.dailyGoalMin).put("yearlyGoal", prefs.yearlyGoal)
                .put("normalizeText", prefs.normalizeText).put("skipFootnotes", prefs.skipFootnotes)
                .put("skipPageNumbers", prefs.skipPageNumbers))
        }
    }

    /** Yedeği mevcut kitaplığa birleştirir (silmez; ilerlemede büyük olanı alır). Eşleşen kitap sayısını döner. */
    /**
     * [positionsOnly]: açılıştaki eşitleme — yalnızca daha yeni "kaldığın yer" kayıtları alınır;
     * favori, ayar vb. dokunulmaz (telefonda sonradan yapılan değişiklikler geri dönmesin).
     */
    suspend fun restore(json: JSONObject, positionsOnly: Boolean = false): Int = withContext(Dispatchers.IO) {
        val books = db.books().all()
        val byUri = books.associateBy { key(it) }
        val byAlt = books.associateBy { altKey(it) }
        fun find(o: JSONObject): BookEntity? = byUri[o.optString("uri")] ?: byAlt[o.optString("alt")]
        var matched = 0

        if (!positionsOnly) json.optJSONArray("kitaplar")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = find(o) ?: continue
                matched++
                db.books().update(
                    b.copy(
                        favorite = b.favorite || o.optBoolean("favorite"),
                        progress = maxOf(b.progress, o.optDouble("progress", 0.0).toFloat()),
                        finished = b.finished || o.optBoolean("finished"),
                        finishedAt = b.finishedAt ?: o.optLong("finishedAt").takeIf { it > 0 },
                        listenedMs = maxOf(b.listenedMs, o.optLong("listenedMs")),
                        lastOpenedAt = maxOf(b.lastOpenedAt ?: 0L, o.optLong("lastOpenedAt")).takeIf { it > 0 },
                    )
                )
            }
        }
        json.optJSONArray("konumlar")?.let { arr ->
            val existing = db.positions().all().associateBy { it.bookId }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = find(o) ?: continue
                val t = o.optLong("t")
                if ((existing[b.id]?.updatedAt ?: 0L) >= t) continue
                db.positions().upsert(PositionEntity(b.id, o.optInt("ch"), o.optInt("bl"), o.optInt("se"), t))
                o.optString("excerpt").takeIf { it.isNotBlank() }?.let { prefs.setPositionExcerpt(b.id, it) }
            }
        }
        if (!positionsOnly) json.optJSONArray("yerimleri")?.let { arr ->
            val existing = db.bookmarks().all().map { "${it.bookId}|${it.chapterIndex}|${it.blockIndex}|${it.sentenceIndex}" }.toHashSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val b = find(o) ?: continue
                val k = "${b.id}|${o.optInt("ch")}|${o.optInt("bl")}|${o.optInt("se")}"
                if (!existing.add(k)) continue
                db.bookmarks().insert(
                    BookmarkEntity(
                        bookId = b.id, chapterIndex = o.optInt("ch"), blockIndex = o.optInt("bl"), sentenceIndex = o.optInt("se"),
                        excerpt = o.optString("excerpt"), note = o.optString("note").ifBlank { null }, createdAt = o.optLong("t"),
                    )
                )
            }
        }
        if (!positionsOnly) json.optJSONArray("alintilar")?.let { arr ->
            val existing = db.quotes().all().map { it.text }.toHashSet()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val text = o.optString("text")
                if (text.isBlank() || !existing.add(text)) continue
                db.quotes().insert(
                    QuoteEntity(
                        bookId = find(o)?.id ?: -1L, chapterIndex = o.optInt("ch"), blockIndex = o.optInt("bl"),
                        sentenceIndex = o.optInt("se"), text = text, bookTitle = o.optString("title"),
                        author = o.optString("author"), createdAt = o.optLong("t"),
                    )
                )
            }
        }
        if (!positionsOnly) json.optJSONArray("raflar")?.let { arr ->
            val shelves = db.shelves().allShelves().associateBy { it.name }.toMutableMap()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name")
                if (name.isBlank()) continue
                val id = shelves[name]?.id ?: db.shelves().insert(ShelfEntity(name = name, createdAt = o.optLong("t")))
                    .also { shelves[name] = ShelfEntity(it, name, 0) }
                val items = o.optJSONArray("kitaplar") ?: continue
                for (j in 0 until items.length()) {
                    find(items.getJSONObject(j))?.let { b -> db.shelves().add(ShelfBookEntity(id, b.id, System.currentTimeMillis())) }
                }
            }
        }
        if (!positionsOnly) json.optJSONArray("dinleme")?.let { arr ->
            val existing = db.listening().all().associate { it.day to it.ms }
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val day = o.optString("day")
                val ms = o.optLong("ms")
                if (day.isNotBlank() && ms > (existing[day] ?: 0L)) db.listening().upsert(ListeningDay(day, ms))
            }
        }
        if (!positionsOnly) json.optJSONObject("ayarlar")?.let { a ->
            prefs.speechRate = a.optDouble("speechRate", prefs.speechRate.toDouble()).toFloat()
            prefs.speechPitch = a.optDouble("speechPitch", prefs.speechPitch.toDouble()).toFloat()
            prefs.voiceMode = a.optString("voiceMode", prefs.voiceMode)
            prefs.cloudVoice = a.optString("cloudVoice", prefs.cloudVoice)
            prefs.dailyGoalMin = a.optInt("dailyGoalMin", prefs.dailyGoalMin)
            prefs.yearlyGoal = a.optInt("yearlyGoal", prefs.yearlyGoal)
            prefs.normalizeText = a.optBoolean("normalizeText", prefs.normalizeText)
            prefs.skipFootnotes = a.optBoolean("skipFootnotes", prefs.skipFootnotes)
            prefs.skipPageNumbers = a.optBoolean("skipPageNumbers", prefs.skipPageNumbers)
        }
        matched
    }

    // ------------------------------------------------------------------ Drive appDataFolder

    suspend fun upload() = withContext(Dispatchers.IO) {
        val existing = findFile()
        // Başka cihazın (web) yazdığı daha yeni konumları kaybetmemek için önce birleştir
        if (existing != null) {
            try {
                val remote = JSONObject(String(http("$BASE/files/$existing?alt=media", "GET", null, null), Charsets.UTF_8))
                restore(remote, positionsOnly = true)
            } catch (e: Exception) {
                Kayit.error("Yedek birleştirilemedi", e)
            }
        }
        val body = build().toString().toByteArray(Charsets.UTF_8)
        if (existing != null) {
            http("$UPLOAD/files/$existing?uploadType=media", "POST", body, "application/json", override = "PATCH")
        } else {
            val boundary = "seslikitap${System.nanoTime()}"
            val meta = JSONObject().put("name", FILE_NAME).put("parents", JSONArray().put("appDataFolder")).toString()
            val multipart = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n" +
                "--$boundary\r\nContent-Type: application/json\r\n\r\n").toByteArray() + body + "\r\n--$boundary--".toByteArray()
            http("$UPLOAD/files?uploadType=multipart", "POST", multipart, "multipart/related; boundary=$boundary")
        }
        prefs.lastBackup = System.currentTimeMillis()
        Kayit.log("Yedek Drive'a yüklendi (${body.size} B)")
    }

    suspend fun download(): JSONObject? = withContext(Dispatchers.IO) {
        val id = findFile() ?: return@withContext null
        JSONObject(String(http("$BASE/files/$id?alt=media", "GET", null, null), Charsets.UTF_8))
    }

    private fun findFile(): String? {
        val q = URLEncoder.encode("name = '$FILE_NAME' and trashed = false", "UTF-8")
        val json = JSONObject(String(http("$BASE/files?spaces=appDataFolder&q=$q&fields=files(id)", "GET", null, null), Charsets.UTF_8))
        return json.optJSONArray("files")?.optJSONObject(0)?.optString("id")?.ifBlank { null }
    }

    private fun http(url: String, method: String, body: ByteArray?, contentType: String?, override: String? = null): ByteArray {
        var refresh = false
        while (true) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.requestMethod = method
                conn.setRequestProperty("Authorization", "Bearer ${auth.tokenBlocking(refresh)}")
                override?.let { conn.setRequestProperty("X-HTTP-Method-Override", it) }
                if (body != null) {
                    conn.doOutput = true
                    contentType?.let { conn.setRequestProperty("Content-Type", it) }
                    conn.outputStream.use { it.write(body) }
                }
                val code = conn.responseCode
                if (code in 200..299) return conn.inputStream.use { it.readBytes() }
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Kayit.log("Yedek HTTP $code: ${err.take(300).replace('\n', ' ')}")
                if (code == 401 && !refresh) { refresh = true; continue }
                throw IOException(
                    if (code == 403 && err.contains("insufficient", true))
                        "Drive yedekleme izni yok. Google Cloud'da drive.appdata iznini ekleyip yeniden bağlanın."
                    else "Drive yedekleme hatası ($code)"
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FILE_NAME = "seslikitap-yedek.json"
        const val SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

/** Günde bir otomatik yedek (internet varken). */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!applicationContext.container.prefs.autoBackup) return Result.success()
        return try {
            Backup(applicationContext).upload()
            Result.success()
        } catch (e: Exception) {
            Kayit.error("Otomatik yedek alınamadı", e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<BackupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("gunluk-yedek", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
