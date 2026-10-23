package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.TestFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpmlTest {
    @Test
    fun feedlyExportKeepsFolders() {
        val doc = Opml.parse(TestFiles.bytes("opml/feedly.opml"))
        assertEquals("Abonnements Feedly", doc.title)
        assertEquals(5, doc.feeds.size)
        val verge = doc.feeds.first { it.title == "The Verge" }
        assertEquals("Tech", verge.folder)
        assertEquals("https://www.theverge.com/rss/index.xml", verge.xmlUrl)
        assertEquals("https://www.theverge.com/", verge.htmlUrl)
        assertEquals("Actualités", doc.feeds.first { it.title == "El País" }.folder)
        assertNull(doc.feeds.first { it.title == "Android Developers" }.folder)
    }

    @Test
    fun inoreaderExportDecodesEntities() {
        val doc = Opml.parse(TestFiles.bytes("opml/inoreader.xml"))
        assertEquals(3, doc.feeds.size)
        val nature = doc.feeds.first { it.xmlUrl.contains("nature.com") }
        assertEquals("Nature & Science", nature.title)
        assertEquals("https://www.nature.com/nature.rss?foo=1&bar=2", nature.xmlUrl)
        assertEquals("Reddit", doc.feeds.first { it.title == "r/androiddev" }.folder)
    }

    @Test
    fun netNewsWireExportWithRootFeeds() {
        val doc = Opml.parse(TestFiles.bytes("opml/netnewswire.opml"))
        assertEquals(3, doc.feeds.size)
        assertNull(doc.feeds.first { it.title == "Daring Fireball" }.folder)
        assertEquals("Blogs", doc.feeds.first { it.title == "Six Colors" }.folder)
    }

    @Test
    fun brokenFileIsReadTolerantly() {
        val doc = Opml.parse(TestFiles.bytes("opml/broken.opml"))
        val urls = doc.feeds.map { it.xmlUrl }

        // Uppercase tags and attributes, nested folders flattened.
        val upper = doc.feeds.first { it.xmlUrl == "https://example.com/upper.xml" }
        assertEquals("Tech / Sous-dossier", upper.folder)
        // Whitespace trimmed, missing scheme added, feed:// converted, "url" attribute accepted.
        assertTrue("https://example.com/spaces.xml" in urls)
        assertTrue("https://example.org/feed" in urls)
        assertTrue("https://example.net/rss" in urls)
        assertTrue("https://example.io/atom.xml" in urls)
        // type="link" outlines and javascript: URLs are not feeds.
        assertTrue(urls.none { it.contains("example.io/page") })
        assertTrue(urls.none { it.startsWith("javascript") })
        // http://www.example.com/upper.xml/ is the same feed as https://example.com/upper.xml
        assertEquals(1, doc.duplicatesInFile)
        // Double-escaped title.
        assertEquals("Tom & Jerry", doc.feeds.first { it.xmlUrl.endsWith("tj.xml") }.title)
        // OPML 2.0 category attribute.
        assertEquals("Sport / Vélo", doc.feeds.first { it.xmlUrl.endsWith("velo.xml") }.folder)
    }

    @Test
    fun badlyBrokenFileFallsBackToRegex() {
        val doc = Opml.parse(TestFiles.bytes("opml/regex-only.opml"))
        val urls = doc.feeds.map { it.xmlUrl }.toSet()
        assertTrue("got $urls", "https://a.example.com/feed" in urls)
        assertTrue("got $urls", "https://b.example.com/rss.xml" in urls)
    }

    @Test
    fun utf16FileIsRead() {
        val text = TestFiles.text("opml/netnewswire.opml").replace("encoding=\"UTF-8\"", "encoding=\"UTF-16\"")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        assertEquals(3, Opml.parse(bytes).feeds.size)
    }

    @Test
    fun emptyOrNonOpmlFileIsRejected() {
        for (input in listOf(ByteArray(0), "<html><body>hello</body></html>".toByteArray())) {
            try {
                Opml.parse(input)
                fail("Expected OpmlException")
            } catch (e: OpmlException) {
                assertTrue(e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun exportThenImportRoundTrips() {
        val feeds = listOf(
            OpmlFeed("Le Monde", "https://www.lemonde.fr/rss/une.xml", "https://www.lemonde.fr", "Actualités"),
            OpmlFeed("A \"quoted\" & <odd> title", "https://example.com/feed?a=1&b=2", null, null),
            OpmlFeed("r/android", "https://www.reddit.com/r/android/.rss", null, "Reddit"),
        )
        val parsed = Opml.parse(Opml.write("Test", feeds, "2025-06-03T00:00:00Z"))
        assertEquals(feeds.toSet(), parsed.feeds.toSet())
    }
}
