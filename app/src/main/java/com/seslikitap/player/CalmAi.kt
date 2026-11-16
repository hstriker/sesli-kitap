package com.seslikitap.player

import android.content.Context

/** Huzur sekmesinin Gemini istekleri (hikâye, masal, merak köşesi, deyim, şiir…). Sonuçlar saklanır. */
object CalmAi {

    private const val PLAIN = "Madde işareti, başlık, emoji ya da markdown kullanma; sesli okunacak düz Türkçe yaz."

    suspend fun ask(context: Context, cacheKey: String?, prompt: String, temperature: Double = 0.8, fresh: Boolean = false): String {
        val key = BookAi.key(context)
        return if (cacheKey == null) Gemini.generate(key, prompt, temperature)
        else AiCache.getOrPut(context, cacheKey, fresh) { Gemini.generate(key, prompt, temperature) }
    }

    fun sleepStory(topic: String) = """
Uykuya dalmak üzere olan bir yetişkin için "$topic" konulu, özgün bir uyku hikâyesi yaz.
- 600-800 kelime. Olay yok denecek kadar az: gerilim, tehlike, sürpriz yok.
- Yavaş, akıcı, duyulara hitap eden betimlemeler (ses, koku, ışık, dokunuş); cümleler uzun ve yumuşak.
- Hikâye ilerledikçe tempo daha da yavaşlasın; sonda her şey sessizliğe ve huzura karışsın.
- İkinci tekil şahısla ("sen") anlat. $PLAIN Paragraflar arasında boş satır bırak.
""".trimIndent()

    fun bodyScan() = """
5-6 dakikalık, yatarken dinlenecek rehberli bir beden taraması gevşeme metni yaz. Ayak parmaklarından başlayıp
başa kadar her bölgeye dikkat ver, nefesle birleştir. Sakin, şefkatli, yargısız bir dil. Aralarda "…" ile kısa
duraklamalar bırak. 450-550 kelime. $PLAIN Paragraflar arasında boş satır bırak.
""".trimIndent()

    fun adventureStart(setting: String) = """
Sesli, seçimli bir macera hikâyesi başlatıyoruz. Ortam: $setting. Sakin ama merak uyandıran, şiddetsiz bir macera.
150-200 kelimelik ilk bölümü ikinci tekil şahısla yaz. Sonunda dinleyiciye iki seçenek sun.
Tam olarak şu biçimde yaz:
METİN: (hikâye)
A: (birinci seçenek, kısa)
B: (ikinci seçenek, kısa)
""".trimIndent()

    fun adventureNext(story: String, choice: String, step: Int) = """
Sesli, seçimli bir macera hikâyesi sürüyor. Şimdiye kadar:
$story

Dinleyicinin seçimi: $choice
Hikâyeyi 150-200 kelimeyle sürdür. ${if (step >= 5) "Bu son bölüm: hikâyeyi güzel ve huzurlu bir sonla bitir, seçenek sunma; sonuna [SON] yaz." else "Sonunda yine iki seçenek sun."}
Tam olarak şu biçimde yaz:
METİN: (hikâye)
${if (step >= 5) "" else "A: (birinci seçenek)\nB: (ikinci seçenek)"}
""".trimIndent()

    fun coWrite(story: String) = """
Okuyucuyla sırayla bir hikâye yazıyorsunuz. Şimdiye kadarki hikâye (okuyucunun son eklediği en sonda):
$story

Hikâyeyi 60-100 kelimelik tek bir paragrafla sürdür: okuyucunun kurduğu dünyaya ve karakterlere sadık kal, onun
fikrini geliştir, sonunda okuyucunun devam edebileceği açık bir an bırak. Yalnızca paragrafı yaz. $PLAIN
""".trimIndent()

    fun folkTale(kind: String) = """
$kind anlat: geleneksel, halk arasında bilinen (telifsiz) bir tanesini kendi sözlerinle, sıcak bir anlatıcı diliyle
yeniden anlat. ${if (kind.contains("fıkra")) "Kısa olsun (120-200 kelime)." else "400-600 kelime."}
Önce hangi masal ya da fıkra olduğunu bir cümleyle söyle. $PLAIN Paragraflar arasında boş satır bırak.
""".trimIndent()

    fun curiosity(topic: String?) = """
${if (topic.isNullOrBlank()) "Şaşırtıcı ve az bilinen ama doğru bir konu seç (doğa, bilim, tarih, sanat)." else "Konu: $topic."}
Sakin bir belgesel anlatıcısı gibi 400-550 kelimelik bir anlatım yaz. Yalnızca doğruluğundan emin olduğun bilgileri
kullan; emin olmadığın sayıları verme. İlk cümlede konuyu söyle. $PLAIN Paragraflar arasında boş satır bırak.
""".trimIndent()

    fun idiom() = """
Türkçede yaygın bir deyim ya da atasözü seç. Önce kendisini, sonra anlamını, sonra (biliniyorsa) nereden çıktığına
dair kısa hikâyesini anlat; kökeni kesin bilinmiyorsa bunu açıkça söyle, uydurma. Toplam 90-150 kelime. $PLAIN
""".trimIndent()

    fun poem(poets: String) = """
$poets şairlerinden birinin halk arasında iyi bilinen bir şiirini seç ve yaz. Yalnızca dizelerinden EMİN olduğun bir
şiiri yaz; emin olmadığın dizeyi uydurma, emin değilsen daha kısa ve çok bilinen bir şiir seç. Önce şairin adını ve
bir cümlelik tanıtımını yaz, boş satır bırak, sonra şiiri dizeleriyle yaz. Sonra boş satır bırakıp şiirin ne anlattığını
iki cümleyle açıkla.
""".trimIndent()

    fun constellation(name: String) = """
"$name" takımyıldızını ve ona dair mitolojik efsaneyi 120-180 kelimeyle, gece gökyüzüne bakan birine anlatır gibi
sakin bir dille anlat. Gökyüzünde nasıl bulunacağına bir cümleyle değin. Emin olmadığın bilgiyi verme. $PLAIN
""".trimIndent()

    fun placesPrompt(books: List<String>) = """
Aşağıdaki kitapların her biri için, hikâyenin ağırlıklı olarak GEÇTİĞİ ülkeyi (ya da ülkeleri) yaz.
Her satıra tam olarak şu biçimde: KİMLİK|ISO2 kodu:Türkçe ülke adı;ISO2:ülke (en fazla 3 ülke)
Kitabı tanımıyorsan ya da hayali bir dünyada geçiyorsa: KİMLİK|- yaz. Başka hiçbir şey yazma.

KİTAPLAR (KİMLİK|başlık|yazar):
${books.joinToString("\n")}
""".trimIndent()

    fun yearsPrompt(books: List<String>) = """
Aşağıdaki kitapların ilk yayımlandığı yılı yaz. Her satıra tam olarak: KİMLİK|yıl (MÖ için eksi sayı).
Emin değilsen KİMLİK|? yaz. Başka hiçbir şey yazma.

KİTAPLAR (KİMLİK|başlık|yazar):
${books.joinToString("\n")}
""".trimIndent()

    fun songsPrompt(title: String, author: String) = """
"$title"${if (author.isNotBlank()) " ($author)" else ""} kitabının havasına, dönemine ve duygusuna uyan 6 gerçek şarkı ya da müzik eseri öner.
Her satıra tam olarak: Sanatçı — Eser adı — neden (kısa). Gerçekte var olmayan eser uydurma. Başka hiçbir şey yazma.
""".trimIndent()
}
