package com.asawaaah.fil.data.feeds

import org.jsoup.Jsoup

/** Splits the HTML of a Reddit RSS entry into the post's own text and its external link. */
object RedditContent {
    data class Info(val selfHtml: String?, val linkUrl: String?, val imageUrl: String?)

    private val imageExt = Regex("""\.(jpe?g|png|webp|gif)(\?.*)?$""", RegexOption.IGNORE_CASE)

    fun parse(contentHtml: String?): Info {
        if (contentHtml.isNullOrBlank()) return Info(null, null, null)
        val doc = Jsoup.parseBodyFragment(contentHtml, "https://www.reddit.com/")
        val md = doc.selectFirst("div.md")
        val selfHtml = md?.takeIf { it.text().isNotBlank() }?.html()
        val link = doc.select("a[href]").firstOrNull { it.text().trim() == "[link]" }?.absUrl("href")
            ?.takeIf { it.isNotBlank() }
        // Self posts point "[link]" back to the comments page: not an external link.
        val external = link?.takeIf { !RedditUrls.isReddit(it) || it.contains("/gallery/") }
        val image = external?.takeIf { imageExt.containsMatchIn(it) || it.contains("i.redd.it") }
        return Info(selfHtml, external, image)
    }

    fun isImageLink(url: String?): Boolean = url != null && (imageExt.containsMatchIn(url) || url.contains("i.redd.it"))
}
