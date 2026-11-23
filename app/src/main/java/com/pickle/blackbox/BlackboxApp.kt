package com.pickle.blackbox

import android.app.Application
import com.pickle.blackbox.ads.AdManager
import com.pickle.blackbox.media.ClipStorage
import com.pickle.blackbox.settings.AppSettings
import com.pickle.blackbox.util.AppLanguage

/**
 * Holds the process-wide singletons so the activities and the recording service share one
 * storage layer and one ad manager (AdMob should only be initialised once per process).
 */
class BlackboxApp : Application() {

    lateinit var settings: AppSettings
        private set

    lateinit var clipStorage: ClipStorage
        private set

    lateinit var adManager: AdManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        // Apply the user's language choice before any screen is created.
        runCatching { AppLanguage.apply(settings.languageTag) }
        clipStorage = ClipStorage(this)
        adManager = AdManager(this)
        adManager.initialize()
        clipStorage.purgeTempSegments()
    }
}
