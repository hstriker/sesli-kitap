package com.seslikitap.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

object TurkishText {

    val TR: Locale = Locale("tr", "TR")

    private val INVISIBLE = Regex("[­​‌‍⁠﻿]")
    private val SPACES = Regex("[\\s   　]+")

    /** Görünmez karakterleri siler, tüm boşlukları teke indirir. */
    fun cleanWhitespace(s: String): String =
        SPACES.replace(INVISIBLE.replace(s, ""), " ").trim()

    /**
     * Arama anahtarı: küçük harf + Türkçe karakterleri sadeleştirme.
     * "İSTANBUL", "istanbul", "Istanbul" ve "ıstanbul" aynı anahtarı verir.
     */
    fun searchKey(s: String): String {
        val lower = s.lowercase(TR)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            sb.append(
                when (c) {
                    'ı' -> 'i'; 'ş' -> 's'; 'ğ' -> 'g'; 'ü' -> 'u'; 'ö' -> 'o'; 'ç' -> 'c'
                    'â' -> 'a'; 'î' -> 'i'; 'û' -> 'u'
                    else -> c
                }
            )
        }
        val noMarks = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return cleanWhitespace(noMarks)
    }

    private val WIN1252: Charset = Charset.forName("windows-1252")
    private val MOJIBAKE_HINT = Regex("[ÃÄÅÂ][\u0080-¿Œ-Ÿ–-›ˆ˜]")

    /**
     * Çift kodlanmış UTF-8 metni düzeltir: "TÃ¼rkÃ§e" -> "Türkçe", "Ä°stanbul" -> "İstanbul".
     * Emin olunamazsa metni değiştirmez.
     */
    fun fixMojibake(s: String): String {
        if (!MOJIBAKE_HINT.containsMatchIn(s)) return s
        for (cs in listOf(WIN1252, Charsets.ISO_8859_1)) {
            val enc = cs.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val bytes = try {
                val bb = enc.encode(java.nio.CharBuffer.wrap(s))
                ByteArray(bb.remaining()).also { bb.get(it) }
            } catch (e: Exception) {
                continue
            }
            val decoded = try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: Exception) {
                continue
            }
            if (decoded.length < s.length) return decoded
        }
        return s
    }

    /** Meta veri metinleri için tam temizlik. */
    fun cleanMeta(s: String?): String = s?.let { cleanWhitespace(fixMojibake(it)) } ?: ""
}
