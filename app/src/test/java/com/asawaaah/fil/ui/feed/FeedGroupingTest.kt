package com.asawaaah.fil.ui.feed

import com.asawaaah.fil.data.db.ArticleListItem
import com.asawaaah.fil.ui.common.TimeFormat
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class FeedGroupingTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 23)
    private var nextId = 1L

    private fun article(feedId: Long, at: LocalDateTime, read: Boolean = false, topics: String? = null) = ArticleListItem(
        id = nextId++,
        feedId = feedId,
        title = "Article $nextId",
        titleFr = null,
        excerpt = null,
        excerptFr = null,
        imageUrl = null,
        url = null,
        publishedAt = at.atZone(zone).toInstant().toEpochMilli(),
        isRead = read,
        isSaved = false,
        lang = null,
        feedTitle = "Source $feedId",
        feedIconUrl = null,
        feedSiteUrl = null,
        hasSummary = false,
        topics = topics,
    )

    private fun todayAt(hour: Int, minute: Int = 0) = today.atTime(hour, minute)

    /** Compact description of the entries: "D2026-10-23", "A3" (article 3), "G1x3" (source 1, 3 articles). */
    private fun describe(entries: List<FeedEntry>) = entries.map {
        when (it) {
            is FeedEntry.DayHeader -> "D${it.day}"
            is FeedEntry.Single -> "A${it.item.id}"
            is FeedEntry.Group -> "G${it.feedId}x${it.items.size}"
            is FeedEntry.Hidden -> "H${it.count}" + if (it.revealed) "r" else ""
        }
    }

    private fun build(items: List<ArticleListItem>, group: Boolean = true) =
        FeedGrouping.build(items, group, zone, today)

    @Test
    fun twoArticlesOfASourceStaySeparate() {
        val a = article(1, todayAt(10))
        val b = article(1, todayAt(9))
        assertEquals(listOf("D$today", "A${a.id}", "A${b.id}"), describe(build(listOf(a, b))))
    }

    @Test
    fun threeArticlesFormAGroupWhereTheNewestWas() {
        val other = article(2, todayAt(12))
        val newest = article(1, todayAt(11))
        val mid = article(2, todayAt(10, 30))
        val b = article(1, todayAt(10))
        val c = article(1, todayAt(8))
        val entries = build(listOf(other, newest, mid, b, c))
        assertEquals(listOf("D$today", "A${other.id}", "G1x3", "A${mid.id}"), describe(entries))
        val group = entries[2] as FeedEntry.Group
        assertEquals(listOf(newest.id, b.id, c.id), group.items.map { it.id })
        assertEquals(newest, group.lead)
    }

    @Test
    fun aSourceOnTwoDaysMakesTwoGroups() {
        val yesterday = today.minusDays(1)
        val items = listOf(
            article(1, todayAt(9)), article(1, todayAt(8)), article(1, todayAt(7)),
            article(1, yesterday.atTime(22, 0)), article(1, yesterday.atTime(21, 0)), article(1, yesterday.atTime(20, 0)),
            article(1, yesterday.atTime(19, 0)),
        )
        assertEquals(listOf("D$today", "G1x3", "D$yesterday", "G1x4"), describe(build(items)))
    }

    @Test
    fun unsortedInputIsSortedNewestFirst() {
        val old = article(2, todayAt(6))
        val recent = article(3, todayAt(18))
        assertEquals(listOf("D$today", "A${recent.id}", "A${old.id}"), describe(build(listOf(old, recent))))
    }

    @Test
    fun groupingOffKeepsOnlyHeadersAndArticles() {
        val items = listOf(article(1, todayAt(9)), article(1, todayAt(8)), article(1, todayAt(7)))
        assertEquals(listOf("D$today") + items.map { "A${it.id}" }, describe(build(items, group = false)))
    }

    @Test
    fun futureDatesCountAsToday() {
        val future = article(1, today.plusDays(2).atTime(9, 0))
        val now = article(2, todayAt(9))
        assertEquals(listOf("D$today", "A${future.id}", "A${now.id}"), describe(build(listOf(future, now))))
    }

    @Test
    fun groupCountsUnreadArticles() {
        val items = listOf(article(1, todayAt(9)), article(1, todayAt(8), read = true), article(1, todayAt(7)))
        assertEquals(2, (build(items)[1] as FeedEntry.Group).unreadCount)
    }

    @Test
    fun sourceNamesInFrench() {
        assertEquals("de The Verge", FeedGrouping.ofSource("The Verge"))
        assertEquals("d'El País", FeedGrouping.ofSource("El País"))
        assertEquals("du Monde", FeedGrouping.ofSource("Le Monde"))
        assertEquals("des Échos", FeedGrouping.ofSource("Les Échos"))
        assertEquals("de la Croix", FeedGrouping.ofSource("La Croix"))
        assertEquals("d'Ars Technica", FeedGrouping.ofSource("Ars Technica"))
    }

    @Test
    fun hiddenTopicsAreCountedAtTheEndOfTheirDay() {
        val foot1 = article(1, todayAt(10), topics = "sport.football")
        val news = article(2, todayAt(9))
        val foot2 = article(3, todayAt(8), topics = "sport.football,societe")
        val tennis = article(4, todayAt(7), topics = "sport.tennis")
        val entries = FeedGrouping.build(listOf(foot1, news, foot2, tennis), false, zone, today, hiddenTopics = setOf("sport.football"))
        assertEquals(listOf("D$today", "A${news.id}", "A${tennis.id}", "H2"), describe(entries))
        assertEquals(listOf("sport.football"), (entries.last() as FeedEntry.Hidden).topics)
    }

    @Test
    fun hidingSportHidesEverySportAndARevealedDayShowsThem() {
        val foot = article(1, todayAt(10), topics = "sport.football")
        val tennis = article(2, todayAt(9), topics = "sport.tennis")
        val hidden = FeedGrouping.build(listOf(foot, tennis), false, zone, today, hiddenTopics = setOf("sport"))
        assertEquals(listOf("D$today", "H2"), describe(hidden))
        val shown = FeedGrouping.build(listOf(foot, tennis), false, zone, today, hiddenTopics = setOf("sport"), revealedDays = setOf(today))
        assertEquals(listOf("D$today", "A${foot.id}", "A${tennis.id}", "H2r"), describe(shown))
    }

    @Test
    fun hiddenArticlesDontCountForPiles() {
        val items = listOf(
            article(1, todayAt(9), topics = "sport.football"), article(1, todayAt(8), topics = "sport.football"),
            article(1, todayAt(7)), article(1, todayAt(6)),
        )
        val entries = FeedGrouping.build(items, true, zone, today, hiddenTopics = setOf("sport.football"))
        assertEquals(listOf("D$today", "A${items[2].id}", "A${items[3].id}", "H2"), describe(entries))
    }

    @Test
    fun dayLabels() {
        assertEquals("Aujourd'hui", TimeFormat.dayLabel(today, today))
        assertEquals("Hier", TimeFormat.dayLabel(today.minusDays(1), today))
        assertEquals("Lundi 19 octobre", TimeFormat.dayLabel(LocalDate.of(2026, 10, 19), today))
        assertEquals("Mercredi 31 décembre 2025", TimeFormat.dayLabel(LocalDate.of(2025, 12, 31), today))
    }
}
