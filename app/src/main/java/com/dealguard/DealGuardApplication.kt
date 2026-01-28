package com.dealguard

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DealGuardApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // App initialization
    }
}
