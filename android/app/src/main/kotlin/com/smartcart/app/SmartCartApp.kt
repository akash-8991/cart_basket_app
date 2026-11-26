package com.smartcart.app

import android.app.Application

class SmartCartApp : Application() {
    /** Replaced only by device tests that point the app at a backend (BackendEndToEndTest). */
    @set:androidx.annotation.VisibleForTesting
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, AssetFixtureSource(this), backendUrl = BuildConfig.BACKEND_URL.ifBlank { null })
    }
}
