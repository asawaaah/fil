package com.asawaaah.fil.ui.feed

import com.asawaaah.fil.data.db.ArticleListItem
import com.asawaaah.fil.data.topics.Topics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One row of the feed once articles are split into days and grouped by source. */
sealed interface FeedEntry {
    /** Stable key for the list (each article appears only once, alone or in a group). */
    val key: String

    data class DayHeader(val day: LocalDate) : FeedEntry {
        override val key: String get() = "d:${day.toEpochDay()}"
    }

    data class Single(val item: ArticleListItem) : FeedEntry {
        override val key: String get() = articleKey(item.id)
    }

    /** Articles of one source published the same day, newest first. */
    data class Group(val feedId: Long, val day: LocalDate, val items: List<ArticleListItem>) : FeedEntry {
        override val key: String get() = "g:$feedId:${day.toEpochDay()}"
        val lead: ArticleListItem get() = items.first()
        val feedTitle: String get() = lead.feedTitle
        val unreadCount: Int get() = items.count { !it.isRead }
    }

    /**
     * End of a day: [count] articles of hidden [topics] were set aside. [revealed]: the user asked
     * to see them for that day (they are then in the list above).
     */
    data class Hidden(val day: LocalDate, val count: Int, val topics: List<String>, val revealed: Boolean) : FeedEntry {
        override val key: String get() = "h:${day.toEpochDay()}"
    }

    companion object {
        fun articleKey(id: Long) = "a:$id"
    }
}

object FeedGrouping {
    /** A source needs this many articles in a day to be grouped: 1 or 2 are not spam. */
    const val MIN_GROUP = 3

    /** "de The Verge", "d'El País", "du Monde", "des Échos", "de la Croix". */
    fun ofSource(name: String): String = when {
        name.startsWith("Le ") -> "du " + name.removePrefix("Le ")
        name.startsWith("Les ") -> "des " + name.removePrefix("Les ")
        name.startsWith("La ") -> "de la " + name.removePrefix("La ")
        name.firstOrNull()?.lowercaseChar()?.let { it in "aeiouyhéèêâîôœ" } == true -> "d'$name"
        else -> "de $name"
    }

    /** "12 articles du Monde". */
    fun articlesOf(group: FeedEntry.Group): String = "${group.items.size} articles ${ofSource(group.feedTitle)}"

    /**
     * Splits [items] into days (newest first, each day under a header) and, when [groupBySource]
     * is on, replaces the articles of a source that published at least [MIN_GROUP] times that day
     * by one group, placed where its newest article was. Articles of [hiddenTopics] are left out
     * and counted at the end of their day, except on [revealedDays].
     */
    fun build(
        items: List<ArticleListItem>,
        groupBySource: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        hiddenTopics: Set<String> = emptySet(),
        revealedDays: Set<LocalDate> = emptySet(),
    ): List<FeedEntry> {
        // Dates in the future (wrong clocks on some sites) count as today.
        fun dayOf(item: ArticleListItem) = minOf(Instant.ofEpochMilli(item.publishedAt).atZone(zone).toLocalDate(), today)

        val out = ArrayList<FeedEntry>(items.size + 8)
        val sorted = items.sortedByDescending { it.publishedAt }
        var start = 0
        while (start < sorted.size) {
            val day = dayOf(sorted[start])
            var end = start
            while (end < sorted.size && dayOf(sorted[end]) == day) end++
            val all = sorted.subList(start, end)
            start = end
            val hidden = if (hiddenTopics.isEmpty()) emptyList() else all.filter { Topics.isHidden(Topics.parse(it.topics), hiddenTopics) }
            val revealed = day in revealedDays
            val dayItems = if (hidden.isEmpty() || revealed) all else all - hidden.toSet()
            out += FeedEntry.DayHeader(day)
            if (!groupBySource) {
                dayItems.mapTo(out) { FeedEntry.Single(it) }
            } else {
                val bySource = dayItems.groupBy { it.feedId }
                val placed = HashSet<Long>()
                for (item in dayItems) {
                    val sameSource = bySource.getValue(item.feedId)
                    when {
                        sameSource.size < MIN_GROUP -> out += FeedEntry.Single(item)
                        placed.add(item.feedId) -> out += FeedEntry.Group(item.feedId, day, sameSource)
                    }
                }
            }
            if (hidden.isNotEmpty()) {
                val why = hidden.mapNotNull { Topics.hidingTopic(Topics.parse(it.topics), hiddenTopics) }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
                out += FeedEntry.Hidden(day, hidden.size, why, revealed)
            }
        }
        return out
    }
}
