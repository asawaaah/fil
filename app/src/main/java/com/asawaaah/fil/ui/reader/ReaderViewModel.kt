package com.asawaaah.fil.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.ai.Languages
import com.asawaaah.fil.ai.ModelCatalog
import com.asawaaah.fil.ai.SummaryProgress
import com.asawaaah.fil.ai.TranslationUnavailableException
import com.asawaaah.fil.data.ContentSource
import com.asawaaah.fil.data.db.ArticleEntity
import com.asawaaah.fil.data.db.FeedEntity
import com.asawaaah.fil.data.extract.Block
import com.asawaaah.fil.data.extract.HtmlBlocks
import com.asawaaah.fil.data.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface SummaryUi {
    data object Hidden : SummaryUi
    data object NeedsModel : SummaryUi
    data class Working(val stage: String, val partial: String?) : SummaryUi
    data class Done(val text: String, val modelName: String, val detail: String? = null) : SummaryUi
    data class Error(val message: String) : SummaryUi
}

sealed interface TranslationUi {
    /** Article is already in French, or its language can't be translated. */
    data object NotNeeded : TranslationUi
    data object Original : TranslationUi
    data class Working(val progress: Float) : TranslationUi

    /** [byAi]: translated by the on-device model rather than ML Kit. */
    data class Translated(val byAi: Boolean = false) : TranslationUi

    /** The on-device model is improving the translation, [done] chunks out of [total]. */
    data class AiWorking(val done: Int, val total: Int) : TranslationUi
    data class Unavailable(val message: String, val canForce: Boolean) : TranslationUi
}

data class ReaderState(
    val article: ArticleEntity? = null,
    val feed: FeedEntity? = null,
    val loadingContent: Boolean = true,
    val source: ContentSource = ContentSource.NONE,
    val blocks: List<Block> = emptyList(),
    val translatedBlocks: List<Block>? = null,
    val translatedTitle: String? = null,
    val translation: TranslationUi = TranslationUi.NotNeeded,
    val translatedByAi: Boolean = false,
    val aiError: String? = null,
    val summary: SummaryUi = SummaryUi.Hidden,
) {
    val showTranslated: Boolean
        get() = (translation is TranslationUi.Translated || translation is TranslationUi.AiWorking) && translatedBlocks != null
    val visibleBlocks: List<Block> get() = if (showTranslated) translatedBlocks!! else blocks
    val languageFrom: String get() = Languages.fromPhrase(article?.lang)
}

class ReaderViewModel(private val c: AppContainer, private val articleId: Long) : ViewModel() {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val modelInstalled: StateFlow<Boolean> = combine(c.modelManager.installed, c.settings.settings) { installed, s ->
        ModelCatalog.byId(s.selectedModelId).id in installed
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private var sourceHash = 0
    private var summaryJob: Job? = null
    private var translationJob: Job? = null

    init {
        load()
    }

    private fun load() = viewModelScope.launch {
        // Load the model while the article is being read: the summary then starts at once.
        c.llm.warmUpSoon()
        val stored = c.db.articleDao().getById(articleId) ?: return@launch
        val article = stored.copy(lang = c.translationService.detectAndStore(stored))
        val feed = c.db.feedDao().getById(article.feedId)
        _state.update { it.copy(article = article, feed = feed) }
        if (!article.isRead) c.articleRepository.setRead(article.id, true)

        // Show any cached summary right away.
        c.articleRepository.content(article.id)?.summary?.let { cached ->
            val name = ModelCatalog.byId(c.articleRepository.content(article.id)?.summaryModel).name
            _state.update { it.copy(summary = SummaryUi.Done(cached, name)) }
        }

        val content = c.articleRepository.readerContent(article, feed, allowNetwork = true)
        val blocks = withContext(Dispatchers.Default) {
            dropRepeatedTitle(HtmlBlocks.parse(content.html, content.baseUrl), article.title)
        }
        sourceHash = (content.html ?: "").hashCode()
        val translatable = c.translationService.translator.canTranslate(article.lang)
        _state.update {
            it.copy(
                loadingContent = false,
                source = content.source,
                blocks = blocks,
                translatedTitle = article.titleFr,
                translation = if (translatable) TranslationUi.Original else TranslationUi.NotNeeded,
            )
        }
        if (translatable && settings.value.autoTranslate) translate(allowCellular = false)
    }

    fun translate(allowCellular: Boolean) {
        val article = _state.value.article ?: return
        val lang = article.lang ?: return
        if (_state.value.translatedBlocks != null) {
            _state.update { it.copy(translation = TranslationUi.Translated(it.translatedByAi)) }
            return
        }
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            _state.update { it.copy(translation = TranslationUi.Working(0f)) }
            try {
                val translated = c.translationService.translateBlocks(
                    articleId = article.id,
                    lang = lang,
                    blocks = _state.value.blocks,
                    sourceHash = sourceHash,
                    allowCellular = allowCellular,
                ) { p -> _state.update { s -> if (s.translation is TranslationUi.Working) s.copy(translation = TranslationUi.Working(p)) else s } }
                val title = _state.value.translatedTitle ?: c.translationService.translateText(article.title, lang)
                _state.update { it.copy(translatedBlocks = translated, translatedTitle = title, translation = TranslationUi.Translated()) }
                if (settings.value.aiTranslation && modelInstalled.value) translateWithAi()
            } catch (e: CancellationException) {
                throw e
            } catch (e: TranslationUnavailableException) {
                val msg = if (e.needsCellularConsent) {
                    "Le pack ${Languages.name(e.language)} (~30 Mo) sera téléchargé en Wi-Fi."
                } else {
                    "Pack ${Languages.name(e.language)} indisponible hors connexion."
                }
                _state.update { it.copy(translation = TranslationUi.Unavailable(msg, canForce = e.needsCellularConsent)) }
            } catch (e: Exception) {
                _state.update { it.copy(translation = TranslationUi.Unavailable("La traduction a échoué.", canForce = false)) }
            }
        }
    }

    fun showOriginal() {
        translationJob?.cancel()
        _state.update { it.copy(translation = TranslationUi.Original) }
    }

    /** Improves the translation with the on-device model, chunk by chunk, starting from what is shown. */
    fun translateWithAi() {
        val s = _state.value
        val article = s.article ?: return
        val lang = article.lang ?: return
        if (!modelInstalled.value) {
            _state.update { it.copy(aiError = "Installe d'abord le modèle d'IA dans les réglages.") }
            return
        }
        translationJob?.cancel()
        translationJob = viewModelScope.launch {
            val base = s.translatedBlocks ?: s.blocks
            _state.update { it.copy(aiError = null, translatedBlocks = base, translation = TranslationUi.AiWorking(0, 1)) }
            try {
                val final = c.translationService.translateWithAi(
                    articleId = article.id,
                    lang = lang,
                    title = article.title,
                    blocks = s.blocks,
                    sourceHash = sourceHash,
                    base = base,
                ) { p ->
                    _state.update {
                        it.copy(
                            translatedBlocks = p.blocks,
                            translatedTitle = p.title ?: it.translatedTitle,
                            translation = TranslationUi.AiWorking(p.done, p.total),
                        )
                    }
                }
                _state.update {
                    it.copy(
                        translatedBlocks = final.blocks,
                        translatedTitle = final.title ?: it.translatedTitle,
                        translatedByAi = true,
                        translation = TranslationUi.Translated(byAi = true),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        aiError = "La traduction par l'IA a échoué : ${e.message ?: e.javaClass.simpleName}",
                        translation = TranslationUi.Translated(it.translatedByAi),
                    )
                }
            }
        }
    }

    fun summarize(force: Boolean = false) {
        if (!modelInstalled.value) {
            _state.update { it.copy(summary = SummaryUi.NeedsModel) }
            return
        }
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            _state.update { it.copy(summary = SummaryUi.Working("Préparation…", null)) }
            c.summaryService.summarize(articleId, force).collect { p ->
                val ui = when (p) {
                    is SummaryProgress.Working -> SummaryUi.Working(p.stage, p.partial)
                    is SummaryProgress.Done -> SummaryUi.Done(p.text, p.modelName, p.detail)
                    is SummaryProgress.Failed -> SummaryUi.Error(p.message)
                }
                _state.update { it.copy(summary = ui) }
            }
        }
    }

    fun hideSummary() {
        summaryJob?.cancel()
        _state.update { it.copy(summary = SummaryUi.Hidden) }
    }

    fun toggleSaved() {
        val a = _state.value.article ?: return
        viewModelScope.launch {
            c.articleRepository.setSaved(a.id, !a.isSaved)
            _state.update { it.copy(article = a.copy(isSaved = !a.isSaved)) }
        }
    }

    fun setTextScale(scale: Float) {
        viewModelScope.launch { c.settings.setTextScale(scale.coerceIn(0.8f, 1.6f)) }
    }

    fun setSerif(serif: Boolean) {
        viewModelScope.launch { c.settings.setSerif(serif) }
    }

    companion object {
        /** Extracted pages often start with the article title, already shown in the header. */
        fun dropRepeatedTitle(blocks: List<Block>, title: String): List<Block> {
            fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
            val first = blocks.take(3).indexOfFirst { it is Block.Heading || it is Block.Paragraph }
            if (first < 0) return blocks
            val text = blocks[first].prose ?: return blocks
            val same = key(text).isNotEmpty() && (key(text) == key(title) || key(title).startsWith(key(text)))
            return if (same) blocks.filterIndexed { i, _ -> i != first } else blocks
        }
    }
}
