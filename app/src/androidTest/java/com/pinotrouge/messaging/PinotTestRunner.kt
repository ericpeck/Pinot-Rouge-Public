package com.pinotrouge.messaging

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumented runner so [@dagger.hilt.android.testing.HiltAndroidTest] can
 * install [HiltTestApplication]. Plain non-Hilt tests still run fine under it.
 */
class PinotTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        return super.newApplication(cl, HiltTestApplication::class.java.name, context)
    }
}
