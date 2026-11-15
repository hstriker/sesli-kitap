package com.seslikitap.core

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

enum class BlockKind { HEADING, PARAGRAPH }

/** Bir paragraf ya da başlık. [anchors]: bu bloğun başında/içinde bulunan HTML id'leri. */
data class TextBlock(
    val text: String,
    val kind: BlockKind,
    val anchors: List<String> = emptyList(),
)

data class ChapterText(
    val title: String?,
    val blocks: List<TextBlock>,
) {
    /** id -> blok indeksi (içindekiler tablosundaki #parça bağlantıları için). */
    fun blockIndexOfAnchor(anchor: String): Int? =
        blocks.indexOfFirst { anchor in it.anchors }.takeIf { it >= 0 }
}

data class ExtractOptions(
    val skipFootnotes: Boolean = true,
    val skipPageNumbers: Boolean = true,
)

object ChapterTextExtractor {

    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "aside", "header", "footer", "main", "nav",
        "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul", "ol", "dl", "dt", "dd",
        "blockquote", "pre", "figure", "figcaption", "table", "tr", "td", "th", "caption",
        "hr", "body", "center", "address", "details", "summary",
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val DROP_TAGS = setOf(
        "script", "style", "noscript", "svg", "math", "img", "audio", "video", "iframe",
        "object", "embed", "rt", "rp", "head", "template", "button", "input", "select",
    )
    private val SELF_CLOSING_FIX = Regex("<(title|script|style|textarea|a|span|div|p|iframe)(\\s[^<>]*?)?/>", RegexOption.IGNORE_CASE)
    private val NOTE_MARK = Regex("^[\\[(]?(\\d{1,3}|[*†‡]{1,3}|[ivxlc]{1,5})[\\])]?$", RegexOption.IGNORE_CASE)
    private val PAGE_NUM = Regex("^[\\[(]?(s\\.|sayfa)?\\s*\\d{1,4}[\\])]?$", RegexOption.IGNORE_CASE)

    fun extract(bytes: ByteArray, options: ExtractOptions = ExtractOptions()): ChapterText {
        // XHTML'deki <title/> gibi kendini kapatan etiketler HTML ayrıştırıcıda tüm metni yutar; düzelt.
        val html = SELF_CLOSING_FIX.replace(TextDecoder.decode(bytes)) { m ->
            "<${m.groupValues[1]}${m.groupValues[2]}></${m.groupValues[1]}>"
        }
        val doc = Jsoup.parse(html)
        val docTitle = TurkishText.cleanMeta(doc.title()).ifBlank { null }
        val body = doc.body()
        clean(body, options)

        val blocks = ArrayList<TextBlock>()
        val sb = StringBuilder()
        val pendingAnchors = ArrayList<String>()
        var headingDepth = 0

        fun flush() {
            val t = TurkishText.cleanWhitespace(sb.toString())
            sb.setLength(0)
            if (t.isEmpty() || t.none { it.isLetterOrDigit() }) return
            blocks.add(TextBlock(t, if (headingDepth > 0) BlockKind.HEADING else BlockKind.PARAGRAPH, pendingAnchors.toList()))
            pendingAnchors.clear()
        }

        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when (node) {
                    is TextNode -> sb.append(node.wholeText)
                    is Element -> {
                        val tag = node.normalName()
                        val id = node.id().ifBlank { if (tag == "a") node.attr("name") else "" }
                        if (tag in BLOCK_TAGS) flush()
                        if (tag == "br") flush()
                        if (tag in HEADING_TAGS) headingDepth++
                        if (id.isNotBlank()) pendingAnchors.add(id)
                    }
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element) {
                    val tag = node.normalName()
                    if (tag in BLOCK_TAGS) flush()
                    if (tag in HEADING_TAGS) headingDepth--
                }
            }
        }, body)
        flush()

        val firstHeading = blocks.firstOrNull { it.kind == BlockKind.HEADING }?.text
        return ChapterText(firstHeading ?: docTitle, blocks)
    }

    private fun clean(body: Element, o: ExtractOptions) {
        val remove = ArrayList<Element>()
        for (el in body.allElements) {
            if (el === body) continue
            val tag = el.normalName()
            if (tag in DROP_TAGS) { remove.add(el); continue }
            val type = el.attr("epub:type").lowercase()
            val role = el.attr("role").lowercase()
            val cls = el.className().lowercase()
            if (el.attr("aria-hidden") == "true" || el.hasAttr("hidden")) {
                remove.add(el); continue
            }
            if (o.skipPageNumbers) {
                if ("pagebreak" in type || role == "doc-pagebreak") { remove.add(el); continue }
                if ((cls.contains("pagenum") || cls.contains("page-number") || cls.contains("pagebreak") ||
                        cls.contains("sayfa")) && PAGE_NUM.matches(el.text().trim())
                ) { remove.add(el); continue }
            }
            if (o.skipFootnotes) {
                if ("noteref" in type || role == "doc-noteref") { remove.add(el); continue }
                if (Regex("\\b(footnotes?|endnotes?|rearnotes?|note)\\b").containsMatchIn(type) ||
                    role == "doc-footnote" || role == "doc-endnote" || role == "doc-endnotes"
                ) { remove.add(el); continue }
                if (tag == "sup" && NOTE_MARK.matches(el.text().trim())) { remove.add(el); continue }
                if (tag == "a" && el.attr("href").contains('#') && NOTE_MARK.matches(el.text().trim())) {
                    remove.add(el); continue
                }
                if (cls.split(' ').any { it == "footnote" || it == "footnotes" || it == "dipnot" || it == "endnote" } &&
                    tag != "body"
                ) { remove.add(el); continue }
            }
        }
        remove.forEach { if (it.parent() != null) it.remove() }
    }
}
