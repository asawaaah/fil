package com.asawaaah.fil.ui.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import com.asawaaah.fil.ui.glass.glassSurface
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.asawaaah.fil.data.ContentSource
import com.asawaaah.fil.data.extract.Block
import com.asawaaah.fil.data.extract.RichText
import com.asawaaah.fil.data.extract.SpanKind
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.ui.common.SourceIcon
import com.asawaaah.fil.ui.common.TimeFormat
import com.asawaaah.fil.ui.common.containerFactory
import com.asawaaah.fil.ui.common.openInBrowser
import com.asawaaah.fil.ui.common.shareLink
import com.asawaaah.fil.ui.theme.readerBodyStyle
import com.asawaaah.fil.ui.glass.GlassScreen
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(articleId: Long, onBack: () -> Unit, onOpenSettings: () -> Unit, onOpenFeed: (Long) -> Unit = {}) {
    var sourceSheet by remember { mutableStateOf(false) }
    val vm: ReaderViewModel = viewModel(key = "reader-$articleId", factory = containerFactory { ReaderViewModel(it, articleId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val aiAvailable by vm.modelInstalled.collectAsStateWithLifecycle()
    val reading by vm.readAloud.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var textMenu by remember { mutableStateOf(false) }
    val article = state.article
    val link = article?.linkUrl ?: article?.url
    val shownTitle = article?.let { if (state.showTranslated && state.translatedTitle != null) state.translatedTitle!! else it.title }

    // Find in the article.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var current by remember { mutableIntStateOf(0) }
    val matches by produceState(emptyList<SearchMatch>(), searching, query, shownTitle, state.visibleBlocks) {
        value = if (!searching) emptyList() else withContext(Dispatchers.Default) {
            ArticleSearch.find(query, shownTitle, state.visibleBlocks)
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val hasFooter = link != null
    fun goTo(index: Int) {
        if (matches.isEmpty()) return
        current = Math.floorMod(index, matches.size)
        val m = matches[current]
        // Blocks are the last items of the list, just before the footer.
        val total = listState.layoutInfo.totalItemsCount
        val item = if (m.block == SearchMatch.TITLE) 0 else total - (if (hasFooter) 1 else 0) - state.visibleBlocks.size + m.block
        scope.launch { listState.animateScrollToItem(item.coerceIn(0, (total - 1).coerceAtLeast(0))) }
    }
    LaunchedEffect(matches) {
        current = 0
        if (matches.isNotEmpty()) goTo(0)
    }
    fun closeSearch() {
        searching = false
        query = ""
        keyboard?.hide()
    }
    BackHandler(enabled = searching) { closeSearch() }
    val currentMatch = matches.getOrNull(current)
    val byBlock = remember(matches) { matches.groupBy { it.block } }

    // Reading aloud: the block being read is marked and the page follows it, unless the user
    // scrolls away (then a button brings it back).
    val readingHere = article != null && reading.articleId == article.id
    val readBlock = if (readingHere) reading.currentBlock else null
    var follow by remember { mutableStateOf(true) }
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(dragged) { if (dragged && reading.playing) follow = false }
    fun itemOfBlock(block: Int): Int {
        val total = listState.layoutInfo.totalItemsCount
        val item = if (block == SearchMatch.TITLE) 0 else total - (if (hasFooter) 1 else 0) - state.visibleBlocks.size + block
        return item.coerceIn(0, (total - 1).coerceAtLeast(0))
    }
    LaunchedEffect(readBlock, follow) {
        val b = readBlock ?: return@LaunchedEffect
        if (follow && !searching) listState.animateScrollToItem(itemOfBlock(b), scrollOffset = -40)
    }

    GlassScreen(
        title = state.feed?.title ?: "Article",
        onBack = onBack,
        titleContent = if (searching) {
            { SearchField(query, onChange = { query = it }, onNext = { goTo(current + 1); keyboard?.hide() }) }
        } else {
            {
            // Source name: opens its panel (notifications, folder, delete…).
            Row(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = state.feed != null, onClickLabel = "Options de la source") { sourceSheet = true }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.feed?.let { feed ->
                    SourceIcon(feed.iconUrl, feed.title, size = 22.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        feed.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
            }
            }
        },
        floating = if (readingHere) {
            { backdrop, bottom ->
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!follow && reading.playing) {
                        FilledTonalButton(onClick = { follow = true }) { Text("Revenir au passage lu") }
                    }
                    ReadAloudBar(
                        backdrop = backdrop,
                        state = reading,
                        onToggle = vm::toggleReading,
                        onPrevious = vm::previousSegment,
                        onNext = vm::nextSegment,
                        onRate = vm::setReadingRate,
                        onClose = vm::stopReading,
                        onInstallVoice = {
                            runCatching {
                                context.startActivity(
                                    android.content.Intent(android.speech.tts.TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }
        } else {
            null
        },
        actions = {
            if (searching) {
                val count = when {
                    query.trim().length < ArticleSearch.MIN_QUERY -> ""
                    matches.isEmpty() -> "0"
                    else -> "${current + 1}/${matches.size}"
                }
                Text(
                    count,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(onClick = { goTo(current - 1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowUp, "Occurrence précédente")
                }
                IconButton(onClick = { goTo(current + 1) }, enabled = matches.isNotEmpty()) {
                    Icon(Icons.Default.KeyboardArrowDown, "Occurrence suivante")
                }
                IconButton(onClick = { closeSearch() }) { Icon(Icons.Default.Close, "Fermer la recherche") }
            } else {
                    IconButton(onClick = { searching = true }, enabled = article != null) {
                        Icon(Icons.Default.Search, "Rechercher dans l'article")
                    }
                    Box {
                        IconButton(onClick = { textMenu = true }) { Icon(Icons.Default.TextFields, "Taille du texte") }
                        DropdownMenu(expanded = textMenu, onDismissRequest = { textMenu = false }) {
                            DropdownMenuItem(text = { Text("Texte plus grand") }, onClick = { vm.setTextScale(settings.textScale + 0.1f) })
                            DropdownMenuItem(text = { Text("Texte plus petit") }, onClick = { vm.setTextScale(settings.textScale - 0.1f) })
                            DropdownMenuItem(
                                text = { Text(if (settings.serif) "Police sans empattement" else "Police avec empattement") },
                                onClick = { vm.setSerif(!settings.serif); textMenu = false },
                            )
                        }
                    }
                    if (article != null) {
                        IconButton(onClick = vm::toggleSaved) {
                            Icon(
                                if (article.isSaved) Icons.Default.Bookmark else Icons.Outlined.BookmarkBorder,
                                "Lire plus tard",
                                tint = if (article.isSaved) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    if (link != null) {
                        IconButton(onClick = { shareLink(context, state.translatedTitle ?: article?.title.orEmpty(), link) }) {
                            Icon(Icons.Default.Share, "Partager")
                        }
                    }
            }
        },
    ) { padding ->
        if (article == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScreen
        }
        val bodyStyle = readerBodyStyle(settings.textScale, settings.serif, MaterialTheme.colorScheme.onSurface)
        val hl = Highlight(
            other = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer, color = MaterialTheme.colorScheme.onTertiaryContainer),
            current = SpanStyle(background = MaterialTheme.colorScheme.tertiary, color = MaterialTheme.colorScheme.onTertiary),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + if (readingHere) 140.dp else 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        highlighted(AnnotatedString(shownTitle ?: article.title), byBlock[SearchMatch.TITLE], currentMatch, hl),
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                            fontSize = (24 * settings.textScale).sp,
                            lineHeight = (31 * settings.textScale).sp,
                        ),
                    )
                    val meta = listOfNotNull(article.author, TimeFormat.full(article.publishedAt)).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.ownership?.let { com.asawaaah.fil.ui.common.OwnerFlair(it) }
                }
            }
            item(key = "actions") {
                ActionRow(state, vm, aiAvailable, listening = readingHere) {
                    follow = true
                    vm.listen()
                }
            }
            val aiWorking = state.translation as? TranslationUi.AiWorking
            if (aiWorking != null) {
                item(key = "ai-translating") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Traduction par l'IA ${state.languageFrom}… partie ${minOf(aiWorking.done + 1, aiWorking.total)} sur ${aiWorking.total}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        LinearProgressIndicator(
                            progress = { aiWorking.done.toFloat() / aiWorking.total.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            state.aiError?.let { err ->
                item(key = "ai-error") {
                    Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            if (state.summary != SummaryUi.Hidden) {
                item(key = "summary") {
                    SummaryCard(
                        state.summary,
                        settings,
                        onRetry = { vm.summarize(force = true) },
                        onClose = vm::hideSummary,
                        onOpenSettings = onOpenSettings,
                        onTranslate = vm::translateSummary,
                        onShowOriginal = vm::showSummaryOriginal,
                    )
                }
            }
            val translation = state.translation
            if (translation is TranslationUi.Unavailable) {
                item(key = "translation-error") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(translation.message, style = MaterialTheme.typography.bodyMedium)
                            if (translation.canForce) {
                                TextButton(onClick = { vm.translate(allowCellular = true) }) { Text("Télécharger maintenant avec les données mobiles") }
                            }
                        }
                    }
                }
            }
            if (translation is TranslationUi.Working) {
                item(key = "translating") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Traduction ${state.languageFrom}…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        LinearProgressIndicator(progress = { translation.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (article.imageUrl != null && state.visibleBlocks.firstOrNull { it is Block.Image } == null) {
                item(key = "hero") {
                    AsyncImage(
                        model = article.imageUrl,
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                    )
                }
            }
            if (state.loadingContent) {
                item(key = "loading") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Récupération de l'article complet…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (state.visibleBlocks.isEmpty()) {
                item(key = "empty") {
                    Text(
                        article.excerpt ?: "Le texte de cet article n'est pas disponible ici. Ouvre-le sur le site.",
                        style = bodyStyle,
                    )
                }
            }
            items(state.visibleBlocks.withIndex().toList(), key = { "b${it.index}" }) { (index, block) ->
                val beingRead = readBlock == index
                Box(
                    if (beingRead) {
                        Modifier
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    } else {
                        Modifier
                    },
                ) {
                    BlockView(block, bodyStyle, byBlock[index], currentMatch, hl)
                }
            }
            if (link != null) {
                item(key = "footer") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        if (state.source == ContentSource.FEED && !state.loadingContent) {
                            Text(
                                "Texte fourni par le flux. Le site peut contenir la version complète.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { openInBrowser(context, link) }) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Lire sur le site")
                        }
                        if (article.linkUrl != null && article.url != null && article.linkUrl != article.url) {
                            TextButton(onClick = { openInBrowser(context, article.url) }) { Text("Voir les commentaires sur Reddit") }
                        }
                    }
                }
            }
        }
    }
    val feedForSheet = state.feed
    if (sourceSheet && feedForSheet != null) {
        com.asawaaah.fil.ui.sources.SourceSheet(
            feedId = feedForSheet.id,
            onDismiss = { sourceSheet = false },
            onOpenFeed = onOpenFeed,
            // The article goes with its source.
            onDeleted = onBack,
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(state: ReaderState, vm: ReaderViewModel, aiAvailable: Boolean, listening: Boolean, onListen: () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val working = state.summary is SummaryUi.Working
        FilledTonalButton(onClick = { vm.summarize() }, enabled = !working) {
            Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.summary is SummaryUi.Done) "Résumé" else "Résumer")
        }
        if (!listening && !state.loadingContent && state.visibleBlocks.isNotEmpty()) {
            AssistChip(
                onClick = onListen,
                label = { Text("Écouter") },
                leadingIcon = { Icon(Icons.Default.Headphones, null, Modifier.size(18.dp)) },
            )
        }
        when (val t = state.translation) {
            TranslationUi.Original, is TranslationUi.Unavailable -> AssistChip(
                onClick = { vm.translate(allowCellular = false) },
                label = { Text("Traduire ${state.languageFrom}") },
                leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(18.dp)) },
            )
            is TranslationUi.Translated, is TranslationUi.AiWorking -> {
                val byAi = t is TranslationUi.Translated && t.byAi
                AssistChip(
                    onClick = vm::showOriginal,
                    label = { Text(if (byAi) "Traduit par l'IA · voir l'original" else "Traduit · voir l'original") },
                    leadingIcon = { Icon(Icons.Default.Translate, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary) },
                )
                if (t is TranslationUi.Translated && !t.byAi && aiAvailable) {
                    AssistChip(
                        onClick = vm::translateWithAi,
                        label = { Text("Améliorer avec l'IA") },
                        leadingIcon = { Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp)) },
                    )
                }
            }
            is TranslationUi.Working, TranslationUi.NotNeeded -> Unit
        }
    }
}

@Composable
private fun SummaryCard(
    summary: SummaryUi,
    settings: Settings,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
    onTranslate: () -> Unit = {},
    onShowOriginal: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                Spacer(Modifier.width(8.dp))
                Text("Résumé", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (summary is SummaryUi.Done) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Refresh, "Refaire le résumé", Modifier.size(18.dp)) }
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Fermer", Modifier.size(18.dp)) }
            }
            val textStyle = MaterialTheme.typography.bodyLarge.copy(
                fontSize = (16 * settings.textScale).sp,
                lineHeight = (24 * settings.textScale).sp,
            )
            when (summary) {
                SummaryUi.Hidden -> Unit
                SummaryUi.NeedsModel -> {
                    Text(
                        "Les résumés sont faits sur ton téléphone par un modèle d'IA à télécharger une fois (~2,5 Go, en Wi-Fi). " +
                            "Rien n'est envoyé sur internet.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    FilledTonalButton(onClick = onOpenSettings) { Text("Installer le modèle") }
                }
                is SummaryUi.Working -> {
                    if (summary.partial != null) Text(summary.partial, style = textStyle)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(summary.stage, style = MaterialTheme.typography.labelMedium)
                    }
                }
                is SummaryUi.Done -> {
                    Text(summary.shown, style = textStyle)
                    if (summary.language != "fr") {
                        when {
                            summary.translating -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Text("Traduction du résumé…", style = MaterialTheme.typography.labelMedium)
                            }
                            summary.showFrench && summary.french != null -> TextButton(onClick = onShowOriginal) {
                                Icon(Icons.Default.Translate, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Traduit ${com.asawaaah.fil.ai.Languages.fromPhrase(summary.language)} · voir l'original")
                            }
                            else -> {
                                if (summary.translationFailed) {
                                    Text(
                                        "Traduction impossible pour l'instant (pack de langue à télécharger en Wi-Fi).",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                FilledTonalButton(onClick = onTranslate) {
                                    Icon(Icons.Default.Translate, null, Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Traduire le résumé")
                                }
                            }
                        }
                    }
                    Text(
                        listOfNotNull("Généré sur l'appareil", summary.modelName, summary.detail).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                is SummaryUi.Error -> {
                    Text(summary.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetry) { Text("Réessayer") }
                }
            }
        }
    }
}

/** Mini-player in a glass capsule: previous / play-pause / next paragraph, speed, close. */
@Composable
private fun ReadAloudBar(
    backdrop: com.kyant.backdrop.Backdrop,
    state: com.asawaaah.fil.ai.ReadAloudState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRate: (Float) -> Unit,
    onClose: () -> Unit,
    onInstallVoice: () -> Unit,
) {
    val rates = listOf(0.8f, 1f, 1.25f, 1.5f, 2f)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .glassSurface(backdrop, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        state.voiceProblem?.let { problem ->
            Text(problem, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            TextButton(onClick = onInstallVoice) { Text("Installer la voix") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, "Paragraphe précédent") }
            IconButton(onClick = onToggle, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (state.playing) "Pause" else "Lecture",
                    Modifier.size(32.dp),
                )
            }
            IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, "Paragraphe suivant") }
            val label = (if (state.rate % 1f == 0f) "${state.rate.toInt()}" else "${state.rate}".replace('.', ',')) + " ×"
            TextButton(
                onClick = {
                    val i = rates.indexOfFirst { it >= state.rate - 0.01f }
                    onRate(rates[(i + 1).mod(rates.size)])
                },
                modifier = Modifier.semantics { contentDescription = "Vitesse de lecture : $label. Toucher pour changer." },
            ) { Text(label, style = MaterialTheme.typography.labelLarge) }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Arrêter la lecture") }
        }
        if (state.segments.isNotEmpty()) {
            LinearProgressIndicator(
                progress = { (state.index + 1f) / state.segments.size },
                modifier = Modifier.width(200.dp).padding(bottom = 4.dp),
            )
        }
    }
}

/** Search highlight styles: every occurrence, and the one the arrows are on. */
private data class Highlight(val other: SpanStyle, val current: SpanStyle)

private fun highlighted(text: AnnotatedString, matches: List<SearchMatch>?, current: SearchMatch?, hl: Highlight): AnnotatedString {
    if (matches.isNullOrEmpty()) return text
    return buildAnnotatedString {
        append(text)
        for (m in matches) {
            if (m.end <= text.length) addStyle(if (m == current) hl.current else hl.other, m.start, m.end)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, onNext: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNext() }),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Rechercher dans l'article" },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Rechercher…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Composable
private fun BlockView(
    block: Block,
    body: TextStyle,
    matches: List<SearchMatch>? = null,
    current: SearchMatch? = null,
    hl: Highlight? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    fun RichText.shown(): AnnotatedString {
        val a = toAnnotated(linkColor, codeBg)
        return if (hl == null) a else highlighted(a, matches, current, hl)
    }
    fun String.shown(): AnnotatedString = AnnotatedString(this).let { if (hl == null) it else highlighted(it, matches, current, hl) }
    when (block) {
        is Block.Heading -> Text(
            block.text.shown(),
            style = body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = body.fontSize * if (block.level <= 2) 1.25f else 1.1f,
                lineHeight = body.lineHeight * 1.1f,
            ),
            modifier = Modifier.padding(top = 6.dp),
        )
        is Block.Paragraph -> Text(block.text.shown(), style = body)
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                block.text.shown(),
                style = body.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp)) {
            Text(if (block.ordered) "${block.index}." else "•", style = body, modifier = Modifier.width(26.dp))
            Text(block.text.shown(), style = body)
        }
        is Block.Code -> Surface(color = codeBg, shape = RoundedCornerShape(8.dp)) {
            Text(
                block.text.shown(),
                style = body.copy(fontFamily = FontFamily.Monospace, fontSize = body.fontSize * 0.8f, lineHeight = body.lineHeight * 0.8f),
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
        is Block.Image -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AsyncImage(
                model = block.url,
                contentDescription = block.caption,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
            )
            block.caption?.let {
                Text(it.shown(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Block.Divider -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
    }
}

private fun RichText.toAnnotated(linkColor: Color, codeBg: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    for (s in spans) {
        if (s.start < 0 || s.end > text.length || s.start >= s.end) continue
        when (s.kind) {
            SpanKind.BOLD -> addStyle(SpanStyle(fontWeight = FontWeight.Bold), s.start, s.end)
            SpanKind.ITALIC -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), s.start, s.end)
            SpanKind.CODE -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg), s.start, s.end)
            SpanKind.LINK -> s.url?.let { url ->
                addLink(
                    LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
                    s.start,
                    s.end,
                )
            }
        }
    }
}
