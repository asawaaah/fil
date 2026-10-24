package com.asawaaah.fil.work

import com.asawaaah.fil.data.db.NotifyItem
import org.junit.Assert.assertEquals
import org.junit.Test

class NewArticlesTest {
    private fun item(id: Long, feedId: Long, fr: String? = null) =
        NotifyItem(id = id, feedId = feedId, title = "Title $id", titleFr = fr, feedTitle = "Source $feedId")

    @Test
    fun fewArticlesGetOneNotificationEach() {
        val plan = NewArticles.plan(listOf(item(1, 1), item(2, 1), item(3, 2)))
        assertEquals(3, plan.size)
        assertEquals(listOf(1L, 2L, 3L), plan.map { (it as NewArticlesNotice.Article).item.id })
    }

    @Test
    fun aBusySourceIsSummedUp() {
        val busy = (1L..8L).map { item(it, 7, fr = "Titre $it") }
        val plan = NewArticles.plan(busy + item(20, 3))
        assertEquals(2, plan.size)
        val digest = plan[0] as NewArticlesNotice.Digest
        assertEquals(8, digest.count)
        assertEquals("Source 7", digest.feedTitle)
        assertEquals((1..5).map { "Titre $it" }, digest.titles)
        assertEquals(20L, (plan[1] as NewArticlesNotice.Article).item.id)
    }

    @Test
    fun exactlyTheLimitStaysSeparate() {
        val plan = NewArticles.plan((1L..NewArticles.MAX_SINGLE.toLong()).map { item(it, 1) })
        assertEquals(NewArticles.MAX_SINGLE, plan.size)
    }
}
