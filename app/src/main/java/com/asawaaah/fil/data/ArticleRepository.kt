package com.asawaaah.fil.data

import com.asawaaah.fil.data.db.ArticleContentEntity
import com.asawaaah.fil.data.db.ArticleEntity
import com.asawaaah.fil.data.db.FeedEntity
import com.asawaaah.fil.data.db.FilDatabase
import com.asawaaah.fil.data.extract.ArticleExtractor
import com.asawaaah.fil.data.feeds.RedditContent
import com.asawaaah.fil.data.net.FetchResult
import com.asawaaah.fil.data.net.Http
import com.asawaaah.fil.data.text.TextUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the reader text comes from. */
enum class ContentSource { EXTRACTED, FEED, IMAGE, NONE }

class ReaderContent(val html: String?, val baseUrl: String?, val source: ContentSource)

class ArticleRepository(private val db: FilDatabase, private val http: Http) {
    private val articleDao = db.articleDao()
    private val contentDao = db.contentDao()

    suspend fun content(articleId: Long): ArticleContentEntity? = contentDao.get(articleId)

    suspend fun updateContent(articleId: Long, change: (ArticleContentEntity) -> ArticleContentEntity) {
        val current = contentDao.get(articleId) ?: ArticleContentEntity(articleId = articleId)
        contentDao.upsert(change(current))
    }

    /**
     * Text to show in reader mode. Uses, in order: a previous extraction, a feed that already has
     * the full text, the web page (extracted now), and finally whatever the feed provided.
     */
    suspend fun readerContent(article: ArticleEntity, feed: FeedEntity?, allowNetwork: Boolean): ReaderContent {
        val stored = contentDao.get(article.id)
        val baseUrl = article.linkUrl ?: article.url
        stored?.extractedHtml?.let { return ReaderContent(it, baseUrl, ContentSource.EXTRACTED) }

        val feedHtml = article.contentHtml?.takeIf { it.isNotBlank() }
        val feedTextLength = feedHtml?.let { TextUtils.stripHtml(it).length } ?: 0
        val isReddit = feed?.kind == FeedEntity.KIND_REDDIT

        if (isReddit) {
            if (feedHtml != null) return ReaderContent(feedHtml, article.url, ContentSource.FEED)
            if (RedditContent.isImageLink(article.linkUrl)) {
                return ReaderContent("<img src=\"${article.linkUrl}\">", article.linkUrl, ContentSource.IMAGE)
            }
        }
        if (feed?.isFullText == true && feedHtml != null) return ReaderContent(feedHtml, article.url, ContentSource.FEED)
        // A long feed body is very likely the full article already: no need to hit the network.
        if (feedTextLength > 2500) return ReaderContent(feedHtml, article.url, ContentSource.FEED)

        val target = if (isReddit) article.linkUrl else article.url
        if (allowNetwork && target != null && stored?.extractionFailed != true) {
            val page = try {
                http.fetch(target, forPage = true) as? FetchResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null // Network problem: try again next time.
            }
            if (page != null) {
                val extracted = withContext(Dispatchers.Default) {
                    runCatching { ArticleExtractor.extract(page.bytes, page.charset, page.finalUrl) }.getOrNull()
                }
                if (extracted != null && extracted.textLength > feedTextLength) {
                    updateContent(article.id) { it.copy(extractedHtml = extracted.html, extractionFailed = false) }
                    return ReaderContent(extracted.html, page.finalUrl, ContentSource.EXTRACTED)
                }
                // The page has nothing better than the feed: don't fetch it again.
                updateContent(article.id) { it.copy(extractionFailed = true) }
            }
        }
        if (feedHtml != null) return ReaderContent(feedHtml, article.url, ContentSource.FEED)
        return ReaderContent(null, article.url, ContentSource.NONE)
    }

    suspend fun setRead(id: Long, read: Boolean) = articleDao.setRead(id, read)
    suspend fun setSaved(id: Long, saved: Boolean) = articleDao.setSaved(id, saved)
}
