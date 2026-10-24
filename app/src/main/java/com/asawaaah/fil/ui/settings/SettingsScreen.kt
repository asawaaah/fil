package com.asawaaah.fil.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.IconButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.asawaaah.fil.ui.glass.CapsuleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asawaaah.fil.ai.DownloadState
import com.asawaaah.fil.ai.Languages
import com.asawaaah.fil.ai.LlmModel
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.data.settings.ThemeMode
import com.asawaaah.fil.data.settings.GlassMode
import com.asawaaah.fil.ui.glass.GlassScreen
import com.asawaaah.fil.ui.common.containerFactory

/** Settings pages, opened from the menu (or from a search result). */
private enum class Page(val title: String) {
    APPARENCE("Apparence"),
    FIL("Fil"),
    MASQUER("Ce que je ne veux pas voir"),
    TRADUCTION("Traduction"),
    IA("Résumés et IA"),
    AUDIO("Lecture audio"),
    NOTIFICATIONS("Notifications"),
    ABONNEMENTS("Abonnements"),
    A_PROPOS("À propos"),
}

/** Every setting, for the search: what it is called, other words people may type, its page. */
private data class SearchEntry(val title: String, val page: Page, val words: String = "")

private val searchIndex = listOf(
    SearchEntry("Thème clair ou sombre", Page.APPARENCE, "mode nuit dark sombre clair couleur"),
    SearchEntry("Barres flottantes (Liquid Glass ou opaques)", Page.APPARENCE, "verre transparence glass contraste"),
    SearchEntry("Taille du texte des articles", Page.APPARENCE, "police grand petit zoom lisibilite"),
    SearchEntry("Police avec empattement", Page.APPARENCE, "serif police livre"),
    SearchEntry("Affichage normal ou compact", Page.FIL, "compact cartes images extrait"),
    SearchEntry("Regrouper par source", Page.FIL, "piles groupes regroupement"),
    SearchEntry("Actualisation automatique", Page.FIL, "rafraichir mise a jour heures frequence"),
    SearchEntry("Garder les articles", Page.FIL, "duree suppression nettoyage jours stockage"),
    SearchEntry("Sujets masqués", Page.MASQUER, "foot football sport people topics themes"),
    SearchEntry("Mots-clés masqués", Page.MASQUER, "filtre mot cle bloquer cacher"),
    SearchEntry("Traduire automatiquement en français", Page.TRADUCTION, "traduction langue francais"),
    SearchEntry("Traduire les articles avec l'IA", Page.TRADUCTION, "gemma traduction naturelle"),
    SearchEntry("Langues installées", Page.TRADUCTION, "packs langues supprimer espace"),
    SearchEntry("Télécharger les langues seulement en Wi-Fi", Page.TRADUCTION, "wifi donnees mobiles"),
    SearchEntry("Modèle d'IA (Gemma, Qwen)", Page.IA, "modele gemma qwen telecharger resume e4b e2b tensor"),
    SearchEntry("Préparer les résumés pendant la recharge", Page.IA, "batterie charge avance"),
    SearchEntry("Utiliser la puce graphique (GPU)", Page.IA, "gpu acceleration vitesse"),
    SearchEntry("Importer un modèle .litertlm", Page.IA, "litertlm fichier modele gguf"),
    SearchEntry("Vitesse de lecture audio", Page.AUDIO, "voix ecouter vitesse tts"),
    SearchEntry("Voix du téléphone", Page.AUDIO, "voix tts synthese vocale langue"),
    SearchEntry("Sources suivies (notifications)", Page.NOTIFICATIONS, "notification avertir alerte"),
    SearchEntry("Importer un fichier OPML", Page.ABONNEMENTS, "opml import feedly inoreader"),
    SearchEntry("Exporter mes abonnements (OPML)", Page.ABONNEMENTS, "opml export sauvegarde"),
    SearchEntry("Version, vie privée, sources des données", Page.A_PROPOS, "version confidentialite licence"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onImportOpml: () -> Unit, onOpenTopics: () -> Unit = {}) {
    val vm: SettingsViewModel = viewModel(factory = containerFactory { SettingsViewModel(it) })
    val s by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()
    val feeds by vm.feeds.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val screenContext = LocalContext.current
    val customLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCustomModel(screenContext, uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-opml")) { uri ->
        if (uri != null) vm.exportOpml(screenContext, uri)
    }
    var pageName by rememberSaveable { mutableStateOf<String?>(null) }
    val page = pageName?.let { Page.valueOf(it) }
    var shownNotify by remember(pageName) { mutableStateOf(emptySet<Long>()) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page != null || searching) {
        when {
            searching -> { searching = false; query = "" }
            else -> pageName = null
        }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val selectedModel = (vm.models + customModels).firstOrNull { it.id == s.selectedModelId }

    GlassScreen(
        title = page?.title ?: "Réglages",
        onBack = when {
            searching -> ({ searching = false; query = "" })
            page != null -> ({ pageName = null })
            else -> null
        },
        snackbar = snackbar,
        titleContent = if (searching) {
            { SettingsSearchField(query) { query = it } }
        } else {
            null
        },
        actions = {
            if (page == null && !searching) {
                IconButton(onClick = { searching = true }) { Icon(Icons.Default.Search, "Rechercher un réglage") }
            }
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            when {
                searching -> {
                    val q = com.asawaaah.fil.ui.reader.ArticleSearch.fold(query.trim())
                    val results = if (q.length < 2) emptyList() else searchIndex.filter {
                        com.asawaaah.fil.ui.reader.ArticleSearch.fold(it.title + " " + it.words + " " + it.page.title).contains(q)
                    }
                    if (q.length >= 2 && results.isEmpty()) {
                        item { Hint("Aucun réglage ne correspond à « ${query.trim()} ».") }
                    }
                    items(results, key = { it.title }) { r ->
                        ListItem(
                            modifier = Modifier.clickable {
                                searching = false
                                query = ""
                                pageName = r.page.name
                            },
                            headlineContent = { Text(r.title) },
                            supportingContent = { Text(r.page.title) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        )
                    }
                }

                page == null -> {
                    val followed = feeds.count { it.feed.notify }
                    val menu = listOf(
                        Triple(Page.APPARENCE, Icons.Outlined.Palette, listOf(
                            when (s.theme) { ThemeMode.SYSTEM -> "Thème du système"; ThemeMode.LIGHT -> "Clair"; ThemeMode.DARK -> "Sombre" },
                            if (s.glass == GlassMode.LIQUID) "Liquid Glass" else "Barres opaques",
                            "texte ${(s.textScale * 100).toInt()} %",
                        ).joinToString(" · ")),
                        Triple(Page.FIL, Icons.AutoMirrored.Outlined.Article, listOf(
                            if (s.compactFeed) "Compact" else "Normal",
                            if (s.groupBySource) "regroupé par source" else "non regroupé",
                            when (s.refreshHours) { 0 -> "actualisation manuelle"; 1 -> "toutes les heures"; else -> "toutes les ${s.refreshHours} h" },
                        ).joinToString(" · ")),
                        Triple(Page.MASQUER, Icons.Outlined.VisibilityOff, listOfNotNull(
                            s.hiddenTopics.map { com.asawaaah.fil.data.topics.Topics.label(it) }.sorted().joinToString(", ").ifEmpty { null },
                            filters.size.takeIf { it > 0 }?.let { "$it mot${if (it > 1) "s" else ""}-clé${if (it > 1) "s" else ""}" },
                        ).joinToString(" · ").ifEmpty { "Rien de masqué" }),
                        Triple(Page.TRADUCTION, Icons.Default.Translate, listOf(
                            if (s.autoTranslate) "Automatique" else "À la demande",
                            if (s.aiTranslation) "par l'IA" else null,
                            "${languages.size} langue${if (languages.size > 1) "s" else ""}",
                        ).filterNotNull().joinToString(" · ")),
                        Triple(Page.IA, Icons.Default.AutoAwesome,
                            (selectedModel?.name ?: "Aucun modèle") + if (selectedModel != null && selectedModel.id in installed) " · installé" else " · à télécharger"),
                        Triple(Page.AUDIO, Icons.Outlined.Headphones, "Vitesse ${rateLabel(s.readAloudRate)}"),
                        Triple(Page.NOTIFICATIONS, Icons.Outlined.Notifications,
                            if (followed == 0) "Aucune source suivie" else "$followed source${if (followed > 1) "s" else ""} suivie${if (followed > 1) "s" else ""}"),
                        Triple(Page.ABONNEMENTS, Icons.Outlined.RssFeed, "${feeds.size} sources · importer / exporter OPML"),
                        Triple(Page.A_PROPOS, Icons.Outlined.Info, "Fil ${com.asawaaah.fil.BuildConfig.VERSION_NAME}"),
                    )
                    items(menu, key = { it.first.name }) { (p, icon, summary) ->
                        ListItem(
                            modifier = Modifier.clickable { pageName = p.name },
                            leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
                            headlineContent = { Text(p.title, style = MaterialTheme.typography.titleMedium) },
                            supportingContent = { Text(summary, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        )
                    }
                }

                page == Page.APPARENCE -> {
                    item { ThemeRow(s, vm) }
                    item { GlassRow(s, vm) }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("Taille du texte des articles : ${(s.textScale * 100).toInt()} %", style = MaterialTheme.typography.titleSmall)
                            Slider(value = s.textScale, onValueChange = vm::setTextScale, valueRange = 0.8f..1.6f, steps = 7)
                        }
                    }
                    item { SwitchRow("Police avec empattement", "Style livre pour la lecture des articles.", s.serif, vm::setSerif) }
                }

                page == Page.FIL -> {
                    item { ChoiceRow("Affichage", listOf(false to "Normal", true to "Compact"), s.compactFeed, vm::setCompactFeed) }
                    item {
                        SwitchRow(
                            "Regrouper par source",
                            "Dès 3 articles d'une même source dans la journée, ils forment une pile à déplier.",
                            s.groupBySource,
                            vm::setGroupBySource,
                        )
                    }
                    item {
                        ChoiceRow(
                            "Actualisation automatique",
                            listOf(0 to "Manuelle", 1 to "1 h", 3 to "3 h", 6 to "6 h", 12 to "12 h"),
                            s.refreshHours,
                            vm::setRefreshHours,
                        )
                    }
                    item {
                        ChoiceRow("Garder les articles", listOf(7 to "7 j", 30 to "30 j", 90 to "90 j", 365 to "1 an"), s.keepDays, vm::setKeepDays)
                    }
                    item { Hint("Les articles « À lire plus tard » sont toujours gardés.") }
                }

                page == Page.MASQUER -> {
                    item {
                        ListItem(
                            modifier = Modifier.clickable(onClick = onOpenTopics),
                            headlineContent = { Text("Sujets masqués") },
                            supportingContent = {
                                Text(
                                    if (s.hiddenTopics.isEmpty()) {
                                        "Aucun. Choisis les sujets à ne plus voir (le foot, les people…)."
                                    } else {
                                        s.hiddenTopics.map { com.asawaaah.fil.data.topics.Topics.label(it) }.sorted().joinToString(", ")
                                    },
                                )
                            },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        )
                    }
                    item { FiltersEditor(filters.map { it.id to it.keyword }, vm::addFilter, vm::removeFilter) }
                    item { Hint("Astuce : un appui long sur un article permet aussi de masquer son sujet.") }
                }

                page == Page.TRADUCTION -> {
                    item { SwitchRow("Traduire automatiquement en français", "Titres et extraits dans le fil, et articles à l'ouverture.", s.autoTranslate, vm::setAutoTranslate) }
                    item {
                        SwitchRow(
                            "Traduire les articles avec l'IA",
                            "Traduction bien plus naturelle, faite par le modèle de résumé, partie par partie " +
                                "(1 à 2 minutes par article). Les titres du fil restent traduits instantanément.",
                            s.aiTranslation,
                            vm::setAiTranslation,
                        )
                    }
                    item {
                        Advanced {
                            SwitchRow("Télécharger les langues seulement en Wi-Fi", "Chaque langue pèse environ 30 Mo.", s.translateWifiOnly, vm::setTranslateWifiOnly)
                            InstalledLanguages(languages, vm::deleteLanguage)
                        }
                    }
                }

                page == Page.IA -> {
                    item {
                        Hint(
                            "Le modèle tourne entièrement sur ton téléphone : aucun article n'est envoyé sur internet. " +
                                "Il se télécharge une seule fois.",
                        )
                    }
                    (vm.models + customModels).forEach { model ->
                        item(key = "model-${model.id}") {
                            ModelCard(vm, model, installed = model.id in installed, selected = s.selectedModelId == model.id)
                        }
                    }
                    item {
                        SwitchRow(
                            "Préparer les résumés pendant la recharge",
                            "Résume à l'avance les nouveaux articles quand le téléphone est branché.",
                            s.prepareSummaries,
                            vm::setPrepareSummaries,
                        )
                    }
                    item {
                        Advanced {
                            SwitchRow(
                                "Utiliser la puce graphique (GPU) · expérimental",
                                when {
                                    vm.gpuCrashed -> "Désactivé automatiquement (plantage ou démarrage trop lent). Réactive pour réessayer."
                                    vm.backendName != null -> "Moteur actuel : ${if (vm.backendName == "GPU") "puce graphique" else "processeur"}"
                                    else -> "Peut accélérer certains téléphones, mais le premier chargement peut être très long. " +
                                        "Par défaut, le processeur est utilisé."
                                },
                                s.useGpu && !vm.gpuCrashed,
                                vm::setUseGpu,
                            )
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                                TextButton(onClick = { customLauncher.launch(arrayOf("*/*")) }) { Text("Importer un autre modèle (.litertlm)…") }
                                Text(
                                    "Tout modèle au format LiteRT-LM, par exemple ceux de huggingface.co/litert-community, " +
                                        "copié sur le téléphone. Les fichiers GGUF et safetensors ne sont pas lisibles par ce moteur.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                page == Page.AUDIO -> {
                    item {
                        ChoiceRow(
                            "Vitesse de lecture par défaut",
                            listOf(0.8f, 1f, 1.25f, 1.5f, 2f).map { it to rateLabel(it) },
                            s.readAloudRate,
                            vm::setReadAloudRate,
                        )
                    }
                    item {
                        ListItem(
                            modifier = Modifier.clickable {
                                runCatching {
                                    screenContext.startActivity(
                                        android.content.Intent("com.android.settings.TTS_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure { vm.message.value = "Réglages vocaux introuvables sur ce téléphone" }
                            },
                            headlineContent = { Text("Voix du téléphone") },
                            supportingContent = { Text("Choisir le moteur vocal et installer des voix (réglages d'Android).") },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                        )
                    }
                    item {
                        Hint(
                            "L'article est lu tel qu'il est affiché : traduit si tu lis la traduction, sinon dans sa langue, " +
                                "avec la voix de cette langue. La lecture continue écran éteint.",
                        )
                    }
                }

                page == Page.NOTIFICATIONS -> {
                    item {
                        Hint(
                            when {
                                !vm.canNotify() -> "Les notifications de Fil sont désactivées dans Android. Active-les pour être averti."
                                s.refreshHours <= 0 -> "L'actualisation automatique est sur « Manuelle » (page Fil) : aucune notification ne partira."
                                else -> "Une notification quand l'actualisation automatique trouve de nouveaux articles de ces sources. " +
                                    "Pour en suivre une autre : ouvre sa fiche (appui long sur un article › Source) › « M'avertir »."
                            },
                        )
                    }
                    if (!vm.canNotify()) {
                        item {
                            TextButton(
                                onClick = {
                                    screenContext.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, screenContext.packageName)
                                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            ) { Text("Ouvrir les réglages de notification") }
                        }
                    }
                    // A source switched off stays listed until the page is left, so it can be switched back on.
                    val followedFeeds = feeds.filter { it.feed.notify || it.feed.id in shownNotify }
                    if (followedFeeds.isEmpty()) {
                        item { Hint("Aucune source suivie pour l'instant.") }
                    }
                    items(followedFeeds, key = { "notify-${it.feed.id}" }) { f ->
                        ListItem(
                            modifier = Modifier.toggleable(value = f.feed.notify, role = Role.Switch) {
                                shownNotify = shownNotify + f.feed.id
                                vm.setNotify(f.feed.id, it)
                            },
                            leadingContent = { com.asawaaah.fil.ui.common.SourceIcon(f.feed.iconUrl, f.feed.title, size = 28.dp) },
                            headlineContent = { Text(f.feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = { Switch(checked = f.feed.notify, onCheckedChange = null) },
                        )
                    }
                }

                page == Page.ABONNEMENTS -> {
                    item {
                        ListItem(
                            modifier = Modifier.clickable(onClick = onImportOpml),
                            leadingContent = { Icon(Icons.Default.FileUpload, null) },
                            headlineContent = { Text("Importer un fichier OPML") },
                            supportingContent = { Text("Depuis Feedly, Inoreader, NetNewsWire, Feeder…") },
                        )
                    }
                    item {
                        ListItem(
                            modifier = Modifier.clickable { exportLauncher.launch("fil-abonnements.opml") },
                            leadingContent = { Icon(Icons.Default.FileDownload, null) },
                            headlineContent = { Text("Exporter mes abonnements (OPML)") },
                            supportingContent = { Text("${feeds.size} sources, dossiers compris : une sauvegarde, ou pour changer d'app.") },
                        )
                    }
                }

                page == Page.A_PROPOS -> {
                    item {
                        Hint(
                            "Fil ${com.asawaaah.fil.BuildConfig.VERSION_NAME}\n\n" +
                                "Aucun compte, aucun serveur : tes abonnements, la traduction, les résumés et la lecture audio " +
                                "restent sur ce téléphone. Seuls les flux, les pages d'articles et les fichiers de modèles et de " +
                                "langues sont téléchargés.\n\n" +
                                "Propriétaires des médias : " + com.asawaaah.fil.data.MediaOwners.ATTRIBUTION,
                        )
                    }
                }
            }
        }
    }
}

private fun rateLabel(rate: Float): String =
    (if (rate % 1f == 0f) "${rate.toInt()}" else "$rate".replace('.', ',')) + " ×"

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Technical options, folded at the bottom of their page. */
@Composable
private fun Advanced(content: @Composable () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column {
        HorizontalDivider(Modifier.padding(top = 12.dp))
        ListItem(
            modifier = Modifier.clickable(onClickLabel = if (open) "Replier" else "Déplier") { open = !open },
            headlineContent = { Text("Avancé", style = MaterialTheme.typography.titleSmall) },
            trailingContent = { Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) },
        )
        if (open) content()
    }
}

@Composable
private fun InstalledLanguages(languages: List<String>, onDelete: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Langues installées", style = MaterialTheme.typography.titleSmall)
        if (languages.isEmpty()) {
            Text(
                "Aucune pour l'instant. Elles s'installent toutes seules quand un article étranger arrive.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            languages.forEach { code ->
                InputChip(
                    selected = false,
                    onClick = { onDelete(code) },
                    label = { Text(Languages.name(code)) },
                    trailingIcon = { Icon(Icons.Default.Close, "Supprimer") },
                )
            }
        }
    }
}

@Composable
private fun SettingsSearchField(query: String, onChange: (String) -> Unit) {
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
        modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = "Rechercher un réglage" },
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Rechercher un réglage…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}

@Composable
private fun Section(title: String) {
    Column {
        Spacer(Modifier.padding(top = 12.dp))
        HorizontalDivider()
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun ThemeRow(s: Settings, vm: SettingsViewModel) {
    ChoiceRow(
        "Thème",
        listOf(ThemeMode.SYSTEM to "Système", ThemeMode.LIGHT to "Clair", ThemeMode.DARK to "Sombre"),
        s.theme,
        vm::setTheme,
    )
}

@Composable
private fun GlassRow(s: Settings, vm: SettingsViewModel) {
    Column {
        ChoiceRow(
            "Barres flottantes",
            listOf(GlassMode.LIQUID to "Liquid Glass", GlassMode.OPAQUE to "Opaques"),
            s.glass,
            vm::setGlass,
        )
        Text(
            if (vm.highContrastText) {
                "Le texte à contraste élevé est activé sur ton téléphone : les barres restent opaques pour la lisibilité."
            } else {
                "« Opaques » rend les barres pleines, plus faciles à lire. Elles le deviennent aussi " +
                    "automatiquement si le texte à contraste élevé est activé dans l'accessibilité d'Android."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        // Chips wrap onto a new line with large text instead of cutting the labels.
        FlowRow(
            Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (v, label) ->
                val selected = value == v
                FilterChip(
                    selected = selected,
                    onClick = { onChange(v) },
                    label = { Text(label) },
                    leadingIcon = if (selected) {
                        { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
                    } else {
                        null
                    },
                    shape = CapsuleShape,
                    modifier = Modifier.semantics { role = Role.RadioButton },
                )
            }
        }
    }
}

@Composable
private fun FiltersEditor(filters: List<Pair<Long, String>>, onAdd: (String) -> Unit, onRemove: (Long) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Masquer les articles contenant…", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("un mot-clé") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { onAdd(text); text = "" }, enabled = text.isNotBlank()) { Text("Ajouter") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            filters.forEach { (id, keyword) ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(id) },
                    label = { Text(keyword) },
                    trailingIcon = { Icon(Icons.Default.Close, "Retirer") },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(vm: SettingsViewModel, model: LlmModel, installed: Boolean, selected: Boolean) {
    val context = LocalContext.current
    val downloadFlow = remember(model.id) { vm.downloadState(model) }
    val download by downloadFlow.collectAsStateWithLifecycle(DownloadState.None)
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.download(model) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importModel(context, uri, model)
    }
    var confirmMobile by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected && installed) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { vm.selectModel(model) })
                Column(Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${model.description} ${String.format(java.util.Locale.FRENCH, "%.1f", model.approxBytes / 1e9)} Go",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (model.npuChip != null) {
                val fallback = com.asawaaah.fil.ai.ModelCatalog.byId(model.fallbackId)
                Text(
                    when {
                        installed && vm.npuFailed ->
                            "N'a pas démarré sur ce téléphone : Fil utilise ${fallback.name} à la place."
                        else -> "Garde aussi ${fallback.name} installé : il sert de secours si la puce refuse."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (installed && vm.npuFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (installed && vm.npuFailed) TextButton(onClick = vm::retryNpu) { Text("Réessayer la puce Tensor") }
            }
            when {
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Installé", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.deleteModel(model) }) { Text("Supprimer") }
                }
                download is DownloadState.Running -> {
                    val d = download as DownloadState.Running
                    val fraction = if (d.total > 0) d.downloaded.toFloat() / d.total else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${d.downloaded / 1_000_000} Mo / ${d.total / 1_000_000} Mo",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { vm.cancelDownload(model) }) { Text("Annuler") }
                    }
                }
                download is DownloadState.WaitingForWifi -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("En attente du Wi-Fi…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.cancelDownload(model) }) { Text("Annuler") }
                    }
                    TextButton(onClick = { confirmMobile = true }) { Text("Utiliser les données mobiles") }
                }
                else -> {
                    (download as? DownloadState.Failed)?.let {
                        Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else vm.download(model)
                        }) { Text("Télécharger (Wi-Fi)") }
                        TextButton(onClick = { confirmMobile = true }) { Text("Avec les données mobiles") }
                        TextButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importer le fichier") }
                    }
                }
            }
        }
    }
    if (confirmMobile) {
        val size = String.format(java.util.Locale.FRENCH, "%.1f", model.approxBytes / 1e9)
        AlertDialog(
            onDismissRequest = { confirmMobile = false },
            title = { Text("Télécharger $size Go avec les données mobiles ?") },
            text = { Text("Ça compte dans ton forfait. Le téléchargement reprend là où il en est s'il est coupé.") },
            confirmButton = {
                TextButton(onClick = { confirmMobile = false; vm.download(model, allowMobileData = true) }) { Text("Télécharger") }
            },
            dismissButton = { TextButton(onClick = { confirmMobile = false }) { Text("Annuler") } },
        )
    }
}
