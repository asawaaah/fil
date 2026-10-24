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
}
