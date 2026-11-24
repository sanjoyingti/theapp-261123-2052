package com.arin.bypass

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import com.arin.bypass.service.SyncStarter
import com.arin.bypass.ui.AppShell
import com.arin.bypass.ui.Boundary
import com.arin.bypass.ui.MissingAppCard
import com.arin.bypass.ui.theme.ArinTheme
import com.arin.bypass.util.CrashLog

class MainActivity : ComponentActivity() {

    private var askedOnce = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startSyncQuietly()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? BypassApp
        setContent {
            ArinTheme {
                Boundary {
                    val container = remember { app?.container }
                    if (container != null) AppShell(container) else MissingAppCard()
                }
            }
        }
        askForNotificationsThenSync()
    }

    private fun askForNotificationsThenSync() {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needed) {
            if (askedOnce) return
            askedOnce = true
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
                .onFailure { CrashLog.write("notification request", it) }
            return
        }
        startSyncQuietly()
    }

    private fun startSyncQuietly() {
        runCatching { SyncStarter.start(this) }
            .onFailure { CrashLog.write("sync start", it) }
    }
}
