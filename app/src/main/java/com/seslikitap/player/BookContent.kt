package com.seslikitap.player

import com.seslikitap.core.ChapterTextExtractor
import com.seslikitap.core.EpubBook
import com.seslikitap.core.ExtractOptions
import com.seslikitap.core.SentenceSplitter
import com.seslikitap.core.TextBlock
import com.seslikitap.core.ZipArchive
import java.io.Closeable

/** Bir bölümdeki cümle: hangi paragrafta, paragrafın neresinde. */
data class Sentence(val blockIndex: Int, val start: Int, val end: Int)

class Chapter(
    val index: Int,
    val title: String,
    val blocks: List<TextBlock>,
    val sentences: List<Sentence>,
    /** HTML id -> cümle indeksi (içindekiler bağlantıları için). */
    private val anchorToSentence: Map<String, Int>,
) {
    fun text(i: Int): String = sentences[i].let { blocks[it.blockIndex].text.substring(it.start, it.end) }

    fun sentenceForAnchor(anchor: String?): Int = anchor?.let { anchorToSentence[it] } ?: 0

    /** Paragrafın i'nci cümlesinin bölüm içindeki indeksi. */
    fun sentenceIndexOf(blockIndex: Int, sentenceInBlock: Int): Int {
        val first = sentences.indexOfFirst { it.blockIndex >= blockIndex }
        if (first < 0) return (sentences.size - 1).coerceAtLeast(0)
        val target = first + sentenceInBlock
        return if (target < sentences.size && sentences[target].blockIndex == sentences[first].blockIndex) target else first
    }

    fun sentenceInBlock(i: Int): Int {
        val b = sentences[i].blockIndex
        var k = i
        while (k > 0 && sentences[k - 1].blockIndex == b) k--
        return i - k
    }

    val isEmpty get() = sentences.isEmpty()
}

/**
 * Açık bir kitap. Arşivi açık tutar, bölümleri ihtiyaç oldukça yükler ve küçük bir önbellekte saklar.
 */
class BookContent(
    val bookId: Long,
    val epub: EpubBook,
    private val zip: ZipArchive,
    private val options: ExtractOptions,
) : Closeable {

    val chapterCount get() = epub.spine.size
    private val cache = object : LinkedHashMap<Int, Chapter>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Chapter>?) = size > 4
    }

    /** Bölüm başlığı: içindekilerdeki ilk eşleşme, yoksa bölümün ilk başlığı. */
    fun chapterTitle(index: Int): String =
        epub.toc.firstOrNull { it.spineIndex == index }?.title ?: "Bölüm ${index + 1}"

    @Synchronized
    fun chapter(index: Int): Chapter {
        cache[index]?.let { return it }
        val ch = load(index)
        cache[index] = ch
        return ch
    }

    private fun load(index: Int): Chapter {
        val bytes = zip.read(epub.spine[index])
        if (bytes == null) return Chapter(index, chapterTitle(index), emptyList(), emptyList(), emptyMap())
        val text = try {
            ChapterTextExtractor.extract(bytes, options)
        } catch (e: Throwable) {
            return Chapter(index, chapterTitle(index), emptyList(), emptyList(), emptyMap())
        }
        val sentences = ArrayList<Sentence>()
        val anchors = HashMap<String, Int>()
        text.blocks.forEachIndexed { bi, block ->
            val first = sentences.size
            block.anchors.forEach { anchors.putIfAbsent(it, first) }
            SentenceSplitter.split(block.text).forEach { sentences.add(Sentence(bi, it.start, it.end)) }
            if (sentences.size == first) {
                // Bu paragraftan cümle çıkmadı: bağlantılar sonraki cümleye işaret etsin.
                block.anchors.forEach { anchors[it] = first }
            }
        }
        val title = epub.toc.firstOrNull { it.spineIndex == index }?.title ?: text.title ?: "Bölüm ${index + 1}"
        return Chapter(index, title, text.blocks, sentences, anchors)
    }

    /** [from] dahil, [direction] yönünde ilk boş olmayan bölüm; yoksa null. */
    fun nextNonEmpty(from: Int, direction: Int = 1): Int? {
        var i = from
        while (i in 0 until chapterCount) {
            if (!chapter(i).isEmpty) return i
            i += direction
        }
        return null
    }

    /** Bölümün seslendirilecek karakter sayısı. */
    fun chapterLength(index: Int): Int = chapter(index).let { ch -> ch.sentences.sumOf { it.end - it.start } }

    /** Paragrafın ilk cümlesi; zaten oradaysa bir önceki paragrafın ilk cümlesi ("kaçırdım"). */
    fun paragraphStart(ch: Chapter, sentence: Int): Int {
        if (ch.sentences.isEmpty()) return 0
        val s = sentence.coerceIn(0, ch.sentences.size - 1)
        val first = s - ch.sentenceInBlock(s)
        if (s - first >= 1) return first
        if (first == 0) return 0
        return (first - 1) - ch.sentenceInBlock(first - 1)
    }

    override fun close() = zip.close()
}

/**
 * Kitapların bölüm uzunlukları (karakter). Bir kez hesaplanır, dosyada saklanır;
 * kalan süre ve tahmini dinleme süresi için kullanılır.
 */
object ChapterLengthStore {
    private fun file(context: android.content.Context, bookId: Long) =
        java.io.File(java.io.File(context.filesDir, "uzunluk").apply { mkdirs() }, "$bookId.txt")

    fun load(context: android.content.Context, bookId: Long): IntArray? = try {
        file(context, bookId).takeIf { it.exists() }?.readText()?.split(',')?.map { it.trim().toInt() }?.toIntArray()
    } catch (e: Exception) {
        null
    }

    fun save(context: android.content.Context, bookId: Long, lengths: IntArray) {
        try { file(context, bookId).writeText(lengths.joinToString(",")) } catch (_: Exception) {}
    }

    /** Kayıtlıysa onu, değilse hesaplayıp kaydeder (arka planda çağırın). */
    fun getOrCompute(context: android.content.Context, content: BookContent): IntArray {
        load(context, content.bookId)?.takeIf { it.size == content.chapterCount }?.let { return it }
        val lens = IntArray(content.chapterCount) { i -> runCatching { content.chapterLength(i) }.getOrDefault(0) }
        save(context, content.bookId, lens)
        return lens
    }
}

/** Ms cinsinden süreyi "6 sa 20 dk" / "14 dk" biçiminde yazar. */
fun formatDuration(ms: Long): String {
    val min = (ms / 60_000L).coerceAtLeast(if (ms > 0) 1L else 0L)
    return if (min < 60) "$min dk" else "${min / 60} sa ${min % 60} dk"
}
