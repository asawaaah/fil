package com.asawaaah.fil.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryLanguageTest {
    @Test
    fun frenchWhenArticlesAreTranslatedAutomatically() {
        assertEquals("fr", SummaryLanguage.target("en", autoTranslate = true, translatable = true))
    }

    @Test
    fun articleLanguageWhenAutoTranslationIsOff() {
        assertEquals("es", SummaryLanguage.target("es", autoTranslate = false, translatable = true))
        assertEquals("de", SummaryLanguage.target("de-AT", autoTranslate = false, translatable = true))
    }

    @Test
    fun frenchWhenTheLanguageCantBeTranslatedOrIsUnknown() {
        assertEquals("fr", SummaryLanguage.target("xx", autoTranslate = false, translatable = false))
        assertEquals("fr", SummaryLanguage.target("und", autoTranslate = false, translatable = true))
        assertEquals("fr", SummaryLanguage.target(null, autoTranslate = false, translatable = true))
        assertEquals("fr", SummaryLanguage.target("fr", autoTranslate = false, translatable = false))
    }

    @Test
    fun promptAsksForTheChosenLanguage() {
        val model = ModelCatalog.GEMMA4_E2B
        val es = Summarizer.buildPrompt("Título", "Texto", model, "es")
        assertTrue(es, es.contains("in Spanish"))
        val fr = Summarizer.buildPrompt("Titre", "Texte", model, "fr")
        assertTrue(fr, fr.contains("en français"))
    }

    @Test
    fun promptFollowsTheLength() {
        val model = ModelCatalog.GEMMA4_E2B
        val short = Summarizer.buildPrompt("Titre", "Texte", model, "fr", SummaryLength.SHORT)
        assertTrue(short, short.contains("une ou deux phrases") && !short.contains("•"))
        assertTrue(Summarizer.buildPrompt("Titre", "Texte", model, "fr", SummaryLength.MEDIUM).contains("3 à 5 points"))
        val long = Summarizer.buildPrompt("Título", "Texto", model, "es", SummaryLength.LONG)
        assertTrue(long, long.contains("6 to 8 points") && long.contains("in Spanish"))
        assertTrue(SummaryLength.SHORT.maxTokens < SummaryLength.MEDIUM.maxTokens && SummaryLength.MEDIUM.maxTokens < SummaryLength.LONG.maxTokens)
        assertEquals(SummaryLength.MEDIUM, SummaryLength.of("nonsense"))
    }
}
