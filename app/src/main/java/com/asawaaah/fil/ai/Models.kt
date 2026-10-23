package com.asawaaah.fil.ai

import android.content.Context
import android.net.Uri
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

enum class ModelFamily { GEMMA, QWEN }

data class LlmModel(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val fileName: String,
    val approxBytes: Long,
    val family: ModelFamily,
    /** Compiled for one phone chip's NPU (e.g. "Tensor G5"); null for models that run anywhere. */
    val npuChip: String? = null,
    /** Model used instead when the NPU can't start. */
    val fallbackId: String? = null,
)

object ModelCatalog {
    val GEMMA4_E2B_TENSOR_G5 = LlmModel(
        id = "gemma4-e2b-tensor-g5",
        name = "Gemma 4 E2B · Tensor G5",
        description = "Expérimental : même modèle, compilé pour la puce de ton Pixel. Bien plus rapide si ça marche, " +
            "sinon Fil repasse sur le modèle normal.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        fileName = "gemma-4-E2B-it_Google_Tensor_G5.litertlm",
        approxBytes = 3_113_000_000L,
        family = ModelFamily.GEMMA,
        npuChip = "Tensor G5",
        fallbackId = "gemma4-e2b",
    )

    val GEMMA4_E2B = LlmModel(
        id = "gemma4-e2b",
        name = "Gemma 4 E2B",
        description = "Recommandé. Meilleure qualité de résumé en français.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        approxBytes = 2_588_000_000L,
        family = ModelFamily.GEMMA,
    )
    val QWEN3_1_7B = LlmModel(
        id = "qwen3-1.7b",
        name = "Qwen3 1.7B",
        description = "Plus léger et plus rapide, un peu moins bon en français.",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        fileName = "Qwen3-1.7B_dynamic_wi4b32_afp32.litertlm",
        approxBytes = 977_000_000L,
        family = ModelFamily.QWEN,
    )
    val GEMMA4_E2B_TENSOR_G6 = GEMMA4_E2B_TENSOR_G5.copy(
        id = "gemma4-e2b-tensor-g6",
        name = "Gemma 4 E2B · Tensor G6",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it_Google_Tensor_G6.litertlm",
        fileName = "gemma-4-E2B-it_Google_Tensor_G6.litertlm",
        approxBytes = 3_314_000_000L,
        npuChip = "Tensor G6",
    )

    /** The regular models run on every phone; chip-specific ones are extra, faster options. */
    val all = listOf(GEMMA4_E2B, QWEN3_1_7B, GEMMA4_E2B_TENSOR_G5, GEMMA4_E2B_TENSOR_G6)

    /** Ids of models imported by the user start with this. */
    const val CUSTOM_PREFIX = "custom:"

    /** A .litertlm file imported by the user, stored in models/custom/. */
    fun custom(fileName: String, bytes: Long = 0): LlmModel = LlmModel(
        id = CUSTOM_PREFIX + fileName,
        name = fileName.removeSuffix(".litertlm"),
        description = "Modèle importé.",
        url = "",
        fileName = "custom/$fileName",
        approxBytes = bytes,
        family = if (fileName.contains("qwen", ignoreCase = true)) ModelFamily.QWEN else ModelFamily.GEMMA,
    )

    fun byId(id: String?): LlmModel = when {
        id != null && id.startsWith(CUSTOM_PREFIX) -> custom(id.removePrefix(CUSTOM_PREFIX))
        else -> all.firstOrNull { it.id == id } ?: GEMMA4_E2B
    }

    /** Chip of this phone as Android reports it, e.g. "Tensor G5" (Pixel 10). */
    val deviceChip: String
        get() = runCatching { android.os.Build.SOC_MODEL }.getOrDefault("").replace('_', ' ').trim()

    /** Pixel models known to carry each chip, in case the chip name is reported differently. */
    private val pixelsByChip = mapOf(
        "Tensor G5" to Regex("""Pixel 10""", RegexOption.IGNORE_CASE),
        "Tensor G6" to Regex("""Pixel 11""", RegexOption.IGNORE_CASE),
    )

    /** Models usable on this phone: chip-specific ones only on the matching chip. */
    fun forThisDevice(): List<LlmModel> = all.filter { m ->
        val chip = m.npuChip ?: return@filter true
        deviceChip.equals(chip, ignoreCase = true) ||
            pixelsByChip[chip]?.containsMatchIn(android.os.Build.MODEL.orEmpty()) == true
    }
}

sealed interface DownloadState {
    data object None : DownloadState
    data object WaitingForWifi : DownloadState
    data class Running(val downloaded: Long, val total: Long) : DownloadState
    data class Failed(val message: String) : DownloadState
}

class UnsupportedModelFormatException(message: String) : Exception(message)

class ModelManager(private val context: Context) {
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val customDir = File(dir, "custom").apply { mkdirs() }
    private val _installed = MutableStateFlow(scanInstalled())
    val installed: StateFlow<Set<String>> = _installed.asStateFlow()

    fun file(model: LlmModel) = File(dir, model.fileName)
    fun partFile(model: LlmModel) = File(dir, model.fileName + ".part")

    fun isInstalled(model: LlmModel): Boolean = file(model).let { it.exists() && it.length() > 50_000_000L }

    /** Models imported by the user. */
    fun customModels(): List<LlmModel> = customDir.listFiles { f -> f.name.endsWith(".litertlm") }
        ?.sortedBy { it.name.lowercase() }
        ?.map { ModelCatalog.custom(it.name, it.length()) }
        .orEmpty()

    private fun scanInstalled(): Set<String> =
        (ModelCatalog.all + customModels()).filter { isInstalled(it) }.map { it.id }.toSet()

    fun refreshInstalled() {
        _installed.value = scanInstalled()
    }

    /** [allowMobileData]: also download over mobile data (asked explicitly by the user). */
    fun startDownload(model: LlmModel, allowMobileData: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            workName(model),
            // A user request replaces a pending Wi-Fi-only one; the partial file is resumed.
            if (allowMobileData) androidx.work.ExistingWorkPolicy.REPLACE else androidx.work.ExistingWorkPolicy.KEEP,
            ModelDownloadWorker.request(model, allowMobileData),
        )
    }

    /**
     * Copies a .litertlm model chosen by the user into the app. Other formats are refused with
     * an explanation (LiteRT-LM can't read GGUF or safetensors files).
     */
    suspend fun importCustom(uri: Uri, displayName: String?): LlmModel = withContext(Dispatchers.IO) {
        val name = (displayName ?: uri.lastPathSegment ?: "modele.litertlm").substringAfterLast('/')
        val lower = name.lowercase()
        when {
            lower.endsWith(".litertlm") -> Unit
            lower.endsWith(".gguf") -> throw UnsupportedModelFormatException(
                "Les fichiers GGUF (llama.cpp) ne sont pas lisibles par le moteur de Fil. Cherche la version " +
                    ".litertlm du modèle (par ex. sur huggingface.co/litert-community).",
            )
            lower.endsWith(".safetensors") || lower.endsWith(".bin") || lower.endsWith(".pt") -> throw UnsupportedModelFormatException(
                "Ce sont des poids bruts : il faut d'abord les convertir en .litertlm sur un ordinateur " +
                    "(outil litert_lm_builder de Google).",
            )
            lower.endsWith(".task") -> throw UnsupportedModelFormatException(
                "Les fichiers .task (MediaPipe) ne sont pas pris en charge : prends la version .litertlm du modèle.",
            )
            else -> throw UnsupportedModelFormatException("Format non reconnu : Fil lit les modèles .litertlm.")
        }
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(customDir, safe)
        val part = File(customDir, "$safe.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fichier illisible" }
            part.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        }
        if (part.length() < 50_000_000L) {
            part.delete()
            error("Ce fichier est trop petit pour être un modèle")
        }
        target.delete()
        check(part.renameTo(target)) { "Impossible d'enregistrer le modèle" }
        refreshInstalled()
        ModelCatalog.custom(safe, target.length())
    }

    fun cancelDownload(model: LlmModel) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(model))
    }

    fun downloadState(model: LlmModel): Flow<DownloadState> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(workName(model)).map { infos ->
            val info = infos.firstOrNull() ?: return@map DownloadState.None
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.WaitingForWifi
                WorkInfo.State.RUNNING -> DownloadState.Running(
                    info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED, partFile(model).length()),
                    info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, model.approxBytes),
                )
                WorkInfo.State.FAILED -> DownloadState.Failed(
                    info.outputData.getString(ModelDownloadWorker.KEY_ERROR) ?: "Téléchargement échoué",
                )
                else -> DownloadState.None
            }
        }

    fun delete(model: LlmModel) {
        file(model).delete()
        partFile(model).delete()
        refreshInstalled()
    }

    /** Copies a model file chosen by the user (for when the direct download isn't possible). */
    suspend fun importFrom(uri: Uri, model: LlmModel) = withContext(Dispatchers.IO) {
        val part = partFile(model)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fichier illisible" }
            part.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
        }
        if (part.length() < 50_000_000L) {
            part.delete()
            error("Ce fichier est trop petit pour être un modèle")
        }
        file(model).delete()
        check(part.renameTo(file(model))) { "Impossible d'enregistrer le modèle" }
        refreshInstalled()
    }

    private fun workName(model: LlmModel) = "model-download-${model.id}"
}
