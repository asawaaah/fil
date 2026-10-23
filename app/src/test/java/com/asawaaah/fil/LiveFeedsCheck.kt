package com.asawaaah.fil

import com.asawaaah.fil.data.extract.ArticleExtractor
import com.asawaaah.fil.data.extract.HtmlBlocks
import com.asawaaah.fil.data.feeds.FeedDiscovery
import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.Http
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Hits real websites. Skipped unless FIL_LIVE=1, e.g.:
 *   $env:FIL_LIVE = "1"; .\build.ps1 testDebugUnitTest --tests "*LiveFeedsCheck*"
 */
class LiveFeedsCheck {
    private val http = Http()
    private val discovery = FeedDiscovery(http)

    @Test
    fun realSites() = runBlocking {
        assumeTrue(System.getenv("FIL_LIVE") == "1")
        val inputs = listOf("lemonde.fr", "theverge.com", "elpais.com", "https://www.spiegel.de", "r/androiddev", "arstechnica.com")
        val report = StringBuilder()
        for (input in inputs) {
            when (val r = discovery.discover(input)) {
                is FeedDiscovery.Result.NotFound -> report.appendLine("FAIL  $input -> ${r.reason}")
                is FeedDiscovery.Result.Found -> {
                    val first = r.feed.items.firstOrNull()
                    report.appendLine(
                        "OK    $input -> ${r.feedUrl} | ${r.feed.title} | lang=${r.feed.language} | ${r.feed.items.size} items" +
                            " | first: ${first?.title?.take(60)} | date=${first?.publishedAt != null} | img=${first?.imageUrl != null}",
                    )
                    val target = first?.url
                    if (target != null && !r.isReddit) {
                        val page = runCatching { http.fetch(target, forPage = true) as FetchResult.Ok }.getOrNull()
                        val extracted = page?.let { ArticleExtractor.extract(it.bytes, it.charset, it.finalUrl) }
                        val blocks = extracted?.let { HtmlBlocks.parse(it.html, target) }.orEmpty()
                        report.appendLine(
                            "      extraction: ${extracted?.textLength ?: "none"} chars, ${blocks.size} blocks; " +
                                "start: ${HtmlBlocks.plainText(blocks).take(120).replace('\n', ' ')}",
                        )
                    }
                }
            }
        }
        println(report)
        java.io.File(System.getProperty("java.io.tmpdir"), "fil-live-report.txt").writeText(report.toString())
    }
}
