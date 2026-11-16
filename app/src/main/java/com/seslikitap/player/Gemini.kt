package com.seslikitap.player

import com.seslikitap.Kayit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Gemini API (Google AI Studio anahtarıyla). Anahtar uygulama ayarlarında saklanır, depoya girmez. */
object Gemini {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val FALLBACK = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite")

    @Volatile private var cachedModels: List<String>? = null
    /** Günlük sınırı dolan yazı modelleri: model → ne zamana kadar atlanacağı. */
    private val blocked = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private class ModelUnavailable(val daily: Boolean) : IOException("model kullanılamıyor")

    /**
     * Yazı üretir. Her modelin ayrı günlük sınırı var: önce Flash (en yeni), dolunca Flash-Lite,
     * sonra diğerleri denenir. Hepsi doluysa anlaşılır bir hata verir.
     */
    suspend fun generate(apiKey: String, prompt: String, temperature: Double = 0.4): String = withContext(Dispatchers.IO) {
        val list = models(apiKey)
        var lastError: IOException? = null
        for (model in list) {
            if ((blocked[model] ?: 0L) > System.currentTimeMillis()) continue
            try {
                return@withContext generateWith(model, apiKey, prompt, temperature)
            } catch (e: ModelUnavailable) {
                blocked[model] = if (e.daily) GeminiTts.nextReset().toInstant().toEpochMilli()
                else System.currentTimeMillis() + 60_000L
                Kayit.log("Gemini yazı: $model atlandı (günlük=${e.daily})")
            } catch (e: IOException) {
                lastError = e
                if (e.message?.contains("anahtar", true) == true) throw e
            }
        }
        throw lastError ?: IOException("Gemini'nin bugünkü ücretsiz yazı sınırı doldu; yarın tekrar deneyin.")
    }

    private fun generateWith(model: String, apiKey: String, prompt: String, temperature: Double): String {
        val conn = URL("$BASE/models/$model:generateContent").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", temperature))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Kayit.log("Gemini $model HTTP $code: ${err.take(300).replace('\n', ' ')}")
                when {
                    code == 429 -> throw ModelUnavailable(err.contains("PerDay", true) || err.contains("per day", true))
                    code == 404 -> throw ModelUnavailable(true)
                    (code == 400 || code == 403) && (err.contains("API key", true) || err.contains("API_KEY", true)) ->
                        throw IOException("Gemini anahtarı geçersiz. Ayarlar → Yapay zekâ özeti'nden kontrol edin.")
                    else -> throw IOException("Gemini hatası ($code)")
                }
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini boş yanıt verdi")
            return buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
        } finally {
            conn.disconnect()
        }
    }

    /** Anahtarın erişebildiği yazı modelleri (Flash önce, sonra Flash-Lite), yoksa sabit liste. */
    private fun models(apiKey: String): List<String> {
        cachedModels?.let { return it }
        val found = try {
            val conn = URL("$BASE/models?pageSize=1000").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("x-goog-api-key", apiKey)
                if (conn.responseCode != 200) null else {
                    val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                    val skip = listOf("tts", "image", "live", "audio", "embedding", "vision", "exp", "learnlm", "robotics", "computer")
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                        .filter { m ->
                            val name = m.optString("name")
                            val methods = m.optJSONArray("supportedGenerationMethods")
                            name.contains("flash") && skip.none { name.contains(it, true) } &&
                                (methods == null || (0 until methods.length()).any { methods.getString(it) == "generateContent" })
                        }
                        .map { it.optString("name").removePrefix("models/") }
                        .filter { !it.contains("latest") }
                        .sortedWith(compareBy<String> { if (it.contains("lite")) 1 else 0 }.thenBy { if (it.contains("preview")) 1 else 0 }.thenByDescending { it })
                        .take(6)
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
        val list = (found.orEmpty() + FALLBACK).distinct()
        Kayit.log("Gemini yazı modelleri: $list")
        if (found != null) cachedModels = list
        return list
    }

    /** "Nerede kalmıştık?" isteği. [recent]: kaldığı yere kadar son kısım; sonrası asla gönderilmez. */
    fun recapPrompt(title: String, author: String, chapterTitles: List<String>, recent: String): String = """
Sen bir sesli kitap uygulamasında, okuyucuya kitapta nerede kaldığını hatırlatan yardımcısın.
Kitap: "$title"${if (author.isNotBlank()) " — $author" else ""}
Okuyucunun bitirdiği bölümler: ${chapterTitles.joinToString(", ").take(1500)}

Aşağıda okuyucunun kaldığı yere kadar olan son kısım var. Sadece bu metne ve bitirilen bölümlere dayanarak,
Türkçe, sıcak ve akıcı bir dille 120-180 kelimelik bir "nerede kalmıştık" özeti yaz:
- Önemli karakterleri ve şu an neler olduğunu hatırlat.
- Son birkaç olayı sırayla anlat, en son kaldığı ana kadar.
- Kaldığı yerden SONRA olacaklar hakkında asla tahmin ya da bilgi verme.
- Madde işareti, başlık ya da markdown kullanma; sesli okunacak düz paragraf yaz.

METİN:
$recent
""".trimIndent()
}
