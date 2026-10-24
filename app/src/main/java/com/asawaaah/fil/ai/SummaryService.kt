package com.asawaaah.fil.ai

import com.asawaaah.fil.data.ArticleRepository
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.extract.HtmlBlocks
import com.asawaaah.fil.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

sealed interface SummaryProgress {
    data class Working(val stage: String, val partial: String? = null) : SummaryProgress

    /**
     * [text] is written in [language]; [french] is its French translation when there is one.
     * [detail] is set for a summary made just now, e.g. "processeur · 14 s".
     */
    data class Done(
        val text: String,
        val modelName: String,
        val detail: String? = null,
        val language: String = "fr",
        val french: String? = null,
    ) : SummaryProgress

    data class Failed(val message: String) : SummaryProgress
}

/** Which language a summary is written in. */
object SummaryLanguage {
    /**
     * French when articles are translated automatically, or when the article's language can't be
     * translated anyway; otherwise the article's own language (a translation is offered next to it).
     */
    fun target(articleLang: String?, autoTranslate: Boolean, translatable: Boolean): String {
        val lang = articleLang?.lowercase()?.substringBefore('-')
        return if (autoTranslate || !translatable || lang.isNullOrBlank() || lang == "und") "fr" else lang
    }
}

class SummaryService(
    private val db: FilDatabase,
    private val articles: ArticleRepository,
    private val summarizer: Summarizer,
    private val settings: SettingsRepository,
    private val translation: TranslationService,
) {
    private suspend fun targetFor(articleLang: String?): String =
        SummaryLanguage.target(articleLang, settings.current().autoTranslate, translation.translator.canTranslate(articleLang))

    /** Summary already made for this article, translated into French if the settings want French. */
    suspend fun cached(articleId: Long): SummaryProgress.Done? {
        val content = articles.content(articleId) ?: return null
        val summary = content.summary ?: return null
        val lang = content.summaryLang ?: "fr"
        var french = content.summaryFr
        if (lang != "fr" && french == null) {
            val article = db.articleDao().getById(articleId)
            // Never regenerate just to change language: translate the existing text.
            if (targetFor(article?.lang) == "fr") french = translateAndStore(articleId, summary, lang)
        }
        return SummaryProgress.Done(summary, ModelCatalog.byId(content.summaryModel).name, language = lang, french = french)
    }

    /** French version of the article's summary, translated now if needed. Null when it can't be done now. */
    suspend fun translateSummary(articleId: Long): String? {
        val content = articles.content(articleId) ?: return null
        content.summaryFr?.let { return it }
        val summary = content.summary ?: return null
        return translateAndStore(articleId, summary, content.summaryLang ?: return summary)
    }

    private suspend fun translateAndStore(articleId: Long, text: String, lang: String): String? {
        val french = translation.translateLines(text, lang) ?: return null
        articles.updateContent(articleId) { it.copy(summaryFr = french) }
        return french
    }

    /** Summary of an article, from the cache or generated now on the device. */
    fun summarize(articleId: Long, force: Boolean = false, allowNetwork: Boolean = true): Flow<SummaryProgress> = flow {
        if (!force) {
            cached(articleId)?.let {
                emit(it)
                return@flow
            }
        }
        val article = db.articleDao().getById(articleId) ?: run {
            emit(SummaryProgress.Failed("Article introuvable"))
            return@flow
        }
        val target = targetFor(article.lang)
        val started = System.currentTimeMillis()
        emit(SummaryProgress.Working("Récupération de l'article…"))
        val feed = db.feedDao().getById(article.feedId)
        val reader = articles.readerContent(article, feed, allowNetwork)
        val text = withContext(Dispatchers.Default) {
            HtmlBlocks.plainText(HtmlBlocks.parse(reader.html, reader.baseUrl))
        }.ifBlank { article.excerpt.orEmpty() }
        if (text.length < 200) {
            emit(SummaryProgress.Failed("Cet article est trop court pour être résumé."))
            return@flow
        }
        try {
            summarizer.summarize(article.title, text, target).collect { event ->
                when (event) {
                    SummaryEvent.LoadingModel -> emit(SummaryProgress.Working("Chargement du modèle…"))
                    SummaryEvent.Reading -> emit(SummaryProgress.Working("Lecture de l'article…"))
                    is SummaryEvent.Partial -> emit(SummaryProgress.Working("Rédaction du résumé…", event.text))
                    is SummaryEvent.Done -> {
                        if (event.text.isBlank()) {
                            emit(SummaryProgress.Failed("Le modèle n'a rien produit. Réessaie."))
                            return@collect
                        }
                        var lang = target
                        var french: String? = null
                        if (target == "fr") {
                            // Small models sometimes answer in the article's language anyway.
                            val written = translation.detect(event.text)
                            if (written != null && written != "fr" && translation.translator.canTranslate(written)) {
                                emit(SummaryProgress.Working("Traduction du résumé…"))
                                lang = written
                                french = translation.translateLines(event.text, written)
                            }
                        }
                        articles.updateContent(articleId) {
                            it.copy(
                                summary = event.text,
                                summaryModel = event.model.id,
                                summaryAt = System.currentTimeMillis(),
                                summaryLang = lang,
                                summaryFr = french,
                            )
                        }
                        val seconds = (System.currentTimeMillis() - started + 500) / 1000
                        val detail = listOfNotNull(event.accelerator?.label, "$seconds s").joinToString(" · ")
                        emit(SummaryProgress.Done(event.text, event.model.name, detail, lang, french))
                    }
                }
            }
        } catch (e: ModelNotInstalledException) {
            emit(SummaryProgress.Failed("Télécharge d'abord le modèle ${e.model.name} dans les réglages."))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            emit(SummaryProgress.Failed("Le résumé a échoué : ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
