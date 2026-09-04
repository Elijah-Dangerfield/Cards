package com.dangerfield.cards

import android.app.Application

class CardsApplication : Application() {
    
    lateinit var appComponent: AndroidAppComponent
        private set
    
    override fun onCreate() {
        super.onCreate()
        // First thing, so it also watches the initialization below — which is
        // exactly where main-thread disk reads like to hide.
        enableStrictModeForDebugBuilds()
        appComponent = AndroidAppComponent::class.create(this)
        appComponent.telemetry.initialize()
        // Construct every @AutoInit singleton up front (products
        // catalog, profile + avatar warm, AppEventDispatcher's
        // lifecycle attach, …). Resolving the set is what forces
        // construction. App.kt does the same when iOS / Compose
        // launches; Android needs it here in Application.onCreate
        // since some warm work (AppLifecycleObserver attachment)
        // wants to fire before the first Activity.
        appComponent.autoInits
        // Eagerly start tracking the foreground Activity so bindings that
        // need it (e.g. AndroidReviewLauncher) work the moment they're called.
        appComponent.activityProvider
    }
}
