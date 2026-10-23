package com.asawaaah.fil.ui.reader

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
fun ReaderScreen(articleId: Long, onBack: () -> Unit, onOpenSettings: () -> Unit) {
    val vm: ReaderViewModel = viewModel(key = "reader-$articleId", factory = containerFactory { ReaderViewModel(it, articleId) })
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val aiAvailable by vm.modelInstalled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var textMenu by remember { mutableStateOf(false) }
    val article = state.article
    val link = article?.linkUrl ?: article?.url

    GlassScreen(
        title = state.feed?.title ?: "Article",
        onBack = onBack,
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
        },
        actions = {
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
        },
    ) { padding ->
        if (article == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GlassScreen
        }
        val bodyStyle = readerBodyStyle(settings.textScale, settings.serif, MaterialTheme.colorScheme.onSurface)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp,
                end = 20.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val title = if (state.showTranslated && state.translatedTitle != null) state.translatedTitle!! else article.title
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontFamily = if (settings.serif) FontFamily.Serif else FontFamily.SansSerif,
                            fontSize = (24 * settings.textScale).sp,
                            lineHeight = (31 * settings.textScale).sp,
                        ),
                    )
                    val meta = listOfNotNull(article.author, TimeFormat.full(article.publishedAt)).joinToString(" · ")
                    Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "actions") { ActionRow(state, vm, aiAvailable) }
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
                item(key = "summary") { SummaryCard(state.summary, settings, onRetry = { vm.summarize(force = true) }, onClose = vm::hideSummary, onOpenSettings = onOpenSettings) }
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
            items(state.visibleBlocks.withIndex().toList(), key = { "b${it.index}" }) { (_, block) ->
                BlockView(block, bodyStyle)
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
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ActionRow(state: ReaderState, vm: ReaderViewModel, aiAvailable: Boolean) {
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
                    Text(summary.text, style = textStyle)
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

@Composable
private fun BlockView(block: Block, body: TextStyle) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    when (block) {
        is Block.Heading -> Text(
            block.text.toAnnotated(linkColor, codeBg),
            style = body.copy(
                fontWeight = FontWeight.Bold,
                fontSize = body.fontSize * if (block.level <= 2) 1.25f else 1.1f,
                lineHeight = body.lineHeight * 1.1f,
            ),
            modifier = Modifier.padding(top = 6.dp),
        )
        is Block.Paragraph -> Text(block.text.toAnnotated(linkColor, codeBg), style = body)
        is Block.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.secondary),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                block.text.toAnnotated(linkColor, codeBg),
                style = body.copy(fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        is Block.ListItem -> Row(Modifier.padding(start = (block.depth * 18).dp)) {
            Text(if (block.ordered) "${block.index}." else "•", style = body, modifier = Modifier.width(26.dp))
            Text(block.text.toAnnotated(linkColor, codeBg), style = body)
        }
        is Block.Code -> Surface(color = codeBg, shape = RoundedCornerShape(8.dp)) {
            Text(
                block.text,
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
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
