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
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Hand wired dependencies. The constructor only keeps the context, so building this
 * class can never touch disk, preferences or a service. Every member below is built
 * on first use only, and always exactly once.
 */
class AppContainer(val context: Context) {

    val settings: SettingsStore by once { SettingsStore(context) }
    val projects: ProjectStore by once { ProjectStore(context) }
    val bypass: BypassStore by once { BypassStore(context) }
    val toolchain: Toolchain by once { Toolchain(context) }
    val patcher: Patcher by once { Patcher(projects) }
    val scanner: Scanner by once { Scanner() }
    val integrity: Integrity by once { Integrity(context, patcher) }
    val billing: Billing by once { Billing(patcher) }
    val actions: Actions by once { Actions(patcher) }
    val renamer: Renamer by once { Renamer(patcher) }
    val repairs: ManifestRepair by once { ManifestRepair(patcher) }
    val intake: Intake by once { Intake(context, projects) }
    val decoder: Decoder by once { Decoder() }
    val builder: Rebuilder by once { Rebuilder(context, toolchain) }
    val installer: Installer by once { Installer(context) }
    val reporter: Report by once { Report() }
    val mock: MockServer by once { MockServer() }
    val probe: ScreenProbe by once { ScreenProbe }
    val rounds: RoundMonitor by once { RoundMonitor(context) }
    val watcher: WatcherSetup by once { WatcherSetup }
    val pipeline: Pipeline by once {
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
    val loop: RepairLoop by once { RepairLoop(context, installer, rounds, pipeline) }
}

/** Builds its value once, on first read, with double checked locking. */
private class Once<T>(private val make: () -> T) : ReadOnlyProperty<Any?, T> {

    @Volatile
    private var value: Any? = UNSET

    @Suppress("UNCHECKED_CAST")
    override fun getValue(thisRef: Any?, property: KProperty<*>): T {
        val seen = value
        if (seen !== UNSET) return seen as T
        return synchronized(this) {
            val again = value
            if (again !== UNSET) again as T else make().also { value = it }
        }
    }

    private companion object {
        val UNSET = Any()
    }
}

private fun <T> once(make: () -> T): ReadOnlyProperty<Any?, T> = Once(make)
