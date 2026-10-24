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
    /**
     * [text] is in [language]; [french] is its translation when there is one, shown when [showFrench].
     * [translating]: the French translation is being made.
     */
    data class Done(
        val text: String,
        val modelName: String,
        val detail: String? = null,
        val language: String = "fr",
        val french: String? = null,
        val showFrench: Boolean = false,
        val translating: Boolean = false,
        val translationFailed: Boolean = false,
    ) : SummaryUi {
        val shown: String get() = if (showFrench && french != null) french else text
    }
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
    /** Owners of the source, when it is a known French media. */
    val ownership: com.asawaaah.fil.data.MediaOwnership? = null,
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

    /** Reading aloud (shared by the whole app: it may be another article's). */
    val readAloud: StateFlow<com.asawaaah.fil.ai.ReadAloudState> = c.readAloud.state

    /** Reads the article as shown (translated or original) with the phone's voice. */
    fun listen(fromBlock: Int? = null) {
        val s = _state.value
        val article = s.article ?: return
        val title = if (s.showTranslated && s.translatedTitle != null) s.translatedTitle else article.title
        val segments = com.asawaaah.fil.ai.ReadAloudScript.build(title, s.visibleBlocks)
        if (segments.isEmpty()) return
        val lang = if (s.showTranslated) "fr" else article.lang?.takeIf { it != "und" } ?: "fr"
        val start = fromBlock?.let { b -> segments.indexOfFirst { it.block >= b }.takeIf { it >= 0 } } ?: 0
        c.readAloud.start(
            com.asawaaah.fil.ai.ReadAloudContent(
                articleId = article.id,
                title = title,
                source = s.feed?.title.orEmpty(),
                imageUrl = article.imageUrl,
                language = lang,
                segments = segments,
            ),
            start,
        )
    }

    fun setTopicHidden(topic: String, hidden: Boolean) {
        viewModelScope.launch { c.settings.setTopicHidden(topic, hidden) }
    }

    fun toggleReading() = c.readAloud.toggle()
    fun nextSegment() = c.readAloud.next()
    fun previousSegment() = c.readAloud.previous()
    fun stopReading() = c.readAloud.stop()
    fun setReadingRate(rate: Float) {
        c.readAloud.setRate(rate)
        viewModelScope.launch { c.settings.setReadAloudRate(rate) }
    }

    private fun load() = viewModelScope.launch {
        // Load the model while the article is being read: the summary then starts at once.
        c.llm.warmUpSoon()
        // Same for the voice, and the speed chosen last time.
        c.readAloud.warmUp()
        if (c.readAloud.state.value.articleId == null) c.readAloud.setRate(c.settings.current().readAloudRate)
        val stored = c.db.articleDao().getById(articleId) ?: return@launch
        val article = stored.copy(lang = c.translationService.detectAndStore(stored))
        val feed = c.db.feedDao().getById(article.feedId)
        val ownership = withContext(Dispatchers.Default) { c.mediaOwners.lookup(feed?.siteUrl, feed?.url, article.url) }
        _state.update { it.copy(article = article, feed = feed, ownership = ownership) }
        if (!article.isRead) c.articleRepository.setRead(article.id, true)

        // Show any cached summary right away.
        c.summaryService.cached(article.id)?.let { cached ->
            _state.update { it.copy(summary = cached.toUi()) }
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
                    is SummaryProgress.Done -> p.toUi()
                    is SummaryProgress.Failed -> SummaryUi.Error(p.message)
                }
                _state.update { it.copy(summary = ui) }
            }
        }
    }

    /** French in the summary card when there is a translation (made automatically or asked for). */
    private fun SummaryProgress.Done.toUi() = SummaryUi.Done(
        text = text,
        modelName = modelName,
        detail = detail,
        language = language,
        french = french,
        showFrench = french != null,
    )

    /** Translates a summary written in the article's language into French (ML Kit, about a second). */
    fun translateSummary() {
        val done = _state.value.summary as? SummaryUi.Done ?: return
        if (done.french != null) {
            _state.update { it.copy(summary = done.copy(showFrench = true)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(summary = done.copy(translating = true, translationFailed = false)) }
            val french = c.summaryService.translateSummary(articleId)
            _state.update {
                val current = it.summary as? SummaryUi.Done ?: return@update it
                it.copy(
                    summary = current.copy(
                        french = french,
                        showFrench = french != null,
                        translating = false,
                        translationFailed = french == null,
                    ),
                )
            }
        }
    }

    fun showSummaryOriginal() {
        val done = _state.value.summary as? SummaryUi.Done ?: return
        _state.update { it.copy(summary = done.copy(showFrench = false)) }
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
