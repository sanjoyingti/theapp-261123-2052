package com.arin.bypass.data

import android.content.Context
import com.arin.bypass.engine.Actions
import com.arin.bypass.engine.Billing
import com.arin.bypass.engine.Decoder
import com.arin.bypass.engine.Installer
import com.arin.bypass.engine.Intake
import com.arin.bypass.engine.Integrity
import com.arin.bypass.engine.ManifestRepair
import com.arin.bypass.engine.MockServer
import com.arin.bypass.engine.Patcher
import com.arin.bypass.engine.Pipeline
import com.arin.bypass.engine.Rebuilder
import com.arin.bypass.engine.Renamer
import com.arin.bypass.engine.RepairLoop
import com.arin.bypass.engine.Report
import com.arin.bypass.engine.RoundMonitor
import com.arin.bypass.engine.Scanner
import com.arin.bypass.engine.ScreenProbe
import com.arin.bypass.engine.Toolchain
import com.arin.bypass.service.WatcherSetup

/**
 * Hand wired dependencies. The constructor only keeps the context, so building this
 * class can never touch disk, preferences or a service.
 */
class AppContainer(val context: Context) {

    val settings by lazy { SettingsStore(context) }
    val projects by lazy { ProjectStore(context) }
    val bypass by lazy { BypassStore(context) }
    val toolchain by lazy { Toolchain(context) }
    val patcher by lazy { Patcher(projects) }
    val scanner by lazy { Scanner() }
    val integrity by lazy { Integrity(context, patcher) }
    val billing by lazy { Billing(patcher) }
    val actions by lazy { Actions(patcher) }
    val renamer by lazy { Renamer(patcher) }
    val repairs by lazy { ManifestRepair(patcher) }
    val intake by lazy { Intake(context, projects) }
    val decoder by lazy { Decoder() }
    val builder by lazy { Rebuilder(context, toolchain) }
    val installer by lazy { Installer(context) }
    val reporter by lazy { Report() }
    val mock by lazy { MockServer() }
    val probe by lazy { ScreenProbe }
    val rounds by lazy { RoundMonitor(context) }
    val watcher by lazy { WatcherSetup }
    val pipeline by lazy {
        Pipeline(
            store = projects,
            settings = settings,
            decoder = decoder,
            scanner = scanner,
            integrity = integrity,
            billing = billing,
            actions = actions,
            repairs = repairs,
            renamer = renamer,
            builder = builder,
            reporter = reporter
        )
    }
    val loop by lazy { RepairLoop(context, installer, rounds, pipeline) }
}
