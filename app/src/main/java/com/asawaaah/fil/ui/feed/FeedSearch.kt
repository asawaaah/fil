package com.asawaaah.fil.ui.feed

import com.asawaaah.fil.data.db.ArticleListItem
import com.asawaaah.fil.ui.reader.ArticleSearch

/** Narrowing chips under the search field. */
data class SearchFilters(val unreadOnly: Boolean = false, val savedOnly: Boolean = false, val lastWeek: Boolean = false)

/**
 * Search through the stored articles: titles (original and French), excerpts and source names,
 * ignoring case and accents. Every word of the query must be found, in any order.
 */
object FeedSearch {
    const val MIN_WORD = 2

    /** An article with its searchable text folded once, so that typing stays instant. */
    class Entry(val item: ArticleListItem, val haystack: String)

    fun index(items: List<ArticleListItem>): List<Entry> = items.map { item ->
        Entry(
            item,
            ArticleSearch.fold(
                listOfNotNull(item.title, item.titleFr, item.excerpt, item.excerptFr, item.feedTitle).joinToString(" \u0000 "),
            ).replace("ß", "ss"), // "fussball" finds "Fußball"
        )
    }

    /** The query's words, folded; single letters are left out. */
    fun words(query: String): List<String> =
        ArticleSearch.fold(query).replace("ß", "ss").split(Regex("[\\s,;]+")).filter { it.length >= MIN_WORD }.distinct()

    fun search(
        index: List<Entry>,
        query: String,
        filters: SearchFilters = SearchFilters(),
        now: Long = System.currentTimeMillis(),
    ): List<ArticleListItem> {
        val words = words(query)
        if (words.isEmpty()) return emptyList()
        val weekAgo = now - 7 * 24 * 3600 * 1000L
        return index.asSequence()
            .filter { e -> words.all { e.haystack.contains(it) } }
            .map { it.item }
            .filter { !filters.unreadOnly || !it.isRead }
            .filter { !filters.savedOnly || it.isSaved }
            .filter { !filters.lastWeek || it.publishedAt >= weekAgo }
            .sortedByDescending { it.publishedAt }
            .toList()
    }

    /** Where the query's words are in [text] (start, end), to highlight them; positions match [text]. */
    fun ranges(text: String, words: List<String>): List<IntRange> {
        if (words.isEmpty()) return emptyList()
        val folded = ArticleSearch.fold(text)
        val out = ArrayList<IntRange>()
        for (w in words) {
            var from = 0
            while (true) {
                val at = folded.indexOf(w, from)
                if (at < 0) break
                out += at until at + w.length
                from = at + w.length
            }
        }
        return out.sortedBy { it.first }
    }
}
