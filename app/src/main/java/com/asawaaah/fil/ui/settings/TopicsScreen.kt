package com.asawaaah.fil.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.asawaaah.fil.AppContainer
import com.asawaaah.fil.data.settings.Settings
import com.asawaaah.fil.data.topics.Topic
import com.asawaaah.fil.data.topics.Topics
import com.asawaaah.fil.ui.common.containerFactory
import com.asawaaah.fil.ui.glass.GlassScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TopicsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<Settings> = c.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    /** Articles of the last 7 days per topic (a parent counts its sub-topics). */
    val counts = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        viewModelScope.launch {
            val since = System.currentTimeMillis() - 7 * 24 * 3600 * 1000L
            val out = HashMap<String, Int>()
            for (stored in c.db.articleDao().recentTopics(since)) {
                val ids = Topics.parse(stored)
                val withParents = ids + ids.mapNotNull { Topics.get(it)?.parent }
                withParents.toSet().forEach { out[it] = (out[it] ?: 0) + 1 }
            }
            counts.value = out
        }
    }

    fun setHidden(topic: String, hidden: Boolean) {
        viewModelScope.launch { c.settings.setTopicHidden(topic, hidden) }
    }
}

/** Topics to leave out of the feed, with how many articles each had over the last 7 days. */
@Composable
fun TopicsScreen(onBack: () -> Unit) {
    val vm: TopicsViewModel = viewModel(factory = containerFactory { TopicsViewModel(it) })
    val settings by vm.settings.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val hidden = settings.hiddenTopics

    GlassScreen(title = "Sujets masqués", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            item {
                Text(
                    "Les articles de ces sujets n'apparaissent plus dans le fil ni dans les dossiers. Ils restent sur la page " +
                        "de leur source, et un compteur en bas de chaque jour permet de les afficher. Le sujet est deviné " +
                        "sur le téléphone (rubrique de l'adresse, catégories du flux, mots du titre) : il peut se tromper.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(Topics.roots, key = { it.id }) { root ->
                TopicRow(root, counts[root.id] ?: 0, checked = root.id in hidden, enabled = true, indent = false) {
                    vm.setHidden(root.id, it)
                }
                Topics.children(root.id).forEach { child ->
                    // A hidden parent hides all its sub-topics.
                    val viaParent = root.id in hidden
                    TopicRow(child, counts[child.id] ?: 0, checked = viaParent || child.id in hidden, enabled = !viaParent, indent = true) {
                        vm.setHidden(child.id, it)
                    }
                }
            }
        }
    }
}

@Composable
private fun TopicRow(topic: Topic, count: Int, checked: Boolean, enabled: Boolean, indent: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .padding(start = if (indent) 32.dp else 0.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        headlineContent = { Text(topic.label) },
        supportingContent = {
            Text(
                when (count) {
                    0 -> "Aucun article cette semaine"
                    1 -> "1 article cette semaine"
                    else -> "$count articles cette semaine"
                },
            )
        },
    )
}
