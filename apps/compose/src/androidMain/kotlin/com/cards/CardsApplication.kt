package com.dangerfield.cards

import android.app.Application
import com.dangerfield.cards.debug.AndroidStrictModeLog

class CardsApplication : Application() {
    
    lateinit var appComponent: AndroidAppComponent
        private set
    
    override fun onCreate() {
        super.onCreate()
        appComponent = AndroidAppComponent::class.create(this)
        // Armed straight after the graph exists and before any warm-up work, so
        // it also watches the initialization below — exactly where main-thread
        // disk reads like to hide.
        (appComponent.strictModeLog as? AndroidStrictModeLog)?.install()
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
