package com.asawaaah.fil.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiTranslatorTest {
    @Test
    fun chunksSkipBlanksAndStaySmall() {
        val texts = listOf("Title", "", "a".repeat(700), "b".repeat(700), "", "c".repeat(100))
        val chunks = AiTranslator.chunks(texts)
        assertEquals(listOf(listOf(0, 2), listOf(3, 5)), chunks)
        assertTrue(AiTranslator.chunks(List(20) { "x" }).all { it.size <= 8 })
    }

    @Test
    fun numberedAnswerIsReadBack() {
        val raw = """
            [1] Premier paragraphe traduit.
            [2] Deuxième, sur
            deux lignes.

            [3] **Troisième**
        """.trimIndent()
        val parsed = AiTranslator.parse(raw, expected = 3)
        assertEquals("Premier paragraphe traduit.", parsed[0])
        assertEquals("Deuxième, sur\ndeux lignes.", parsed[1])
        assertEquals("Troisième", parsed[2])
    }

    @Test
    fun missingOrExtraNumbersAreIgnored() {
        val parsed = AiTranslator.parse("[1] Un\n[3] Trois\n[9] Hors limite", expected = 3)
        assertEquals(setOf(0, 2), parsed.keys)
    }

    @Test
    fun singleParagraphWithoutNumber() {
        assertEquals(mapOf(0 to "Bonjour le monde"), AiTranslator.parse("Bonjour le monde", expected = 1))
    }

    @Test
    fun promptKeepsNumbering() {
        val prompt = AiTranslator.buildPrompt(listOf("Hello  world", "Bye"), "anglais", ModelCatalog.GEMMA4_E2B)
        assertTrue(prompt.contains("[1] Hello world"))
        assertTrue(prompt.contains("[2] Bye"))
        assertTrue(prompt.contains("anglais"))
    }
}
