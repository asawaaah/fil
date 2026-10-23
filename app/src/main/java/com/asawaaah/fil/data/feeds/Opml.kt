package com.asawaaah.fil.data.feeds

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import java.io.ByteArrayInputStream

data class OpmlFeed(
    val title: String,
    val xmlUrl: String,
    val htmlUrl: String?,
    /** Folder name; nested folders are flattened to "Parent / Child". */
    val folder: String?,
)

data class OpmlDocument(
    val title: String?,
    val feeds: List<OpmlFeed>,
    /** Feeds dropped because the same URL appeared earlier in the file. */
    val duplicatesInFile: Int,
)

class OpmlException(message: String) : Exception(message)

object Opml {
    private val feedUrlKeys = listOf("xmlurl", "xml_url", "rssurl", "feedurl", "url")
    private val skippedTypes = setOf("link", "include", "html")

    fun parse(bytes: ByteArray): OpmlDocument {
        if (bytes.isEmpty()) throw OpmlException("Le fichier est vide")
        val doc = try {
            Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())
        } catch (e: Exception) {
            null
        }
        val fromXml = doc?.let { parseElements(it.getAllElements()) }
        // Safety net for badly broken files: also pick xmlUrl="..." attributes with a regex,
        // and add any feed the XML parser missed.
        val fallback = regexFallback(decodeLoosely(bytes))
        val known = fromXml?.feeds?.map { Urls.normalize(it.xmlUrl) }?.toHashSet() ?: HashSet()
        val missed = fallback.feeds.filter { known.add(Urls.normalize(it.xmlUrl)) }
        val feeds = fromXml?.feeds.orEmpty() + missed
        if (feeds.isEmpty()) throw OpmlException("Ce fichier ne contient aucun flux. Est-ce bien un fichier OPML ?")
        return OpmlDocument(
            title = doc?.let(::docTitle),
            feeds = feeds,
            duplicatesInFile = fromXml?.duplicatesInFile ?: fallback.duplicatesInFile,
        )
    }

    fun parse(text: String): OpmlDocument = parse(text.toByteArray(Charsets.UTF_8))

    private fun docTitle(doc: org.jsoup.nodes.Document): String? =
        doc.getAllElements().firstOrNull { it.normalName() == "title" }?.text()?.trim()?.takeIf { it.isNotEmpty() }

    private fun parseElements(all: List<Element>): OpmlDocument {
        val seen = HashSet<String>()
        val feeds = ArrayList<OpmlFeed>()
        var duplicates = 0
        for (el in all) {
            if (el.normalName() != "outline") continue
            val type = el.attrIc("type").lowercase()
            if (type in skippedTypes) continue
            val raw = feedUrlKeys.firstNotNullOfOrNull { key -> el.attrIc(key).takeIf { it.isNotBlank() } } ?: continue
            val url = Urls.clean(unescape(raw)) ?: continue
            val key = Urls.normalize(url)
            if (!seen.add(key)) {
                duplicates++
                continue
            }
            val title = unescape(el.attrIc("text").ifBlank { el.attrIc("title") }).trim()
                .ifBlank { Urls.host(url) ?: url }
            feeds += OpmlFeed(
                title = title,
                xmlUrl = url,
                htmlUrl = Urls.clean(unescape(el.attrIc("htmlurl")))?.takeIf { it != url },
                folder = folderOf(el),
            )
        }
        return OpmlDocument(title = null, feeds = feeds, duplicatesInFile = duplicates)
    }

    private fun folderOf(el: Element): String? {
        val names = el.parents()
            .filter { it.normalName() == "outline" && feedUrlKeys.none { k -> it.attrIc(k).isNotBlank() } }
            .mapNotNull { p -> unescape(p.attrIc("text").ifBlank { p.attrIc("title") }).trim().takeIf { it.isNotEmpty() } }
            .reversed()
        if (names.isNotEmpty()) return names.joinToString(" / ")
        // OPML 2.0 "category" attribute: "/Tech/Android,/News" -> "Tech / Android".
        val category = el.attrIc("category").split(',').firstOrNull()?.trim()?.trim('/')
        return category?.takeIf { it.isNotEmpty() }?.split('/')?.filter { it.isNotBlank() }?.joinToString(" / ")
    }

    private fun regexFallback(text: String): OpmlDocument {
        val urlAttr = Regex("""(?i)\bxml_?url\s*=\s*["']([^"']+)["']""")
        val titleAttr = Regex("""(?i)\b(?:text|title)\s*=\s*["']([^"']*)["']""")
        val seen = HashSet<String>()
        val feeds = ArrayList<OpmlFeed>()
        var duplicates = 0
        for (tag in Regex("""(?is)<outline\b[^<>]*""").findAll(text)) {
            val raw = urlAttr.find(tag.value)?.groupValues?.get(1) ?: continue
            val url = Urls.clean(unescape(raw)) ?: continue
            if (!seen.add(Urls.normalize(url))) {
                duplicates++
                continue
            }
            val title = titleAttr.find(tag.value)?.groupValues?.get(1)?.let(::unescape)?.trim()
                ?.takeIf { it.isNotEmpty() } ?: Urls.host(url) ?: url
            feeds += OpmlFeed(title, url, null, null)
        }
        return OpmlDocument(null, feeds, duplicates)
    }

    private fun decodeLoosely(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> String(bytes, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, Charsets.UTF_16BE)
        else -> String(bytes, Charsets.UTF_8).trimStart('﻿')
    }

    /** Some exporters double-escape ("&amp;amp;"); decode until stable (max twice). */
    private fun unescape(s: String): String {
        var out = s
        repeat(2) { if (out.contains('&')) out = Parser.unescapeEntities(out, true) }
        return out
    }

    private fun Element.attrIc(key: String): String = attributes().getIgnoreCase(key)

    // ---------- Export ----------

    fun write(title: String, feeds: List<OpmlFeed>, dateCreated: String): String {
        fun esc(s: String) = Entities.escape(s).replace("\"", "&quot;")
        fun outline(f: OpmlFeed, indent: String) = buildString {
            append(indent).append("<outline type=\"rss\" text=\"").append(esc(f.title))
            append("\" title=\"").append(esc(f.title))
            append("\" xmlUrl=\"").append(esc(f.xmlUrl)).append('"')
            f.htmlUrl?.let { append(" htmlUrl=\"").append(esc(it)).append('"') }
            append("/>\n")
        }
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n    <title>").append(esc(title)).append("</title>\n")
            append("    <dateCreated>").append(esc(dateCreated)).append("</dateCreated>\n  </head>\n")
            append("  <body>\n")
            feeds.filter { it.folder == null }.forEach { append(outline(it, "    ")) }
            feeds.filter { it.folder != null }.groupBy { it.folder!! }.forEach { (folder, list) ->
                append("    <outline text=\"").append(esc(folder)).append("\" title=\"").append(esc(folder)).append("\">\n")
                list.forEach { append(outline(it, "      ")) }
                append("    </outline>\n")
            }
            append("  </body>\n</opml>\n")
        }
    }
}
