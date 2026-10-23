package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.db.FeedEntity
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.net.ErrorMessages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

data class ImportFailure(val feedId: Long, val title: String, val url: String, val reason: String, val kind: FailureKind)

/** A source whose address was corrected during the import. */
data class ImportRepair(val feedId: Long, val title: String, val oldUrl: String, val newUrl: String, val otherSite: Boolean)

data class ImportReport(
    val added: Int,
    val duplicates: Int,
    val failures: List<ImportFailure>,
    val repairs: List<ImportRepair> = emptyList(),
) {
    fun failuresOf(kind: FailureKind) = failures.filter { it.kind == kind }
}

sealed interface ImportState {
    data object Idle : ImportState
    data object Reading : ImportState
    data class ParseError(val message: String) : ImportState
    data class Preview(
        val document: OpmlDocument,
        val sourceName: String?,
        /** Normalized URLs already followed in the app. */
        val alreadyFollowed: Set<String>,
    ) : ImportState

    data class Running(val total: Int, val done: Int, val added: Int, val failed: Int) : ImportState
    data class Finished(val report: ImportReport, val retrying: Boolean = false) : ImportState
}

/** Runs OPML imports in the application scope, so they continue when the screen is left. */
class ImportManager(
    private val db: FilDatabase,
    private val feeds: FeedRepository,
    private val scope: CoroutineScope,
    private val afterImport: () -> Unit,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()
    private var job: Job? = null

    val isBusy: Boolean get() = _state.value is ImportState.Running

    private class Batch(val failures: List<ImportFailure>, val repairs: List<ImportRepair>, val merged: Int)

    fun load(read: suspend () -> Pair<ByteArray, String?>) {
        if (isBusy) return
        _state.value = ImportState.Reading
        scope.launch {
            _state.value = try {
                val (bytes, name) = read()
                val doc = Opml.parse(bytes)
                ImportState.Preview(doc, name, db.feedDao().allNormalizedUrls().toHashSet())
            } catch (e: Exception) {
                ImportState.ParseError(ErrorMessages.describe(e))
            }
        }
    }

    fun start(selected: List<OpmlFeed>, duplicatesInFile: Int) {
        if (isBusy) return
        job = scope.launch {
            val feedDao = db.feedDao()
            val existing = feedDao.allNormalizedUrls().toHashSet()
            var duplicates = duplicatesInFile
            val created = ArrayList<FeedEntity>()
            for (f in selected) {
                val normalized = Urls.normalize(f.xmlUrl)
                if (!existing.add(normalized)) {
                    duplicates++
                    continue
                }
                val folderId = f.folder?.let { feeds.getOrCreateFolder(it) }
                val entity = FeedEntity(
                    url = f.xmlUrl,
                    normalizedUrl = normalized,
                    title = f.title,
                    siteUrl = f.htmlUrl,
                    iconUrl = Urls.faviconFor(f.htmlUrl ?: f.xmlUrl),
                    kind = if (RedditUrls.isReddit(f.xmlUrl)) FeedEntity.KIND_REDDIT else FeedEntity.KIND_RSS,
                    folderId = folderId,
                )
                val id = feedDao.insert(entity)
                if (id == -1L) duplicates++ else created += entity.copy(id = id)
            }
            val batch = fetchAll(created) { done, added, failed ->
                _state.value = ImportState.Running(created.size, done, added, failed)
            }
            _state.value = ImportState.Finished(
                ImportReport(
                    added = created.size - batch.failures.size - batch.merged,
                    duplicates = duplicates + batch.merged,
                    failures = batch.failures,
                    repairs = batch.repairs,
                ),
            )
            afterImport()
        }
    }

    fun retryFailed() {
        val finished = _state.value as? ImportState.Finished ?: return
        if (finished.retrying) return
        _state.value = finished.copy(retrying = true)
        job = scope.launch {
            val toRetry = finished.report.failures.mapNotNull { db.feedDao().getById(it.feedId) }
            val batch = fetchAll(toRetry) { _, _, _ -> }
            val recovered = toRetry.size - batch.failures.size - batch.merged
            _state.value = ImportState.Finished(
                finished.report.copy(
                    added = finished.report.added + recovered,
                    duplicates = finished.report.duplicates + batch.merged,
                    failures = batch.failures,
                    repairs = finished.report.repairs + batch.repairs,
                ),
            )
            if (recovered > 0) afterImport()
        }
    }

    /** Removes the failed feeds of one kind (all kinds when null). */
    fun removeFailed(kind: FailureKind? = null) {
        val finished = _state.value as? ImportState.Finished ?: return
        val removed = finished.report.failures.filter { kind == null || it.kind == kind }
        _state.value = finished.copy(report = finished.report.copy(failures = finished.report.failures - removed.toSet()))
        scope.launch { removed.map { it.feedId }.chunked(500).forEach { db.feedDao().deleteAll(it) } }
    }

    /** Removes one source whose address was corrected to an unrelated site. */
    fun removeRepaired(repair: ImportRepair) {
        val finished = _state.value as? ImportState.Finished ?: return
        _state.value = finished.copy(
            report = finished.report.copy(
                added = finished.report.added - 1,
                repairs = finished.report.repairs - repair,
            ),
        )
        scope.launch { db.feedDao().delete(repair.feedId) }
    }

    fun reset() {
        if (!isBusy) _state.value = ImportState.Idle
    }

    private suspend fun fetchAll(
        list: List<FeedEntity>,
        onProgress: (done: Int, added: Int, failed: Int) -> Unit,
    ): Batch = coroutineScope {
        val semaphore = Semaphore(FeedRepository.CONCURRENCY)
        var done = 0
        var failed = 0
        val lock = Any()
        onProgress(0, 0, 0)
        val outcomes = list.map { feed ->
            async {
                semaphore.withPermit {
                    val outcome = feeds.refreshFeed(feed, rediscover = true)
                    synchronized(lock) {
                        done++
                        if (outcome is FeedRepository.RefreshOutcome.Failed) failed++
                        onProgress(done, done - failed, failed)
                    }
                    feed to outcome
                }
            }
        }.awaitAll()
        Batch(
            failures = outcomes.mapNotNull { (feed, o) ->
                (o as? FeedRepository.RefreshOutcome.Failed)?.let { ImportFailure(feed.id, feed.title, feed.url, it.reason, it.kind) }
            },
            repairs = outcomes.mapNotNull { (feed, o) ->
                val ok = o as? FeedRepository.RefreshOutcome.Ok ?: return@mapNotNull null
                val moved = ok.movedTo ?: return@mapNotNull null
                // A plain switch to https is not worth reporting.
                if (moved == "https://" + feed.url.removePrefix("http://")) null
                else ImportRepair(feed.id, feed.title, feed.url, moved, ok.otherSite)
            },
            merged = outcomes.count { it.second is FeedRepository.RefreshOutcome.Merged },
        )
    }
}
