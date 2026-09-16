package com.pinotrouge.messaging

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.pinotrouge.messaging.work.WorkScheduler
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PinotRougeApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Enqueue after Hilt injects workerFactory (super.onCreate).
        WorkScheduler.enqueueAll(this)
        // Debuggable builds also fire a one-shot commit so device verification
        // does not wait a full day for the periodic window.
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) {
            WorkScheduler.enqueueQuarantineCommitNow(this)
        }
    }
}
