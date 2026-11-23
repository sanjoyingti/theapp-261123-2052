package com.arin.bypass

import android.app.Application
import com.arin.bypass.data.AppContainer

class BypassApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
