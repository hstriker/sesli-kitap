package com.seslikitap.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.ListeningDay
import com.seslikitap.data.QuoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HomeFolder(val name: String, val count: Int, val coverPath: String?)

data class HomeUi(
    val loaded: Boolean = false,
    val hasBooks: Boolean = false,
    val continueBook: BookEntity? = null,
    val inProgress: List<BookEntity> = emptyList(),
    val favorites: List<BookEntity> = emptyList(),
    val suggestion: BookEntity? = null,
    val recentlyAdded: List<BookEntity> = emptyList(),
    val finished: List<BookEntity> = emptyList(),
    val folders: List<HomeFolder> = emptyList(),
    val shelves: List<Pair<String, List<BookEntity>>> = emptyList(),
    val quotes: List<QuoteEntity> = emptyList(),
    val yearGoal: Int = 12,
    val yearFinished: Int = 0,
    val todayMs: Long = 0,
    val goalMs: Long = 30 * 60_000L,
    val streak: Int = 0,
    /** Okuma listesi (sırasıyla). */
    val readingList: List<BookEntity> = emptyList(),
    /** Bir süredir dokunulmayan yarım kitap (nazik hatırlatma). */
    val neglected: BookEntity? = null,
    /** Bu hafta dinlenen süre. */
    val weekMs: Long = 0,
    /** Günün alıntısı (her gün bir tane, bütün alıntılar arasından). */
    val dayQuote: QuoteEntity? = null,
)

/** Arka arkaya dinlenen gün sayısı (bugün ya da dün biten seri). En az 1 dakika dinlenen günler sayılır. */
fun streakOf(days: List<ListeningDay>, today: LocalDate = LocalDate.now()): Int {
    val active = days.filter { it.ms >= 60_000L }.map { it.day }.toHashSet()
    var d = if (today.toString() in active) today else today.minusDays(1)
    var n = 0
    while (d.toString() in active) { n++; d = d.minusDays(1) }
    return n
}

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.library
    /** "Başka öner" her basışta artar. */
    private val suggestionSeed = MutableStateFlow(0)

    private val extras = combine(repo.shelves, repo.shelfLinks, repo.quotes) { a, b, c -> Triple(a, b, c) }

    val ui: StateFlow<HomeUi> = combine(repo.books, repo.folders, repo.listening, suggestionSeed, extras) { books, roots, days, seed, ex ->
        val (shelfList, links, quotes) = ex
        val byId = books.associateBy { it.id }
        val shelfRows = shelfList.mapNotNull { sh ->
            val list = links.filter { it.shelfId == sh.id }.sortedByDescending { it.addedAt }.mapNotNull { byId[it.bookId] }
            if (list.isEmpty()) null else sh.name to list
        }
        val year = java.time.Year.now().value
        val yearFinished = books.count { b ->
            b.finishedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).year == year } == true
        }
        val ok = books.filter { it.status == BookStatus.OK }
        val inProgress = ok.filter { it.progress > 0f && !it.finished }
            .sortedByDescending { it.lastOpenedAt ?: 0L }
        val unread = ok.filter { it.progress == 0f && !it.finished && it.lastOpenedAt == null }

        // Klasörler: tek kök klasör varsa onun alt klasörleri, birden fazlaysa kök klasörler
        val multiRoot = roots.size > 1
        val rootNames = roots.associate { it.uri to it.name }
        val folders = books.groupBy { b ->
            if (multiRoot) rootNames[b.folderUri] ?: "Klasör" else b.relativePath.substringBefore('/')
        }.filterKeys { it.isNotBlank() }
            .map { (name, list) -> HomeFolder(name, list.size, list.firstOrNull { it.coverPath != null }?.coverPath) }
            .sortedByDescending { it.count }

        val today = LocalDate.now().toString()
        val byIdOk = ok.associateBy { it.id }
        val now = System.currentTimeMillis()
        val neglected = inProgress.filter { b ->
            b.progress in 0.02f..0.95f && (b.lastOpenedAt ?: now) < now - 21 * 86_400_000L &&
                container.prefs.nudgeSnoozedUntil(b.id) < now
        }.maxByOrNull { it.progress }
        val weekStart = LocalDate.now().minusDays(6).toString()
        HomeUi(
            readingList = container.prefs.readingList.mapNotNull { byIdOk[it] }.filter { !it.finished },
            neglected = neglected,
            weekMs = days.filter { it.day >= weekStart }.sumOf { it.ms },
            loaded = true,
            hasBooks = books.isNotEmpty(),
            continueBook = inProgress.firstOrNull(),
            inProgress = inProgress.drop(1).take(20),
            favorites = ok.filter { it.favorite }.sortedByDescending { it.lastOpenedAt ?: it.addedAt }.take(30),
            suggestion = if (unread.isEmpty()) null else unread[Math.floorMod(seed * 7919 + unread.size * 31, unread.size)],
            recentlyAdded = ok.sortedByDescending { it.addedAt }.take(20),
            finished = ok.filter { it.finished }.sortedByDescending { it.lastOpenedAt ?: 0L }.take(20),
            folders = folders,
            todayMs = days.firstOrNull { it.day == today }?.ms ?: 0L,
            goalMs = container.prefs.dailyGoalMin * 60_000L,
            streak = streakOf(days),
            shelves = shelfRows,
            quotes = quotes.take(5),
            dayQuote = quotes.filter { !it.text.startsWith("📝") }.let { qs ->
                if (qs.isEmpty()) null else qs[Math.floorMod(LocalDate.now().toEpochDay(), qs.size.toLong()).toInt()]
            },
            yearGoal = container.prefs.yearlyGoal,
            yearFinished = yearFinished,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    /** Yarım kitabı "Sonraya kalanlar" rafına kaldırır; bir daha sormaz. */
    fun shelveLater(book: BookEntity) {
        container.prefs.snoozeNudge(book.id, Long.MAX_VALUE)
        viewModelScope.launch(Dispatchers.IO) {
            val dao = container.db.shelves()
            val name = "Sonraya kalanlar"
            val id = dao.allShelves().firstOrNull { it.name == name }?.id
                ?: dao.insert(com.seslikitap.data.ShelfEntity(name = name, createdAt = System.currentTimeMillis()))
            dao.add(com.seslikitap.data.ShelfBookEntity(id, book.id, System.currentTimeMillis()))
        }
        suggestionSeed.value = suggestionSeed.value + 1
    }

    /** "Sonra": bu kitap için 30 gün sorma. */
    fun snoozeNudge(book: BookEntity) {
        container.prefs.snoozeNudge(book.id, System.currentTimeMillis() + 30 * 86_400_000L)
        suggestionSeed.value = suggestionSeed.value + 1
    }

    init {
        // Rastgele öneri her açılışta farklı olsun
        suggestionSeed.value = (System.currentTimeMillis() / 1000 % 100_000).toInt()
        repo.autoScan()
        // Bilgisayarda (web) dinlenenleri al: Drive yedeğini birleştir (yalnızca daha yeni konumlar)
        if (container.prefs.autoBackup) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val b = com.seslikitap.data.Backup(container.appContext)
                    b.download()?.let { b.restore(it, positionsOnly = true) }
                } catch (_: Exception) {}
            }
        }
    }

    fun nextSuggestion() { suggestionSeed.value = suggestionSeed.value + 1 }

    fun continueListening(book: BookEntity) {
        container.player.open(book.id, autoPlay = true)
    }

    fun openFolder(name: String) {
        container.pendingLibraryPath.value = listOf(name)
    }
}
