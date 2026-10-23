package com.asawaaah.fil.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onImportOpml: () -> Unit) {
    val vm: SettingsViewModel = viewModel(factory = containerFactory { SettingsViewModel(it) })
    val s by vm.settings.collectAsStateWithLifecycle()
    val installed by vm.installedModels.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    val languages by vm.languages.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val customModels by vm.customModels.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val screenContext = LocalContext.current
    val customLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importCustomModel(screenContext, uri)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    GlassScreen(title = "Réglages", snackbar = snackbar) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            item { Section("Résumés par IA, sur le téléphone") }
            item {
                Text(
                    "Le modèle tourne entièrement sur ton téléphone : aucun article n'est envoyé sur internet. " +
                        "Il se télécharge une seule fois, en Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            (vm.models + customModels).forEach { model ->
                item(key = "model-${model.id}") {
                    ModelCard(vm, model, installed = model.id in installed, selected = s.selectedModelId == model.id)
                }
            }
            item(key = "import-custom") {
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
            item {
                SwitchRow(
                    "Préparer les résumés pendant la recharge",
                    "Résume à l'avance les nouveaux articles quand le téléphone est branché.",
                    s.prepareSummaries,
                    vm::setPrepareSummaries,
                )
            }
            item {
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
            }

            item { Section("Traduction") }
            item {
                SwitchRow(
                    "Traduire automatiquement en français",
                    "Titres et extraits dans le fil, et articles à l'ouverture.",
                    s.autoTranslate,
                    vm::setAutoTranslate,
                )
            }
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
                SwitchRow(
                    "Télécharger les langues seulement en Wi-Fi",
                    "Chaque langue pèse environ 30 Mo.",
                    s.translateWifiOnly,
                    vm::setTranslateWifiOnly,
                )
            }
            item {
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
                                onClick = { vm.deleteLanguage(code) },
                                label = { Text(Languages.name(code)) },
                                trailingIcon = { Icon(Icons.Default.Close, "Supprimer") },
                            )
                        }
                    }
                }
            }

            item { Section("Lecture") }
            item { ThemeRow(s, vm) }
            item { GlassRow(s, vm) }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Taille du texte : ${(s.textScale * 100).toInt()} %", style = MaterialTheme.typography.titleSmall)
                    Slider(value = s.textScale, onValueChange = vm::setTextScale, valueRange = 0.8f..1.6f, steps = 7)
                }
            }
            item { SwitchRow("Police avec empattement", "Style livre pour la lecture des articles.", s.serif, vm::setSerif) }

            item { Section("Fil") }
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
                ChoiceRow(
                    "Garder les articles",
                    listOf(7 to "7 j", 30 to "30 j", 90 to "90 j", 365 to "1 an"),
                    s.keepDays,
                    vm::setKeepDays,
                )
            }
            item { FiltersEditor(filters.map { it.id to it.keyword }, vm::addFilter, vm::removeFilter) }

            item { Section("Abonnements") }
            item {
                ListItem(
                    headlineContent = { Text("Importer un fichier OPML") },
                    supportingContent = { Text("Depuis Feedly, Inoreader, NetNewsWire, Feeder…") },
                    modifier = Modifier.clickable(onClick = onImportOpml),
                )
            }
            item {
                Text(
                    "L'export OPML se trouve dans l'onglet Sources.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item { Section("À propos") }
            item {
                Text(
                    "Fil 0.1 · Aucun compte, aucun serveur : tes abonnements, la traduction et les résumés restent sur ce téléphone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
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
