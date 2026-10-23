package com.asawaaah.fil.ui.importopml

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asawaaah.fil.AppContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import com.asawaaah.fil.data.feeds.FailureKind
import com.asawaaah.fil.ui.glass.GlassScreen
import com.asawaaah.fil.ui.glass.glassSurface
import com.asawaaah.fil.data.feeds.ImportFailure
import com.asawaaah.fil.data.feeds.ImportRepair
import com.asawaaah.fil.data.feeds.ImportState
import com.asawaaah.fil.data.feeds.OpmlFeed
import com.asawaaah.fil.data.feeds.Urls
import com.asawaaah.fil.ui.common.containerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ImportViewModel(private val c: AppContainer) : ViewModel() {
    val state: StateFlow<ImportState> = c.importManager.state

    /** Selected feed URLs in the preview. */
    val selected = MutableStateFlow<Set<String>>(emptySet())
    private var preselectedFor: Any? = null

    init {
        viewModelScope.launch {
            state.collect { s ->
                if (s is ImportState.Preview && preselectedFor !== s) {
                    preselectedFor = s
                    selected.value = s.document.feeds
                        .filter { Urls.normalize(it.xmlUrl) !in s.alreadyFollowed }
                        .map { it.xmlUrl }
                        .toSet()
                }
            }
        }
    }

    fun pick(context: Context, uri: Uri) {
        val resolver = context.applicationContext.contentResolver
        c.importManager.load { withContext(Dispatchers.IO) { readUri(resolver, uri) } }
    }

    fun toggle(url: String) {
        selected.value = selected.value.let { if (url in it) it - url else it + url }
    }

    fun setAll(urls: Collection<String>, on: Boolean) {
        selected.value = if (on) selected.value + urls else selected.value - urls.toSet()
    }

    fun start() {
        val preview = state.value as? ImportState.Preview ?: return
        val chosen = preview.document.feeds.filter { it.xmlUrl in selected.value }
        c.importManager.start(chosen, preview.document.duplicatesInFile)
    }

    fun retry() = c.importManager.retryFailed()
    fun removeFailed(kind: FailureKind) = c.importManager.removeFailed(kind)
    fun removeRepaired(repair: ImportRepair) = c.importManager.removeRepaired(repair)
    fun reset() = c.importManager.reset()

    companion object {
        fun readUri(resolver: android.content.ContentResolver, uri: Uri): Pair<ByteArray, String?> {
            val name = runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment
            val bytes = resolver.openInputStream(uri)?.use { input ->
                val data = input.readBytes()
                require(data.size <= 20 * 1024 * 1024) { "Fichier trop volumineux pour un OPML" }
                data
            } ?: error("Impossible d'ouvrir ce fichier")
            return bytes to name
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val vm: ImportViewModel = viewModel(factory = containerFactory { ImportViewModel(it) })
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.pick(context, uri)
    }
    val pickFile = { picker.launch(arrayOf("*/*")) }

    val isPreview = state is ImportState.Preview
    GlassScreen(
        title = "Importer des abonnements",
        onBack = onBack,
        floating = { backdrop, bottom ->
            if (isPreview) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottom + 8.dp)
                        .fillMaxWidth()
                        .glassSurface(backdrop, RoundedCornerShape(32.dp))
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = { vm.reset() }) { Text("Annuler") }
                    Button(onClick = vm::start, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Text("Importer ${selected.size} source${if (selected.size > 1) "s" else ""}")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + if (isPreview) 84.dp else 0.dp),
        ) {
            when (val s = state) {
                ImportState.Idle -> Intro(pickFile)
                ImportState.Reading -> Centered { CircularProgressIndicator(); Text("Lecture du fichier…") }
                is ImportState.ParseError -> Centered {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                    Text("Impossible de lire ce fichier", style = MaterialTheme.typography.titleMedium)
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = pickFile) { Text("Choisir un autre fichier") }
                }
                is ImportState.Preview -> Preview(s, selected, vm)
                is ImportState.Running -> Centered {
                    Text("Ajout des sources…", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${s.done} / ${s.total} vérifiées · ${s.added} ajoutées · ${s.failed} en erreur")
                    Text(
                        "Tu peux quitter cet écran : l'import continue en arrière-plan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is ImportState.Finished -> Report(s, vm, onDone = { vm.reset(); onDone() })
            }
        }
    }
}

@Composable
private fun Intro(pickFile: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Récupère tous tes abonnements d'un coup", style = MaterialTheme.typography.titleLarge)
        Text(
            "Exporte un fichier OPML depuis ton ancien lecteur (Feedly, Inoreader, NetNewsWire, Feeder, Reeder…), " +
                "puis choisis-le ici. Les dossiers seront recréés et les doublons ignorés.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "Astuce : tu peux aussi toucher un fichier .opml dans ton gestionnaire de fichiers ou tes e-mails, " +
                "puis choisir « Importer dans Fil ».",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = pickFile) { Text("Choisir un fichier OPML") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun Preview(s: ImportState.Preview, selected: Set<String>, vm: ImportViewModel) {
    val feeds = s.document.feeds
    val groups: List<Pair<String?, List<OpmlFeed>>> = feeds.groupBy { it.folder }.toList()
        .sortedWith(compareBy<Pair<String?, List<OpmlFeed>>> { it.first == null }.thenBy { it.first?.lowercase() })
    val followed = feeds.filter { Urls.normalize(it.xmlUrl) in s.alreadyFollowed }.map { it.xmlUrl }.toSet()
    val folderCount = groups.count { it.first != null }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.document.title ?: s.sourceName ?: "Fichier OPML", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("${feeds.size} source${if (feeds.size > 1) "s" else ""}")
                        if (folderCount > 0) append(" dans $folderCount dossier${if (folderCount > 1) "s" else ""}")
                        if (followed.isNotEmpty()) append(" · ${followed.size} déjà suivie${if (followed.size > 1) "s" else ""}")
                        if (s.document.duplicatesInFile > 0) append(" · ${s.document.duplicatesInFile} doublon(s) dans le fichier")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row {
                    val importable = feeds.map { it.xmlUrl }.filter { it !in followed }
                    TextButton(onClick = { vm.setAll(importable, true) }) { Text("Tout cocher") }
                    TextButton(onClick = { vm.setAll(importable, false) }) { Text("Tout décocher") }
                }
            }
        }
        groups.forEach { (folder, list) ->
            item(key = "g-$folder") {
                val urls = list.map { it.xmlUrl }.filter { it !in followed }
                val allOn = urls.isNotEmpty() && urls.all { it in selected }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = urls.isNotEmpty()) { vm.setAll(urls, !allOn) }.padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = allOn, onCheckedChange = { vm.setAll(urls, it) }, enabled = urls.isNotEmpty())
                    Text(
                        (folder ?: "Sans dossier").uppercase() + "  (${list.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                HorizontalDivider()
            }
            items(list, key = { "f-${it.xmlUrl}" }) { feed ->
                val isFollowed = feed.xmlUrl in followed
                ListItem(
                    modifier = Modifier.clickable(enabled = !isFollowed) { vm.toggle(feed.xmlUrl) },
                    leadingContent = {
                        Checkbox(checked = !isFollowed && feed.xmlUrl in selected, onCheckedChange = { vm.toggle(feed.xmlUrl) }, enabled = !isFollowed)
                    },
                    headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            if (isFollowed) "Déjà suivie" else (Urls.host(feed.xmlUrl) ?: feed.xmlUrl),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun Report(s: ImportState.Finished, vm: ImportViewModel, onDone: () -> Unit) {
    val r = s.report
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.CheckCircle, null)
                        Text("Import terminé", style = MaterialTheme.typography.titleMedium)
                    }
                    Text("✓ ${plural(r.added, "source ajoutée", "sources ajoutées")}")
                    val fixed = r.repairs.size
                    if (fixed > 0) Text("✎ ${plural(fixed, "adresse corrigée", "adresses corrigées")} automatiquement")
                    if (r.duplicates > 0) Text("↺ ${plural(r.duplicates, "doublon ignoré", "doublons ignorés")}")
                    if (r.failures.isNotEmpty()) Text("⚠ ${plural(r.failures.size, "source en échec", "sources en échec")}, détail ci-dessous")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone) { Text("Voir mon fil") }
                if (r.failures.isNotEmpty()) {
                    OutlinedButton(onClick = vm::retry, enabled = !s.retrying) {
                        Text(if (s.retrying) "Nouvel essai…" else "Réessayer les échecs")
                    }
                }
            }
        }

        val otherSites = r.repairs.filter { it.otherSite }
        if (otherSites.isNotEmpty()) {
            item(key = "h-other") {
                Section(
                    title = "À vérifier : le flux est maintenant sur un autre site (${otherSites.size})",
                    text = "Le site a changé d'adresse ou de propriétaire. Garde la source si c'est bien elle, sinon supprime-la.",
                )
            }
            items(otherSites, key = { "o${it.feedId}" }) { rep -> RepairRow(rep, onRemove = { vm.removeRepaired(rep) }) }
        }
        val sameSite = r.repairs.filter { !it.otherSite }
        if (sameSite.isNotEmpty()) {
            item(key = "h-fixed") {
                ExpandableSection(
                    title = "Adresses corrigées (${sameSite.size})",
                    text = "Le flux avait changé d'adresse sur le même site : c'est réglé.",
                ) { sameSite.forEach { RepairRow(it, onRemove = null) } }
            }
        }

        val groups = listOf(
            Triple(FailureKind.GONE, "Disparues", "Le site ou le flux n'existe plus (domaine abandonné, flux supprimé, plateforme fermée)."),
            Triple(FailureKind.BLOCKED, "Bloquées par le site", "Ces sites refusent les lecteurs RSS. Fil ne peut rien y faire."),
            Triple(FailureKind.UNAVAILABLE, "Indisponibles pour l'instant", "Erreur du serveur, délai dépassé ou certificat : Fil réessaiera à chaque actualisation."),
        )
        for ((kind, title, text) in groups) {
            val list = r.failuresOf(kind)
            if (list.isEmpty()) continue
            item(key = "f-$kind") {
                ExpandableSection(
                    title = "$title (${list.size})",
                    text = text,
                    action = if (kind == FailureKind.UNAVAILABLE) null else {
                        "Supprimer ces ${list.size} sources" to { vm.removeFailed(kind) }
                    },
                ) { list.forEach { FailureRow(it) } }
            }
        }
    }
}

private fun plural(n: Int, one: String, many: String) = "$n ${if (n > 1) many else one}"

@Composable
private fun Section(title: String, text: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    text: String,
    action: Pair<String, () -> Unit>? = null,
    content: @Composable () -> Unit,
) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, if (open) "Replier" else "Déplier")
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        if (open) content()
    }
}

@Composable
private fun FailureRow(f: ImportFailure) {
    ListItem(
        leadingContent = { Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error) },
        headlineContent = { Text(f.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(f.reason, color = MaterialTheme.colorScheme.error)
                Text(f.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
    )
}

@Composable
private fun RepairRow(rep: ImportRepair, onRemove: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(rep.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text("Avant : ${rep.oldUrl}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "Maintenant : ${rep.newUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (rep.otherSite) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = onRemove?.let { remove ->
            { IconButton(onClick = remove) { Icon(Icons.Default.Delete, "Supprimer cette source") } }
        },
    )
}
