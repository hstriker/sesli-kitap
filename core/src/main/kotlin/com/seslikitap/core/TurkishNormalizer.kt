package com.seslikitap.core

/**
 * Seslendirmeden önce metni Türkçe okunuşa uygun hale getirir.
 * Ekranda gösterilen metin değişmez; sadece TTS motoruna giden metin dönüştürülür.
 */
object TurkishNormalizer {

    private val ONES = arrayOf("", "bir", "iki", "üç", "dört", "beş", "altı", "yedi", "sekiz", "dokuz")
    private val TENS = arrayOf("", "on", "yirmi", "otuz", "kırk", "elli", "altmış", "yetmiş", "seksen", "doksan")
    private val SCALES = arrayOf("", "bin", "milyon", "milyar", "trilyon")
    private val MONTHS = arrayOf(
        "", "Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
        "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık",
    )

    /** 1923 -> "bin dokuz yüz yirmi üç" */
    fun cardinal(n: Long): String {
        if (n == 0L) return "sıfır"
        if (n < 0) return "eksi " + cardinal(-n)
        val groups = ArrayList<Int>()
        var x = n
        while (x > 0) { groups.add((x % 1000).toInt()); x /= 1000 }
        val parts = ArrayList<String>()
        for (i in groups.indices.reversed()) {
            val g = groups[i]
            if (g == 0) continue
            if (i == 1 && g == 1) {
                parts.add("bin") // "bir bin" değil
            } else {
                parts.add(hundreds(g))
                if (i > 0) parts.add(SCALES.getOrElse(i) { "" })
            }
        }
        return parts.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun hundreds(n: Int): String {
        val h = n / 100
        val t = (n % 100) / 10
        val o = n % 10
        val p = ArrayList<String>()
        if (h > 0) p.add(if (h == 1) "yüz" else "${ONES[h]} yüz")
        if (t > 0) p.add(TENS[t])
        if (o > 0) p.add(ONES[o])
        return p.joinToString(" ")
    }

    /** 1 -> "birinci", 4 -> "dördüncü", 20 -> "yirminci" */
    fun ordinal(n: Long): String {
        val c = cardinal(n)
        val words = c.split(' ')
        var last = words.last()
        if (last == "dört") last = "dörd"
        val lastVowel = last.lastOrNull { it in "aeıioöuü" } ?: 'i'
        val v = when (lastVowel) {
            'a', 'ı' -> 'ı'
            'e', 'i' -> 'i'
            'o', 'u' -> 'u'
            else -> 'ü'
        }
        val suffix = if (last.last() in "aeıioöuü") "nc$v" else "${v}nc$v"
        return (words.dropLast(1) + (last + suffix)).joinToString(" ")
    }

    private val ROMAN_VALUES = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)

    fun romanToInt(s: String): Int? {
        if (s.isEmpty() || s.any { it !in ROMAN_VALUES }) return null
        var total = 0
        for (i in s.indices) {
            val v = ROMAN_VALUES.getValue(s[i])
            val next = if (i + 1 < s.length) ROMAN_VALUES.getValue(s[i + 1]) else 0
            total += if (v < next) -v else v
        }
        // Geçersiz yazımları ele (ör. "IIII", "VX"): geri çevirip karşılaştır.
        return if (intToRoman(total) == s) total else null
    }

    private fun intToRoman(n: Int): String {
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n
        val sb = StringBuilder()
        for (i in vals.indices) while (x >= vals[i]) { sb.append(syms[i]); x -= vals[i] }
        return sb.toString()
    }

    private fun capitalizeTr(s: String) =
        s.replaceFirstChar { if (it == 'i') 'İ' else it.titlecaseChar() }

    // Sıra: önce en özel kalıplar.
    private val DATE = Regex("""\b(\d{1,2})[./](\d{1,2})[./](\d{4})\b""")
    private val THOUSANDS = Regex("""\b\d{1,3}(?:\.\d{3})+\b(?![.,]\d)""")
    private val ROMAN_ORD = Regex("""\b([IVXLCDM]{1,7})\.(?=\s+\p{L})""")
    private val ARABIC_ORD = Regex("""(?<![\d.,])(\d{1,3})\.(?=\s+[\p{L}(])""")
    private val PERCENT = Regex("""%\s?(\d+(?:,\d+)?)""")
    private val PAGE = Regex("""\b[sS]\.\s?(?=\d)""")
    private val NO = Regex("""\b(No|Nu|Nr)\.\s?(?=\d)""")

    private val ABBR = listOf(
        "Prof. Dr." to "Profesör Doktor",
        "Doç. Dr." to "Doçent Doktor",
        "Yrd. Doç." to "Yardımcı Doçent",
        "Dr." to "Doktor",
        "Prof." to "Profesör",
        "Doç." to "Doçent",
        "Av." to "Avukat",
        "Op." to "Operatör",
        "Uzm." to "Uzman",
        "Sn." to "Sayın",
        "Hz." to "Hazreti",
        "M.Ö." to "milattan önce",
        "M.S." to "milattan sonra",
        "MÖ" to "milattan önce",
        "vb." to "ve benzeri",
        "vs." to "vesaire",
        "vd." to "ve diğerleri",
        "bkz." to "bakınız",
        "Bkz." to "Bakınız",
        "bknz." to "bakınız",
        "örn." to "örneğin",
        "Örn." to "Örneğin",
        "yy." to "yüzyıl",
        "çev." to "çeviren",
        "Çev." to "Çeviren",
        "haz." to "hazırlayan",
        "Haz." to "Hazırlayan",
        "Ltd. Şti." to "Limited Şirketi",
        "A.Ş." to "Anonim Şirketi",
        "T.C." to "Türkiye Cumhuriyeti",
        "Cad." to "Caddesi",
        "Sok." to "Sokağı",
        "Mah." to "Mahallesi",
        "Apt." to "Apartmanı",
    )
    private val ABBR_RE: List<Pair<Regex, String>> = ABBR.map { (k, v) ->
        Regex("(?<![\\p{L}.])" + Regex.escape(k) + "(?!\\p{L})") to v
    }
    private val UNITS = listOf("km" to "kilometre", "kg" to "kilogram", "cm" to "santimetre", "mm" to "milimetre", "gr" to "gram", "TL" to "lira")
        .map { (k, v) -> Regex("(?<=\\d)\\s?$k\\b") to " $v" }

    fun normalize(input: String): String {
        var s = input
        s = DATE.replace(s) { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (d in 1..31 && mo in 1..12) "$d ${MONTHS[mo]} ${m.groupValues[3]}" else m.value
        }
        s = THOUSANDS.replace(s) { it.value.replace(".", "") }
        for ((re, v) in ABBR_RE) s = re.replace(s, Regex.escapeReplacement(v))
        s = PAGE.replace(s, "sayfa ")
        s = NO.replace(s, "numara ")
        s = PERCENT.replace(s) { "yüzde ${it.groupValues[1]}" }
        for ((re, v) in UNITS) s = re.replace(s, v)
        s = ROMAN_ORD.replace(s) { m ->
            val r = m.groupValues[1]
            val n = romanToInt(r)
            // Tek harfli "I." / "V." / "C." gibi baş harfleri yanlışlıkla çevirmemek için sadece I, V, X kabul.
            if (n == null || (r.length == 1 && r[0] !in "IVX")) m.value
            else {
                val w = ordinal(n.toLong())
                if (isSentenceStart(s, m.range.first)) capitalizeTr(w) else w
            }
        }
        s = ARABIC_ORD.replace(s) { m ->
            val w = ordinal(m.groupValues[1].toLong())
            if (isSentenceStart(s, m.range.first)) capitalizeTr(w) else w
        }
        return s
    }

    private fun isSentenceStart(s: String, i: Int): Boolean {
        var j = i - 1
        while (j >= 0 && s[j].isWhitespace()) j--
        return j < 0 || s[j] in ".!?…:\"“«—–-("
    }
}
