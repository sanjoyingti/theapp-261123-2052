package com.arin.bypass.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
    private val portKey = intPreferencesKey("mock_port")
    private val lastProjectKey = stringPreferencesKey("last_project")

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

    /** The port the built-in mock server listens on, remembered between visits. */
    suspend fun port(fallback: Int = 8899): Int =
        context.settingsStore.data.map { prefs -> prefs[portKey] ?: fallback }.first()

    suspend fun setPort(value: Int) = edit { it[portKey] = value.coerceIn(1024, 65535) }

    /** The project that was open last, so the tab reopens where the person left off. */
    suspend fun lastProject(): String? =
        context.settingsStore.data.map { prefs -> prefs[lastProjectKey] }.first()

    suspend fun setLastProject(id: String) = edit { it[lastProjectKey] = id }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsStore.edit { block(it) }
    }
}
