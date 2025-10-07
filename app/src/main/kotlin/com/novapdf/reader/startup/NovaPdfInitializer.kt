package com.novapdf.reader.startup

import android.content.Context
import androidx.startup.Initializer
import com.novapdf.reader.NovaPdfApp

class NovaPdfInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val application = context.applicationContext as? NovaPdfApp
            ?: throw IllegalStateException(
                "NovaPdfInitializer requires NovaPdfApp as the application context"
            )

        application.beginStartupInitialization()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
