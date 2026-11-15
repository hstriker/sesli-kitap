package com.seslikitap.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.seslikitap.Kayit
import com.seslikitap.Prefs
import com.seslikitap.container
import kotlinx.coroutines.sync.Mutex
import com.seslikitap.core.EpubBook
import com.seslikitap.drive.DriveAuthException
import com.seslikitap.drive.DriveCache
import com.seslikitap.core.EpubParser
import com.seslikitap.core.TurkishText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val total: Int = 0,
    val processed: Int = 0,
    val added: Int = 0,
    val failed: Int = 0,
    val lastMessage: String? = null,
)

class LibraryRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
    private val prefs: Prefs,
) {
    private val _scan = MutableStateFlow(ScanState())
    val scan: StateFlow<ScanState> = _scan.asStateFlow()

    val books: Flow<List<BookEntity>> = db.books().observeAll()
    val folders: Flow<List<FolderEntity>> = db.folders().observeAll()

    fun observeBook(id: Long) = db.books().observe(id)
    suspend fun getBook(id: Long) = db.books().get(id)
    suspend fun markOpened(id: Long) = db.books().markOpened(id, System.currentTimeMillis())
    suspend fun setFavorite(id: Long, favorite: Boolean) = db.books().setFavorite(id, favorite)
    val listening = db.listening().observeAll()

    // ------------------------------------------------------------------ Klasörler

    fun addFolder(treeUri: Uri) {
        scope.launch {
            try {
                context.contentResolver.takePersistableUriPermission(
                    treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Kalıcı izin alınamadı", e)
            }
            db.folders().upsert(FolderEntity(treeUri.toString(), folderName(treeUri), System.currentTimeMillis()))
            startScan()
        }
    }

    /** Google Drive klasörü ekler ve taramayı başlatır. */
    fun addDriveFolder(folderId: String, name: String) {
        scope.launch {
            db.folders().upsert(FolderEntity(DriveUri.folder(folderId), name, System.currentTimeMillis()))
            startScan()
        }
    }

    fun removeFolder(uri: String) {
        scope.launch {
            val ids = db.books().forFolder(uri).map { it.id }
            ids.chunked(500).forEach {
                db.positions().delete(it)
                db.bookmarks().deleteForBooks(it)
            }
            db.books().forFolder(uri).forEach { b ->
                b.coverPath?.let { File(it).delete() }
                if (DriveUri.isDrive(b.uri)) DriveCache.delete(context, DriveUri.id(b.uri))
            }
            db.books().deleteFolder(uri)
            db.folders().delete(uri)
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun folderName(treeUri: Uri): String {
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) return it } }
        } catch (_: Exception) {
        }
        return DocumentsContract.getTreeDocumentId(treeUri).substringAfterLast(':').ifBlank { "Klasör" }
    }

    // ------------------------------------------------------------------ Tarama

    private data class FoundFile(
        val uri: String,
        val name: String,
        val size: Long,
        val lastModified: Long,
        val relativePath: String,
    )

    /**
     * Taramayı arka plan görevi olarak başlatır (uygulama kapansa da sürer).
     * [full]: değişmemiş kitapları da yeniden oku. [includeDrive]: Drive klasörlerini de tara.
     */
    fun startScan(full: Boolean = false, includeDrive: Boolean = true) {
        ScanWorker.enqueue(context, full, includeDrive)
    }

    /** Açılışta: telefondaki klasörler her zaman, Drive en fazla 12 saatte bir. */
    fun autoScan() {
        val driveDue = System.currentTimeMillis() - prefs.lastDriveScan > DRIVE_AUTO_INTERVAL
        startScan(full = false, includeDrive = driveDue)
    }

    private val scanMutex = Mutex()

    /** Asıl tarama (ScanWorker çağırır). Aynı anda tek tarama çalışır. */
    suspend fun runScan(full: Boolean, includeDrive: Boolean) {
        if (!scanMutex.tryLock()) return
        try {
            withContext(Dispatchers.IO) { doScan(full, includeDrive) }
        } finally {
            scanMutex.unlock()
        }
    }

    private suspend fun doScan(full: Boolean, includeDrive: Boolean) {
        Kayit.log("Tarama başladı (tam=$full, drive=$includeDrive)")
        val scanStart = System.currentTimeMillis()
        run {
            _scan.value = ScanState(running = true, phase = "Klasörler okunuyor…")
            var added = 0
            var failed = 0
            var message: String? = null
            try {
                val folders = db.folders().all().filter { includeDrive || !DriveUri.isDrive(it.uri) }
                var driveOk = true
                for (folder in folders) {
                    val isDrive = DriveUri.isDrive(folder.uri)
                    Kayit.log("Klasör listeleniyor: ${folder.name} (${if (isDrive) "Drive" else "telefon"})")
                    val listStart = System.currentTimeMillis()
                    val found = try {
                        if (isDrive) listDrive(folder) else listEpubs(Uri.parse(folder.uri))
                    } catch (e: DriveAuthException) {
                        Kayit.error("Drive yetkisi yok", e)
                        message = e.message
                        driveOk = false
                        continue
                    } catch (e: SecurityException) {
                        message = "\"${folder.name}\" klasörüne erişim izni kayboldu. Klasörü kaldırıp yeniden ekleyin."
                        continue
                    } catch (e: Exception) {
                        if (isDrive) driveOk = false
                        Kayit.error("Klasör listelenemedi: ${folder.name}", e)
                        message = "\"${folder.name}\" klasörü okunamadı: ${e.message}"
                        continue
                    }
                    Kayit.log("Klasör listelendi: ${folder.name}, ${found.size} kitap, ${System.currentTimeMillis() - listStart} ms")
                    var existing = db.books().forFolder(folder.uri).associateBy { it.uri }
                    val todo = found.filter { f ->
                        val e = existing[f.uri]
                        full || e == null || e.sizeBytes != f.size || e.lastModified != f.lastModified ||
                            e.parserVersion < EpubParser.VERSION ||
                            (isDrive && e.status == BookStatus.ERROR) // Drive'da geçici hatalar olabilir: yeniden dene
                    }
                    _scan.update { it.copy(phase = "${folder.name}: ${found.size} kitap bulundu", total = it.total + todo.size) }

                    // Yeni kitapları önce dosya adlarıyla hemen ekle: kitaplıkta görünür ve dinlenebilir olsunlar.
                    // Başlık, yazar ve kapak aşağıda tek tek okunup güncellenir.
                    val newUris = todo.filter { existing[it.uri] == null }.mapTo(HashSet()) { it.uri }
                    if (newUris.isNotEmpty()) {
                        val now = System.currentTimeMillis()
                        todo.filter { it.uri in newUris }.chunked(200).forEach { chunk ->
                            db.books().insertAll(chunk.map { placeholder(folder.uri, it, now) })
                        }
                        existing = db.books().forFolder(folder.uri).associateBy { it.uri }
                    }

                    val counter = AtomicInteger(0)
                    val failCounter = AtomicInteger(0)
                    val newCounter = AtomicInteger(0)
                    val networkFailures = AtomicInteger(0)
                    val sem = Semaphore(if (isDrive) DRIVE_PARALLELISM else PARALLELISM)
                    coroutineScope {
                        todo.map { f ->
                            async {
                                sem.withPermit {
                                    val old = existing[f.uri]
                                    val entity = processFile(folder.uri, f, old)
                                    if (entity == null) {
                                        // Ağ hatası: kitabı sorunlu sayma, sonraki taramada yeniden denenir.
                                        networkFailures.incrementAndGet()
                                        counter.incrementAndGet()
                                        _scan.update { it.copy(processed = it.processed + 1) }
                                        return@withPermit
                                    }
                                    if (entity.status != BookStatus.OK) failCounter.incrementAndGet()
                                    if (f.uri in newUris) newCounter.incrementAndGet()
                                    if (old == null) {
                                        db.books().insert(entity)
                                    } else {
                                        db.books().update(entity.copy(id = old.id, addedAt = old.addedAt,
                                            lastOpenedAt = old.lastOpenedAt, progress = old.progress, finished = old.finished,
                                            favorite = old.favorite, listenedMs = old.listenedMs))
                                    }
                                    val done = counter.incrementAndGet()
                                    _scan.update { it.copy(processed = it.processed + 1, phase = "Kitaplar okunuyor… ($done/${todo.size})") }
                                }
                            }
                        }.awaitAll()
                    }
                    added += newCounter.get()
                    if (networkFailures.get() > 0) {
                        message = "${networkFailures.get()} kitap internet sorunu nedeniyle okunamadı; sonraki taramada yeniden denenecek."
                    }
                    failed += failCounter.get()

                    // Klasörden silinmiş kitaplar
                    val foundUris = found.mapTo(HashSet()) { it.uri }
                    // Drive boş liste döndürdüyse (geçici sorun olabilir) kitapları silme.
                    val gone = if (isDrive && found.isEmpty()) emptyList()
                    else existing.values.filter { it.uri !in foundUris }
                    gone.forEach { b ->
                        b.coverPath?.let { File(it).delete() }
                        if (DriveUri.isDrive(b.uri)) DriveCache.delete(context, DriveUri.id(b.uri))
                    }
                    gone.map { it.id }.chunked(500).forEach {
                        db.positions().delete(it)
                        db.bookmarks().deleteForBooks(it)
                        db.books().delete(it)
                    }
                }
                if (includeDrive && driveOk && folders.any { DriveUri.isDrive(it.uri) }) {
                    prefs.lastDriveScan = System.currentTimeMillis()
                }
            } catch (e: Exception) {
                Kayit.error("Tarama hatası", e)
                message = "Tarama sırasında hata: ${e.message}"
            } finally {
                Kayit.log("Tarama bitti: $added yeni, $failed sorunlu, ${System.currentTimeMillis() - scanStart} ms")
                _scan.update {
                    it.copy(
                        running = false, phase = "", added = added, failed = failed,
                        lastMessage = message ?: when {
                            added > 0 -> "$added yeni kitap eklendi"
                            it.processed > 0 -> "Kitaplık güncellendi"
                            else -> null
                        },
                    )
                }
            }
        }
    }

    fun clearScanMessage() = _scan.update { it.copy(lastMessage = null) }

    /** Henüz okunmamış kitap için geçici kayıt (dosya adıyla). parserVersion 0: tarama yarıda kalırsa yeniden okunur. */
    private fun placeholder(folderUri: String, f: FoundFile, now: Long): BookEntity {
        val title = TurkishText.cleanMeta(f.name.substringBeforeLast('.'))
        return BookEntity(
            uri = f.uri, folderUri = folderUri, fileName = f.name, relativePath = f.relativePath,
            title = title, author = "", language = null, sizeBytes = f.size, lastModified = f.lastModified,
            coverPath = null, chapterCount = 0, status = BookStatus.OK, errorMessage = null,
            searchKey = TurkishText.searchKey("$title ${f.name} ${f.relativePath}"),
            parserVersion = 0, addedAt = now,
        )
    }

    /** Kitabı okuyup kaydı hazırlar. Drive'da ağ hatası olursa null (kayıt değiştirilmez). */
    private fun processFile(folderUri: String, f: FoundFile, old: BookEntity?): BookEntity? {
        val now = System.currentTimeMillis()
        val r = processFileInner(folderUri, f, old, now)
        val ms = System.currentTimeMillis() - now
        when {
            r == null -> Kayit.log("Kitap atlandı (ağ): ${f.name} ($ms ms)")
            r.status != BookStatus.OK -> Kayit.log("Kitap sorunlu: ${f.name}: ${r.errorMessage} ($ms ms)")
            ms > 5000 -> Kayit.log("Kitap yavaş okundu: ${f.name} ($ms ms)")
        }
        return r
    }

    private fun processFileInner(folderUri: String, f: FoundFile, old: BookEntity?, now: Long): BookEntity? {
        val fallbackTitle = f.name.substringBeforeLast('.')
        var book: EpubBook? = null
        var coverPath: String? = null
        var error: String? = null
        try {
            if (old != null && DriveUri.isDrive(f.uri) && old.lastModified != f.lastModified) {
                DriveCache.delete(context, DriveUri.id(f.uri)) // Drive'da değişmiş: eski kopya geçersiz
            }
            EpubOpener.openForScan(context, Uri.parse(f.uri), f.size, f.lastModified, f.name).use { zip ->
                val b = EpubParser.parse(zip, f.name, includeToc = false)
                book = b
                b.coverPath?.let { p -> zip.read(p, 20 * 1024 * 1024) }?.let { bytes ->
                    coverPath = CoverStore.save(context, f.uri, bytes)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Kitap okunamadı: ${f.name}", e)
            if (DriveUri.isDrive(f.uri) && isNetworkError(e)) return null
            error = when (e) {
                is OutOfMemoryError -> "Kitap çok büyük, bellek yetmedi"
                else -> e.message ?: e.javaClass.simpleName
            }
        }
        if (coverPath == null) old?.coverPath?.let { File(it).delete() }
        val b = book
        val title = b?.title?.ifBlank { null } ?: TurkishText.cleanMeta(fallbackTitle)
        val author = b?.author.orEmpty()
        val status = when {
            b == null -> BookStatus.ERROR
            b.drmProtected -> BookStatus.DRM
            else -> BookStatus.OK
        }
        return BookEntity(
            uri = f.uri,
            folderUri = folderUri,
            fileName = f.name,
            relativePath = f.relativePath,
            title = title,
            author = author,
            language = b?.language,
            sizeBytes = f.size,
            lastModified = f.lastModified,
            coverPath = coverPath,
            chapterCount = b?.spine?.size ?: 0,
            status = status,
            errorMessage = if (status == BookStatus.DRM) "Bu kitap DRM ile korunuyor, seslendirilemez" else error,
            searchKey = TurkishText.searchKey("$title $author ${f.name} ${f.relativePath}"),
            parserVersion = EpubParser.VERSION,
            addedAt = old?.addedAt ?: now,
        )
    }

    private fun isNetworkError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is DriveAuthException || t is java.net.UnknownHostException || t is java.net.SocketTimeoutException ||
                t is java.net.SocketException || t is javax.net.ssl.SSLException
            ) return true
            val m = t.message.orEmpty()
            if (m.contains("Google Drive hatası (5") || m.contains("429")) return true
            t = t.cause
        }
        return false
    }

    /**
     * Drive klasörünün kitapları. "Drive'ım" (tüm Drive) seçildiyse toplu sorgu,
     * belirli bir klasör seçildiyse yalnızca o klasörün içi taranır.
     */
    private fun listDrive(folder: FolderEntity): List<FoundFile> {
        val id = DriveUri.id(folder.uri)
        val drive = context.container.drive
        val progress: (String) -> Unit = { msg -> _scan.update { it.copy(phase = "${folder.name}: $msg") } }
        val books = if (id == "root") drive.listEpubsUnder(id, progress) else drive.listEpubsInFolder(id, progress)
        return books.map { FoundFile(DriveUri.file(it.item.id), it.item.name, it.item.size, it.item.modified, it.relativePath) }
    }

    /** Klasör ağacını DocumentsContract ile dolaşır (DocumentFile'dan çok daha hızlı). */
    private fun listEpubs(treeUri: Uri): List<FoundFile> {
        val resolver = context.contentResolver
        val result = ArrayList<FoundFile>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val stack = ArrayDeque<Pair<String, String>>()
        stack.add(rootId to "")
        val visited = HashSet<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (stack.isNotEmpty()) {
            val (docId, rel) = stack.removeLast()
            if (!visited.add(docId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.add(id to "$rel$name/")
                    } else if (name.endsWith(".epub", ignoreCase = true) || mime == "application/epub+zip") {
                        val size = if (c.isNull(3)) -1L else c.getLong(3)
                        val lm = if (c.isNull(4)) 0L else c.getLong(4)
                        result.add(
                            FoundFile(
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                                name, size, lm, rel,
                            )
                        )
                    }
                }
            }
            if (stack.size % 50 == 0) _scan.update { it.copy(phase = "Klasörler okunuyor… (${result.size} kitap)") }
        }
        return result
    }

    /** Bir kitabı tam olarak (içindekilerle) açar. */
    suspend fun openBook(entity: BookEntity): EpubBook = withContext(Dispatchers.IO) {
        EpubOpener.open(context, Uri.parse(entity.uri)).use { zip ->
            EpubParser.parse(zip, entity.fileName, includeToc = true)
        }
    }

    companion object {
        private const val TAG = "LibraryRepository"
        private const val PARALLELISM = 4
        private const val DRIVE_PARALLELISM = 6
        private const val DRIVE_AUTO_INTERVAL = 12 * 60 * 60 * 1000L
    }
}
