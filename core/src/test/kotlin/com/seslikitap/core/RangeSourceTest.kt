package com.seslikitap.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RangeSourceTest {

    @Test
    fun readsCorrectBytesAcrossBlocks() {
        val data = Random(1).nextBytes(300_000)
        val src = CachedRangeSource(data.size.toLong(), blockSize = 1000) { s, l ->
            data.copyOfRange(s.toInt(), minOf(data.size, s.toInt() + l))
        }
        val buf = ByteArray(5_500)
        src.readFully(123_456, buf)
        assertArrayEquals(data.copyOfRange(123_456, 123_456 + 5_500), buf)
        val tail = ByteArray(10)
        src.readFully(data.size - 10L, tail)
        assertArrayEquals(data.copyOfRange(data.size - 10, data.size), tail)
    }

    /** Büyük bir EPUB'ın sadece meta verisini okumak az istekle olmalı ve dosyanın küçük bir kısmını indirmeli. */
    @Test
    fun parsesEpubMetadataWithFewRequests() {
        val big = Random(2).nextBytes(3_000_000) // sıkıştırılamayan büyük resim
        val epub = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("OEBPS/c.opf"))
            .file("OEBPS/c.opf", """<package><metadata><dc:title>Uzak Kitap</dc:title><dc:creator>Yazar</dc:creator></metadata>
<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
<item id="img" href="resim.jpg" media-type="image/jpeg"/></manifest><spine><itemref idref="a"/></spine></package>""")
            .bytes("OEBPS/resim.jpg", big)
            .file("OEBPS/a.xhtml", TestEpub.xhtml("<p>Merhaba.</p>"))
            .build()
        var bytesFetched = 0L
        val src = CachedRangeSource(epub.size.toLong()) { s, l ->
            bytesFetched += l
            epub.copyOfRange(s.toInt(), minOf(epub.size, s.toInt() + l))
        }
        ZipArchive.open(src).use { zip ->
            val b = EpubParser.parse(zip, "x.epub", includeToc = false)
            assertEquals("Uzak Kitap", b.title)
            assertEquals("Yazar", b.author)
        }
        assertTrue("istek sayısı: ${src.requestCount}", src.requestCount <= 6)
        assertTrue("indirilen: $bytesFetched", bytesFetched < 800_000)
    }
}
