package com.asawaaah.fil.ai

import com.asawaaah.fil.data.extract.Block

/** One piece read aloud: [block] is the index in the article's blocks, or [TITLE]. */
data class Segment(val block: Int, val text: String) {
    companion object {
        const val TITLE = -1
    }
}

/** Cuts an article into short pieces for the phone's voice: quick to start, easy to skip. */
object ReadAloudScript {
    /** Longer pieces are cut between sentences, so that ⏭ never skips too much. */
    const val MAX_CHARS = 400

    fun build(title: String?, blocks: List<Block>): List<Segment> {
        val out = ArrayList<Segment>()
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { out += Segment(Segment.TITLE, it) }
        blocks.forEachIndexed { i, block ->
            val text = when (block) {
                is Block.Heading -> block.text.text
                is Block.Paragraph -> block.text.text
                is Block.Quote -> block.text.text
                is Block.ListItem -> block.text.text
                // Code, pictures and separators don't read well.
                is Block.Code, is Block.Image, Block.Divider -> null
            }?.replace(Regex("\\s+"), " ")?.trim()
            if (!text.isNullOrEmpty()) split(text).forEach { out += Segment(i, it) }
        }
        return out
    }

    fun split(text: String, max: Int = MAX_CHARS): List<String> {
        if (text.length <= max) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?…»\"])\\s+(?=[\\p{Lu}«\"0-9])"))
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + 1 + sentence.length > max) {
                out += current.toString()
                current.setLength(0)
            }
            if (sentence.length > max) {
                // A very long sentence: cut at commas or spaces.
                var rest = sentence
                while (rest.length > max) {
                    val cut = rest.lastIndexOf(", ", max).takeIf { it > max / 2 }?.plus(1)
                        ?: rest.lastIndexOf(' ', max).takeIf { it > 0 }
                        ?: max
                    out += rest.substring(0, cut).trim()
                    rest = rest.substring(cut).trim()
                }
                if (rest.isNotEmpty()) current.append(rest)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
