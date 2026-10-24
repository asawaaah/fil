package com.asawaaah.fil.ui.reader

import com.asawaaah.fil.data.extract.Block
import com.asawaaah.fil.data.extract.RichText
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleSearchTest {
    private val blocks = listOf(
        Block.Paragraph(RichText("Le niveau est très élevé, vraiment ÉLEVÉ.")),
        Block.Image("https://x/img.jpg", caption = "Un élève"),
        Block.Divider,
        Block.Code("val eleve = 1"),
    )

    @Test
    fun ignoresCaseAndAccents() {
        val matches = ArticleSearch.find("eleve", "Élevé ou pas", blocks)
        assertEquals(
            listOf(
                SearchMatch(SearchMatch.TITLE, 0, 5),
                SearchMatch(0, 19, 24),
                SearchMatch(0, 35, 40),
                SearchMatch(1, 3, 8),
                SearchMatch(3, 4, 9),
            ),
            matches,
        )
    }

    @Test
    fun positionsPointAtTheOriginalText() {
        val m = ArticleSearch.find("ÉLEVÉ", null, blocks)[1]
        assertEquals("ÉLEVÉ", (blocks[0] as Block.Paragraph).text.text.substring(m.start, m.end))
    }

    @Test
    fun tooShortQueryFindsNothing() {
        assertEquals(emptyList<SearchMatch>(), ArticleSearch.find(" e ", "élevé", blocks))
    }

    @Test
    fun foldKeepsLength() {
        val s = "Œuvre, ça, naïve, Ñandú, straße"
        assertEquals(s.length, ArticleSearch.fold(s).length)
        assertEquals("œuvre, ca, naive, nandu, straße", ArticleSearch.fold(s))
    }
}
