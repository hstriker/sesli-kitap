package com.seslikitap.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DialogDetectorTest {
    private fun flags(text: String): List<Boolean> = DialogDetector.detect(text, SentenceSplitter.split(text)).toList()

    @Test
    fun dashDialog() {
        assertEquals(listOf(true, true), flags("— Nereye gidiyorsun? — Eve dönüyorum."))
        assertEquals(listOf(false, false), flags("Paul uyandı. Hava soğuktu."))
    }

    @Test
    fun quotesSpanningSentences() {
        assertEquals(
            listOf(false, true, true, false),
            flags("Annesi seslendi. «Paul, gel buraya. Hemen!» Paul koştu."),
        )
        assertEquals(listOf(true, false), flags("\"Evet.\" Kapıyı kapattı."))
    }
}
