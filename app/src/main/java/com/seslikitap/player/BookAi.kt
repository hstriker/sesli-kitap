package com.seslikitap.player

import android.content.Context
import com.seslikitap.container
import com.seslikitap.core.ExtractOptions
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Gemini yanıtlarının telefondaki deposu: aynı soru tekrar hak yemez. */
object AiCache {
    private fun dir(context: Context) = File(context.filesDir, "ai").apply { mkdirs() }
    private fun file(context: Context, key: String) = File(
        dir(context),
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".txt",
    )

    fun get(context: Context, key: String): String? = file(context, key).takeIf { it.exists() }?.readText()

    fun put(context: Context, key: String, value: String) = file(context, key).writeText(value)

    suspend fun getOrPut(context: Context, key: String, fresh: Boolean = false, block: suspend () -> String): String {
        if (!fresh) get(context, key)?.let { return it }
        return block().also { put(context, key, it) }
    }
}

/**
 * Kitapla konuşma özellikleri için Gemini istekleri. Hepsi kitabın yalnızca okunan kısmını gönderir
 * (kitap bitmediyse sonrası asla gitmez), yanıtlar sesli okunabilsin diye düz yazı ister.
 */
object BookAi {

    fun key(context: Context): String =
        context.container.prefs.geminiKey?.takeIf { it.isNotBlank() }
            ?: throw IOException("Bu özellik için Gemini anahtarı gerekiyor: Ayarlar → Yapay zekâ özeti.")

    private const val PLAIN = "Madde işareti, başlık ya da markdown kullanma; sesli okunacak düz Türkçe yaz."

    private fun book(title: String, author: String) = "\"$title\"" + if (author.isNotBlank()) " — $author" else ""

    fun askPrompt(title: String, author: String, text: String, history: List<Pair<String, String>>, question: String) = """
Sen bir sesli kitap uygulamasında okuyucunun kitap arkadaşısın. Kitap: ${book(title, author)}.
Aşağıda kitabın okuyucunun ŞU ANA KADAR dinlediği kısmı var. Soruyu yalnızca bu metne dayanarak yanıtla.
- Metinde olmayan, ileride olacak hiçbir şeyi söyleme ve tahmin etme (sürprizleri bozma).
- Cevap metinde yoksa bunu açıkça söyle ("Bu kısma kadar bununla ilgili bir şey geçmedi" gibi).
- Kısa ve sıcak yanıt ver (en fazla 120 kelime). $PLAIN
${if (history.isNotEmpty()) "\nÖnceki konuşma:\n" + history.joinToString("\n") { "Okuyucu: ${it.first}\nSen: ${it.second}" } + "\n" else ""}
Okuyucunun sorusu: $question

METİN:
$text
""".trimIndent()

    fun charactersPrompt(title: String, author: String, text: String) = """
Kitap: ${book(title, author)}. Aşağıda okuyucunun şu ana kadar dinlediği kısım var.
Bu kısımda geçen önemli karakterleri listele. Her satıra bir karakter: "Ad — bir iki cümlelik tanıtım
(kim olduğu, diğerleriyle ilişkisi, şu ana kadar ne yaptığı)". En önemlisinden başla, en fazla 15 karakter.
Yalnızca bu metne dayan; ileride olacakları asla söyleme. Başka hiçbir şey yazma.

METİN:
$text
""".trimIndent()

    fun thoughtPrompt(title: String, author: String, text: String) = """
Kitap: ${book(title, author)}. Aşağıda okuyucunun son dinlediği kısım var.
Bu kısımdan doğan, okuyucuyu düşündürecek TEK bir soru sor: ahlaki bir ikilem, bir karakterin seçimi, hayatla
bağlantı… Önce bir cümleyle bağlamı hatırlat, sonra soruyu sor. En fazla 60 kelime. İleride olacakları söyleme. $PLAIN

METİN:
$text
""".trimIndent()

    fun chapterSummaryPrompt(title: String, chapterTitle: String, text: String) = """
Kitap: "$title". Okuyucu az önce "$chapterTitle" bölümünü dinlemeyi bitirdi. Bu bölümün 60-90 kelimelik, sakin
ve akıcı bir özetini yaz: ne oldu, kim ne yaptı. Sonraki bölümler hakkında tahmin yürütme. $PLAIN

BÖLÜM:
$text
""".trimIndent()

    fun introPrompt(title: String, author: String, opening: String) = """
Okuyucu ${book(title, author)} kitabına başlamak üzere. Aşağıda kitabın yalnızca başı var.
Kitabın havasını, konusunun çıkış noktasını ve yazarını tanıtan 100-140 kelimelik, merak uyandıran bir tanıtım yaz.
KESİNLİKLE sürpriz bozma: başlangıçtan sonrası hakkında olay anlatma, sonu ima etme. Kitabı kendi bilgilerinden
tanıyorsan da yalnızca ilk bölümlerde öğrenilenleri söyle. $PLAIN

KİTABIN BAŞI:
$opening
""".trimIndent()

    fun authorPrompt(author: String, title: String) = """
"$author" adlı yazarı ("$title" kitabının yazarı) 120-160 kelimeyle sesli bir anlatımla tanıt: nerede ve ne zaman
yaşadı, hayatından ilginç noktalar, en bilinen eserleri, yazısının özelliği. Emin olmadığın hiçbir şeyi yazma;
yazarı tanımıyorsan yalnızca "Bu yazar hakkında güvenilir bilgim yok." yaz. Kitabın sonunu anlatma. $PLAIN
""".trimIndent()

    fun clubPrompt(title: String, author: String, ending: String, transcript: List<Pair<String, String>>) = """
Sen tek kişilik bir kitap kulübünün sıcak, meraklı sohbet arkadaşısın. Okuyucu ${book(title, author)} kitabını
az önce BİTİRDİ (sürpriz bozma kaygısı yok). Toplam 4 soru soracaksın, her mesajda yalnızca bir tane:
duyguları, sevdiği/kızdığı karakter, aklında kalan sahne, kitabın ona ne düşündürdüğü gibi.
Okuyucunun cevabına önce kısa ve içten bir karşılık ver (1-2 cümle, kendi yorumunu da kat), sonra sıradaki soruyu sor.
4. cevaptan sonra soru sorma; sohbeti güzel bir kapanış cümlesiyle bitir ve sonuna [BİTTİ] yaz.
Her mesaj en fazla 70 kelime. $PLAIN
${if (transcript.isEmpty()) "Sohbeti başlat: kitabı bitirdiği için kutla ve ilk soruyu sor."
    else "Şimdiye kadarki sohbet:\n" + transcript.joinToString("\n") { "${it.first}: ${it.second}" } + "\nSıradaki mesajını yaz."}

KİTABIN SON KISMI (hatırlaman için):
$ending
""".trimIndent()

    fun translatePrompt(sentence: String, context: String) = """
Aşağıdaki cümleyi doğal, akıcı Türkçeye çevir. Yalnızca çeviriyi yaz.
Bağlam için önceki cümleler: $context
Cümle: $sentence
""".trimIndent()

    // ------------------------------------------------------------------ Kitap sayfası

    /** Spoiler'sız tanıtım: kitabın yalnızca başı gönderilir. */
    suspend fun intro(context: Context, e: BookEntity, fresh: Boolean = false): String =
        AiCache.getOrPut(context, "tanitim|${e.id}", fresh) {
            val opening = withContext(Dispatchers.IO) {
                val opened = BookOpener.open(context, e.uri, e.fileName)
                BookContent(e.id, opened.book, opened.source, ExtractOptions(true, true)).use { c ->
                    val sb = StringBuilder()
                    var i = 0
                    while (i < c.chapterCount && sb.length < 20_000) {
                        val ch = c.chapter(i)
                        for (s in ch.sentences.indices) { sb.append(ch.text(s)).append(' '); if (sb.length >= 20_000) break }
                        i++
                    }
                    sb.toString()
                }
            }
            Gemini.generate(key(context), introPrompt(e.title, e.author, opening), 0.6)
        }

    suspend fun author(context: Context, author: String, title: String, fresh: Boolean = false): String =
        AiCache.getOrPut(context, "yazar|${author.trim().lowercase()}", fresh) {
            Gemini.generate(key(context), authorPrompt(author, title), 0.3)
        }

    /** Kitap kulübü sohbetlerinin saklandığı yer (kitap başına). */
    fun clubFile(context: Context, bookId: Long) = File(File(context.filesDir, "ai").apply { mkdirs() }, "kulup-$bookId.txt")
}
