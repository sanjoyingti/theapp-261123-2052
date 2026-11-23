package com.arin.bypass.model

data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    val isSystem: Boolean
)
