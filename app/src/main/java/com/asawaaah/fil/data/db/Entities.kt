package com.asawaaah.fil.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "folders", indices = [Index(value = ["name"], unique = true)])
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val position: Int = 0,
)

@Entity(
    tableName = "feeds",
    indices = [Index(value = ["normalizedUrl"], unique = true), Index("folderId")],
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
)
data class FeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val normalizedUrl: String,
    val title: String,
    val siteUrl: String? = null,
    val iconUrl: String? = null,
    /** Language declared by the feed itself (e.g. "en-us"), used as a hint. */
    val language: String? = null,
    val kind: String = KIND_RSS,
    val folderId: Long? = null,
    val hiddenFromMain: Boolean = false,
    /** The feed already contains the full article, so skip web page extraction. */
    val isFullText: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    val lastFetchedAt: Long? = null,
    val lastError: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** [com.asawaaah.fil.data.feeds.FailureKind] name when [lastError] is set. */
    val errorKind: String? = null,
    /** Previous site, when the feed was automatically moved to another site: to be checked by the user. */
    val movedFrom: String? = null,
) {
    companion object {
        const val KIND_RSS = "rss"
        const val KIND_REDDIT = "reddit"
    }
}

@Entity(
    tableName = "articles",
    indices = [
        Index(value = ["feedId", "guid"], unique = true),
        Index("publishedAt"),
        Index("isSaved"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FeedEntity::class,
            parentColumns = ["id"],
            childColumns = ["feedId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArticleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val feedId: Long,
    val guid: String,
    /** Link to the article page (for Reddit: the comments page). */
    val url: String?,
    /** External link of a Reddit link post. */
    val linkUrl: String? = null,
    val title: String,
    val author: String?,
    /** Plain-text excerpt, a few hundred characters. */
    val excerpt: String?,
    /** HTML provided by the feed itself. */
    val contentHtml: String?,
    val imageUrl: String?,
    val publishedAt: Long,
    val fetchedAt: Long,
    val isRead: Boolean = false,
    val isSaved: Boolean = false,
    /** Detected language code ("en", "es"...), "und" when unknown, null when not yet detected. */
    val lang: String? = null,
    val titleFr: String? = null,
    val excerptFr: String? = null,
)

/** Heavy per-article data, kept out of the list queries. */
@Entity(
    tableName = "article_content",
    foreignKeys = [
        ForeignKey(
            entity = ArticleEntity::class,
            parentColumns = ["id"],
            childColumns = ["articleId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ArticleContentEntity(
    @PrimaryKey val articleId: Long,
    val extractedHtml: String? = null,
    val extractionFailed: Boolean = false,
    /** Translated block texts joined by [TRANSLATION_SEPARATOR]. */
    val translatedTexts: String? = null,
    /** Hash of the HTML the translation was made from, to detect stale translations. */
    val translatedSourceHash: Int? = null,
    val summary: String? = null,
    val summaryModel: String? = null,
    val summaryAt: Long? = null,
    /** Language the summary is written in ("fr", "en"...); null for summaries made before 0.1.7 (French). */
    val summaryLang: String? = null,
    /** French translation of a summary written in the article's language. */
    val summaryFr: String? = null,
    /** Translation by the on-device model: title then block texts, joined by [TRANSLATION_SEPARATOR]. */
    val aiTranslatedTexts: String? = null,
    val aiTranslatedSourceHash: Int? = null,
) {
    companion object {
        const val TRANSLATION_SEPARATOR = "\u001E"
    }
}

@Entity(tableName = "keyword_filters", indices = [Index(value = ["keyword"], unique = true)])
data class KeywordFilterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val keyword: String,
)

/** Row of the article lists. */
data class ArticleListItem(
    val id: Long,
    val feedId: Long,
    val title: String,
    val titleFr: String?,
    val excerpt: String?,
    val excerptFr: String?,
    val imageUrl: String?,
    val url: String?,
    val publishedAt: Long,
    val isRead: Boolean,
    val isSaved: Boolean,
    val lang: String?,
    val feedTitle: String,
    val feedIconUrl: String?,
    val feedSiteUrl: String?,
    val hasSummary: Boolean,
)

data class FeedWithCount(
    @Embedded val feed: FeedEntity,
    val unreadCount: Int,
)
