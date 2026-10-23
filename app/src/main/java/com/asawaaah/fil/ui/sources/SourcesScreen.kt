package com.asawaaah.fil.ui.sources

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asawaaah.fil.data.db.FeedWithCount
import com.asawaaah.fil.data.db.FolderEntity
import com.asawaaah.fil.data.feeds.FailureKind
import com.asawaaah.fil.ui.glass.GlassFab
import com.asawaaah.fil.ui.glass.GlassScreen
import com.asawaaah.fil.data.feeds.Urls
import com.asawaaah.fil.ui.common.SourceIcon
import com.asawaaah.fil.ui.common.containerFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    prefillUrl: String?,
    onPrefillConsumed: () -> Unit,
    onOpenFeed: (Long) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onImportOpml: () -> Unit,
) {
    val vm: SourcesViewModel = viewModel(factory = containerFactory { SourcesViewModel(it) })
    val groups by vm.groups.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showAdd by remember { mutableStateOf(false) }
    var addPrefill by remember { mutableStateOf("") }
    var newFolderFor by remember { mutableStateOf<Long?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameFolder by remember { mutableStateOf<FolderEntity?>(null) }
    var renameFeed by remember { mutableStateOf<FeedWithCount?>(null) }
    var moveFeed by remember { mutableStateOf<FeedWithCount?>(null) }
    var confirmDelete by remember { mutableStateOf<FeedWithCount?>(null) }
    var confirmDeleteGone by remember { mutableStateOf<Int?>(null) }
    val retryProgress by vm.retryProgress.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) vm.exportOpml(context, uri)
    }

    LaunchedEffect(prefillUrl) {
        if (prefillUrl != null) {
            addPrefill = prefillUrl
            showAdd = true
            onPrefillConsumed()
        }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    GlassScreen(
        title = "Sources",
        snackbar = snackbar,
        actions = {
            IconButton(onClick = onImportOpml) { Icon(Icons.Default.FileUpload, "Importer un fichier OPML") }
            IconButton(onClick = { exportLauncher.launch("fil-abonnements.opml") }) { Icon(Icons.Default.FileDownload, "Exporter en OPML") }
            IconButton(onClick = { newFolderFor = null; showNewFolder = true }) { Icon(Icons.Default.CreateNewFolder, "Nouveau dossier") }
        },
        floating = { backdrop, bottom ->
            GlassFab(
                backdrop = backdrop,
                icon = Icons.Default.Add,
                text = "Ajouter",
                onClick = { addPrefill = ""; showAdd = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = bottom + 8.dp),
            )
        },
    ) { padding ->
        val list = groups
        if (list == null) return@GlassScreen
        if (list.all { it.feeds.isEmpty() } && list.none { it.folder != null }) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Aucune source pour l'instant.", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Ajoute l'adresse d'un site, d'un flux RSS, ou un subreddit (ex. r/android). " +
                        "Tu peux aussi importer d'un coup tous tes abonnements depuis un fichier OPML.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                TextButton(onClick = onImportOpml) { Text("Importer un fichier OPML") }
            }
            return@GlassScreen
        }
        val inError = list.flatMap { g -> g.feeds.filter { it.feed.lastError != null } }
        val errors = inError.size
        val gone = inError.count { it.feed.errorKind == FailureKind.GONE.name }
        val moved = list.sumOf { g -> g.feeds.count { it.feed.movedFrom != null } }
        LazyColumn(
            Modifier.fillMaxSize(),
            // Under the glass bars; extra room at the bottom so the floating "Ajouter" never covers a row.
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 88.dp),
        ) {
            if (moved > 0) {
                item {
                    Text(
                        "$moved source${if (moved > 1) "s ont" else " a"} été déplacée${if (moved > 1) "s" else ""} vers un autre site " +
                            "(nouvelle adresse ou nouveau propriétaire) : vérifie-${if (moved > 1) "les" else "la"} (marquée${if (moved > 1) "s" else ""} ✎).",
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                }
            }
            if (errors > 0) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(
                            "$errors source${if (errors > 1) "s" else ""} en erreur (marquée${if (errors > 1) "s" else ""} ⚠)" +
                                if (gone > 0) ", dont $gone disparue${if (gone > 1) "s" else ""}" else "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val progress = retryProgress
                        if (progress != null) {
                            Text(
                                "Nouvel essai… ${progress.first} / ${progress.second}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        } else {
                            Row {
                                TextButton(onClick = vm::retryErrors) { Text("Réessayer") }
                                if (gone > 0) {
                                    TextButton(onClick = { confirmDeleteGone = gone }) {
                                        Text("Supprimer les disparues ($gone)")
                                    }
                                }
                            }
                        }
                    }
                }
            }
            list.forEach { group ->
                item(key = "folder-${group.folder?.id ?: "none"}") {
                    FolderHeader(
                        folder = group.folder,
                        count = group.feeds.size,
                        unread = group.feeds.sumOf { it.unreadCount },
                        onOpen = { group.folder?.let { onOpenFolder(it.id) } },
                        onRename = { renameFolder = group.folder },
                        onDelete = { group.folder?.let(vm::deleteFolder) },
                    )
                }
                items(group.feeds, key = { "feed-${it.feed.id}" }) { item ->
                    FeedRow(
                        item = item,
                        onOpen = { onOpenFeed(item.feed.id) },
                        onMove = { moveFeed = item },
                        onRename = { renameFeed = item },
                        onToggleHidden = { vm.setHidden(item.feed.id, !item.feed.hiddenFromMain) },
                        onToggleFullText = { vm.setFullText(item.feed.id, !item.feed.isFullText) },
                        onConfirmMoved = { vm.confirmMoved(item.feed.id) },
                        onRefresh = { vm.refreshOne(item) },
                        onDelete = { confirmDelete = item },
                    )
                }
            }
        }
    }

    if (showAdd) {
        AddSourceDialog(vm, folders, addPrefill) { showAdd = false; vm.resetAdd() }
    }
    if (showNewFolder) {
        TextInputDialog(
            title = "Nouveau dossier",
            initial = "",
            confirm = "Créer",
            onConfirm = { vm.createFolder(it, newFolderFor); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    renameFolder?.let { folder ->
        TextInputDialog("Renommer le dossier", folder.name, "Renommer", { vm.renameFolder(folder, it); renameFolder = null }, { renameFolder = null })
    }
    renameFeed?.let { item ->
        TextInputDialog("Renommer la source", item.feed.title, "Renommer", { vm.rename(item.feed.id, it); renameFeed = null }, { renameFeed = null })
    }
    moveFeed?.let { item ->
        AlertDialog(
            onDismissRequest = { moveFeed = null },
            title = { Text("Déplacer « ${item.feed.title} »") },
            text = {
                LazyColumn {
                    item {
                        ListItem(
                            headlineContent = { Text("Sans dossier") },
                            modifier = Modifier.clickable { vm.moveToFolder(item.feed.id, null); moveFeed = null },
                        )
                    }
                    items(folders, key = { it.id }) { f ->
                        ListItem(
                            headlineContent = { Text(f.name) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            modifier = Modifier.clickable { vm.moveToFolder(item.feed.id, f.id); moveFeed = null },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("Nouveau dossier…") },
                            leadingContent = { Icon(Icons.Default.CreateNewFolder, null) },
                            modifier = Modifier.clickable { newFolderFor = item.feed.id; moveFeed = null; showNewFolder = true },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveFeed = null }) { Text("Annuler") } },
        )
    }
    confirmDeleteGone?.let { n ->
        AlertDialog(
            onDismissRequest = { confirmDeleteGone = null },
            title = { Text("Supprimer $n source${if (n > 1) "s" else ""} disparue${if (n > 1) "s" else ""} ?") },
            text = { Text("Leur site ou leur flux n'existe plus (domaine abandonné, flux supprimé, plateforme fermée).") },
            confirmButton = { TextButton(onClick = { vm.deleteGone(); confirmDeleteGone = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDeleteGone = null }) { Text("Annuler") } },
        )
    }
    confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Supprimer « ${item.feed.title} » ?") },
            text = { Text("Ses articles seront supprimés aussi, sauf si tu les réimportes plus tard.") },
            confirmButton = { TextButton(onClick = { vm.delete(item); confirmDelete = null }) { Text("Supprimer") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderHeader(folder: FolderEntity?, count: Int, unread: Int, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(enabled = folder != null, onClick = onOpen, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (folder?.name ?: "Sans dossier").uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count source${if (count > 1) "s" else ""}" + if (unread > 0) " · $unread non lus" else "",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (folder != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options du dossier") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Ouvrir le dossier") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Supprimer le dossier") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun FeedRow(
    item: FeedWithCount,
    onOpen: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onToggleHidden: () -> Unit,
    onToggleFullText: () -> Unit,
    onConfirmMoved: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val feed = item.feed
    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = { SourceIcon(feed.iconUrl, feed.title, size = 28.dp) },
        headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            if (feed.lastError != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text(feed.lastError, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            } else if (feed.movedFrom != null) {
                Text(
                    "✎ Avant sur ${feed.movedFrom}, maintenant sur ${Urls.host(feed.siteUrl ?: feed.url)} : à vérifier",
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (feed.hiddenFromMain) {
                        Icon(Icons.Default.VisibilityOff, "Masquée du fil principal", Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(Urls.host(feed.siteUrl ?: feed.url) ?: feed.url, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.unreadCount > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) { Text("${item.unreadCount}") }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Options") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        if (feed.movedFrom != null) {
                            DropdownMenuItem(text = { Text("C'est la bonne source") }, onClick = { menu = false; onConfirmMoved() })
                        }
                        DropdownMenuItem(text = { Text("Actualiser") }, onClick = { menu = false; onRefresh() })
                        DropdownMenuItem(text = { Text("Déplacer vers un dossier") }, onClick = { menu = false; onMove() })
                        DropdownMenuItem(text = { Text("Renommer") }, onClick = { menu = false; onRename() })
                        DropdownMenuItem(
                            text = { Text(if (feed.hiddenFromMain) "Afficher dans le fil principal" else "Masquer du fil principal") },
                            onClick = { menu = false; onToggleHidden() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (feed.isFullText) "Récupérer le texte sur le site" else "Le flux contient déjà le texte complet") },
                            onClick = { menu = false; onToggleFullText() },
                        )
                        DropdownMenuItem(text = { Text("Supprimer") }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
    )
}

@Composable
private fun AddSourceDialog(vm: SourcesViewModel, folders: List<FolderEntity>, prefill: String, onDismiss: () -> Unit) {
    val state by vm.addState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf(prefill) }
    var folderId by remember { mutableStateOf<Long?>(null) }
    val working = state is AddState.Working
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("Ajouter une source") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Site, flux RSS ou r/subreddit") },
                    placeholder = { Text("lemonde.fr, theverge.com, r/android…") },
                    singleLine = true,
                    enabled = !working,
                    isError = state is AddState.Error,
                    supportingText = { (state as? AddState.Error)?.let { Text(it.message) } },
                )
                if (folders.isNotEmpty()) {
                    Text("Dossier", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = folderId == null, onClick = { folderId = null }, label = { Text("Aucun") })
                        folders.forEach { f ->
                            FilterChip(selected = folderId == f.id, onClick = { folderId = f.id }, label = { Text(f.name) })
                        }
                    }
                }
                if (working) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Recherche du flux…")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.add(input, folderId, onDismiss) }, enabled = !working && input.isNotBlank()) { Text("Ajouter") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Annuler") } },
    )
}

@Composable
fun TextInputDialog(title: String, initial: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
