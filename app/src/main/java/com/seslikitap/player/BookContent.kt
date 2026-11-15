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

    override fun close() = zip.close()
}
