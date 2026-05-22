package com.dangerfield.cards

import android.app.Application

class CardsApplication : Application() {
    
    lateinit var appComponent: AndroidAppComponent
        private set
    
    override fun onCreate() {
        super.onCreate()
        appComponent = AndroidAppComponent::class.create(this)
        appComponent.telemetry.initialize()
        appComponent.appEventDispatcher
        // Eagerly start tracking the foreground Activity so bindings that
        // need it (e.g. AndroidReviewLauncher) work the moment they're called.
        appComponent.activityProvider
    }
}
