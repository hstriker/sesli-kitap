package com.seslikitap.player

import android.util.Base64
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini ile seslendirme (deneme). Ücretsiz AI Studio anahtarıyla çalışır.
 * Model adı sabit değil: anahtarın erişebildiği "tts" modelleri listelenip en uygunu seçilir.
 */
object GeminiTts {

    /** Ad -> ekranda gösterilen açıklama. */
    val VOICES = listOf(
        "Kore" to "Kore (kadın, sakin)",
        "Aoede" to "Aoede (kadın, akıcı)",
        "Leda" to "Leda (kadın, genç)",
        "Charon" to "Charon (erkek, anlatıcı)",
        "Orus" to "Orus (erkek, tok)",
        "Puck" to "Puck (erkek, canlı)",
    )

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val FALLBACK_MODEL = "gemini-2.5-flash-preview-tts"


    /** [tokens]: üretilen ses jetonu (yoksa 0); [finish]: Google'ın bitiş nedeni (STOP, MAX_TOKENS…). */
    class Result(val file: File, val model: String, val millis: Long, val tokens: Int = 0, val finish: String = "")

    /** Ücretsiz kota doldu. [daily]: günlük sınır (gece yarısı Pasifik saatinde yenilenir); değilse [retrySeconds] sonra. */
    class QuotaException(val daily: Boolean, val retrySeconds: Int, val limit: Int?, message: String) : IOException(message)

    /**
     * Kota hatasını kaydeder: Gemini'ye ne zamana kadar istek gönderilmeyeceği ve günlük sınır.
     * Günlük sınırsa yenilenme zamanını döndürür.
     */
    fun recordQuota(prefs: Prefs, e: QuotaException): ZonedDateTime? {
        Kayit.log("Gemini kota: günlük=${e.daily}, sınır=${e.limit}, bekle=${e.retrySeconds} sn")
        return if (e.daily) {
            val until = nextReset()
            prefs.geminiBlockedUntil = until.toInstant().toEpochMilli()
            until
        } else {
            prefs.geminiBlockedUntil = System.currentTimeMillis() + (e.retrySeconds.coerceIn(10, 600) * 1000L)
            null
        }
    }

    /** Ayarlarda ve ses ayarlarında gösterilen "kalan sınır" satırı. */
    fun quotaSummary(prefs: Prefs): String {
        val (chars, reqs) = prefs.geminiToday()
        val now = System.currentTimeMillis()
        // Bir parçanın ortalama süresi (dakika); 900 karakter ≈ 1 dk
        val perReq = if (reqs > 0) chars / 900.0 / reqs else 6.0
        val models = prefs.geminiModels.filter { prefs.geminiModelLimit(it) > 0 }
        return buildString {
            if (models.isEmpty()) {
                append("Bugün kullanılan: $reqs parça (~${chars / 900} dk). Kalan miktar ilk dinlemeden sonra görünür.")
            } else {
                val parts = models.map { m ->
                    val limit = prefs.geminiModelLimit(m)
                    val left = if (prefs.geminiModelBlockedUntil(m) > now) 0 else (limit - prefs.geminiModelReqs(m)).coerceAtLeast(0)
                    Triple(m, left, limit)
                }
                val left = parts.sumOf { it.second }
                val total = parts.sumOf { it.third }
                if (left == 0) append("Bugünkü Gemini sınırı doldu (~${chars / 900} dk dinlendi); telefon sesi kullanılıyor.")
                else append("Bugün kalan: $left / $total parça (~${(left * perReq).toInt()} dk Gemini sesi).")
                append("\n")
                append(parts.joinToString(" · ") { (m, l, t) -> "${label(m)}: $l/$t" })
            }
            append("\nSınır her gün Türkiye saatiyle 10:00-11:00 arası yenilenir.")
        }
    }

    /** Anlatıcı tonu sürümü: talimat metni değişince önbellek anahtarı da değişsin. */
    const val PROMPT_VERSION = "anlatici2"

    /**
     * Talimatın sesli okunmasını önlemek için Google'ın önerdiği yapı: notlar ayrı başlıkta,
     * okunacak metin yalnızca "TRANSCRIPT" başlığının altında.
     */
    private fun narratorPrompt(text: String) = """
### DIRECTOR'S NOTES
Style: calm, warm, natural audiobook narrator. Turkish. Steady pace, natural pauses at punctuation.
Speak ONLY the transcript below. Never read these notes or any heading aloud.

### TRANSCRIPT
$text
""".trimIndent()

    /**
     * Metni seslendirip WAV dosyası olarak [out]'a yazar. Her ses modelinin ayrı günlük sınırı var:
     * sırayla denenir (önce Flash, sonra Flash-Lite, sonra diğerleri); sınırı dolan model ertesi güne kadar atlanır.
     * Hepsi dolduysa günlük [QuotaException] fırlatır. [onSwitch]: başka modele geçildi (eski, yeni).
     */
    suspend fun synthesize(
        apiKey: String, text: String, voice: String, narrator: Boolean, out: File,
        prefs: Prefs, onSwitch: ((String, String) -> Unit)? = null, onlyLite: Boolean = false,
    ): Result = withContext(Dispatchers.IO) {
        val all = models(apiKey, prefs)
        val list = if (onlyLite) all.filter { it.contains("lite") }.ifEmpty { all } else all
        var previous: String? = null
        for (model in list) {
            val now = System.currentTimeMillis()
            if (prefs.geminiModelBlockedUntil(model) > now || prefs.geminiModelLimit(model) <= 0) continue
            if (previous != null) onSwitch?.invoke(previous, model)
            try {
                val r = synthesizeWith(model, apiKey, text, voice, narrator, out)
                prefs.addGeminiUsage(text.length.toLong(), model)
                return@withContext r
            } catch (e: QuotaException) {
                if (!e.daily) throw e
                Kayit.log("Gemini $model günlük sınırı doldu (sınır=${e.limit})")
                prefs.setGeminiModelBlockedUntil(model, nextReset().toInstant().toEpochMilli())
                e.limit?.let { prefs.setGeminiModelLimit(model, it) }
                previous = model
            } catch (e: ModelGoneException) {
                cachedModels = null
                previous = model
            }
        }
        throw QuotaException(true, 0, null, "Gemini ses modellerinin bugünkü ücretsiz sınırı doldu. Yarın yenilenir.")
    }

    private class ModelGoneException : IOException("model yok")

    private suspend fun synthesizeWith(model: String, apiKey: String, text: String, voice: String, narrator: Boolean, out: File): Result =
        withContext(Dispatchers.IO) {
            val prompt = if (narrator) narratorPrompt(text) else text
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put(
                    "generationConfig", JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put(
                            "speechConfig", JSONObject().put(
                                "voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                            )
                        )
                )
            val t0 = System.currentTimeMillis()
            val conn = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 300_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    Kayit.log("GeminiTts $model HTTP $code: ${err.take(400).replace('\n', ' ')}")
                    if (code == 404) throw ModelGoneException()
                    if (code == 429) {
                        val daily = err.contains("PerDay", true) || err.contains("per day", true) || err.contains("RPD", false)
                        val limit = Regex("\"quotaValue\"\\s*:\\s*\"?(\\d+)").find(err)?.groupValues?.get(1)?.toIntOrNull()
                        val retry = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)").find(err)?.groupValues?.get(1)?.toIntOrNull() ?: 60
                        throw QuotaException(daily, retry, limit, "Gemini ses sınırına ulaşıldı (ücretsiz kota). " +
                            if (daily) "Günlük sınır yarın yenilenir." else "Birkaç dakika sonra tekrar deneyin.")
                    }
                    throw IOException(
                        when (code) {
                            429 -> "Gemini ses sınırına ulaşıldı (ücretsiz kota). Birkaç dakika sonra ya da yarın tekrar deneyin."
                            400, 403 -> if (err.contains("API key", true) || err.contains("API_KEY", true))
                                "Gemini anahtarı geçersiz." else "Gemini isteği reddetti ($code)."
                            404 -> "Ses modeli bulunamadı ($model). Tekrar deneyin."
                            else -> "Gemini ses hatası ($code)."
                        }
                    )
                }
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?: throw IOException("Gemini ses üretmedi.")
                var inline: JSONObject? = null
                for (i in 0 until parts.length()) parts.getJSONObject(i).optJSONObject("inlineData")?.let { inline = it }
                val data = inline ?: throw IOException("Gemini ses üretmedi.")
                val pcm = Base64.decode(data.getString("data"), Base64.DEFAULT)
                val rate = Regex("rate=(\\d+)").find(data.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                writeWav(out, pcm, rate)
                val tokens = json.optJSONObject("usageMetadata")?.optInt("candidatesTokenCount", 0) ?: 0
                val finish = json.optJSONArray("candidates")?.optJSONObject(0)?.optString("finishReason").orEmpty()
                Result(out, model, System.currentTimeMillis() - t0, tokens, finish)
            } finally {
                conn.disconnect()
            }
        }

    @Volatile private var cachedModels: List<String>? = null

    /** Gemini günlük kotası Pasifik saatiyle gece yarısı yenilenir. */
    fun nextReset(): ZonedDateTime {
        val la = ZoneId.of("America/Los_Angeles")
        return ZonedDateTime.now(la).toLocalDate().plusDays(1).atStartOfDay(la).plusMinutes(5)
    }

    /** Model adından kısa ad: "gemini-3.8-flash-lite-tts" → "3.8 Flash-Lite". */
    fun label(model: String): String = model.removePrefix("gemini-").replace("-preview", "").replace("-tts", "")
        .split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }.replace("Flash Lite", "Flash-Lite")

    /**
     * Anahtarın erişebildiği TTS modelleri, tercih sırasıyla: Flash (en yeni önce), Flash-Lite, diğerleri.
     */
    private fun models(apiKey: String, prefs: Prefs): List<String> {
        cachedModels?.let { return it }
        val list = try {
            val conn = URL("$BASE/models?pageSize=1000").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("x-goog-api-key", apiKey)
                if (conn.responseCode != 200) null else {
                    val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { m ->
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            m.optString("name").contains("tts", true) &&
                                (methods == null || (0 until methods.length()).any { methods.getString(it) == "generateContent" })
                        }
                        .map { it.optString("name").removePrefix("models/") }
                        .sortedWith(
                            compareBy<String> {
                                when {
                                    it.contains("flash") && !it.contains("lite") -> 0
                                    it.contains("lite") -> 1
                                    else -> 2
                                }
                            }.thenByDescending { it }
                        )
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Kayit.log("GeminiTts model listesi alınamadı: ${e.message}")
            null
        }
        val result = list?.takeIf { it.isNotEmpty() } ?: prefs.geminiModels.takeIf { it.isNotEmpty() } ?: listOf(FALLBACK_MODEL)
        if (list != null) {
            Kayit.log("GeminiTts modeller: $list")
            prefs.geminiModels = result
            cachedModels = result
        }
        return result
    }

    private fun writeWav(out: File, pcm: ByteArray, rate: Int) {
        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0)
            fun i32(v: Int) { f.write(v and 0xff); f.write(v shr 8 and 0xff); f.write(v shr 16 and 0xff); f.write(v shr 24 and 0xff) }
            fun i16(v: Int) { f.write(v and 0xff); f.write(v shr 8 and 0xff) }
            f.writeBytes("RIFF"); i32(36 + pcm.size); f.writeBytes("WAVE")
            f.writeBytes("fmt "); i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16)
            f.writeBytes("data"); i32(pcm.size); f.write(pcm)
        }
    }
}
