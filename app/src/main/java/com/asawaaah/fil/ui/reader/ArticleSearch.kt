package com.asawaaah.fil.ui.reader

import com.asawaaah.fil.data.extract.Block
import java.text.Normalizer

/** One occurrence of the searched words: in the title when [block] is [TITLE], else in that block. */
data class SearchMatch(val block: Int, val start: Int, val end: Int) {
    companion object {
        const val TITLE = -1
    }
}

/** Find-in-article, ignoring case and accents ("eleve" finds "Élevé"). */
object ArticleSearch {
    const val MIN_QUERY = 2

    /** Text of a block as shown on screen, or null when it has none (divider, image without caption). */
    fun textOf(block: Block): String? = when (block) {
        is Block.Heading -> block.text.text
        is Block.Paragraph -> block.text.text
        is Block.Quote -> block.text.text
        is Block.ListItem -> block.text.text
        is Block.Code -> block.text
        is Block.Image -> block.caption
        Block.Divider -> null
    }

    fun find(query: String, title: String?, blocks: List<Block>): List<SearchMatch> {
        val needle = fold(query.trim())
        if (needle.length < MIN_QUERY) return emptyList()
        val out = ArrayList<SearchMatch>()
        title?.let { addMatches(out, SearchMatch.TITLE, it, needle) }
        blocks.forEachIndexed { i, block -> textOf(block)?.let { addMatches(out, i, it, needle) } }
        return out
    }

    private fun addMatches(out: MutableList<SearchMatch>, block: Int, text: String, needle: String) {
        val hay = fold(text)
        var from = 0
        while (true) {
            val at = hay.indexOf(needle, from)
            if (at < 0) break
            out += SearchMatch(block, at, at + needle.length)
            from = at + needle.length
        }
    }

    /**
     * Lower case without accents, one character for one character, so that positions found in
     * the folded text are also valid in the original text.
     */
    fun fold(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val base = if (c.code < 128) c else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).firstOrNull() ?: c
            sb.append(base.lowercaseChar())
        }
        return sb.toString()
    }
}
