package com.seslikitap.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.seslikitap.Kayit
import com.seslikitap.container
import com.seslikitap.core.BlockKind
import com.seslikitap.core.ChapterText
import com.seslikitap.core.EpubBook
import com.seslikitap.core.EpubParser
import com.seslikitap.core.ExtractOptions
import com.seslikitap.core.PdfTextCleaner
import com.seslikitap.core.TextBlock
import com.seslikitap.core.TocEntry
import com.seslikitap.core.TurkishText
import com.seslikitap.player.ChapterSource
import com.seslikitap.player.EpubChapterSource
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/** Açılmış kitap: yapı (bölümler, içindekiler) + bölüm metni kaynağı. */
class OpenedBook(val book: EpubBook, val source: ChapterSource, val localPdf: File? = null)

/** EPUB ve PDF'i aynı şekilde açar. */
object BookOpener {

    fun isPdf(fileName: String) = fileName.endsWith(".pdf", ignoreCase = true)

    fun open(context: Context, uri: String, fileName: String): OpenedBook {
        if (isPdf(fileName)) return PdfBooks.open(context, Uri.parse(uri), fileName)
        val zip = EpubOpener.open(context, Uri.parse(uri))
        try {
            val epub = EpubParser.parse(zip, fileName, includeToc = true)
            return OpenedBook(epub, EpubChapterSource(zip, epub))
        } catch (e: Throwable) {
            zip.close()
            throw e
        }
    }
}

object PdfBooks {

    private const val PAGES_PER_CHUNK = 10

    /** PDF'in telefondaki kopyası (Drive'dan indirilir ya da telefondaki dosya uygulama klasörüne kopyalanır). */
    fun localFile(context: Context, uri: Uri): File {
        val s = uri.toString()
        if (DriveUri.isDrive(s)) return DriveCache.ensure(context, context.container.drive, DriveUri.id(s))
        val dir = File(context.filesDir, "pdf").apply { mkdirs() }
        val f = File(dir, sha1(s) + ".pdf")
        val size = try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        } catch (e: Exception) { -1L }
        if (f.exists() && (size <= 0 || f.length() == size)) return f
        val tmp = File(dir, f.name + ".part")
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            ?: throw IOException("PDF açılamadı")
        tmp.renameTo(f)
        return f
    }

    fun open(context: Context, uri: Uri, fileName: String): OpenedBook {
        val file = localFile(context, uri)
        val doc = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: Exception) {
            throw IOException(
                if (e.javaClass.simpleName.contains("Password")) "Bu PDF parolayla korunuyor" else "PDF açılamadı: ${e.message}",
                e,
            )
        }
        try {
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val pages = doc.numberOfPages
            val chapters = chaptersFromOutline(doc, pages) ?: chunked(pages)
            val info = doc.documentInformation
            val title = TurkishText.cleanMeta(info?.title).takeIf { it.length >= 2 }
                ?: TurkishText.cleanMeta(fileName.substringBeforeLast('.'))
            val author = TurkishText.cleanMeta(info?.author)
            val spine = chapters.map { "pdf:${it.first}-${it.second}" }
            val toc = chapters.mapIndexed { i, c -> TocEntry(c.third, spine[i], null, 0, i) }
            val book = EpubBook(
                opfPath = "", title = title, authors = listOfNotNull(author.ifBlank { null }),
                language = null, publisher = null, description = null, coverPath = null,
                spine = spine, toc = toc, drmProtected = false,
            )
            return OpenedBook(book, PdfChapterSource(doc, chapters), file)
        } catch (e: Throwable) {
            doc.close()
            throw e
        }
    }

    /** PDF içindekilerinden bölümler: (ilk sayfa, son sayfa, başlık), sayfalar 0'dan başlar. */
    private fun chaptersFromOutline(doc: PDDocument, pages: Int): List<Triple<Int, Int, String>>? {
        val outline = doc.documentCatalog.documentOutline ?: return null
        val starts = ArrayList<Pair<Int, String>>()
        fun collect(node: PDOutlineNode, depth: Int) {
            for (item in node.children()) {
                val page = try { item.findDestinationPage(doc) } catch (e: Exception) { null }
                val idx = page?.let { doc.pages.indexOf(it) } ?: -1
                val t = TurkishText.cleanMeta(item.title)
                if (idx >= 0 && t.isNotBlank()) starts.add(idx to t)
                if (depth == 0) collect(item, 1)
            }
        }
        try { collect(outline, 0) } catch (e: Exception) { return null }
        val sorted = starts.distinctBy { it.first }.sortedBy { it.first }
        if (sorted.size < 2) return null
        val out = ArrayList<Triple<Int, Int, String>>()
        if (sorted.first().first > 0) out.add(Triple(0, sorted.first().first - 1, "Başlangıç"))
        sorted.forEachIndexed { i, (start, t) ->
            val end = if (i + 1 < sorted.size) sorted[i + 1].first - 1 else pages - 1
            if (end >= start) out.add(Triple(start, end, t))
        }
        // Çok uzun bölümleri (ör. 80 sayfa) böl: bölüm geçişleri ve kalan süre daha anlamlı olsun
        return out.flatMap { (s, e, t) ->
            if (e - s < 40) listOf(Triple(s, e, t))
            else (s..e step 20).mapIndexed { k, a -> Triple(a, minOf(e, a + 19), if (k == 0) t else "$t (devam ${k + 1})") }
        }
    }

    private fun chunked(pages: Int): List<Triple<Int, Int, String>> =
        (0 until maxOf(pages, 1) step PAGES_PER_CHUNK).map { s ->
            val e = minOf(pages - 1, s + PAGES_PER_CHUNK - 1)
            Triple(s, e, "Sayfa ${s + 1}–${e + 1}")
        }

    /** PDF'in ilk sayfasından kapak resmi üretir (telefon PDF görüntüleyicisiyle). */
    fun renderCover(context: Context, file: File, bookUri: String): String? {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> renderCover(context, pfd, bookUri) }
        } catch (e: Throwable) {
            null
        }
    }

    fun renderCover(context: Context, pfd: ParcelFileDescriptor, bookUri: String): String? {
        return try {
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) null else r.openPage(0).use { page ->
                val h = 480
                val w = (page.width * h.toFloat() / page.height).toInt().coerceIn(100, 800)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                val out = CoverStore.fileFor(context, bookUri)
                FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bmp.recycle()
                out.absolutePath
            }
        }
        } catch (e: Throwable) {
            Kayit.error("PDF kapağı üretilemedi", e)
            null
        }
    }

    /** Taramada: telefondaki PDF'in sayfa sayısı ve kapağı (PDF'i kopyalamadan). */
    fun scanLocal(context: Context, uri: Uri, bookUri: String): Pair<Int, String?> {
        val pageCount = context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        } ?: 0
        val cover = context.contentResolver.openFileDescriptor(uri, "r")?.use { renderCover(context, it, bookUri) }
        return pageCount to cover
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** PDF bölümlerinin metni: sayfa sayfa çıkarılır, üst/alt bilgi ve tireler temizlenir. */
class PdfChapterSource(
    private val doc: PDDocument,
    private val chapters: List<Triple<Int, Int, String>>,
) : ChapterSource {

    override fun chapterText(index: Int, options: ExtractOptions): ChapterText? = synchronized(doc) {
        val (start, end, title) = chapters[index]
        val stripper = PDFTextStripper()
        stripper.setSortByPosition(true)
        stripper.setParagraphEnd(PdfTextCleaner.PARAGRAPH.toString())
        stripper.setLineSeparator("\n")
        // Üst/alt bilgi tespiti için bölümün çevresindeki sayfalar da örnek alınır
        val pages = (start..end).map { p ->
            stripper.setStartPage(p + 1)
            stripper.setEndPage(p + 1)
            try { stripper.getText(doc) } catch (e: Exception) { "" }
        }
        val blocks: List<TextBlock> = PdfTextCleaner.clean(pages)
        val withTitle = if (blocks.firstOrNull()?.kind == BlockKind.HEADING) blocks
        else listOf(TextBlock(title, BlockKind.HEADING)) + blocks
        ChapterText(title, if (blocks.isEmpty()) emptyList() else withTitle)
    }

    override fun close() = synchronized(doc) { doc.close() }
}
