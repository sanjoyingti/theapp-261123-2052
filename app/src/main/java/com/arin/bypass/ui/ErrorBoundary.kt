package com.arin.bypass.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arin.bypass.util.CrashLog
import com.arin.bypass.util.CrashTail
import com.arin.bypass.util.Trouble

/**
 * Draws its content through a sub-layout, which is the one place a drawing failure can
 * actually be caught: a plain try block inside a composable body is refused by the
 * Compose compiler, but a measure lambda is an ordinary function. If drawing throws, a
 * card with the reason and a Retry button replaces the content and the rest of Arin keeps
 * working. Every failure is written to the crash file before the card appears.
 */
@Composable
fun Boundary(content: @Composable () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<Throwable?>(null) }
    var saved by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val current = failure

    if (current == null) {
        SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
            val placeables = try {
                subcompose(attempt) { content() }.map { it.measure(constraints) }
            } catch (t: Throwable) {
                CrashLog.write("screen draw", t)
                failure = t
                saved = CrashTail.read(context)
                emptyList()
            }
            val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
            val height = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
            layout(width, height) {
                placeables.forEach { it.placeRelative(0, 0) }
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    Modifier
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("This screen could not be drawn", style = MaterialTheme.typography.titleMedium)
                    Text(Trouble.plain(current), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The rest of Arin still works. Press Retry to draw the screen again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    val extra = saved
                    if (extra != null) {
                        Text("Last saved crash", style = MaterialTheme.typography.labelSmall)
                        Text(
                            extra,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            failure = null
                            saved = null
                            attempt++
                        }) { Text("Retry") }
                    }
                }
            }
        }
    }
}
