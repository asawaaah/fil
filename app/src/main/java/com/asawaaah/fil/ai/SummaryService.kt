package com.asawaaah.fil.ai

import com.asawaaah.fil.data.ArticleRepository
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.extract.HtmlBlocks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

sealed interface SummaryProgress {
    data class Working(val stage: String, val partial: String? = null) : SummaryProgress
    /** [detail] is set for a summary made just now, e.g. "processeur · 14 s". */
    data class Done(val text: String, val modelName: String, val detail: String? = null) : SummaryProgress
    data class Failed(val message: String) : SummaryProgress
}

class SummaryService(
    private val db: FilDatabase,
    private val articles: ArticleRepository,
    private val summarizer: Summarizer,
) {
    /** Summary of an article, from the cache or generated now on the device. */
    fun summarize(articleId: Long, force: Boolean = false, allowNetwork: Boolean = true): Flow<SummaryProgress> = flow {
        val cached = articles.content(articleId)
        if (!force && cached?.summary != null) {
            emit(SummaryProgress.Done(cached.summary, ModelCatalog.byId(cached.summaryModel).name))
            return@flow
        }
        val article = db.articleDao().getById(articleId) ?: run {
            emit(SummaryProgress.Failed("Article introuvable"))
            return@flow
        }
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
            summarizer.summarize(article.title, text).collect { event ->
                when (event) {
                    SummaryEvent.LoadingModel -> emit(SummaryProgress.Working("Chargement du modèle…"))
                    SummaryEvent.Reading -> emit(SummaryProgress.Working("Lecture de l'article…"))
                    is SummaryEvent.Partial -> emit(SummaryProgress.Working("Rédaction du résumé…", event.text))
                    is SummaryEvent.Done -> {
                        if (event.text.isBlank()) {
                            emit(SummaryProgress.Failed("Le modèle n'a rien produit. Réessaie."))
                        } else {
                            articles.updateContent(articleId) {
                                it.copy(summary = event.text, summaryModel = event.model.id, summaryAt = System.currentTimeMillis())
                            }
                            val seconds = (System.currentTimeMillis() - started + 500) / 1000
                            val detail = listOfNotNull(event.accelerator?.label, "$seconds s").joinToString(" · ")
                            emit(SummaryProgress.Done(event.text, event.model.name, detail))
                        }
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
