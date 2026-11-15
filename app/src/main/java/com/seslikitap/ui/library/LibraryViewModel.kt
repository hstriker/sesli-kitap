package com.seslikitap.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.core.TurkishText
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.FolderEntity
import com.seslikitap.data.ScanState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import java.text.Collator

enum class SortOrder(val label: String) {
    TITLE("Başlık (A-Z)"),
    AUTHOR("Yazar (A-Z)"),
    RECENT_ADDED("Son eklenen"),
    RECENT_OPENED("Son açılan"),
}

enum class LibraryFilter(val label: String) {
    ALL("Tümü"),
    IN_PROGRESS("Devam eden"),
    FINISHED("Bitenler"),
    PROBLEMS("Sorunlu"),
}

data class LibraryUi(
    val books: List<BookEntity> = emptyList(),
    val totalCount: Int = 0,
    val problemCount: Int = 0,
    val loaded: Boolean = false,
)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {

    private val repo = container.library

    val query = MutableStateFlow("")
    val sort = MutableStateFlow(
        runCatching { SortOrder.valueOf(container.prefs.librarySort) }.getOrDefault(SortOrder.TITLE)
    )
    val filter = MutableStateFlow(LibraryFilter.ALL)

    val scan: StateFlow<ScanState> = repo.scan

    val folders: StateFlow<List<FolderEntity>?> =
        repo.folders.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val ui: StateFlow<LibraryUi> = combine(repo.books.conflate(), query, sort, filter) { all, q, s, f ->
        val key = TurkishText.searchKey(q)
        val terms = key.split(' ').filter { it.isNotBlank() }
        var list = if (terms.isEmpty()) all else all.filter { b -> terms.all { b.searchKey.contains(it) } }
        list = when (f) {
            LibraryFilter.ALL -> list
            LibraryFilter.IN_PROGRESS -> list.filter { it.progress > 0f && !it.finished }
            LibraryFilter.FINISHED -> list.filter { it.finished }
            LibraryFilter.PROBLEMS -> list.filter { it.status != BookStatus.OK }
        }
        val collator = Collator.getInstance(TurkishText.TR).apply { strength = Collator.SECONDARY }
        list = when (s) {
            SortOrder.TITLE -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
            SortOrder.AUTHOR -> list.sortedWith { a, b ->
                val c = collator.compare(a.author.ifBlank { "￿" }, b.author.ifBlank { "￿" })
                if (c != 0) c else collator.compare(a.title, b.title)
            }
            SortOrder.RECENT_ADDED -> list.sortedByDescending { it.addedAt }
            SortOrder.RECENT_OPENED -> list.sortedByDescending { it.lastOpenedAt ?: 0L }
        }
        LibraryUi(
            books = list,
            totalCount = all.size,
            problemCount = all.count { it.status != BookStatus.OK },
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    init {
        // Açılışta hızlı artımlı tarama: sadece yeni/değişen dosyalar okunur.
        repo.startScan()
    }

    fun setSort(s: SortOrder) {
        sort.value = s
        container.prefs.librarySort = s.name
    }

    fun rescan(full: Boolean = false) = repo.startScan(full)
    fun addFolder(uri: android.net.Uri) = repo.addFolder(uri)
    fun clearMessage() = repo.clearScanMessage()
}
