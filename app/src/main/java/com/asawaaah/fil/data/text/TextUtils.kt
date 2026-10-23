package com.asawaaah.fil.data.text

import com.asawaaah.fil.data.feeds.Urls
import org.jsoup.Jsoup
import org.jsoup.nodes.Entities

object TextUtils {
    private val spaces = Regex("\\s+")

    /** Removes HTML tags and entities, collapses whitespace. */
    fun stripHtml(s: String): String {
        val text = if (s.contains('<') || s.contains('&')) Jsoup.parse(s).text() else s
        return text.replace(spaces, " ").trim()
    }

    fun escapeHtml(s: String): String = Entities.escape(s)

    /** Plain-text excerpt of [html], cut on a word boundary. */
    fun excerpt(html: String?, max: Int = 280): String? {
        if (html.isNullOrBlank()) return null
        val text = stripHtml(html)
        if (text.isEmpty()) return null
        return cut(text, max)
    }

    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val cutAt = text.lastIndexOf(' ', max).takeIf { it > max / 2 } ?: max
        return text.substring(0, cutAt).trimEnd(',', ';', ':', ' ') + "…"
    }

    /** First meaningful image of an HTML fragment (skips tracking pixels and icons). */
    fun firstImage(html: String?, baseUrl: String?): String? {
        if (html.isNullOrBlank() || !html.contains("<img", ignoreCase = true)) return null
        val doc = Jsoup.parseBodyFragment(html, baseUrl ?: "")
        for (img in doc.select("img")) {
            val w = img.attr("width").toIntOrNull()
            val h = img.attr("height").toIntOrNull()
            if ((w != null && w < 40) || (h != null && h < 40)) continue
            val src = sequenceOf("src", "data-src", "data-lazy-src", "data-original")
                .map { img.attr(it) }.firstOrNull { it.isNotBlank() && !it.startsWith("data:") } ?: continue
            if (src.contains("feedburner") || src.contains("pixel") || src.contains("/stats/") || src.endsWith(".svg")) continue
            return Urls.resolve(baseUrl, src)
        }
        return null
    }
}
