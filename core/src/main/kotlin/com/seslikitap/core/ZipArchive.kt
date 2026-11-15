package com.seslikitap.core

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/** Rastgele erişimli bayt kaynağı (dosya kanalı ya da bellekteki dizi). */
interface RandomAccessSource : Closeable {
    val size: Long
    fun readFully(position: Long, dst: ByteArray, off: Int = 0, len: Int = dst.size - off)
}

class FileChannelSource(
    private val channel: FileChannel,
    private val onClose: () -> Unit = { channel.close() },
) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        val buf = ByteBuffer.wrap(dst, off, len)
        var pos = position
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw IOException("Dosya beklenenden kısa")
            pos += n
        }
    }

    override fun close() = onClose()

    companion object {
        fun open(path: String): FileChannelSource {
            val raf = RandomAccessFile(path, "r")
            return FileChannelSource(raf.channel) { raf.close() }
        }
    }
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()
    override fun readFully(position: Long, dst: ByteArray, off: Int, len: Int) {
        if (position < 0 || position + len > data.size) throw IOException("Dosya beklenenden kısa")
        System.arraycopy(data, position.toInt(), dst, off, len)
    }
    override fun close() {}
}

class ZipFormatException(message: String) : IOException(message)

/**
 * Kendi ZIP okuyucumuz. java.util.zip.ZipFile'a göre avantajları:
 *  - Dosya yolu gerektirmez; Android'in SAF (content://) kaynaklarındaki kanallarla çalışır.
 *  - UTF-8 olmayan (eski Türkçe/DOS) dosya adlarında çökmez.
 *  - Merkezi dizini bozuk dosyalarda sıralı okumaya geri düşer.
 *  - Büyük harf/küçük harf ve %20 gibi kodlanmış yol farklarını tolere eder.
 */
class ZipArchive private constructor(
    private val source: RandomAccessSource?,
    private val indexed: Map<String, CdEntry>,
    private val inMemory: Map<String, ByteArray>,
) : Closeable {

    internal class CdEntry(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
    )

    /** Dizinler hariç tüm girdi adları (orijinal sırada değil). */
    val entryNames: List<String> =
        (if (source != null) indexed.keys else inMemory.keys).filter { !it.endsWith("/") }

    private val lowerIndex: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        for (n in entryNames) m.putIfAbsent(n.lowercase(), n)
        m
    }

    /** Verilen yolu gerçek girdi adına çevirir; bulunamazsa null. */
    fun findEntry(path: String): String? {
        val clean = path.trimStart('/')
        if (contains(clean)) return clean
        lowerIndex[clean.lowercase()]?.let { return it }
        val decoded = PathUtil.percentDecode(clean)
        if (decoded != clean) {
            if (contains(decoded)) return decoded
            lowerIndex[decoded.lowercase()]?.let { return it }
        }
        return null
    }

    private fun contains(name: String) =
        if (source != null) indexed.containsKey(name) else inMemory.containsKey(name)

    fun exists(path: String) = findEntry(path) != null

    /** Girdiyi okur. Bulunamazsa ya da okunamazsa null döner (istisna fırlatmaz). */
    fun read(path: String, maxBytes: Int = MAX_ENTRY_BYTES): ByteArray? {
        val name = findEntry(path) ?: return null
        return try {
            if (source == null) inMemory[name] else readIndexed(indexed.getValue(name), maxBytes)
        } catch (e: Exception) {
            null
        }
    }

    /** Okuma hatasını yutmadan okur (hata mesajı göstermek için). */
    fun readOrThrow(path: String, maxBytes: Int = MAX_ENTRY_BYTES): ByteArray {
        val name = findEntry(path) ?: throw IOException("Dosya bulunamadı: $path")
        return if (source == null) inMemory.getValue(name) else readIndexed(indexed.getValue(name), maxBytes)
    }

    private fun readIndexed(e: CdEntry, maxBytes: Int): ByteArray {
        val src = source!!
        val lh = ByteArray(30)
        src.readFully(e.localHeaderOffset, lh)
        if (le32(lh, 0) != LOCAL_SIG) throw ZipFormatException("Bozuk yerel başlık: ${e.name}")
        val nameLen = le16(lh, 26)
        val extraLen = le16(lh, 28)
        val dataStart = e.localHeaderOffset + 30 + nameLen + extraLen
        val avail = src.size - dataStart
        val compSize = if (e.compressedSize in 0..avail) e.compressedSize else avail
        if (compSize > Int.MAX_VALUE - 16) throw ZipFormatException("Girdi çok büyük: ${e.name}")
        return when (e.method) {
            0 -> {
                if (compSize > maxBytes) throw ZipFormatException("Girdi çok büyük: ${e.name}")
                ByteArray(compSize.toInt()).also { src.readFully(dataStart, it) }
            }
            8 -> inflate(src, dataStart, compSize, e, maxBytes)
            else -> throw ZipFormatException("Desteklenmeyen sıkıştırma (${e.method}): ${e.name}")
        }
    }

    private fun inflate(src: RandomAccessSource, start: Long, compSize: Long, e: CdEntry, maxBytes: Int): ByteArray {
        val inflater = Inflater(true)
        try {
            val expected = e.uncompressedSize
            val out = ByteArrayOutputStream(if (expected in 1..maxBytes.toLong()) expected.toInt() else 16 * 1024)
            val inBuf = ByteArray(64 * 1024)
            val outBuf = ByteArray(64 * 1024)
            var pos = start
            val end = start + compSize
            while (!inflater.finished()) {
                if (inflater.needsInput()) {
                    if (pos >= end) {
                        // Bazı sıkıştırıcılar fazladan bir boş bayt bekletir.
                        inflater.setInput(ByteArray(1))
                    } else {
                        val n = minOf(inBuf.size.toLong(), end - pos).toInt()
                        src.readFully(pos, inBuf, 0, n)
                        pos += n
                        inflater.setInput(inBuf, 0, n)
                    }
                }
                val n = inflater.inflate(outBuf)
                if (n == 0 && inflater.needsDictionary()) throw ZipFormatException("Sözlük gerekli: ${e.name}")
                if (n == 0 && inflater.needsInput() && pos >= end) {
                    // Veri bitti ama akış bitmedi: elde olanı döndür (kırpılmış dosya toleransı).
                    if (out.size() > 0) break
                    throw ZipFormatException("Kırpılmış veri: ${e.name}")
                }
                out.write(outBuf, 0, n)
                if (out.size() > maxBytes) throw ZipFormatException("Girdi çok büyük: ${e.name}")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    override fun close() {
        source?.close()
    }

    companion object {
        const val MAX_ENTRY_BYTES = 64 * 1024 * 1024
        private const val MAX_TOTAL_IN_MEMORY = 512L * 1024 * 1024
        private const val LOCAL_SIG = 0x04034b50L
        private const val CEN_SIG = 0x02014b50L
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_LOC_SIG = 0x07064b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L

        /**
         * Arşivi açar. Başarısız olursa [source] kapatılır ve istisna fırlatılır.
         */
        fun open(source: RandomAccessSource): ZipArchive {
            try {
                val index = try {
                    readCentralDirectory(source)
                } catch (e: Exception) {
                    null
                }
                if (index != null && index.isNotEmpty()) return ZipArchive(source, index, emptyMap())
                // Merkezi dizin yok/bozuk: baştan sona sıralı oku.
                val mem = readSequential(SourceInputStream(source))
                source.close()
                if (mem.isEmpty()) throw ZipFormatException("Geçerli bir EPUB/ZIP dosyası değil")
                return ZipArchive(null, emptyMap(), mem)
            } catch (e: Exception) {
                try { source.close() } catch (_: Exception) {}
                throw if (e is IOException) e else IOException(e.message ?: "ZIP okunamadı", e)
            }
        }

        /** Sadece akış olarak okunabilen kaynaklar için (tamamı belleğe alınır). */
        fun openStream(input: InputStream): ZipArchive {
            val mem = readSequential(input)
            if (mem.isEmpty()) throw ZipFormatException("Geçerli bir EPUB/ZIP dosyası değil")
            return ZipArchive(null, emptyMap(), mem)
        }

        fun openFile(path: String): ZipArchive = open(FileChannelSource.open(path))

        private fun readCentralDirectory(src: RandomAccessSource): Map<String, CdEntry>? {
            val size = src.size
            if (size < 22) return null
            val tailLen = minOf(size, 65_557L).toInt()
            val tail = ByteArray(tailLen)
            src.readFully(size - tailLen, tail)
            var eocd = -1
            for (i in tailLen - 22 downTo 0) {
                if (le32(tail, i) == EOCD_SIG) { eocd = i; break }
            }
            if (eocd < 0) return null
            var total = le16(tail, eocd + 10).toLong()
            var cdSize = le32(tail, eocd + 12)
            var cdOffset = le32(tail, eocd + 16)
            val eocdAbs = size - tailLen + eocd

            if (cdOffset == 0xFFFFFFFFL || cdSize == 0xFFFFFFFFL || total == 0xFFFFL) {
                // ZIP64
                if (eocdAbs >= 20) {
                    val loc = ByteArray(20)
                    src.readFully(eocdAbs - 20, loc)
                    if (le32(loc, 0) == ZIP64_LOC_SIG) {
                        val z64 = le64(loc, 8)
                        val rec = ByteArray(56)
                        src.readFully(z64, rec)
                        if (le32(rec, 0) == ZIP64_EOCD_SIG) {
                            total = le64(rec, 32)
                            cdSize = le64(rec, 40)
                            cdOffset = le64(rec, 48)
                        }
                    }
                }
            }
            // Başına veri eklenmiş arşivlerde ofset kayabilir.
            var shift = 0L
            if (cdOffset + cdSize != eocdAbs && eocdAbs - cdSize >= 0) {
                val probe = ByteArray(4)
                src.readFully(eocdAbs - cdSize, probe)
                if (le32(probe, 0) == CEN_SIG) shift = (eocdAbs - cdSize) - cdOffset
            }
            val start = cdOffset + shift
            if (start < 0 || cdSize <= 0 || start + cdSize > size || cdSize > 64L * 1024 * 1024) return null
            val cd = ByteArray(cdSize.toInt())
            src.readFully(start, cd)

            val result = LinkedHashMap<String, CdEntry>()
            var p = 0
            while (p + 46 <= cd.size && le32(cd, p) == CEN_SIG) {
                val flags = le16(cd, p + 8)
                val method = le16(cd, p + 10)
                var comp = le32(cd, p + 20)
                var uncomp = le32(cd, p + 24)
                val nameLen = le16(cd, p + 28)
                val extraLen = le16(cd, p + 30)
                val commentLen = le16(cd, p + 32)
                var lho = le32(cd, p + 42)
                if (p + 46 + nameLen > cd.size) break
                val name = decodeName(cd, p + 46, nameLen, flags and 0x800 != 0)
                // ZIP64 ek alanı
                var ep = p + 46 + nameLen
                val eEnd = minOf(ep + extraLen, cd.size)
                while (ep + 4 <= eEnd) {
                    val id = le16(cd, ep)
                    val len = le16(cd, ep + 2)
                    if (id == 0x0001) {
                        var q = ep + 4
                        if (uncomp == 0xFFFFFFFFL && q + 8 <= eEnd) { uncomp = le64(cd, q); q += 8 }
                        if (comp == 0xFFFFFFFFL && q + 8 <= eEnd) { comp = le64(cd, q); q += 8 }
                        if (lho == 0xFFFFFFFFL && q + 8 <= eEnd) { lho = le64(cd, q) }
                    }
                    ep += 4 + len
                }
                val normalized = name.replace('\\', '/').trimStart('/')
                if (normalized.isNotEmpty()) {
                    result.putIfAbsent(normalized, CdEntry(normalized, method, comp, uncomp, lho + shift))
                }
                p += 46 + nameLen + extraLen + commentLen
            }
            return result
        }

        private fun readSequential(input: InputStream): Map<String, ByteArray> {
            val result = LinkedHashMap<String, ByteArray>()
            var total = 0L
            try {
                ZipInputStream(input.buffered(), Charsets.ISO_8859_1).use { zis ->
                    while (true) {
                        val entry = try { zis.nextEntry } catch (e: Exception) { null } ?: break
                        if (entry.isDirectory) continue
                        val bytes = try { readLimited(zis, MAX_ENTRY_BYTES) } catch (e: Exception) { null } ?: continue
                        total += bytes.size
                        if (total > MAX_TOTAL_IN_MEMORY) break
                        val raw = entry.name.toByteArray(Charsets.ISO_8859_1)
                        val name = decodeName(raw, 0, raw.size, false).replace('\\', '/').trimStart('/')
                        result.putIfAbsent(name, bytes)
                    }
                }
            } catch (e: Exception) {
                // kısmen okunanlarla devam
            }
            return result
        }

        private fun readLimited(input: InputStream, max: Int): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) return null
            }
            return out.toByteArray()
        }

        private val fallbackNameCharset: Charset = try {
            Charset.forName("IBM857") // Türkçe DOS kod sayfası
        } catch (e: Exception) {
            Charsets.ISO_8859_1
        }

        private fun decodeName(b: ByteArray, off: Int, len: Int, utf8Flag: Boolean): String {
            if (utf8Flag) return String(b, off, len, Charsets.UTF_8)
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(b, off, len)).toString()
            } catch (e: CharacterCodingException) {
                String(b, off, len, fallbackNameCharset)
            }
        }

        private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
        private fun le32(b: ByteArray, o: Int): Long =
            (le16(b, o).toLong()) or (le16(b, o + 2).toLong() shl 16)
        private fun le64(b: ByteArray, o: Int): Long = le32(b, o) or (le32(b, o + 4) shl 32)
    }

    private class SourceInputStream(private val src: RandomAccessSource) : InputStream() {
        private var pos = 0L
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= src.size) return -1
            val n = minOf(len.toLong(), src.size - pos).toInt()
            src.readFully(pos, b, off, n)
            pos += n
            return n
        }
    }
}
