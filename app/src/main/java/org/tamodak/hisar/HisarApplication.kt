package org.tamodak.hisar

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.tamodak.hisar.data.repository.FilterRepository
import javax.inject.Inject

@HiltAndroidApp
class HisarApplication : Application() {
    @Inject lateinit var filters: FilterRepository

    override fun onCreate() {
        super.onCreate()
        // The lists take a moment to read; start now so they are ready by the first request.
        filters.preload()
    }
}
