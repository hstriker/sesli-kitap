package com.seslikitap.data

import android.content.Context
import com.seslikitap.core.TurkishText
import org.json.JSONObject
import java.io.File

/** Kitap günlüğü: başlama/bitirme tarihi, puan, not. Telefonda JSON dosyasında (veritabanı şeması değişmesin). */
object Journal {
    data class Entry(val started: Long? = null, val finished: Long? = null, val rating: Int = 0, val note: String = "")

    private fun file(context: Context) = File(context.filesDir, "kitap-gunlugu.json")
    private val lock = Any()

    fun all(context: Context): Map<Long, Entry> = synchronized(lock) {
        val f = file(context)
        if (!f.exists()) return emptyMap()
        return try {
            val o = JSONObject(f.readText())
            o.keys().asSequence().mapNotNull { k ->
                val e = o.getJSONObject(k)
                k.toLongOrNull()?.let {
                    it to Entry(
                        e.optLong("basla").takeIf { v -> v > 0 },
                        e.optLong("bitir").takeIf { v -> v > 0 },
                        e.optInt("puan"),
                        e.optString("not"),
                    )
                }
            }.toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun get(context: Context, bookId: Long): Entry = all(context)[bookId] ?: Entry()

    fun update(context: Context, bookId: Long, change: (Entry) -> Entry) = synchronized(lock) {
        val map = all(context).toMutableMap()
        map[bookId] = change(map[bookId] ?: Entry())
        val o = JSONObject()
        map.forEach { (id, e) ->
            o.put(id.toString(), JSONObject().put("basla", e.started ?: 0).put("bitir", e.finished ?: 0).put("puan", e.rating).put("not", e.note))
        }
        file(context).writeText(o.toString())
    }

    fun markStarted(context: Context, bookId: Long) {
        if (get(context, bookId).started == null) update(context, bookId) { it.copy(started = System.currentTimeMillis()) }
    }

    fun markFinished(context: Context, bookId: Long) = update(context, bookId) { it.copy(finished = System.currentTimeMillis()) }
}

/** Kitaplık araçlarının yardımcıları. */
object LibraryTools {

    /** Başlık karşılaştırma anahtarı: büyük-küçük harf, noktalama, baskı/kopya ekleri yok sayılır. */
    fun titleKey(title: String): String = TurkishText.searchKey(
        title.replace(Regex("\\((\\d+|kopya|copy)\\)", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
    ).replace(Regex("\\s+"), " ").trim()

    /** Aynı kitabın birden fazla kopyası (aynı başlık + aynı ya da boş yazar). */
    fun duplicates(books: List<BookEntity>): List<List<BookEntity>> =
        books.filter { it.status == BookStatus.OK }
            .groupBy { titleKey(it.title) + "|" + TurkishText.searchKey(it.author).take(12) }
            .values.filter { it.size > 1 && titleKey(it[0].title).length >= 3 }
            .sortedBy { it[0].title }

    data class SeriesInfo(val name: String, val books: List<BookEntity>, val missing: List<Int>)

    /** Kitaplıktaki seriler ve sıra numarasına göre aradaki eksikler. */
    fun series(books: List<BookEntity>): List<SeriesInfo> =
        books.filter { it.series != null && it.status == BookStatus.OK }
            .groupBy { it.series!! }
            .map { (name, list) ->
                val sorted = list.sortedBy { it.seriesIndex ?: 999.0 }
                val nums = sorted.mapNotNull { it.seriesIndex?.toInt() }.filter { it in 1..200 }.toSet()
                val missing = if (nums.isEmpty()) emptyList() else (1..nums.max()).filter { it !in nums }
                SeriesInfo(name, sorted, missing)
            }
            .sortedWith(compareByDescending<SeriesInfo> { it.missing.isNotEmpty() }.thenBy { it.name })

    /** Kalan dinleme süresi tahmini (ms): bölüm uzunlukları biliniyorsa onlardan, yoksa dosya boyutundan. */
    fun remainingMs(context: Context, b: BookEntity, charsPerSecond: Float, rate: Float): Long? {
        val chars = com.seslikitap.player.ChapterLengthStore.load(context, b.id)?.sum()?.toLong()
            ?: if (b.fileName.endsWith(".pdf", true)) null else (b.sizeBytes * 1.1).toLong()
        chars ?: return null
        val left = chars * (1f - b.progress.coerceIn(0f, 1f))
        return (left / (charsPerSecond * rate.coerceAtLeast(0.3f)) * 1000).toLong()
    }
}
