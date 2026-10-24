package com.asawaaah.fil.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.asawaaah.fil.MainActivity
import com.asawaaah.fil.R
import com.asawaaah.fil.data.db.NotifyItem

/** What to post for the new articles of the sources the user follows closely. */
sealed interface NewArticlesNotice {
    /** One notification per article, opening it. */
    data class Article(val item: NotifyItem) : NewArticlesNotice

    /** A busy source: one notification listing the first titles, opening the source. */
    data class Digest(val feedId: Long, val feedTitle: String, val count: Int, val titles: List<String>) : NewArticlesNotice
}

object NewArticles {
    /** Above this many new articles from one source, they are summed up in one notification. */
    const val MAX_SINGLE = 5

    fun plan(items: List<NotifyItem>): List<NewArticlesNotice> =
        items.groupBy { it.feedId }.values.flatMap { sameSource ->
            if (sameSource.size <= MAX_SINGLE) {
                sameSource.map { NewArticlesNotice.Article(it) }
            } else {
                val first = sameSource.first()
                listOf(
                    NewArticlesNotice.Digest(
                        feedId = first.feedId,
                        feedTitle = first.feedTitle,
                        count = sameSource.size,
                        titles = sameSource.take(MAX_SINGLE).map { it.titleFr ?: it.title },
                    ),
                )
            }
        }
}

/** Posts the "new articles" notifications, grouped by source. */
class NewArticleNotifier(private val context: Context) {

    fun canPost(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun post(items: List<NotifyItem>) {
        if (items.isEmpty() || !canPost()) return
        ensureChannel()
        val nm = NotificationManagerCompat.from(context)
        val notices = NewArticles.plan(items)
        try {
            for (notice in notices) {
                when (notice) {
                    is NewArticlesNotice.Article -> {
                        val item = notice.item
                        nm.notify(
                            "article-${item.id}".hashCode(),
                            base(group(item.feedId))
                                .setContentTitle(item.feedTitle)
                                .setContentText(item.titleFr ?: item.title)
                                .setStyle(NotificationCompat.BigTextStyle().bigText(item.titleFr ?: item.title))
                                .setContentIntent(open(MainActivity.EXTRA_ARTICLE_ID, item.id))
                                .build(),
                        )
                    }
                    is NewArticlesNotice.Digest -> {
                        val style = NotificationCompat.InboxStyle()
                        notice.titles.forEach { style.addLine(it) }
                        if (notice.count > notice.titles.size) style.setSummaryText("+${notice.count - notice.titles.size} autres")
                        nm.notify(
                            "digest-${notice.feedId}".hashCode(),
                            base(group(notice.feedId))
                                .setContentTitle("${notice.feedTitle} · ${notice.count} nouveaux articles")
                                .setContentText(notice.titles.first())
                                .setStyle(style)
                                .setNumber(notice.count)
                                .setContentIntent(open(MainActivity.EXTRA_FEED_ID, notice.feedId))
                                .build(),
                        )
                    }
                }
            }
            // Android bundles the notifications of a group under one summary.
            notices.groupBy { feedOf(it) }.forEach { (feedId, list) ->
                if (list.size < 2) return@forEach
                val title = when (val n = list.first()) {
                    is NewArticlesNotice.Article -> n.item.feedTitle
                    is NewArticlesNotice.Digest -> n.feedTitle
                }
                nm.notify(
                    "summary-$feedId".hashCode(),
                    base(group(feedId))
                        .setContentTitle(title)
                        .setContentText("${list.size} nouveaux articles")
                        .setGroupSummary(true)
                        .setContentIntent(open(MainActivity.EXTRA_FEED_ID, feedId))
                        .build(),
                )
            }
        } catch (_: SecurityException) {
            // Permission withdrawn in the meantime.
        }
    }

    private fun feedOf(n: NewArticlesNotice) = when (n) {
        is NewArticlesNotice.Article -> n.item.feedId
        is NewArticlesNotice.Digest -> n.feedId
    }

    private fun group(feedId: Long) = "fil-feed-$feedId"

    private fun base(group: String) = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setGroup(group)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)

    private fun open(extra: String, id: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction("fil.open.$extra.$id")
            .putExtra(extra, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Nouveaux articles", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Nouveaux articles des sources pour lesquelles tu as demandé à être averti"
                },
            )
        }
    }

    companion object {
        const val CHANNEL = "new_articles"
    }
}
