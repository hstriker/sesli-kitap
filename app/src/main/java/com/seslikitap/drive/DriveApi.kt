package com.seslikitap.drive

import android.content.Context
import com.seslikitap.core.CachedRangeSource
import com.seslikitap.core.ZipArchive
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.time.Instant

data class DriveItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val modified: Long,
) {
    val isFolder get() = mimeType == FOLDER_MIME
    val isEpub get() = mimeType == "application/epub+zip" || name.endsWith(".epub", ignoreCase = true)

    companion object {
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}

/** Drive'da bulunan bir kitap ve seçilen klasöre göre yolu. */
data class DriveBook(val item: DriveItem, val relativePath: String)

/** Google Drive REST v3 istemcisi (yalnızca okuma). Tüm çağrılar bloklar; arka planda çağırın. */
class DriveApi(private val auth: DriveAuth) {

    /** Klasörün doğrudan içeriği (kısayollar hedefe çözülür). */
    fun listChildren(folderId: String): List<DriveItem> {
        val out = ArrayList<DriveItem>()
        var pageToken: String? = null
        val q = enc("'$folderId' in parents and trashed = false")
        val fields = enc("nextPageToken,files(id,name,mimeType,size,modifiedTime,shortcutDetails(targetId,targetMimeType))")
        do {
            val url = "$BASE/files?q=$q&fields=$fields&pageSize=1000&orderBy=folder,name" +
                "&supportsAllDrives=true&includeItemsFromAllDrives=true" +
                (pageToken?.let { "&pageToken=${enc(it)}" } ?: "")
            val json = JSONObject(String(request(url), Charsets.UTF_8))
            val files = json.optJSONArray("files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    var item = DriveItem(
                        id = f.getString("id"),
                        name = f.optString("name"),
                        mimeType = f.optString("mimeType"),
                        size = f.optString("size").toLongOrNull() ?: -1L,
                        modified = parseTime(f.optString("modifiedTime")),
                    )
                    val sc = f.optJSONObject("shortcutDetails")
                    if (item.mimeType == SHORTCUT_MIME && sc != null) {
                        val target = sc.optString("targetId")
                        val targetMime = sc.optString("targetMimeType")
                        if (target.isEmpty()) continue
                        item = if (targetMime == DriveItem.FOLDER_MIME) {
                            item.copy(id = target, mimeType = targetMime)
                        } else {
                            runCatching { metadata(target) }.getOrNull()?.copy(name = item.name) ?: continue
                        }
                    }
                    out.add(item)
                }
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    fun metadata(fileId: String): DriveItem {
        val fields = enc("id,name,mimeType,size,modifiedTime")
        val f = JSONObject(String(request("$BASE/files/$fileId?fields=$fields&supportsAllDrives=true"), Charsets.UTF_8))
        return DriveItem(
            f.getString("id"), f.optString("name"), f.optString("mimeType"),
            f.optString("size").toLongOrNull() ?: -1L, parseTime(f.optString("modifiedTime")),
        )
    }

    /** Klasörü ve tüm alt klasörlerini dolaşıp EPUB'ları bulur. */
    fun listEpubsRecursive(rootId: String, onProgress: (folders: Int, books: Int) -> Unit = { _, _ -> }): List<DriveBook> {
        val result = ArrayList<DriveBook>()
        val visited = HashSet<String>()
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to "")
        var folders = 0
        while (stack.isNotEmpty()) {
            val (id, rel) = stack.removeLast()
            if (!visited.add(id)) continue
            folders++
            for (item in listChildren(id)) {
                when {
                    item.isFolder -> stack.add(item.id to "$rel${item.name}/")
                    item.isEpub && item.size != 0L -> result.add(DriveBook(item, rel))
                }
            }
            onProgress(folders, result.size)
        }
        return result
    }

    /** Dosyanın [start]'tan itibaren [length] baytı. */
    fun readRange(fileId: String, start: Long, length: Int): ByteArray =
        request("$BASE/files/$fileId?alt=media&supportsAllDrives=true", range = start to (start + length - 1))

    /** Dosyayı tamamen indirir (önce geçici dosyaya, bitince yerine taşır). */
    fun download(fileId: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        var attempt = 0
        var refresh = false
        while (true) {
            try {
                val conn = open("$BASE/files/$fileId?alt=media&supportsAllDrives=true", null, auth.tokenBlocking(refresh))
                try {
                    val code = conn.responseCode
                    if (code != 200) {
                        val body = readText(conn.errorStream, 4096)
                        if (code == 401 && !refresh) { refresh = true; continue }
                        if (shouldRetry(code, body) && attempt < MAX_RETRY) { backoff(attempt++); continue }
                        throw IOException(errorMessage(code, body))
                    }
                    conn.inputStream.use { input -> FileOutputStream(tmp).use { input.copyTo(it, 64 * 1024) } }
                } finally {
                    conn.disconnect()
                }
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
                return
            } catch (e: IOException) {
                tmp.delete()
                if (e is DriveAuthException || attempt >= MAX_RETRY || !isTransient(e)) throw friendly(e)
                backoff(attempt++)
            }
        }
    }

    /** Drive'daki EPUB'ı indirmeden, parça parça okuyarak açar (tarama için). */
    fun openRemote(item: DriveItem): ZipArchive {
        val size = if (item.size > 0) item.size else metadata(item.id).size
        if (size <= 0) throw IOException("Dosya boyutu alınamadı")
        return ZipArchive.open(CachedRangeSource(size) { s, l -> readRange(item.id, s, l) })
    }

    // ------------------------------------------------------------------ HTTP

    private fun request(url: String, range: Pair<Long, Long>? = null): ByteArray {
        var attempt = 0
        var refresh = false
        while (true) {
            try {
                val conn = open(url, range, auth.tokenBlocking(refresh))
                try {
                    val code = conn.responseCode
                    if (code == 200 || code == 206) {
                        val expected = range?.let { (it.second - it.first + 1).toInt() }
                        val bytes = readAll(conn.inputStream, Int.MAX_VALUE)
                        // Sunucu aralığı yok sayıp tüm dosyayı gönderdiyse gereken kısmı kes.
                        if (range != null && code == 200 && expected != null && bytes.size > expected) {
                            val s = range.first.toInt()
                            return bytes.copyOfRange(s, minOf(bytes.size, s + expected))
                        }
                        return bytes
                    }
                    val body = readText(conn.errorStream, 4096)
                    if (code == 401 && !refresh) { refresh = true; continue }
                    if (shouldRetry(code, body) && attempt < MAX_RETRY) { backoff(attempt++); continue }
                    throw IOException(errorMessage(code, body))
                } finally {
                    conn.disconnect()
                }
            } catch (e: IOException) {
                if (e is DriveAuthException || attempt >= MAX_RETRY || !isTransient(e)) throw friendly(e)
                backoff(attempt++)
            }
        }
    }

    private fun open(url: String, range: Pair<Long, Long>?, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (range != null) conn.setRequestProperty("Range", "bytes=${range.first}-${range.second}")
        return conn
    }

    private fun shouldRetry(code: Int, body: String) =
        code == 429 || code >= 500 ||
            (code == 403 && (body.contains("rateLimitExceeded") || body.contains("userRateLimitExceeded")))

    /** Kısa süre sonra yeniden denemeye değer ağ hataları (internet hiç yoksa denemez). */
    private fun isTransient(e: IOException) =
        e is SocketTimeoutException || e is java.net.SocketException || e is java.io.EOFException ||
            e is javax.net.ssl.SSLException

    private fun friendly(e: IOException): IOException = when (e) {
        is UnknownHostException -> IOException("İnternet bağlantısı yok", e)
        is SocketTimeoutException -> IOException("Google Drive yanıt vermedi (zaman aşımı)", e)
        else -> e
    }

    private fun errorMessage(code: Int, body: String): String {
        val reason = runCatching {
            JSONObject(body).getJSONObject("error").optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            401 -> "Google Drive izni geçersiz. Yeniden bağlanın."
            403 -> if (body.contains("accessNotConfigured") || body.contains("has not been used"))
                "Google Cloud projesinde \"Google Drive API\" etkinleştirilmemiş."
            else "Google Drive erişimi reddedildi: $reason"
            404 -> "Dosya Drive'da bulunamadı (silinmiş ya da taşınmış olabilir)"
            else -> "Google Drive hatası ($code): $reason"
        }
    }

    private fun backoff(attempt: Int) {
        Thread.sleep((500L shl attempt).coerceAtMost(16_000L) + (0..400).random())
    }

    private fun readAll(input: InputStream?, max: Int): ByteArray {
        if (input == null) return ByteArray(0)
        return input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) break
            }
            out.toByteArray()
        }
    }

    private fun readText(input: InputStream?, max: Int): String = String(readAll(input, max), Charsets.UTF_8)

    companion object {
        private const val BASE = "https://www.googleapis.com/drive/v3"
        private const val SHORTCUT_MIME = "application/vnd.google-apps.shortcut"
        private const val MAX_RETRY = 5

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        private fun parseTime(s: String): Long = try { Instant.parse(s).toEpochMilli() } catch (e: Exception) { 0L }
    }
}

/** Okunmak üzere açılan Drive kitaplarının telefondaki kopyaları. */
object DriveCache {
    private const val KEEP = 40

    fun dir(context: Context) = File(context.filesDir, "drive").apply { mkdirs() }
    fun fileFor(context: Context, fileId: String) = File(dir(context), "$fileId.epub")

    fun isCached(context: Context, fileId: String) = fileFor(context, fileId).let { it.exists() && it.length() > 0 }

    /** Kitabın telefondaki kopyası; yoksa indirir. */
    fun ensure(context: Context, api: DriveApi, fileId: String): File {
        val f = fileFor(context, fileId)
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis())
            return f
        }
        api.download(fileId, f)
        trim(context)
        return f
    }

    fun delete(context: Context, fileId: String) {
        fileFor(context, fileId).delete()
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles { f -> f.name.endsWith(".epub") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}
