package com.streamprobe.android

import android.app.Application
import android.os.StrictMode
import com.streamprobe.android.data.DebugSettingsRepository

class StreamProbeApplication : Application() {
    lateinit var debugSettings: DebugSettingsRepository
        private set

    override fun onCreate() {
        super.onCreate()
        debugSettings = DebugSettingsRepository(this)

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectActivityLeaks()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build()
            )
        }
    }
}