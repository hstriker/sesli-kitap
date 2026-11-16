package com.seslikitap.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Huzur sekmesinin küçük kayıtları: telefonda JSON dosyalarında (veritabanı şeması değişmesin). */
object CalmStore {
    private val lock = Any()

    private fun file(context: Context, name: String) = File(File(context.filesDir, "huzur").apply { mkdirs() }, "$name.json")

    fun list(context: Context, name: String): List<JSONObject> = synchronized(lock) {
        val f = file(context, name)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).map { a.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, name: String, items: List<JSONObject>) = synchronized(lock) {
        val a = JSONArray()
        items.forEach { a.put(it) }
        file(context, name).writeText(a.toString())
    }

    fun add(context: Context, name: String, item: JSONObject) = save(context, name, list(context, name) + item)

    // Kapanış kelimeleri: {"t": zaman, "w": kelime, "b": kitap}
    const val WORDS = "kelimeler"
    // Geleceğe mektuplar: {"id", "t": yazıldı, "open": açılacak, "text", "opened": bool}
    const val LETTERS = "mektuplar"
    // Ortak hikâyeler: {"t", "title", "text"}
    const val STORIES = "hikayeler"
    // Kelime defteri: {"t", "w": kelime, "m": anlam, "b": kitap}
    const val VOCAB = "kelime-defteri"
    // Yürüyüşler: {"t", "ms", "steps", "chapters", "book"}
    const val WALKS = "yuruyusler"

    fun notesDir(context: Context) = File(context.filesDir, "aksam-notlari").apply { mkdirs() }
}
