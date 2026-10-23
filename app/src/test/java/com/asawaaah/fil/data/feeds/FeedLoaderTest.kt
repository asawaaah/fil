package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class FeedLoaderTest {
    private fun page(html: String) = FetchResult.Ok(html.toByteArray(), "UTF-8", "text/html", "https://x.example/", null, null)

    @Test
    fun failuresAreClassifiedForTheUser() {
        assertEquals(FailureKind.GONE, FeedLoader.classify(UnknownHostException("x")))
        assertEquals(FailureKind.GONE, FeedLoader.classify(HttpStatusException(404)))
        assertEquals(FailureKind.GONE, FeedLoader.classify(HttpStatusException(410)))
        assertEquals(FailureKind.BLOCKED, FeedLoader.classify(HttpStatusException(403)))
        assertEquals(FailureKind.BLOCKED, FeedLoader.classify(HttpStatusException(429)))
        assertEquals(FailureKind.UNAVAILABLE, FeedLoader.classify(HttpStatusException(503)))
        assertEquals(FailureKind.UNAVAILABLE, FeedLoader.classify(SocketTimeoutException()))
    }

    @Test
    fun nonFeedPagesAreExplained() {
        val closed = FeedLoader.nonFeedError(page("<html><head><title>Typepad - Closed for Business</title></head><body>Bye</body></html>"))
        assertFalse(closed.blocked)
        assertTrue(closed.message!!.contains("Typepad - Closed for Business"))
        assertEquals(FailureKind.GONE, FeedLoader.classify(closed))

        val bot = FeedLoader.nonFeedError(page("<html><head><title>Making sure you're not a bot!</title></head><body>…</body></html>"))
        assertTrue(bot.blocked)
        assertEquals(FailureKind.BLOCKED, FeedLoader.classify(bot))

        val text = FeedLoader.nonFeedError(page("Stream not found!"))
        assertEquals("Le site répond : « Stream not found! »", text.message)
    }

    @Test
    fun baseDomainTellsSameSiteFromAnotherSite() {
        assertEquals("lemonde.fr", Urls.baseDomain("https://www.lemonde.fr/rss/une.xml"))
        assertEquals(Urls.baseDomain("https://pro.bpi.fr/feed"), Urls.baseDomain("https://balises.bpi.fr/feed"))
        assertEquals("bbc.co.uk", Urls.baseDomain("https://feeds.bbc.co.uk/news/rss.xml"))
        assertEquals("numerique.gouv.fr", Urls.baseDomain("https://www.numerique.gouv.fr/feed.xml"))
        assertNotEquals(Urls.baseDomain("http://www.lespetitescases.net/rss.xml"), Urls.baseDomain("https://epresskitz.com/feed"))
    }
}
