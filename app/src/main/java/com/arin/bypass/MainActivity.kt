package com.arin.bypass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import com.arin.bypass.ui.AppShell
import com.arin.bypass.ui.theme.ArinTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as BypassApp).container
        setContent {
            ArinTheme {
                val shell = remember { container }
                AppShell(shell)
            }
        }
    }
}
