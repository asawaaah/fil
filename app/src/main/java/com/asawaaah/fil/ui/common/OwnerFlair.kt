package com.asawaaah.fil.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.asawaaah.fil.data.MediaOwners
import com.asawaaah.fil.data.MediaOwnership
import com.asawaaah.fil.ui.glass.CapsuleShape

/** "Propriétaire : Xavier Niel +1" label; touching it shows the ownership chain and the source of the data. */
@Composable
fun OwnerFlair(ownership: MediaOwnership, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val names = ownership.owners.joinToString(", ") { it.name }
    AssistChip(
        onClick = { open = true },
        label = { Text("Propriétaire : ${ownership.shortLabel}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Outlined.AccountBalance, null, Modifier.size(AssistChipDefaults.IconSize)) },
        shape = CapsuleShape,
        modifier = modifier.semantics { contentDescription = "${ownership.media} appartient à $names. Toucher pour le détail." },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Qui possède ${ownership.media} ?") },
            text = {
                Column(
                    Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        (if (ownership.owners.size > 1) "Propriétaires finaux : " else "Propriétaire final : ") + names,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (ownership.chains.isNotEmpty()) {
                        Text("Par l'intermédiaire de :", style = MaterialTheme.typography.labelLarge)
                        ownership.chains.forEach { chain ->
                            Text(chain, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!ownership.recent) {
                        Text(
                            "Donnée de 2021, peut-être plus à jour.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    HorizontalDivider(Modifier.padding(top = 4.dp))
                    Text(MediaOwners.ATTRIBUTION, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Fermer") } },
        )
    }
}
