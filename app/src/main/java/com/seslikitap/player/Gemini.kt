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

    private const val MODEL = "gemini-2.5-flash"

    suspend fun generate(apiKey: String, prompt: String): String = withContext(Dispatchers.IO) {
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.4))
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                Kayit.log("Gemini HTTP $code: ${err.take(300).replace('\n', ' ')}")
                throw IOException(
                    when (code) {
                        400, 403 -> if (err.contains("API_KEY", true) || err.contains("API key", true))
                            "Gemini anahtarı geçersiz. Ayarlar → Yapay zekâ özeti'nden kontrol edin." else "Gemini isteği reddetti ($code)"
                        429 -> "Gemini kullanım sınırına ulaşıldı; birkaç dakika sonra tekrar deneyin."
                        else -> "Gemini hatası ($code)"
                    }
                )
            }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val parts = json.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IOException("Gemini boş yanıt verdi")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }.trim()
        } finally {
            conn.disconnect()
        }
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
