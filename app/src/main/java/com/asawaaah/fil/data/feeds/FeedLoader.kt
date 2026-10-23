package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.net.ErrorMessages
import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.Http
import com.asawaaah.fil.data.net.HttpStatusException
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Why a feed can't be read, from the user's point of view. */
enum class FailureKind {
    /** The site or the feed no longer exists: safe to delete. */
    GONE,

    /** The site refuses feed readers (anti-bot protection). */
    BLOCKED,

    /** Server error, timeout, certificate...: may work later. */
    UNAVAILABLE,
}

/**
 * Fetches and parses one feed, working around the usual problems of old subscription lists:
 * - "http://" URLs are tried in "https://" first,
 * - sites that refuse feed readers are retried as a regular browser,
 * - "https://" URLs with a broken certificate are retried in "http://",
 * - a URL that now serves a web page is replaced by the feed that page advertises,
 * - a dead feed URL is replaced by the feed found on the site's home page.
 * Pure JVM: no database, so it can be tested on real sites from the PC.
 */
class FeedLoader(private val http: Http, private val discovery: FeedDiscovery = FeedDiscovery(http)) {

    sealed interface Result {
        data object NotModified : Result

        /** [url] is the address to keep for this feed: it differs from the requested one when it was fixed. */
        class Ok(val feed: ParsedFeed, val fetch: FetchResult.Ok, val url: String) : Result {
            fun changed(original: String) = url != original
        }

        class Failed(val reason: String, val error: Throwable?, val kind: FailureKind) : Result
    }

    /** The URL answered with something that is not a feed; [message] says what it was. */
    class NotAFeedPage(message: String, val blocked: Boolean) : Exception(message)

    suspend fun load(
        url: String,
        etag: String? = null,
        lastModified: String? = null,
        repair: Boolean = false,
        siteUrl: String? = null,
    ): Result {
        val candidates = buildList {
            if (url.startsWith("http://", ignoreCase = true)) add("https://" + url.substring(7))
            add(url)
        }
        var error: Throwable? = null
        for (candidate in candidates) {
            val isUpgrade = candidate != url
            when (val attempt = attempt(candidate, if (isUpgrade) null else etag, if (isUpgrade) null else lastModified, isUpgrade, repair)) {
                is Attempt.Done -> return attempt.result
                // The error of the address as written matters more than the one of the https attempt.
                is Attempt.Error -> if (!isUpgrade || error == null) error = attempt.error
            }
        }

        // Some sites refuse one kind of client only: try a plain reader name, then a browser.
        if (error is HttpStatusException && error.code in AGENT_RETRY_CODES ||
            error is NotAFeedPage && error.blocked
        ) {
            for (agent in listOf(Http.Agent.PLAIN, Http.Agent.BROWSER)) {
                when (val retry = attempt(url, null, null, quick = false, repair = repair, agent = agent)) {
                    is Attempt.Done -> return retry.result
                    is Attempt.Error -> Unit
                }
            }
        }
        // Broken certificate: the same feed is often still served in clear text.
        if (error is SSLException && url.startsWith("https://", ignoreCase = true)) {
            when (val retry = attempt("http://" + url.substring(8), null, null, quick = true, repair = repair)) {
                is Attempt.Done -> return retry.result
                is Attempt.Error -> Unit
            }
        }
        if (repair && siteUrl != null && classify(error) == FailureKind.GONE) {
            when (val found = discovery.discover(siteUrl)) {
                is FeedDiscovery.Result.Found -> return Result.Ok(found.feed, found.fetch, found.feedUrl)
                is FeedDiscovery.Result.NotFound -> Unit
            }
        }
        return Result.Failed(error?.let(::describe) ?: "Flux illisible", error, classify(error))
    }

    private sealed interface Attempt {
        class Done(val result: Result) : Attempt
        class Error(val error: Throwable) : Attempt
    }

    private suspend fun attempt(
        candidate: String,
        etag: String?,
        lastModified: String?,
        quick: Boolean,
        repair: Boolean,
        agent: Http.Agent = Http.Agent.DEFAULT,
    ): Attempt = try {
        val res = http.fetch(
            candidate,
            etag = etag,
            lastModified = lastModified,
            connectTimeoutMs = if (quick) 6_000 else null,
            agent = agent,
        )
        if (res is FetchResult.NotModified) {
            Attempt.Done(Result.NotModified)
        } else {
            res as FetchResult.Ok
            val parsed = parse(res)
            when {
                parsed != null -> Attempt.Done(Result.Ok(parsed, res, candidate))
                else -> {
                    val advertised = if (repair) advertisedFeed(res) else null
                    if (advertised != null) Attempt.Done(advertised) else Attempt.Error(nonFeedError(res))
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Attempt.Error(e)
    }

    private fun parse(res: FetchResult.Ok): ParsedFeed? = try {
        FeedParser.parse(res.bytes, res.charset, res.finalUrl)
    } catch (_: Exception) {
        null
    }

    private suspend fun advertisedFeed(page: FetchResult.Ok): Result.Ok? {
        val links = runCatching { HtmlFeedLinks.extract(page.bytes, page.charset, page.finalUrl) }.getOrDefault(emptyList())
        for (link in links.take(3)) {
            val upgraded = if (link.startsWith("http://", true)) listOf("https://" + link.substring(7), link) else listOf(link)
            for (candidate in upgraded) {
                val fetched = runCatching { http.fetch(candidate) as? FetchResult.Ok }.getOrNull() ?: continue
                val parsed = parse(fetched) ?: continue
                return Result.Ok(parsed, fetched, candidate)
            }
        }
        return null
    }

    private fun describe(e: Throwable): String = when (e) {
        is NotAFeedPage -> e.message ?: "Ce n'est pas un flux RSS ou Atom"
        else -> ErrorMessages.describe(e)
    }

    companion object {
        private val AGENT_RETRY_CODES = setOf(401, 403, 406, 429, 503)
        private val botTitles = Regex(
            "bot|captcha|just a moment|attention required|access denied|un instant|vérification|security check|ddos",
            RegexOption.IGNORE_CASE,
        )
        private val closedTitles = Regex(
            "closed for business|coming soon|domain (is )?for sale|parked|page not found|introuvable|404|n'existe plus",
            RegexOption.IGNORE_CASE,
        )

        fun classify(e: Throwable?): FailureKind = when (e) {
            is UnknownHostException -> FailureKind.GONE
            is HttpStatusException -> when (e.code) {
                404, 410 -> FailureKind.GONE
                401, 403, 429 -> FailureKind.BLOCKED
                else -> FailureKind.UNAVAILABLE
            }
            is NotAFeedPage -> if (e.blocked) FailureKind.BLOCKED else FailureKind.GONE
            is NotAFeedException -> FailureKind.GONE
            else -> FailureKind.UNAVAILABLE
        }

        /** Explains what a non-feed answer was: a web page (with its title) or a short message. */
        fun nonFeedError(res: FetchResult.Ok): NotAFeedPage {
            val doc = runCatching { Jsoup.parse(ByteArrayInputStream(res.bytes), res.charset, res.finalUrl) }.getOrNull()
            val title = doc?.title()?.trim()?.takeIf { it.isNotEmpty() }
            val text = doc?.body()?.text()?.trim().orEmpty()
            return when {
                res.bytes.isEmpty() -> NotAFeedPage("Le site renvoie une réponse vide", blocked = false)
                title != null && botTitles.containsMatchIn(title) && !closedTitles.containsMatchIn(title) ->
                    NotAFeedPage("Le site bloque les lecteurs RSS (« ${title.take(60)} »)", blocked = true)
                title != null -> NotAFeedPage("Le site renvoie une page web (« ${title.take(80)} »), pas un flux", blocked = false)
                text.isNotEmpty() && text.length <= 120 -> NotAFeedPage("Le site répond : « $text »", blocked = false)
                else -> NotAFeedPage("Ce n'est pas un flux RSS ou Atom valide", blocked = false)
            }
        }
    }
}
