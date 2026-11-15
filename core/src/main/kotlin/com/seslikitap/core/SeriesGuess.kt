package com.seslikitap.core

/**
 * Kitap başlığından seri adı ve sırasını tahmin eder. Yanlış eşleşmeyi önlemek için yalnızca
 * açık kalıpları tanır:
 *   "Dune 2. Kitap: Mesih", "Yüzüklerin Efendisi - Cilt 3", "Vakıf Serisi 4", "Seri Adı #2", "Seri Adı (2)",
 *   "Harry Potter 3: ...", "1. Kitap - Seri Adı" gibi.
 */
object SeriesGuess {

    private val PATTERNS = listOf(
        // Seri Adı #2  |  Seri Adı (#2)
        Regex("""^(.{2,60}?)\s*\(?#\s*(\d{1,2})\)?(?:\s*[:\-–—].*)?$"""),
        // Seri Adı 2. Kitap / 2. Cilt / 2. Bölüm(kitap) ...
        Regex("""^(.{2,60}?)[\s,:\-–—]+(\d{1,2})\.\s*(?:kitap|cilt|kısım)\b.*$""", RegexOption.IGNORE_CASE),
        // Seri Adı - Cilt 3 / Kitap 3
        Regex("""^(.{2,60}?)[\s,:\-–—]+(?:cilt|kitap)\s*(\d{1,2})\b.*$""", RegexOption.IGNORE_CASE),
        // Seri Adı Serisi 4
        Regex("""^(.{2,60}?\bseri(?:si)?)\s*(\d{1,2})\b.*$""", RegexOption.IGNORE_CASE),
        // Seri Adı (2)
        Regex("""^(.{2,60}?)\s*\((\d{1,2})\)\s*$"""),
        // 2. Kitap: Seri Adı
        Regex("""^(\d{1,2})\.\s*(?:kitap|cilt)\s*[:\-–—]\s*(.{2,60})$""", RegexOption.IGNORE_CASE),
    )

    fun fromTitle(title: String): Pair<String, Double>? {
        val t = TurkishText.cleanWhitespace(title)
        PATTERNS.forEachIndexed { i, re ->
            val m = re.find(t) ?: return@forEachIndexed
            val (a, b) = m.destructured
            val (name, num) = if (i == PATTERNS.size - 1) b to a else a to b
            val clean = name.trim().trim('-', '–', '—', ':', ',', ' ')
            if (clean.length >= 2 && clean.any { it.isLetter() }) return clean to num.toDouble()
        }
        return null
    }
}
