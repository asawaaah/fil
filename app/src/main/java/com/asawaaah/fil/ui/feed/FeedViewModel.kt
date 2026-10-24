package com.asawaaah.fil.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.data.db.ArticleListItem
import com.asawaaah.fil.data.db.FolderEntity
import com.asawaaah.fil.data.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface ListSelection {
    data object Main : ListSelection
    data class Folder(val id: Long) : ListSelection
    data class Feed(val id: Long) : ListSelection
    data object Saved : ListSelection
}

/** "Mark as read" that the snackbar can undo. */
data class UndoableRead(val text: String, val ids: List<Long>)

@OptIn(ExperimentalCoroutinesApi::class)
class FeedViewModel(private val c: AppContainer, initial: ListSelection) : ViewModel() {
    val selection = MutableStateFlow(initial)
    val refreshing = MutableStateFlow(false)
    val refreshProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val message = MutableStateFlow<String?>(null)

    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val folders: StateFlow<List<FolderEntity>> =
        c.db.folderDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val title: StateFlow<String> = selection.flatMapLatest { sel ->
        when (sel) {
            ListSelection.Main -> flowOf("Fil")
            ListSelection.Saved -> flowOf("À lire plus tard")
            is ListSelection.Feed -> c.db.feedDao().observeById(sel.id).map { it?.title ?: "Source" }
            is ListSelection.Folder -> c.db.folderDao().observeAll().map { list -> list.firstOrNull { it.id == sel.id }?.name ?: "Dossier" }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val keywords = c.db.filterDao().observeAll().map { list -> list.map { it.keyword.lowercase() } }

    /** Null while loading. */
    val items: StateFlow<List<ArticleListItem>?> = combine(selection, settings.map { it.unreadOnly }) { sel, unread -> sel to unread }
        .flatMapLatest { (sel, unread) ->
            val dao = c.db.articleDao()
            when (sel) {
                ListSelection.Main -> dao.observeMain(unread, LIMIT)
                is ListSelection.Folder -> dao.observeFolder(sel.id, unread, LIMIT)
                is ListSelection.Feed -> dao.observeFeed(sel.id, unread, LIMIT)
                ListSelection.Saved -> dao.observeSaved()
            }
        }
        .combine(keywords) { list, words ->
            if (words.isEmpty()) list
            else list.filter { item ->
                val haystack = listOfNotNull(item.title, item.titleFr, item.excerpt, item.excerptFr).joinToString(" ").lowercase()
                words.none { haystack.contains(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * The list split into days, with the articles of a busy source grouped (main feed and folders
     * only: a source's own page or the saved list would make no sense grouped). Null while loading.
     */
    /** Days whose hidden articles the user asked to see. */
    val revealedDays = MutableStateFlow<Set<java.time.LocalDate>>(emptySet())

    val entries: StateFlow<List<FeedEntry>?> =
        combine(
            items,
            settings.map { it.groupBySource to it.hiddenTopics }.distinctUntilChanged(),
            selection,
            revealedDays,
        ) { list, (group, hidden), sel, revealed ->
            // Topics are hidden from the main feed and folders, not from a source's own page.
            val mixed = sel == ListSelection.Main || sel is ListSelection.Folder
            list?.let {
                FeedGrouping.build(
                    it,
                    groupBySource = group && mixed,
                    hiddenTopics = if (mixed) hidden else emptySet(),
                    revealedDays = revealed,
                )
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Keys of the groups the user unfolded. */
    val expandedGroups = MutableStateFlow<Set<String>>(emptySet())
    val undoableRead = MutableStateFlow<UndoableRead?>(null)

    fun toggleGroup(key: String) {
        expandedGroups.value = expandedGroups.value.let { if (key in it) it - key else it + key }
    }

    fun markGroupRead(group: FeedEntry.Group) {
        val ids = group.items.filter { !it.isRead }.map { it.id }
        if (ids.isEmpty()) {
            message.value = "Déjà tout lu"
            return
        }
        viewModelScope.launch {
            ids.chunked(500).forEach { c.db.articleDao().markRead(it) }
            val n = ids.size
            undoableRead.value = UndoableRead(
                "${if (n == 1) "1 article" else "$n articles"} ${FeedGrouping.ofSource(group.feedTitle)} " +
                    if (n == 1) "marqué comme lu" else "marqués comme lus",
                ids,
            )
        }
    }

    fun toggleRevealed(day: java.time.LocalDate) {
        revealedDays.value = revealedDays.value.let { if (day in it) it - day else it + day }
    }

    /** Topic just hidden from an article's menu, offered for undo. */
    val undoableHide = MutableStateFlow<String?>(null)

    fun hideTopic(topic: String) {
        viewModelScope.launch {
            c.settings.setTopicHidden(topic, true)
            undoableHide.value = topic
        }
    }

    fun unhideTopic(topic: String) {
        viewModelScope.launch { c.settings.setTopicHidden(topic, false) }
    }

    fun consumeUndoableHide() {
        undoableHide.value = null
    }

    fun undoRead(ids: List<Long>) {
        viewModelScope.launch { ids.chunked(500).forEach { c.db.articleDao().markUnread(it) } }
    }

    fun consumeUndoableRead() {
        undoableRead.value = null
    }

    fun select(sel: ListSelection) {
        selection.value = sel
    }

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            try {
                val feeds = when (val sel = selection.value) {
                    is ListSelection.Feed -> listOfNotNull(c.db.feedDao().getById(sel.id))
                    is ListSelection.Folder -> c.db.feedDao().getAll().filter { it.folderId == sel.id }
                    else -> null
                }
                val report = c.feedRepository.refreshAll(feeds) { done, total -> refreshProgress.value = done to total }
                c.translateInBackground()
                val n = report.newArticleIds.size
                message.value = buildString {
                    append(
                        when (n) {
                            0 -> "Rien de nouveau"
                            1 -> "1 nouvel article"
                            else -> "$n nouveaux articles"
                        },
                    )
                    if (report.failed > 0) append(" · ${report.failed} source${if (report.failed > 1) "s" else ""} en erreur")
                }
            } finally {
                refreshing.value = false
                refreshProgress.value = null
            }
        }
    }

    fun toggleUnreadOnly() {
        viewModelScope.launch { c.settings.setUnreadOnly(!settings.value.unreadOnly) }
    }

    fun setRead(item: ArticleListItem, read: Boolean) {
        viewModelScope.launch { c.articleRepository.setRead(item.id, read) }
    }

    fun toggleSaved(item: ArticleListItem) {
        viewModelScope.launch { c.articleRepository.setSaved(item.id, !item.isSaved) }
    }

    fun markAllRead() {
        viewModelScope.launch {
            val dao = c.db.articleDao()
            val ids = when (val sel = selection.value) {
                ListSelection.Main -> dao.unreadMainIds()
                is ListSelection.Folder -> dao.unreadFolderIds(sel.id)
                is ListSelection.Feed -> dao.unreadFeedIds(sel.id)
                ListSelection.Saved -> items.value.orEmpty().filter { !it.isRead }.map { it.id }
            }
            ids.chunked(500).forEach { dao.markRead(it) }
            message.value = if (ids.isEmpty()) "Tout est déjà lu" else "${ids.size} articles marqués comme lus"
        }
    }

    fun consumeMessage() {
        message.value = null
    }

    companion object {
        const val LIMIT = 600
    }
}
