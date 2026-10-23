package com.asawaaah.fil.ai

import android.content.Context
import android.util.Log
import com.asawaaah.fil.data.settings.SettingsRepository
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ModelNotInstalledException(val model: LlmModel) : Exception("Le modèle ${model.name} n'est pas installé")

/** Where the model runs. */
enum class Accelerator(val label: String) {
    CPU("processeur"),
    GPU("puce graphique"),
    NPU("puce IA Tensor"),
}

/**
 * Holds the single LiteRT-LM engine. Loads it on first use (NPU or GPU when asked, CPU otherwise),
 * keeps it 5 minutes after the last use, and frees it when the app goes to the background.
 * An accelerator that crashes the app or takes too long to start is disabled for the next loads.
 */
class LlmEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    private val models: ModelManager,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var loadedModel: LlmModel? = null
    private var releaseJob: Job? = null
    private val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)

    @Volatile
    var accelerator: Accelerator? = null
        private set

    /** Kept for the settings screen. */
    val backendName: String? get() = accelerator?.label

    /** Time the last engine load took. */
    @Volatile
    var lastLoadMs: Long = 0
        private set

    val gpuCrashed: Boolean get() = failed(GPU)
    val npuFailed: Boolean get() = failed(NPU)

    init {
        // An accelerator start that never finished means the process died during it.
        for (accel in listOf(GPU, NPU)) {
            if (prefs.getBoolean(startingKey(accel), false)) {
                prefs.edit().putBoolean(startingKey(accel), false).putBoolean(failedKey(accel), true).commit()
            }
        }
    }

    fun resetGpuCrash() = prefs.edit().putBoolean(failedKey(GPU), false).apply()
    fun resetNpuFailure() = prefs.edit().putBoolean(failedKey(NPU), false).apply()

    /**
     * Runs [block] with the loaded engine. The model given to [block] is the one really used:
     * the fallback model when the NPU one couldn't start.
     */
    suspend fun <T> use(onLoading: suspend () -> Unit, block: suspend (Engine, LlmModel) -> T): T = mutex.withLock {
        releaseJob?.cancel()
        val s = settings.current()
        val selected = ModelCatalog.byId(s.selectedModelId)
        if (!models.isInstalled(selected)) throw ModelNotInstalledException(selected)
        val wantNpu = selected.npuChip != null && !npuFailed
        val wantGpu = s.useGpu && !gpuCrashed
        val key = "${selected.id}|$wantNpu|$wantGpu"
        if (engine == null || loadedKey != key) {
            onLoading()
            closeEngine()
            val started = System.currentTimeMillis()
            val (e, model) = withContext(Dispatchers.IO) { createEngine(selected, wantNpu, wantGpu) }
            lastLoadMs = System.currentTimeMillis() - started
            engine = e
            loadedModel = model
            loadedKey = key
        }
        try {
            block(engine!!, loadedModel!!)
        } finally {
            scheduleRelease()
        }
    }

    /** Loads the model in the background so that the next summary starts right away. */
    fun warmUpSoon() {
        if (engine != null) {
            scheduleRelease()
            return
        }
        scope.launch {
            val model = ModelCatalog.byId(settings.current().selectedModelId)
            if (models.isInstalled(model)) runCatching { use(onLoading = {}) { _, _ -> } }
        }
    }

    private fun createEngine(selected: LlmModel, wantNpu: Boolean, wantGpu: Boolean): Pair<Engine, LlmModel> {
        if (selected.npuChip != null) {
            if (wantNpu) {
                val npu = tryAccelerated(NPU, NPU_INIT_LIMIT_MS) {
                    // NPU models are compiled for a fixed context: let the model decide its size.
                    Engine(
                        EngineConfig(
                            modelPath = models.file(selected).absolutePath,
                            backend = Backend.NPU(nativeLibraryDir = context.applicationInfo.nativeLibraryDir),
                            cacheDir = context.cacheDir.path,
                        ),
                    )
                }
                if (npu != null) return npu to selected
            }
            // The NPU model only runs on the NPU: switch to the regular model.
            val fallback = ModelCatalog.byId(selected.fallbackId)
            if (!models.isInstalled(fallback)) throw ModelNotInstalledException(fallback)
            return createEngine(fallback, wantNpu = false, wantGpu = wantGpu)
        }
        val path = models.file(selected).absolutePath
        if (wantGpu) {
            tryAccelerated(GPU, GPU_INIT_LIMIT_MS) { Engine(config(path, Backend.GPU())) }?.let { return it to selected }
        }
        val e = Engine(config(path, Backend.CPU()))
        e.initialize()
        accelerator = Accelerator.CPU
        return e to selected
    }

    /** Starts an engine on [accel]; null if it fails. A crash or a too slow start disables it for next time. */
    private fun tryAccelerated(accel: Accelerator, limitMs: Long, build: () -> Engine): Engine? {
        prefs.edit().putBoolean(startingKey(accel), true).commit()
        // Native init can't be interrupted: if it is too slow, at least never try it again.
        val watchdog = scope.launch {
            delay(limitMs)
            Log.w(TAG, "$accel init slower than ${limitMs / 1000}s, disabled for next loads")
            prefs.edit().putBoolean(failedKey(accel), true).apply()
        }
        return try {
            val e = build()
            e.initialize()
            accelerator = accel
            e
        } catch (t: Throwable) {
            Log.w(TAG, "$accel backend unavailable, falling back", t)
            prefs.edit().putBoolean(failedKey(accel), true).apply()
            null
        } finally {
            watchdog.cancel()
            prefs.edit().putBoolean(startingKey(accel), false).commit()
        }
    }

    private fun config(path: String, backend: Backend) = EngineConfig(
        modelPath = path,
        backend = backend,
        maxNumTokens = MAX_TOKENS,
        cacheDir = context.cacheDir.path,
    )

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(KEEP_LOADED_MS)
            mutex.withLock { closeEngine() }
        }
    }

    /** Frees the model memory as soon as the current generation (if any) ends. */
    fun releaseSoon() {
        releaseJob?.cancel()
        releaseJob = scope.launch { mutex.withLock { closeEngine() } }
    }

    private fun closeEngine() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        loadedModel = null
    }

    private fun failed(accel: Accelerator) = prefs.getBoolean(failedKey(accel), false)
    private fun startingKey(accel: Accelerator) = "${accel.name.lowercase()}_starting"
    private fun failedKey(accel: Accelerator) = if (accel == GPU) "gpu_crashed" else "${accel.name.lowercase()}_failed"

    companion object {
        private const val TAG = "LlmEngine"
        private val GPU = Accelerator.GPU
        private val NPU = Accelerator.NPU
        const val MAX_TOKENS = 4096
        private const val GPU_INIT_LIMIT_MS = 45_000L
        private const val NPU_INIT_LIMIT_MS = 60_000L
        private const val KEEP_LOADED_MS = 5 * 60_000L
    }
}
