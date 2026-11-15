package com.seslikitap.core

import java.io.IOException

/**
 * Uzak bir dosyayı (ör. Google Drive) parça parça okuyan kaynak. Okunan bloklar bellekte tutulur,
 * eksik ardışık bloklar tek istekte ve biraz ileri okuma ile alınır. Böylece bir EPUB'ın başlık/kapak
 * bilgisi dosyanın tamamı indirilmeden birkaç istekle okunur.
 *
 * [fetch]: (başlangıç, uzunluk) -> en az o kadar bayt (dosya sonunda daha az olabilir).
 */
class CachedRangeSource(
    override val size: Long,
    private val blockSize: Int = 64 * 1024,
    private val readAheadBlocks: Int = 3,
    private val maxCachedBlocks: Int = 160,
    private val fetch: (start: Long, length: Int) -> ByteArray,
) : RandomAccessSource {

    private val blocks = object : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxCachedBlocks
    }

    /** Yapılan uzak istek sayısı (test ve günlük için). */
    var requestCount = 0
        private set

    @Synchronized
    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        if (len == 0) return
        if (position < 0 || position + len > size) throw IOException("Dosya beklenenden kısa")
        val first = position / blockSize
        val last = (position + len - 1) / blockSize
        var b = first
        while (b <= last) {
            if (blocks.containsKey(b)) { b++; continue }
            // b'den başlayan eksik blok dizisini (ve ileri okumayı) tek istekte al
            var end = b
            while (end + 1 <= last && !blocks.containsKey(end + 1)) end++
            val lastBlock = (size - 1) / blockSize
            var ahead = 0
            while (ahead < readAheadBlocks && end + 1 <= lastBlock && !blocks.containsKey(end + 1)) { end++; ahead++ }
            load(b, end)
            b = end + 1
        }
        var copied = 0
        var pos = position
        while (copied < len) {
            val bi = pos / blockSize
            val data = blocks[bi] ?: run { load(bi, bi); blocks.getValue(bi) }
            val inBlock = (pos - bi * blockSize).toInt()
            val n = minOf(len - copied, data.size - inBlock)
            if (n <= 0) throw IOException("Uzak dosya beklenenden kısa")
            System.arraycopy(data, inBlock, dst, off + copied, n)
            copied += n
            pos += n
        }
    }

    private fun load(fromBlock: Long, toBlock: Long) {
        val start = fromBlock * blockSize
        val endExclusive = minOf(size, (toBlock + 1) * blockSize)
        val length = (endExclusive - start).toInt()
        requestCount++
        val data = fetch(start, length)
        if (data.size < length) throw IOException("Uzak dosyadan eksik veri geldi (${data.size}/$length)")
        var i = fromBlock
        while (i <= toBlock) {
            val s = ((i - fromBlock) * blockSize).toInt()
            val e = minOf(data.size, s + blockSize)
            if (s < e) blocks[i] = data.copyOfRange(s, e)
            i++
        }
    }

    override fun close() {
        synchronized(this) { blocks.clear() }
    }
}
