package com.asawaaah.fil.ui.sources

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.data.db.FeedEntity
import com.asawaaah.fil.data.db.FolderEntity
import com.asawaaah.fil.data.feeds.FeedRepository
import com.asawaaah.fil.data.feeds.Urls
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.ui.common.SourceIcon
import com.asawaaah.fil.ui.common.containerFactory
import com.asawaaah.fil.work.NewArticleNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything that can be done to one source, from wherever it is opened. */
class SourceSheetViewModel(private val c: AppContainer, private val feedId: Long) : ViewModel() {
    val feed: StateFlow<FeedEntity?> = c.db.feedDao().observeById(feedId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val unread: StateFlow<Int> = c.db.articleDao().observeUnreadCount(feedId).stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val folders: StateFlow<List<FolderEntity>> = c.db.folderDao().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Short result line shown in the sheet (refresh outcome, refused permission…). */
    val status = MutableStateFlow<String?>(null)
    val refreshing = MutableStateFlow(false)

    fun canNotify(): Boolean = NewArticleNotifier(c.appContext).canPost()

    private fun edit(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    fun setNotify(v: Boolean) = edit { c.db.feedDao().setNotify(feedId, v) }
    fun setHidden(v: Boolean) = edit { c.db.feedDao().setHidden(feedId, v) }
    fun setFullText(v: Boolean) = edit { c.db.feedDao().setFullText(feedId, v) }
    fun confirmMoved() = edit { c.db.feedDao().setMovedFrom(feedId, null) }
    fun rename(title: String) = edit { if (title.isNotBlank()) c.db.feedDao().rename(feedId, title.trim()) }
    fun moveTo(folderId: Long?) = edit { c.db.feedDao().setFolder(feedId, folderId) }
    fun moveToNewFolder(name: String) = edit {
        if (name.isNotBlank()) c.db.feedDao().setFolder(feedId, c.feedRepository.getOrCreateFolder(name.trim()))
    }

    fun refresh() {
        val f = feed.value ?: return
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            status.value = when (val r = c.feedRepository.refreshFeed(f, rediscover = true)) {
                is FeedRepository.RefreshOutcome.Ok -> {
                    c.translateInBackground()
                    when (val n = r.newArticleIds.size) {
                        0 -> "Rien de nouveau"
                        1 -> "1 nouvel article"
                        else -> "$n nouveaux articles"
                    }
                }
                is FeedRepository.RefreshOutcome.Failed -> r.reason
                is FeedRepository.RefreshOutcome.Merged -> "Doublon de « ${r.intoTitle} » : supprimé"
            }
            refreshing.value = false
        }
    }

    fun delete(onDone: () -> Unit) = edit {
        c.db.feedDao().delete(feedId)
        onDone()
    }
}

/**
 * Bottom panel with the actions on one source. [onOpenFeed] is null when the source's own list
 * is already on screen; [onDeleted] runs after the source was deleted (e.g. to leave its article).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSheet(
    feedId: Long,
    onDismiss: () -> Unit,
    onOpenFeed: ((Long) -> Unit)?,
    onDeleted: () -> Unit = {},
) {
    val vm: SourceSheetViewModel = viewModel(key = "source-sheet-$feedId", factory = containerFactory { SourceSheetViewModel(it, feedId) })
    val feed by vm.feed.collectAsStateWithLifecycle()
    val unread by vm.unread.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.setNotify(true) else vm.status.value = "Notifications refusées : autorise-les dans les réglages d'Android."
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val f = feed ?: return@ModalBottomSheet
        val folderName = folders.firstOrNull { it.id == f.folderId }?.name
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SourceIcon(f.iconUrl, f.title, size = 40.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        f.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        listOfNotNull(
                            Urls.host(f.siteUrl ?: f.url),
                            folderName?.let { "dossier $it" },
                            if (unread > 0) "$unread non lu${if (unread > 1) "s" else ""}" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            f.lastError?.let {
                Text(
                    "⚠ $it",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            ToggleRow(
                icon = Icons.Default.Notifications,
                title = "M'avertir des nouveaux articles",
                subtitle = when {
                    settings.refreshHours <= 0 -> "L'actualisation automatique est désactivée (Réglages › Fil) : aucune notification ne partira."
                    settings.refreshHours == 1 -> "Une notification quand l'actualisation automatique (toutes les heures) trouve du nouveau."
                    else -> "Une notification quand l'actualisation automatique (toutes les ${settings.refreshHours} heures) trouve du nouveau."
                },
                checked = f.notify,
                onChange = { on ->
                    if (on && !vm.canNotify() && Build.VERSION.SDK_INT >= 33) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        vm.setNotify(on)
                    }
                },
            )
            ToggleRow(
                icon = Icons.Default.VisibilityOff,
                title = "Masquer du fil principal",
                subtitle = "Ses articles restent dans son dossier et sur sa page.",
                checked = f.hiddenFromMain,
                onChange = vm::setHidden,
            )
            ToggleRow(
                icon = Icons.Default.Subject,
                title = "Le flux contient le texte complet",
                subtitle = "Ne pas aller chercher l'article sur le site.",
                checked = f.isFullText,
                onChange = vm::setFullText,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            if (onOpenFeed != null) {
                ActionRow(Icons.AutoMirrored.Filled.Article, "Voir ses articles") { onDismiss(); onOpenFeed(f.id) }
            }
            if (f.movedFrom != null) {
                ActionRow(Icons.Default.CheckCircle, "C'est la bonne source", "Avant sur ${f.movedFrom}") { vm.confirmMoved() }
            }
            ActionRow(Icons.Default.Refresh, if (refreshing) "Actualisation…" else "Actualiser maintenant") { vm.refresh() }
            ActionRow(Icons.AutoMirrored.Filled.DriveFileMove, "Déplacer vers un dossier", folderName ?: "Sans dossier") { moving = true }
            ActionRow(Icons.Default.Edit, "Renommer") { renaming = true }
            ActionRow(Icons.Default.Delete, "Supprimer la source…", danger = true) { confirmDelete = true }
        }
    }

    val f = feed
    if (renaming && f != null) {
        TextInputDialog("Renommer la source", f.title, "Renommer", { vm.rename(it); renaming = false }, { renaming = false })
    }
    if (moving && f != null) {
        FolderPickerDialog(
            title = "Déplacer « ${f.title} »",
            folders = folders,
            current = f.folderId,
            onPick = { vm.moveTo(it); moving = false },
            onNewFolder = { vm.moveToNewFolder(it); moving = false },
            onDismiss = { moving = false },
        )
    }
    if (confirmDelete && f != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer « ${f.title} » ?") },
            text = { Text("Ses articles seront supprimés aussi, y compris ceux gardés dans « À lire plus tard ». Tu pourras la réajouter plus tard.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete {
                        onDismiss()
                        onDeleted()
                    }
                }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, headlineColor = color, leadingIconColor = color),
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
    )
}

/** Choice of a folder, with "no folder" and a new folder. [current] is marked. */
@Composable
fun FolderPickerDialog(
    title: String,
    folders: List<FolderEntity>,
    current: Long?,
    onPick: (Long?) -> Unit,
    onNewFolder: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        TextInputDialog("Nouveau dossier", "", "Créer et déplacer", onNewFolder, { naming = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                item {
                    FolderChoice("Sans dossier", null, selected = current == null) { onPick(null) }
                }
                items(folders, key = { it.id }) { f ->
                    FolderChoice(f.name, Icons.Default.Folder, selected = current == f.id) { onPick(f.id) }
                }
                item {
                    FolderChoice("Nouveau dossier…", Icons.Default.CreateNewFolder, selected = false) { naming = true }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
private fun FolderChoice(name: String, icon: ImageVector?, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = icon?.let { { Icon(it, null) } },
        headlineContent = { Text(name) },
        trailingContent = if (selected) {
            { Icon(Icons.Default.CheckCircle, "Dossier actuel", tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
    )
}
