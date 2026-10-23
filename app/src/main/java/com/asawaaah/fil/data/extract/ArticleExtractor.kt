package com.asawaaah.fil.data.extract

import com.asawaaah.fil.data.feeds.Urls
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist
import java.io.ByteArrayInputStream

data class ExtractedArticle(val html: String, val title: String?, val leadImage: String?, val textLength: Int)

/**
 * Reader-mode extraction, a small take on Mozilla Readability:
 * strips page chrome, finds the block holding the article text, and returns clean HTML.
 */
object ArticleExtractor {
    private const val MIN_TEXT = 250

    private val unlikely = Regex(
        "comment|sidebar|footer|foot|nav|menu|share|social|related|promo|advert|sponsor|newsletter|subscribe|" +
            "cookie|banner|popup|modal|breadcrumb|pagination|pager|tags|author-bio|masthead|outbrain|taboola|" +
            "recommend|widget|signup|paywall|disqus|rating|toolbar",
        RegexOption.IGNORE_CASE,
    )
    private val likely = Regex("article|body|content|entry|main|post|story|text|blog", RegexOption.IGNORE_CASE)

    private val contentSelectors = listOf(
        "[itemprop=articleBody]", ".entry-content", ".post-content", ".article-content", ".article-body",
        ".article__body", ".article__content", ".story-body", ".c-entry-content", ".post-body", ".post__content",
        ".content-body", ".rich-text", "article", "main", "[role=main]",
    )

    private val safelist: Safelist = Safelist.relaxed()
        .addTags("figure", "figcaption", "hr", "picture", "mark", "s", "del", "ins")
        .addAttributes("img", "srcset")
        .removeTags("span")

    fun extract(bytes: ByteArray, charset: String?, url: String): ExtractedArticle? =
        extract(Jsoup.parse(ByteArrayInputStream(bytes), charset, url))

    fun extract(html: String, url: String): ExtractedArticle? = extract(Jsoup.parse(html, url))

    private fun extract(doc: Document): ExtractedArticle? {
        val baseUrl = doc.location()
        val leadImage = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.attr("content")
            ?.let { Urls.resolve(baseUrl, it) }
        val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title().takeIf { it.isNotBlank() }

        fixLazyImages(doc)
        doc.select("script, style, noscript, iframe, form, button, input, select, textarea, svg, canvas, template, nav, aside, footer, link, meta").remove()
        doc.select("[hidden], [aria-hidden=true], .sr-only, .visually-hidden").remove()

        val candidate = pickBySelectors(doc) ?: pickByScore(doc) ?: return null
        clean(candidate)

        val cleanHtml = Jsoup.clean(candidate.html(), baseUrl, safelist, org.jsoup.nodes.Document.OutputSettings().prettyPrint(false))
        val cleanDoc = Jsoup.parseBodyFragment(cleanHtml, baseUrl)
        // Drop empty leftovers.
        cleanDoc.select("p, div, li, h1, h2, h3, h4, h5, h6, figure, blockquote").filter {
            it.text().isBlank() && it.select("img").isEmpty()
        }.forEach { it.remove() }
        val textLength = cleanDoc.body().text().length
        if (textLength < MIN_TEXT) return null
        return ExtractedArticle(cleanDoc.body().html(), title, leadImage, textLength)
    }

    private fun fixLazyImages(doc: Document) {
        for (img in doc.select("img")) {
            val src = img.attr("src")
            if (src.isBlank() || src.startsWith("data:")) {
                val lazy = sequenceOf("data-src", "data-lazy-src", "data-original", "data-url", "data-hi-res-src")
                    .map { img.attr(it) }.firstOrNull { it.isNotBlank() }
                    ?: img.attr("data-srcset").ifBlank { img.attr("srcset") }.split(',').firstOrNull()?.trim()?.substringBefore(' ')
                if (!lazy.isNullOrBlank()) img.attr("src", lazy)
            }
            val abs = Urls.resolve(doc.location(), img.attr("src"))
            if (abs == null) img.remove() else img.attr("src", abs)
        }
        // <noscript><img></noscript> pattern: move the real image out before noscript is dropped.
        for (ns in doc.select("noscript")) {
            val inner = Jsoup.parseBodyFragment(ns.html(), doc.location()).selectFirst("img[src]") ?: continue
            val prev = ns.previousElementSibling()
            if (prev != null && prev.tagName() == "img" && prev.attr("src").isBlank()) prev.attr("src", inner.absUrl("src"))
        }
        for (a in doc.select("a[href]")) {
            Urls.resolve(doc.location(), a.attr("href"))?.let { a.attr("href", it) }
        }
    }

    private fun pickBySelectors(doc: Document): Element? {
        for (sel in contentSelectors) {
            val found = doc.select(sel)
            if (found.isEmpty()) continue
            // Some sites split the article body into several blocks (around ads): merge them.
            val parts = found
                .filter { it.text().length >= 150 }
                .filter { el -> found.none { other -> other !== el && el.parents().contains(other) } }
            if (parts.size > 1 && sel != "article" && sel != "main") {
                val total = parts.sumOf { it.text().length }
                if (total >= MIN_TEXT * 2) {
                    val wrapper = Element("div")
                    parts.forEach { wrapper.appendChild(it.clone()) }
                    return wrapper
                }
            }
            val best = found.maxByOrNull { it.text().length } ?: continue
            if (best.text().length >= MIN_TEXT * 2) return best
        }
        return null
    }

    private fun pickByScore(doc: Document): Element? {
        val scores = HashMap<Element, Double>()
        for (p in doc.select("p, pre, td, blockquote")) {
            val text = p.text()
            if (text.length < 25) continue
            val score = 1.0 + text.count { it == ',' } + minOf(text.length / 100.0, 3.0)
            val parent = p.parent() ?: continue
            scores[parent] = (scores[parent] ?: initialScore(parent)) + score
            parent.parent()?.let { gp -> scores[gp] = (scores[gp] ?: initialScore(gp)) + score / 2 }
        }
        return scores.entries
            .map { (el, s) -> el to s * (1 - linkDensity(el)) }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun initialScore(el: Element): Double {
        var s = when (el.tagName()) {
            "div", "article", "section" -> 5.0
            "pre", "td", "blockquote" -> 3.0
            "ol", "ul", "dl", "dd", "dt", "li", "form" -> -3.0
            "h1", "h2", "h3", "h4", "h5", "h6", "th" -> -5.0
            else -> 0.0
        }
        val classAndId = el.className() + " " + el.id()
        if (likely.containsMatchIn(classAndId)) s += 25
        if (unlikely.containsMatchIn(classAndId)) s -= 25
        return s
    }

    private fun linkDensity(el: Element): Double {
        val total = el.text().length
        if (total == 0) return 0.0
        val links = el.select("a").sumOf { it.text().length }
        return links.toDouble() / total
    }

    private fun clean(root: Element) {
        root.select("header").filter { it != root }.forEach { h ->
            if (h.text().length < 300) h.remove()
        }
        for (el in root.select("*").toList()) {
            if (el == root || el.parent() == null) continue
            val classAndId = el.className() + " " + el.id()
            if (classAndId.isNotBlank() && unlikely.containsMatchIn(classAndId) && !likely.containsMatchIn(classAndId)) {
                el.remove()
                continue
            }
            if (el.tagName() in setOf("ul", "ol", "div", "section", "table") && el.select("img").isEmpty()) {
                val text = el.text()
                if (text.length < 400 && linkDensity(el) > 0.5) el.remove()
            }
        }
    }
}
