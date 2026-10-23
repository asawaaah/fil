package com.asawaaah.fil.data.extract

import com.asawaaah.fil.data.feeds.Urls
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

enum class SpanKind { BOLD, ITALIC, LINK, CODE }

data class Span(val start: Int, val end: Int, val kind: SpanKind, val url: String? = null)

data class RichText(val text: String, val spans: List<Span> = emptyList()) {
    companion object {
        fun plain(text: String) = RichText(text)
    }
}

/** Reader content as a flat list of blocks, rendered natively by the reader screen. */
sealed interface Block {
    data class Heading(val level: Int, val text: RichText) : Block
    data class Paragraph(val text: RichText) : Block
    data class Quote(val text: RichText) : Block
    data class ListItem(val text: RichText, val ordered: Boolean, val index: Int, val depth: Int) : Block
    data class Code(val text: String) : Block
    data class Image(val url: String, val caption: String?) : Block
    data object Divider : Block

    /** Text that can be translated or summarized, or null for blocks without prose. */
    val prose: String?
        get() = when (this) {
            is Heading -> text.text
            is Paragraph -> text.text
            is Quote -> text.text
            is ListItem -> text.text
            is Image -> caption
            is Code, Divider -> null
        }

    /** Same block with its prose replaced (inline styles are dropped). */
    fun withProse(newText: String): Block = when (this) {
        is Heading -> copy(text = RichText.plain(newText))
        is Paragraph -> copy(text = RichText.plain(newText))
        is Quote -> copy(text = RichText.plain(newText))
        is ListItem -> copy(text = RichText.plain(newText))
        is Image -> copy(caption = newText)
        is Code, Divider -> this
    }
}

object HtmlBlocks {
    private val blockTags = setOf(
        "p", "div", "section", "article", "main", "header", "footer", "figure", "figcaption", "blockquote",
        "ul", "ol", "li", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "table", "thead", "tbody", "tfoot", "tr",
        "td", "th", "dl", "dt", "dd", "hr", "img", "picture", "aside", "details", "summary", "center",
    )

    fun parse(html: String?, baseUrl: String? = null): List<Block> {
        if (html.isNullOrBlank()) return emptyList()
        val body = Jsoup.parseBodyFragment(html, baseUrl ?: "").body()
        val out = ArrayList<Block>()
        Walker(out, baseUrl).container(body, depth = 0)
        return out.filterNot { it is Block.Paragraph && it.text.text.isBlank() }
    }

    /** Prose of the blocks as plain text, paragraphs separated by blank lines. */
    fun plainText(blocks: List<Block>): String = blocks.mapNotNull { b ->
        when (b) {
            is Block.ListItem -> "• " + b.text.text
            is Block.Image -> null
            else -> b.prose
        }
    }.filter { it.isNotBlank() }.joinToString("\n\n")

    private class Walker(private val out: MutableList<Block>, private val baseUrl: String?) {
        private val pending = InlineBuilder()

        fun container(el: Element, depth: Int) {
            for (child in el.childNodes()) node(child, depth)
            flushParagraph()
        }

        private fun node(node: Node, depth: Int) {
            when (node) {
                is TextNode -> pending.text(node.text())
                is Element -> element(node, depth)
            }
        }

        private fun element(el: Element, depth: Int) {
            val tag = el.normalName()
            if (tag !in blockTags) {
                if (tag == "br") pending.newline() else pending.inline(el) { child -> node(child, depth) }
                return
            }
            flushParagraph()
            when (tag) {
                "h1", "h2", "h3", "h4", "h5", "h6" -> inlineOf(el)?.let { out += Block.Heading(tag[1].digitToInt(), it) }
                "p", "dt", "dd", "summary", "figcaption" -> paragraphWithImages(el, depth)
                "blockquote" -> {
                    val text = InlineBuilder().also { b -> collectInline(el, b) }.build()
                    if (text.text.isNotBlank()) out += Block.Quote(text)
                }
                "ul", "ol" -> list(el, ordered = tag == "ol", depth = depth)
                "pre" -> el.wholeText().trimEnd().takeIf { it.isNotBlank() }?.let { out += Block.Code(it) }
                "img" -> image(el, caption = null)
                "picture" -> el.selectFirst("img")?.let { image(it, null) }
                "figure" -> {
                    val caption = el.selectFirst("figcaption")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                    val imgs = el.select("img")
                    if (imgs.isEmpty()) {
                        container(el, depth)
                    } else {
                        imgs.forEachIndexed { i, img -> image(img, if (i == imgs.lastIndex) caption else null) }
                    }
                }
                "hr" -> out += Block.Divider
                "tr" -> {
                    val cells = el.children().map { it.text().trim() }.filter { it.isNotEmpty() }
                    if (cells.isNotEmpty()) out += Block.Paragraph(RichText.plain(cells.joinToString("  ·  ")))
                }
                "li" -> inlineOf(el)?.let { out += Block.ListItem(it, ordered = false, index = 1, depth = depth) }
                else -> container(el, depth)
            }
        }

        /** A paragraph may contain images: split around them. */
        private fun paragraphWithImages(el: Element, depth: Int) {
            for (child in el.childNodes()) {
                if (child is Element && (child.normalName() == "img" || child.normalName() == "picture")) {
                    flushParagraph()
                    val img = if (child.normalName() == "img") child else child.selectFirst("img")
                    img?.let { image(it, null) }
                } else if (child is Element && child.normalName() in blockTags) {
                    element(child, depth)
                } else {
                    node(child, depth)
                }
            }
            flushParagraph()
        }

        private fun list(el: Element, ordered: Boolean, depth: Int) {
            var index = el.attr("start").toIntOrNull() ?: 1
            for (li in el.children()) {
                if (li.normalName() != "li") continue
                val b = InlineBuilder()
                val nested = ArrayList<Element>()
                for (child in li.childNodes()) {
                    val name = (child as? Element)?.normalName()
                    when (name) {
                        "ul", "ol" -> nested.add(child)
                        "img" -> Unit
                        else -> collectNode(child, b)
                    }
                }
                val text = b.build()
                if (text.text.isNotBlank()) out += Block.ListItem(text, ordered, index++, depth)
                nested.forEach { list(it, it.normalName() == "ol", depth + 1) }
            }
        }

        private fun image(img: Element, caption: String?) {
            val src = Urls.resolve(baseUrl, img.attr("src")) ?: return
            if (src.endsWith(".svg")) return
            val w = img.attr("width").toIntOrNull()
            if (w != null && w < 40) return
            out += Block.Image(src, caption ?: img.attr("alt").trim().takeIf { it.length > 20 })
        }

        private fun inlineOf(el: Element): RichText? =
            InlineBuilder().also { collectInline(el, it) }.build().takeIf { it.text.isNotBlank() }

        private fun collectInline(el: Element, b: InlineBuilder) {
            for (child in el.childNodes()) collectNode(child, b)
        }

        private fun collectNode(node: Node, b: InlineBuilder) {
            when (node) {
                is TextNode -> b.text(node.text())
                is Element -> when {
                    node.normalName() == "br" -> b.newline()
                    node.normalName() in setOf("p", "div", "li", "blockquote") -> {
                        b.paragraphBreak()
                        b.inline(node) { collectNode(it, b) }
                        b.paragraphBreak()
                    }
                    else -> b.inline(node) { collectNode(it, b) }
                }
            }
        }

        private fun flushParagraph() {
            val text = pending.build()
            pending.reset()
            if (text.text.isNotBlank()) out += Block.Paragraph(text)
        }
    }

    /** Accumulates inline text with bold/italic/link/code spans and normalized whitespace. */
    private class InlineBuilder {
        private val sb = StringBuilder()
        private val spans = ArrayList<Span>()

        fun text(raw: String) {
            if (raw.isEmpty()) return
            val collapsed = raw.replace(Regex("[\\s ]+"), " ")
            if (collapsed == " " && (sb.isEmpty() || sb.last().isWhitespace())) return
            if (collapsed.startsWith(" ") && (sb.isEmpty() || sb.last().isWhitespace())) sb.append(collapsed.trimStart())
            else sb.append(collapsed)
        }

        fun newline() {
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            if (sb.isNotEmpty()) sb.append('\n')
        }

        fun paragraphBreak() {
            while (sb.isNotEmpty() && sb.last().isWhitespace()) sb.setLength(sb.length - 1)
            if (sb.isNotEmpty()) sb.append("\n\n")
        }

        fun inline(el: Element, visit: (Node) -> Unit) {
            val kind = when (el.normalName()) {
                "b", "strong" -> SpanKind.BOLD
                "i", "em", "cite", "dfn" -> SpanKind.ITALIC
                "a" -> SpanKind.LINK
                "code", "kbd", "samp", "tt" -> SpanKind.CODE
                else -> null
            }
            val start = sb.length
            for (child in el.childNodes()) visit(child)
            val end = sb.length
            if (kind != null && end > start) {
                val url = if (kind == SpanKind.LINK) el.absUrl("href").ifBlank { null } else null
                if (kind != SpanKind.LINK || url != null) spans += Span(start, end, kind, url)
            }
        }

        fun build(): RichText {
            val full = sb.toString()
            val lead = full.length - full.trimStart().length
            val text = full.trim()
            val adjusted = spans.mapNotNull { s ->
                val start = (s.start - lead).coerceIn(0, text.length)
                val end = (s.end - lead).coerceIn(0, text.length)
                if (end > start) s.copy(start = start, end = end) else null
            }
            return RichText(text, adjusted)
        }

        fun reset() {
            sb.setLength(0)
            spans.clear()
        }
    }
}
