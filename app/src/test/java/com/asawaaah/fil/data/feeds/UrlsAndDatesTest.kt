package com.asawaaah.fil.data.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UrlsAndDatesTest {
    @Test
    fun cleanUrls() {
        assertEquals("https://example.com/feed", Urls.clean("  example.com/feed "))
        assertEquals("https://example.com/rss", Urls.clean("feed://example.com/rss"))
        assertEquals("https://example.com/rss", Urls.clean("feed:https://example.com/rss"))
        assertNull(Urls.clean("javascript:alert(1)"))
        assertNull(Urls.clean(""))
        assertNull(Urls.clean("not a url"))
    }

    @Test
    fun normalizeIgnoresSchemeWwwAndTrailingSlash() {
        val a = Urls.normalize("http://www.Example.com/feed/")
        val b = Urls.normalize("https://example.com/feed")
        assertEquals(a, b)
        assertTrue(Urls.normalize("https://example.com/feed?x=1") != Urls.normalize("https://example.com/feed?x=2"))
    }

    @Test
    fun redditUrls() {
        assertEquals("https://www.reddit.com/r/android/.rss", RedditUrls.toFeedUrl("r/android"))
        assertEquals("https://www.reddit.com/r/android/.rss", RedditUrls.toFeedUrl("/r/android/"))
        assertEquals("https://www.reddit.com/r/android/new/.rss", RedditUrls.toFeedUrl("https://old.reddit.com/r/android/new/"))
        assertEquals("https://www.reddit.com/user/spez/.rss", RedditUrls.toFeedUrl("u/spez"))
        assertEquals("https://www.reddit.com/r/androiddev/.rss", RedditUrls.toFeedUrl("https://www.reddit.com/r/androiddev/.rss"))
        assertNull(RedditUrls.toFeedUrl("https://example.com/r/android"))
        assertTrue(RedditUrls.isReddit("https://np.reddit.com/r/x"))
        assertFalse(RedditUrls.isReddit("https://notreddit.com/r/x"))
    }

    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun rfc822Variants() {
        val expected = millis("2025-06-03T09:39:21Z")
        assertEquals(expected, DateParser.parse("Tue, 03 Jun 2025 09:39:21 +0000"))
        assertEquals(expected, DateParser.parse("Tue, 03 Jun 2025 09:39:21 GMT"))
        assertEquals(expected, DateParser.parse("03 Jun 2025 09:39:21 UT"))
        assertEquals(expected, DateParser.parse("Tue, 3 Jun 2025 05:39:21 EDT"))
        assertEquals(expected, DateParser.parse("Tue, 03 Jun 2025 11:39:21 +02:00"))
        assertEquals(expected, DateParser.parse("Tuesday, 03 June 2025 09:39:21 Z"))
        assertEquals(expected, DateParser.parse("Tue, 03 Jun 25 09:39:21 +0000"))
        assertEquals(millis("2025-06-03T09:39:00Z"), DateParser.parse("Tue, 03 Jun 2025 09:39 +0000"))
        assertEquals(expected, DateParser.parse("Tue, 03 Jun 2025 09:39:21"))
    }

    @Test
    fun isoVariants() {
        val expected = millis("2025-06-03T09:39:21Z")
        assertEquals(expected, DateParser.parse("2025-06-03T09:39:21Z"))
        assertEquals(expected, DateParser.parse("2025-06-03T11:39:21+02:00"))
        assertEquals(expected, DateParser.parse("2025-06-03T11:39:21+0200"))
        assertEquals(expected + 123, DateParser.parse("2025-06-03T09:39:21.123Z"))
        assertEquals(expected, DateParser.parse("2025-06-03 09:39:21"))
        assertEquals(millis("2025-06-03T00:00:00Z"), DateParser.parse("2025-06-03"))
    }

    @Test
    fun garbageDatesAreNull() {
        assertNull(DateParser.parse(null))
        assertNull(DateParser.parse(""))
        assertNull(DateParser.parse("hier soir"))
    }
}
