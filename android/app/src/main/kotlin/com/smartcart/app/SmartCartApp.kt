package com.smartcart.app

import android.app.Application
import com.smartcart.app.automation.TestStoreBinding

class SmartCartApp : Application() {
    /** Replaced only by device tests that point the app at a backend (BackendEndToEndTest). */
    @set:androidx.annotation.VisibleForTesting
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            this, AssetFixtureSource(this),
            backendUrl = BuildConfig.BACKEND_URL.ifBlank { null },
            // The accessibility service exists only in debug builds (D-027), and so does the Test Store binding.
            testStore = if (BuildConfig.DEBUG) TestStoreBinding(this) else null,
        )
    }
}
