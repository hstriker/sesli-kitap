package com.seslikitap.player

import android.content.Context
import android.util.Base64
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import com.seslikitap.drive.DriveAuth
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln

/**
 * Google Cloud Text-to-Speech (WaveNet) ile seslendirme ve telefondaki ses önbelleği.
 * Oynatıcı, Wi-Fi'da önceden hazırlama ve MP3 dışa aktarma aynı önbelleği kullanır:
 * bir cümle bir kez seslendirilir, kotadan bir kez düşer.
 */
class CloudSynth(context: Context, private val auth: DriveAuth, private val prefs: Prefs) {

    val cacheDir = File(context.filesDir, "ses").apply { mkdirs() }

    fun fileFor(text: String, voice: String, rate: Float, pitch: Float): File {
        val key = sha1("$voice|${"%.2f".format(Locale.US, rate)}|${"%.2f".format(Locale.US, pitch)}|$text")
        return File(cacheDir, "$key.mp3")
    }

    fun cached(text: String, voice: String, rate: Float, pitch: Float): File? {
        val f = fileFor(text, voice, rate, pitch)
        if (f.exists() && f.length() > 0) {
            f.setLastModified(System.currentTimeMillis())
            return f
        }
        return null
    }

    fun hasQuotaFor(text: String): Boolean =
        prefs.cloudCharsThisMonth() + text.length <= prefs.cloudMonthlyLimit

    fun quotaLeft(): Long = (prefs.cloudMonthlyLimit - prefs.cloudCharsThisMonth()).coerceAtLeast(0)

    /** Google'dan seslendirir, kotayı sayar, önbelleğe yazar. Hata durumunda istisna fırlatır. */
    fun synthesize(text: String, voice: String, rate: Float, pitch: Float): File {
        cached(text, voice, rate, pitch)?.let { return it }
        val bytes = requestCloud(text, voice, rate, pitch)
        prefs.addCloudChars(text.length.toLong())
        val f = fileFor(text, voice, rate, pitch)
        val tmp = File(cacheDir, f.name + ".tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        trimCache()
        return f
    }

    private fun requestCloud(text: String, voice: String, rate: Float, pitch: Float): ByteArray {
        var refresh = false
        while (true) {
            val token = auth.tokenBlocking(refresh)
            val conn = URL("https://texttospeech.googleapis.com/v1/text:synthesize").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("x-goog-user-project", PROJECT_ID)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val semitones = (12.0 * ln(pitch.toDouble()) / ln(2.0)).coerceIn(-20.0, 20.0)
                val body = JSONObject()
                    .put("input", JSONObject().put("text", text))
                    .put("voice", JSONObject().put("languageCode", "tr-TR").put("name", voice))
                    .put(
                        "audioConfig", JSONObject()
                            .put("audioEncoding", "MP3")
                            .put("speakingRate", rate.toDouble().coerceIn(0.25, 4.0))
                            .put("pitch", semitones)
                    )
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    return Base64.decode(json.getString("audioContent"), Base64.DEFAULT)
                }
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Kayit.log("Doğal ses HTTP $code: ${err.take(300).replace('\n', ' ')}")
                if (code == 401 && !refresh) { refresh = true; continue }
                throw IOException(explain(code, err))
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun explain(code: Int, body: String): String = when {
        body.contains("BILLING_DISABLED") || body.contains("billing", ignoreCase = true) ->
            "Google Cloud projesinde faturalandırma açık değil"
        body.contains("SERVICE_DISABLED") || body.contains("has not been used") ->
            "Google Cloud'da \"Cloud Text-to-Speech API\" etkinleştirilmemiş"
        code == 403 -> "Google erişimi reddetti (403)"
        code == 429 -> "Google çok fazla istek dedi (429)"
        else -> "Google hatası ($code)"
    }

    private fun trimCache() {
        val files = cacheDir.listFiles { f -> f.name.endsWith(".mp3") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            total -= f.length()
            f.delete()
            if (total <= MAX_CACHE_BYTES * 0.8) break
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val PROJECT_ID = "sesli-kitap-514818"
        private const val MAX_CACHE_BYTES = 600L * 1024 * 1024
    }
}
