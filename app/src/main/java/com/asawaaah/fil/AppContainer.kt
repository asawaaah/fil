package com.asawaaah.fil

import android.content.Context
import com.asawaaah.fil.ai.AiTranslator
import com.asawaaah.fil.ai.Connectivity
import com.asawaaah.fil.ai.LanguageDetector
import com.asawaaah.fil.ai.LlmEngine
import com.asawaaah.fil.ai.MlKitTranslator
import com.asawaaah.fil.ai.ModelManager
import com.asawaaah.fil.ai.Summarizer
import com.asawaaah.fil.ai.SummaryService
import com.asawaaah.fil.ai.TranslationService
import com.asawaaah.fil.data.ArticleRepository
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.feeds.FeedDiscovery
import com.asawaaah.fil.data.feeds.FeedRepository
import com.asawaaah.fil.data.feeds.ImportManager
import com.asawaaah.fil.data.net.Http
import com.asawaaah.fil.data.settings.SettingsRepository
import com.asawaaah.fil.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Something the app was asked to do from outside (share sheet, "open with"). */
sealed interface PendingAction {
    data object OpenImport : PendingAction
    data class AddSource(val url: String) : PendingAction
}

/** Hand-made dependency container: one instance per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val db = FilDatabase.build(appContext)
    val settings = SettingsRepository(appContext)
    val http = Http()
    val workScheduler = WorkScheduler(appContext)

    val feedRepository = FeedRepository(db, http, FeedDiscovery(http), settings)
    val articleRepository = ArticleRepository(db, http)

    val modelManager = ModelManager(appContext)
    val llm = LlmEngine(appContext, appScope, modelManager, settings)
    val connectivity = Connectivity(appContext)
    val translationService = TranslationService(
        db, LanguageDetector(), MlKitTranslator(connectivity), settings, connectivity, AiTranslator(llm),
    )
    val summaryService = SummaryService(db, articleRepository, Summarizer(llm), settings, translationService)

    val importManager = ImportManager(db, feedRepository, appScope) {
        appScope.launch {
            translationService.processPending()
            workScheduler.enqueuePrepareSummaries()
        }
    }

    val pendingAction = MutableStateFlow<PendingAction?>(null)

    fun start() {
        appScope.launch {
            settings.settings
                .map { it.refreshHours to it.prepareSummaries }
                .distinctUntilChanged()
                .collect { workScheduler.apply(settings.current()) }
        }
    }

    /** Translates titles of fresh articles in the background. */
    fun translateInBackground() {
        appScope.launch { runCatching { translationService.processPending() } }
    }
}
