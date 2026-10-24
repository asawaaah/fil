package com.asawaaah.fil.ai

import com.asawaaah.fil.data.extract.Block
import com.asawaaah.fil.data.extract.RichText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAloudScriptTest {
    @Test
    fun readsTitleThenTextBlocksOnly() {
        val blocks = listOf(
            Block.Heading(2, RichText("Intertitre")),
            Block.Image("https://x/a.jpg", "Légende"),
            Block.Paragraph(RichText("Un   paragraphe\navec des espaces.")),
            Block.Code("val x = 1"),
            Block.Divider,
            Block.ListItem(RichText("Un point"), ordered = false, index = 1, depth = 0),
        )
        val script = ReadAloudScript.build("Le titre", blocks)
        assertEquals(
            listOf(
                Segment(Segment.TITLE, "Le titre"),
                Segment(0, "Intertitre"),
                Segment(2, "Un paragraphe avec des espaces."),
                Segment(5, "Un point"),
            ),
            script,
        )
    }

    @Test
    fun longParagraphsAreCutBetweenSentences() {
        val sentence = "Voici une phrase assez longue pour remplir de la place dans le texte lu à voix haute."
        val text = List(12) { sentence }.joinToString(" ")
        val parts = ReadAloudScript.split(text)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= ReadAloudScript.MAX_CHARS })
        assertTrue(parts.all { it.endsWith(".") })
        assertEquals(text, parts.joinToString(" "))
    }

    @Test
    fun aSentenceLongerThanTheLimitIsCutToo() {
        val text = List(80) { "mot" }.joinToString(", ") + "."
        val parts = ReadAloudScript.split(text, max = 100)
        assertTrue(parts.all { it.length <= 100 })
        assertEquals(text.replace(" ", ""), parts.joinToString("").replace(" ", ""))
    }

    @Test
    fun shortTextStaysWhole() {
        assertEquals(listOf("Court."), ReadAloudScript.split("Court."))
    }
}
