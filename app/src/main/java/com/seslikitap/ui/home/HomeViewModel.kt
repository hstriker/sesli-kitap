package com.seslikitap.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.ListeningDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
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
    val todayMs: Long = 0,
    val goalMs: Long = 30 * 60_000L,
    val streak: Int = 0,
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

    val ui: StateFlow<HomeUi> = combine(repo.books, repo.folders, repo.listening, suggestionSeed) { books, roots, days, seed ->
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
        HomeUi(
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
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUi())

    init {
        // Rastgele öneri her açılışta farklı olsun
        suggestionSeed.value = (System.currentTimeMillis() / 1000 % 100_000).toInt()
        repo.autoScan()
    }

    fun nextSuggestion() { suggestionSeed.value = suggestionSeed.value + 1 }

    fun continueListening(book: BookEntity) {
        container.player.open(book.id, autoPlay = true)
    }

    fun openFolder(name: String) {
        container.pendingLibraryPath.value = listOf(name)
    }
}
