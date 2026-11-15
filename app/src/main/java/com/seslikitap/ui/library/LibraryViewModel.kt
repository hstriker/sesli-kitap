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

/** Klasör görünümünde bir alt klasör kutusu. */
data class FolderTile(
    val name: String,
    val bookCount: Int,
    /** Klasördeki ilk kitabın kapağı (küçük önizleme). */
    val coverPath: String?,
)

data class LibraryUi(
    val books: List<BookEntity> = emptyList(),
    val folders: List<FolderTile> = emptyList(),
    /** Klasör görünümünde bulunulan yer, ör. ["Romanlar", "Klasikler"]. */
    val path: List<String> = emptyList(),
    /** Klasör görünümü etkin mi (arama yokken). */
    val browsing: Boolean = false,
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
    /** Klasör görünümü açık mı (kapalıysa tüm kitaplar tek listede). */
    val browseMode = MutableStateFlow(container.prefs.libraryBrowse)
    /** Klasör görünümünde bulunulan yer. */
    val path = MutableStateFlow<List<String>>(emptyList())

    val scan: StateFlow<ScanState> = repo.scan

    val folders: StateFlow<List<FolderEntity>?> =
        repo.folders.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private data class Params(
        val query: String,
        val sort: SortOrder,
        val filter: LibraryFilter,
        val browse: Boolean,
        val path: List<String>,
    )

    private val params = combine(query, sort, filter, browseMode, path) { q, s, f, b, p -> Params(q, s, f, b, p) }

    val ui: StateFlow<LibraryUi> = combine(repo.books.conflate(), repo.folders, params) { all, roots, p ->
        val key = TurkishText.searchKey(p.query)
        val terms = key.split(' ').filter { it.isNotBlank() }
        var list = if (terms.isEmpty()) all else all.filter { b -> terms.all { b.searchKey.contains(it) } }
        list = when (p.filter) {
            LibraryFilter.ALL -> list
            LibraryFilter.IN_PROGRESS -> list.filter { it.progress > 0f && !it.finished }
            LibraryFilter.FINISHED -> list.filter { it.finished }
            LibraryFilter.PROBLEMS -> list.filter { it.status != BookStatus.OK }
        }
        val collator = Collator.getInstance(TurkishText.TR).apply { strength = Collator.SECONDARY }
        list = when (p.sort) {
            SortOrder.TITLE -> list.sortedWith { a, b -> collator.compare(a.title, b.title) }
            SortOrder.AUTHOR -> list.sortedWith { a, b ->
                val c = collator.compare(a.author.ifBlank { "\uFFFF" }, b.author.ifBlank { "\uFFFF" })
                if (c != 0) c else collator.compare(a.title, b.title)
            }
            SortOrder.RECENT_ADDED -> list.sortedByDescending { it.addedAt }
            SortOrder.RECENT_OPENED -> list.sortedByDescending { it.lastOpenedAt ?: 0L }
        }

        // Klasör görünümü: arama yokken ve "Tümü" filtresindeyken
        val browsing = p.browse && terms.isEmpty() && p.filter == LibraryFilter.ALL
        var tiles = emptyList<FolderTile>()
        if (browsing) {
            val rootNames = roots.associate { it.uri to it.name }
            val multiRoot = roots.size > 1
            fun segments(b: BookEntity): List<String> {
                val rel = b.relativePath.split('/').filter { it.isNotBlank() }
                return if (multiRoot) listOf(rootNames[b.folderUri] ?: "Klasör") + rel else rel
            }
            val here = ArrayList<BookEntity>()
            val sub = LinkedHashMap<String, MutableList<BookEntity>>()
            for (b in list) {
                val seg = segments(b)
                if (seg.size < p.path.size || seg.subList(0, p.path.size) != p.path) continue
                if (seg.size == p.path.size) here.add(b)
                else sub.getOrPut(seg[p.path.size]) { ArrayList() }.add(b)
            }
            tiles = sub.entries
                .sortedWith { x, y -> collator.compare(x.key, y.key) }
                .map { (name, books) -> FolderTile(name, books.size, books.firstOrNull { it.coverPath != null }?.coverPath) }
            list = here
        }

        LibraryUi(
            books = list,
            folders = tiles,
            path = if (browsing) p.path else emptyList(),
            browsing = browsing,
            totalCount = all.size,
            problemCount = all.count { it.status != BookStatus.OK },
            loaded = true,
        )
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun openFolder(name: String) { path.value = path.value + name }

    /** Klasör görünümünde i'nci seviyeye dön (-1: en üst). */
    fun goToLevel(i: Int) { path.value = path.value.take(i + 1) }

    /** Bir üst klasöre çık; zaten en üstteyse false. */
    fun up(): Boolean {
        if (path.value.isEmpty()) return false
        path.value = path.value.dropLast(1)
        return true
    }

    fun setBrowse(on: Boolean) {
        browseMode.value = on
        container.prefs.libraryBrowse = on
    }

    init {
        // Açılışta hızlı artımlı tarama: sadece yeni/değişen dosyalar okunur.
        repo.autoScan()
    }

    fun setSort(s: SortOrder) {
        sort.value = s
        container.prefs.librarySort = s.name
    }

    fun rescan(full: Boolean = false) = repo.startScan(full)
    fun addFolder(uri: android.net.Uri) = repo.addFolder(uri)
    fun clearMessage() = repo.clearScanMessage()
}
