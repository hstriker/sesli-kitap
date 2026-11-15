package com.seslikitap.core

/**
 * Bir paragraftaki cümlelerden hangilerinin konuşma (diyalog) olduğunu bulur.
 *
 * Kurallar (Türkçe yayın alışkanlıklarına göre):
 *  - Paragraf "—", "–" ya da "-" ile başlıyorsa paragrafın ilk cümlesi konuşmadır; paragraf içindeki
 *    yeni bir çizgiyle başlayan cümleler de konuşmadır.
 *  - «, ", “ ile açılan tırnak kapanana kadar (cümleler boyunca) konuşma sürer.
 *  - "diye sordu", "dedi" gibi anlatıcı ekleri aynı cümlede kalır; bu basit bir yaklaşımdır.
 */
object DialogDetector {

    private val DASHES = setOf('—', '–', '―')
    private val OPEN_QUOTES = setOf('«', '“', '"', '„')
    private val CLOSE_FOR = mapOf('«' to '»', '“' to '”', '"' to '"', '„' to '“')

    fun detect(text: String, spans: List<Span>): BooleanArray {
        val out = BooleanArray(spans.size)
        val paragraphDash = text.trimStart().firstOrNull()?.let { it in DASHES || it == '-' } == true
        var openQuote: Char? = null
        spans.forEachIndexed { i, s ->
            val sentence = text.substring(s.start, s.end)
            val first = sentence.firstOrNull() ?: return@forEachIndexed
            val dialog = openQuote != null ||
                (i == 0 && paragraphDash) ||
                first in DASHES ||
                first in OPEN_QUOTES
            // Tırnak takibi: cümlede açılıp kapanmayan tırnak sonraki cümleye taşınır.
            // Düz tırnakta (") açma ve kapama aynı karakter olduğundan her biri durumu değiştirir.
            for (c in sentence) {
                val q = openQuote
                if (q == null) {
                    if (c in OPEN_QUOTES) openQuote = c
                } else if (c == CLOSE_FOR[q]) {
                    openQuote = null
                }
            }
            out[i] = dialog
        }
        return out
    }
}
