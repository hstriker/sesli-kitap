package com.seslikitap.core

import java.io.ByteArrayOutputStream

object PathUtil {

    /** "OEBPS/content.opf" -> "OEBPS" ; "content.opf" -> "" */
    fun dirOf(path: String): String = path.substringBeforeLast('/', "")

    /** href'teki #parça kısmı (yoksa null). */
    fun fragmentOf(href: String): String? =
        href.substringAfter('#', "").takeIf { it.isNotEmpty() }?.let { percentDecode(it) }

    /**
     * Göreli bir href'i arşiv içi mutlak yola çevirir.
     * Örn: resolve("OEBPS/Text", "../Images/kapak%20resmi.jpg#x") -> "OEBPS/Images/kapak resmi.jpg"
     */
    fun resolve(baseDir: String, href: String): String {
        var h = href.trim().substringBefore('#').substringBefore('?')
        if (h.startsWith("file://")) h = h.removePrefix("file://")
        h = percentDecode(h).replace('\\', '/')
        val parts = ArrayList<String>()
        if (!h.startsWith("/") && baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString("/")
    }

    /** %XX dizilerini UTF-8 olarak çözer; '+' karakterine dokunmaz. Geçersiz diziler olduğu gibi kalır. */
    fun percentDecode(s: String): String {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hi = Character.digit(s[i + 1], 16)
                val lo = Character.digit(s[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write(hi * 16 + lo)
                    i += 3
                    continue
                }
            }
            val bytes = c.toString().toByteArray(Charsets.UTF_8)
            if (Character.isHighSurrogate(c) && i + 1 < s.length) {
                val pair = s.substring(i, i + 2).toByteArray(Charsets.UTF_8)
                out.write(pair, 0, pair.size)
                i += 2
                continue
            }
            out.write(bytes, 0, bytes.size)
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
