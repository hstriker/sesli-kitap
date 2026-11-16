package com.seslikitap.ui.huzur

import androidx.compose.runtime.Composable

/** Sonraki paketlerin bölümleri (oyunlar, günün köşesi, hatıralar…). */
val EXTRA_SECTIONS: List<Pair<String, List<HuzurTile>>> = listOf(
    "Sakin oyunlar" to listOf(
        HuzurTile("zen", "🪨", "Zen bahçesi", "Kuma desen çiz, taş koy"),
        HuzurTile("kabarcik", "🫧", "Kabarcık patlatma", "Pıt pıt, sonsuz naylon"),
        HuzurTile("mandala", "🌸", "Mandala boyama", "Hata yok, yalnızca renk"),
        HuzurTile("yapboz", "🧩", "Kapaktan yapboz", "Kitaplığından bir kapak"),
        HuzurTile("hafiza", "🃏", "Hafıza kartları", "Kapakları eşleştir"),
        HuzurTile("kelimeavi", "🔎", "Kelime avı", "Okuduğun bölümden kelimeler"),
        HuzurTile("eksik", "✏️", "Eksik kelime", "Kendi alıntılarınla"),
        HuzurTile("hangikitap", "📚", "Hangi kitaptan?", "Alıntının kitabını bul"),
        HuzurTile("sudoku", "🔢", "Sudoku", "Süre yok, puan yok"),
        HuzurTile("solitaire", "♠️", "Solitaire", "Sakin bir kart falı"),
        HuzurTile("bilmece", "❓", "Günün bilmecesi", "Cevabı yarın"),
    ),
    "Günün köşesi" to listOf(
        HuzurTile("gunun", "☀️", "Bugün", "Alıntı, öykü, kelime, deyim, şiir"),
        HuzurTile("masal", "🧞", "Masal ve fıkra", "Halk masalları, Nasreddin Hoca"),
        HuzurTile("merak", "🔭", "Merak köşesi", "5 dakikalık sakin belgesel"),
        HuzurTile("defter", "📒", "Kelime defterim", "Öğrendiğin kelimeler ve tekrar"),
    ),
    "Hatıralar" to listOf(
        HuzurTile("raf", "🪵", "Kitap rafım", "Bitirdiklerin, rafında"),
        HuzurTile("agac", "🌳", "Kitap ağacım", "Her kitap bir yaprak"),
        HuzurTile("harita", "🌍", "Okuma haritam", "Kitaplarınla gittiğin ülkeler"),
        HuzurTile("zaman", "⏳", "Zaman çizgim", "Okuduklarının yüzyılları"),
        HuzurTile("yillik", "🗓️", "Yıllık okuma kartım", "Paylaşılabilir yıl özeti"),
        HuzurTile("oduller", "🏆", "Kendi ödüllerim", "Yılın kitabı, karakteri…"),
        HuzurTile("rozetler", "🎖️", "Rozetler", "Baskısız küçük sevinçler"),
        HuzurTile("gokyuzu", "✨", "Gece gökyüzü", "Takımyıldızlar ve efsaneleri"),
    ),
)

@Composable
fun ExtraPage(kind: String, onOpenBook: (Long) -> Unit, onOpenReader: (Long) -> Unit) {
    when (kind) {
        "zen" -> ZenGardenPage()
        "kabarcik" -> BubblesPage()
        "mandala" -> MandalaPage()
        "yapboz" -> PuzzlePage(onOpenBook)
        "hafiza" -> MemoryPage()
        "kelimeavi" -> WordSearchPage()
        "eksik", "hangikitap" -> QuoteQuizPage(kind)
        "sudoku" -> SudokuPage()
        "solitaire" -> SolitairePage()
        "bilmece" -> RiddlePage()
        "gunun" -> DailyCornerPage(onOpenReader)
        "masal" -> TalesPage()
        "merak" -> CuriosityPage()
        "defter" -> VocabPage()
        "raf" -> ShelfRoomPage(onOpenBook)
        "agac" -> TreePage()
        "harita" -> ReadingMapPage(onOpenBook)
        "zaman" -> TimelinePage(onOpenBook)
        "yillik" -> YearCardPage()
        "oduller" -> AwardsPage()
        "rozetler" -> BadgesPage()
        "gokyuzu" -> NightSkyPage()
    }
}
