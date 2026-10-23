package com.asawaaah.fil.ui.sources

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.data.db.FeedWithCount
import com.asawaaah.fil.data.db.FolderEntity
import com.asawaaah.fil.data.feeds.FeedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FolderGroup(val folder: FolderEntity?, val feeds: List<FeedWithCount>)

sealed interface AddState {
    data object Idle : AddState
    data object Working : AddState
    data class Error(val message: String) : AddState
}

class SourcesViewModel(private val c: AppContainer) : ViewModel() {
    val folders: StateFlow<List<FolderEntity>> =
        c.db.folderDao().observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val groups: StateFlow<List<FolderGroup>?> = combine(c.db.feedDao().observeWithCounts(), c.db.folderDao().observeAll()) { feeds, folders ->
        val byFolder = feeds.groupBy { it.feed.folderId }
        buildList {
            folders.forEach { f -> add(FolderGroup(f, byFolder[f.id].orEmpty())) }
            byFolder[null]?.takeIf { it.isNotEmpty() }?.let { add(FolderGroup(null, it)) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val addState = MutableStateFlow<AddState>(AddState.Idle)
    val message = MutableStateFlow<String?>(null)

    fun add(input: String, folderId: Long?, onDone: () -> Unit) {
        if (input.isBlank()) return
        addState.value = AddState.Working
        viewModelScope.launch {
            when (val r = c.feedRepository.addFeed(input.trim(), folderId)) {
                is FeedRepository.AddResult.Added -> {
                    addState.value = AddState.Idle
                    message.value = "« ${r.title} » ajouté (${r.newArticleIds.size} articles)"
                    c.translateInBackground()
                    onDone()
                }
                is FeedRepository.AddResult.AlreadyFollowed -> {
                    addState.value = AddState.Idle
                    message.value = "Tu suis déjà « ${r.title} »"
                    onDone()
                }
                is FeedRepository.AddResult.Failed -> addState.value = AddState.Error(r.reason)
            }
        }
    }

    fun resetAdd() {
        addState.value = AddState.Idle
    }

    fun createFolder(name: String, thenMove: Long? = null) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val id = c.feedRepository.getOrCreateFolder(name)
            thenMove?.let { c.db.feedDao().setFolder(it, id) }
        }
    }

    fun renameFolder(folder: FolderEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { runCatching { c.db.folderDao().rename(folder.id, name.trim()) } }
    }

    fun deleteFolder(folder: FolderEntity) {
        viewModelScope.launch {
            c.db.folderDao().delete(folder.id)
            message.value = "Dossier « ${folder.name} » supprimé (ses sources sont gardées)"
        }
    }

    fun moveToFolder(feedId: Long, folderId: Long?) {
        viewModelScope.launch { c.db.feedDao().setFolder(feedId, folderId) }
    }

    fun setHidden(feedId: Long, hidden: Boolean) {
        viewModelScope.launch { c.db.feedDao().setHidden(feedId, hidden) }
    }

    /** The user checked a source that was moved to another site: it's the right one. */
    fun confirmMoved(feedId: Long) {
        viewModelScope.launch { c.db.feedDao().setMovedFrom(feedId, null) }
    }

    fun setFullText(feedId: Long, fullText: Boolean) {
        viewModelScope.launch { c.db.feedDao().setFullText(feedId, fullText) }
    }

    fun rename(feedId: Long, title: String) {
        if (title.isBlank()) return
        viewModelScope.launch { c.db.feedDao().rename(feedId, title.trim()) }
    }

    fun delete(item: FeedWithCount) {
        viewModelScope.launch {
            c.db.feedDao().delete(item.feed.id)
            message.value = "« ${item.feed.title} » supprimé"
        }
    }

    fun refreshOne(item: FeedWithCount) {
        viewModelScope.launch {
            when (val r = c.feedRepository.refreshFeed(item.feed, rediscover = true)) {
                is FeedRepository.RefreshOutcome.Ok -> {
                    message.value = "${r.newArticleIds.size} nouveaux articles"
                    c.translateInBackground()
                }
                is FeedRepository.RefreshOutcome.Failed -> message.value = r.reason
                is FeedRepository.RefreshOutcome.Merged -> message.value = "Doublon de « ${r.intoTitle} » : supprimé"
            }
        }
    }

    /** Null when idle, else (done, total). */
    val retryProgress = MutableStateFlow<Pair<Int, Int>?>(null)

    fun retryErrors() {
        if (retryProgress.value != null) return
        viewModelScope.launch {
            retryProgress.value = 0 to 0
            try {
                val report = c.feedRepository.retryFailed { done, total -> retryProgress.value = done to total }
                val fixed = report.total - report.failed
                message.value = when {
                    report.total == 0 -> "Aucune source en erreur"
                    fixed == 0 -> "Toujours en erreur : ${report.failed}"
                    else -> "$fixed source${if (fixed > 1) "s" else ""} réparée${if (fixed > 1) "s" else ""}, ${report.failed} toujours en erreur"
                }
                c.translateInBackground()
            } finally {
                retryProgress.value = null
            }
        }
    }

    fun deleteGone() {
        viewModelScope.launch {
            val n = c.feedRepository.deleteGone()
            message.value = if (n == 0) "Aucune source disparue" else "$n source${if (n > 1) "s" else ""} disparue${if (n > 1) "s" else ""} supprimée${if (n > 1) "s" else ""}"
        }
    }

    fun exportOpml(context: Context, uri: Uri) {
        viewModelScope.launch {
            val ok = runCatching {
                val text = c.feedRepository.exportOpml()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("Fichier inaccessible")
                }
            }.isSuccess
            message.value = if (ok) "Abonnements exportés" else "L'export a échoué"
        }
    }

    fun consumeMessage() {
        message.value = null
    }
}
