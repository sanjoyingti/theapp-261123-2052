package com.arin.bypass.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arin.bypass.util.CrashLog

/**
 * Keeps a broken screen from being a broken app: a throw during composition becomes a
 * card with the reason and a Retry button, and the rest of the app keeps working.
 */
@Composable
fun Boundary(content: @Composable () -> Unit) {
    var attempt by remember { mutableIntStateOf(0) }
    var failure by remember { mutableStateOf<String?>(null) }

    runCatching { }
    key(attempt) {
        if (failure == null) {
            try {
                content()
            } catch (t: Throwable) {
                runCatching { CrashLog.write("screen boundary", t) }
                failure = t.message?.ifBlank { null } ?: (t::class.java.simpleName)
            }
        }
    }

    val message = failure
    if (message != null) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("The bypass tab stopped", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The rest of the app still works. This is what it said:",
                    style = MaterialTheme.typography.bodySmall
                )
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        message,
                        Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                }
                Button(onClick = {
                    failure = null
                    attempt++
                }) { Text("Retry") }
            }
        }
    }
}
