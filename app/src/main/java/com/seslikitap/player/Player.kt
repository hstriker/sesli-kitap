package com.seslikitap.player

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.seslikitap.Kayit
import com.seslikitap.container
import com.seslikitap.Prefs
import com.seslikitap.core.BlockKind
import com.seslikitap.core.EpubParser
import com.seslikitap.core.ExtractOptions
import com.seslikitap.core.TurkishNormalizer
import com.seslikitap.data.AppDatabase
import com.seslikitap.data.BookStatus
import com.seslikitap.data.BookmarkEntity
import com.seslikitap.data.BookOpener
import com.seslikitap.data.PdfBooks
import com.seslikitap.data.PositionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class PlayerState(
    val bookId: Long? = null,
    val bookTitle: String = "",
    val author: String = "",
    val coverPath: String? = null,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val chapterTitle: String = "",
    val sentenceIndex: Int = 0,
    val sentenceCount: Int = 0,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val rate: Float = 1f,
    val pitch: Float = 1f,
    /** Uyku zamanlayıcısı bitiş zamanı (epoch ms). */
    val sleepAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val error: String? = null,
    val engine: EngineStatus = EngineStatus.Initializing,
    val finished: Boolean = false,
    /** Kitabın bölüm uzunlukları hesaplandı (kitabın kalan süresi gösterilebilir). */
    val lengthsReady: Boolean = false,
    /** Kitaba uzun aradan (3+ gün) sonra dönüldü: "nerede kalmıştık" önerilir. */
    val longAbsence: Boolean = false,
    /** Kitap bitince: serinin sıradaki kitabı. */
    val nextBookId: Long? = null,
    /** Sıradaki kitap okuma listesinden mi (değilse serinin devamı). */
    val nextFromList: Boolean = false,
    val nextBookTitle: String? = null,
) {
    val active get() = bookId != null
}

/** Seslendirme motoruna gidecek metin (ekrandaki metin değişmez). Önceden hazırlama da aynısını kullanır. */
fun spokenTextOf(ch: Chapter, i: Int, normalize: Boolean): String {
    var t = ch.text(i)
    if (normalize) t = TurkishNormalizer.normalize(t)
    val block = ch.blocks[ch.sentences[i].blockIndex]
    if (block.kind == BlockKind.HEADING && t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
    return t
}

/**
 * Uygulama genelinde tek oynatıcı. Ekran ve arka plan servisi aynı örneği kullanır.
 * Tüm durum değişiklikleri ana iş parçacığında yapılır.
 */
class Player(
    private val context: Context,
    private val db: AppDatabase,
    private val prefs: Prefs,
) : SpeechListener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(PlayerState(rate = prefs.speechRate, pitch = prefs.speechPitch))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _chapter = MutableStateFlow<Chapter?>(null)
    /** Ekranda gösterilen bölüm. */
    val chapter: StateFlow<Chapter?> = _chapter.asStateFlow()

    private var content: BookContent? = null
    private var engine: SpeechEngine? = null
    private var engineWatch: Job? = null

    /** Her yeni sıralamada artar; eski motor olaylarını yok saymak için. */
    private var generation = 0
    private var queuedUntil = -1
    private var consecutiveErrors = 0
    private var sleepJob: Job? = null
    private var openJob: Job? = null

    val bookContent: BookContent? get() = content

    // ------------------------------------------------------------------ Motor

    private fun engine(): SpeechEngine {
        engine?.let { return it }
        val cloud = prefs.voiceMode == "cloud"
        val gemini = prefs.voiceMode == "gemini"
        val e: SpeechEngine =
            if (cloud) CloudTtsEngine(context, context.container.cloudAuth, prefs)
            else if (gemini) GeminiTtsEngine(context, prefs)
            else AndroidTtsEngine(context, prefs.ttsEngine)
        e.listener = this
        engine = e
        engineWatch = scope.launch {
            val notice = (e as? CloudTtsEngine)?.notice ?: (e as? GeminiTtsEngine)?.notice
            if (notice != null) {
                // Doğal ses uyarılarını (kota doldu, bağlantı gerekli…) ekranda bir kez göster
                launch {
                    notice.collect { msg ->
                        if (msg != null) {
                            _state.update { it.copy(error = msg) }
                            notice.value = null
                        }
                    }
                }
            }
            e.status.collect { s ->
                _state.update { it.copy(engine = s) }
                if (s == EngineStatus.Ready) {
                    e.setRate(_state.value.rate)
                    e.setPitch(_state.value.pitch)
                    if (cloud) e.setVoice(prefs.cloudVoice)
                    else if (gemini) e.setVoice(prefs.geminiVoice)
                    else prefs.voiceName?.let { e.setVoice(it) }
                    if (!gemini) e.setDialogVoice(if (cloud) prefs.cloudDialogVoice else prefs.phoneDialogVoice)
                }
            }
        }
        return e
    }

    fun voices(): List<VoiceInfo> = engine().voices()

    fun setVoice(name: String?) {
        if (prefs.voiceMode == "cloud") prefs.cloudVoice = name ?: CloudTtsEngine.DEFAULT_VOICE
        else if (prefs.voiceMode == "gemini") prefs.geminiVoice = name ?: "Kore"
        else prefs.voiceName = name
        engine().setVoice(name)
        restartIfPlaying()
    }

    val usingCloudVoice: Boolean get() = prefs.voiceMode == "cloud"
    val usingGeminiVoice: Boolean get() = prefs.voiceMode == "gemini"

    /** Telefon sesi ile Gemini sesi arasında geçiş. */
    fun setGeminiVoice(on: Boolean) {
        val wasPlaying = _state.value.playing
        prefs.voiceMode = if (on) "gemini" else "phone"
        if (on) GeminiBankWorker.schedule(context)
        resetEngine()
        if (wasPlaying) play()
    }

    /** Gemini ses ayarı (ses / anlatıcı tonu) değişti. */
    fun geminiSettingsChanged() {
        if (usingGeminiVoice) { engine().setVoice(prefs.geminiVoice); restartIfPlaying() }
    }

    /** Diyaloglar için ikinci ses; null kapatır. */
    fun setDialogVoice(name: String?) {
        prefs.dialogVoiceEnabled = name != null
        if (name != null) {
            if (usingCloudVoice) prefs.cloudDialogVoice = name else prefs.phoneDialogVoice = name
        }
        engine().setDialogVoice(name)
        restartIfPlaying()
    }

    fun currentDialogVoice(): String? = if (!prefs.dialogVoiceEnabled) null
    else if (usingCloudVoice) prefs.cloudDialogVoice else prefs.phoneDialogVoice

    /** Telefon sesi ile doğal ses (Google WaveNet) arasında geçiş. */
    fun setCloudVoice(on: Boolean) {
        val wasPlaying = _state.value.playing
        prefs.voiceMode = if (on) "cloud" else "phone"
        resetEngine()
        if (wasPlaying) play()
    }

    /** Ses motoru değişti (ör. başka bir TTS uygulaması seçildi). */
    fun resetEngine() {
        pause()
        engineWatch?.cancel()
        engine?.shutdown()
        engine = null
        engine()
    }

    // ------------------------------------------------------------------ Kitap açma

    /**
     * Kitabı açar. Zaten açıksa bir şey yapmaz. [chapter]/[anchor] verilirse oradan başlar,
     * yoksa kalınan yerden devam eder.
     */
    fun open(bookId: Long, chapter: Int? = null, anchor: String? = null, autoPlay: Boolean = false) {
        if (_state.value.bookId == bookId && content != null && chapter == null) {
            if (autoPlay && !_state.value.playing) play()
            return
        }
        openJob?.cancel()
        openJob = scope.launch {
            pause()
            engine()
            _state.update {
                it.copy(loading = true, error = null, bookId = bookId, lengthsReady = false, nextBookId = null, nextBookTitle = null)
            }
            val t0 = System.currentTimeMillis()
            Kayit.log("Kitap açılıyor: $bookId")
            try {
                val entity = withContext(Dispatchers.IO) { db.books().get(bookId) }
                    ?: throw IllegalStateException("Kitap bulunamadı")
                bookLanguage = entity.language
                withContext(Dispatchers.IO) { com.seslikitap.data.Journal.markStarted(context, bookId) }
                if (entity.status == BookStatus.DRM) throw IllegalStateException("Bu kitap DRM ile korunuyor, seslendirilemez")
                val newContent = withContext(Dispatchers.IO) {
                    val opened = BookOpener.open(context, entity.uri, entity.fileName)
                    // PDF'in kapağı taramada üretilemediyse (Drive) şimdi ilk sayfadan üret
                    opened.localPdf?.let { f ->
                        if (entity.coverPath == null || entity.chapterCount == 0) {
                            val cover = entity.coverPath ?: PdfBooks.renderCover(context, f, entity.uri)
                            db.books().setCoverAndChapters(bookId, cover, opened.book.spine.size)
                        }
                    }
                    BookContent(bookId, opened.book, opened.source, ExtractOptions(prefs.skipFootnotes, prefs.skipPageNumbers))
                }
                content?.close()
                content = newContent
                val absent = entity.progress > 0f && entity.lastOpenedAt != null &&
                    System.currentTimeMillis() - entity.lastOpenedAt > 3 * 24 * 3600_000L
                _state.update { it.copy(longAbsence = absent) }
                db.books().markOpened(bookId, System.currentTimeMillis())

                val saved = withContext(Dispatchers.IO) { db.positions().get(bookId) }
                val (ch, sentence) = withContext(Dispatchers.IO) {
                    if (chapter != null) {
                        val c = newContent.chapter(chapter.coerceIn(0, newContent.chapterCount - 1))
                        c.index to c.sentenceForAnchor(anchor)
                    } else if (saved != null && saved.chapterIndex in 0 until newContent.chapterCount) {
                        val c = newContent.chapter(saved.chapterIndex)
                        // Başka cihazda (web) kaydedilen yer: cümle metniyle tam yeri bul
                        val ex = prefs.positionExcerpt(bookId)?.take(40)
                        val byText = ex?.takeIf { it.length >= 10 }?.let { e -> (0 until c.sentences.size).firstOrNull { c.text(it).startsWith(e) } }
                        c.index to (byText ?: c.sentenceIndexOf(saved.blockIndex, saved.sentenceIndex))
                    } else 0 to 0
                }
                val startChapter = withContext(Dispatchers.IO) { newContent.nextNonEmpty(ch) }
                    ?: throw IllegalStateException(
                        if (BookOpener.isPdf(entity.fileName))
                            "Bu PDF'te okunabilir yazı bulunamadı. Taranmış (sayfa fotoğrafı) bir PDF olabilir."
                        else "Kitapta seslendirilecek metin bulunamadı"
                    )
                _state.update {
                    it.copy(
                        bookTitle = entity.title, author = entity.author, coverPath = entity.coverPath,
                        chapterCount = newContent.chapterCount, loading = false, finished = false,
                    )
                }
                showChapter(startChapter, if (startChapter == ch) sentence else 0)
                chapterLengths = null
                scope.launch(Dispatchers.IO) {
                    val lens = ChapterLengthStore.getOrCompute(context, newContent)
                    if (content === newContent) { chapterLengths = lens; _state.update { it.copy(lengthsReady = true) } }
                }
                Kayit.log("Kitap açıldı: ${entity.title} (${System.currentTimeMillis() - t0} ms)")
                if (autoPlay) play()
            } catch (e: Throwable) {
                Kayit.error("Kitap açılamadı ($bookId)", e)
                _state.update { it.copy(loading = false, error = e.message ?: "Kitap açılamadı") }
            }
        }
    }

    private suspend fun showChapter(index: Int, sentence: Int) {
        val c = content ?: return
        val ch = withContext(Dispatchers.IO) { c.chapter(index) }
        _chapter.value = ch
        val s = sentence.coerceIn(0, (ch.sentences.size - 1).coerceAtLeast(0))
        _state.update {
            it.copy(
                chapterIndex = index, chapterTitle = ch.title,
                sentenceIndex = s, sentenceCount = ch.sentences.size,
            )
        }
        // Sonraki bölümü önceden hazırla
        scope.launch(Dispatchers.IO) { if (index + 1 < c.chapterCount) runCatching { c.chapter(index + 1) } }
        // Doğal seste: Wi-Fi'dayken sıradaki bölümleri önceden seslendir
        if (prefs.voiceMode == "cloud" && prefs.prefetchEnabled) PrefetchWorker.enqueue(context, c.bookId)
    }

    // ------------------------------------------------------------------ Dinleme süresi

    private var listenStart = 0L
    private var listenJob: Job? = null

    private fun startListenClock() {
        listenStart = System.currentTimeMillis()
        listenJob?.cancel()
        listenJob = scope.launch {
            while (true) {
                delay(30_000)
                flushListening()
            }
        }
    }

    private fun stopListenClock() {
        listenJob?.cancel()
        listenJob = null
        flushListening()
        listenStart = 0L
    }

    /** Geçen dinleme süresini güne ve kitaba yazar. */
    private fun flushListening() {
        val start = listenStart
        val id = _state.value.bookId
        if (start == 0L || id == null) return
        val now = System.currentTimeMillis()
        val ms = (now - start).coerceIn(0, 10 * 60_000L)
        listenStart = now
        if (ms < 1000) return
        val day = java.time.LocalDate.now().toString()
        scope.launch(Dispatchers.IO) {
            db.listening().ensure(day)
            db.listening().add(day, ms)
            db.books().addListened(id, ms)
        }
    }

    // ------------------------------------------------------------------ Oynatma

    fun play() {
        val st = _state.value
        if (content == null || st.loading) return
        if (!requestFocus()) return
        val e = engine()
        _state.update { it.copy(playing = true, error = null, finished = false) }
        if (prefs.myTimeDnd) FocusMode.enter(context, prefs)
        startListenClock()
        PlaybackService.start(context)
        if (e.status.value != EngineStatus.Ready) {
            scope.launch {
                val s = e.status.first { it != EngineStatus.Initializing }
                if (s == EngineStatus.Ready && _state.value.playing) speakFromCurrent()
                else if (s != EngineStatus.Ready) _state.update { it.copy(playing = false) }
            }
            return
        }
        speakFromCurrent()
    }

    private var backupJob: Job? = null

    /** Duraklatınca 20 sn sonra Drive yedeğini güncelle (bilgisayarla eşitleme için). */
    private fun scheduleSyncUpload() {
        if (!prefs.autoBackup) return
        backupJob?.cancel()
        backupJob = scope.launch {
            delay(20_000)
            withContext(Dispatchers.IO) {
                try { com.seslikitap.data.Backup(context).upload() } catch (e: Exception) { Kayit.error("Eşitleme yüklemesi", e) }
            }
        }
    }

    fun pause() {
        if (!_state.value.playing) return
        scheduleSyncUpload()
        stopListenClock()
        generation++
        engine?.stop()
        _state.update { it.copy(playing = false) }
        FocusMode.exit(context, prefs)
        savePosition()
    }

    fun toggle() = if (_state.value.playing) pause() else play()

    /** En son dinlenen kitabı kaldığı yerden çalar (widget / Android Auto). Kitap yoksa false. */
    suspend fun resumeLast(): Boolean {
        _state.value.bookId?.let { play(); return true }
        val last = withContext(Dispatchers.IO) { db.books().lastOpened() } ?: return false
        open(last.id, autoPlay = true)
        return true
    }

    /** Kitabı kapatır, bildirimi kaldırır. */
    fun stop() {
        pause()
        abandonFocus()
        cancelSleepTimer()
        content?.close()
        content = null
        _chapter.value = null
        _state.update { PlayerState(rate = it.rate, pitch = it.pitch, engine = it.engine) }
    }

    private fun speakFromCurrent() {
        bridgeId = null
        bridgeThen = null
        generation++
        queuedUntil = _state.value.sentenceIndex - 1
        consecutiveErrors = 0
        fillQueue(flush = true)
    }

    private fun restartIfPlaying() {
        if (_state.value.playing) {
            engine?.stop()
            speakFromCurrent()
        }
    }

    private fun fillQueue(flush: Boolean) {
        val ch = _chapter.value ?: return
        val e = engine ?: return
        if (e is GroupSpeechEngine) { fillGroups(ch, e, flush); return }
        var first = flush
        while (queuedUntil < ch.sentences.size - 1 && queuedUntil - _state.value.sentenceIndex < LOOKAHEAD - 1) {
            queuedUntil++
            val alt = prefs.dialogVoiceEnabled && ch.dialog.getOrElse(queuedUntil) { false }
            e.speak(utteranceId(ch.index, queuedUntil), spokenText(ch, queuedUntil), first, alt)
            first = false
        }
    }

    /** Gemini parçalarının bitiş cümleleri (bölüm başına; her seferinde aynı sınırlar → önbellek tutar). */
    private var groupEndsFor: Triple<Chapter, Int, IntArray>? = null

    /**
     * Bölümü eşit boylu parçalara böler (en fazla [Prefs.geminiGroupMax] karakter, mümkünse paragraf sonunda):
     * bölüm sonunda kısa bir artık parça kalıp günlük istek hakkı boşa gitmesin.
     */
    private fun groupEnds(ch: Chapter): IntArray {
        val max = prefs.geminiGroupMax
        groupEndsFor?.let { (c, m, ends) -> if (c === ch && m == max) return ends }
        return GeminiAudio.groupEnds(ch, max).also { groupEndsFor = Triple(ch, max, it) }
    }

    /** Gemini: cümleleri parça parça sıraya koyar; çalan parçadan sonra bir parça önceden hazırlanır. */
    private fun fillGroups(ch: Chapter, e: GroupSpeechEngine, flush: Boolean) {
        val ends = groupEnds(ch)
        var first = flush
        while (queuedUntil < ch.sentences.size - 1) {
            // Çalan parça + bir sonraki parça sırada olsun (sonraki, çalarken hazırlanır)
            val cur = _state.value.sentenceIndex
            val curEnd = ends.firstOrNull { it >= cur } ?: break
            if (!first && queuedUntil > curEnd) break
            val next = queuedUntil + 1
            val gi = ends.indexOfFirst { it >= next }.takeIf { it >= 0 } ?: break
            val start = if (gi == 0) 0 else ends[gi - 1] + 1
            val end = ends[gi]
            val items = (start..end).map { utteranceId(ch.index, it) to spokenText(ch, it) }
            val text = GeminiAudio.groupText(ch, start, end, prefs.normalizeText, prefs.geminiDialogVoice != null)
            e.speakGroup(items, next - start, first, text)
            queuedUntil = end
            first = false
        }
    }

    private fun spokenText(ch: Chapter, i: Int): String = spokenTextOf(ch, i, prefs.normalizeText)

    private fun utteranceId(chapter: Int, sentence: Int) = "$generation:$chapter:$sentence"

    private fun parseId(id: String): Triple<Int, Int, Int>? {
        val p = id.split(':')
        if (p.size != 3) return null
        return Triple(p[0].toIntOrNull() ?: return null, p[1].toIntOrNull() ?: return null, p[2].toIntOrNull() ?: return null)
    }

    override fun onStart(id: String) {
        scope.launch {
            val (gen, ch, s) = parseId(id) ?: return@launch
            if (gen != generation || ch != _state.value.chapterIndex) return@launch
            consecutiveErrors = 0
            measureSpeed(ch, s)
            _state.update { it.copy(sentenceIndex = s) }
            savePosition()
        }
    }

    override fun onDone(id: String) {
        if (finishBridge(id)) return
        scope.launch {
            val (gen, chIdx, s) = parseId(id) ?: return@launch
            if (gen != generation || !_state.value.playing) return@launch
            val ch = _chapter.value ?: return@launch
            if (chIdx != ch.index) return@launch
            // Bölüm sonu özeti açıksa, bölüm bitmeden özeti hazırla (geçişte bekleme olmasın)
            if (prefs.chapterSummary && hasGeminiKey && s >= ch.sentences.size - 25 && summaryPrepared != "${_state.value.bookId}:${ch.index}") {
                summaryPrepared = "${_state.value.bookId}:${ch.index}"
                val idx = ch.index
                scope.launch { runCatching { chapterSummary(idx) } }
            }
            if (s >= ch.sentences.size - 1) {
                onChapterFinished()
            } else {
                if (_state.value.sentenceIndex < s + 1) _state.update { it.copy(sentenceIndex = s + 1) }
                fillQueue(flush = false)
            }
        }
    }

    override fun onError(id: String, code: Int) {
        Log.w(TAG, "TTS hatası $code, $id")
        if (finishBridge(id)) return
        scope.launch {
            val (gen, _, _) = parseId(id) ?: return@launch
            if (gen != generation) return@launch
            consecutiveErrors++
            if (consecutiveErrors > 5) {
                pause()
                _state.update { it.copy(error = "Seslendirme motoru hata veriyor (kod $code). Ayarlardan başka bir ses deneyin.") }
                return@launch
            }
            onDone(id)
        }
    }

    private suspend fun onChapterFinished() {
        val c = content ?: return
        if (_state.value.sleepAtChapterEnd) {
            cancelSleepTimer()
            val next = withContext(Dispatchers.IO) { c.nextNonEmpty(_state.value.chapterIndex + 1) }
            pause()
            if (next != null) showChapter(next, 0)
            savePosition()
            return
        }
        val next = withContext(Dispatchers.IO) { c.nextNonEmpty(_state.value.chapterIndex + 1) }
        if (next == null) {
            pause()
            _state.update { it.copy(finished = true) }
            _state.value.bookId?.let { id ->
                scope.launch {
                    val next = withContext(Dispatchers.IO) {
                        markFinished(id)
                        com.seslikitap.data.Journal.markFinished(context, id)
                        // Okuma listesinden düş; sıradaki: önce serinin devamı, yoksa okuma listesindeki ilk kitap
                        prefs.readingList = prefs.readingList - id
                        val e = db.books().get(id)
                        val series = e?.series
                        val inSeries = if (series != null) db.books().nextInSeries(series, e?.seriesIndex ?: 0.0) else null
                        inSeries?.let { it to false }
                            ?: prefs.readingList.firstNotNullOfOrNull { db.books().get(it) }?.let { it to true }
                    }
                    if (next != null) _state.update {
                        it.copy(nextBookId = next.first.id, nextBookTitle = next.first.title, nextFromList = next.second)
                    }
                }
            }
            return
        }
        // Bölüm sonu sesli özeti (isteğe bağlı): özet okunur, sonra sıradaki bölüm başlar
        if (prefs.chapterSummary && hasGeminiKey) {
            val finished = _state.value.chapterIndex
            val summary = withTimeoutOrNull(25_000) { runCatching { chapterSummary(finished) }.getOrNull() }
            if (!summary.isNullOrBlank() && _state.value.playing) {
                showChapter(next, 0)
                speakBridge("Bölümün kısa özeti. $summary") { speakFromCurrent() }
                return
            }
        }
        showChapter(next, 0)
        speakFromCurrent()
    }

    // ------------------------------------------------------------------ Gezinme

    fun seek(chapter: Int, sentence: Int) {
        val c = content ?: return
        scope.launch {
            val wasPlaying = _state.value.playing
            if (wasPlaying) { generation++; engine?.stop() }
            val target = withContext(Dispatchers.IO) {
                c.nextNonEmpty(chapter.coerceIn(0, c.chapterCount - 1)) ?: c.nextNonEmpty(chapter, -1)
            } ?: return@launch
            if (target != _state.value.chapterIndex || _chapter.value == null) showChapter(target, if (target == chapter) sentence else 0)
            else _state.update { it.copy(sentenceIndex = sentence.coerceIn(0, it.sentenceCount - 1)) }
            savePosition()
            if (wasPlaying) speakFromCurrent()
        }
    }

    fun seekToAnchor(chapter: Int, anchor: String?) {
        val c = content ?: return
        scope.launch {
            val ch = withContext(Dispatchers.IO) { c.chapter(chapter.coerceIn(0, c.chapterCount - 1)) }
            seek(ch.index, ch.sentenceForAnchor(anchor))
        }
    }

    fun nextSentence() {
        val st = _state.value
        if (st.sentenceIndex + 1 < st.sentenceCount) seek(st.chapterIndex, st.sentenceIndex + 1)
        else nextChapter()
    }

    fun previousSentence() {
        val st = _state.value
        if (st.sentenceIndex > 0) seek(st.chapterIndex, st.sentenceIndex - 1)
        else if (st.chapterIndex > 0) {
            val c = content ?: return
            scope.launch {
                val prev = withContext(Dispatchers.IO) { c.nextNonEmpty(st.chapterIndex - 1, -1) } ?: return@launch
                val count = withContext(Dispatchers.IO) { c.chapter(prev).sentences.size }
                seek(prev, count - 1)
            }
        }
    }

    /**
     * Zamana göre ileri/geri sarar (kulaklık düğmeleri, bildirim, sakin ekran kaydırma).
     * Süre, ölçülen okuma hızıyla karaktere çevrilir; bölüm sınırında bir sonraki/önceki bölüme geçer.
     */
    fun skipSeconds(seconds: Int) {
        val ch = _chapter.value ?: return
        val st = _state.value
        if (ch.sentences.isEmpty()) return
        fun len(i: Int) = ch.sentences[i].let { it.end - it.start } + 1
        var budget = (kotlin.math.abs(seconds) * cps * st.rate.coerceAtLeast(0.3f)).toInt()
        var i = st.sentenceIndex.coerceIn(0, ch.sentences.size - 1)
        if (seconds > 0) {
            while (i < ch.sentences.size - 1 && budget > 0) { budget -= len(i); i++ }
            if (budget > 0) { nextChapter(); return }
        } else {
            if (i == 0) { previousSentence(); return }
            while (i > 0 && budget > 0) { i--; budget -= len(i) }
        }
        seek(ch.index, i)
    }

    fun nextChapter() {
        val c = content ?: return
        val st = _state.value
        scope.launch {
            val n = withContext(Dispatchers.IO) { c.nextNonEmpty(st.chapterIndex + 1) } ?: return@launch
            seek(n, 0)
        }
    }

    fun previousChapter() {
        val c = content ?: return
        val st = _state.value
        scope.launch {
            // Bölümün başındaysak bir önceki bölüme, değilsek bu bölümün başına git.
            val target = if (st.sentenceIndex > 2) st.chapterIndex
            else withContext(Dispatchers.IO) { c.nextNonEmpty(st.chapterIndex - 1, -1) } ?: st.chapterIndex
            seek(target, 0)
        }
    }

    // ------------------------------------------------------------------ Ayarlar

    fun setRate(rate: Float) {
        val r = rate.coerceIn(0.3f, 4f)
        prefs.speechRate = r
        _state.update { it.copy(rate = r) }
        engine?.setRate(r)
        restartIfPlaying()
    }

    fun setPitch(pitch: Float) {
        val p = pitch.coerceIn(0.5f, 2f)
        prefs.speechPitch = p
        _state.update { it.copy(pitch = p) }
        engine?.setPitch(p)
        restartIfPlaying()
    }

    /** Dipnot/sayfa numarası ayarı değişti: kitabı yeni ayarlarla yeniden aç (konum korunur). */
    fun reloadContent() {
        val id = _state.value.bookId ?: return
        val wasPlaying = _state.value.playing
        val c = content ?: return
        pause()
        savePosition()
        content = null
        c.close()
        open(id, autoPlay = wasPlaying)
    }

    fun engines(): List<Pair<String, String>> = engine().engines()

    fun setEngine(pkg: String?) {
        prefs.ttsEngine = pkg
        prefs.voiceName = null
        resetEngine()
    }

    // ------------------------------------------------------------------ Kalan süre

    @Volatile private var chapterLengths: IntArray? = null
    private var lastStartAt = 0L
    private var lastStartChapter = -1
    private var lastStartSentence = -1

    /** Cümleler arasındaki süreden gerçek okuma hızını öğrenir (hız 1,0'a göre saniyedeki karakter). */
    private fun measureSpeed(chapter: Int, sentence: Int) {
        val now = System.currentTimeMillis()
        val ch = _chapter.value
        if (ch != null && chapter == lastStartChapter && sentence == lastStartSentence + 1 &&
            lastStartSentence in ch.sentences.indices
        ) {
            val elapsed = now - lastStartAt
            val chars = ch.sentences[lastStartSentence].let { it.end - it.start }
            if (elapsed in 400..30_000 && chars >= 20) {
                val sample = chars / (elapsed / 1000f) / _state.value.rate.coerceAtLeast(0.3f)
                if (sample in 5f..40f) {
                    cps = cps * 0.95f + sample * 0.05f
                    if (++speedSamples % 20 == 0) prefs.charsPerSecond = cps
                }
            }
        }
        lastStartAt = now
        lastStartChapter = chapter
        lastStartSentence = sentence
    }

    private var cps = prefs.charsPerSecond
    private var speedSamples = 0

    /** (bölümün kalanı, kitabın kalanı) ms; kitap uzunlukları henüz hesaplanmadıysa ikincisi null. */
    fun remainingMs(): Pair<Long, Long?> {
        val st = _state.value
        val ch = _chapter.value ?: return 0L to null
        var chars = 0L
        for (i in st.sentenceIndex until ch.sentences.size) chars += ch.sentences[i].let { it.end - it.start }
        val perSecond = cps * st.rate.coerceAtLeast(0.3f)
        val chapterMs = (chars / perSecond * 1000).toLong()
        val lens = chapterLengths ?: return chapterMs to null
        var rest = 0L
        for (i in (ch.index + 1) until lens.size) rest += lens[i]
        return chapterMs to chapterMs + (rest / perSecond * 1000).toLong()
    }

    /** Tahmini toplam dinleme süresi (ms) — kayıtlı bölüm uzunluklarından. */
    fun estimatedTotalMs(lengths: IntArray): Long = (lengths.sum() / (cps * _state.value.rate.coerceAtLeast(0.3f)) * 1000).toLong()

    // ------------------------------------------------------------------ Nerede kalmıştık? (Gemini)

    val hasGeminiKey: Boolean get() = !prefs.geminiKey.isNullOrBlank()

    /** Kaldığı yere kadar olan son kısım (en fazla [maxChars]); kaldığı yerden sonrası asla. */
    private suspend fun recapText(maxChars: Int = 60_000): Pair<List<String>, String> = withContext(Dispatchers.IO) {
        val c = content ?: return@withContext emptyList<String>() to ""
        val st = _state.value
        val parts = ArrayList<String>()
        var total = 0
        val cur = c.chapter(st.chapterIndex)
        val curText = (0 until st.sentenceIndex.coerceAtMost(cur.sentences.size)).joinToString(" ") { cur.text(it) }
        parts.add(curText); total += curText.length
        var i = st.chapterIndex - 1
        while (i >= 0 && total < maxChars) {
            val ch = c.chapter(i)
            val t = (0 until ch.sentences.size).joinToString(" ") { ch.text(it) }
            parts.add(0, "[${ch.title}] " + t.takeLast(maxChars - total))
            total += t.length
            i--
        }
        val titles = (0 until st.chapterIndex).map { c.chapterTitle(it) }.distinct()
        titles to parts.joinToString("\n\n").takeLast(maxChars)
    }

    /** Gemini ile "nerede kalmıştık" özeti. */
    suspend fun recap(): String {
        val key = prefs.geminiKey?.takeIf { it.isNotBlank() } ?: throw java.io.IOException("Gemini anahtarı eklenmemiş")
        val st = _state.value
        val (titles, text) = recapText()
        if (text.length < 200) return "Kitabın henüz başındasın; özetlenecek kadar ilerlemedin."
        return Gemini.generate(key, Gemini.recapPrompt(st.bookTitle, st.author, titles, text))
    }

    // ------------------------------------------------------------------ Kitapla konuşma (Gemini)

    /** Açık kitabın dili (EPUB'dan; yabancı dil kitaplarında cümle çevirisi için). */
    var bookLanguage: String? = null
        private set

    val isForeignBook: Boolean get() = bookLanguage?.lowercase()?.let { it.isNotBlank() && !it.startsWith("tr") } == true

    suspend fun askBook(question: String, history: List<Pair<String, String>>): String {
        val st = _state.value
        val (_, text) = recapText(120_000)
        if (text.length < 100) return "Kitabın henüz çok başındasın; biraz dinledikten sonra sorabilirsin."
        return Gemini.generate(BookAi.key(context), BookAi.askPrompt(st.bookTitle, st.author, text, history.takeLast(4), question))
    }

    suspend fun characters(fresh: Boolean = false): String {
        val st = _state.value
        return AiCache.getOrPut(context, "karakter|${st.bookId}|${st.chapterIndex}", fresh) {
            val (_, text) = recapText(150_000)
            if (text.length < 300) "Henüz karakter tanıtacak kadar ilerlemedin."
            else Gemini.generate(BookAi.key(context), BookAi.charactersPrompt(st.bookTitle, st.author, text), 0.2)
        }
    }

    suspend fun thoughtQuestion(fresh: Boolean = false): String {
        val st = _state.value
        return AiCache.getOrPut(context, "dusunce|${st.bookId}|${st.chapterIndex}", fresh) {
            val (_, text) = recapText(15_000)
            if (text.length < 300) "Biraz daha dinledikten sonra sana güzel bir soru sorabilirim."
            else Gemini.generate(BookAi.key(context), BookAi.thoughtPrompt(st.bookTitle, st.author, text), 0.8)
        }
    }

    /** Bölüm sonu özeti (bölümün tamamı gönderilir; sonraki bölümler gönderilmez). */
    suspend fun chapterSummary(index: Int): String {
        val c = content ?: throw java.io.IOException("Kitap açık değil")
        val st = _state.value
        return AiCache.getOrPut(context, "bolumozet|${st.bookId}|$index") {
            val ch = withContext(Dispatchers.IO) { c.chapter(index) }
            val text = (0 until ch.sentences.size).joinToString(" ") { ch.text(it) }.take(80_000)
            if (text.length < 1500) "" else Gemini.generate(BookAi.key(context), BookAi.chapterSummaryPrompt(st.bookTitle, ch.title, text), 0.4)
        }
    }

    suspend fun translate(ch: Chapter, sentence: Int): String {
        val text = ch.text(sentence)
        return AiCache.getOrPut(context, "ceviri|$text") {
            val ctx = (maxOf(0, sentence - 3) until sentence).joinToString(" ") { ch.text(it) }
            Gemini.generate(BookAi.key(context), BookAi.translatePrompt(text, ctx), 0.2)
        }
    }

    /** Kitap kulübü: sohbetin sıradaki mesajı. */
    suspend fun clubReply(transcript: List<Pair<String, String>>): String {
        val st = _state.value
        val (_, text) = recapText(40_000)
        return Gemini.generate(BookAi.key(context), BookAi.clubPrompt(st.bookTitle, st.author, text, transcript), 0.8)
    }

    private var summaryPrepared: String? = null

    // Bölüm sonu özeti çalınırken sıradaki bölüme geçiş
    private var bridgeId: String? = null
    private var bridgeThen: (() -> Unit)? = null

    private fun speakBridge(text: String, then: () -> Unit) {
        generation++
        val id = "kopru:$generation"
        bridgeId = id
        bridgeThen = then
        engine().speak(id, text, true)
    }

    private fun finishBridge(id: String): Boolean {
        if (id != bridgeId) return false
        scope.launch {
            if (bridgeId != id) return@launch
            bridgeId = null
            val then = bridgeThen
            bridgeThen = null
            if (_state.value.playing) then?.invoke()
        }
        return true
    }

    /** Kitaptan bağımsız bir metni (ör. özet) seslendirir; kitap duraklatılır, konum değişmez. */
    fun speakAside(text: String) {
        pause()
        engine().speak("aside:${System.currentTimeMillis()}", text, true)
    }

    fun dismissLongAbsence() = _state.update { it.copy(longAbsence = false) }

    // ------------------------------------------------------------------ Kaçırdım

    /** Paragrafın başına (zaten baştaysa bir önceki paragrafa) döner. */
    fun replayParagraph() {
        val c = content ?: return
        val ch = _chapter.value ?: return
        val target = c.paragraphStart(ch, _state.value.sentenceIndex)
        if (target == _state.value.sentenceIndex && target == 0) previousSentence() else seek(ch.index, target)
    }

    // ------------------------------------------------------------------ Uyku zamanlayıcısı

    private var fadeVolume = 1f

    private fun setVolume(v: Float) {
        fadeVolume = v
        engine?.setVolume(v)
    }

    fun setSleepTimer(minutes: Int) {
        cancelSleepTimer()
        val total = minutes * 60_000L
        val end = System.currentTimeMillis() + total
        _state.update { it.copy(sleepAt = end, sleepAtChapterEnd = false) }
        startShakeDetection()
        sleepJob = scope.launch {
            // Son dakikada ses yavaşça kısılır
            val fade = minOf(60_000L, total / 2)
            delay(total - fade)
            val steps = 12
            for (i in 1..steps) {
                setVolume(1f - 0.85f * i / steps)
                delay(fade / steps)
            }
            pause()
            setVolume(1f)
            stopShakeDetection()
            _state.update { it.copy(sleepAt = null) }
        }
    }

    fun setSleepAtChapterEnd() {
        cancelSleepTimer()
        _state.update { it.copy(sleepAtChapterEnd = true) }
    }

    fun cancelSleepTimer() {
        sleepJob?.cancel()
        sleepJob = null
        stopShakeDetection()
        if (fadeVolume != 1f) setVolume(1f)
        _state.update { it.copy(sleepAt = null, sleepAtChapterEnd = false) }
    }

    // Telefonu sallayınca uyku zamanlayıcısı 10 dk uzar
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
    private var lastShake = 0L
    private val shakeListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val (x, y, z) = Triple(e.values[0], e.values[1], e.values[2])
            val g = kotlin.math.sqrt(x * x + y * y + z * z) / android.hardware.SensorManager.GRAVITY_EARTH
            val now = System.currentTimeMillis()
            if (g > 2.6f && now - lastShake > 2000) {
                lastShake = now
                scope.launch { extendSleepByShake() }
            }
        }
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {}
    }
    private var shakeRegistered = false

    private fun startShakeDetection() {
        if (shakeRegistered) return
        val acc = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        shakeRegistered = sensorManager.registerListener(shakeListener, acc, android.hardware.SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopShakeDetection() {
        if (!shakeRegistered) return
        sensorManager.unregisterListener(shakeListener)
        shakeRegistered = false
    }

    private fun extendSleepByShake() {
        val at = _state.value.sleepAt ?: return
        val leftMin = ((at - System.currentTimeMillis()) / 60_000L).toInt().coerceAtLeast(0)
        setSleepTimer(leftMin + 10)
        _state.update { it.copy(error = "Uyku zamanlayıcısı 10 dakika uzatıldı") }
    }

    // ------------------------------------------------------------------ Yer imleri ve konum

    fun addBookmark() {
        val st = _state.value
        val id = st.bookId ?: return
        val ch = _chapter.value ?: return
        if (ch.sentences.isEmpty()) return
        val s = ch.sentences[st.sentenceIndex]
        val excerpt = ch.text(st.sentenceIndex).take(160)
        scope.launch(Dispatchers.IO) {
            db.bookmarks().insert(
                BookmarkEntity(
                    bookId = id, chapterIndex = ch.index, blockIndex = s.blockIndex,
                    sentenceIndex = ch.sentenceInBlock(st.sentenceIndex), excerpt = excerpt, note = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
    }

    fun addQuote(ch: Chapter, sentence: Int) {
        val st = _state.value
        val id = st.bookId ?: return
        if (sentence !in ch.sentences.indices) return
        val s = ch.sentences[sentence]
        container.library.addQuote(
            com.seslikitap.data.QuoteEntity(
                bookId = id, chapterIndex = ch.index, blockIndex = s.blockIndex,
                sentenceIndex = ch.sentenceInBlock(sentence), text = ch.text(sentence),
                bookTitle = st.bookTitle, author = st.author, createdAt = System.currentTimeMillis(),
            )
        )
    }

    private val container get() = context.container

    /** Sesli not: dinlerken söylenen not, o anki cümleye bağlı olarak alıntılara eklenir. */
    fun addNote(note: String) {
        val st = _state.value
        val id = st.bookId ?: return
        val ch = _chapter.value ?: return
        val i = st.sentenceIndex.coerceIn(0, (ch.sentences.size - 1).coerceAtLeast(0))
        if (ch.sentences.isEmpty()) return
        val s = ch.sentences[i]
        container.library.addQuote(
            com.seslikitap.data.QuoteEntity(
                bookId = id, chapterIndex = ch.index, blockIndex = s.blockIndex,
                sentenceIndex = ch.sentenceInBlock(i), text = "📝 $note — (“${ch.text(i).take(120)}”)",
                bookTitle = st.bookTitle, author = st.author, createdAt = System.currentTimeMillis(),
            )
        )
    }

    fun goToBookmark(b: BookmarkEntity) {
        val c = content ?: return
        scope.launch {
            val ch = withContext(Dispatchers.IO) { c.chapter(b.chapterIndex.coerceIn(0, c.chapterCount - 1)) }
            seek(ch.index, ch.sentenceIndexOf(b.blockIndex, b.sentenceIndex))
        }
    }

    private fun savePosition() {
        val st = _state.value
        val id = st.bookId ?: return
        val ch = _chapter.value ?: return
        val c = content ?: return
        if (ch.sentences.isEmpty() || st.sentenceIndex !in ch.sentences.indices) return
        val s = ch.sentences[st.sentenceIndex]
        val pos = PositionEntity(id, ch.index, s.blockIndex, ch.sentenceInBlock(st.sentenceIndex), System.currentTimeMillis())
        val progress = ((ch.index + st.sentenceIndex.toFloat() / ch.sentences.size.coerceAtLeast(1)) / c.chapterCount)
            .coerceIn(0f, 1f)
        // Kaldığın cümle her seferinde kaydedilir; kitaplıktaki ilerleme yüzdesi (tüm ekranları
        // yeniden çizdirdiği için) en fazla 30 sn'de bir, bölüm değişince ya da duraklatınca.
        val now = System.currentTimeMillis()
        val writeProgress = !_state.value.playing || ch.index != lastProgressChapter ||
            now - lastProgressWrite > 30_000L
        if (writeProgress) {
            lastProgressWrite = now
            lastProgressChapter = ch.index
        }
        val excerpt = ch.text(st.sentenceIndex).take(80)
        scope.launch(Dispatchers.IO) {
            db.positions().upsert(pos)
            prefs.setPositionExcerpt(id, excerpt)
            if (writeProgress) db.books().setProgress(id, progress)
        }
    }

    private var lastProgressWrite = 0L
    private var lastProgressChapter = -1

    private suspend fun markFinished(id: Long) {
        db.books().setFinished(id, true, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ Ses odağı

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var resumeOnFocusGain = false
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            scope.launch {
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; pause() }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        if (_state.value.playing) { resumeOnFocusGain = true; pause() }
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
                    }
                }
            }
        }
        .build()

    private fun requestFocus(): Boolean =
        audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object {
        private const val TAG = "Player"
        private const val LOOKAHEAD = 3
    }
}
