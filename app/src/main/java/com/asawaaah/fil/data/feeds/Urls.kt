package com.asawaaah.fil.data.feeds

import java.net.URI

object Urls {
    /**
     * Turns user or OPML input into a usable http(s) URL, or null.
     * Handles missing scheme, "feed://" and "feed:https://" forms and stray whitespace.
     */
    fun clean(input: String?): String? {
        var s = input?.trim()?.replace(" ", "%20") ?: return null
        if (s.isEmpty()) return null
        if (s.startsWith("feed:", ignoreCase = true)) {
            s = s.substring(5).removePrefix("//")
            if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "https://$s"
        }
        if (s.startsWith("//")) s = "https:$s"
        if (!s.contains("://")) s = "https://$s"
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) return null
        return try {
            val uri = URI(s)
            if (uri.host.isNullOrBlank() || !uri.host.contains('.') && uri.host != "localhost") null else s
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Key used to detect duplicate feeds: ignores http/https, "www.", default ports,
     * a trailing slash and the fragment. Keeps the query string.
     */
    fun normalize(url: String): String {
        val cleaned = clean(url) ?: return url.trim().lowercase()
        return try {
            val uri = URI(cleaned)
            val host = uri.host.lowercase().removePrefix("www.")
            val port = if (uri.port == -1 || uri.port == 80 || uri.port == 443) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            "$host$port$path$query"
        } catch (_: Exception) {
            cleaned.lowercase()
        }
    }

    /** Resolves [href] against [base]; returns null when it cannot be made absolute. */
    fun resolve(base: String?, href: String?): String? {
        val h = href?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (h.startsWith("data:", true) || h.startsWith("javascript:", true)) return null
        return try {
            val resolved = if (base.isNullOrBlank()) URI(h) else URI(base).resolve(URI(h.replace(" ", "%20")))
            if (resolved.isAbsolute) resolved.toString() else null
        } catch (_: Exception) {
            if (h.startsWith("http://") || h.startsWith("https://")) h else null
        }
    }

    fun host(url: String?): String? = try {
        url?.let { URI(it).host?.removePrefix("www.") }
    } catch (_: Exception) {
        null
    }

    /** "blog.example.co.uk" -> "example.co.uk", "www.example.com" -> "example.com". */
    fun baseDomain(url: String?): String? {
        val host = host(url)?.lowercase() ?: return null
        val labels = host.split('.')
        if (labels.size <= 2) return host
        val secondLevel = labels[labels.size - 2]
        val publicSecondLevels = setOf("co", "com", "org", "net", "gov", "gouv", "ac", "edu", "asso", "or", "ne")
        val keep = if (secondLevel in publicSecondLevels && labels.last().length == 2) 3 else 2 // co.uk, gouv.fr...
        return labels.takeLast(keep).joinToString(".")
    }

    /** Site favicon, fetched straight from the site (no third-party service). */
    fun faviconFor(siteUrl: String?): String? {
        val u = clean(siteUrl) ?: return null
        return try {
            val uri = URI(u)
            "${uri.scheme}://${uri.host}/favicon.ico"
        } catch (_: Exception) {
            null
        }
    }
}

object RedditUrls {
    private val bare = Regex("""^/?(r|u|user)/([A-Za-z0-9_+\-]+)/?$""", RegexOption.IGNORE_CASE)
    private val sorts = setOf("hot", "new", "top", "rising", "controversial")

    fun isReddit(url: String?): Boolean {
        val host = Urls.host(url ?: return false)?.lowercase() ?: return false
        return host == "reddit.com" || host.endsWith(".reddit.com")
    }

    /** Converts "r/android", a subreddit or user URL into its RSS feed URL. Null if not Reddit. */
    fun toFeedUrl(input: String): String? {
        val s = input.trim()
        bare.matchEntire(s)?.let { m ->
            val type = if (m.groupValues[1].equals("r", true)) "r" else "user"
            return "https://www.reddit.com/$type/${m.groupValues[2]}/.rss"
        }
        val url = Urls.clean(s) ?: return null
        if (!isReddit(url)) return null
        val path = try {
            java.net.URI(url).path ?: ""
        } catch (_: Exception) {
            return null
        }
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val type = when (parts[0].lowercase()) {
            "r" -> "r"
            "u", "user" -> "user"
            else -> return null
        }
        val name = parts[1]
        val sort = parts.getOrNull(2)?.lowercase()?.takeIf { it in sorts && type == "r" }
        return buildString {
            append("https://www.reddit.com/$type/$name/")
            if (sort != null) append("$sort/")
            append(".rss")
        }
    }
}
