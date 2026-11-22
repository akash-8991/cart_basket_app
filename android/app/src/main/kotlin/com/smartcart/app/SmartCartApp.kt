package com.smartcart.app

import android.app.Application

class SmartCartApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, AssetFixtureSource(this))
    }
}
