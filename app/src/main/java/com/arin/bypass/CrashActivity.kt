package com.arin.bypass

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.arin.bypass.ui.theme.ArinTheme
import com.arin.bypass.util.CrashLog

/** Shown after a crash. The trace is already on disk before this screen exists. */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashLog.lastCrash(this) ?: "No crash text was saved this time."
        setContent {
            ArinTheme {
                Surface(Modifier.fillMaxSize()) {
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(scroll)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Arin closed unexpectedly", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "This text was saved before the screen closed. Copy it if you want it looked at.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Card(Modifier.fillMaxWidth()) {
                            Text(
                                report,
                                Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { copy(report) }) { Text("Copy") }
                            OutlinedButton(onClick = { restart() }) { Text("Open again") }
                        }
                        OutlinedButton(onClick = {
                            CrashLog.clear(this@CrashActivity)
                            finish()
                        }) { Text("Clear the saved reports") }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    private fun copy(text: String) {
        val manager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { manager.setPrimaryClip(ClipData.newPlainText("crash", text)) }
    }

    private fun restart() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        runCatching { startActivity(intent) }
        finish()
    }
}
