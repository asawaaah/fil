package com.asawaaah.fil.ai

import com.asawaaah.fil.data.db.ArticleContentEntity

/** How long a summary is: [label] for the chips, [maxTokens] for the model's answer. */
enum class SummaryLength(val label: String, val maxTokens: Int) {
    SHORT("Court", 160),
    MEDIUM("Moyen", 512),
    LONG("Détaillé", 1000),
    ;

    companion object {
        fun of(name: String?): SummaryLength = entries.firstOrNull { it.name == name } ?: MEDIUM
    }
}

/** One stored summary: its text, the language it is written in, its French translation, the model. */
data class StoredSummary(val text: String, val lang: String, val french: String?, val model: String?)

/** Summaries are kept per length: the medium one in the original columns, short and long beside. */
fun ArticleContentEntity.summaryOf(length: SummaryLength): StoredSummary? = when (length) {
    SummaryLength.SHORT -> summaryShort?.let { StoredSummary(it, summaryShortLang ?: "fr", summaryShortFr, summaryShortModel) }
    SummaryLength.MEDIUM -> summary?.let { StoredSummary(it, summaryLang ?: "fr", summaryFr, summaryModel) }
    SummaryLength.LONG -> summaryLong?.let { StoredSummary(it, summaryLongLang ?: "fr", summaryLongFr, summaryLongModel) }
}

fun ArticleContentEntity.withSummary(length: SummaryLength, s: StoredSummary, at: Long): ArticleContentEntity = when (length) {
    SummaryLength.SHORT -> copy(summaryShort = s.text, summaryShortLang = s.lang, summaryShortFr = s.french, summaryShortModel = s.model)
    SummaryLength.MEDIUM -> copy(summary = s.text, summaryLang = s.lang, summaryFr = s.french, summaryModel = s.model, summaryAt = at)
    SummaryLength.LONG -> copy(summaryLong = s.text, summaryLongLang = s.lang, summaryLongFr = s.french, summaryLongModel = s.model)
}

fun ArticleContentEntity.withSummaryFr(length: SummaryLength, french: String): ArticleContentEntity = when (length) {
    SummaryLength.SHORT -> copy(summaryShortFr = french)
    SummaryLength.MEDIUM -> copy(summaryFr = french)
    SummaryLength.LONG -> copy(summaryLongFr = french)
}
