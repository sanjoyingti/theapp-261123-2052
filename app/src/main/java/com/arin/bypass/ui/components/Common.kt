package com.arin.bypass.ui.components

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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.arin.bypass.model.Finding
import com.arin.bypass.model.LogLine
import com.arin.bypass.ui.theme.Mono
import com.arin.bypass.util.Format

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun EmptyState(title: String, hint: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun WorkProgress(text: String, progress: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ConfidenceChip(confidence: Int) {
    val scheme = MaterialTheme.colorScheme
    val background = when {
        confidence >= 75 -> scheme.error.copy(alpha = 0.18f)
        confidence >= 55 -> scheme.primary.copy(alpha = 0.18f)
        else -> scheme.surfaceVariant
    }
    val text = when {
        confidence >= 75 -> scheme.error
        confidence >= 55 -> scheme.primary
        else -> scheme.onSurfaceVariant
    }
    Surface(color = background, shape = RoundedCornerShape(6.dp)) {
        Text(
            "$confidence%",
            style = MaterialTheme.typography.labelSmall,
            color = text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun LogRow(line: LogLine) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            Format.time(line.time),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(6.dp))
        Text(
            line.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = Mono,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun DiffBlock(diff: String) {
    if (diff.isBlank()) {
        Text("Nothing to preview.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(8.dp)) {
            diff.lineSequence().forEach { line ->
                val colour = when {
                    line.startsWith("+++") || line.startsWith("---") -> MaterialTheme.colorScheme.onSurfaceVariant
                    line.startsWith("+") -> MaterialTheme.colorScheme.primary
                    line.startsWith("-") -> MaterialTheme.colorScheme.error
                    line.startsWith("@@") -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = Mono, color = colour)
            }
        }
    }
}

@Composable
fun CategoryTag(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(6.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
fun FindingHeader(finding: Finding) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ConfidenceChip(finding.confidence)
        Spacer(Modifier.width(8.dp))
        CategoryTag(finding.category.name.lowercase())
        Spacer(Modifier.width(8.dp))
        Text(finding.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "${finding.file}${if (finding.line > 0) ":${finding.line}" else ""}",
        style = MaterialTheme.typography.labelSmall,
        fontFamily = Mono,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(4.dp))
    Text(finding.detail, style = MaterialTheme.typography.bodySmall)
    if (finding.snippet.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(6.dp))
            Text(finding.snippet, style = MaterialTheme.typography.bodySmall, fontFamily = Mono)
        }
    }
}
