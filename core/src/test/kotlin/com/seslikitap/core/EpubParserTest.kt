package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class EpubParserTest {

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    @Test
    fun epub3WithNavAndCoverImage() {
        val opf = """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>Kürk Mantolu Madonna</dc:title>
  <dc:creator id="c1">Sabahattin Ali</dc:creator>
  <dc:language>tr</dc:language>
 </metadata>
 <manifest>
  <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
  <item id="kapak" href="Images/kapak%20resmi.jpg" media-type="image/jpeg" properties="cover-image"/>
  <item id="b1" href="Text/bolum1.xhtml" media-type="application/xhtml+xml"/>
  <item id="b2" href="Text/bolum2.xhtml" media-type="application/xhtml+xml"/>
 </manifest>
 <spine><itemref idref="b1"/><itemref idref="b2"/></spine>
</package>"""
        val nav = TestEpub.xhtml("""<nav epub:type="toc"><ol>
  <li><a href="Text/bolum1.xhtml">Birinci Bölüm</a>
    <ol><li><a href="Text/bolum1.xhtml#alt">Alt Başlık</a></li></ol></li>
  <li><a href="Text/bolum2.xhtml">İkinci Bölüm</a></li></ol></nav>""")
        val zip = TestEpub()
            .file("META-INF/container.xml", TestEpub.container())
            .file("OEBPS/content.opf", opf)
            .file("OEBPS/nav.xhtml", nav)
            .bytes("OEBPS/Images/kapak resmi.jpg", png)
            .file("OEBPS/Text/bolum1.xhtml", TestEpub.xhtml("<h1>Bir</h1><p>Metin.</p>"))
            .file("OEBPS/Text/bolum2.xhtml", TestEpub.xhtml("<h1>İki</h1><p>Metin.</p>"))
            .open()

        val book = EpubParser.parse(zip, "dosya.epub")
        assertEquals("Kürk Mantolu Madonna", book.title)
        assertEquals("Sabahattin Ali", book.author)
        assertEquals("tr", book.language)
        assertEquals("OEBPS/Images/kapak resmi.jpg", book.coverPath)
        assertEquals(listOf("OEBPS/Text/bolum1.xhtml", "OEBPS/Text/bolum2.xhtml"), book.spine)
        assertEquals(3, book.toc.size)
        assertEquals("Alt Başlık", book.toc[1].title)
        assertEquals(1, book.toc[1].depth)
        assertEquals("alt", book.toc[1].fragment)
        assertEquals(1, book.toc[2].spineIndex)
        assertFalse(book.drmProtected)
    }

    @Test
    fun epub2WithNcxMetaCoverAndWindows1254() {
        val cp1254 = Charset.forName("windows-1254")
        val opf = """<?xml version="1.0" encoding="windows-1254"?>
<package xmlns="http://www.idpf.org/2007/opf" xmlns:opf="http://www.idpf.org/2007/opf" version="2.0">
 <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
  <dc:title>Çalıkuşu</dc:title>
  <dc:creator opf:role="edt">Bir Editör</dc:creator>
  <dc:creator opf:role="aut">Reşat Nuri Güntekin</dc:creator>
  <meta name="cover" content="cover-img"/>
 </metadata>
 <manifest>
  <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  <item id="cover-img" href="kapak.png" media-type="image/png"/>
  <item id="c1" href="ch1.html" media-type="application/xhtml+xml"/>
 </manifest>
 <spine toc="ncx"><itemref idref="c1"/></spine>
</package>"""
        val ncx = """<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/"><navMap>
 <navPoint id="n1" playOrder="1"><navLabel><text>Birinci Kısım</text></navLabel><content src="ch1.html"/>
   <navPoint id="n2"><navLabel><text>Feride</text></navLabel><content src="ch1.html#feride"/></navPoint>
 </navPoint>
</navMap></ncx>"""
        val zip = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("content.opf"))
            .file("content.opf", opf, cp1254)
            .file("toc.ncx", ncx)
            .bytes("kapak.png", png)
            .file("ch1.html", "<html><body><p>Şu ığdır.</p></body></html>", cp1254)
            .open()
        val book = EpubParser.parse(zip, "x.epub")
        assertEquals("Çalıkuşu", book.title)
        assertEquals("Reşat Nuri Güntekin", book.author)
        assertEquals("kapak.png", book.coverPath)
        assertEquals(2, book.toc.size)
        assertEquals("Feride", book.toc[1].title)

        val text = ChapterTextExtractor.extract(zip.read("ch1.html")!!)
        assertEquals("Şu ığdır.", text.blocks.single().text)
    }

    @Test
    fun missingContainerAndManifestFallbacks() {
        val opf = """<package><metadata></metadata><manifest>
  <item id="a" href="b10.xhtml" media-type="application/xhtml+xml"/>
  <item id="b" href="b2.xhtml" media-type="application/xhtml+xml"/>
 </manifest><spine/></package>"""
        val zip = TestEpub()
            .file("kitap.opf", opf)
            .file("b10.xhtml", TestEpub.xhtml("<p>on</p>"))
            .file("b2.xhtml", TestEpub.xhtml("<p>iki</p>"))
            .open()
        val book = EpubParser.parse(zip, "Benim Kitabım.epub")
        assertEquals("Benim Kitabım", book.title)
        assertEquals(listOf("b2.xhtml", "b10.xhtml"), book.spine)
        assertEquals("Bölüm 1", book.toc.first().title)
        assertNull(book.coverPath)
    }

    @Test
    fun mojibakeTitleIsRepaired() {
        val opf = """<package><metadata><dc:title>TÃ¼rkÃ§e Ä°stanbul ÅžarkÄ±larÄ±</dc:title></metadata>
 <manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/></manifest>
 <spine><itemref idref="a"/></spine></package>"""
        val zip = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("c.opf"))
            .file("c.opf", opf)
            .file("a.xhtml", TestEpub.xhtml("<p>x</p>"))
            .open()
        assertEquals("Türkçe İstanbul Şarkıları", EpubParser.parse(zip, "a").title)
    }

    @Test
    fun drmIsDetectedButFontObfuscationIsNot() {
        fun withEnc(algo: String, uri: String) = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("c.opf"))
            .file("META-INF/encryption.xml", """<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container"
 xmlns:enc="http://www.w3.org/2001/04/xmlenc#"><enc:EncryptedData><enc:EncryptionMethod Algorithm="$algo"/>
 <enc:CipherData><enc:CipherReference URI="$uri"/></enc:CipherData></enc:EncryptedData></encryption>""")
            .file("c.opf", """<package><manifest><item id="a" href="a.xhtml" media-type="application/xhtml+xml"/>
 </manifest><spine><itemref idref="a"/></spine></package>""")
            .file("a.xhtml", TestEpub.xhtml("<p>x</p>"))
            .open()
        assertTrue(EpubParser.parse(withEnc("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "a.xhtml"), "a").drmProtected)
        assertFalse(EpubParser.parse(withEnc("http://www.idpf.org/2008/embedding", "fonts/a.otf"), "a").drmProtected)
    }

    @Test
    fun coverFromFirstPageImage() {
        val zip = TestEpub()
            .file("META-INF/container.xml", TestEpub.container("c.opf"))
            .file("c.opf", """<package><manifest>
 <item id="t" href="titlepage.xhtml" media-type="application/xhtml+xml"/>
 <item id="i" href="images/img001.jpg" media-type="image/jpeg"/></manifest>
 <spine><itemref idref="t"/></spine></package>""")
            .file("titlepage.xhtml", TestEpub.xhtml("""<div><svg xmlns:xlink="http://www.w3.org/1999/xlink">
 <image xlink:href="images/img001.jpg"/></svg></div>"""))
            .bytes("images/img001.jpg", png)
            .open()
        assertEquals("images/img001.jpg", EpubParser.parse(zip, "a").coverPath)
    }

    @Test
    fun notAZipFails() {
        try {
            ZipArchive.open(ByteArraySource("bu bir epub değil".toByteArray()))
            throw AssertionError("istisna bekleniyordu")
        } catch (e: java.io.IOException) {
            assertNotNull(e.message)
        }
    }
}
