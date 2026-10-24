package com.asawaaah.fil.data.feeds

import com.asawaaah.fil.data.topics.TopicClassifier
import com.asawaaah.fil.data.topics.Topics
import com.asawaaah.fil.data.db.ArticleEntity
import com.asawaaah.fil.data.db.FeedEntity
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.db.FolderEntity
import com.asawaaah.fil.data.net.ErrorMessages
import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.Http
import com.asawaaah.fil.data.settings.SettingsRepository
import com.asawaaah.fil.data.text.TextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class FeedRepository(
    private val db: FilDatabase,
    private val http: Http,
    private val discovery: FeedDiscovery,
    private val settings: SettingsRepository,
) {
    private val feedDao = db.feedDao()
    private val articleDao = db.articleDao()
    private val folderDao = db.folderDao()
    private val loader = FeedLoader(http, discovery)

    sealed interface AddResult {
        class Added(val feedId: Long, val title: String, val newArticleIds: List<Long>) : AddResult
        class AlreadyFollowed(val feedId: Long, val title: String) : AddResult
        class Failed(val reason: String) : AddResult
    }

    sealed interface RefreshOutcome {
        /** [movedTo] is set when the feed address was corrected (https, feed moved to a new place). */
        class Ok(val newArticleIds: List<Long>, val movedTo: String? = null, val otherSite: Boolean = false) : RefreshOutcome
        class Failed(val reason: String, val kind: FailureKind) : RefreshOutcome

        /** The corrected address is a feed already followed: this duplicate was removed. */
        class Merged(val intoTitle: String) : RefreshOutcome
    }

    data class RefreshReport(val newArticleIds: List<Long>, val failed: Int, val total: Int)

    suspend fun addFeed(input: String, folderId: Long?): AddResult {
        return when (val r = discovery.discover(input)) {
            is FeedDiscovery.Result.NotFound -> AddResult.Failed(r.reason)
            is FeedDiscovery.Result.Found -> {
                val normalized = Urls.normalize(r.feedUrl)
                feedDao.getByNormalizedUrl(normalized)?.let { return AddResult.AlreadyFollowed(it.id, it.title) }
                val siteUrl = r.feed.siteUrl
                val feed = FeedEntity(
                    url = r.feedUrl,
                    normalizedUrl = normalized,
                    title = r.feed.title ?: Urls.host(siteUrl ?: r.feedUrl) ?: r.feedUrl,
                    siteUrl = siteUrl,
                    iconUrl = r.feed.iconUrl ?: Urls.faviconFor(siteUrl ?: r.feedUrl),
                    language = r.feed.language,
                    kind = if (r.isReddit) FeedEntity.KIND_REDDIT else FeedEntity.KIND_RSS,
                    folderId = folderId,
                    etag = r.fetch.etag,
                    lastModified = r.fetch.lastModified,
                    lastFetchedAt = System.currentTimeMillis(),
                )
                val id = feedDao.insert(feed)
                if (id == -1L) {
                    val existing = feedDao.getByNormalizedUrl(normalized)
                    return AddResult.AlreadyFollowed(existing?.id ?: -1, existing?.title ?: feed.title)
                }
                val ids = storeItems(feed.copy(id = id), r.feed.items)
                AddResult.Added(id, feed.title, ids)
            }
        }
    }

    /**
     * Fetches one feed and stores its new articles.
     * With [rediscover], a URL that now serves an HTML page is replaced by the feed that page advertises.
     */
    suspend fun refreshFeed(feed: FeedEntity, rediscover: Boolean = false): RefreshOutcome {
        val now = System.currentTimeMillis()
        val result = try {
            loader.load(feed.url, feed.etag, feed.lastModified, repair = rediscover, siteUrl = feed.siteUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FeedLoader.Result.Failed(ErrorMessages.describe(e), e, FeedLoader.classify(e))
        }
        return when (result) {
            FeedLoader.Result.NotModified -> {
                feedDao.setError(feed.id, now, null, null)
                RefreshOutcome.Ok(emptyList())
            }
            is FeedLoader.Result.Failed -> {
                feedDao.setError(feed.id, now, result.reason, result.kind.name)
                RefreshOutcome.Failed(result.reason, result.kind)
            }
            is FeedLoader.Result.Ok -> {
                if (!result.changed(feed.url)) return apply(feed, result.feed, result.fetch)
                // The address was fixed (https, moved feed): keep the working one.
                val normalized = Urls.normalize(result.url)
                val other = feedDao.getByNormalizedUrl(normalized)
                if (other != null && other.id != feed.id) {
                    feedDao.delete(feed.id)
                    return RefreshOutcome.Merged(other.title)
                }
                feedDao.updateUrl(feed.id, result.url, normalized)
                val otherSite = Urls.baseDomain(result.url) != Urls.baseDomain(feed.url) &&
                    Urls.baseDomain(result.url) != Urls.baseDomain(feed.siteUrl)
                var current = feed.copy(url = result.url, normalizedUrl = normalized)
                if (otherSite) {
                    // New owner or new address of the site: show the new site and ask the user to check.
                    val newSite = result.feed.siteUrl ?: result.url
                    feedDao.updateSite(feed.id, newSite, result.feed.iconUrl ?: Urls.faviconFor(newSite))
                    feedDao.setMovedFrom(feed.id, Urls.host(feed.siteUrl ?: feed.url))
                    current = current.copy(siteUrl = newSite, iconUrl = null)
                }
                val applied = apply(current, result.feed, result.fetch) as RefreshOutcome.Ok
                RefreshOutcome.Ok(applied.newArticleIds, movedTo = result.url, otherSite = otherSite)
            }
        }
    }

    private suspend fun apply(feed: FeedEntity, parsed: ParsedFeed, res: FetchResult.Ok): RefreshOutcome {
        val siteUrl = feed.siteUrl ?: parsed.siteUrl
        feedDao.updateFetchState(feed.id, res.etag, res.lastModified, System.currentTimeMillis())
        feedDao.updateMeta(
            feed.id,
            siteUrl = siteUrl,
            iconUrl = feed.iconUrl ?: parsed.iconUrl ?: Urls.faviconFor(siteUrl ?: feed.url),
            language = parsed.language ?: feed.language,
        )
        val placeholder = feed.title.isBlank() || feed.title == Urls.host(feed.url) || feed.title == feed.url
        if (placeholder) parsed.title?.let { feedDao.rename(feed.id, it) }
        return RefreshOutcome.Ok(storeItems(feed, parsed.items))
    }

    /** Sources in error, retried with the full repair attempt. */
    suspend fun retryFailed(onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RefreshReport =
        refreshAll(feedDao.getInError(), repairErrors = true, onProgress = onProgress)

    /** Deletes the sources whose site or feed no longer exists. Returns how many were removed. */
    suspend fun deleteGone(): Int {
        val ids = feedDao.getInError().filter { it.errorKind == FailureKind.GONE.name }.map { it.id }
        ids.chunked(500).forEach { feedDao.deleteAll(it) }
        return ids.size
    }

    /** Guesses the topics of one stored article again (e.g. once its title is translated). */
    suspend fun classify(input: com.asawaaah.fil.data.db.TopicInput) {
        val topics = TopicClassifier.classify(
            url = input.linkUrl ?: input.url,
            categories = input.categories?.split('|').orEmpty(),
            title = input.title,
            titleFr = input.titleFr,
            excerpt = input.excerpt,
        )
        articleDao.setTopics(input.id, Topics.format(topics), TopicClassifier.VERSION)
    }

    /** Articles stored before topics existed, or with older dictionaries. */
    suspend fun classifyPending(): Int {
        var done = 0
        while (true) {
            val batch = articleDao.needingTopics(TopicClassifier.VERSION, 500)
            if (batch.isEmpty()) return done
            batch.forEach { classify(it) }
            done += batch.size
        }
    }

    private suspend fun storeItems(feed: FeedEntity, items: List<ParsedItem>): List<Long> {
        if (items.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val keepSince = now - settings.current().keepDays * DAY_MS
        val isReddit = feed.kind == FeedEntity.KIND_REDDIT
        fun categoriesOf(item: ParsedItem) = item.categories.take(8).joinToString("|").takeIf { it.isNotEmpty() }
        fun topicsOf(item: ParsedItem, url: String?, excerpt: String?) = Topics.format(
            TopicClassifier.classify(url, item.categories, item.title, null, excerpt),
        )
        val entities = items.mapIndexedNotNull { index, item ->
            // Undated items keep the feed's order.
            val published = (item.publishedAt ?: (now - index * 1000L)).coerceAtMost(now)
            if (published < keepSince) return@mapIndexedNotNull null
            if (isReddit) {
                val info = RedditContent.parse(item.contentHtml ?: item.summaryHtml)
                ArticleEntity(
                    feedId = feed.id,
                    guid = item.guid,
                    url = item.url,
                    linkUrl = info.linkUrl,
                    title = item.title,
                    author = item.author,
                    excerpt = TextUtils.excerpt(info.selfHtml),
                    contentHtml = info.selfHtml,
                    imageUrl = item.imageUrl ?: info.imageUrl,
                    publishedAt = published,
                    fetchedAt = now,
                    categories = categoriesOf(item),
                    topics = topicsOf(item, info.linkUrl ?: item.url, TextUtils.excerpt(info.selfHtml)),
                    topicsVersion = TopicClassifier.VERSION,
                )
            } else {
                val excerpt = TextUtils.excerpt(item.summaryHtml ?: item.contentHtml)
                ArticleEntity(
                    feedId = feed.id,
                    guid = item.guid,
                    url = item.url,
                    title = item.title,
                    author = item.author,
                    excerpt = excerpt?.takeIf { !it.equals(item.title, ignoreCase = true) },
                    contentHtml = item.contentHtml ?: item.summaryHtml,
                    imageUrl = item.imageUrl,
                    publishedAt = published,
                    fetchedAt = now,
                    categories = categoriesOf(item),
                    topics = topicsOf(item, item.url, excerpt),
                    topicsVersion = TopicClassifier.VERSION,
                )
            }
        }
        if (entities.isEmpty()) return emptyList()
        return articleDao.insertAll(entities).filter { it != -1L }
    }

    /**
     * Refreshes [feeds] (all feeds by default), a few at a time. The full repair of sources in
     * error (looking for the feed on the site, etc.) is costly: only done when [repairErrors] is set
     * (import, "Réessayer"), or for errors recorded by older versions of the app.
     */
    suspend fun refreshAll(
        feeds: List<FeedEntity>? = null,
        repairErrors: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): RefreshReport = coroutineScope {
        val list = feeds ?: feedDao.getAll()
        val semaphore = Semaphore(CONCURRENCY)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val results = list.map { feed ->
            async {
                semaphore.withPermit {
                    val repair = feed.lastError != null && (repairErrors || feed.errorKind == null)
                    val outcome = refreshFeed(feed, rediscover = repair)
                    onProgress(done.incrementAndGet(), list.size)
                    when (outcome) {
                        is RefreshOutcome.Ok -> outcome.newArticleIds
                        is RefreshOutcome.Merged -> emptyList()
                        is RefreshOutcome.Failed -> {
                            failed.incrementAndGet()
                            emptyList()
                        }
                    }
                }
            }
        }.awaitAll()
        RefreshReport(results.flatten(), failed.get(), list.size)
    }

    suspend fun purgeOld(): Int {
        val keepDays = settings.current().keepDays
        return articleDao.deleteOlderThan(System.currentTimeMillis() - keepDays * DAY_MS)
    }

    suspend fun getOrCreateFolder(name: String): Long {
        val clean = name.trim()
        folderDao.getByName(clean)?.let { return it.id }
        val id = folderDao.insert(FolderEntity(name = clean))
        return if (id != -1L) id else folderDao.getByName(clean)?.id ?: error("Dossier impossible à créer")
    }

    suspend fun exportOpml(): String {
        val folders = folderDao.getAll().associate { it.id to it.name }
        val feeds = feedDao.getAll().map { f ->
            OpmlFeed(title = f.title, xmlUrl = f.url, htmlUrl = f.siteUrl, folder = f.folderId?.let { folders[it] })
        }
        return Opml.write("Abonnements Fil", feeds, Instant.now().toString())
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val CONCURRENCY = 6
    }
}
