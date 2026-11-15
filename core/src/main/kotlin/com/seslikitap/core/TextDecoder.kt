package com.seslikitap.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * EPUB içindeki XML/XHTML baytlarını doğru karakter kodlamasıyla metne çevirir.
 * Türkçe kitaplarda sık görülen sorunlar için önlemler içerir:
 *  - Bildirim olmadan Windows-1254 ile kaydedilmiş dosyalar
 *  - "iso-8859-1" yazıp aslında Windows-1254 olan dosyalar (Ý, Þ, Ð yerine İ, Ş, Ğ)
 *  - UTF-8 olduğu halde başka kodlama bildiren dosyalar
 */
object TextDecoder {

    private val WIN1254: Charset = Charset.forName("windows-1254")

    fun decode(bytes: ByteArray): String {
        // BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        // BOM'suz UTF-16 (ilk karakter '<' ise)
        if (bytes.size >= 4 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte() && bytes[3] == 0.toByte()) {
            return String(bytes, Charsets.UTF_16LE)
        }
        if (bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte() && bytes[2] == 0.toByte()) {
            return String(bytes, Charsets.UTF_16BE)
        }

        strictUtf8(bytes)?.let { return it.removePrefix("﻿") }

        val declared = declaredCharset(bytes)
        val cs = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        if (cs != null && cs != Charsets.UTF_8 && cs.name() != "UTF-16") {
            val text = String(bytes, cs)
            val n = cs.name().uppercase()
            val latinLike = n == "ISO-8859-1" || n == "WINDOWS-1252" || n == "US-ASCII" || n == "ISO-8859-15"
            if (latinLike && looksLikeMisdeclaredTurkish(text)) return String(bytes, WIN1254)
            return text
        }
        // UTF-8 bildirilmiş/bildirilmemiş ama geçerli UTF-8 değil: Türkçe Windows kodlaması varsay.
        return String(bytes, WIN1254)
    }

    private fun strictUtf8(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        null
    }

    private val ENCODING_RE = Regex("""encoding\s*=\s*["']([A-Za-z0-9._:\-]+)["']""", RegexOption.IGNORE_CASE)
    private val CHARSET_RE = Regex("""charset\s*=\s*["']?([A-Za-z0-9._:\-]+)""", RegexOption.IGNORE_CASE)

    private fun declaredCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        ENCODING_RE.find(head)?.let { return it.groupValues[1] }
        CHARSET_RE.find(head)?.let { return it.groupValues[1] }
        return null
    }

    /** Latin-1 olarak çözülmüş metinde Türkçe'ye özgü bozulma işaretleri var mı? */
    private fun looksLikeMisdeclaredTurkish(text: String): Boolean {
        var suspicious = 0
        for (c in text) {
            if (c == 'Ý' || c == 'Þ' || c == 'Ð' || c == 'ý' || c == 'þ' || c == 'ð') suspicious++
        }
        return suspicious >= 2
    }
}
