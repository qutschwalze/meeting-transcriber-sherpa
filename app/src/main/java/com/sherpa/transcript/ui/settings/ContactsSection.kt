package com.sherpa.transcript.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sherpa.transcript.data.local.SpeakerProfile
import com.sherpa.transcript.engine.SpeakerProfiles

/**
 * Phase 7a (0.7.2): Kontakte-Sektion im Einstellungs-Screen.
 * Profile umbenennen, zusammenführen und löschen – arbeitet auf der
 * ZENTRALEN SpeakerProfiles-Instanz (identisch zur Live-Ansicht) und
 * persistiert sofort.
 */
@Composable
fun ContactsSection() {
    var profiles by remember {
        mutableStateOf(SpeakerProfiles.ensureBank().snapshot())
    }
    var renameTarget by remember { mutableStateOf<SpeakerProfile?>(null) }
    var deleteTarget by remember { mutableStateOf<SpeakerProfile?>(null) }
    var mergeSource by remember { mutableStateOf<SpeakerProfile?>(null) }
    var renameInput by remember { mutableStateOf("") }

    fun refresh() {
        profiles = SpeakerProfiles.ensureBank().snapshot()
    }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Kontakte (Speaker-Profile)",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "Erkannte Stimmen über alle Aufnahmen. Benannte Profile erscheinen als Name im Transkript und Export.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (profiles.isEmpty()) {
            Text(
                text = "Noch keine Profile – sie entstehen automatisch nach jeder Aufnahme.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        profiles.forEach { profile ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name ?: "Profil ${profile.id.takeLast(8)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "${profile.sampleCount} Kontakt(e)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    renameTarget = profile
                    renameInput = profile.name ?: ""
                }) {
                    Icon(Icons.Default.Edit, contentDescription = "Umbenennen")
                }
                IconButton(onClick = { mergeSource = profile }) {
                    Icon(Icons.Default.Merge, contentDescription = "Zusammenführen")
                }
                IconButton(onClick = { deleteTarget = profile }) {
                    Icon(Icons.Default.Delete, contentDescription = "Löschen")
                }
            }
        }
    }

    // ── Umbenennen-Dialog ──
    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Profil umbenennen") },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    singleLine = true,
                    label = { Text("Name (leer = Sprecher N)") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    SpeakerProfiles.ensureBank().rename(target.id, renameInput)
                    SpeakerProfiles.save()
                    renameTarget = null
                    refresh()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Abbrechen") }
            },
        )
    }

    // ── Löschen-Dialog ──
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Profil löschen?") },
            text = { Text("Profil '${target.name ?: target.id.takeLast(8)}' wird dauerhaft entfernt.") },
            confirmButton = {
                TextButton(onClick = {
                    SpeakerProfiles.ensureBank().deleteProfile(target.id)
                    SpeakerProfiles.save()
                    deleteTarget = null
                    refresh()
                }) { Text("Löschen") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Abbrechen") }
            },
        )
    }

    // ── Zusammenführen-Dialog: Quelle → Ziel ──
    mergeSource?.let { source ->
        val others = profiles.filter { it.id != source.id }
        AlertDialog(
            onDismissRequest = { mergeSource = null },
            title = { Text("Zusammenführen mit …") },
            text = {
                Column {
                    Text("Profil '${source.name ?: source.id.takeLast(8)}' wird hinein gemerged:")
                    others.forEach { target ->
                        Text(
                            text = target.name ?: "Profil ${target.id.takeLast(8)}",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    SpeakerProfiles.ensureBank().mergeProfiles(fromId = source.id, intoId = target.id)
                                    SpeakerProfiles.save()
                                    mergeSource = null
                                    refresh()
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                    if (others.isEmpty()) {
                        Text("Keine anderen Profile vorhanden.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { mergeSource = null }) { Text("Abbrechen") }
            },
        )
    }
}