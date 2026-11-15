package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTextCleanerTest {
    private val P = PdfTextCleaner.PARAGRAPH

    @Test
    fun removesHeadersFootersAndJoinsHyphenation() {
        val openers = listOf("Raif Efendi", "Hamdi Bey", "Maria Puder", "Genç adam", "Yaşlı kadın")
        val pages = (1..5).map { n ->
            "KÜRK MANTOLU MADONNA\n" +
                "${openers[n - 1]} her sabah büro-\nya gelirdi ve akşama ka-\ndar çalışırdı.$P" +
                "${listOf("Sonra", "Ertesi gün", "Akşam", "Bir süre", "Nihayet")[n - 1]} yeni bir paragraf başlar ve sayfa sonunda\n" +
                "$n"
        }
        val blocks = PdfTextCleaner.clean(pages).map { it.text }
        assertTrue(blocks.none { it.contains("KÜRK MANTOLU") })
        assertEquals("Raif Efendi her sabah büroya gelirdi ve akşama kadar çalışırdı.", blocks[0])
        // Sayfa sonunda yarım kalan paragraf sonraki sayfanın ilk paragrafıyla birleşir
        assertTrue(blocks[1].startsWith("Sonra yeni bir paragraf başlar ve sayfa sonunda Hamdi Bey"))
        assertTrue(blocks.none { it.trim() == "3" })
    }

    @Test
    fun keepsRealDashesAndDetectsHeadings() {
        val pages = listOf("BİRİNCİ BÖLÜM${P}Türk-İslam sentezi ve Ankara-\nİstanbul hattı konuşuldu.")
        val blocks = PdfTextCleaner.clean(pages)
        assertEquals(BlockKind.HEADING, blocks[0].kind)
        assertEquals("BİRİNCİ BÖLÜM", blocks[0].text)
        // Büyük harfle başlayan devam satırında tire korunur (birleşik özel ad)
        assertEquals("Türk-İslam sentezi ve Ankara-İstanbul hattı konuşuldu.", blocks[1].text)
    }
}
