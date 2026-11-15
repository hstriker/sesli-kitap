package com.seslikitap.core

/** Metin içindeki bir cümlenin konumu: [start, end) */
data class Span(val start: Int, val end: Int)

/**
 * Türkçe cümle bölücü. Kısaltmalarda ("Dr.", "vb.", "s. 25"), sıra sayılarında ("1. Dünya Savaşı"),
 * Romen rakamlarında ("II. Abdülhamit") ve baş harflerde ("A. Kadir") cümleyi bölmez.
 * Çok uzun cümleleri seslendirme ve vurgulama için virgül/noktalı virgülden böler.
 */
object SentenceSplitter {

    const val MAX_LEN = 350

    private val ABBREVIATIONS = setOf(
        "dr", "prof", "doç", "doc", "yrd", "av", "op", "uzm", "sn", "bkz", "bk", "bknz", "vb", "vs", "vd",
        "örn", "orn", "sf", "ss", "no", "nu", "nr", "st", "mah", "cad", "sok", "sk", "apt", "blv",
        "böl", "md", "mad", "alb", "yzb", "bnb", "tğm", "ütğm", "org", "gen", "korg", "tümg", "tuğg",
        "ltd", "şti", "hz", "yy", "çev", "haz", "ed", "yay", "krş", "karş", "tel", "fak", "üniv", "ünv",
        "cilt", "sy", "ms", "mö", "mr", "mrs", "jr", "sr", "vol", "pp", "fig", "ibid", "etc", "ca",
        "km", "kg", "gr", "cm", "mm", "lt", "ml", "sa", "dk", "tah", "ort", "max", "min", "şek", "tab",
        "hk", "tic", "müd", "gnl", "bşk", "öğr", "arş", "müh", "ecz", "dt", "vet",
    )
    private val ROMAN = Regex("^[IVXLCDM]{1,7}$")
    private val CLOSERS = setOf('"', '\'', '”', '’', '»', ')', ']', '}', '›')
    private val OPENERS = setOf('"', '\'', '“', '‘', '«', '(', '[', '—', '–', '-', '‹', '¿', '¡')

    fun split(text: String): List<Span> {
        val raw = ArrayList<Span>()
        var start = 0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                // Noktalama dizisinin sonuna git ("?!", "...", ".”")
                var j = i
                while (j < n && (text[j] == '.' || text[j] == '!' || text[j] == '?' || text[j] == '…')) j++
                while (j < n && text[j] in CLOSERS) j++
                if (j >= n) break
                if (!text[j].isWhitespace()) { i = j; continue }
                var k = j
                while (k < n && text[k].isWhitespace()) k++
                if (k >= n) break
                if (isBoundary(text, i, j, k)) {
                    raw.add(Span(start, j))
                    start = k
                }
                i = k
                continue
            }
            i++
        }
        if (start < n) raw.add(Span(start, n))
        return raw.flatMap { s -> trim(text, s)?.let { breakLong(text, it) } ?: emptyList() }
    }

    fun sentences(text: String): List<String> = split(text).map { text.substring(it.start, it.end) }

    /** i: noktalamanın başı, j: noktalama+kapanıştan sonraki boşluk, k: sonraki cümlenin ilk karakteri */
    private fun isBoundary(text: String, i: Int, j: Int, k: Int): Boolean {
        val next = text[k]
        val punct = text[i]
        val nextStartsSentence = next.isUpperCase() || next.isDigit() || next in OPENERS
        if (!nextStartsSentence) return false
        if (punct == '!' || punct == '?') return true
        val isEllipsis = punct == '…' || (i + 1 < text.length && text[i + 1] == '.')
        if (isEllipsis) return true
        // Noktadan önceki kelime
        var w = i
        while (w > 0 && !text[w - 1].isWhitespace() && text[w - 1] !in OPENERS) w--
        val word = text.substring(w, i)
        if (word.isEmpty()) return true
        val lower = word.lowercase(TurkishText.TR).trim('(', '[', '"', '\'')
        if (lower in ABBREVIATIONS) {
            // "vb." ve "vs." cümle sonunda da sık kullanılır; ardından büyük harfli bir kelime + boşluk gelirse
            // yine de bölmeyelim (konuşmada fark edilmez, hatalı bölmekten iyidir).
            return false
        }
        // Tek harf: baş harf (A. Kadir)
        if (word.length == 1 && word[0].isLetter()) return false
        // "a.ş." "T.C." gibi noktalı kısaltmalar
        if (word.contains('.') && word.replace(".", "").length <= 4) return false
        // Romen rakamı: "II. Abdülhamit"
        if (ROMAN.matches(word) && word.length <= 4) return false
        // Sıra sayısı: "1. Dünya", "15. yüzyıl" (1-3 hane). Yıllar (4 hane) cümle sonu olabilir.
        if (word.all { it.isDigit() } && word.length <= 3) return false
        return true
    }

    private fun trim(text: String, s: Span): Span? {
        var a = s.start; var b = s.end
        while (a < b && text[a].isWhitespace()) a++
        while (b > a && text[b - 1].isWhitespace()) b--
        if (a >= b) return null
        if (text.substring(a, b).none { it.isLetterOrDigit() }) return null
        return Span(a, b)
    }

    private fun breakLong(text: String, s: Span): List<Span> {
        if (s.end - s.start <= MAX_LEN) return listOf(s)
        val out = ArrayList<Span>()
        var a = s.start
        while (s.end - a > MAX_LEN) {
            val limit = a + MAX_LEN
            var cut = -1
            for (marks in listOf(";:", ",", "—–")) {
                for (p in limit downTo a + MAX_LEN / 3) {
                    if (text[p - 1] in marks && p < s.end && text[p].isWhitespace()) { cut = p; break }
                }
                if (cut > 0) break
            }
            if (cut < 0) {
                cut = (limit downTo a + MAX_LEN / 3).firstOrNull { text[it].isWhitespace() } ?: limit
            }
            trim(text, Span(a, cut))?.let { out.add(it) }
            a = cut
        }
        trim(text, Span(a, s.end))?.let { out.add(it) }
        return out
    }
}
