package com.novapdf.reader.startup

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.core.view.doOnPreDraw
import java.util.concurrent.atomic.AtomicBoolean

internal object FirstDrawListener {

    fun register(application: Application, onFirstDraw: () -> Unit) {
        val callbacks = FirstDrawActivityCallbacks(application, onFirstDraw)
        application.registerActivityLifecycleCallbacks(callbacks)
    }

    private class FirstDrawActivityCallbacks(
        private val application: Application,
        private val onFirstDraw: () -> Unit,
    ) : Application.ActivityLifecycleCallbacks {

        private val firstDrawInvoked = AtomicBoolean(false)

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            observeFirstDraw(activity)
        }

        override fun onActivityStarted(activity: Activity) {
            observeFirstDraw(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            observeFirstDraw(activity)
        }

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit

        private fun observeFirstDraw(activity: Activity) {
            if (firstDrawInvoked.get()) {
                return
            }
            val decorView = activity.window?.decorView ?: return
            decorView.doOnPreDraw {
                if (firstDrawInvoked.compareAndSet(false, true)) {
                    decorView.post {
                        application.unregisterActivityLifecycleCallbacks(this)
                        onFirstDraw()
                    }
                }
            }
        }
    }
}
