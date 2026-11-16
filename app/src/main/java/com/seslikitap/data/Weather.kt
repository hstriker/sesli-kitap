package com.seslikitap.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Havaya göre öneri: Open-Meteo (ücretsiz, anahtarsız). Konum izni istenmez; kullanıcı şehrini kendisi yazar.
 * Hava durumu saatte bir alınır.
 */
object Weather {
    private fun sp(context: Context) = context.getSharedPreferences(com.seslikitap.Prefs.FILE, Context.MODE_PRIVATE)

    data class City(val name: String, val lat: Double, val lon: Double)
    data class Now(val code: Int, val temp: Double)

    fun city(context: Context): City? = sp(context).getString("weather_city", null)?.split('|')?.let {
        if (it.size == 3) City(it[0], it[1].toDoubleOrNull() ?: return null, it[2].toDoubleOrNull() ?: return null) else null
    }

    fun clearCity(context: Context) = sp(context).edit().remove("weather_city").remove("weather_cache").apply()

    suspend fun setCity(context: Context, query: String): City? = withContext(Dispatchers.IO) {
        val url = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name=" + URLEncoder.encode(query.trim(), "UTF-8")
        val o = JSONObject(get(url)).optJSONArray("results")?.optJSONObject(0) ?: return@withContext null
        val c = City(o.optString("name"), o.optDouble("latitude"), o.optDouble("longitude"))
        sp(context).edit().putString("weather_city", "${c.name}|${c.lat}|${c.lon}").remove("weather_cache").apply()
        c
    }

    suspend fun now(context: Context): Now? = withContext(Dispatchers.IO) {
        val c = city(context) ?: return@withContext null
        val cached = sp(context).getString("weather_cache", null)?.split('|')
        if (cached != null && cached.size == 3 && System.currentTimeMillis() - (cached[0].toLongOrNull() ?: 0) < 3_600_000L) {
            return@withContext Now(cached[1].toInt(), cached[2].toDouble())
        }
        try {
            val o = JSONObject(get("https://api.open-meteo.com/v1/forecast?current=weather_code,temperature_2m&latitude=${c.lat}&longitude=${c.lon}"))
                .getJSONObject("current")
            val n = Now(o.optInt("weather_code"), o.optDouble("temperature_2m"))
            sp(context).edit().putString("weather_cache", "${System.currentTimeMillis()}|${n.code}|${n.temp}").apply()
            n
        } catch (_: Exception) {
            null
        }
    }

    /** (kısa betim, öneri için ruh hâli) */
    fun describe(code: Int): Pair<String, String> = when (code) {
        0, 1 -> "açık ve güneşli" to "güneşli, aydınlık bir gün; ferah, umut dolu bir hikâye"
        2, 3 -> "bulutlu" to "bulutlu, dingin bir gün; düşündüren sakin bir kitap"
        45, 48 -> "sisli" to "sisli, gizemli bir gün; atmosferik, gizemli bir kitap"
        in 51..67, in 80..82 -> "yağmurlu" to "yağmurlu bir gün; içeride, battaniye altında okunacak sıcak bir kitap"
        in 71..77, 85, 86 -> "karlı" to "karlı bir kış günü; uzun, sürükleyici bir roman"
        in 95..99 -> "fırtınalı" to "fırtınalı bir gün; heyecanlı, sürükleyici bir hikâye"
        else -> "değişken" to "sakin bir gün"
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        return try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
    }
}
