package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {

    @Test
    fun extractsParagraphsAndSkipsNotesAndPageNumbers() {
        val html = TestEpub.xhtml(
            """
            <h2 id="b1">BİRİNCİ BÖLÜM</h2>
            <p>Ali eve geldi.<sup><a href="#n1" epub:type="noteref">1</a></sup> Kapıyı açtı.</p>
            <span epub:type="pagebreak" id="page12">12</span>
            <p id="feride">Feride&nbsp;ise   bekliyordu.<br/>Yeni satır.</p>
            <aside epub:type="footnote" id="n1"><p>Bu bir dipnottur.</p></aside>
            <p>Bkz. <a href="notes.xhtml#x">[2]</a>sonraki</p>
            """.trimIndent()
        )
        val ch = ChapterTextExtractor.extract(html.toByteArray())
        val texts = ch.blocks.map { it.text }
        assertEquals(
            listOf("BİRİNCİ BÖLÜM", "Ali eve geldi. Kapıyı açtı.", "Feride ise bekliyordu.", "Yeni satır.", "Bkz. sonraki"),
            texts
        )
        assertEquals(BlockKind.HEADING, ch.blocks[0].kind)
        assertEquals("BİRİNCİ BÖLÜM", ch.title)
        assertEquals(2, ch.blockIndexOfAnchor("feride"))
        assertEquals(0, ch.blockIndexOfAnchor("b1"))
    }

    @Test
    fun selfClosingTitleDoesNotSwallowBody() {
        val html = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title/></head>
            <body><p>Merhaba dünya.</p></body></html>"""
        assertEquals("Merhaba dünya.", ChapterTextExtractor.extract(html.toByteArray()).blocks.single().text)
    }

    @Test
    fun turkishSentenceSplitting() {
        val t = "Dr. Ahmet geldi. 1. Dünya Savaşı'nda II. Abdülhamit vb. konular konuşuldu! A. Kadir ne dedi? " +
            "\"Hayır,\" dedi. Sonra... Gitti. 1923. Cumhuriyet ilan edildi. (bkz. s. 25) Son."
        val s = SentenceSplitter.sentences(t)
        assertEquals(
            listOf(
                "Dr. Ahmet geldi.",
                "1. Dünya Savaşı'nda II. Abdülhamit vb. konular konuşuldu!",
                "A. Kadir ne dedi?",
                "\"Hayır,\" dedi.",
                "Sonra...",
                "Gitti.",
                "1923.",
                "Cumhuriyet ilan edildi.",
                "(bkz. s. 25) Son.",
            ),
            s
        )
    }

    @Test
    fun dialogDashAndQuotes() {
        val s = SentenceSplitter.sentences("— Nereye gidiyorsun? — Eve. «Peki.» Tamam.")
        assertEquals(listOf("— Nereye gidiyorsun?", "— Eve.", "«Peki.»", "Tamam."), s)
    }

    @Test
    fun longSentencesAreBroken() {
        val long = (1..60).joinToString(", ") { "kelime$it uzun bir ifade" } + "."
        val parts = SentenceSplitter.sentences(long)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= SentenceSplitter.MAX_LEN })
        assertEquals(long.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun searchKeyIsTurkishInsensitive() {
        val k = TurkishText.searchKey("İSTANBUL")
        assertEquals(k, TurkishText.searchKey("istanbul"))
        assertEquals(k, TurkishText.searchKey("Istanbul"))
        assertEquals(TurkishText.searchKey("Çalıkuşu"), TurkishText.searchKey("calikusu"))
    }

    @Test
    fun decoderHandlesMisdeclaredLatin1() {
        val bytes = "<?xml version=\"1.0\" encoding=\"iso-8859-1\"?><p>İŞĞ ışğ</p>"
            .toByteArray(charset("windows-1254"))
        assertTrue(TextDecoder.decode(bytes).contains("İŞĞ ışğ"))
    }

    @Test
    fun pathResolution() {
        assertEquals("OEBPS/Images/a b.jpg", PathUtil.resolve("OEBPS/Text", "../Images/a%20b.jpg#x"))
        assertEquals("a.xhtml", PathUtil.resolve("", "./a.xhtml"))
        assertEquals("ç.xhtml", PathUtil.resolve("", "%C3%A7.xhtml"))
    }
}
