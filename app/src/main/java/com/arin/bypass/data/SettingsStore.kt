package com.arin.bypass.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.arin.bypass.model.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore("bypass_settings")

class SettingsStore(private val context: Context) {

    private val dryRunKey = booleanPreferencesKey("dry_run")
    private val renameKey = booleanPreferencesKey("rename_clones")
    private val scanKey = booleanPreferencesKey("auto_scan")
    private val roundsKey = intPreferencesKey("round_limit")
    private val toolsKey = booleanPreferencesKey("toolchain_ready")

    suspend fun read(): Settings =
        context.settingsStore.data.map { prefs ->
            Settings(
                dryRun = prefs[dryRunKey] ?: true,
                renameClones = prefs[renameKey] ?: true,
                autoScan = prefs[scanKey] ?: true,
                roundLimit = prefs[roundsKey] ?: 6,
                toolchainReady = prefs[toolsKey] ?: false
            )
        }.first()

    suspend fun setDryRun(value: Boolean) = edit { it[dryRunKey] = value }

    suspend fun setRenameClones(value: Boolean) = edit { it[renameKey] = value }

    suspend fun setAutoScan(value: Boolean) = edit { it[scanKey] = value }

    suspend fun setRoundLimit(value: Int) = edit { it[roundsKey] = value.coerceIn(1, 6) }

    suspend fun setToolchainReady(value: Boolean) = edit { it[toolsKey] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
