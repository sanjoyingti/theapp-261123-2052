package com.arin.bypass.data

import android.content.Context
import com.arin.bypass.engine.Actions
import com.arin.bypass.engine.Billing
import com.arin.bypass.engine.Decoder
import com.arin.bypass.engine.Installer
import com.arin.bypass.engine.Intake
import com.arin.bypass.engine.Integrity
import com.arin.bypass.engine.ManifestRepair
import com.arin.bypass.engine.Patcher
import com.arin.bypass.engine.Pipeline
import com.arin.bypass.engine.Rebuilder
import com.arin.bypass.engine.Renamer
import com.arin.bypass.engine.RepairLoop
import com.arin.bypass.engine.Report
import com.arin.bypass.engine.Scanner
import com.arin.bypass.engine.Toolchain

class AppContainer(val context: Context) {

    val settings = SettingsStore(context)
    val projects = ProjectStore(context)
    val toolchain = Toolchain(context)
    val patcher = Patcher(projects)
    val scanner = Scanner()
    val integrity = Integrity(context, patcher)
    val billing = Billing(patcher)
    val actions = Actions(patcher)
    val renamer = Renamer(patcher)
    val repairs = ManifestRepair(patcher)
    val intake = Intake(context, projects)
    val decoder = Decoder()
    val builder = Rebuilder(context, toolchain)
    val installer = Installer(context)
    val loop = RepairLoop(context, installer)
    val reporter = Report()
    val pipeline = Pipeline(this)
}
