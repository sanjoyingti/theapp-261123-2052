package com.arin.bypass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arin.bypass.service.SyncStarter
import com.arin.bypass.ui.AppShell
import com.arin.bypass.ui.theme.ArinTheme
import com.arin.bypass.util.CrashLog
import com.arin.bypass.util.Trouble

class MainActivity : ComponentActivity() {

    private var askedOnce = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSyncIfAllowed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? BypassApp
        setContent {
            ArinTheme {
                LaunchGuard {
                    val container = remember { app?.container }
                    if (container != null) AppShell(container) else MissingContainerCard()
                }
            }
        }
        askThenSync()
    }

    private fun askThenSync() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            if (askedOnce) return
            askedOnce = true
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { CrashLog.write("notification request", it) }
            return
        }
        startSyncIfAllowed()
    }

    private fun startSyncIfAllowed() {
        // no notification permission means no foreground notification: skip instead of throwing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            CrashLog.write("sync start skipped: notifications not granted")
            return
        }
        runCatching { SyncStarter.start(this) }
            .onFailure { CrashLog.write("sync start", it) }
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Error boundary for the whole screen. If building it throws, a card with the plain
 * reason and a Retry button takes its place while the rest of Arin keeps working.
 */
@Composable
private fun LaunchGuard(content: @Composable () -> Unit) {
    var attempt by remember { mutableStateOf(0) }
    var problem by remember { mutableStateOf<String?>(null) }

    key(attempt) {
        val message = problem
        if (message == null) {
            val thrown = runCatching { content() }.exceptionOrNull()
            if (thrown != null) {
                CrashLog.write("screen composition", thrown)
                problem = Trouble.plain(thrown)
            }
        } else {
            GuardCard(message = message, onRetry = {
                problem = null
                attempt += 1
            })
        }
    }
}

@Composable
private fun GuardCard(message: String, onRetry: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("This screen could not be drawn", style = MaterialTheme.typography.titleMedium)
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "The rest of Arin still works. Press Retry to build the screen again.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Button(onClick = onRetry) { Text("Retry") }
            Text(
                "If it keeps failing, open the crash report from the settings screen.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MissingContainerCard() {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Arin could not prepare its tools", style = MaterialTheme.typography.titleMedium)
            Text(
                "The application object was not the expected one, so the screens have nothing to draw with.",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "Shared objects are still built lazily, so closing and reopening the app usually clears this.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}
