package com.asawaaah.fil.ui.feed

import com.asawaaah.fil.data.db.ArticleListItem
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedSearchTest {
    private val now = 1_800_000_000_000L
    private val day = 24 * 3600 * 1000L

    private fun item(
        id: Long,
        title: String,
        titleFr: String? = null,
        excerpt: String? = null,
        feed: String = "Le Monde",
        ageDays: Int = 0,
        read: Boolean = false,
        saved: Boolean = false,
    ) = ArticleListItem(
        id = id, feedId = 1, title = title, titleFr = titleFr, excerpt = excerpt, excerptFr = null,
        imageUrl = null, url = null, publishedAt = now - ageDays * day, isRead = read, isSaved = saved,
        lang = null, feedTitle = feed, feedIconUrl = null, feedSiteUrl = null, hasSummary = false,
    )

    private val items = listOf(
        item(1, "Fußball-Bundesliga: Werder Bremen gewinnt", titleFr = "Bundesliga : le Werder Brême gagne", feed = "Der Spiegel"),
        item(2, "Réforme des retraites : le Sénat vote", excerpt = "Le texte élargit l'âge légal", ageDays = 2, read = true),
        item(3, "Élections européennes : les sondages", ageDays = 10, saved = true),
        item(4, "Climate summit opens", titleFr = "Le sommet sur le climat s'ouvre", feed = "The Guardian", ageDays = 1),
    )
    private val index = FeedSearch.index(items)

    private fun ids(q: String, f: SearchFilters = SearchFilters()) = FeedSearch.search(index, q, f, now).map { it.id }

    @Test
    fun ignoresCaseAndAccents() {
        assertEquals(listOf(3L), ids("ELECTIONS europeennes"))
        assertEquals(listOf(2L), ids("senat"))
    }

    @Test
    fun searchesTranslatedTitlesExcerptsAndSources() {
        assertEquals(listOf(4L), ids("climat"))
        assertEquals(listOf(2L), ids("age legal"))
        assertEquals(listOf(4L), ids("guardian"))
        assertEquals(listOf(1L), ids("brême"))
        assertEquals(listOf(1L), ids("fussball"))
    }

    @Test
    fun everyWordMustBeFound() {
        assertEquals(listOf(1L), ids("werder bundesliga"))
        assertEquals(emptyList<Long>(), ids("werder retraites"))
    }

    @Test
    fun newestFirstAndFilters() {
        // "le" is in titles, an excerpt and the source name "Le Monde": newest first.
        assertEquals(listOf(1L, 4L, 2L, 3L), ids("le"))
        assertEquals(listOf(3L), ids("sondages", SearchFilters(savedOnly = true)))
        assertEquals(emptyList<Long>(), ids("senat", SearchFilters(unreadOnly = true)))
        assertEquals(emptyList<Long>(), ids("sondages", SearchFilters(lastWeek = true)))
    }

    @Test
    fun tooShortQueryFindsNothing() {
        assertEquals(emptyList<Long>(), ids(" a "))
    }

    @Test
    fun rangesPointAtTheShownText() {
        val title = "Réforme des retraites : le Sénat vote"
        val r = FeedSearch.ranges(title, FeedSearch.words("senat reforme"))
        assertEquals(listOf("Réforme", "Sénat"), r.map { title.substring(it) })
    }
}
