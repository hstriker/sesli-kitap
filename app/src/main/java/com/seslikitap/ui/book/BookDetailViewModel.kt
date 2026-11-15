package com.seslikitap.ui.book

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seslikitap.AppContainer
import com.seslikitap.core.ChapterTextExtractor
import com.seslikitap.core.EpubBook
import com.seslikitap.core.SentenceSplitter
import com.seslikitap.data.BookEntity
import com.seslikitap.data.BookStatus
import com.seslikitap.data.BookOpener
import com.seslikitap.core.ExtractOptions
import com.seslikitap.player.BookContent
import com.seslikitap.player.ChapterLengthStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChapterPreview(
    val spineIndex: Int,
    val title: String,
    val sentences: List<String>,
    val error: String? = null,
)

class BookDetailViewModel(private val container: AppContainer, private val bookId: Long) : ViewModel() {

    private val repo = container.library
    private val context get() = container.appContext

    val entity: StateFlow<BookEntity?> =
        repo.observeBook(bookId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val book = MutableStateFlow<EpubBook?>(null)
    val loadError = MutableStateFlow<String?>(null)
    val preview = MutableStateFlow<ChapterPreview?>(null)
    /** Tahmini toplam dinleme süresi (ms). */
    val totalMs = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            val e = repo.getBook(bookId) ?: return@launch
            repo.markOpened(bookId)
            if (e.status == BookStatus.ERROR) {
                loadError.value = e.errorMessage ?: "Kitap açılamadı"
            }
            try {
                val b = repo.openBook(e)
                book.value = b
                loadError.value = null
                // Tahmini dinleme süresi: bölüm uzunlukları kayıtlı değilse arka planda hesaplanır
                val lens = withContext(Dispatchers.IO) {
                    ChapterLengthStore.load(context, e.id)?.takeIf { it.size == b.spine.size } ?: runCatching {
                        val opened = BookOpener.open(context, e.uri, e.fileName)
                        BookContent(e.id, opened.book, opened.source, ExtractOptions(container.prefs.skipFootnotes, container.prefs.skipPageNumbers))
                            .use { content -> ChapterLengthStore.getOrCompute(context, content) }
                    }.getOrNull()
                }
                if (lens != null) totalMs.value = container.player.estimatedTotalMs(lens)
            } catch (t: Throwable) {
                loadError.value = t.message ?: "Kitap açılamadı"
            }
        }
    }

    fun showPreview(spineIndex: Int, title: String) {
        val e = entity.value ?: return
        val b = book.value ?: return
        preview.value = ChapterPreview(spineIndex, title, emptyList())
        viewModelScope.launch {
            preview.value = withContext(Dispatchers.IO) {
                try {
                    BookOpener.open(context, e.uri, e.fileName).source.use { src ->
                        val chapter = src.chapterText(spineIndex, ExtractOptions(container.prefs.skipFootnotes, container.prefs.skipPageNumbers))
                            ?: throw java.io.IOException("Bölüm okunamadı")
                        val sentences = chapter.blocks.flatMap { SentenceSplitter.sentences(it.text) }
                        ChapterPreview(spineIndex, title, sentences)
                    }
                } catch (t: Throwable) {
                    ChapterPreview(spineIndex, title, emptyList(), t.message ?: "Bölüm okunamadı")
                }
            }
        }
    }

    fun toggleFavorite() {
        val e = entity.value ?: return
        viewModelScope.launch { repo.setFavorite(e.id, !e.favorite) }
    }

    fun closePreview() {
        preview.value = null
    }
}
