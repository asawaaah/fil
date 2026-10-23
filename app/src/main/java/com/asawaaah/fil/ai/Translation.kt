package com.asawaaah.fil.ai

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.asawaaah.fil.data.db.ArticleContentEntity
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.extract.Block
import com.asawaaah.fil.data.settings.SettingsRepository
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class Connectivity(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    fun isMetered(): Boolean = cm.isActiveNetworkMetered
}

/** On-device language identification (ML Kit). */
class LanguageDetector {
    private val client = LanguageIdentification.getClient(
        LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.5f).build(),
    )

    suspend fun detect(text: String): String? {
        if (text.isBlank()) return null
        val code = client.identifyLanguage(text.take(1000)).await()
        return code.takeIf { it != "und" }
    }
}

class TranslationUnavailableException(val language: String, val needsCellularConsent: Boolean) :
    Exception("Pack de langue indisponible")

/** On-device translation to French (ML Kit). Language packs are ~30 MB each, downloaded on demand. */
class MlKitTranslator(private val connectivity: Connectivity) {
    private val clients = ConcurrentHashMap<String, Translator>()
    private val models = RemoteModelManager.getInstance()

    /** ML Kit code for a detected or declared language, or null if ML Kit cannot translate it. */
    fun codeFor(lang: String?): String? {
        if (lang.isNullOrBlank() || lang == "und") return null
        val base = lang.lowercase().replace('_', '-').substringBefore('-')
        if (base == "fr") return null
        return TranslateLanguage.fromLanguageTag(base)
    }

    fun canTranslate(lang: String?): Boolean = codeFor(lang) != null

    private fun client(code: String): Translator = clients.getOrPut(code) {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(code).setTargetLanguage(TranslateLanguage.FRENCH).build(),
        )
    }

    suspend fun isReady(code: String): Boolean =
        models.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await() &&
            models.isModelDownloaded(TranslateRemoteModel.Builder(TranslateLanguage.FRENCH).build()).await()

    /** Makes sure the pack is installed. False when it can't be downloaded right now. */
    suspend fun ensureModel(code: String, wifiOnly: Boolean): Boolean {
        if (isReady(code)) return true
        if (!connectivity.isOnline()) return false
        if (wifiOnly && connectivity.isMetered()) return false
        return try {
            val conditions = DownloadConditions.Builder().build()
            withTimeout(5 * 60_000L) { client(code).downloadModelIfNeeded(conditions).await() }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    suspend fun translate(text: String, code: String): String =
        if (text.isBlank()) text else client(code).translate(text).await()

    suspend fun downloadedLanguages(): List<String> =
        models.getDownloadedModels(TranslateRemoteModel::class.java).await()
            .map { it.language }
            .filter { it != TranslateLanguage.FRENCH }
            .sorted()

    suspend fun deleteLanguage(code: String) {
        clients.remove(code)?.close()
        models.deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
    }
}

/** Progress of an article translation by the on-device model. */
class AiTranslationProgress(val blocks: List<Block>, val title: String?, val done: Int, val total: Int)

/** Detects languages and translates titles, excerpts and articles into French. */
class TranslationService(
    private val db: FilDatabase,
    private val detector: LanguageDetector,
    val translator: MlKitTranslator,
    private val settings: SettingsRepository,
    private val connectivity: Connectivity,
    private val ai: AiTranslator,
) {
    /**
     * Translates an article with the on-device model, chunk by chunk, starting from [base]
     * (the quick ML Kit translation, or the original). Cached per article.
     * Returns the final progress (all chunks done).
     */
    suspend fun translateWithAi(
        articleId: Long,
        lang: String,
        title: String,
        blocks: List<Block>,
        sourceHash: Int,
        base: List<Block>,
        onProgress: (AiTranslationProgress) -> Unit,
    ): AiTranslationProgress {
        val contentDao = db.contentDao()
        fun merge(aiTexts: List<String>): List<Block> = blocks.mapIndexed { i, b ->
            val t = aiTexts.getOrNull(i)
            if (b.prose != null && !t.isNullOrEmpty()) b.withProse(t) else base.getOrElse(i) { b }
        }
        contentDao.get(articleId)?.let { c ->
            if (c.aiTranslatedSourceHash == sourceHash && c.aiTranslatedTexts != null) {
                val parts = c.aiTranslatedTexts.split(ArticleContentEntity.TRANSLATION_SEPARATOR)
                if (parts.size == blocks.size + 1) {
                    return AiTranslationProgress(merge(parts.drop(1)), parts[0].ifEmpty { null }, 1, 1)
                }
            }
        }
        val texts = listOf(title) + blocks.map { it.prose.orEmpty() }
        val total = AiTranslator.chunks(texts).size
        val results = HashMap<Int, String>()
        var done = 0
        var last = AiTranslationProgress(base, null, 0, total)
        onProgress(last)
        ai.translate(texts, Languages.name(lang)) { part ->
            results += part
            done++
            last = AiTranslationProgress(merge((1..blocks.size).map { results[it].orEmpty() }), results[0], done, total)
            onProgress(last)
        }
        val current = contentDao.get(articleId) ?: ArticleContentEntity(articleId = articleId)
        contentDao.upsert(
            current.copy(
                aiTranslatedTexts = texts.indices.joinToString(ArticleContentEntity.TRANSLATION_SEPARATOR) { results[it].orEmpty() },
                aiTranslatedSourceHash = sourceHash,
            ),
        )
        return last
    }

    private val mutex = Mutex()
    private val articleDao = db.articleDao()

    /** Detects the language of new articles and, if enabled, translates their title and excerpt. */
    suspend fun processPending(limit: Int = 400): Int = mutex.withLock {
        val s = settings.current()
        val feedLanguages = db.feedDao().getAll().associate { it.id to it.language }
        val unavailable = HashSet<String>()
        var translated = 0

        val articles = if (s.autoTranslate) articleDao.pendingTranslation(limit) else articleDao.pendingDetection(limit)
        for (a in articles) {
            currentCoroutineContext().ensureActive()
            var lang = a.lang
            if (lang == null) {
                val sample = listOfNotNull(a.title, a.excerpt).joinToString(". ")
                lang = runCatching { detector.detect(sample) }.getOrNull()
                    ?: feedLanguages[a.feedId]?.lowercase()?.substringBefore('-')
                    ?: "und"
                lang = lang.lowercase().substringBefore('-')
                // A language ML Kit can't translate is treated like an unknown one.
                if (lang != "fr" && !translator.canTranslate(lang)) lang = "und"
                articleDao.setLang(a.id, lang)
            }
            if (!s.autoTranslate) continue
            val code = translator.codeFor(lang) ?: continue
            if (code in unavailable) continue
            if (!translator.ensureModel(code, s.translateWifiOnly)) {
                unavailable += code
                continue
            }
            try {
                val titleFr = translator.translate(a.title, code)
                val excerptFr = a.excerpt?.let { translator.translate(it, code) }
                articleDao.setTranslation(a.id, lang, titleFr, excerptFr)
                translated++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unavailable += code
            }
        }
        translated
    }

    /**
     * Translates reader blocks, using the cached translation when it matches [sourceHash].
     * Throws [TranslationUnavailableException] when the language pack can't be downloaded now.
     */
    suspend fun translateBlocks(
        articleId: Long,
        lang: String,
        blocks: List<Block>,
        sourceHash: Int,
        allowCellular: Boolean,
        onProgress: (Float) -> Unit,
    ): List<Block> {
        val contentDao = db.contentDao()
        val cached = contentDao.get(articleId)
        if (cached?.translatedSourceHash == sourceHash && cached.translatedTexts != null) {
            val texts = cached.translatedTexts.split(ArticleContentEntity.TRANSLATION_SEPARATOR)
            if (texts.size == blocks.size) {
                return blocks.mapIndexed { i, b -> if (b.prose != null && texts[i].isNotEmpty()) b.withProse(texts[i]) else b }
            }
        }
        val code = translator.codeFor(lang) ?: return blocks
        val wifiOnly = settings.current().translateWifiOnly && !allowCellular
        if (!translator.ensureModel(code, wifiOnly)) {
            throw TranslationUnavailableException(code, needsCellularConsent = connectivity.isOnline() && connectivity.isMetered())
        }
        val texts = ArrayList<String>(blocks.size)
        blocks.forEachIndexed { i, b ->
            val prose = b.prose
            texts += if (prose.isNullOrBlank()) "" else translator.translate(prose, code)
            onProgress((i + 1f) / blocks.size)
        }
        val current = contentDao.get(articleId) ?: ArticleContentEntity(articleId = articleId)
        contentDao.upsert(
            current.copy(
                translatedTexts = texts.joinToString(ArticleContentEntity.TRANSLATION_SEPARATOR),
                translatedSourceHash = sourceHash,
            ),
        )
        return blocks.mapIndexed { i, b -> if (b.prose != null && texts[i].isNotEmpty()) b.withProse(texts[i]) else b }
    }

    /** Language of an article, detected now if the background pass hasn't reached it yet. */
    suspend fun detectAndStore(article: com.asawaaah.fil.data.db.ArticleEntity): String {
        article.lang?.let { return it }
        val sample = listOfNotNull(article.title, article.excerpt).joinToString(". ")
        var lang = runCatching { detector.detect(sample) }.getOrNull()
            ?: db.feedDao().getById(article.feedId)?.language
            ?: "und"
        lang = lang.lowercase().substringBefore('-')
        if (lang != "fr" && !translator.canTranslate(lang)) lang = "und"
        articleDao.setLang(article.id, lang)
        return lang
    }

    suspend fun translateText(text: String, lang: String): String? {
        val code = translator.codeFor(lang) ?: return null
        if (!translator.isReady(code)) return null
        return runCatching { translator.translate(text, code) }.getOrNull()
    }
}

object Languages {
    /** "anglais", "espagnol"... */
    fun name(code: String?): String {
        if (code.isNullOrBlank() || code == "und") return "langue inconnue"
        val name = Locale.forLanguageTag(code).getDisplayLanguage(Locale.FRENCH)
        return name.ifBlank { code }
    }

    /** "de l'anglais", "du japonais". */
    fun fromPhrase(code: String?): String {
        val n = name(code)
        val first = n.firstOrNull()?.lowercaseChar() ?: return n
        val elision = first in "aeiouyéèêàâîïôûh" && !n.startsWith("hindi") && !n.startsWith("hongrois") && !n.startsWith("haoussa")
        return if (elision) "de l'$n" else "du $n"
    }
}
