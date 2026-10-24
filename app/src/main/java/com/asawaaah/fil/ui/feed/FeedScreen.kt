package com.asawaaah.fil.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import com.asawaaah.fil.data.db.FolderEntity
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.FilterListOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import com.asawaaah.fil.ui.glass.CapsuleShape
import com.asawaaah.fil.ui.glass.GlassScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.asawaaah.fil.data.db.ArticleListItem
import com.asawaaah.fil.ui.common.SourceIcon
import com.asawaaah.fil.ui.common.TimeFormat
import com.asawaaah.fil.ui.common.containerFactory
import com.asawaaah.fil.ui.common.openInBrowser
import com.asawaaah.fil.ui.common.shareLink

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    initial: ListSelection,
    onOpenArticle: (Long) -> Unit,
    onBack: (() -> Unit)?,
    onGoToSources: () -> Unit,
    onImportOpml: () -> Unit,
    onOpenFeed: (Long) -> Unit = {},
) {
    val key = initial.toString()
    // Panel with the actions on one source, opened from an article or a pile.
    var sheetFor by remember { mutableStateOf<Long?>(null) }
    val vm: FeedViewModel = viewModel(key = key, factory = containerFactory { FeedViewModel(it, initial) })
    val items by vm.items.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val progress by vm.refreshProgress.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val expanded by vm.expandedGroups.collectAsStateWithLifecycle()
    val undoable by vm.undoableRead.collectAsStateWithLifecycle()
    val undoableHide by vm.undoableHide.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val pullState = rememberPullToRefreshState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(undoableHide) {
        val topic = undoableHide ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            "Sujet « ${com.asawaaah.fil.data.topics.Topics.label(topic)} » masqué",
            actionLabel = "Annuler",
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.unhideTopic(topic)
        vm.consumeUndoableHide()
    }
    LaunchedEffect(undoable) {
        // Cleared only once the message is gone: clearing it earlier would cancel this effect.
        val read = undoable ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(read.text, actionLabel = "Annuler", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoRead(read.ids)
        vm.consumeUndoableRead()
    }

    GlassScreen(
        title = when (initial) {
            ListSelection.Main -> "Fil"
            ListSelection.Saved -> "À lire plus tard"
            else -> title
        },
        onBack = onBack,
        snackbar = snackbar,
        actions = {
            if (initial != ListSelection.Saved) {
                IconButton(onClick = vm::toggleUnreadOnly) {
                    Icon(
                        if (settings.unreadOnly) Icons.Default.FilterListOff else Icons.Default.FilterList,
                        if (settings.unreadOnly) "Afficher tout" else "Non lus seulement",
                    )
                }
            }
            IconButton(onClick = vm::markAllRead) { Icon(Icons.Default.DoneAll, "Tout marquer comme lu") }
            if (initial != ListSelection.Saved) {
                IconButton(onClick = vm::refresh, enabled = !refreshing) { Icon(Icons.Default.Refresh, "Actualiser") }
            }
        },
        belowTopBar = {
            progress?.let { (done, total) ->
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { done.toFloat() / total },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { if (initial != ListSelection.Saved) vm.refresh() },
            modifier = Modifier.fillMaxSize(),
            state = pullState,
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = refreshing,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                )
            },
        ) {
            val showChips = initial == ListSelection.Main && folders.isNotEmpty()
            val showUnreadLabel = settings.unreadOnly && initial != ListSelection.Saved
            val rows = remember(entries, expanded, showChips, showUnreadLabel) {
                feedRows(entries, expanded, showChips, showUnreadLabel)
            }
            val showSource = initial !is ListSelection.Feed
            // The list scrolls under the floating glass bars: the padding keeps its ends visible.
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            ) {
                items(rows, key = { it.key }, contentType = { it.contentType }) { row ->
                    when (row) {
                        FeedRow.Chips -> FolderChips(folders, selection, vm::select)
                        FeedRow.UnreadLabel -> Text(
                            "Non lus seulement",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                        FeedRow.Empty -> EmptyState(initial, settings.unreadOnly, onGoToSources, onImportOpml)
                        is FeedRow.Day -> DayHeader(row.header)
                        is FeedRow.HiddenNote -> HiddenNote(row.entry) { vm.toggleRevealed(row.entry.day) }
                        is FeedRow.Article -> {
                            val item = row.item
                            Column(if (row.inGroup) Modifier.groupMember() else Modifier) {
                                ArticleCard(
                                    item = item,
                                    showSource = showSource && !row.inGroup,
                                    onClick = { onOpenArticle(item.id) },
                                    onToggleSaved = { vm.toggleSaved(item) },
                                    onSetRead = { vm.setRead(item, it) },
                                    compact = settings.compactFeed,
                                    onOpenSource = { sheetFor = item.feedId },
                                    onHideTopic = if (initial is ListSelection.Feed || initial == ListSelection.Saved) null else vm::hideTopic,
                                )
                                HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                        is FeedRow.GroupCollapsed -> GroupCard(
                            group = row.group,
                            onOpenArticle = onOpenArticle,
                            onExpand = { vm.toggleGroup(row.group.key) },
                            onMarkRead = { vm.markGroupRead(row.group) },
                            onToggleSaved = { vm.toggleSaved(it) },
                            onSetRead = { item, read -> vm.setRead(item, read) },
                            compact = settings.compactFeed,
                            onOpenSource = { sheetFor = row.group.feedId },
                            onHideTopic = vm::hideTopic,
                        )
                        is FeedRow.GroupHead -> ExpandedGroupHeader(
                            group = row.group,
                            onOpenSource = { sheetFor = row.group.feedId },
                            onCollapse = { vm.toggleGroup(row.group.key) },
                            onMarkRead = { vm.markGroupRead(row.group) },
                        )
                        is FeedRow.GroupEnd -> CollapseFooter(row.group) {
                            // Back to the top of the group, which takes the place of its header.
                            val index = rows.indexOfFirst { it.key == row.group.key }
                            vm.toggleGroup(row.group.key)
                            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
                        }
                    }
                }
            }
        }
    }
    SourceSheetHost(sheetFor, initial, onDismiss = { sheetFor = null }, onOpenFeed = onOpenFeed)
}

@Composable
private fun SourceSheetHost(feedId: Long?, initial: ListSelection, onDismiss: () -> Unit, onOpenFeed: (Long) -> Unit) {
    feedId ?: return
    com.asawaaah.fil.ui.sources.SourceSheet(
        feedId = feedId,
        onDismiss = onDismiss,
        // No "see its articles" when its own list is the one on screen.
        onOpenFeed = if (initial is ListSelection.Feed && initial.id == feedId) null else onOpenFeed,
    )
}

/** Flat list of what the feed shows, so that each row has a stable key and a known position. */
private sealed interface FeedRow {
    val key: String
    val contentType: String

    data object Chips : FeedRow {
        override val key = "chips"
        override val contentType = "chips"
    }

    data object UnreadLabel : FeedRow {
        override val key = "unread"
        override val contentType = "unread"
    }

    data object Empty : FeedRow {
        override val key = "empty"
        override val contentType = "empty"
    }

    data class HiddenNote(val entry: FeedEntry.Hidden) : FeedRow {
        override val key get() = entry.key
        override val contentType get() = "hidden"
    }

    data class Day(val header: FeedEntry.DayHeader) : FeedRow {
        override val key get() = header.key
        override val contentType get() = "day"
    }

    data class Article(val item: ArticleListItem, val inGroup: Boolean) : FeedRow {
        override val key get() = FeedEntry.articleKey(item.id)
        override val contentType get() = "article"
    }

    /** Collapsed and expanded states share the group's key, so the list keeps its place when toggling. */
    data class GroupCollapsed(val group: FeedEntry.Group) : FeedRow {
        override val key get() = group.key
        override val contentType get() = "group"
    }

    data class GroupHead(val group: FeedEntry.Group) : FeedRow {
        override val key get() = group.key
        override val contentType get() = "groupHead"
    }

    data class GroupEnd(val group: FeedEntry.Group) : FeedRow {
        override val key get() = group.key + ":end"
        override val contentType get() = "groupEnd"
    }
}

private fun feedRows(
    entries: List<FeedEntry>?,
    expanded: Set<String>,
    showChips: Boolean,
    showUnreadLabel: Boolean,
): List<FeedRow> {
    val rows = ArrayList<FeedRow>()
    if (showChips) rows += FeedRow.Chips
    if (showUnreadLabel) rows += FeedRow.UnreadLabel
    when {
        entries == null -> Unit
        entries.isEmpty() -> rows += FeedRow.Empty
        else -> for (entry in entries) {
            when (entry) {
                is FeedEntry.DayHeader -> rows += FeedRow.Day(entry)
                is FeedEntry.Hidden -> rows += FeedRow.HiddenNote(entry)
                is FeedEntry.Single -> rows += FeedRow.Article(entry.item, inGroup = false)
                is FeedEntry.Group -> if (entry.key in expanded) {
                    rows += FeedRow.GroupHead(entry)
                    entry.items.mapTo(rows) { FeedRow.Article(it, inGroup = true) }
                    rows += FeedRow.GroupEnd(entry)
                } else {
                    rows += FeedRow.GroupCollapsed(entry)
                }
            }
        }
    }
    return rows
}

@Composable
private fun FolderChips(folders: List<FolderEntity>, selection: ListSelection, onSelect: (ListSelection) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FilterChip(
                selected = selection == ListSelection.Main,
                onClick = { onSelect(ListSelection.Main) },
                label = { Text("Tout") },
                shape = CapsuleShape,
            )
        }
        items(folders, key = { it.id }) { folder ->
            val sel = ListSelection.Folder(folder.id)
            FilterChip(
                selected = selection == sel,
                onClick = { onSelect(sel) },
                label = { Text(folder.name) },
                shape = CapsuleShape,
            )
        }
    }
}

/** "12 articles masqués (Football) · Afficher" at the end of a day. */
@Composable
private fun HiddenNote(entry: FeedEntry.Hidden, onToggle: () -> Unit) {
    val n = entry.count
    val labels = entry.topics.take(3).joinToString(", ") { com.asawaaah.fil.data.topics.Topics.label(it) }
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.revealed) {
                "$n article${if (n > 1) "s" else ""} de sujets masqués affiché${if (n > 1) "s" else ""} ($labels)"
            } else {
                "$n article${if (n > 1) "s" else ""} masqué${if (n > 1) "s" else ""} ($labels)"
            },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggle) { Text(if (entry.revealed) "Masquer" else "Afficher") }
    }
}

@Composable
private fun DayHeader(header: FeedEntry.DayHeader) {
    Text(
        TimeFormat.dayLabel(header.day),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** Thin line on the left of the articles of an unfolded group, to show they belong together. */
@Composable
private fun Modifier.groupMember(): Modifier {
    val color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    return this
        .drawBehind {
            drawRect(color, topLeft = Offset(12.dp.toPx(), 0f), size = Size(3.dp.toPx(), size.height))
        }
        .padding(start = 12.dp)
}

private fun articlesOf(group: FeedEntry.Group) = FeedGrouping.articlesOf(group)

/** Collapsed group: newest article as a normal card, two more titles, and a button to unfold. */
@Composable
private fun GroupCard(
    group: FeedEntry.Group,
    onOpenArticle: (Long) -> Unit,
    onExpand: () -> Unit,
    onMarkRead: () -> Unit,
    onToggleSaved: (ArticleListItem) -> Unit,
    onSetRead: (ArticleListItem, Boolean) -> Unit,
    compact: Boolean = false,
    onOpenSource: () -> Unit = {},
    onHideTopic: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    val previews = group.items.drop(1).take(2)
    val others = group.items.size - 1 - previews.size
    val unread = group.unreadCount
    Box(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 18.dp)) {
        // Edges of the cards stacked below: the "pile".
        Box(
            Modifier
                .matchParentSize()
                .offset(y = 10.dp)
                .padding(horizontal = 22.dp)
                .background(scheme.surfaceContainerHigh, shape)
                .border(1.dp, scheme.outlineVariant, shape),
        )
        Box(
            Modifier
                .matchParentSize()
                .offset(y = 5.dp)
                .padding(horizontal = 11.dp)
                .background(scheme.surfaceContainer, shape)
                .border(1.dp, scheme.outlineVariant, shape),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(scheme.surfaceContainerLow)
                .border(1.dp, scheme.outlineVariant, shape),
        ) {
            ArticleCard(
                item = group.lead,
                showSource = true,
                onClick = { onOpenArticle(group.lead.id) },
                onToggleSaved = { onToggleSaved(group.lead) },
                onSetRead = { onSetRead(group.lead, it) },
                compact = compact,
                onOpenSource = onOpenSource,
                onHideTopic = onHideTopic,
            )
            previews.forEach { item ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = "Ouvrir l'article") { onOpenArticle(item.id) }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .alpha(if (item.isRead) 0.55f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("•", color = scheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        item.titleFr ?: item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(TimeFormat.relative(item.publishedAt), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    Modifier
                        .weight(1f, fill = false)
                        .heightIn(min = 48.dp)
                        .clip(CapsuleShape)
                        .background(scheme.secondaryContainer)
                        .clickable(role = Role.Button, onClick = onExpand)
                        .semantics {
                            contentDescription = "Afficher les ${articlesOf(group)}" + if (unread > 0) ", $unread non lus" else ""
                            stateDescription = "Replié"
                        }
                        .padding(start = 16.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        buildString {
                            append(if (others > 0) "+$others autre${if (others > 1) "s" else ""}" else "Tout afficher")
                            if (unread > 0) append(" · $unread non lu${if (unread > 1) "s" else ""}")
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Default.ExpandMore, null, tint = scheme.onSecondaryContainer)
                }
                if (unread > 0) {
                    IconButton(onClick = onMarkRead) {
                        Icon(Icons.Default.DoneAll, "Marquer les ${articlesOf(group)} comme lus", tint = scheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Top of an unfolded group: source, count, "mark all read" and "fold". */
@Composable
private fun ExpandedGroupHeader(group: FeedEntry.Group, onOpenSource: () -> Unit, onCollapse: () -> Unit, onMarkRead: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Source name: opens its panel (notifications, folder, delete…).
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = "Options de la source", onClick = onOpenSource)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SourceIcon(group.lead.feedIconUrl, group.feedTitle, size = 22.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                "${group.feedTitle} · ${group.items.size} articles",
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (group.unreadCount > 0) {
            IconButton(onClick = onMarkRead) {
                Icon(Icons.Default.DoneAll, "Marquer les ${articlesOf(group)} comme lus", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FoldButton(group, onCollapse)
    }
}

@Composable
private fun CollapseFooter(group: FeedEntry.Group, onCollapse: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
        FoldButton(group, onCollapse)
    }
}

@Composable
private fun FoldButton(group: FeedEntry.Group, onCollapse: () -> Unit) {
    TextButton(
        onClick = onCollapse,
        modifier = Modifier.semantics {
            contentDescription = "Replier les ${articlesOf(group)}"
            stateDescription = "Déplié"
        },
    ) {
        Text("Replier")
        Icon(Icons.Default.ExpandLess, null)
    }
}

@Composable
private fun EmptyState(selection: ListSelection, unreadOnly: Boolean, onGoToSources: () -> Unit, onImportOpml: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(48.dp))
        Icon(Icons.Outlined.RssFeed, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        val text = when {
            selection == ListSelection.Saved -> "Rien à lire plus tard pour l'instant.\nTouche le signet d'un article pour le garder ici."
            unreadOnly -> "Tout est lu ! Tire vers le bas pour actualiser."
            else -> "Aucun article pour l'instant.\nAjoute une source ou importe tes abonnements (OPML)."
        }
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (selection == ListSelection.Main && !unreadOnly) {
            Button(onClick = onImportOpml) { Text("Importer un fichier OPML") }
            OutlinedButton(onClick = onGoToSources) { Text("Ajouter une source") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArticleCard(
    item: ArticleListItem,
    showSource: Boolean,
    onClick: () -> Unit,
    onToggleSaved: () -> Unit,
    onSetRead: (Boolean) -> Unit,
    /** Title and a small picture only: about twice as many articles per screen. */
    compact: Boolean = false,
    /** Opens the source's panel; null hides the menu entry. */
    onOpenSource: (() -> Unit)? = null,
    /** Hides one of the article's topics from the feed; null hides the menu entries. */
    onHideTopic: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val translated = item.titleFr != null
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true })
                .padding(start = 16.dp, end = if (compact) 12.dp else 4.dp, top = if (compact) 10.dp else 12.dp, bottom = if (compact) 10.dp else 12.dp)
                .alpha(if (item.isRead) 0.55f else 1f),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSource) {
                        SourceIcon(item.feedIconUrl, item.feedTitle, size = 16.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            item.feedTitle,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(" · ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        TimeFormat.relative(item.publishedAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (translated) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Translate, "Traduit", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                        Text(
                            " ${item.lang?.uppercase()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    if (item.hasSummary) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.AutoAwesome, "Résumé prêt", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    if (compact && item.isSaved) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.Bookmark, "À lire plus tard", Modifier.size(13.dp), tint = MaterialTheme.colorScheme.secondary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    item.titleFr ?: item.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
                val excerpt = item.excerptFr ?: item.excerpt
                if (!compact && !excerpt.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        excerpt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (compact) {
                // Saving stays one long press away (menu), the card keeps its small height.
                if (item.imageUrl != null) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
            } else Column(horizontalAlignment = Alignment.End) {
                if (item.imageUrl != null) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .padding(start = 12.dp, end = 8.dp)
                            .size(84.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
                IconButton(onClick = onToggleSaved) {
                    Icon(
                        if (item.isSaved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                        if (item.isSaved) "Retirer de « À lire plus tard »" else "Lire plus tard",
                        tint = if (item.isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (item.isRead) "Marquer comme non lu" else "Marquer comme lu") },
                onClick = { menu = false; onSetRead(!item.isRead) },
            )
            DropdownMenuItem(
                text = { Text(if (item.isSaved) "Retirer de « À lire plus tard »" else "Lire plus tard") },
                onClick = { menu = false; onToggleSaved() },
            )
            item.url?.let { url ->
                DropdownMenuItem(text = { Text("Ouvrir dans le navigateur") }, onClick = { menu = false; openInBrowser(context, url) })
                DropdownMenuItem(text = { Text("Partager") }, onClick = { menu = false; shareLink(context, item.titleFr ?: item.title, url) })
            }
            if (onHideTopic != null) {
                val topics = com.asawaaah.fil.data.topics.Topics.parse(item.topics)
                if (topics.isNotEmpty()) HorizontalDivider()
                topics.forEach { t ->
                    DropdownMenuItem(
                        text = { Text("Masquer le sujet : ${com.asawaaah.fil.data.topics.Topics.label(t)}") },
                        onClick = { menu = false; onHideTopic(t) },
                    )
                }
            }
            if (onOpenSource != null) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Source : ${item.feedTitle}…", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    onClick = { menu = false; onOpenSource() },
                )
            }
        }
    }
}
