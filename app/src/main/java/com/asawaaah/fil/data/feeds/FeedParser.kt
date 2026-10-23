package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.text.TextUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

data class ParsedFeed(
    val title: String?,
    val siteUrl: String?,
    val iconUrl: String?,
    val language: String?,
    val items: List<ParsedItem>,
)

data class ParsedItem(
    val guid: String,
    val url: String?,
    val title: String,
    val author: String?,
    val summaryHtml: String?,
    val contentHtml: String?,
    val imageUrl: String?,
    val publishedAt: Long?,
)

class NotAFeedException(message: String = "Ce n'est pas un flux RSS ou Atom valide") : Exception(message)

/** Tolerant RSS 2.0 / RSS 1.0 (RDF) / Atom parser built on jsoup's XML parser. */
object FeedParser {
    private enum class Kind { RSS, RDF, ATOM }

    fun parse(bytes: ByteArray, charset: String?, baseUrl: String): ParsedFeed {
        val doc = Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUrl, Parser.xmlParser())
        return parseDocument(doc, baseUrl)
    }

    fun parse(xml: String, baseUrl: String): ParsedFeed =
        parseDocument(Jsoup.parse(xml.trimStart('﻿'), baseUrl, Parser.xmlParser()), baseUrl)

    /** True when the bytes look like an RSS/Atom document (cheap check on the root element). */
    fun looksLikeFeed(bytes: ByteArray, charset: String?, baseUrl: String): Boolean = try {
        rootOf(Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUrl, Parser.xmlParser())) != null
    } catch (_: Exception) {
        false
    }

    private fun parseDocument(doc: Document, baseUrl: String): ParsedFeed {
        val (kind, root) = rootOf(doc) ?: throw NotAFeedException()
        return when (kind) {
            Kind.ATOM -> parseAtom(root, baseUrl)
            Kind.RSS, Kind.RDF -> parseRss(root, baseUrl)
        }
    }

    private fun rootOf(doc: Document): Pair<Kind, Element>? {
        for (el in doc.children()) {
            when (local(el)) {
                "rss" -> return Kind.RSS to el
                "rdf" -> return Kind.RDF to el
                "feed" -> return Kind.ATOM to el
            }
        }
        return null
    }

    // ---------- RSS ----------

    private fun parseRss(root: Element, baseUrl: String): ParsedFeed {
        val channel = root.first("channel") ?: root
        val siteUrl = channel.first("link")?.txt()?.takeIf { it.isNotBlank() }
            ?: channel.children().firstOrNull { local(it) == "link" && it.attrIc("rel") == "alternate" }?.attrIc("href")
        val base = Urls.resolve(baseUrl, siteUrl) ?: baseUrl
        val icon = channel.first("image")?.first("url")?.txt()
            ?: channel.first("itunes:image")?.attrIc("href")
        var items = channel.childrenNamed("item")
        if (items.isEmpty()) items = root.childrenNamed("item")
        return ParsedFeed(
            title = channel.first("title")?.txt()?.let(TextUtils::stripHtml)?.takeIf { it.isNotBlank() },
            siteUrl = Urls.resolve(baseUrl, siteUrl),
            iconUrl = Urls.resolve(base, icon),
            language = channel.first("language", "dc:language")?.txt()?.takeIf { it.isNotBlank() },
            items = items.mapNotNull { parseRssItem(it, base) },
        )
    }

    private fun parseRssItem(item: Element, base: String): ParsedItem? {
        val title = item.first("title")?.txt()?.let(TextUtils::stripHtml).orEmpty()
        var link = item.first("link")?.txt()?.takeIf { it.isNotBlank() }
            ?: item.children().firstOrNull {
                local(it) == "link" && it.attrIc("href").isNotBlank() &&
                    (it.attrIc("rel").isBlank() || it.attrIc("rel") == "alternate")
            }?.attrIc("href")
        val guidEl = item.first("guid")
        val guid = guidEl?.txt()?.takeIf { it.isNotBlank() }
        if (link == null && guid != null && guidEl.attrIc("isPermaLink") != "false" && guid.startsWith("http")) {
            link = guid
        }
        val description = item.first("description")?.txt()?.takeIf { it.isNotBlank() }
        val content = (item.first("content:encoded") ?: item.firstLocal("encoded"))?.txt()?.takeIf { it.isNotBlank() }
        val date = (item.first("pubdate") ?: item.first("dc:date") ?: item.firstLocal("date", "published", "updated", "issued"))?.txt()
        val author = (item.first("dc:creator") ?: item.first("author") ?: item.firstLocal("creator"))?.txt()
            ?.let(TextUtils::stripHtml)?.takeIf { it.isNotBlank() }
        if (title.isBlank() && description == null && content == null) return null
        val url = Urls.resolve(base, link)
        return ParsedItem(
            guid = guid ?: url ?: "${title.hashCode()}-${date.hashCode()}",
            url = url,
            title = title.ifBlank { TextUtils.excerpt(content ?: description, 90) ?: "(sans titre)" },
            author = author,
            summaryHtml = description,
            contentHtml = content,
            imageUrl = imageOf(item, content ?: description, base),
            publishedAt = DateParser.parse(date),
        )
    }

    // ---------- Atom ----------

    private fun parseAtom(root: Element, baseUrl: String): ParsedFeed {
        val siteUrl = alternateLink(root)
        val base = Urls.resolve(baseUrl, siteUrl) ?: baseUrl
        return ParsedFeed(
            title = root.first("title")?.let(::atomText)?.let(TextUtils::stripHtml)?.takeIf { it.isNotBlank() },
            siteUrl = Urls.resolve(baseUrl, siteUrl),
            iconUrl = Urls.resolve(base, (root.first("icon") ?: root.first("logo"))?.txt()),
            language = root.attrIc("xml:lang").takeIf { it.isNotBlank() },
            items = root.childrenNamed("entry").mapNotNull { parseAtomEntry(it, base) },
        )
    }

    private fun parseAtomEntry(entry: Element, base: String): ParsedItem? {
        val title = entry.first("title")?.let(::atomText)?.let(TextUtils::stripHtml).orEmpty()
        val url = Urls.resolve(base, alternateLink(entry))
        val id = entry.first("id")?.txt()?.takeIf { it.isNotBlank() }
        val date = (entry.first("published") ?: entry.first("issued") ?: entry.first("created")
            ?: entry.first("updated") ?: entry.first("modified"))?.txt()
        val author = entry.first("author")?.first("name")?.txt()?.takeIf { it.isNotBlank() }
        val summary = entry.first("summary")?.let(::atomText)?.takeIf { it.isNotBlank() }
        val content = entry.first("content")?.let(::atomText)?.takeIf { it.isNotBlank() }
            ?: entry.first("media:group")?.first("media:description")?.txt()?.takeIf { it.isNotBlank() }
                ?.let { "<p>" + TextUtils.escapeHtml(it).replace("\n", "<br>") + "</p>" }
        if (title.isBlank() && summary == null && content == null) return null
        return ParsedItem(
            guid = id ?: url ?: "${title.hashCode()}-${date.hashCode()}",
            url = url,
            title = title.ifBlank { TextUtils.excerpt(content ?: summary, 90) ?: "(sans titre)" },
            author = author,
            summaryHtml = summary,
            contentHtml = content,
            imageUrl = imageOf(entry, content ?: summary, base),
            publishedAt = DateParser.parse(date),
        )
    }

    private fun alternateLink(el: Element): String? {
        val links = el.childrenNamed("link")
        return (
            links.firstOrNull { val rel = it.attrIc("rel"); rel.isBlank() || rel == "alternate" }
                ?: links.firstOrNull()
            )?.attrIc("href")?.takeIf { it.isNotBlank() }
            ?: links.firstOrNull()?.txt()?.takeIf { it.startsWith("http") }
    }

    private fun atomText(el: Element): String = when (el.attrIc("type").lowercase()) {
        "xhtml" -> el.children().firstOrNull()?.html() ?: el.html()
        "text" -> TextUtils.escapeHtml(el.txt())
        else -> el.txt()
    }

    // ---------- Images ----------

    private val imageExt = Regex("""\.(jpe?g|png|webp|gif|avif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    private fun imageOf(item: Element, html: String?, base: String): String? {
        fun ok(u: String?) = Urls.resolve(base, u)
        item.childrenNamed("media:thumbnail").firstOrNull()?.attrIc("url")?.let { ok(it) }?.let { return it }
        mediaImage(item)?.let { ok(it) }?.let { return it }
        item.first("media:group")?.let { g ->
            g.childrenNamed("media:thumbnail").firstOrNull()?.attrIc("url")?.let { ok(it) }?.let { return it }
            mediaImage(g)?.let { ok(it) }?.let { return it }
        }
        item.childrenNamed("enclosure").firstOrNull {
            it.attrIc("type").startsWith("image") || imageExt.containsMatchIn(it.attrIc("url"))
        }?.attrIc("url")?.let { ok(it) }?.let { return it }
        item.first("itunes:image")?.attrIc("href")?.let { ok(it) }?.let { return it }
        return TextUtils.firstImage(html, base)
    }

    private fun mediaImage(parent: Element): String? = parent.childrenNamed("media:content").firstOrNull {
        it.attrIc("medium") == "image" || it.attrIc("type").startsWith("image") || imageExt.containsMatchIn(it.attrIc("url"))
    }?.attrIc("url")

    // ---------- Helpers ----------

    private fun local(el: Element): String = el.normalName().substringAfter(':')

    private fun Element.childrenNamed(name: String): List<Element> = children().filter { it.normalName() == name }

    private fun Element.first(vararg names: String): Element? = children().firstOrNull { it.normalName() in names }

    private fun Element.firstLocal(vararg locals: String): Element? = children().firstOrNull { local(it) in locals }

    private fun Element.txt(): String = wholeText().trim()

    private fun Element.attrIc(key: String): String = attributes().getIgnoreCase(key).trim()
}
