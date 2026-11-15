package com.seslikitap.player

import android.util.Base64
import com.seslikitap.Kayit
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

    @Volatile private var cachedModel: String? = null

    class Result(val file: File, val model: String, val millis: Long)

    /** Metni seslendirip WAV dosyası olarak [out]'a yazar. */
    suspend fun synthesize(apiKey: String, text: String, voice: String, narrator: Boolean, out: File): Result =
        withContext(Dispatchers.IO) {
            val model = cachedModel ?: pickModel(apiKey).also { cachedModel = it }
            val prompt = if (narrator) "Bir sesli kitap anlatıcısı gibi, sakin, sıcak ve doğal bir tonla Türkçe oku:\n$text" else text
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
                conn.readTimeout = 120_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.setRequestProperty("x-goog-api-key", apiKey)
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code != 200) {
                    val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    Kayit.log("GeminiTts $model HTTP $code: ${err.take(400).replace('\n', ' ')}")
                    if (code == 404) cachedModel = null
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
                Result(out, model, System.currentTimeMillis() - t0)
            } finally {
                conn.disconnect()
            }
        }

    /** Anahtarın erişebildiği TTS modelleri arasından birini seçer (flash önce, en yeni ad önce). */
    private fun pickModel(apiKey: String): String = try {
        val conn = URL("$BASE/models?pageSize=1000").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("x-goog-api-key", apiKey)
            if (conn.responseCode != 200) FALLBACK_MODEL else {
                val arr = JSONObject(conn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                val names = (0 until arr.length()).map { arr.getJSONObject(it) }
                    .filter { m ->
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        m.optString("name").contains("tts", true) &&
                            (methods == null || (0 until methods.length()).any { methods.getString(it) == "generateContent" })
                    }
                    .map { it.optString("name").removePrefix("models/") }
                Kayit.log("GeminiTts modeller: $names")
                names.sortedWith(compareBy<String> { !it.contains("flash") }.thenByDescending { it })
                    .firstOrNull() ?: FALLBACK_MODEL
            }
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        Kayit.log("GeminiTts model listesi alınamadı: ${e.message}")
        FALLBACK_MODEL
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
