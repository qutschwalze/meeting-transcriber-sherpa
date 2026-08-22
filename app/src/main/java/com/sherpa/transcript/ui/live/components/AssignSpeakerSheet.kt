package com.sherpa.transcript.ui.live.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sherpa.transcript.domain.model.TranscriptSegment
import com.sherpa.transcript.ui.live.SpeakerProfileUi

/**
 * Phase 7a (0.7.2): Zuweisungs-Sheet nach dem Stoppen einer Aufnahme.
 * - Bekannte Profile: Tap = Stimme dem Profil zuordnen (rolling Enroll)
 * - "Neuer Kontakt": Name eingeben → neues Profil anlegen + benennen
 */
@Composable
fun AssignSpeakerSheet(
    segment: TranscriptSegment,
    profiles: List<SpeakerProfileUi>,
    onAssign: (segmentId: String, profileId: String?, newName: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var newName by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            text = "Sprecher zuweisen",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = "„${segment.text.take(80)}${if (segment.text.length > 80) "…" else ""}“",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (profiles.isNotEmpty()) {
            Text(
                text = "Bekannte Profile",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
            )
            profiles.forEach { profile ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onAssign(segment.segmentId, profile.id, null)
                        }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = profile.name ?: "Profil ${profile.id.takeLast(8)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "(${profile.sampleCount})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Neuer Kontakt – Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                if (newName.isNotBlank()) {
                    onAssign(segment.segmentId, null, newName.trim())
                }
            },
            enabled = newName.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Als neuen Kontakt anlegen")
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            Text("Abbrechen")
        }
    }
}