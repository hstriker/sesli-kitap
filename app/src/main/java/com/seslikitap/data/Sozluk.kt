package com.seslikitap.data

import com.seslikitap.Kayit
import com.seslikitap.core.TurkishText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SozlukSonuc(val madde: String, val anlamlar: List<String>)

/**
 * TDK Güncel Türkçe Sözlük. Ekli kelimelerde ("kitaplarından") sonu kısaltılarak kök aranır.
 */
object Sozluk {

    private val cache = HashMap<String, SozlukSonuc?>()

    suspend fun ara(kelime: String): SozlukSonuc? = withContext(Dispatchers.IO) {
        val base = TurkishText.cleanWhitespace(kelime)
            .substringBefore('\'').substringBefore('’')
            .trim { !it.isLetter() }
            .lowercase(TurkishText.TR)
        if (base.length < 2) return@withContext null
        // Tam kelime, sonra sondan kısaltarak (en az 3 harf kalana kadar)
        val adaylar = buildList {
            add(base)
            var n = base.length - 1
            while (n >= 3 && size < 8) { add(base.substring(0, n)); n-- }
        }
        for (a in adaylar) {
            synchronized(cache) { if (cache.containsKey(a)) cache[a]?.let { return@withContext it } }
            val r = try { sorgula(a) } catch (e: Exception) {
                Kayit.error("Sözlük hatası", e)
                throw e
            }
            synchronized(cache) { cache[a] = r }
            if (r != null) return@withContext r
        }
        null
    }

    private fun sorgula(kelime: String): SozlukSonuc? {
        val conn = URL("https://sozluk.gov.tr/gts?ano=" + URLEncoder.encode(kelime, "UTF-8")).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            val body = conn.inputStream.bufferedReader().readText().trim()
            if (!body.startsWith("[")) return null // {"error":"Sonuç bulunamadı"}
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val first = arr.getJSONObject(0)
            val anlamlar = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val liste = arr.getJSONObject(i).optJSONArray("anlamlarListe") ?: continue
                for (j in 0 until liste.length()) {
                    val a: JSONObject = liste.getJSONObject(j)
                    a.optString("anlam").takeIf { it.isNotBlank() }?.let { anlamlar.add(it) }
                }
            }
            return SozlukSonuc(first.optString("madde", kelime), anlamlar.take(6))
        } finally {
            conn.disconnect()
        }
    }
}
