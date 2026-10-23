package com.asawaaah.fil

import android.app.Application
import android.content.ComponentCallbacks2

class FilApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The model takes 1-3 GB: free it as soon as the app is no longer visible.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) container.llm.releaseSoon()
    }
}
