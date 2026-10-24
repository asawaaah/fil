package com.asawaaah.fil.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.ai.DownloadState
import com.asawaaah.fil.ai.LlmModel
import com.asawaaah.fil.ai.ModelCatalog
import com.asawaaah.fil.data.db.KeywordFilterEntity
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.data.settings.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())
    val installedModels: StateFlow<Set<String>> = c.modelManager.installed
    val filters: StateFlow<List<KeywordFilterEntity>> =
        c.db.filterDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val languages = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    val gpuCrashed: Boolean get() = c.llm.gpuCrashed
    val npuFailed: Boolean get() = c.llm.npuFailed
    val backendName: String? get() = c.llm.backendName

    fun retryNpu() {
        c.llm.resetNpuFailure()
        c.llm.releaseSoon()
        message.value = "Le modèle Tensor G5 sera réessayé au prochain résumé"
    }

    init {
        refreshLanguages()
    }

    fun downloadState(model: LlmModel): Flow<DownloadState> = c.modelManager.downloadState(model)

    fun refreshLanguages() {
        viewModelScope.launch {
            languages.value = runCatching { c.translationService.translator.downloadedLanguages() }.getOrDefault(emptyList())
        }
    }

    fun deleteLanguage(code: String) {
        viewModelScope.launch {
            runCatching { c.translationService.translator.deleteLanguage(code) }
            refreshLanguages()
        }
    }

    fun download(model: LlmModel, allowMobileData: Boolean = false) = c.modelManager.startDownload(model, allowMobileData)

    /** Models imported by the user (.litertlm files). */
    val customModels: StateFlow<List<LlmModel>> = c.modelManager.installed
        .map { c.modelManager.customModels() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, c.modelManager.customModels())

    fun importCustomModel(context: Context, uri: Uri) {
        viewModelScope.launch {
            val name = runCatching {
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
            }.getOrNull()
            message.value = "Copie du modèle… (peut prendre une minute)"
            message.value = try {
                val model = c.modelManager.importCustom(uri, name)
                c.settings.setSelectedModel(model.id)
                "Modèle « ${model.name} » importé et sélectionné"
            } catch (e: Exception) {
                e.message ?: "Import impossible"
            }
        }
    }
    fun cancelDownload(model: LlmModel) = c.modelManager.cancelDownload(model)

    fun deleteModel(model: LlmModel) {
        c.llm.releaseSoon()
        c.modelManager.delete(model)
    }

    fun importModel(context: Context, uri: Uri, model: LlmModel) {
        viewModelScope.launch {
            message.value = "Copie du modèle…"
            message.value = runCatching { c.modelManager.importFrom(uri, model) }
                .fold({ "Modèle ${model.name} installé" }, { "Import impossible : ${it.message}" })
        }
    }

    fun selectModel(model: LlmModel) = edit { c.settings.setSelectedModel(model.id) }
    fun setTheme(v: ThemeMode) = edit { c.settings.setTheme(v) }
    fun setGlass(v: com.asawaaah.fil.data.settings.GlassMode) = edit { c.settings.setGlass(v) }

    /** Android's "high contrast text" accessibility option. */
    val highContrastText: Boolean get() = com.asawaaah.fil.ui.glass.Accessibility.highContrastText(c.appContext)
    fun setTextScale(v: Float) = edit { c.settings.setTextScale(v) }
    fun setSerif(v: Boolean) = edit { c.settings.setSerif(v) }
    fun setRefreshHours(v: Int) = edit { c.settings.setRefreshHours(v) }
    fun setKeepDays(v: Int) = edit { c.settings.setKeepDays(v) }
    fun setGroupBySource(v: Boolean) = edit { c.settings.setGroupBySource(v) }
    fun setCompactFeed(v: Boolean) = edit { c.settings.setCompactFeed(v) }
    fun setAutoTranslate(v: Boolean) = edit {
        c.settings.setAutoTranslate(v)
        if (v) c.translateInBackground()
    }
    fun setTranslateWifiOnly(v: Boolean) = edit { c.settings.setTranslateWifiOnly(v) }
    fun setAiTranslation(v: Boolean) = edit { c.settings.setAiTranslation(v) }
    fun setPrepareSummaries(v: Boolean) = edit { c.settings.setPrepareSummaries(v) }
    fun setUseGpu(v: Boolean) = edit {
        if (v) c.llm.resetGpuCrash()
        c.settings.setUseGpu(v)
        c.llm.releaseSoon()
    }

    fun addFilter(keyword: String) = edit {
        if (keyword.isNotBlank()) c.db.filterDao().insert(KeywordFilterEntity(keyword = keyword.trim()))
    }

    fun removeFilter(id: Long) = edit { c.db.filterDao().delete(id) }

    /** All sources, for the notifications page (followed ones first) and the subscriptions summary. */
    val feeds: StateFlow<List<com.asawaaah.fil.data.db.FeedWithCount>> =
        c.db.feedDao().observeWithCounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setNotify(feedId: Long, v: Boolean) = edit { c.db.feedDao().setNotify(feedId, v) }

    fun canNotify(): Boolean = com.asawaaah.fil.work.NewArticleNotifier(c.appContext).canPost()

    fun setReadAloudRate(v: Float) = edit {
        c.settings.setReadAloudRate(v)
        // Applies at once if nothing is being read (else on the next article).
        if (c.readAloud.state.value.articleId == null) c.readAloud.setRate(v)
    }

    fun exportOpml(context: Context, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val text = c.feedRepository.exportOpml()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Fichier inaccessible")
                }
            }.isSuccess
            message.value = if (ok) "Abonnements exportés" else "L'export a échoué"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    val models = ModelCatalog.forThisDevice()
}
