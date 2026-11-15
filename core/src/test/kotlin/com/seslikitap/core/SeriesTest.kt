package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeriesTest {
    @Test
    fun guessesFromTitle() {
        assertEquals("Dune" to 2.0, SeriesGuess.fromTitle("Dune 2. Kitap: Mesih"))
        assertEquals("Yüzüklerin Efendisi" to 3.0, SeriesGuess.fromTitle("Yüzüklerin Efendisi - Cilt 3"))
        assertEquals("Vakıf Serisi" to 4.0, SeriesGuess.fromTitle("Vakıf Serisi 4"))
        assertEquals("Açlık Oyunları" to 2.0, SeriesGuess.fromTitle("Açlık Oyunları #2"))
        assertEquals("Dune" to 1.0, SeriesGuess.fromTitle("1. Kitap: Dune"))
    }

    @Test
    fun doesNotGuessOrdinaryTitles() {
        assertNull(SeriesGuess.fromTitle("Kürk Mantolu Madonna"))
        assertNull(SeriesGuess.fromTitle("1984"))
        assertNull(SeriesGuess.fromTitle("Saatleri Ayarlama Enstitüsü"))
        assertNull(SeriesGuess.fromTitle("Bir İdam Mahkumunun Son Günü"))
    }

    @Test
    fun readsCalibreSeriesMeta() {
        val zip = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("c.opf"))
            .file("c.opf", """<package><metadata><dc:title>Dune Mesihi</dc:title>
<meta name="calibre:series" content="Dune"/><meta name="calibre:series_index" content="2.0"/></metadata>
<manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
<spine><itemref idref="a"/></spine></package>""")
            .file("a.xhtml", TestEpub.xhtml("<p>x</p>"))
            .open()
        val b = EpubParser.parse(zip, "x.epub")
        assertEquals("Dune", b.series)
        assertEquals(2.0, b.seriesIndex!!, 0.0)
    }
}
