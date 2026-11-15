package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NormalizerTest {

    @Test
    fun cardinals() {
        assertEquals("sıfır", TurkishNormalizer.cardinal(0))
        assertEquals("on bir", TurkishNormalizer.cardinal(11))
        assertEquals("yüz", TurkishNormalizer.cardinal(100))
        assertEquals("bin dokuz yüz yirmi üç", TurkishNormalizer.cardinal(1923))
        assertEquals("iki bin", TurkishNormalizer.cardinal(2000))
        assertEquals("bir milyon beş yüz bin", TurkishNormalizer.cardinal(1_500_000))
    }

    @Test
    fun ordinals() {
        val expected = mapOf(
            1L to "birinci", 2L to "ikinci", 3L to "üçüncü", 4L to "dördüncü", 5L to "beşinci",
            6L to "altıncı", 7L to "yedinci", 8L to "sekizinci", 9L to "dokuzuncu", 10L to "onuncu",
            20L to "yirminci", 30L to "otuzuncu", 40L to "kırkıncı", 50L to "ellinci", 60L to "altmışıncı",
            70L to "yetmişinci", 80L to "sekseninci", 90L to "doksanıncı", 100L to "yüzüncü",
            1000L to "bininci", 14L to "on dördüncü", 21L to "yirmi birinci",
        )
        expected.forEach { (n, w) -> assertEquals(w, TurkishNormalizer.ordinal(n)) }
    }

    @Test
    fun roman() {
        assertEquals(14, TurkishNormalizer.romanToInt("XIV"))
        assertEquals(null, TurkishNormalizer.romanToInt("IIII"))
        assertEquals(null, TurkishNormalizer.romanToInt("MAX"))
    }

    @Test
    fun normalizeSentences() {
        val n = TurkishNormalizer::normalize
        assertEquals("İkinci Abdülhamit tahta çıktı.", n("II. Abdülhamit tahta çıktı."))
        assertEquals("Bu yirminci yüzyıl olayıdır.", n("Bu XX. yüzyıl olayıdır."))
        assertEquals("Birinci Dünya Savaşı başladı.", n("1. Dünya Savaşı başladı."))
        assertEquals("On beşinci yüzyılda", n("15. yüzyılda"))
        assertEquals("Cumhuriyet 29 Ekim 1923 günü ilan edildi.", n("Cumhuriyet 29.10.1923 günü ilan edildi."))
        assertEquals("Doktor Ahmet ve benzeri kişiler", n("Dr. Ahmet vb. kişiler"))
        assertEquals("Profesör Doktor Ayşe", n("Prof. Dr. Ayşe"))
        assertEquals("bakınız sayfa 25", n("bkz. s. 25"))
        assertEquals("Nüfus 1500000 kişiydi.", n("Nüfus 1.500.000 kişiydi."))
        assertEquals("yüzde 50 indirim", n("%50 indirim"))
        assertEquals("5 kilometre yürüdü", n("5 km yürüdü"))
        // Dokunulmaması gerekenler
        assertEquals("Saat 1923'te geldi.", n("Saat 1923'te geldi."))
        assertEquals("C. Ali geldi.", n("C. Ali geldi."))
        assertEquals("Fiyatı 3,5 lira.", n("Fiyatı 3,5 lira."))
    }
}
