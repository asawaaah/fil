package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedParserTest {
    private fun millis(iso: String) = Instant.parse(iso).toEpochMilli()

    @Test
    fun rss2() {
        val feed = FeedParser.parse(TestFiles.bytes("feeds/rss2.xml"), null, "https://blog.example.com/feed/")
        assertEquals("Example & Co Blog", feed.title)
        assertEquals("https://blog.example.com/", feed.siteUrl)
        assertEquals("en-US", feed.language)
        assertEquals(3, feed.items.size)

        val first = feed.items[0]
        assertEquals("First post title", first.title)
        assertEquals("https://blog.example.com/first-post/", first.url)
        assertEquals("post-1", first.guid)
        assertEquals("Jane Doe", first.author)
        assertEquals(millis("2025-06-03T09:39:21Z"), first.publishedAt)
        assertEquals("https://cdn.example.com/thumb1.jpg", first.imageUrl)
        assertTrue(first.contentHtml!!.contains("Full text"))

        val second = feed.items[1]
        assertEquals("https://blog.example.com/second-post/", second.url)
        assertEquals(millis("2025-06-02T18:00:00Z"), second.publishedAt)
        assertTrue(second.summaryHtml!!.contains("<b>HTML</b>"))
        // Audio enclosure ignored, image found in the description.
        assertEquals("https://cdn.example.com/two.png", second.imageUrl)

        val third = feed.items[2]
        assertTrue(third.title.startsWith("Item without title"))
        assertEquals("https://blog.example.com/?p=3", third.url)
        assertNull(third.publishedAt)
    }

    @Test
    fun atom() {
        val feed = FeedParser.parse(TestFiles.bytes("feeds/atom.xml"), null, "https://atom.example.de/feed.xml")
        assertEquals("Ein Atom Feed", feed.title)
        assertEquals("https://atom.example.de/", feed.siteUrl)
        assertEquals("https://atom.example.de/favicon.png", feed.iconUrl)
        assertEquals("de", feed.language)
        assertEquals(2, feed.items.size)

        val first = feed.items[0]
        assertEquals("Erster Eintrag", first.title)
        assertEquals("https://atom.example.de/2025/erster", first.url)
        assertEquals("tag:atom.example.de,2025:1", first.guid)
        assertEquals("Max Mustermann", first.author)
        assertEquals(millis("2025-06-03T06:30:00Z"), first.publishedAt)
        assertTrue(first.contentHtml!!.contains("<strong>Inhalt</strong>"))

        val second = feed.items[1]
        assertEquals("Zweiter & letzter", second.title)
        assertEquals(millis("2025-06-01T12:00:00Z"), second.publishedAt)
        assertTrue(second.contentHtml!!.contains("&lt;kein html&gt;"))
    }

    @Test
    fun rss1RdfWithLatin1Encoding() {
        val feed = FeedParser.parse(TestFiles.bytes("feeds/rdf.xml"), null, "https://rdf.example.org/rss")
        assertEquals("RDF Site", feed.title)
        assertEquals(2, feed.items.size)
        assertEquals("Café crème", feed.items[0].title)
        assertEquals("Deuxième", feed.items[1].title)
        assertEquals(millis("2025-05-30T07:00:00Z"), feed.items[0].publishedAt)
        assertEquals(millis("2025-05-29T00:00:00Z"), feed.items[1].publishedAt)
    }

    @Test
    fun redditAtom() {
        val feed = FeedParser.parse(TestFiles.bytes("feeds/reddit.xml"), null, "https://www.reddit.com/r/androiddev/.rss")
        assertEquals("Android Developers", feed.title)
        assertEquals(2, feed.items.size)

        val self = feed.items[0]
        assertEquals("t3_abc123", self.guid)
        val selfInfo = RedditContent.parse(self.contentHtml)
        assertTrue(selfInfo.selfHtml!!.contains("Compose"))
        assertNull(selfInfo.linkUrl)

        val link = feed.items[1]
        assertEquals("https://b.thumbs.redditmedia.com/xyz.jpg", link.imageUrl)
        val linkInfo = RedditContent.parse(link.contentHtml)
        assertNull(linkInfo.selfHtml)
        assertEquals("https://android-developers.googleblog.com/2025/06/new-release.html", linkInfo.linkUrl)
    }

    @Test(expected = NotAFeedException::class)
    fun htmlPageIsNotAFeed() {
        FeedParser.parse(TestFiles.bytes("pages/article.html"), null, "https://example.com/")
    }

    @Test
    fun feedLinksAreFoundInHtml() {
        val links = HtmlFeedLinks.extract(TestFiles.text("pages/article.html"), "https://times.example.com/2025/rivers")
        assertEquals(listOf("https://times.example.com/feed.xml", "https://times.example.com/comments/feed.xml"), links)
    }

    @Test
    fun malformedXmlStillParses() {
        val xml = """<rss><channel><title>Broken & co</title><item><title>One</title><link>https://x.example/1</link>
            <description>unclosed <b>tag</description></item><item><title>Two</title></channel></rss>"""
        val feed = FeedParser.parse(xml, "https://x.example/")
        assertNotNull(feed.title)
        assertTrue(feed.items.isNotEmpty())
        assertEquals("One", feed.items[0].title)
    }
}
