package com.asawaaah.fil.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val LIST_COLUMNS = """
    a.id, a.feedId, a.title, a.titleFr, a.excerpt, a.excerptFr, a.imageUrl, a.url, a.publishedAt,
    a.isRead, a.isSaved, a.lang, f.title AS feedTitle, f.iconUrl AS feedIconUrl,
    f.siteUrl AS feedSiteUrl, (c.summary IS NOT NULL) AS hasSummary
"""

private const val LIST_FROM = """
    FROM articles a
    JOIN feeds f ON f.id = a.feedId
    LEFT JOIN article_content c ON c.articleId = a.id
"""

@Dao
interface FolderDao {
    @Query("SELECT * FROM folders ORDER BY position, name COLLATE NOCASE")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders ORDER BY position, name COLLATE NOCASE")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface FeedDao {
    @Query(
        """
        SELECT f.*, (SELECT COUNT(*) FROM articles a WHERE a.feedId = f.id AND a.isRead = 0) AS unreadCount
        FROM feeds f ORDER BY f.title COLLATE NOCASE
        """,
    )
    fun observeWithCounts(): Flow<List<FeedWithCount>>

    @Query("SELECT * FROM feeds ORDER BY title COLLATE NOCASE")
    suspend fun getAll(): List<FeedEntity>

    @Query("SELECT * FROM feeds WHERE id = :id")
    suspend fun getById(id: Long): FeedEntity?

    @Query("SELECT * FROM feeds WHERE id = :id")
    fun observeById(id: Long): Flow<FeedEntity?>

    @Query("SELECT * FROM feeds WHERE normalizedUrl = :normalizedUrl LIMIT 1")
    suspend fun getByNormalizedUrl(normalizedUrl: String): FeedEntity?

    @Query("SELECT normalizedUrl FROM feeds")
    suspend fun allNormalizedUrls(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(feed: FeedEntity): Long

    @Update
    suspend fun update(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE feeds SET folderId = :folderId WHERE id = :id")
    suspend fun setFolder(id: Long, folderId: Long?)

    @Query("UPDATE feeds SET folderId = :folderId WHERE id IN (:ids)")
    suspend fun setFolderMany(ids: List<Long>, folderId: Long?)

    @Query("UPDATE feeds SET notify = :notify WHERE id = :id")
    suspend fun setNotify(id: Long, notify: Boolean)

    @Query("UPDATE feeds SET hiddenFromMain = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE feeds SET isFullText = :fullText WHERE id = :id")
    suspend fun setFullText(id: Long, fullText: Boolean)

    @Query("UPDATE feeds SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    @Query(
        "UPDATE feeds SET etag = :etag, lastModified = :lastModified, lastFetchedAt = :fetchedAt, " +
            "lastError = NULL, errorKind = NULL WHERE id = :id",
    )
    suspend fun updateFetchState(id: Long, etag: String?, lastModified: String?, fetchedAt: Long)

    @Query("UPDATE feeds SET lastFetchedAt = :fetchedAt, lastError = :error, errorKind = :kind WHERE id = :id")
    suspend fun setError(id: Long, fetchedAt: Long, error: String?, kind: String?)

    @Query("SELECT * FROM feeds WHERE lastError IS NOT NULL")
    suspend fun getInError(): List<FeedEntity>

    @Query("DELETE FROM feeds WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)

    @Query("UPDATE feeds SET siteUrl = :siteUrl, iconUrl = :iconUrl WHERE id = :id")
    suspend fun updateSite(id: Long, siteUrl: String?, iconUrl: String?)

    @Query("UPDATE feeds SET movedFrom = :movedFrom WHERE id = :id")
    suspend fun setMovedFrom(id: Long, movedFrom: String?)

    @Query("UPDATE feeds SET siteUrl = :siteUrl, iconUrl = :iconUrl, language = :language WHERE id = :id")
    suspend fun updateMeta(id: Long, siteUrl: String?, iconUrl: String?, language: String?)

    @Query("UPDATE feeds SET url = :url, normalizedUrl = :normalizedUrl WHERE id = :id")
    suspend fun updateUrl(id: Long, url: String, normalizedUrl: String)
}

@Dao
interface ArticleDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(articles: List<ArticleEntity>): List<Long>

    @Query(
        "SELECT $LIST_COLUMNS $LIST_FROM WHERE f.hiddenFromMain = 0 AND (:unreadOnly = 0 OR a.isRead = 0) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit",
    )
    fun observeMain(unreadOnly: Boolean, limit: Int): Flow<List<ArticleListItem>>

    @Query(
        "SELECT $LIST_COLUMNS $LIST_FROM WHERE f.folderId = :folderId AND (:unreadOnly = 0 OR a.isRead = 0) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit",
    )
    fun observeFolder(folderId: Long, unreadOnly: Boolean, limit: Int): Flow<List<ArticleListItem>>

    @Query(
        "SELECT $LIST_COLUMNS $LIST_FROM WHERE a.feedId = :feedId AND (:unreadOnly = 0 OR a.isRead = 0) " +
            "ORDER BY a.publishedAt DESC LIMIT :limit",
    )
    fun observeFeed(feedId: Long, unreadOnly: Boolean, limit: Int): Flow<List<ArticleListItem>>

    @Query("SELECT $LIST_COLUMNS $LIST_FROM WHERE a.isSaved = 1 ORDER BY a.publishedAt DESC")
    fun observeSaved(): Flow<List<ArticleListItem>>

    @Query("SELECT * FROM articles WHERE id = :id")
    suspend fun getById(id: Long): ArticleEntity?

    @Query("SELECT * FROM articles WHERE id = :id")
    fun observeById(id: Long): Flow<ArticleEntity?>

    @Query("SELECT * FROM articles WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<ArticleEntity>

    @Query("UPDATE articles SET isRead = :read WHERE id = :id")
    suspend fun setRead(id: Long, read: Boolean)

    @Query("UPDATE articles SET isSaved = :saved WHERE id = :id")
    suspend fun setSaved(id: Long, saved: Boolean)

    @Query("UPDATE articles SET isRead = 1 WHERE id IN (:ids)")
    suspend fun markRead(ids: List<Long>)

    @Query("UPDATE articles SET isRead = 0 WHERE id IN (:ids)")
    suspend fun markUnread(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM articles WHERE feedId = :feedId AND isRead = 0")
    fun observeUnreadCount(feedId: Long): Flow<Int>

    /** Among [ids], the unread articles of sources that asked for notifications, newest first. */
    @Query(
        "SELECT a.id, a.feedId, a.title, a.titleFr, f.title AS feedTitle FROM articles a " +
            "JOIN feeds f ON f.id = a.feedId WHERE a.id IN (:ids) AND f.notify = 1 AND a.isRead = 0 " +
            "ORDER BY a.publishedAt DESC",
    )
    suspend fun forNotification(ids: List<Long>): List<NotifyItem>

    @Query("UPDATE articles SET lang = :lang, titleFr = :titleFr, excerptFr = :excerptFr WHERE id = :id")
    suspend fun setTranslation(id: Long, lang: String?, titleFr: String?, excerptFr: String?)

    @Query("UPDATE articles SET lang = :lang WHERE id = :id")
    suspend fun setLang(id: Long, lang: String)

    @Query("SELECT * FROM articles WHERE lang IS NULL ORDER BY publishedAt DESC LIMIT :limit")
    suspend fun pendingDetection(limit: Int): List<ArticleEntity>

    @Query("SELECT a.id FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.isRead = 0 AND f.hiddenFromMain = 0")
    suspend fun unreadMainIds(): List<Long>

    @Query("SELECT a.id FROM articles a JOIN feeds f ON f.id = a.feedId WHERE a.isRead = 0 AND f.folderId = :folderId")
    suspend fun unreadFolderIds(folderId: Long): List<Long>

    @Query("SELECT id FROM articles WHERE isRead = 0 AND feedId = :feedId")
    suspend fun unreadFeedIds(feedId: Long): List<Long>

    /** Articles whose language is unknown, or foreign and not yet translated. */
    @Query(
        """
        SELECT * FROM articles
        WHERE lang IS NULL OR (lang NOT IN ('fr', 'und') AND titleFr IS NULL)
        ORDER BY publishedAt DESC LIMIT :limit
        """,
    )
    suspend fun pendingTranslation(limit: Int): List<ArticleEntity>

    @Query(
        """
        SELECT a.* FROM articles a LEFT JOIN article_content c ON c.articleId = a.id
        WHERE a.publishedAt > :since AND a.isRead = 0 AND c.summary IS NULL
        ORDER BY a.publishedAt DESC LIMIT :limit
        """,
    )
    suspend fun recentWithoutSummary(since: Long, limit: Int): List<ArticleEntity>

    @Query("DELETE FROM articles WHERE publishedAt < :before AND isSaved = 0")
    suspend fun deleteOlderThan(before: Long): Int
}

@Dao
interface ArticleContentDao {
    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    suspend fun get(articleId: Long): ArticleContentEntity?

    @Query("SELECT * FROM article_content WHERE articleId = :articleId")
    fun observe(articleId: Long): Flow<ArticleContentEntity?>

    @Upsert
    suspend fun upsert(content: ArticleContentEntity)
}

@Dao
interface KeywordFilterDao {
    @Query("SELECT * FROM keyword_filters ORDER BY keyword COLLATE NOCASE")
    fun observeAll(): Flow<List<KeywordFilterEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(filter: KeywordFilterEntity): Long

    @Query("DELETE FROM keyword_filters WHERE id = :id")
    suspend fun delete(id: Long)
}
