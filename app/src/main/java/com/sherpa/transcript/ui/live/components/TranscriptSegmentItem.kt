package com.sherpa.transcript.ui.live.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherpa.transcript.domain.model.TranscriptSegment

/**
 * Ein einzelnes Transkriptsegment.
 * - Finale Segmente: normales Gewicht
 * - Nicht-finale Segmente: kursiv, heller
 * - Neue Segmente: farblich markiert (grüner linker Rand)
 */
@Composable
fun TranscriptSegmentItem(
    segment: TranscriptSegment,
    fontSize: Float,
    isLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = if (isLatest && segment.isNew) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
    } else {
        Color.Transparent
    }

    val textColor = if (segment.isFinal) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    }

    val fontStyle = if (!segment.isFinal) FontStyle.Italic else FontStyle.Normal

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Farbbalken für neues Segment
        if (segment.isNew) {
            Spacer(
                modifier = Modifier
                    .width(4.dp)
                    .height(24.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        Text(
            text = segment.text,
            fontSize = fontSize.sp,
            fontStyle = fontStyle,
            fontWeight = if (segment.isFinal) FontWeight.Normal else FontWeight.Light,
            color = textColor,
            lineHeight = fontSize.sp * 1.5f,
        )
    }
}
