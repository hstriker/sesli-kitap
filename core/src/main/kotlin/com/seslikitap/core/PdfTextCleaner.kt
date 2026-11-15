package com.seslikitap.core

/**
 * PDF'ten sayfa sayfa çıkarılan ham metni seslendirilebilir paragraflara çevirir.
 *
 * Girdi: her sayfa için metin; satırlar "\n", PDF kütüphanesinin bulduğu paragraf sonları [PARAGRAPH] ile ayrılmış.
 * Yapılanlar:
 *  - Sayfaların çoğunda tekrar eden üst/alt bilgi satırları (kitap/bölüm adı) ve sayfa numaraları atılır.
 *  - Satır sonunda tireyle bölünmüş kelimeler birleştirilir ("kitap-" + "lar" -> "kitaplar").
 *  - Satırlar paragraflarda birleştirilir; sayfa sonunda yarım kalan paragraf sonraki sayfayla birleşir.
 */
object PdfTextCleaner {

    const val PARAGRAPH = ' '

    private val PAGE_NUMBER = Regex("""^[\s\-–—|•.]*(?:(?:sayfa|s\.|page|p\.)\s*)?[0-9ivxlcdmIVXLCDM]{1,6}[\s\-–—|•.]*$""", RegexOption.IGNORE_CASE)
    private val SENTENCE_END = Regex("""[.!?…:;"”»)\]]\s*$""")

    fun clean(pages: List<String>): List<TextBlock> {
        val pageLines = pages.map { page ->
            page.replace("\r", "").split(PARAGRAPH).map { para ->
                para.split('\n').map { TurkishText.cleanWhitespace(it) }.filter { it.isNotEmpty() }
            }.filter { it.isNotEmpty() }
        }
        val repeated = repeatedEdgeLines(pageLines)

        val blocks = ArrayList<String>()
        var current = StringBuilder()

        fun flush() {
            val t = TurkishText.cleanWhitespace(current.toString())
            if (t.isNotEmpty() && t.any { it.isLetterOrDigit() }) blocks.add(t)
            current = StringBuilder()
        }

        for (paras in pageLines) {
            // Sayfanın ilk ve son satırları üst/alt bilgi ya da sayfa numarası olabilir.
            val flat = paras.flatten()
            val drop = HashSet<Int>()
            for (i in flat.indices) {
                val isEdge = i < 2 || i >= flat.size - 2
                if (!isEdge) continue
                val line = flat[i]
                if (PAGE_NUMBER.matches(line) || normalize(line) in repeated) drop.add(i)
            }
            var idx = 0
            paras.forEachIndexed { pi, lines ->
                for (line in lines) {
                    val keep = idx !in drop
                    idx++
                    if (!keep) continue
                    appendLine(current, line)
                }
                // Paragraf sonu: sayfanın son paragrafı cümle ortasında bitiyorsa sonraki sayfayla birleşsin.
                val lastOfPage = pi == paras.size - 1
                if (!lastOfPage || SENTENCE_END.containsMatchIn(current)) flush()
            }
        }
        flush()
        return blocks.map { TextBlock(it, if (looksLikeHeading(it)) BlockKind.HEADING else BlockKind.PARAGRAPH) }
    }

    /** Satırı paragrafa ekler; satır sonundaki tireyle bölünmüş kelimeyi birleştirir. */
    private fun appendLine(sb: StringBuilder, line: String) {
        if (sb.isEmpty()) { sb.append(line); return }
        val endsWithHyphen = sb.endsWith("-") || sb.endsWith("­")
        val prevChar = if (sb.length >= 2) sb[sb.length - 2] else ' '
        val next = line.firstOrNull()
        if (endsWithHyphen && prevChar.isLetter() && next?.isLowerCase() == true) {
            sb.setLength(sb.length - 1) // "büro-" + "ya" -> "büroya"
            sb.append(line)
        } else if (endsWithHyphen && prevChar.isLetter() && next?.isLetter() == true) {
            sb.append(line) // "Ankara-" + "İstanbul" -> "Ankara-İstanbul"
        } else {
            sb.append(' ').append(line)
        }
    }

    /** Sayfaların en az %30'unda (en az 3 sayfada) ilk/son iki satırda geçen kısa satırlar. */
    private fun repeatedEdgeLines(pages: List<List<List<String>>>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (paras in pages) {
            val flat = paras.flatten()
            val edges = (flat.take(2) + flat.takeLast(2)).map { normalize(it) }.filter { it.length in 2..60 }.toSet() // gövde satırları genelde uzundur
            edges.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val threshold = maxOf(3, (pages.size * 0.3).toInt())
        return counts.filterValues { it >= threshold }.keys
    }

    /** Sayıları atıp küçük harfe çevirir: "KÜRK MANTOLU MADONNA 12" ile "... 13" aynı sayılır. */
    private fun normalize(line: String) = line.replace(Regex("[0-9]+"), "#").lowercase(TurkishText.TR).trim()

    /** Kısa, noktalama ile bitmeyen ve çoğu büyük harfli satırlar başlık sayılır. */
    private fun looksLikeHeading(t: String): Boolean {
        if (t.length > 80 || SENTENCE_END.containsMatchIn(t)) return false
        val letters = t.filter { it.isLetter() }
        if (letters.length < 2) return false
        val upper = letters.count { it.isUpperCase() }
        return upper >= letters.length * 0.7 || Regex("""^(\d+\.|bölüm|kısım|chapter)\s""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    }
}
