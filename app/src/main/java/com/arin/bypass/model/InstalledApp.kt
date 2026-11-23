package com.arin.bypass.model

/** One app that is already installed on the phone and can be pulled in as a project. */
data class InstalledApp(
    val label: String,
    val packageName: String,
    val versionName: String,
    val isSystem: Boolean
)
