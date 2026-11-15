package com.seslikitap.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.IOException

class EpubException(message: String, cause: Throwable? = null) : IOException(message, cause)

data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: Set<String>,
)

data class TocEntry(
    val title: String,
    val path: String,
    val fragment: String?,
    val depth: Int,
    /** Bu girdinin işaret ettiği okuma sırası (spine) indeksi. */
    val spineIndex: Int,
)

data class EpubBook(
    val opfPath: String,
    val title: String,
    val authors: List<String>,
    val language: String?,
    val publisher: String?,
    val description: String?,
    val coverPath: String?,
    /** Okuma sırasındaki içerik dosyalarının arşiv içi yolları. */
    val spine: List<String>,
    val toc: List<TocEntry>,
    /** İçerik DRM ile şifrelenmişse true (seslendirilemez). */
    val drmProtected: Boolean,
) {
    val author: String get() = authors.joinToString(", ")
}

object EpubParser {

    /** Ayrıştırıcı mantığı değiştiğinde artırılır; uygulama eski kayıtları yeniden tarar. */
    const val VERSION = 1

    fun parse(zip: ZipArchive, fallbackTitle: String, includeToc: Boolean = true): EpubBook {
        val opfPath = findOpfPath(zip) ?: throw EpubException("Kitap içerik dosyası (OPF) bulunamadı")
        val opfBytes = zip.read(opfPath) ?: throw EpubException("Kitap içerik dosyası okunamadı")
        val opf = parseXml(opfBytes)
        val baseDir = PathUtil.dirOf(opfPath)

        // ---- Meta veri
        val metadata = opf.allElements.firstOrNull { it.local() == "metadata" } ?: opf
        val metaEls = metadata.allElements

        val title = metaEls.filter { it.local() == "title" }
            .map { TurkishText.cleanMeta(it.text()) }
            .firstOrNull { it.isNotBlank() }
            ?: TurkishText.cleanMeta(fallbackTitle.substringBeforeLast('.'))

        val creators = metaEls.filter { it.local() == "creator" }
        val authorEls = creators.filter {
            val role = it.attrLocal("role")
            role.isEmpty() || role.equals("aut", ignoreCase = true)
        }.ifEmpty { creators }
        val authors = authorEls.map { TurkishText.cleanMeta(it.text()) }
            .filter { it.isNotBlank() && !it.equals("unknown", true) && !it.equals("bilinmiyor", true) }
            .distinct()

        val language = metaEls.firstOrNull { it.local() == "language" }?.text()?.trim()?.ifBlank { null }
        val publisher = metaEls.firstOrNull { it.local() == "publisher" }
            ?.let { TurkishText.cleanMeta(it.text()) }?.ifBlank { null }
        val description = metaEls.firstOrNull { it.local() == "description" }
            ?.let { TurkishText.cleanMeta(Jsoup.parse(it.text()).text()) }?.ifBlank { null }

        // ---- Manifest
        val manifest = LinkedHashMap<String, ManifestItem>()
        opf.allElements.filter { it.local() == "item" }.forEach { el ->
            val href = el.attr("href")
            if (href.isBlank()) return@forEach
            val path = PathUtil.resolve(baseDir, href)
            val real = zip.findEntry(path) ?: path
            val id = el.attr("id").ifBlank { real }
            manifest.putIfAbsent(
                id,
                ManifestItem(
                    id = id,
                    path = real,
                    mediaType = el.attr("media-type").trim().lowercase(),
                    properties = el.attr("properties").split(' ').filter { it.isNotBlank() }.toSet(),
                )
            )
        }

        // ---- Okuma sırası (spine)
        val spineEl = opf.allElements.firstOrNull { it.local() == "spine" }
        val itemrefs = opf.allElements.filter { it.local() == "itemref" }
        val linear = ArrayList<String>()
        val nonLinear = ArrayList<String>()
        for (ref in itemrefs) {
            val item = manifest[ref.attr("idref")] ?: continue
            if (!isContentDoc(item.path, item.mediaType) || !zip.exists(item.path)) continue
            if (ref.attr("linear").equals("no", true)) nonLinear.add(item.path) else linear.add(item.path)
        }
        var spine = (if (linear.isNotEmpty()) linear else nonLinear).distinct()
        if (spine.isEmpty()) {
            spine = manifest.values.filter { isContentDoc(it.path, it.mediaType) && zip.exists(it.path) }
                .map { it.path }.distinct().sortedWith(NaturalOrder)
        }
        if (spine.isEmpty()) {
            spine = zip.entryNames.filter { isContentDoc(it, "") && !it.startsWith("META-INF/", true) }
                .sortedWith(NaturalOrder)
        }
        if (spine.isEmpty()) throw EpubException("Kitapta okunabilir bölüm bulunamadı")

        // ---- Kapak
        val coverPath = findCover(zip, opf, manifest, baseDir, spine)

        // ---- İçindekiler
        val toc = if (includeToc) buildToc(zip, manifest, spineEl, spine) else emptyList()

        return EpubBook(
            opfPath = opfPath,
            title = title,
            authors = authors,
            language = language,
            publisher = publisher,
            description = description,
            coverPath = coverPath,
            spine = spine,
            toc = toc,
            drmProtected = detectDrm(zip),
        )
    }

    // ------------------------------------------------------------------ OPF

    private fun findOpfPath(zip: ZipArchive): String? {
        zip.read("META-INF/container.xml")?.let { bytes ->
            try {
                val doc = parseXml(bytes)
                val rootfiles = doc.allElements.filter { it.local() == "rootfile" }
                val preferred = rootfiles.firstOrNull {
                    it.attr("media-type").contains("oebps-package", true)
                } ?: rootfiles.firstOrNull()
                val candidates = (listOfNotNull(preferred) + rootfiles).map { it.attr("full-path") }
                for (c in candidates) {
                    if (c.isBlank()) continue
                    zip.findEntry(PathUtil.resolve("", c))?.let { return it }
                }
            } catch (_: Exception) {
            }
        }
        return zip.entryNames.filter { it.endsWith(".opf", true) }.minByOrNull { it.count { c -> c == '/' } }
    }

    // ------------------------------------------------------------------ Kapak

    private val IMAGE_EXT = Regex(".*\\.(jpe?g|png|gif|webp|bmp)$", RegexOption.IGNORE_CASE)

    private fun isImage(path: String, mediaType: String) =
        (mediaType.startsWith("image/") && !mediaType.contains("svg")) || IMAGE_EXT.matches(path)

    private fun findCover(
        zip: ZipArchive,
        opf: Document,
        manifest: Map<String, ManifestItem>,
        baseDir: String,
        spine: List<String>,
    ): String? {
        fun ok(p: String?): String? = p?.let { zip.findEntry(it) }?.takeIf { isImage(it, "") }

        // 1) EPUB 3: properties="cover-image"
        manifest.values.firstOrNull { "cover-image" in it.properties }?.let { ok(it.path) }?.let { return it }

        // 2) EPUB 2: <meta name="cover" content="id"/>
        val metaCover = opf.allElements.firstOrNull {
            it.local() == "meta" && it.attr("name").equals("cover", true)
        }?.attr("content")?.trim()
        if (!metaCover.isNullOrEmpty()) {
            manifest[metaCover]?.let { ok(it.path) }?.let { return it }
            manifest.values.firstOrNull { it.id.equals(metaCover, true) }?.let { ok(it.path) }?.let { return it }
            ok(PathUtil.resolve(baseDir, metaCover))?.let { return it }
        }

        // 3) id/ad içinde "cover"/"kapak" geçen resim
        manifest.values.firstOrNull {
            isImage(it.path, it.mediaType) &&
                (it.id.contains("cover", true) || it.id.contains("kapak", true))
        }?.let { ok(it.path) }?.let { return it }
        manifest.values.firstOrNull {
            isImage(it.path, it.mediaType) && it.path.substringAfterLast('/').let { n ->
                n.contains("cover", true) || n.contains("kapak", true)
            }
        }?.let { ok(it.path) }?.let { return it }

        // 4) <guide><reference type="cover"> ya da ilk bölümdeki ilk resim
        val guideCover = opf.allElements.firstOrNull {
            it.local() == "reference" && it.attr("type").equals("cover", true)
        }?.attr("href")?.let { PathUtil.resolve(baseDir, it) }
        for (page in listOfNotNull(guideCover, spine.firstOrNull())) {
            firstImageIn(zip, page)?.let { return it }
        }
        return null
    }

    private fun firstImageIn(zip: ZipArchive, pagePath: String): String? {
        val bytes = zip.read(pagePath, 4 * 1024 * 1024) ?: return null
        if (IMAGE_EXT.matches(pagePath)) return zip.findEntry(pagePath)
        val doc = try { Jsoup.parse(TextDecoder.decode(bytes)) } catch (e: Exception) { return null }
        val dir = PathUtil.dirOf(pagePath)
        for (el in doc.allElements) {
            val src = when (el.local()) {
                "img" -> el.attr("src")
                "image" -> el.attr("xlink:href").ifBlank { el.attr("href") }
                else -> ""
            }
            if (src.isNotBlank()) {
                zip.findEntry(PathUtil.resolve(dir, src))?.takeIf { isImage(it, "") }?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------ İçindekiler

    private fun buildToc(
        zip: ZipArchive,
        manifest: Map<String, ManifestItem>,
        spineEl: Element?,
        spine: List<String>,
    ): List<TocEntry> {
        val spineIndex = HashMap<String, Int>()
        spine.forEachIndexed { i, p -> spineIndex.putIfAbsent(p.lowercase(), i) }
        fun idx(path: String): Int = spineIndex[(zip.findEntry(path) ?: path).lowercase()] ?: -1

        // EPUB 3 nav
        val nav = manifest.values.firstOrNull { "nav" in it.properties }
        if (nav != null) {
            val entries = try { parseNav(zip, nav.path, ::idx) } catch (e: Exception) { emptyList() }
            if (entries.size >= 1) return entries
        }
        // EPUB 2 NCX
        val ncxId = spineEl?.attr("toc").orEmpty()
        val ncx = manifest[ncxId]
            ?: manifest.values.firstOrNull { it.mediaType.contains("dtbncx") }
            ?: zip.entryNames.firstOrNull { it.endsWith(".ncx", true) }?.let { ManifestItem("ncx", it, "", emptySet()) }
        if (ncx != null) {
            val entries = try { parseNcx(zip, ncx.path, ::idx) } catch (e: Exception) { emptyList() }
            if (entries.isNotEmpty()) return entries
        }
        // Yedek: her okuma dosyası bir bölüm
        return spine.mapIndexed { i, p -> TocEntry("Bölüm ${i + 1}", p, null, 0, i) }
    }

    private fun parseNav(zip: ZipArchive, path: String, idx: (String) -> Int): List<TocEntry> {
        val bytes = zip.read(path) ?: return emptyList()
        val doc = Jsoup.parse(TextDecoder.decode(bytes))
        val navs = doc.allElements.filter { it.local() == "nav" }
        val tocNav = navs.firstOrNull { it.attr("epub:type").contains("toc") || it.attr("role") == "doc-toc" }
            ?: navs.firstOrNull() ?: return emptyList()
        val dir = PathUtil.dirOf(path)
        val out = ArrayList<TocEntry>()
        fun walk(list: Element, depth: Int) {
            for (li in list.children().filter { it.local() == "li" }) {
                val a = li.children().firstOrNull { it.local() == "a" || it.local() == "span" }
                    ?: li.selectFirst("a")
                if (a != null) {
                    val href = a.attr("href")
                    val title = TurkishText.cleanMeta(a.text())
                    if (href.isNotBlank() && title.isNotBlank()) {
                        val p = PathUtil.resolve(dir, href)
                        val i = idx(p)
                        if (i >= 0) out.add(TocEntry(title, zip.findEntry(p) ?: p, PathUtil.fragmentOf(href), depth, i))
                    }
                }
                li.children().filter { it.local() == "ol" || it.local() == "ul" }.forEach { walk(it, depth + 1) }
            }
        }
        tocNav.allElements.firstOrNull { it.local() == "ol" || it.local() == "ul" }?.let { walk(it, 0) }
        return out
    }

    private fun parseNcx(zip: ZipArchive, path: String, idx: (String) -> Int): List<TocEntry> {
        val bytes = zip.read(path) ?: return emptyList()
        val doc = parseXml(bytes)
        val dir = PathUtil.dirOf(path)
        val out = ArrayList<TocEntry>()
        fun walk(parent: Element, depth: Int) {
            for (np in parent.children().filter { it.local() == "navpoint" }) {
                val label = np.children().firstOrNull { it.local() == "navlabel" }?.text().orEmpty()
                val src = np.children().firstOrNull { it.local() == "content" }?.attr("src").orEmpty()
                val title = TurkishText.cleanMeta(label)
                if (src.isNotBlank() && title.isNotBlank()) {
                    val p = PathUtil.resolve(dir, src)
                    val i = idx(p)
                    if (i >= 0) out.add(TocEntry(title, zip.findEntry(p) ?: p, PathUtil.fragmentOf(src), depth, i))
                }
                walk(np, depth + 1)
            }
        }
        val navMap = doc.allElements.firstOrNull { it.local() == "navmap" } ?: return emptyList()
        walk(navMap, 0)
        return out
    }

    // ------------------------------------------------------------------ DRM

    private val FONT_OBFUSCATION = setOf(
        "http://www.idpf.org/2008/embedding",
        "http://ns.adobe.com/pdf/enc#rc",
    )
    private val FONT_EXT = Regex(".*\\.(otf|ttf|woff2?|eot)$", RegexOption.IGNORE_CASE)

    private fun detectDrm(zip: ZipArchive): Boolean {
        val bytes = zip.read("META-INF/encryption.xml") ?: return false
        val doc = try { parseXml(bytes) } catch (e: Exception) { return false }
        for (enc in doc.allElements.filter { it.local() == "encrypteddata" }) {
            val algo = enc.allElements.firstOrNull { it.local() == "encryptionmethod" }?.attr("Algorithm").orEmpty()
            val uri = enc.allElements.firstOrNull { it.local() == "cipherreference" }?.attr("URI").orEmpty()
            if (algo.lowercase() in FONT_OBFUSCATION) continue
            if (FONT_EXT.matches(uri)) continue
            if (uri.isNotBlank()) return true
        }
        return false
    }

    // ------------------------------------------------------------------ Yardımcılar

    internal fun parseXml(bytes: ByteArray): Document =
        Jsoup.parse(TextDecoder.decode(bytes), "", Parser.xmlParser())

    private val CONTENT_EXT = Regex(".*\\.(x?html?|xml)$", RegexOption.IGNORE_CASE)

    private fun isContentDoc(path: String, mediaType: String): Boolean {
        if (path.endsWith(".opf", true) || path.endsWith(".ncx", true)) return false
        if (path.startsWith("META-INF/", true)) return false
        if (mediaType.contains("html")) return true
        if (mediaType.isNotEmpty() && (mediaType.startsWith("image/") || mediaType.contains("css") ||
                mediaType.contains("font") || mediaType.contains("javascript") || mediaType.contains("ncx"))
        ) return false
        return CONTENT_EXT.matches(path)
    }
}

/** Elemanın ad alanı ön eki olmayan küçük harfli adı ("dc:title" -> "title"). */
internal fun Element.local(): String = tagName().substringAfterLast(':').lowercase()

/** "opf:role" ya da "role" özniteliği. */
internal fun Element.attrLocal(name: String): String {
    for (a in attributes()) {
        if (a.key.substringAfterLast(':').equals(name, true)) return a.value.trim()
    }
    return ""
}

/** "bolum2" < "bolum10" sıralaması. */
object NaturalOrder : Comparator<String> {
    private val CHUNK = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val x = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val y = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]; val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pn = p.trimStart('0'); val qn = q.trimStart('0')
                if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
            } else p.compareTo(q)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}
