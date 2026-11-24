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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arin.bypass.service.SyncStarter
import com.arin.bypass.ui.AppShell
import com.arin.bypass.ui.theme.ArinTheme
import com.arin.bypass.util.CrashLog
import com.arin.bypass.util.Trouble

/**
 * The screen is mounted outside composition, so a throw while the first frame is built
 * can still be caught. The fallback card is mounted with a fresh composition and a
 * fresh set of view models, which is the only way to retry safely: a try block inside
 * composition is not allowed by the Compose compiler.
 */
class MainActivity : ComponentActivity() {

    private var askedOnce = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSyncIfAllowed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mount()
        askThenSync()
    }

    /** Draws the real app. The composition runs inside this call, so its failure is catchable. */
    private fun mount() {
        val app = application as? BypassApp
        try {
            setContent {
                ArinTheme {
                    val container = remember { app?.container }
                    if (container != null) {
                        AppShell(container)
                    } else {
                        MissingContainerCard(onRetry = { showProblem(null) })
                    }
                }
            }
        } catch (t: Throwable) {
            showProblem(t)
        }
    }

    /** Mounts the reason card. Never throws again, so the activity cannot die here. */
    private fun showProblem(cause: Throwable?) {
        if (cause != null) CrashLog.write("screen composition", cause)
        runCatching { viewModelStore.clear() }
            .onFailure { CrashLog.write("view model reset", it) }
        val message = if (cause == null) {
            "Trying again now."
        } else {
            Trouble.plain(cause)
        }
        try {
            setContent {
                ArinTheme {
                    GuardCard(message = message, onRetry = { showProblem(null) })
                }
            }
        } catch (t: Throwable) {
            CrashLog.write("error card", t)
        }
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
        // no notification permission means no foreground notice: skip instead of throwing
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
                "If it keeps failing, the reason is written to the crash report file in the app folder.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun MissingContainerCard(onRetry: () -> Unit) {
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
                "Shared objects are built lazily, so pressing Retry usually clears this.",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}
