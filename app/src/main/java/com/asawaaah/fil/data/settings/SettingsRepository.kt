package com.asawaaah.fil.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Look of the floating bars: see-through "liquid glass", or opaque for more readability. */
enum class GlassMode { LIQUID, OPAQUE }

data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val glass: GlassMode = GlassMode.LIQUID,
    val textScale: Float = 1.0f,
    val serif: Boolean = true,
    /** 0 = manual refresh only. */
    val refreshHours: Int = 1,
    val keepDays: Int = 30,
    val unreadOnly: Boolean = false,
    /** Articles of a source published the same day are shown as one stack (from 3 articles). */
    val groupBySource: Boolean = true,
    /** Compact cards in the feed: title and a small picture, no excerpt. */
    val compactFeed: Boolean = false,
    /** Speed of the voice reading articles aloud. */
    val readAloudRate: Float = 1f,
    /** Topics left out of the feed (see Topics), e.g. "sport.football". */
    val hiddenTopics: Set<String> = emptySet(),
    val autoTranslate: Boolean = true,
    val translateWifiOnly: Boolean = true,
    /** Translate opened articles with the on-device model (slower, better) instead of ML Kit only. */
    val aiTranslation: Boolean = false,
    val selectedModelId: String = "gemma4-e2b",
    val prepareSummaries: Boolean = true,
    /** GPU init can be very slow or unsupported on mid-range phones: CPU is the safe default. */
    val useGpu: Boolean = false,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val theme = stringPreferencesKey("theme")
        val glass = stringPreferencesKey("glass")
        val textScale = floatPreferencesKey("text_scale")
        val serif = booleanPreferencesKey("serif")
        val refreshHours = intPreferencesKey("refresh_hours")
        val keepDays = intPreferencesKey("keep_days")
        val unreadOnly = booleanPreferencesKey("unread_only")
        val groupBySource = booleanPreferencesKey("group_by_source")
        val compactFeed = booleanPreferencesKey("compact_feed")
        val readAloudRate = floatPreferencesKey("read_aloud_rate")
        val hiddenTopics = androidx.datastore.preferences.core.stringSetPreferencesKey("hidden_topics")
        val autoTranslate = booleanPreferencesKey("auto_translate")
        val translateWifiOnly = booleanPreferencesKey("translate_wifi_only")
        val aiTranslation = booleanPreferencesKey("ai_translation")
        val selectedModel = stringPreferencesKey("selected_model")
        val prepareSummaries = booleanPreferencesKey("prepare_summaries")
        val useGpu = booleanPreferencesKey("use_gpu_v2")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): Settings = settings.first()

    private fun Preferences.toSettings(): Settings {
        val d = Settings()
        return Settings(
            theme = this[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            glass = this[Keys.glass]?.let { runCatching { GlassMode.valueOf(it) }.getOrNull() } ?: d.glass,
            textScale = this[Keys.textScale] ?: d.textScale,
            serif = this[Keys.serif] ?: d.serif,
            refreshHours = this[Keys.refreshHours] ?: d.refreshHours,
            keepDays = this[Keys.keepDays] ?: d.keepDays,
            unreadOnly = this[Keys.unreadOnly] ?: d.unreadOnly,
            groupBySource = this[Keys.groupBySource] ?: d.groupBySource,
            compactFeed = this[Keys.compactFeed] ?: d.compactFeed,
            readAloudRate = this[Keys.readAloudRate] ?: d.readAloudRate,
            hiddenTopics = this[Keys.hiddenTopics] ?: d.hiddenTopics,
            autoTranslate = this[Keys.autoTranslate] ?: d.autoTranslate,
            translateWifiOnly = this[Keys.translateWifiOnly] ?: d.translateWifiOnly,
            aiTranslation = this[Keys.aiTranslation] ?: d.aiTranslation,
            selectedModelId = this[Keys.selectedModel] ?: d.selectedModelId,
            prepareSummaries = this[Keys.prepareSummaries] ?: d.prepareSummaries,
            useGpu = this[Keys.useGpu] ?: d.useGpu,
        )
    }

    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[Keys.theme] = v.name }
    suspend fun setGlass(v: GlassMode) = context.dataStore.edit { it[Keys.glass] = v.name }
    suspend fun setTextScale(v: Float) = context.dataStore.edit { it[Keys.textScale] = v }
    suspend fun setSerif(v: Boolean) = context.dataStore.edit { it[Keys.serif] = v }
    suspend fun setRefreshHours(v: Int) = context.dataStore.edit { it[Keys.refreshHours] = v }
    suspend fun setKeepDays(v: Int) = context.dataStore.edit { it[Keys.keepDays] = v }
    suspend fun setUnreadOnly(v: Boolean) = context.dataStore.edit { it[Keys.unreadOnly] = v }
    suspend fun setGroupBySource(v: Boolean) = context.dataStore.edit { it[Keys.groupBySource] = v }
    suspend fun setCompactFeed(v: Boolean) = context.dataStore.edit { it[Keys.compactFeed] = v }
    suspend fun setReadAloudRate(v: Float) = context.dataStore.edit { it[Keys.readAloudRate] = v }
    suspend fun setTopicHidden(topic: String, hidden: Boolean) = context.dataStore.edit {
        val current = it[Keys.hiddenTopics].orEmpty()
        it[Keys.hiddenTopics] = if (hidden) current + topic else current - topic
    }
    suspend fun setAutoTranslate(v: Boolean) = context.dataStore.edit { it[Keys.autoTranslate] = v }
    suspend fun setTranslateWifiOnly(v: Boolean) = context.dataStore.edit { it[Keys.translateWifiOnly] = v }
    suspend fun setAiTranslation(v: Boolean) = context.dataStore.edit { it[Keys.aiTranslation] = v }
    suspend fun setSelectedModel(v: String) = context.dataStore.edit { it[Keys.selectedModel] = v }
    suspend fun setPrepareSummaries(v: Boolean) = context.dataStore.edit { it[Keys.prepareSummaries] = v }
    suspend fun setUseGpu(v: Boolean) = context.dataStore.edit { it[Keys.useGpu] = v }
}
