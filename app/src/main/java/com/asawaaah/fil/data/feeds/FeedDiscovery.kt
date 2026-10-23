package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.net.ErrorMessages
import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.Http
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream

/** Feed links advertised by an HTML page. */
object HtmlFeedLinks {
    private val feedTypes = listOf("application/rss+xml", "application/atom+xml", "application/feed+json", "application/rdf+xml")

    fun extract(bytes: ByteArray, charset: String?, baseUrl: String): List<String> =
        extract(Jsoup.parse(ByteArrayInputStream(bytes), charset, baseUrl))

    fun extract(html: String, baseUrl: String): List<String> = extract(Jsoup.parse(html, baseUrl))

    private fun extract(doc: org.jsoup.nodes.Document): List<String> {
        val links = doc.select("link[href]").filter { el ->
            val rel = el.attr("rel").lowercase()
            val type = el.attr("type").lowercase()
            ("alternate" in rel || "feed" in rel) && feedTypes.any { type.startsWith(it) } && !type.contains("json")
        }.mapNotNull { Urls.resolve(doc.location(), it.attr("href")) }
        // Comment feeds are rarely what people want: put them last.
        return links.distinct().sortedBy { if (it.contains("comment", ignoreCase = true)) 1 else 0 }
    }
}

class FeedDiscovery(private val http: Http) {
    sealed interface Result {
        class Found(val feedUrl: String, val feed: ParsedFeed, val fetch: FetchResult.Ok, val isReddit: Boolean) : Result
        class NotFound(val reason: String) : Result
    }

    private val commonPaths = listOf("/feed", "/rss", "/feed.xml", "/rss.xml", "/atom.xml", "/index.xml", "/feeds/posts/default", "/?feed=rss2")

    suspend fun discover(input: String): Result {
        RedditUrls.toFeedUrl(input)?.let { redditUrl ->
            return tryFeed(redditUrl, isReddit = true)
                ?: Result.NotFound("Impossible de lire ce subreddit (vérifie le nom, ou réessaie plus tard)")
        }
        val url = Urls.clean(input) ?: return Result.NotFound("Adresse invalide")
        val first = try {
            http.fetch(url) as FetchResult.Ok
        } catch (e: Exception) {
            return Result.NotFound(ErrorMessages.describe(e))
        }
        parseOrNull(first)?.let { return Result.Found(first.finalUrl, it, first, isReddit = false) }

        val candidates = try {
            HtmlFeedLinks.extract(first.bytes, first.charset, first.finalUrl)
        } catch (_: Exception) {
            emptyList()
        }
        for (candidate in candidates.take(4)) tryFeed(candidate)?.let { return it }

        val root = try {
            val u = java.net.URI(first.finalUrl)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) {
            null
        }
        val pagePath = first.finalUrl.trimEnd('/')
        val bases = listOfNotNull(pagePath, root).distinct()
        for (base in bases) {
            for (path in commonPaths) tryFeed(base + path)?.let { return it }
        }
        return Result.NotFound("Aucun flux RSS trouvé sur cette page")
    }

    private suspend fun tryFeed(url: String, isReddit: Boolean = false): Result.Found? {
        val fetched = try {
            http.fetch(url) as? FetchResult.Ok
        } catch (_: Exception) {
            null
        } ?: return null
        val parsed = parseOrNull(fetched) ?: return null
        return Result.Found(fetched.finalUrl, parsed, fetched, isReddit || RedditUrls.isReddit(fetched.finalUrl))
    }

    private fun parseOrNull(f: FetchResult.Ok): ParsedFeed? = try {
        FeedParser.parse(f.bytes, f.charset, f.finalUrl)
    } catch (_: Exception) {
        null
    }
}
